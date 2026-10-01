from __future__ import annotations

import base64
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, field_serializer


def to_json_value(value: Any) -> Any:
    """결과 셀 값 하나를 응답 JSON 에 담을 수 있는 값으로 바꾼다(#763). 바꿀 필요가 없으면 그대로 돌려준다.

    psycopg2 는 bytea 를 ``memoryview`` 로 주는데 pydantic 은 이를 직렬화하지 못해(``Unable to serialize unknown
    type``) 응답 전체가 500 이 됐다. 직접 경로(Java)는 ``byte[]`` 를 Jackson 기본값인 **패딩 있는 표준 Base64**
    (``'\\x0102'`` → ``"AQI="``, 빈 값 → ``""``)로 내보내므로 같은 형태로 맞춘다. 배열(1·2차원)은 중첩 리스트라
    원소 단위로 내려가 바꾼다.

    그 밖의 타입은 **손대지 않는다** — 날짜·Decimal·timedelta 등은 pydantic 이 이미 직렬화하고, 그 형태가 직접
    경로와 다른 문제는 별건(#758)이다. 알 수 없는 타입을 ``str()`` 로 뭉뚱그리는 안전망도 두지 않는다(검증하지
    않은 타입의 형태를 조용히 바꾸게 된다). 파싱 단계에서 텍스트로 둘 타입은 ``pg_typecasters`` 가 맡는다.
    """
    if isinstance(value, (memoryview, bytes, bytearray)):
        return base64.b64encode(bytes(value)).decode("ascii")
    if isinstance(value, list):
        return [to_json_value(v) for v in value]
    return value


def _rows_to_json(rows: Optional[list]) -> Optional[list]:
    """행 목록(dict 의 리스트)의 각 셀에 ``to_json_value`` 를 적용한다. 행이 dict 가 아니면 그대로 둔다."""
    if rows is None:
        return None
    return [
        {k: to_json_value(v) for k, v in row.items()} if isinstance(row, dict) else to_json_value(row)
        for row in rows
    ]


class SqlExecuteResponse(BaseModel):
    success: bool
    rows: Optional[list] = None
    columns: Optional[list] = None
    row_count: int
    execution_log: str
    error: Optional[str] = None

    # 파이프라인 SQL 스텝 SELECT 도 같은 연결 풀·같은 행 값을 내보내므로 같은 직렬화 경계를 둔다(#763).
    @field_serializer("rows")
    def _serialize_rows(self, rows: Optional[list]) -> Any:
        return _rows_to_json(rows)


class PythonExecuteResponse(BaseModel):
    success: bool
    output: str
    exit_code: int
    error: Optional[str] = None
    execution_time_ms: int
    rows_loaded: int = 0


class QueryExecuteResponse(BaseModel):
    success: bool
    query_type: str = "UNKNOWN"
    columns: List[str] = []
    rows: List[Dict[str, Any]] = []
    row_count: int = 0
    affected_rows: int = 0
    execution_time_ms: int = 0
    truncated: bool = False
    error: Optional[str] = None

    # 행을 만드는 곳이 여러 군데(주 경로·geometry 재시도·geometry 래핑)라 직렬화 경계 한 곳에서 바꾼다(#763).
    @field_serializer("rows")
    def _serialize_rows(self, rows: List[Dict[str, Any]]) -> Any:
        return _rows_to_json(rows)


class ApiCallExecuteResponse(BaseModel):
    success: bool
    rows_loaded: int = 0
    total_pages: int = 0
    execution_log: str = ""
    error: Optional[str] = None
    execution_time_ms: int = 0
