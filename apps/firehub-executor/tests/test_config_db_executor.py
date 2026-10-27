"""db-executor 연결 설정(스펙 §3.4): 적재 토큰·주소를 환경변수로 읽고, Tibero 접속 설정은 더 이상 없다."""
from unittest.mock import MagicMock, patch

import pytest
from pydantic import ValidationError

from app.config import Settings
from app.db.backends import backend_for
from app.db.store_client import StoreLookupError
from tests.conftest import get_test_settings


def test_db_executor_settings_load_from_environment(monkeypatch):
    monkeypatch.setenv("EXECUTOR_DB_EXECUTOR_BASE_URL", "http://db-executor:8090")
    monkeypatch.setenv("EXECUTOR_DB_EXECUTOR_LOAD_TOKEN", "load-secret")
    s = get_test_settings(_env_file=None)
    assert s.db_executor_base_url == "http://db-executor:8090"
    assert s.db_executor_load_token == "load-secret"


def test_db_executor_settings_default_to_unset(monkeypatch):
    monkeypatch.delenv("EXECUTOR_DB_EXECUTOR_BASE_URL", raising=False)
    monkeypatch.delenv("EXECUTOR_DB_EXECUTOR_LOAD_TOKEN", raising=False)
    s = get_test_settings(_env_file=None)
    assert s.db_executor_base_url == "" and s.db_executor_load_token == ""


def test_pg_statement_timeout_defaults_load_from_environment(monkeypatch):
    # 운영자가 두 PG 문장 제한을 독립적으로 조정할 수 있어야 한다.
    monkeypatch.setenv("EXECUTOR_SQL_STATEMENT_TIMEOUT_SECONDS", "42")
    monkeypatch.setenv("EXECUTOR_LOAD_STATEMENT_TIMEOUT_SECONDS", "90")
    s = get_test_settings(_env_file=None)
    assert s.sql_statement_timeout_seconds == 42
    assert s.load_statement_timeout_seconds == 90


@pytest.mark.parametrize("field", ["sql_statement_timeout_seconds", "load_statement_timeout_seconds"])
def test_pg_statement_timeout_config_rejects_zero(field):
    # 0밀리초는 PG 에서 제한 해제이므로 잘못된 설정을 기동 전에 거부한다.
    with pytest.raises(ValidationError):
        get_test_settings(_env_file=None, **{field: 0})


def test_tibero_odbc_settings_are_gone():
    assert not [name for name in Settings.model_fields if name.startswith("tibero_")]


def test_backend_for_refuses_tibero_instead_of_crashing(mock_settings):
    # ODBC 백엔드가 없어도 Tibero 테넌트는 PG 로 흘러가지 않고 한국어 오류로 닫힌다.
    client = MagicMock()
    client.get.return_value = MagicMock(backend="TIBERO")
    with patch("app.db.backends.get_store_client", return_value=client):
        with pytest.raises(StoreLookupError, match="db-executor"):
            backend_for(7, mock_settings)
