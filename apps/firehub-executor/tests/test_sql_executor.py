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
    # 출력 테이블 직렬화 잠금(#731)이 DELETE **앞에, 별도 문장으로** 실행돼야 한다 — 같은 문장이거나
    # DELETE 뒤라면 겹친 실행의 미커밋 행을 지우지 못해 행이 중복된다. 키는 스키마까지 포함한다.
    assert cursor.execute.call_args_list[0].args == (
        "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
        ('"data"."out"',),
    )
    executed = [c.args[0] for c in cursor.execute.call_args_list]
    assert executed[1:] == [
        'DELETE FROM "data"."out"',
        'INSERT INTO "data"."out" ("a") SELECT 1',
    ]
    conn.commit.assert_called_once()


def test_output_lock_key_is_scoped_to_tenant_schema():
    # advisory 키 공간은 DB 전체가 공유한다 — 같은 테이블명이라도 테넌트 스키마가 다르면 키가 달라야
    # 서로 무관한 테넌트의 실행이 직렬화되지 않는다.
    cursor = MagicMock()
    cursor.rowcount = 1
    conn = make_conn(cursor)

    execute_sql(
        'INSERT INTO "data_t7"."out" ("a") SELECT 1',
        conn,
        pre_statements=['DELETE FROM "data_t7"."out"'],
    )

    assert cursor.execute.call_args_list[0].args[1] == ('"data_t7"."out"',)


def test_no_lock_without_pre_statements():
    # 선행 문장이 없는 실행(APPEND·MERGE)은 잠금을 잡지 않는다. 사용자가 직접 쓴 DML 도 REPLACE 면
    # API 가 출력 비우기 선행 문장을 함께 보내므로(#735) 위 선행 문장 테스트와 같은 경로를 탄다.
    cursor = MagicMock()
    cursor.rowcount = 1
    conn = make_conn(cursor)

    execute_sql('INSERT INTO "data"."out" ("a") SELECT 1', conn)

    assert [c.args[0] for c in cursor.execute.call_args_list] == [
        'INSERT INTO "data"."out" ("a") SELECT 1'
    ]


def test_user_dml_update_with_pre_statement_locks_clears_then_commits_once():
    # #735 — 사용자가 직접 쓴 비SELECT DML(여기서는 UPDATE)도 REPLACE 면 비우기 선행 문장과 함께 온다.
    # 잠금 → 비우기 → 사용자 DML 이 한 트랜잭션에서 순서대로 돌고 한 번만 커밋돼야, 겹친 실행이
    # 서로의 비우기와 적재 사이에 끼어들지 못한다. 결과 행을 읽으려 하지 않는다(SELECT 로 오판 금지).
    cursor = MagicMock()
    cursor.rowcount = 3
    conn = make_conn(cursor)

    result = execute_sql(
        'UPDATE "data"."out" SET "a" = 1',
        conn,
        pre_statements=['DELETE FROM "data"."out"'],
    )

    assert result.success
    assert result.row_count == 3
    assert [c.args[0] for c in cursor.execute.call_args_list] == [
        "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
        'DELETE FROM "data"."out"',
        'UPDATE "data"."out" SET "a" = 1',
    ]
    cursor.fetchall.assert_not_called()
    conn.commit.assert_called_once()


def test_query_failure_rolls_back_pre_statements():
    # 본 쿼리가 실패하면 이미 실행한 pre-statement 도 같은 트랜잭션이므로 롤백돼야 한다 —
    # 그렇지 않으면 DELETE 만 반영되고 INSERT 는 실패해 출력 테이블이 비게 된다(원자성 붕괴).
    cursor = MagicMock()
    # 실행 순서: 직렬화 잠금 → DELETE → 본 쿼리(실패)
    cursor.execute.side_effect = [None, None, Exception("boom")]
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
