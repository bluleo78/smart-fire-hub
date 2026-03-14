from unittest.mock import MagicMock, patch

import pytest

from app.services.sql_executor import execute_sql
from app.schemas.responses import SqlExecuteResponse


def make_conn(cursor_mock):
    conn = MagicMock()
    conn.cursor.return_value.__enter__ = MagicMock(return_value=cursor_mock)
    conn.cursor.return_value.__exit__ = MagicMock(return_value=False)
    return conn


def test_select_returns_rows_and_columns():
    cursor = MagicMock()
    cursor.description = [("id",), ("name",)]
    cursor.fetchall.return_value = [(1, "Alice"), (2, "Bob")]
    conn = make_conn(cursor)

    result = execute_sql("SELECT id, name FROM users", conn)

    assert result.success is True
    assert result.columns == ["id", "name"]
    assert result.rows == [{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]
    assert result.row_count == 2
    conn.commit.assert_not_called()


def test_dml_commits_and_returns_rowcount():
    cursor = MagicMock()
    cursor.rowcount = 3
    conn = make_conn(cursor)

    result = execute_sql("UPDATE users SET active = true WHERE id > 0", conn)

    assert result.success is True
    assert result.row_count == 3
    assert result.rows is None
    conn.commit.assert_called_once()


def test_blocked_keyword_returns_error():
    conn = MagicMock()

    result = execute_sql("DROP TABLE users", conn)

    assert result.success is False
    assert result.error is not None
    assert "DROP" in result.error
    conn.cursor.assert_not_called()


def test_db_error_rollbacks():
    cursor = MagicMock()
    cursor.execute.side_effect = Exception("DB connection lost")
    conn = make_conn(cursor)

    result = execute_sql("SELECT * FROM users", conn)

    assert result.success is False
    assert "DB connection lost" in result.error
    conn.rollback.assert_called_once()


def test_empty_select_returns_empty_rows():
    cursor = MagicMock()
    cursor.description = [("id",), ("name",)]
    cursor.fetchall.return_value = []
    conn = make_conn(cursor)

    result = execute_sql("SELECT id, name FROM users WHERE id = -1", conn)

    assert result.success is True
    assert result.rows == []
    assert result.row_count == 0
