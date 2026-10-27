"""행 목록 적재(``/execute/load``, WD-77) — PG(테넌트 롤 연결)용.

db-executor(Tibero) 의 ``/execute/load`` 와 같은 요청·응답 모양이다(``table``·``rows``·``column_type_map``·
``timeout_seconds`` → ``success``·``rows_loaded``·``error``·``db_error``). 적재는 한 트랜잭션이라 전부 반영되거나
전부 취소된다. ``insert_batch`` 와 같은 INSERT 문장(``db_utils`` 의 조립·페이지 실행 도우미)을 쓰고 PG 적재 경로(API 수집·Python
단계)는 바꾸지 않는다.
"""
from __future__ import annotations

import re
from typing import Any, Dict, List, Optional

from app.schemas.responses import LoadResponse, db_error_of
from app.services.db_utils import (
    build_insert_query,
    execute_insert_page,
    set_local_statement_timeout,
    statement_timeout_message,
)
from app.services.python_executor import _apply_type_conversion
from app.services.query_executor import RunningStatement, sqlstate

# 테이블·컬럼 이름 형식(앱 이름 규칙) — INSERT 조립이 식별자를 인용하지만 형식을 한 번 더 좁힌다. ``$`` 는 끝 개행도 받으므로
# fullmatch 로 전체 일치를 요구한다. 길이는 PG 식별자 한도(63).
_NAME = re.compile(r"[a-z][a-z0-9_]*")
_MAX_NAME = 63

BAD_TABLE = "적재할 수 없는 테이블입니다."
BAD_COLUMN = "컬럼 이름이 올바르지 않습니다."

# 한 문장(페이지)에 싣는 행 수 — ``execute_values`` 의 기본 ``page_size`` 와 같다. 예전에는 ``insert_batch`` 한 번이 내부에서 이 크기로
# 나눠 보냈는데, 문장 제한은 페이지(문장)마다 걸리므로 시간 초과 판정도 페이지마다 시작 시각을 재야 한다(WD-261 §5.2). 그래서 여기서 같은
# 크기로 나눠 페이지마다 한 문장씩 보낸다 — INSERT 조립은 한 번, 커서도 하나이고 나가는 문장은 예전과 같다.
PAGE_SIZE = 100


def _failure(message: str, sql_state: Optional[str] = None) -> LoadResponse:
    return LoadResponse(success=False, rows_loaded=0, error=message, db_error=db_error_of(message, sql_state))


def _valid_name(name: Any) -> bool:
    return isinstance(name, str) and len(name) <= _MAX_NAME and _NAME.fullmatch(name) is not None


def _normalize(rows: List[Any]) -> Optional[List[Dict[str, Any]]]:
    """모든 행의 키 합집합(처음 나온 순서)으로 행을 맞추고 없는 키는 None 으로 채운다. 행이 dict 가 아니거나 컬럼 이름이 잘못되면 None.

    INSERT 는 첫 행의 키만 컬럼으로 쓰므로 행마다 키가 다르면 KeyError 가 난다 — db-executor 와 같이 합집합으로 받는다.
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
    *,
    default_timeout_seconds: int,
) -> LoadResponse:
    """검증 → 문장 제한 → 타입 변환 → 배치 INSERT → 커밋. 오류는 예외가 아니라 ``success=False`` 로 돌려준다.

    ``conn`` 은 테넌트 롤로 연 연결이고 ``search_path`` 가 테넌트 스키마라, 이름 하나만 인용해 넣으면 그 스키마의 테이블이다.
    ``default_timeout_seconds`` 는 요청에 제한이 없을 때 쓸 값 — 기본값은 ``Settings.load_statement_timeout_seconds`` 한 곳뿐이라
    라우터가 설정값을 넘긴다.
    """
    if not _valid_name(table):
        return _failure(BAD_TABLE)
    if not rows:
        return LoadResponse(success=True, rows_loaded=0)
    # 요청별 값 또는 기본값 — 한 번만 정해 SET LOCAL 과 시간 초과 안내 문구가 같은 값을 쓴다(최종 리뷰).
    effective_timeout = timeout_seconds if timeout_seconds is not None else default_timeout_seconds
    # 실행 중인 페이지의 시작 시각·제한. 페이지 밖(검증·변환·SET LOCAL·조립·커밋)에서 난 오류에는 없다 — 그때는 시간 초과로 보지 않는다.
    running = RunningStatement()
    try:
        # 행 모양 검증·타입 변환도 try 안에서 한다 — 어긋난 입력이 500 이 아니라 success=false + db_error 가 되게.
        normalized = _normalize(rows)
        if normalized is None:
            return _failure(BAD_COLUMN)
        _apply_type_conversion(normalized, column_type_map)
        # 요청별 값 또는 기본값을 트랜잭션 문장 제한으로 건다 — /execute/sql 과 같은 도우미. 기본값이 늘 있으므로 언제나 건다(리뷰 M-4).
        # 주의: execute_values 는 페이지(문장) 단위로 실행하므로 이 제한은 문장 단위다(요청 전체 기한이 아니다).
        with conn.cursor() as cur:
            set_local_statement_timeout(cur, effective_timeout)
            # 행 모양이 합집합으로 맞춰져 있어 모든 페이지가 같은 컬럼·같은 문장이다 — 한 번만 조립한다.
            columns = list(normalized[0].keys())
            query = build_insert_query(cur, table, columns)
            for start in range(0, len(normalized), PAGE_SIZE):
                # 이 페이지(문장)의 시작 시각을 적는다 — 57014 가 이 페이지의 시간 초과인지 이 값으로 가른다(앞 페이지들의 시간은 셈하지 않는다).
                # page_size 를 명시해 「한 번 호출 = 한 문장(페이지)」 전제를 코드로 고정한다 — 기본값이 바뀌어도 시간 초과 판정이 어긋나지 않게.
                running.run(
                    effective_timeout,
                    execute_insert_page, cur, query, columns, normalized[start:start + PAGE_SIZE], PAGE_SIZE,
                )
        conn.commit()
        return LoadResponse(success=True, rows_loaded=len(normalized))
    except Exception as exc:
        _rollback_quietly(conn)
        if running.timed_out(exc):
            # 우리가 건 문장 제한에 걸린 페이지 — Tibero 적재(db-executor LoadRunner)와 같은 뜻의 한국어 문구를, 같은 값(db_error 없음)으로
            # 돌려준다(WD-261). 시간 초과는 DB 가 거부한 오류가 아니라 실행기가 판정한 실패라서다.
            return LoadResponse(success=False, rows_loaded=0, error=statement_timeout_message("적재", effective_timeout))
        # 관리자 취소처럼 제한보다 일찍 끝난 57014 등은 원문과 SQLState 를 둔다.
        return _failure(str(exc), sqlstate(exc))


def _rollback_quietly(conn) -> None:
    """롤백 실패(끊긴 연결 등)가 원래 실패 응답을 가리지 않게 삼킨다 — 풀 반납 때 미커밋 작업은 버려진다."""
    try:
        conn.rollback()
    except Exception:
        pass
