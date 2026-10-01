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


# ---------------------------------------------------------------------------
# #771 — 파싱은 되지만 다른 값이 되는 날짜·시각·interval 은 PG 원문 텍스트로 둔다
# ---------------------------------------------------------------------------

# 값이 바뀌던 식들 — 수정 전: 9999-12-31 / 0001-01-01 / 00:00 / P1D / P30D / -PT21H56M55.5S
_LOSSY_EXPRS = [
    "'infinity'::date",
    "'-infinity'::date",
    "'infinity'::timestamptz",
    "'-infinity'::timestamp",
    "'infinity'::timestamp",
    "'24:00:00'::time",
    "'24:00:00+09'::timetz",
    "'24:00:00'::interval",
    "'1 mon'::interval",
    "'1 year 2 mons 3 days'::interval",
    "'-1 years +2 mons'::interval",
    "'-1 day 02:03:04.5'::interval",
    "'1 day -02:00:00'::interval",
    "'100:00:00'::interval",
]


@pytest.mark.parametrize("expr", _LOSSY_EXPRS)
def test_real_lossy_temporal_values_keep_pg_text(pg_conn, expr):
    """오라클은 하드코딩이 아니라 PG 자신의 ``::text`` 다 — executor 값이 PG 원문과 같아야 한다."""
    cur = pg_conn.cursor()
    cur.execute(f"SELECT {expr}, ({expr})::text")
    value, pg_text = cur.fetchone()
    cur.close()
    assert value == pg_text


@pytest.mark.parametrize(
    "expr, element_exprs",
    [
        ("ARRAY['24:00:00'::interval, '1 mon']", ["'24:00:00'::interval", "'1 mon'::interval"]),
        ("ARRAY['24:00'::time, '24:00:00']", ["'24:00'::time", "'24:00:00'::time"]),
        ("ARRAY['infinity'::date, '-infinity']", ["'infinity'::date", "'-infinity'::date"]),
    ],
)
def test_real_lossy_temporal_array_elements_keep_pg_text(pg_conn, expr, element_exprs):
    cur = pg_conn.cursor()
    cur.execute("SELECT " + ", ".join(f"({e})::text" for e in element_exprs))
    expected = list(cur.fetchone())
    cur.close()
    assert _fetch(pg_conn, expr) == expected


def test_real_infinity_array_element_only_that_element_is_text(pg_conn):
    """infinity 원소만 텍스트가 되고 정상 원소는 date 로 남는다(배열 전체 폴백 아님)."""
    assert _fetch(pg_conn, "ARRAY['infinity'::date, '2020-01-02']") == ["infinity", datetime.date(2020, 1, 2)]


def test_real_normal_temporal_values_keep_python_types(pg_conn):
    """정상 값의 형태는 바꾸지 않는다(형태 통일은 #758 범위)."""
    td = datetime.timedelta
    assert _fetch(pg_conn, "'2020-01-02 03:04:05'::timestamp") == datetime.datetime(2020, 1, 2, 3, 4, 5)
    assert isinstance(_fetch(pg_conn, "'2020-01-02 03:04:05+09'::timestamptz"), datetime.datetime)
    assert _fetch(pg_conn, "'12:34:56'::time") == datetime.time(12, 34, 56)
    assert _fetch(pg_conn, "'23:59:59.999999'::time") == datetime.time(23, 59, 59, 999999)
    assert isinstance(_fetch(pg_conn, "'12:34:56+09'::timetz"), datetime.time)
    assert _fetch(pg_conn, "'1 day 02:03:04.5'::interval") == td(days=1, hours=2, minutes=3, seconds=4.5)
    assert _fetch(pg_conn, "'-1 day -02:00'::interval") == -td(days=1, hours=2)
    assert _fetch(pg_conn, "'-02:00'::interval") == -td(hours=2)
    assert _fetch(pg_conn, "'-1 day'::interval") == -td(days=1)
    assert _fetch(pg_conn, "'23:59:59.999999'::interval") == td(hours=23, minutes=59, seconds=59, microseconds=999999)
    assert _fetch(pg_conn, "ARRAY['1 day'::interval, '02:00']") == [td(days=1), td(hours=2)]
    assert _fetch(pg_conn, "ARRAY['12:00'::time]") == [datetime.time(12, 0)]


def test_execute_query_json_issue_771(pg_conn):
    """이슈 재현 SQL 이 응답 JSON 에서 PG 원문을 주고, 정상 값의 ISO 형태는 그대로다."""
    from app.services.query_executor import execute_query

    result = execute_query(
        "SELECT 'infinity'::date dinf, '-infinity'::date dninf, 'infinity'::timestamptz tzinf,"
        " '-infinity'::timestamp tsinf, '24:00:00'::time t24, '24:00:00+09'::timetz tz24,"
        " '24:00:00'::interval iv24, ARRAY['24:00:00'::interval,'1 mon'] ivarr,"
        " '-1 day 02:03:04.5'::interval ivneg,"
        " '1 day 02:03:04.5'::interval iv, '-1 day -02:00'::interval ivn2, '-02:00'::interval ivn3,"
        " '2020-01-02'::date d",
        100,
        True,
        pg_conn,
        tenant_id=1,
    )
    assert result.success, result.error
    row = result.model_dump(mode="json")["rows"][0]
    assert row == {
        "dinf": "infinity",
        "dninf": "-infinity",
        "tzinf": "infinity",
        "tsinf": "-infinity",
        "t24": "24:00:00",
        "tz24": "24:00:00+09",
        "iv24": "24:00:00",
        "ivarr": ["24:00:00", "1 mon"],
        "ivneg": "-1 days +02:03:04.5",
        # 정상 값은 기존 형태(ISO 8601) 유지
        "iv": "P1DT2H3M4.5S",
        "ivn2": "-P1DT2H",
        "ivn3": "-PT2H",
        "d": "2020-01-02",
    }
