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


class PgErrorInfo(BaseModel):
    """PostgreSQL 오류의 구조화 필드(WD-111).

    API 는 ``sql_state`` 로 흔한 오류를 분류해 고칠 방법을 담은 한국어 안내를 앞에 두고, 원문은 주 메시지·DETAIL·HINT 만
    남긴다. ``error`` 문자열(``str(exc)``)에는 psycopg2 가 붙인 ``LINE 1: INSERT INTO …`` 위치 표시 줄에 러너가 감싼 내부 SQL
    이 실리고 SQLState 도 없어서 그대로는 분류할 수 없다.
    """

    sql_state: str
    message: Optional[str] = None
    detail: Optional[str] = None
    hint: Optional[str] = None
    column: Optional[str] = None


class DbErrorInfo(BaseModel):
    """두 실행기(Python·db-executor) 공통 오류 구조(WD-77, 계약 벡터 ``dbErrorFields``).

    ``code`` 는 DB 벤더 오류 코드다. PG 는 SQLState 문자열만 있고 숫자 코드가 없어 항상 0 이다.
    ``pg_error`` 는 PG 전용 필드로 그대로 두고, 이 필드는 백엔드와 무관한 소비자를 위해 **추가**된다.
    """

    code: int = 0
    sql_state: Optional[str] = None
    message: str


def db_error_of(message: str, sql_state: Optional[str] = None) -> DbErrorInfo:
    """실패 응답에 싣는 ``db_error`` 를 만든다. 검증 거부처럼 SQLState 가 없으면 None 으로 둔다."""
    return DbErrorInfo(code=0, sql_state=sql_state, message=message)


class SqlExecuteResponse(BaseModel):
    success: bool
    rows: Optional[list] = None
    columns: Optional[list] = None
    row_count: int
    execution_log: str
    error: Optional[str] = None
    # PG 오류로 실패했을 때만 채운다(검증 거부·연결 오류 등은 None). 기존 필드는 그대로라 구버전 API 와도 호환된다.
    pg_error: Optional[PgErrorInfo] = None
    # 실행기 공통 오류 구조(WD-77). 실패 때만 채우고 성공이면 None. pg_error 와 병존한다.
    db_error: Optional[DbErrorInfo] = None
    # 출력 잠금을 얻은 뒤 파이프라인 중지 요청을 확인해 롤백했으면 True(WD-466) — 출력은 손대지 않았고 api 는 스텝을 실패가 아니라
    # 취소로 기록한다. 실행 id 가 없는 요청에서는 언제나 False.
    cancelled: bool = False

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
    # 출력 적재가 PG 오류로 실패했을 때만 채운다(WD-211). API 가 데이터 오류(22xxx)를 한국어 사유 문구로 바꾸는 데 쓴다.
    # 기존 필드는 그대로라 구버전 API 와도 호환된다.
    pg_error: Optional[PgErrorInfo] = None


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
    # 실행기 공통 오류 구조(WD-77). 실패 때만 채운다.
    db_error: Optional[DbErrorInfo] = None

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


class ProbeColumn(BaseModel):
    """probe 가 알아낸 결과 컬럼 하나. ``jdbc_type`` 은 ``java.sql.Types`` 정수값이다."""

    name: str
    jdbc_type: int
    type_name: str
    precision: Optional[int] = None
    scale: Optional[int] = None


class ProbeResponse(BaseModel):
    """``/execute/probe`` 응답 — 행을 읽지 않고 쿼리의 결과 컬럼만 돌려준다."""

    success: bool
    columns: List[ProbeColumn] = []
    error: Optional[str] = None
    db_error: Optional[DbErrorInfo] = None


class LoadResponse(BaseModel):
    """``/execute/load`` 응답 — db-executor 와 같은 모양(``success``·``rows_loaded``·``error``·``db_error``)."""

    success: bool
    rows_loaded: int = 0
    error: Optional[str] = None
    db_error: Optional[DbErrorInfo] = None
