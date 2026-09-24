"""Test fixtures. No Docker needed — the service is stateless. The service token is set before the
app is imported so the cached Settings pick it up."""

import os

import pytest

os.environ["SERVICE_TOKEN"] = "test-token"
os.environ["LLM_PROVIDER"] = "stub"


@pytest.fixture()
def client():
    from fastapi.testclient import TestClient

    from ai_service.core.config import Settings
    from ai_service.main import create_app

    config = Settings(_env_file=None, llm_provider="stub", service_token="test-token")
    with TestClient(create_app(config)) as test_client:
        yield test_client
