"""응답 직렬화 회귀 가드(#763) — bytea·range 가 있는 결과가 500 이 아니라 직접 경로와 같은 형태로 나간다.

- ``to_json_value`` 의 Base64 변환은 DB 없이 검증한다.
- 실제 psycopg2 값(``memoryview``·range 텍스트 캐스터)은 로컬 PostgreSQL 에 붙어 ``model_dump_json``(FastAPI 가
  response_model 을 직렬화하는 것과 같은 경로)으로 검증한다. 붙을 수 없으면 skip 한다.
"""

from __future__ import annotations

import json
import os

import pytest

import app.db.connection  # noqa: F401 — 전역 캐스터(range 텍스트 포함) 설치
from app.schemas.responses import QueryExecuteResponse, SqlExecuteResponse, to_json_value

_DSN = os.environ.get(
    "EXECUTOR_TEST_PG_DSN",
    "host=localhost port=5432 dbname=smartfirehub user=app password=app connect_timeout=3",
)


# ---------------------------------------------------------------------------
# 단위 테스트 (DB 불필요)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "value, expected",
    [
        # 직접 경로(Jackson byte[])와 같은 패딩 있는 표준 Base64
        (memoryview(b"\x01\x02"), "AQI="),
        (b"\xff", "/w=="),
        (bytearray(b"abc"), "YWJj"),
        (memoryview(b""), ""),
        # 배열은 원소 단위(1·2차원, NULL 원소 유지)
        ([memoryview(b"\x01\x02"), None], ["AQI=", None]),
        ([[memoryview(b"\x01\x02")]], [["AQI="]]),
        # 그 밖의 값은 손대지 않는다
        ("text", "text"),
        (None, None),
        ([1, "a"], [1, "a"]),
    ],
)
def test_to_json_value(value, expected):
    assert to_json_value(value) == expected


def test_query_response_serializes_bytea_rows():
    resp = QueryExecuteResponse(success=True, rows=[{"b": memoryview(b"\x01\x02"), "n": 1}])
    assert json.loads(resp.model_dump_json())["rows"] == [{"b": "AQI=", "n": 1}]


def test_sql_response_serializes_bytea_rows_and_none():
    resp = SqlExecuteResponse(success=True, rows=[{"b": [memoryview(b"\x01\x02"), None]}], row_count=1, execution_log="")
    assert json.loads(resp.model_dump_json())["rows"] == [{"b": ["AQI=", None]}]
    empty = SqlExecuteResponse(success=False, rows=None, row_count=0, execution_log="")
    assert json.loads(empty.model_dump_json())["rows"] is None


# ---------------------------------------------------------------------------
# 실제 PostgreSQL 값
# ---------------------------------------------------------------------------


@pytest.fixture
def pg_conn():
    psycopg2 = pytest.importorskip("psycopg2")
    try:
        conn = psycopg2.connect(_DSN)
    except Exception as exc:  # pragma: no cover — DB 없는 환경
        pytest.skip(f"PostgreSQL 에 연결할 수 없음: {exc}")
    yield conn
    conn.rollback()
    conn.close()


def _serialized(conn, expr):
    """한 값을 조회해 두 응답 모델로 직렬화한 JSON 값을 돌려준다(두 모델의 결과가 같아야 한다)."""
    cur = conn.cursor()
    cur.execute(f"SELECT {expr} AS v")
    value = cur.fetchone()[0]
    cur.close()
    q = json.loads(QueryExecuteResponse(success=True, rows=[{"v": value}]).model_dump_json())["rows"][0]["v"]
    s = json.loads(
        SqlExecuteResponse(success=True, rows=[{"v": value}], row_count=1, execution_log="").model_dump_json()
    )["rows"][0]["v"]
    assert q == s
    return q


@pytest.mark.parametrize(
    "expr, expected",
    [
        # 이슈 원문 3건 — 수정 전 "Unable to serialize unknown type: memoryview" 로 500
        ("'\\x0102'::bytea", "AQI="),
        ("ARRAY['\\x0102'::bytea, null]", ["AQI=", None]),
        ("ARRAY[['\\x0102'::bytea]]", [["AQI="]]),
        ("''::bytea", ""),
        ("'\\xff'::bytea", "/w=="),
        # range — 수정 전 psycopg2 Range 객체라 같은 방식으로 500. PG 원문 텍스트로 준다.
        ("'[1,5)'::int4range", "[1,5)"),
        ("'[1,5)'::int8range", "[1,5)"),
        ("'[0.0000001,2)'::numrange", "[0.0000001,2)"),
        ("'[2020-01-01,2021-01-01)'::daterange", "[2020-01-01,2021-01-01)"),
        ("'[2020-01-01 00:00,)'::tsrange", '["2020-01-01 00:00:00",)'),
        ("'empty'::int4range", "empty"),
        ("ARRAY['[1,2)'::int4range, null]", ["[1,2)", None]),
        # 하한 지정 다차원 range 배열도 #762 배열 래퍼(장식 걷기)를 그대로 탄다
        ("'[0:0][0:1]={{\"[1,2)\",empty}}'::int4range[]", [["[1,2)", "empty"]]),
        ("NULL::int4range", None),
    ],
)
def test_real_bytea_and_range_serialize(pg_conn, expr, expected):
    assert _serialized(pg_conn, expr) == expected


@pytest.mark.parametrize(
    "expr, expected",
    [
        # 형태 고정 가드 — 이번 수정이 다른 타입의 직렬화 형태를 바꾸지 않는다(형태 차이 자체는 #758 소관).
        ("'2020-01-01 01:02:03+09'::timestamptz", "2019-12-31T16:02:03Z"),
        ("'1 day 2 hours'::interval", "P1DT2H"),
        ("'NaN'::numeric", "NaN"),
        ("1.50::numeric", "1.50"),
        ("'NaN'::float8", None),
        ("'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
        ("'12:34:56'::time", "12:34:56"),
        ("'10.0.0.1/8'::inet", "10.0.0.1/8"),
        ("'{[1,2)}'::int4multirange", "{[1,2)}"),
        ("'{\"a\":1}'::jsonb", {"a": 1}),
        ("ARRAY[1,2]", [1, 2]),
    ],
)
def test_real_other_types_shape_unchanged(pg_conn, expr, expected):
    assert _serialized(pg_conn, expr) == expected


def test_execute_query_with_bytea_and_range_serializes(pg_conn):
    """이슈 재현 SQL 이 execute_query 경로에서 성공하고 응답 JSON 이 Base64·range 텍스트를 담는다."""
    from app.services.query_executor import execute_query

    result = execute_query(
        "SELECT 1 AS a, '\\x0102'::bytea AS by, ARRAY['\\x0102'::bytea, null] AS by1, "
        "ARRAY[['\\x0102'::bytea]] AS by2, '[1,5)'::int4range AS r",
        100,
        True,
        pg_conn,
        tenant_id=1,
    )
    assert result.success, result.error
    assert json.loads(result.model_dump_json())["rows"] == [
        {"a": 1, "by": "AQI=", "by1": ["AQI=", None], "by2": [["AQI="]], "r": "[1,5)"}
    ]
