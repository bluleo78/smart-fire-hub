import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import app


TEST_TOKEN = "test-internal-token-secret"
# Java 쪽 application-test.yml 의 app.pipeline.role-password-secret 과 같은 값 —
# 두 언어의 비밀번호 파생이 일치함을 이 값으로 검증한다.
TEST_ROLE_SECRET = "test-tenant-pipeline-secret"


def get_test_settings(**overrides) -> Settings:
    """테스트용 Settings. 기본값 위에 ``overrides`` 를 덮어쓴다(예: ``_env_file=None`` 으로 .env 무시)."""
    values = dict(
        db_host="localhost",
        db_port=5432,
        db_name="firehub_test",
        db_user="pipeline_executor",
        db_password="",
        internal_service_token=TEST_TOKEN,
        role_password_secret=TEST_ROLE_SECRET,
        nsjail_enabled=False,
        # 기본값(서비스명 api)은 테스트 머신에서 DNS 조회로 느려질 수 있어, 즉시 거부되는 루프백 discard 포트를 쓴다.
        api_base_url="http://127.0.0.1:9",
    )
    values.update(overrides)
    return Settings(**values)


def _settings_dependency() -> Settings:
    """FastAPI 의존성 오버라이드용 무인자 래퍼 — **overrides 가 쿼리 파라미터로 오인되지 않게 한다."""
    return get_test_settings()


@pytest.fixture(autouse=True)
def override_settings():
    app.dependency_overrides[get_settings] = _settings_dependency
    yield
    app.dependency_overrides.clear()


@pytest.fixture
def test_client() -> TestClient:
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def mock_settings() -> Settings:
    return get_test_settings()


@pytest.fixture
def mock_auth_header() -> dict:
    return {
        "Authorization": f"Internal {TEST_TOKEN}",
        "X-On-Behalf-Of": "user-123",
    }
