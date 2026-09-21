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


def test_pre_statements_run_before_query_in_same_transaction():
    # pre-statement(출력 비우기)와 본 쿼리가 같은 트랜잭션에서 순서대로 실행되고,
    # 성공 시 한 번에 커밋되는지 확인한다.
    cursor = MagicMock()
    cursor.rowcount = 1
    conn = make_conn(cursor)

    result = execute_sql(
        'INSERT INTO "data"."out" ("a") SELECT 1',
        conn,
        pre_statements=['DELETE FROM "data"."out"'],
    )

    assert result.success
    executed = [c.args[0] for c in cursor.execute.call_args_list]
    assert executed == [
        'DELETE FROM "data"."out"',
        'INSERT INTO "data"."out" ("a") SELECT 1',
    ]
    conn.commit.assert_called_once()


def test_query_failure_rolls_back_pre_statements():
    # 본 쿼리가 실패하면 이미 실행한 pre-statement 도 같은 트랜잭션이므로 롤백돼야 한다 —
    # 그렇지 않으면 DELETE 만 반영되고 INSERT 는 실패해 출력 테이블이 비게 된다(원자성 붕괴).
    cursor = MagicMock()
    cursor.execute.side_effect = [None, Exception("boom")]
    conn = make_conn(cursor)

    result = execute_sql(
        'INSERT INTO "data"."out" ("a") SELECT 1',
        conn,
        pre_statements=['DELETE FROM "data"."out"'],
    )

    assert not result.success
    conn.rollback.assert_called_once()
    conn.commit.assert_not_called()


def test_pre_statements_with_with_clause_query_still_commits():
    # 본 쿼리가 WITH/SELECT 로 시작해도(예: WITH 를 쓴 MERGE 형태) pre_statements 가 있으면
    # 무조건 쓰기 경로로 취급해 commit 해야 한다. SELECT 로 오판하면 DELETE 로 비운 내용이
    # 커밋되지 않아 사라진다.
    cursor = MagicMock()
    cursor.rowcount = 1
    conn = make_conn(cursor)

    result = execute_sql(
        'WITH src AS (SELECT 1 AS a) INSERT INTO "data"."out" ("a") SELECT a FROM src',
        conn,
        pre_statements=['DELETE FROM "data"."out"'],
    )

    assert result.success
    assert result.rows is None
    conn.commit.assert_called_once()


def test_disallowed_pre_statement_is_rejected_before_execution():
    # 허용되지 않은 pre-statement(조건부 DELETE)는 커서를 열기도 전에 거부돼야 한다.
    cursor = MagicMock()
    conn = make_conn(cursor)

    result = execute_sql(
        "SELECT 1", conn, pre_statements=['DELETE FROM "data"."out" WHERE 1=1']
    )

    assert not result.success
    cursor.execute.assert_not_called()


def test_empty_select_returns_empty_rows():
    cursor = MagicMock()
    cursor.description = [("id",), ("name",)]
    cursor.fetchall.return_value = []
    conn = make_conn(cursor)

    result = execute_sql("SELECT id, name FROM users WHERE id = -1", conn)

    assert result.success is True
    assert result.rows == []
    assert result.row_count == 0
