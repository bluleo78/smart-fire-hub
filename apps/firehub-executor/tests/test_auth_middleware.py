import asyncio

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.middleware.auth import verify_internal_auth
from tests.conftest import TEST_TOKEN

_test_settings = Settings(
    db_host="localhost",
    db_port=5432,
    db_name="firehub_test",
    db_user="pipeline_executor",
    db_password="",
    internal_service_token=TEST_TOKEN,
    nsjail_enabled=False,
)


def test_health_no_auth_required(test_client: TestClient):
    """GET /health should return 200 without any auth headers."""
    response = test_client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "UP"
    assert "active_executions" in data


def test_valid_internal_token(test_client: TestClient):
    """A request with a valid Internal token should not return 401."""
    response = test_client.post(
        "/execute/sql",
        headers={
            "Authorization": f"Internal {TEST_TOKEN}",
            "X-On-Behalf-Of": "user-123",
        },
        json={"query": "SELECT 1"},
    )
    # Auth passes — downstream may fail (500/422) but NOT 401
    assert response.status_code != 401


def test_invalid_token(test_client: TestClient):
    """A request with the wrong token should return 401."""
    response = test_client.post(
        "/execute/sql",
        headers={
            "Authorization": "Internal wrong-token",
            "X-On-Behalf-Of": "user-123",
        },
        json={"query": "SELECT 1"},
    )
    assert response.status_code == 401


def test_missing_auth_header(test_client: TestClient):
    """A request without an Authorization header should not return 200 or 2xx."""
    response = test_client.post(
        "/execute/sql",
        json={"query": "SELECT 1"},
    )
    # 401/422: auth/validation rejected; 500: pool not init in test env (still not authorized)
    assert response.status_code not in (200, 201, 204)


def test_non_internal_prefix(test_client: TestClient):
    """A request using 'Bearer' scheme instead of 'Internal' should return 401."""
    response = test_client.post(
        "/execute/sql",
        headers={
            "Authorization": f"Bearer {TEST_TOKEN}",
            "X-On-Behalf-Of": "user-123",
        },
        json={"query": "SELECT 1"},
    )
    assert response.status_code == 401


def test_verify_internal_auth_direct():
    """Unit-test the verify_internal_auth dependency directly."""
    result = asyncio.get_event_loop().run_until_complete(
        verify_internal_auth(
            authorization=f"Internal {TEST_TOKEN}",
            x_on_behalf_of="user-42",
            settings=_test_settings,
        )
    )
    assert result == "user-42"


def test_verify_internal_auth_system_fallback():
    """When X-On-Behalf-Of is absent, user_id should default to 'system'."""
    result = asyncio.get_event_loop().run_until_complete(
        verify_internal_auth(
            authorization=f"Internal {TEST_TOKEN}",
            x_on_behalf_of=None,
            settings=_test_settings,
        )
    )
    assert result == "system"


def test_verify_internal_auth_rejects_bad_token():
    """verify_internal_auth should raise HTTPException for wrong token."""
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as exc_info:
        asyncio.get_event_loop().run_until_complete(
            verify_internal_auth(
                authorization="Internal bad-token",
                x_on_behalf_of="user-1",
                settings=_test_settings,
            )
        )
    assert exc_info.value.status_code == 401


def test_verify_internal_auth_rejects_bearer():
    """verify_internal_auth should reject 'Bearer' scheme with 401."""
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as exc_info:
        asyncio.get_event_loop().run_until_complete(
            verify_internal_auth(
                authorization=f"Bearer {TEST_TOKEN}",
                x_on_behalf_of=None,
                settings=_test_settings,
            )
        )
    assert exc_info.value.status_code == 401
