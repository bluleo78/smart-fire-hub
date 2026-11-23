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
    role_password_secret="test-tenant-pipeline-secret",
    nsjail_enabled=False,
)


# lifespan 이 돌지 않는 TestClient 라 DB 풀이 준비되지 않으므로, 풀 상태를 덮어
# 인증 없이 열린 /health 의 정상 응답만 본다 (WD-1052).
def test_health_no_auth_required(test_client: TestClient, monkeypatch):
    """GET /health should return 200 without any auth headers."""
    monkeypatch.setattr("app.routers.health.is_pool_ready", lambda: True)
    response = test_client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "UP"
    assert "active_executions" in data


# lifespan 이 돌지 않는 TestClient 라 풀 상태를 덮어 풀 미준비 때의 503/DOWN 을 보고,
# 그때도 인증 없이 열려 있음(401 아님)을 함께 확인한다 (WD-1052).
def test_health_reports_down_without_pool_and_no_auth(test_client: TestClient, monkeypatch):
    """GET /health without pool returns 503 DOWN, not 401."""
    monkeypatch.setattr("app.routers.health.is_pool_ready", lambda: False)
    response = test_client.get("/health")
    assert response.status_code == 503
    assert response.json()["status"] == "DOWN"


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
    # Authorization 은 필수 헤더라 FastAPI 가 핸들러(풀 사용) 전에 422 로 거절한다.
    # 「2xx 아님」만 보면 인증이 풀려 풀 미준비 500 이 나도 통과하므로, 422 와 빠진 헤더 위치까지 본다 (WD-1052).
    assert response.status_code == 422
    assert any(
        e["type"] == "missing" and e["loc"] == ["header", "Authorization"]
        for e in response.json()["detail"]
    )


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


# Python 3.14 는 실행 중 루프 없이 asyncio.get_event_loop() 가 오류라, async 시험으로 직접 await 한다(asyncio_mode=auto, WD-1052).
async def test_verify_internal_auth_direct():
    """Unit-test the verify_internal_auth dependency directly."""
    result = await verify_internal_auth(
        authorization=f"Internal {TEST_TOKEN}",
        x_on_behalf_of="user-42",
        settings=_test_settings,
    )
    assert result == "user-42"


# X-On-Behalf-Of 가 없으면 행위자를 "system" 으로 본다.
async def test_verify_internal_auth_system_fallback():
    """When X-On-Behalf-Of is absent, user_id should default to 'system'."""
    result = await verify_internal_auth(
        authorization=f"Internal {TEST_TOKEN}",
        x_on_behalf_of=None,
        settings=_test_settings,
    )
    assert result == "system"


# 틀린 토큰은 401 — 예외가 잡히도록 pytest.raises 안에서 await 한다 (WD-1052).
async def test_verify_internal_auth_rejects_bad_token():
    """verify_internal_auth should raise HTTPException for wrong token."""
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as exc_info:
        await verify_internal_auth(
            authorization="Internal bad-token",
            x_on_behalf_of="user-1",
            settings=_test_settings,
        )
    assert exc_info.value.status_code == 401


# Internal 이 아닌 Bearer 체계는 401 로 거절한다.
async def test_verify_internal_auth_rejects_bearer():
    """verify_internal_auth should reject 'Bearer' scheme with 401."""
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as exc_info:
        await verify_internal_auth(
            authorization=f"Bearer {TEST_TOKEN}",
            x_on_behalf_of=None,
            settings=_test_settings,
        )
    assert exc_info.value.status_code == 401
