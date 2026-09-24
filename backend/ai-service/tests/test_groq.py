"""Exercise the real adapter and generation pipeline through an offline HTTP transport."""

import asyncio
import json
import logging
from datetime import UTC, datetime, timedelta
from email.utils import format_datetime

import httpx
import pytest
from fastapi.testclient import TestClient

from ai_service.core.config import Settings
from ai_service.llm.groq import GroqLlmClient, retry_after_seconds
from ai_service.llm.metrics import ATTEMPTS, REQUESTS, TOKENS
from ai_service.main import create_app

TOKEN = {"X-Service-Token": "test-token"}
PAYLOAD = {
    "finding_id": "opaque-finding",
    "advisory_text": "Fictional advisory: example-lib before 2.0 is affected.",
    "service_context": "Fictional service uses example-lib 1.0.",
}
VALID_OUTPUT = {
    "material": True,
    "rationale": "The supplied advisory applies to the example service.",
    "suggested_severity": "HIGH",
    "cited_passage_ids": ["A1", "S1"],
}


def completion(content=None, *, finish_reason="stop"):
    return httpx.Response(200, json={
        "choices": [{"finish_reason": finish_reason, "message": {
            "content": json.dumps(VALID_OUTPUT) if content is None else content,
        }}],
        "usage": {"prompt_tokens": 123, "completion_tokens": 45},
    })


@pytest.fixture
def make_client():
    clients = []

    def make(handler, **overrides):
        config = Settings(
            _env_file=None, llm_provider="groq", groq_api_key="offline-test-credential",
            service_token="test-token", **overrides,
        )
        http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        adapter = GroqLlmClient(config, http=http)
        client = TestClient(create_app(config, llm_client=adapter))
        client.__enter__()
        clients.append(client)
        return client, http

    yield make
    for client in reversed(clients):
        client.__exit__(None, None, None)


@pytest.fixture
def retry_delays(monkeypatch):
    delays = []

    async def sleep(delay):
        delays.append(delay)

    monkeypatch.setattr("ai_service.llm.structured.asyncio.sleep", sleep)
    return delays


def assert_error(response, status):
    assert response.status_code == status
    assert set(response.json()) == {"timestamp", "status", "error", "message", "path"}
    assert response.json()["status"] == status
    assert response.json()["path"] == "/v1/assess"


def test_request_contract_usage_metrics_and_safe_logging(make_client, caplog):
    calls = []

    def handler(request):
        calls.append(request)
        return completion()

    labels = ("groq", "openai/gpt-oss-120b")
    input_before = TOKENS.labels(*labels, "input")._value.get()
    output_before = TOKENS.labels(*labels, "output")._value.get()
    attempts_before = ATTEMPTS.labels(*labels, "assess")._value.get()
    requests_before = REQUESTS.labels(*labels, "assess", "assess.v1", "success")._value.get()
    client, http = make_client(handler)
    with caplog.at_level(logging.INFO):
        response = client.post("/v1/assess", headers=TOKEN, json=PAYLOAD)
    assert response.status_code == 200
    assert response.json()["citations"] == [PAYLOAD["advisory_text"], PAYLOAD["service_context"]]
    assert len(calls) == 1
    request = calls[0]
    assert str(request.url) == "https://api.groq.com/openai/v1/chat/completions"
    assert request.headers["authorization"] == "Bearer offline-test-credential"
    body = json.loads(request.content)
    assert body["model"] == labels[1]
    assert body["max_completion_tokens"] == 1000
    assert body["temperature"] == 0.1
    assert body["reasoning_effort"] == "low"
    assert body["include_reasoning"] is False
    assert body["stream"] is False
    assert [message["role"] for message in body["messages"]] == ["system", "user"]
    assert body["response_format"]["type"] == "json_schema"
    spec = body["response_format"]["json_schema"]
    assert spec["strict"] is True
    assert spec["schema"]["additionalProperties"] is False
    assert set(spec["schema"]["required"]) == set(VALID_OUTPUT)
    assert TOKENS.labels(*labels, "input")._value.get() - input_before == 123
    assert TOKENS.labels(*labels, "output")._value.get() - output_before == 45
    assert ATTEMPTS.labels(*labels, "assess")._value.get() - attempts_before == 1
    assert REQUESTS.labels(*labels, "assess", "assess.v1", "success")._value.get() - (
        requests_before
    ) == 1
    assert "template=assess.v1" in caplog.text
    assert "offline-test-credential" not in caplog.text
    assert PAYLOAD["advisory_text"] not in caplog.text
    assert VALID_OUTPUT["rationale"] not in caplog.text
    assert "aegis_llm_duration_seconds_count" in client.get("/actuator/prometheus").text
    # Application lifespan owns the HTTP adapter.
    assert not http.is_closed


@pytest.mark.parametrize("status", [429, 500, 503])
def test_transient_status_retries_with_retry_after(make_client, retry_delays, status):
    responses = iter([httpx.Response(status, headers={"Retry-After": "2"}), completion()])
    client, _ = make_client(lambda request: next(responses))
    assert client.post("/v1/assess", headers=TOKEN, json=PAYLOAD).status_code == 200
    assert retry_delays == [2]


@pytest.mark.parametrize("status", [429, 500, 503])
def test_transient_status_stops_after_two_retries(make_client, retry_delays, status):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(status, text="sensitive-provider-error")

    client, _ = make_client(handler)
    response = client.post("/v1/assess", headers=TOKEN, json=PAYLOAD)
    assert_error(response, 503)
    assert len(calls) == 3
    assert retry_delays == [0.25, 0.5]
    assert "sensitive-provider-error" not in response.text


@pytest.mark.parametrize("status", [301, 307, 400, 401, 403, 404, 422])
def test_other_statuses_never_retry_or_follow_redirects(make_client, retry_delays, caplog, status):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(
            status, text="sensitive-provider-error", headers={"Location": "https://other.invalid"},
        )

    client, _ = make_client(handler)
    with caplog.at_level(logging.INFO):
        response = client.post("/v1/assess", headers=TOKEN, json=PAYLOAD)
    assert_error(response, 503)
    assert len(calls) == 1
    assert retry_delays == []
    for sensitive in ("sensitive-provider-error", "offline-test-credential"):
        assert sensitive not in response.text + caplog.text


@pytest.mark.parametrize("bad_response", [
    lambda: httpx.Response(200, text="not-json"),
    lambda: httpx.Response(200, json={"choices": []}),
    lambda: completion("not-json"),
    lambda: completion(json.dumps({**VALID_OUTPUT, "material": "true"})),
    lambda: completion(json.dumps({**VALID_OUTPUT, "suggested_severity": "INVALID"})),
    lambda: completion(json.dumps({**VALID_OUTPUT, "unexpected": "data"})),
    lambda: completion(finish_reason="length"),
])
def test_invalid_output_retries_once_then_returns_502(make_client, bad_response):
    calls = []

    def handler(request):
        calls.append(request)
        return bad_response()

    client, _ = make_client(handler)
    response = client.post("/v1/assess", headers=TOKEN, json=PAYLOAD)
    assert_error(response, 502)
    assert len(calls) == 2


def test_invalid_json_can_recover_on_validation_retry(make_client):
    responses = iter([completion("not-json"), completion()])
    client, _ = make_client(lambda request: next(responses))
    assert client.post("/v1/assess", headers=TOKEN, json=PAYLOAD).status_code == 200


@pytest.mark.parametrize("error", [httpx.ReadTimeout, httpx.ConnectError])
def test_transport_failure_returns_safe_503_without_retry(make_client, error):
    calls = []

    def handler(request):
        calls.append(request)
        raise error("sensitive transport details", request=request)

    client, _ = make_client(handler)
    response = client.post("/v1/assess", headers=TOKEN, json=PAYLOAD)
    assert_error(response, 503)
    assert len(calls) == 1
    assert "sensitive transport details" not in response.text


def test_overall_deadline_cancels_slow_provider(make_client):
    cancelled = []

    async def handler(request):
        try:
            await asyncio.sleep(10)
        except asyncio.CancelledError:
            cancelled.append(True)
            raise
        return completion()

    client, _ = make_client(handler, llm_timeout_seconds=0.05)
    response = client.post("/v1/assess", headers=TOKEN, json=PAYLOAD)
    assert_error(response, 503)
    assert "timed out" in response.json()["message"]
    assert cancelled == [True]


def test_overall_deadline_also_caps_retry_after_wait(make_client):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(429, headers={"Retry-After": "3600"})

    client, _ = make_client(handler, llm_timeout_seconds=0.05)
    response = client.post("/v1/assess", headers=TOKEN, json=PAYLOAD)
    assert_error(response, 503)
    assert "timed out" in response.json()["message"]
    assert len(calls) == 1


@pytest.mark.parametrize("limit", [
    {"llm_daily_token_budget": 1}, {"llm_max_tokens_per_minute": 1},
])
def test_budget_rejects_request_before_provider_is_called(make_client, limit):
    calls = []

    def handler(request):
        calls.append(request)
        return completion()

    client, _ = make_client(handler, **limit)
    assert_error(client.post("/v1/assess", headers=TOKEN, json=PAYLOAD), 503)
    assert calls == []


def test_retry_is_admitted_through_request_limiter(make_client, retry_delays):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(429)

    client, _ = make_client(handler, llm_max_requests_per_minute=1)
    response = client.post("/v1/assess", headers=TOKEN, json=PAYLOAD)
    assert_error(response, 503)
    assert "request rate limit" in response.json()["message"]
    assert len(calls) == 1


@pytest.mark.parametrize("value,expected", [
    (None, None), ("", None), ("nonsense", None), ("nan", None), ("inf", None),
    ("-5", 0), ("1.5", 1.5),
])
def test_retry_after_parsing(value, expected):
    assert retry_after_seconds(value) == expected


def test_retry_after_accepts_http_date():
    future = datetime.now(UTC) + timedelta(seconds=30)
    assert 28 <= retry_after_seconds(format_datetime(future, usegmt=True)) <= 30


def test_shutdown_closes_provider_connection():
    config = Settings(_env_file=None, groq_api_key="offline-test-credential")
    http = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: completion()))
    with TestClient(create_app(config, llm_client=GroqLlmClient(config, http=http))):
        assert not http.is_closed
    assert http.is_closed
