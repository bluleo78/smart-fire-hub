"""결과 컬럼 탐지(probe) — 쿼리를 ``LIMIT 0`` 으로 감싸 실행해 컬럼 메타데이터만 얻는다(WD-77).

행을 읽지 않으므로 큰 쿼리도 비용이 작고, 서브쿼리로 감싸 SELECT 형태가 아닌 문장은 DB 가 거부한다.
응답의 ``jdbc_type`` 은 db-executor(Java) 와 같은 ``java.sql.Types`` 정수값이라, api 는 백엔드와 무관하게 해석한다.
"""
from __future__ import annotations

from typing import Optional

from app.schemas.responses import ProbeColumn, ProbeResponse, db_error_of
from app.services.query_executor import _normalize_sql, sqlstate

# PG 타입 OID → (java.sql.Types 값, 타입 이름). 목록에 없는 타입은 OTHER(1111).
_OTHER = 1111
_OID_TYPES = {
    21: (4, "int2"),
    23: (4, "int4"),
    20: (-5, "int8"),
    1700: (2, "numeric"),
    700: (8, "float4"),
    701: (8, "float8"),
    16: (16, "bool"),
    1043: (12, "varchar"),
    25: (12, "text"),
    1082: (91, "date"),
    1114: (93, "timestamp"),
    1184: (2014, "timestamptz"),
}


def _failure(message: str, sql_state: Optional[str] = None) -> ProbeResponse:
    return ProbeResponse(success=False, columns=[], error=message, db_error=db_error_of(message, sql_state))


def _column(desc) -> ProbeColumn:
    """psycopg2 ``cursor.description`` 항목 하나를 컬럼으로 바꾼다(인덱스 4·5 가 precision·scale)."""
    jdbc_type, type_name = _OID_TYPES.get(desc[1], (_OTHER, "other"))
    precision = desc[4] if len(desc) > 4 else None
    scale = desc[5] if len(desc) > 5 else None
    return ProbeColumn(name=desc[0], jdbc_type=jdbc_type, type_name=type_name, precision=precision, scale=scale)


def execute_probe(query: str, conn) -> ProbeResponse:
    """``SELECT * FROM (<query>) AS _probe LIMIT 0`` 을 실행해 컬럼 목록을 돌려준다. 읽기만 하고 항상 롤백한다."""
    clean_sql = _normalize_sql(query)
    if not clean_sql:
        return _failure("Query must not be empty")
    try:
        with conn.cursor() as cursor:
            cursor.execute(f"SELECT * FROM ({clean_sql}) AS _probe LIMIT 0")
            columns = [_column(d) for d in (cursor.description or [])]
        conn.rollback()
        return ProbeResponse(success=True, columns=columns)
    except Exception as exc:
        conn.rollback()
        return _failure(str(exc), sqlstate(exc))
