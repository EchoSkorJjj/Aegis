"""Test fixtures. No Docker needed — the service is stateless. The service token is set before the
app is imported so the cached Settings pick it up."""

import os

import pytest

os.environ.setdefault("SERVICE_TOKEN", "test-token")


@pytest.fixture()
def client():
    from fastapi.testclient import TestClient

    from ai_service.main import app

    with TestClient(app) as test_client:
        yield test_client
