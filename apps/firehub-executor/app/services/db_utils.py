"""Shared database utilities for executor services."""
from __future__ import annotations

from typing import Any, Dict, List

from psycopg2 import sql as pgsql
from psycopg2.extras import execute_values

from app.services.query_executor import is_statement_timeout


def set_local_statement_timeout(cursor, seconds: int) -> None:
    """이 트랜잭션에만 걸리는 ``statement_timeout``(밀리초)을 건다 — ``/execute/sql`` 본문과 ``/execute/load`` 적재 공용.

    SET 은 값을 바인딩하지 못하므로 검증된 정수(요청 스키마·설정이 1 이상 정수만 받는다)를 ``int()`` 로 한 번 더 고정해 문장에
    넣는다. LOCAL 이라 커밋·롤백 때 풀려 풀에 반납된 연결로 새지 않는다.
    """
    cursor.execute(f"SET LOCAL statement_timeout = {int(seconds) * 1000}")


def statement_timeout_message(exc: BaseException, action: str, seconds: int) -> str:
    """실패 문구 — 우리가 건 문장 제한에 걸렸으면 「{action} 시간이 N초를 넘어 취소했습니다.」, 아니면 원문.

    관리자 취소 등 다른 57014 는 원문을 둔다(``is_statement_timeout``). ``action`` 은 「실행」(SQL)·「적재」(load).
    """
    if is_statement_timeout(exc):
        return f"{action} 시간이 {seconds}초를 넘어 취소했습니다."
    return str(exc)


def insert_batch(conn, table_name: str, rows: List[Dict[str, Any]]) -> None:
    """Batch-insert rows into target table using execute_values."""
    if not rows:
        return

    columns = list(rows[0].keys())
    col_identifiers = [pgsql.Identifier(c) for c in columns]

    query = pgsql.SQL("INSERT INTO {table} ({cols}) VALUES %s").format(
        table=pgsql.Identifier(table_name),
        cols=pgsql.SQL(", ").join(col_identifiers),
    )

    values = [tuple(row[c] for c in columns) for row in rows]

    with conn.cursor() as cur:
        execute_values(cur, query.as_string(cur), values)
