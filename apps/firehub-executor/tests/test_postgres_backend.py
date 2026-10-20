"""PostgresBackend 가 기존 함수에 인자를 그대로 위임하는지 검증한다."""
from unittest.mock import MagicMock, patch

from app.db.backends.postgres import PostgresBackend


def test_kind():
    assert PostgresBackend(MagicMock()).kind == "POSTGRESQL"


def test_connection_delegates():
    settings = MagicMock()
    with patch("app.db.backends.postgres.get_connection") as gc:
        backend = PostgresBackend(settings)
        with backend.connection(3):
            pass
    gc.assert_called_once_with(3, settings)


def test_insert_batch_delegates():
    conn, rows = object(), [{"a": 1}]
    with patch("app.db.backends.postgres.insert_batch") as ib:
        PostgresBackend(MagicMock()).insert_batch(conn, "t", rows)
    ib.assert_called_once_with(conn, "t", rows)


def test_execute_query_delegates_and_returns_same_object():
    conn, sentinel = object(), object()
    with patch("app.db.backends.postgres.execute_query", return_value=sentinel) as eq:
        out = PostgresBackend(MagicMock()).execute_query("select 1", 10, True, conn, tenant_id=3)
    eq.assert_called_once_with("select 1", 10, True, conn, tenant_id=3)
    assert out is sentinel
