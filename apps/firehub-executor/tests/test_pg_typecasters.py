"""psycopg2 캐스터 보강(#762) 회귀 가드.

- 래퍼 로직(장식 걷기·값 단위 폴백)은 가짜 원본 캐스터로 DB 없이 검증한다.
  (C 캐스터는 실제 psycopg2 커서를 요구하므로 MagicMock 커서로 원본을 부르면 안 된다.)
- 실제 psycopg2 파싱은 로컬 PostgreSQL 에 붙어 검증한다. 붙을 수 없으면 skip 한다
  (DSN 은 ``EXECUTOR_TEST_PG_DSN`` 으로 덮어쓸 수 있다 — 기본값은 docker-compose 의 dev DB).
"""

from __future__ import annotations

import datetime
import os
from decimal import Decimal

import pytest

import app.db.connection  # noqa: F401 — 임포트만으로 전역 캐스터가 설치되는지 함께 검증한다
from app.services.pg_typecasters import make_array_caster, make_fallback_caster

_DSN = os.environ.get(
    "EXECUTOR_TEST_PG_DSN",
    "host=localhost port=5432 dbname=smartfirehub user=app password=app connect_timeout=3",
)


# ---------------------------------------------------------------------------
# 래퍼 로직 단위 테스트 (DB 불필요)
# ---------------------------------------------------------------------------


def test_array_caster_strips_multi_dim_bounds_before_delegating():
    seen = []

    def orig(value, cur):
        seen.append(value)
        return "parsed"

    cast = make_array_caster(orig)
    assert cast("[0:1][-1:0]={{1,2},{3,4}}", None) == "parsed"
    assert seen == ["{{1,2},{3,4}}"]


def test_array_caster_leaves_plain_literal_untouched():
    seen = []
    cast = make_array_caster(lambda v, c: seen.append(v) or "ok")
    cast("{1,2}", None)
    assert seen == ["{1,2}"]


def test_array_caster_falls_back_to_original_text_on_failure():
    def orig(value, cur):
        raise ValueError("boom")

    cast = make_array_caster(orig)
    # 폴백은 장식을 포함한 원문 그대로 — 직접 경로의 Array.toString() 폴백과 같은 계약.
    assert cast("[0:1][0:1]={{1,2},{3,4}}", None) == "[0:1][0:1]={{1,2},{3,4}}"


def test_casters_pass_none_through():
    assert make_array_caster(lambda v, c: "x")(None, None) is None
    assert make_fallback_caster(lambda v, c: "x")(None, None) is None


def test_scalar_fallback_returns_text_on_failure():
    def orig(value, cur):
        raise ValueError("year 10000 is out of range")

    assert make_fallback_caster(orig)("10000-01-01", None) == "10000-01-01"


# ---------------------------------------------------------------------------
# 실제 psycopg2 파싱 (로컬 PostgreSQL)
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


def _fetch(conn, expr):
    cur = conn.cursor()
    cur.execute(f"SELECT {expr}")
    value = cur.fetchone()[0]
    cur.close()
    return value


@pytest.mark.parametrize(
    "expr, expected",
    [
        # 이슈 원문: 하한 지정 2차원 — 수정 전 "array does not start with '{'" 로 쿼리 전체 실패
        ("'[0:1][0:1]={{1,2},{3,4}}'::int[]", [[1, 2], [3, 4]]),
        # 3차원
        ("'[0:1][0:1][0:0]={{{1},{2}},{{3},{4}}}'::int[]", [[[1], [2]], [[3], [4]]]),
        # 음수 하한 + text + NULL 원소
        ("'[-2:-1][0:1]={{a,b},{c,NULL}}'::text[]", [["a", "b"], ["c", None]]),
        ("'[0:1][1:2]={{1.5,2},{3,NULL}}'::numeric[]", [[Decimal("1.5"), Decimal("2")], [Decimal("3"), None]]),
        ("'[0:1][0:1]={{t,f},{t,NULL}}'::bool[]", [[True, False], [True, None]]),
        (
            "'[0:1][0:1]={{2020-01-01,NULL},{2020-01-03,2020-01-04}}'::date[]",
            [[datetime.date(2020, 1, 1), None], [datetime.date(2020, 1, 3), datetime.date(2020, 1, 4)]],
        ),
        ("'[0:1][0:0]={{\"{\\\"a\\\":1}\"},{null}}'::jsonb[]", [[{"a": 1}], [None]]),
        # 기존 동작 유지: 1차원 하한 지정·장식 없는 배열·빈 배열·NULL
        ("'[2:3]={7,8}'::int[]", [7, 8]),
        ("'{{1,2},{3,4}}'::int[]", [[1, 2], [3, 4]]),
        ("'{}'::int[]", []),
        ("NULL::int[]", None),
        # 원소 텍스트가 장식처럼 생겨도 원소는 건드리지 않는다
        ("ARRAY['[1:2]={a}']", ["[1:2]={a}"]),
    ],
)
def test_real_array_parsing(pg_conn, expr, expected):
    assert _fetch(pg_conn, expr) == expected


@pytest.mark.parametrize(
    "expr, expected",
    [
        # 원소 하나가 Python 범위를 벗어나면 배열 값만 원문 텍스트로 폴백한다(쿼리는 성공)
        ("'{infinity,10000-01-01}'::date[]", "{infinity,10000-01-01}"),
        # 범위 초과 스칼라 날짜·시각·interval 도 값 단위 폴백
        ("'10000-01-01'::date", "10000-01-01"),
        ("'10000-01-01 00:00:00'::timestamp", "10000-01-01 00:00:00"),
        ("'178000000 years'::interval", "178000000 years"),
    ],
)
def test_real_unparseable_values_fall_back_to_text(pg_conn, expr, expected):
    assert _fetch(pg_conn, expr) == expected


def test_real_normal_scalars_unchanged(pg_conn):
    assert _fetch(pg_conn, "'2020-01-02'::date") == datetime.date(2020, 1, 2)
    assert _fetch(pg_conn, "'1 day'::interval") == datetime.timedelta(days=1)


def test_execute_query_succeeds_with_bounded_multi_dim_array(pg_conn):
    """이슈 재현 SQL 이 execute_query 경로에서 실패하지 않고 직접 경로와 같은 형태를 준다."""
    from app.services.query_executor import execute_query

    result = execute_query(
        "SELECT '[0:1][0:1]={{1,2},{3,4}}'::int[] AS lb, '[2:3]={7,8}'::int[] AS lb1",
        100,
        True,
        pg_conn,
        tenant_id=1,
    )
    assert result.success, result.error
    assert result.rows == [{"lb": [[1, 2], [3, 4]], "lb1": [7, 8]}]
