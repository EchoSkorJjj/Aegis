"""Real W3C extraction and log correlation, using an in-memory exporter rather than Zipkin."""

import asyncio
import logging

import httpx
import pytest
from fastapi.testclient import TestClient

from ai_service.core.config import Settings
from ai_service.core.observability import _ids
from ai_service.main import create_app

TRACE = "1234567890abcdef1234567890abcdef"
PARENT = "1234567890abcdef"


def request_logs(caplog):
    return [r for r in caplog.records if "stage=ai.request" in r.getMessage()]


@pytest.fixture
def traced_app(monkeypatch):
    pytest.importorskip("opentelemetry.sdk")
    from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

    exporter = InMemorySpanExporter()
    monkeypatch.setattr("opentelemetry.exporter.zipkin.json.ZipkinExporter", lambda **kw: exporter)
    app = create_app(Settings(_env_file=None, llm_provider="stub", service_token="test-token",
                              tracing_enabled=True))
    return app, exporter


def test_w3c_parent_and_log_ids_match_exported_server_span(traced_app, caplog):
    from opentelemetry.trace import SpanKind

    app, exporter = traced_app
    caplog.set_level(logging.INFO, logger="ai_service")
    with TestClient(app) as client:
        response = client.post("/v1/draft", headers={
            "X-Service-Token": "test-token", "traceparent": f"00-{TRACE}-{PARENT}-01",
        }, json={"finding_id": "f1", "draft_type": "risk_ticket"})
        assert response.status_code == 200
        app.state.tracing_provider.force_flush()
        server = [s for s in exporter.get_finished_spans() if s.kind == SpanKind.SERVER]
        assert len(server) == 1
        assert server[0].context.trace_id == int(TRACE, 16)
        assert server[0].parent.span_id == int(PARENT, 16)
        record = request_logs(caplog)[-1]
        assert record.trace_id == TRACE
        assert record.span_id == f"{server[0].context.span_id:016x}"
        assert "status=200" in record.getMessage()
        assert "test-token" not in str(server[0].attributes)
    assert _ids.get() == ("-", "-")


def test_errors_keep_correlation_without_logging_body_or_raw_unknown_path(client, caplog):
    caplog.set_level(logging.INFO, logger="ai_service")
    client.post("/v1/draft", json={"private": "private-body-value"}, headers={
        "traceparent": f"00-{TRACE}-{PARENT}-01",
    })
    record = request_logs(caplog)[-1]
    assert record.trace_id == TRACE
    assert "status=401" in record.getMessage()
    client.get("/private-path-value", headers={"traceparent": "invalid-trace-value"})
    record = request_logs(caplog)[-1]
    assert record.trace_id != TRACE
    assert len(record.trace_id) == 32
    assert "route=unmatched status=404" in record.getMessage()
    assert "private-body-value" not in caplog.text
    assert "private-path-value" not in " ".join(r.getMessage() for r in request_logs(caplog))
    assert "invalid-trace-value" not in caplog.text


def test_overlapping_requests_do_not_share_log_context(caplog):
    caplog.set_level(logging.INFO, logger="ai_service")
    app = create_app(Settings(_env_file=None, llm_provider="stub", service_token="test-token"))
    arrived = 0

    @app.get("/concurrent")
    async def concurrent():
        nonlocal arrived
        arrived += 1
        while arrived < 2:
            await asyncio.sleep(0)
        return {}

    async def exercise():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            await asyncio.gather(*[
                client.get("/concurrent", headers={"traceparent": f"00-{trace}-{PARENT}-01"})
                for trace in [TRACE, "a" * 32]
            ])

    asyncio.run(exercise())
    assert {r.trace_id for r in request_logs(caplog)} == {TRACE, "a" * 32}
    assert _ids.get() == ("-", "-")


def test_missing_optional_sdk_keeps_request_logging(monkeypatch, caplog):
    import builtins

    original_import = builtins.__import__

    def without_sdk(name, *args, **kwargs):
        if name == "opentelemetry.sdk.resources":
            raise ImportError("tracing extra absent")
        return original_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", without_sdk)
    caplog.set_level(logging.INFO, logger="ai_service")
    app = create_app(Settings(_env_file=None, llm_provider="stub", service_token="test-token",
                              tracing_enabled=True))
    with TestClient(app) as client:
        assert client.get("/v1/ping", headers={"X-Service-Token": "test-token",
                          "traceparent": f"00-{TRACE}-{PARENT}-01"}).status_code == 200
    assert app.state.tracing_provider is None
    assert request_logs(caplog)[-1].trace_id == TRACE
    assert "OpenTelemetry is not installed" in caplog.text


def test_unsampled_parent_preserves_log_ids_without_exporting(traced_app, caplog):
    app, exporter = traced_app
    caplog.set_level(logging.INFO, logger="ai_service")
    with TestClient(app) as client:
        assert client.get("/v1/ping", headers={"X-Service-Token": "test-token",
                          "traceparent": f"00-{TRACE}-{PARENT}-00"}).status_code == 200
        app.state.tracing_provider.force_flush()
        assert exporter.get_finished_spans() == ()
    assert request_logs(caplog)[-1].trace_id == TRACE
