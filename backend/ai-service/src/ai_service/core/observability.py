"""Metrics (always) and tracing (optional) — same surface as the Java services.

Prometheus at /actuator/prometheus so one scrape config covers every service. Zipkin tracing is
opt-in (TRACING_ENABLED + the `tracing` extra) and degrades to a warning if OpenTelemetry is absent.
"""

import logging
import re
from contextvars import ContextVar
from secrets import token_hex
from time import perf_counter

from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator

from ai_service.core.config import Settings, settings

log = logging.getLogger(__name__)
_ids: ContextVar[tuple[str, str]] = ContextVar("trace_ids", default=("-", "-"))
_traceparent = re.compile(r"00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})")


class CorrelationFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.trace_id, record.span_id = _ids.get()
        return True


class RequestLoggingMiddleware:
    """Log only fixed route names, status and trace IDs; never headers, bodies or exceptions' text."""

    def __init__(self, app, tracing: bool = False):
        self.app = app
        self.tracing = tracing

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        trace_id, span_id = token_hex(16), token_hex(8)
        headers = dict(scope.get("headers", []))
        incoming = headers.get(b"traceparent", b"").decode("ascii", errors="replace")
        parent = _traceparent.fullmatch(incoming)
        if parent and int(parent[1], 16) and int(parent[2], 16):
            trace_id = parent[1]
        if self.tracing:
            from opentelemetry import trace

            context = trace.get_current_span().get_span_context()
            if context.is_valid:
                trace_id, span_id = f"{context.trace_id:032x}", f"{context.span_id:016x}"
        token = _ids.set((trace_id, span_id))
        status = 500
        started = perf_counter()

        async def respond(message):
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
            await send(message)

        try:
            await self.app(scope, receive, respond)
        finally:
            # The route template is controlled by the application; a raw URL may contain user data.
            route = scope.get("route")
            log.info("pipeline stage=ai.request route=%s status=%s durationMs=%.1f",
                     getattr(route, "path", "unmatched"), status,
                     (perf_counter() - started) * 1000)
            _ids.reset(token)


def setup_observability(app: FastAPI, config: Settings | None = None) -> None:
    config = config or settings
    service_log = logging.getLogger("ai_service")
    service_log.setLevel(logging.INFO)
    if not any(isinstance(f, CorrelationFilter) for h in service_log.handlers for f in h.filters):
        handler = logging.StreamHandler()
        handler.addFilter(CorrelationFilter())
        handler.setFormatter(logging.Formatter(
            "%(levelname)s [traceId=%(trace_id)s spanId=%(span_id)s] %(name)s %(message)s"
        ))
        service_log.addHandler(handler)
    app.add_middleware(RequestLoggingMiddleware, tracing=config.tracing_enabled)
    Instrumentator().instrument(app).expose(
        app, endpoint="/actuator/prometheus", include_in_schema=False
    )
    app.state.tracing_provider = _setup_tracing(app, config) if config.tracing_enabled else None


def _setup_tracing(app: FastAPI, config: Settings):
    try:
        from opentelemetry.exporter.zipkin.json import ZipkinExporter
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
    except ImportError:
        log.warning("TRACING_ENABLED is set but OpenTelemetry is not installed; run: pip install '.[tracing]'")
        # Correlation logging still works without the optional tracing SDK.
        for middleware in app.user_middleware:
            if middleware.cls is RequestLoggingMiddleware:
                middleware.kwargs["tracing"] = False
        return

    provider = TracerProvider(resource=Resource.create({"service.name": config.service_name}))
    provider.add_span_processor(BatchSpanProcessor(ZipkinExporter(endpoint=config.zipkin_endpoint)))
    # App-local providers support test/app factories without trying to replace OTel's global provider.
    FastAPIInstrumentor.instrument_app(app, tracer_provider=provider,
                                     excluded_urls="/actuator/health,/actuator/prometheus")
    log.info("Tracing enabled, exporting spans to %s", config.zipkin_endpoint)
    return provider
