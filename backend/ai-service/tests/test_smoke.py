"""Smoke tests: health/metrics are public; the AI endpoints require the service token and, with it,
return the scaffold's stub payloads. No Docker or API key required."""

TOKEN = {"X-Service-Token": "test-token"}


def test_health_is_public(client):
    response = client.get("/actuator/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_metrics_are_exposed(client):
    assert client.get("/actuator/prometheus").status_code == 200


def test_ping_requires_service_token(client):
    assert client.get("/v1/ping").status_code == 401


def test_ping_with_token(client):
    response = client.get("/v1/ping", headers=TOKEN)
    assert response.status_code == 200
    assert response.json() == {"service": "ai-service", "status": "ok"}


ASSESS = {"finding_id": "f1", "advisory_text": "x", "package": {"ecosystem": "npm", "name": "x"},
        "excerpts": [{"id": "E1", "path": "a.js", "start_line": 1, "text": "require('x')", "kind": "import"}]}


def test_assess_requires_service_token(client):
    response = client.post("/v1/assess", json=ASSESS)
    assert response.status_code == 401


def test_assess_with_token_returns_the_stub_which_can_never_tell(client):
    response = client.post("/v1/assess", headers=TOKEN, json=ASSESS)
    assert response.status_code == 200
    body = response.json()
    # Offline, the stub never claims the code calls (or doesn't call) anything.
    assert body["verdict"] == "CANT_TELL"
    assert body["cited_excerpts"] == []
    # Nor does it invent anything for a reviewer to check.
    assert body["checks"] == []
    assert set(body) == {"verdict", "rationale", "checks", "cited_excerpts"}


def test_draft_with_token_returns_stub(client):
    response = client.post("/v1/draft", headers=TOKEN, json={"finding_id": "f1", "draft_type": "risk_ticket"})
    assert response.status_code == 200
    assert response.json()["draft_type"] == "risk_ticket"
    assert response.json()["ticket"]["actions"], "the offline stub still fills the ticket's required actions"
    assert response.json()["memo"] is None


def test_a_full_minute_budget_is_a_429_with_when_to_retry(client, monkeypatch):
    from ai_service.core.errors import RateLimited

    async def full(*args, **kwargs):
        raise RateLimited("LLM token rate limit reached; try again later", retry_after=42)

    monkeypatch.setattr("ai_service.api.routes.run_assess", full)
    response = client.post("/v1/assess", headers=TOKEN, json=ASSESS)
    assert response.status_code == 429
    assert response.headers["Retry-After"] == "42"
    assert response.json()["error"] == "Too Many Requests"
