"""행 목록 적재(``/execute/load``, WD-77) — PG(테넌트 롤 연결)용.

db-executor(Tibero) 의 ``/execute/load`` 와 같은 요청·응답 모양이다(``table``·``rows``·``column_type_map``·
``timeout_seconds`` → ``success``·``rows_loaded``·``error``·``db_error``). 적재는 한 트랜잭션이라 전부 반영되거나
전부 취소된다. 기존 ``insert_batch`` 를 그대로 쓰고 PG 적재 경로(API 수집·Python 단계)는 바꾸지 않는다.
"""
from __future__ import annotations

import re
from typing import Any, Dict, List, Optional

from app.schemas.responses import LoadResponse, db_error_of
from app.services.db_utils import insert_batch
from app.services.python_executor import _apply_type_conversion
from app.services.query_executor import sqlstate

# 테이블·컬럼 이름 형식(앱 이름 규칙) — insert_batch 가 식별자를 인용하지만 형식을 한 번 더 좁힌다. ``$`` 는 끝 개행도 받으므로
# fullmatch 로 전체 일치를 요구한다. 길이는 PG 식별자 한도(63).
_NAME = re.compile(r"[a-z][a-z0-9_]*")
_MAX_NAME = 63

BAD_TABLE = "적재할 수 없는 테이블입니다."
BAD_COLUMN = "컬럼 이름이 올바르지 않습니다."


def _failure(message: str, sql_state: Optional[str] = None) -> LoadResponse:
    return LoadResponse(success=False, rows_loaded=0, error=message, db_error=db_error_of(message, sql_state))


def _valid_name(name: Any) -> bool:
    return isinstance(name, str) and len(name) <= _MAX_NAME and _NAME.fullmatch(name) is not None


def _normalize(rows: List[Any]) -> Optional[List[Dict[str, Any]]]:
    """모든 행의 키 합집합(처음 나온 순서)으로 행을 맞추고 없는 키는 None 으로 채운다. 행이 dict 가 아니거나 컬럼 이름이 잘못되면 None.

    insert_batch 는 첫 행의 키만 컬럼으로 쓰므로 행마다 키가 다르면 KeyError 가 난다 — db-executor 와 같이 합집합으로 받는다.
    """
    if not all(isinstance(row, dict) for row in rows):
        return None
    columns: List[str] = []
    seen = set()
    for row in rows:
        for key in row:
            if key not in seen:
                seen.add(key)
                columns.append(key)
    if not columns or not all(_valid_name(c) for c in columns):
        return None
    return [{c: row.get(c) for c in columns} for row in rows]


def execute_load(
    table: str,
    rows: List[Dict[str, Any]],
    column_type_map: Optional[dict],
    conn,
    timeout_seconds: Optional[int] = None,
) -> LoadResponse:
    """검증 → (선택) 문장 제한 → 타입 변환 → 배치 INSERT → 커밋. 오류는 예외가 아니라 ``success=False`` 로 돌려준다.

    ``conn`` 은 테넌트 롤로 연 연결이고 ``search_path`` 가 테넌트 스키마라, 이름 하나만 인용해 넣으면 그 스키마의 테이블이다.
    """
    if not _valid_name(table):
        return _failure(BAD_TABLE)
    if not rows:
        return LoadResponse(success=True, rows_loaded=0)
    try:
        # 행 모양 검증·타입 변환도 try 안에서 한다 — 어긋난 입력이 500 이 아니라 success=false + db_error 가 되게.
        normalized = _normalize(rows)
        if normalized is None:
            return _failure(BAD_COLUMN)
        _apply_type_conversion(normalized, column_type_map)
        if timeout_seconds is not None:
            # 이 트랜잭션에만 걸리는 문장 제한(밀리초) — /execute/sql 과 같은 방식. 값은 요청 스키마가 1 이상 정수로 보증한다.
            # 주의: execute_values 는 페이지(문장) 단위로 실행하므로 이 제한은 문장 단위다(요청 전체 기한이 아니다).
            with conn.cursor() as cur:
                cur.execute(f"SET LOCAL statement_timeout = {int(timeout_seconds) * 1000}")
        insert_batch(conn, table, normalized)
        conn.commit()
        return LoadResponse(success=True, rows_loaded=len(normalized))
    except Exception as exc:
        _rollback_quietly(conn)
        return _failure(str(exc), sqlstate(exc))


def _rollback_quietly(conn) -> None:
    """롤백 실패(끊긴 연결 등)가 원래 실패 응답을 가리지 않게 삼킨다 — 풀 반납 때 미커밋 작업은 버려진다."""
    try:
        conn.rollback()
    except Exception:
        pass
