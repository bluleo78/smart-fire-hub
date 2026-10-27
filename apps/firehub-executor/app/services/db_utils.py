"""Shared database utilities for executor services."""
from __future__ import annotations

import math
from typing import Any, Dict, List

from psycopg2 import sql as pgsql
from psycopg2.extras import execute_values


def set_local_statement_timeout(cursor, seconds: float) -> None:
    """이 트랜잭션에만 걸리는 ``statement_timeout``(밀리초)을 건다 — ``/execute/sql`` 본문·선행 문장과 ``/execute/load`` 적재 공용.

    SET 은 값을 바인딩하지 못하므로 숫자를 ``int`` 밀리초로 고정해 문장에 넣는다(문자열이 끼어들 틈이 없다). LOCAL 이라 커밋·롤백 때
    풀려 풀에 반납된 연결로 새지 않는다.

    소수 초를 받는다(WD-262) — 선행 문장 예산의 「남은 시간」은 소수라서다. 올림해 남은 시간보다 짧게 끊지 않으며, 0 밀리초는 PG 에서
    「제한 없음」이므로 최소 1 밀리초로 올린다(호출부가 예산 소진을 먼저 걸러내지만 방어선으로 둔다). 정수 초는 예전과 같은 값이다.
    """
    # round(…, 6) 은 12.3 * 1000 = 12300.000000000002 같은 부동소수 잡음에 올림이 한 칸 더 올라가는 것을 막는다.
    millis = max(1, math.ceil(round(seconds * 1000, 6)))
    cursor.execute(f"SET LOCAL statement_timeout = {millis}")


def pre_statement_timeout_message(seconds: int) -> str:
    """선행 문장(출력 잠금·비우기)이 합계 상한을 넘겼을 때의 실패 문구 — ``seconds`` 는 설정 상한.

    db-executor ``SqlRunner.preTimeoutMessage`` 와 **글자까지 같아야 한다**(두 실행기 같은 계약, WD-236/WD-262) — api 가 두 백엔드의
    오류를 같은 문구로 사용자에게 보여 준다.
    """
    return f"기존 출력 정리(선행 문장)가 {seconds}초 안에 끝나지 않아 취소했습니다."


def statement_timeout_message(action: str, seconds: int) -> str:
    """우리가 건 문장 제한에 걸렸을 때의 실패 문구 「{action} 시간이 N초를 넘어 취소했습니다.」 — ``action`` 은 「실행」(SQL)·「적재」(load).

    db-executor ``SqlRunner.timeoutMessage``·``LoadRunner.timeoutMessage`` 와 같은 모양이다. 시간 초과인지 판정은 호출부가
    ``query_executor.is_statement_timeout`` 으로 한다(WD-261) — 판정에는 그 문장의 시작 시각·제한이 필요하고, 시간 초과면 응답의
    구조화 오류도 비워야 해서(db-executor 와 같은 값) 문구만 여기서 만든다.
    """
    return f"{action} 시간이 {seconds}초를 넘어 취소했습니다."


def build_insert_query(cur, table_name: str, columns: List[str]) -> str:
    """``INSERT INTO "table" ("c1", …) VALUES %s`` 문장을 조립한다(식별자는 인용). ``execute_values`` 가 ``%s`` 자리에 행을 채운다.

    여러 페이지를 같은 문장 모양으로 보내는 호출자(``load_executor``)는 한 번만 조립해 :func:`execute_insert_page` 에 넘긴다."""
    query = pgsql.SQL("INSERT INTO {table} ({cols}) VALUES %s").format(
        table=pgsql.Identifier(table_name),
        cols=pgsql.SQL(", ").join([pgsql.Identifier(c) for c in columns]),
    )
    return query.as_string(cur)


def execute_insert_page(
    cur, query: str, columns: List[str], rows: List[Dict[str, Any]], page_size: int = 100
) -> None:
    """조립된 INSERT 문장으로 ``rows`` 를 보낸다. ``execute_values`` 가 ``page_size`` 행씩 나눠 문장을 보내므로, ``rows`` 가
    ``page_size`` 이하이면 문장 하나다. 기본값 100 은 ``execute_values`` 자신의 기본값과 같다."""
    execute_values(cur, query, [tuple(row[c] for c in columns) for row in rows], page_size=page_size)


def insert_batch(conn, table_name: str, rows: List[Dict[str, Any]]) -> None:
    """Batch-insert rows into target table using execute_values."""
    if not rows:
        return

    columns = list(rows[0].keys())
    with conn.cursor() as cur:
        execute_insert_page(cur, build_insert_query(cur, table_name, columns), columns, rows)
