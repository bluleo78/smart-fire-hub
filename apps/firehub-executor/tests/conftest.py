import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import app


TEST_TOKEN = "test-internal-token-secret"
# Java 쪽 application-test.yml 의 app.pipeline.role-password-secret 과 같은 값 —
# 두 언어의 비밀번호 파생이 일치함을 이 값으로 검증한다.
TEST_ROLE_SECRET = "test-tenant-pipeline-secret"


def get_test_settings() -> Settings:
    return Settings(
        db_host="localhost",
        db_port=5432,
        db_name="firehub_test",
        db_user="pipeline_executor",
        db_password="",
        internal_service_token=TEST_TOKEN,
        role_password_secret=TEST_ROLE_SECRET,
        nsjail_enabled=False,
    )


@pytest.fixture(autouse=True)
def override_settings():
    app.dependency_overrides[get_settings] = get_test_settings
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
