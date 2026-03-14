import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import app


TEST_TOKEN = "test-internal-token-secret"


def get_test_settings() -> Settings:
    return Settings(
        db_host="localhost",
        db_port=5432,
        db_name="firehub_test",
        db_user="pipeline_executor",
        db_password="",
        internal_service_token=TEST_TOKEN,
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
