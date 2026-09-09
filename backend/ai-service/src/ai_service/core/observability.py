"""Metrics (always) and tracing (optional) — same surface as the Java services.

Prometheus at /actuator/prometheus so one scrape config covers every service. Zipkin tracing is
opt-in (TRACING_ENABLED + the `tracing` extra) and degrades to a warning if OpenTelemetry is absent.
"""

import logging

from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator

from ai_service.core.config import settings

log = logging.getLogger(__name__)


def setup_observability(app: FastAPI) -> None:
    Instrumentator().instrument(app).expose(
        app, endpoint="/actuator/prometheus", include_in_schema=False
    )
    if settings.tracing_enabled:
        _setup_tracing(app)


def _setup_tracing(app: FastAPI) -> None:
    try:
        from opentelemetry import trace
        from opentelemetry.exporter.zipkin.json import ZipkinExporter
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
    except ImportError:
        log.warning("TRACING_ENABLED is set but OpenTelemetry is not installed; run: pip install '.[tracing]'")
        return

    provider = TracerProvider(resource=Resource.create({"service.name": settings.service_name}))
    provider.add_span_processor(BatchSpanProcessor(ZipkinExporter(endpoint=settings.zipkin_endpoint)))
    trace.set_tracer_provider(provider)
    FastAPIInstrumentor.instrument_app(app)
    log.info("Tracing enabled, exporting spans to %s", settings.zipkin_endpoint)
