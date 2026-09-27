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


def test_assess_requires_service_token(client):
    response = client.post("/v1/assess", json={"finding_id": "f1", "advisory_text": "x"})
    assert response.status_code == 401


def test_assess_with_token_returns_stub(client):
    response = client.post("/v1/assess", headers=TOKEN, json={"finding_id": "f1", "advisory_text": "x"})
    assert response.status_code == 200
    body = response.json()
    assert {"material", "rationale", "suggested_severity", "citations"} <= body.keys()


def test_draft_with_token_returns_stub(client):
    response = client.post("/v1/draft", headers=TOKEN, json={"finding_id": "f1", "draft_type": "risk_ticket"})
    assert response.status_code == 200
    assert response.json()["draft_type"] == "risk_ticket"
    assert response.json()["ticket"]["actions"], "the offline stub still fills the ticket's required actions"
    assert response.json()["memo"] is None
