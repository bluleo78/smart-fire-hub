from __future__ import annotations

from unittest.mock import MagicMock, call, patch

import pytest

from app.services.query_executor import (
    _build_geojson_wrapped_sql,
    execute_query,
)
from tests.conftest import PgError


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def make_cursor(description=None, fetchall_return=None, rowcount=0):
    """Return a MagicMock cursor that behaves like a psycopg2 cursor."""
    cursor = MagicMock()
    cursor.description = description
    cursor.fetchall.return_value = fetchall_return or []
    cursor.rowcount = rowcount
    return cursor


def make_conn(cursor):
    """Return a MagicMock connection whose .cursor() always returns the same mock cursor."""
    conn = MagicMock()
    conn.cursor.return_value = cursor
    # cursor.connection is used by _detect_geometry_columns
    cursor.connection = conn
    return conn


# ---------------------------------------------------------------------------
# SELECT tests
# ---------------------------------------------------------------------------

def test_select_returns_rows_and_columns():
    cursor = make_cursor(
        description=[("id", 23, None, None, None, None, None), ("name", 25, None, None, None, None, None)],
        fetchall_return=[(1, "Alice"), (2, "Bob")],
    )
    conn = make_conn(cursor)

    result = execute_query("SELECT id, name FROM users", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert result.success is True
    assert result.columns == ["id", "name"]
    assert result.rows == [{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]
    assert result.row_count == 2
    assert result.affected_rows == 0
    assert result.query_type == "SELECT"


def test_read_only_rejects_insert():
    conn = MagicMock()
    conn.cursor.return_value = MagicMock()

    result = execute_query("INSERT INTO t VALUES (1)", max_rows=1000, read_only=True, conn=conn, tenant_id=1)

    assert result.success is False
    assert result.error is not None
    assert "read_only" in result.error.lower() or "INSERT" in result.error


def test_read_only_allows_select():
    cursor = make_cursor(
        description=[("?column?", 23, None, None, None, None, None)],
        fetchall_return=[(1,)],
    )
    conn = make_conn(cursor)

    result = execute_query("SELECT 1", max_rows=1000, read_only=True, conn=conn, tenant_id=1)

    assert result.success is True
    assert result.query_type == "SELECT"


def test_with_query_treated_as_select():
    cursor = make_cursor(
        description=[("id", 23, None, None, None, None, None)],
        fetchall_return=[(42,)],
    )
    conn = make_conn(cursor)

    result = execute_query(
        "WITH cte AS (SELECT 42 AS id) SELECT id FROM cte",
        max_rows=1000,
        read_only=False,
        conn=conn,
        tenant_id=1,
    )

    assert result.success is True
    assert result.query_type == "SELECT"


# ---------------------------------------------------------------------------
# WD-91: 첫 키워드 바로 뒤 구두점 — PostgreSQL 어휘 규칙으로 첫 키워드를 뽑는다
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "query",
    [
        "SELECT*FROM data.sales",
        "select(a) from data.sales",
        'SELECT"a" FROM data.sales',
        'WITH"d" AS (SELECT 1 AS a) SELECT*FROM"d"',
    ],
)
def test_read_only_allows_select_with_punctuation_right_after_keyword(query):
    """공백 없이 구두점이 붙은 SELECT 도 PostgreSQL 이 받는 SELECT 다 — 읽기 전용에서 실행해 행을 돌려준다.

    예전엔 공백으로만 첫 단어를 잘라 ``SELECT*FROM`` 을 UNKNOWN 으로 보고 거부했다(운영 경로).
    """
    cursor = make_cursor(
        description=[("a", 23, None, None, None, None, None)],
        fetchall_return=[(1,)],
    )
    conn = make_conn(cursor)

    result = execute_query(query, max_rows=1000, read_only=True, conn=conn, tenant_id=1)

    assert result.success is True, result.error
    assert result.query_type == "SELECT"
    assert result.rows == [{"a": 1}]


@pytest.mark.parametrize(
    "query, query_type",
    [
        ("DELETE*FROM t", "DELETE"),
        ('UPDATE"t" SET a = 1', "UPDATE"),
        ("INSERT(a) VALUES (1)", "INSERT"),
    ],
)
def test_read_only_rejects_dml_with_punctuation_right_after_keyword(query, query_type):
    """같은 규칙으로 구두점이 붙은 DML 은 DML 로 분류돼 읽기 전용이 거부한다 — 실행(cursor)까지 가지 않는다."""
    conn = MagicMock()

    result = execute_query(query, max_rows=1000, read_only=True, conn=conn, tenant_id=1)

    assert result.success is False
    assert result.query_type == query_type
    assert query_type in result.error
    conn.cursor.assert_not_called()


@pytest.mark.parametrize("query", ["SELECT$x FROM t", "SELECT_1 FROM t", "SELECT$$x$$", "(SELECT 1)", "$1"])
def test_read_only_rejects_non_keyword_start(query):
    """식별자 글자($·_·숫자)가 이어지면 PostgreSQL 에선 식별자 하나라 SELECT 가 아니다. 키워드로 시작하지 않으면 UNKNOWN."""
    conn = MagicMock()

    result = execute_query(query, max_rows=1000, read_only=True, conn=conn, tenant_id=1)

    assert result.success is False
    assert result.query_type == "UNKNOWN"
    assert "None" not in result.error
    conn.cursor.assert_not_called()


# ---------------------------------------------------------------------------
# 보안(WD-91 리뷰): WITH 문 분류 — CTE 안 DML·본문 키워드를 api 와 같은 규칙으로 본다
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "query, query_type",
    [
        ('WITH"d"AS(DELETE FROM t RETURNING *)SELECT*FROM"d"', "DELETE"),
        ("WITH d AS (UPDATE t SET a = 1 RETURNING *) SELECT * FROM d", "UPDATE"),
        ("WITH selected AS (SELECT 1) DELETE FROM t", "DELETE"),
        ('WITH "select" AS (SELECT 1) UPDATE t SET a=1', "UPDATE"),
        ("WITH selected AS (SELECT 1) INSERT INTO t SELECT 7 LIMIT 1 RETURNING *", "INSERT"),
    ],
)
def test_read_only_rejects_data_modifying_with(query, query_type):
    """WITH 로 시작해도 CTE 안이나 본문이 DML 이면 읽기 전용이 거부한다 — 실행(cursor)까지 가지 않는다.

    예전엔 WITH 면 무조건 SELECT 로 보고 실행했다. api 를 거치지 않고 executor 에 직접 온 요청도 막아야 한다.
    """
    conn = MagicMock()

    result = execute_query(query, max_rows=1000, read_only=True, conn=conn, tenant_id=1)

    assert result.success is False
    assert result.query_type == query_type
    conn.cursor.assert_not_called()


def test_read_only_runs_in_read_only_transaction():
    """읽기 전용 요청은 사용자 SQL 전에 트랜잭션을 READ ONLY 로 연다 — 분류가 틀려도 DB 가 쓰기를 거부하는 2차 방어선."""
    executed = []
    cursor = make_cursor(
        description=[("a", 23, None, None, None, None, None)],
        fetchall_return=[(1,)],
    )
    cursor.execute.side_effect = lambda sql, *a, **k: executed.append(sql)
    conn = make_conn(cursor)

    result = execute_query("SELECT 1 AS a", max_rows=10, read_only=True, conn=conn, tenant_id=1)

    assert result.success is True
    # 설정 문장은 한 번의 execute 로 묶여 나가고, READ ONLY 가 그 첫 문장이며 사용자 SQL 보다 앞선다.
    assert executed[0].startswith("SET TRANSACTION READ ONLY; ")
    user_sql_index = next(i for i, s in enumerate(executed) if s.startswith("SELECT 1 AS a"))
    assert user_sql_index > 0


def test_read_write_does_not_force_read_only_transaction():
    """쓰기 허용 요청(read_only=False)은 READ ONLY 를 걸지 않는다 — DML 이 실행·커밋돼야 한다."""
    executed = []
    cursor = make_cursor(rowcount=1)
    cursor.execute.side_effect = lambda sql, *a, **k: executed.append(sql)
    conn = make_conn(cursor)

    result = execute_query("UPDATE t SET a = 1", max_rows=10, read_only=False, conn=conn, tenant_id=1)

    assert result.success is True
    assert not any("READ ONLY" in s for s in executed)
    conn.commit.assert_called_once()


def test_limit_auto_added():
    """Query without LIMIT should have LIMIT appended before execution."""
    executed_sqls = []
    cursor = MagicMock()
    cursor.description = [("id", 23, None, None, None, None, None)]
    cursor.fetchall.return_value = [(1,)]
    cursor.rowcount = 0

    def capture_execute(sql, *args, **kwargs):
        executed_sqls.append(sql)

    cursor.execute.side_effect = capture_execute
    cursor.connection = MagicMock()
    conn = MagicMock()
    conn.cursor.return_value = cursor

    execute_query("SELECT id FROM t", max_rows=500, read_only=False, conn=conn, tenant_id=1)

    # Find the SELECT execution (not SET LOCAL / SAVEPOINT)
    select_calls = [s for s in executed_sqls if s.upper().startswith("SELECT") or "FROM" in s.upper()]
    assert any("LIMIT 500" in s for s in select_calls), f"Expected LIMIT 500 in one of: {select_calls}"


def test_limit_not_doubled():
    """Query that already has LIMIT should not get a second LIMIT."""
    executed_sqls = []
    cursor = MagicMock()
    cursor.description = [("id", 23, None, None, None, None, None)]
    cursor.fetchall.return_value = [(1,)]
    cursor.rowcount = 0

    def capture_execute(sql, *args, **kwargs):
        executed_sqls.append(sql)

    cursor.execute.side_effect = capture_execute
    cursor.connection = MagicMock()
    conn = MagicMock()
    conn.cursor.return_value = cursor

    execute_query("SELECT id FROM t LIMIT 10", max_rows=500, read_only=False, conn=conn, tenant_id=1)

    select_calls = [s for s in executed_sqls if "FROM" in s.upper() and "LIMIT" in s.upper()]
    # Should not have two LIMIT keywords in the same statement
    for s in select_calls:
        assert s.upper().count("LIMIT") == 1, f"Double LIMIT found in: {s}"


def test_dml_returns_affected_rows():
    cursor = make_cursor(rowcount=7)
    conn = make_conn(cursor)

    result = execute_query("UPDATE t SET x = 1", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert result.success is True
    assert result.affected_rows == 7
    assert result.query_type == "UPDATE"
    assert result.rows == []
    conn.commit.assert_called_once()


def test_empty_query_rejected():
    conn = MagicMock()

    result = execute_query("   ", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert result.success is False
    assert result.error is not None


def test_savepoint_management_on_success():
    """RELEASE SAVEPOINT must be called on successful SELECT."""
    executed_sqls = []
    cursor = MagicMock()
    cursor.description = [("id", 23, None, None, None, None, None)]
    cursor.fetchall.return_value = [(1,)]
    cursor.rowcount = 0

    def capture_execute(sql, *args, **kwargs):
        executed_sqls.append(sql)

    cursor.execute.side_effect = capture_execute
    cursor.connection = MagicMock()
    conn = MagicMock()
    conn.cursor.return_value = cursor

    execute_query("SELECT 1", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert any("RELEASE SAVEPOINT analytics_query" in s for s in executed_sqls)
    assert not any("ROLLBACK TO SAVEPOINT analytics_query" in s for s in executed_sqls)


def test_savepoint_rollback_on_error():
    """ROLLBACK TO SAVEPOINT must be called when query fails."""
    executed_sqls = []
    call_count = [0]

    cursor = MagicMock()
    cursor.description = None
    cursor.fetchall.return_value = []
    cursor.rowcount = 0
    cursor.connection = MagicMock()

    def capture_execute(sql, *args, **kwargs):
        executed_sqls.append(sql)
        # Fail on the actual SELECT (not SET LOCAL / SAVEPOINT / pg_type queries)
        if sql.upper().startswith("SELECT") and "search_path" not in sql and "pg_type" not in sql and "LIMIT 0" not in sql:
            raise Exception("syntax error")

    cursor.execute.side_effect = capture_execute

    # Make sub-cursors for geometry detection return empty (no geometry)
    sub_cursor = MagicMock()
    sub_cursor.description = []
    sub_cursor.fetchall.return_value = []
    cursor.connection.cursor.return_value = sub_cursor

    conn = MagicMock()
    conn.cursor.return_value = cursor

    result = execute_query("SELECT bad syntax !!!", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert result.success is False
    assert any("ROLLBACK TO SAVEPOINT analytics_query" in s for s in executed_sqls)


def test_search_path_set_and_restored():
    """SET LOCAL search_path must be set to data,public and restored to data at end."""
    executed_sqls = []
    cursor = MagicMock()
    cursor.description = [("x", 23, None, None, None, None, None)]
    cursor.fetchall.return_value = [(1,)]
    cursor.rowcount = 0

    def capture_execute(sql, *args, **kwargs):
        executed_sqls.append(sql)

    cursor.execute.side_effect = capture_execute
    cursor.connection = MagicMock()
    conn = MagicMock()
    conn.cursor.return_value = cursor

    execute_query("SELECT 1", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert any("search_path = 'data', 'public'" in s for s in executed_sqls), \
        f"Expected search_path setup in: {executed_sqls}"
    assert any("search_path TO data" in s for s in executed_sqls), \
        f"Expected search_path restore in: {executed_sqls}"


def test_statement_timeout_set():
    """SET LOCAL statement_timeout = '30s' must be issued."""
    executed_sqls = []
    cursor = MagicMock()
    cursor.description = [("x", 23, None, None, None, None, None)]
    cursor.fetchall.return_value = [(1,)]
    cursor.rowcount = 0

    def capture_execute(sql, *args, **kwargs):
        executed_sqls.append(sql)

    cursor.execute.side_effect = capture_execute
    cursor.connection = MagicMock()
    conn = MagicMock()
    conn.cursor.return_value = cursor

    execute_query("SELECT 1", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert any("statement_timeout" in s and "30s" in s for s in executed_sqls), \
        f"Expected statement_timeout in: {executed_sqls}"


def test_numeric_text_not_misdetected_as_geometry():
    """YYYYMMDDHHMMSS 같은 숫자 텍스트 컬럼을 geometry 로 오판하지 않는다 (회귀)."""
    executed_sqls = []
    cursor = MagicMock()
    cursor.description = [("report_date", 25, None, None, None, None, None)]  # 25 = text OID
    cursor.fetchall.return_value = [("20260131235609",)]
    cursor.rowcount = 0

    def capture_execute(sql, *args, **kwargs):
        executed_sqls.append(sql)

    cursor.execute.side_effect = capture_execute

    # geometry OID 조회용 사이드 커서 (geometry OID=16000; text 25 는 미포함)
    geom_cursor = MagicMock()
    geom_cursor.fetchall.return_value = [(16000,)]
    side_conn = MagicMock()
    side_conn.cursor.return_value = geom_cursor
    cursor.connection = side_conn

    conn = MagicMock()
    conn.cursor.return_value = cursor

    result = execute_query("SELECT report_date FROM survey", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert result.success is True
    assert result.rows == [{"report_date": "20260131235609"}]
    assert not any("ST_AsGeoJSON" in s for s in executed_sqls), \
        f"text 컬럼이 geometry 로 오판되어 래핑됨: {executed_sqls}"


def test_geometry_column_wrapped_via_oid_on_success():
    """성공한 SELECT 결과에 실제 geometry 컬럼(OID 매칭)이 있으면 ST_AsGeoJSON 로 래핑한다."""
    GEOM_OID = 16000
    executed_sqls = []
    state = {"wrapped": False}

    cursor = MagicMock()
    cursor.rowcount = 0
    cursor.description = [("geom", GEOM_OID, None, None, None, None, None)]

    def execute_side(sql, *args, **kwargs):
        executed_sqls.append(sql)
        if "ST_AsGeoJSON" in sql:
            state["wrapped"] = True
            cursor.description = [("geom", 25, None, None, None, None, None)]

    def fetchall_side():
        if state["wrapped"]:
            return [('{"type":"Point","coordinates":[1,2]}',)]
        return [("0101000000AABBCCDD",)]

    cursor.execute.side_effect = execute_side
    cursor.fetchall.side_effect = fetchall_side

    geom_cursor = MagicMock()
    geom_cursor.fetchall.return_value = [(GEOM_OID,)]
    side_conn = MagicMock()
    side_conn.cursor.return_value = geom_cursor
    cursor.connection = side_conn

    conn = MagicMock()
    conn.cursor.return_value = cursor

    result = execute_query("SELECT geom FROM shapes", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert result.success is True
    assert any("ST_AsGeoJSON" in s for s in executed_sqls)
    assert result.rows == [{"geom": '{"type":"Point","coordinates":[1,2]}'}]


def test_geometry_wrap_failure_falls_back_to_original_result():
    """geometry 로 판정됐으나 ST_AsGeoJSON 재실행이 실패하면 원본 성공 결과를 반환한다."""
    GEOM_OID = 16000
    executed_sqls = []

    cursor = MagicMock()
    cursor.rowcount = 0
    cursor.description = [("geom", GEOM_OID, None, None, None, None, None)]
    cursor.fetchall.return_value = [("0101000000DEADBEEF",)]

    def execute_side(sql, *args, **kwargs):
        executed_sqls.append(sql)
        if "ST_AsGeoJSON" in sql:
            raise Exception("ST_AsGeoJSON 실패")

    cursor.execute.side_effect = execute_side

    geom_cursor = MagicMock()
    geom_cursor.fetchall.return_value = [(GEOM_OID,)]
    side_conn = MagicMock()
    side_conn.cursor.return_value = geom_cursor
    cursor.connection = side_conn

    conn = MagicMock()
    conn.cursor.return_value = cursor

    result = execute_query("SELECT geom FROM shapes", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert result.success is True
    assert result.rows == [{"geom": "0101000000DEADBEEF"}]
    assert any("ST_AsGeoJSON" in s for s in executed_sqls)  # 시도는 했음


def test_geometry_detection_via_limit0():
    """When initial SELECT fails, geometry detection via LIMIT 0 + pg_type should trigger wrapped SQL."""
    # Geometry OID
    GEOM_OID = 12345

    executed_sqls = []

    main_cursor = MagicMock()
    main_cursor.rowcount = 0

    # Sub-cursors for _detect_geometry_columns
    meta_cursor = MagicMock()
    # description item: (name, type_code, ...)
    meta_cursor.description = [("geom", GEOM_OID, None, None, None, None, None)]
    meta_cursor.fetchall.return_value = []

    oid_cursor = MagicMock()
    oid_cursor.fetchall.return_value = [(GEOM_OID,)]

    sub_cursors = iter([meta_cursor, oid_cursor])

    conn_mock = MagicMock()
    conn_mock.cursor.return_value = MagicMock(
        fetchall=MagicMock(return_value=[(GEOM_OID,)])
    )

    call_count = [0]

    def main_execute(sql, *args, **kwargs):
        executed_sqls.append(sql)
        call_count[0] += 1
        # Fail first real SELECT attempt
        if "FROM shapes" in sql and "ST_AsGeoJSON" not in sql and "LIMIT 0" not in sql and call_count[0] <= 4:
            raise Exception("could not read geometry value")
        # Succeed on wrapped SQL
        if "ST_AsGeoJSON" in sql:
            main_cursor.description = [("geom", 25, None, None, None, None, None)]
            main_cursor.fetchall.return_value = [('{"type":"Point","coordinates":[0,0]}',)]

    main_cursor.execute.side_effect = main_execute
    main_cursor.connection = conn_mock

    # conn_mock sub-cursor returns for geometry detection
    sub_cursor_calls = [meta_cursor, oid_cursor]
    sub_idx = [0]

    def sub_cursor_factory():
        c = sub_cursor_calls[sub_idx[0] % len(sub_cursor_calls)]
        sub_idx[0] += 1
        return c

    conn_mock.cursor.side_effect = sub_cursor_factory

    main_conn = MagicMock()
    main_conn.cursor.return_value = main_cursor

    result = execute_query("SELECT geom FROM shapes", max_rows=1000, read_only=False, conn=main_conn, tenant_id=1)

    # Verify that wrapped SQL with ST_AsGeoJSON was attempted
    assert any("ST_AsGeoJSON" in s for s in executed_sqls), \
        f"Expected ST_AsGeoJSON wrap in one of: {executed_sqls}"


# ---------------------------------------------------------------------------
# Unit tests for helper functions
# ---------------------------------------------------------------------------

def test_fetch_geom_oids_returns_pg_type_oids():
    """pg_type 조회 결과의 OID 집합을 반환한다."""
    from app.services.query_executor import _fetch_geom_oids

    cur = MagicMock()
    cur.fetchall.return_value = [(16000,), (16001,)]
    conn = MagicMock()
    conn.cursor.return_value = cur

    oids = _fetch_geom_oids(conn)

    assert oids == {16000, 16001}
    assert any("pg_type" in c.args[0] for c in cur.execute.call_args_list)


def test_build_geojson_wrapped_sql():
    column_metas = [("id", False), ("geom", True), ("name", False)]
    result = _build_geojson_wrapped_sql("SELECT id, geom, name FROM t", column_metas)

    assert result.startswith('WITH _src("id", "geom", "name") AS (\nSELECT id, geom, name FROM t\n) SELECT')
    assert 'public.ST_AsGeoJSON("geom") AS "geom"' in result
    assert '"id"' in result
    assert '"name"' in result
    # geom column should be wrapped in ST_AsGeoJSON (appears as argument and alias)
    parts = result.split("SELECT", 2)[-1]  # after the final SELECT
    assert 'ST_AsGeoJSON("geom")' in parts  # wrapped
    assert parts.count('"id"') == 1  # id is plain, appears once



def test_execution_time_measured():
    cursor = make_cursor(
        description=[("x", 23, None, None, None, None, None)],
        fetchall_return=[(1,)],
    )
    conn = make_conn(cursor)

    result = execute_query("SELECT 1", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert result.execution_time_ms >= 0


def test_truncated_flag_when_rows_equal_max_rows():
    """truncated=True when returned rows == max_rows."""
    max_rows = 3
    cursor = make_cursor(
        description=[("id", 23, None, None, None, None, None)],
        fetchall_return=[(1,), (2,), (3,)],  # exactly max_rows
    )
    conn = make_conn(cursor)

    result = execute_query("SELECT id FROM t", max_rows=max_rows, read_only=False, conn=conn, tenant_id=1)

    assert result.truncated is True


def test_truncated_flag_false_when_fewer_rows():
    """truncated=False when returned rows < max_rows."""
    max_rows = 10
    cursor = make_cursor(
        description=[("id", 23, None, None, None, None, None)],
        fetchall_return=[(1,), (2,)],  # fewer than max_rows
    )
    conn = make_conn(cursor)

    result = execute_query("SELECT id FROM t LIMIT 10", max_rows=max_rows, read_only=False, conn=conn, tenant_id=1)

    assert result.truncated is False


# ---------------------------------------------------------------------------
# #741 — 끝의 한 줄 주석(--)이 래핑의 닫는 괄호를 삼키지 않는다
# ---------------------------------------------------------------------------

def _strip_line_comments(sql: str) -> str:
    """PostgreSQL 이 한 줄 주석을 해석하는 방식(-- 부터 줄 끝까지 무시)을 흉내 낸다.

    목 커서는 문법을 검사하지 않으므로, 주석을 걷어낸 뒤에도 래핑 뼈대(닫는 괄호 등)가 살아 있는지로
    "DB 가 실제로 보는 문장"을 판정한다.
    """
    import re

    return re.sub(r"--[^\n]*", "", sql)


def test_build_geojson_wrapped_sql_survives_trailing_line_comment():
    """사용자 SQL 이 -- 주석으로 끝나도 CTE 의 닫는 괄호와 바깥 SELECT 가 주석에 먹히지 않는다(#741)."""
    result = _build_geojson_wrapped_sql(
        "SELECT id, geom FROM t -- note", [("id", False), ("geom", True)]
    )

    effective = _strip_line_comments(result)
    assert ") SELECT" in effective, f"닫는 괄호가 주석에 먹혔다: {result!r}"
    assert effective.rstrip().endswith("FROM _src"), f"바깥 SELECT 가 주석에 먹혔다: {result!r}"


def test_geometry_detect_sql_survives_trailing_line_comment():
    """geometry 감지용 LIMIT 0 래핑도 끝의 -- 주석에 닫는 괄호를 잃지 않는다(#741)."""
    from app.services.query_executor import _detect_geometry_columns

    meta_cursor = MagicMock()
    meta_cursor.description = []
    oid_cursor = MagicMock()
    oid_cursor.fetchall.return_value = []
    conn = MagicMock()
    conn.cursor.side_effect = [meta_cursor, oid_cursor]
    cursor = MagicMock()
    cursor.connection = conn

    _detect_geometry_columns(cursor, "SELECT geom FROM shapes -- note")

    detect_sql = meta_cursor.execute.call_args.args[0]
    effective = _strip_line_comments(detect_sql)
    assert effective.rstrip().endswith(") _geom_detect LIMIT 0"), (
        f"닫는 괄호/LIMIT 0 이 주석에 먹혔다: {detect_sql!r}"
    )


# ---------------------------------------------------------------------------
# #745 — 끝 주석·세미콜론이 max_rows LIMIT 을 삼키지 않는다 (#746 블록 주석·달러 인용 포함)
# ---------------------------------------------------------------------------

def _capture_select_sql(query: str, max_rows: int = 1) -> list[str]:
    """execute_query 가 실제로 DB 에 보낸 사용자 SELECT 문(SET/SAVEPOINT/pg_type 제외)을 모은다."""
    executed: list[str] = []
    cursor = MagicMock()
    cursor.description = [("v", 23, None, None, None, None, None)]
    cursor.fetchall.return_value = [(1,)]
    cursor.execute.side_effect = lambda sql, *a, **k: executed.append(sql)
    conn = MagicMock()
    conn.cursor.return_value = cursor
    cursor.connection = conn
    result = execute_query(query, max_rows=max_rows, read_only=True, conn=conn, tenant_id=1)
    assert result.success is True, result.error
    return [
        s for s in executed
        if not s.startswith(("SET ", "SAVEPOINT", "RELEASE", "ROLLBACK")) and "pg_type" not in s
    ]


@pytest.mark.parametrize(
    "query",
    [
        "SELECT g FROM generate_series(1,5000) g -- note",
        "SELECT g FROM generate_series(1,5000) g; -- note",
        "SELECT g FROM generate_series(1,5000) g /* note */",
        "SELECT g FROM generate_series(1,5000) g; /* a */ -- b\n",
        "SELECT g FROM generate_series(1,5000) g /* outer /* nested */ still comment */",
        "SELECT g FROM generate_series(1,5000) g;;  \n",
    ],
)
def test_trailing_comment_and_semicolon_do_not_swallow_limit(query):
    """끝 주석/세미콜론이 있어도 DB 에 가는 문장은 주석·세미콜론 없이 LIMIT 으로 끝난다(#745)."""
    sqls = _capture_select_sql(query, max_rows=1)
    assert len(sqls) == 1, sqls
    sql = sqls[0]
    assert sql.rstrip().endswith("LIMIT 1"), f"LIMIT 이 문장 끝에 오지 않는다: {sql!r}"
    assert "--" not in sql and "/*" not in sql and ";" not in sql, f"끝 주석/세미콜론이 남았다: {sql!r}"


@pytest.mark.parametrize(
    "query, expected_prefix",
    [
        # 문자열·식별자·달러 인용 안의 ; -- /* 는 끝 주석·세미콜론이 아니다 — 지우면 안 된다.
        ("SELECT 'a;--b' AS v", "SELECT 'a;--b' AS v"),
        ("SELECT 'it''s;' AS v; -- c", "SELECT 'it''s;' AS v"),
        ('SELECT 1 AS "x;--/*"', 'SELECT 1 AS "x;--/*"'),
        ("SELECT $$;-- /*$$ AS v;", "SELECT $$;-- /*$$ AS v"),
        ("SELECT $t$ ; $$ -- $t$ AS v /* c */", "SELECT $t$ ; $$ -- $t$ AS v"),
        ("SELECT E'a\\';--' AS v -- c", "SELECT E'a\\';--' AS v"),
    ],
)
def test_quoted_semicolons_and_comment_markers_are_preserved(query, expected_prefix):
    """따옴표·식별자·달러 인용 속 기호는 보존하고 진짜 끝 주석/세미콜론만 걷어낸다(#745/#746)."""
    sql = _capture_select_sql(query, max_rows=1)[0]
    assert sql == f"{expected_prefix}\nLIMIT 1", sql


@pytest.mark.parametrize(
    "query",
    [
        "SELECT g FROM generate_series(1,5000) g -- LIMIT 5",
        "SELECT g FROM generate_series(1,5000) g /* LIMIT 5 */",
        "SELECT 'LIMIT 5' AS v FROM generate_series(1,5000) g",
        "SELECT g, (SELECT 1 LIMIT 1) AS s FROM generate_series(1,5000) g",
        "SELECT g FROM (SELECT * FROM generate_series(1,5000) LIMIT 4000) AS g(g)",
    ],
)
def test_limit_in_comment_literal_or_subquery_does_not_bypass_max_rows(query):
    """주석·문자열·서브쿼리 속 LIMIT 은 최상위 LIMIT 이 아니므로 max_rows LIMIT 이 붙어야 한다(#745)."""
    sql = _capture_select_sql(query, max_rows=1)[0]
    assert sql.endswith("\nLIMIT 1"), f"max_rows LIMIT 이 빠졌다: {sql!r}"


def test_comment_only_query_is_rejected_as_empty():
    """주석만 있는 쿼리는 걷어내면 빈 문장이다 — 빈 쿼리 오류로 끝나야 한다."""
    conn = MagicMock()
    result = execute_query("-- only\n/* comment */ ;", max_rows=1, read_only=True, conn=conn, tenant_id=1)
    assert result.success is False
    assert result.error == "Query must not be empty"


# #749 — FETCH FIRST / LIMIT ALL 로 끝나는 SELECT 에 LIMIT 을 한 번 더 붙이지 않는다
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "query",
    [
        "SELECT g FROM generate_series(1,5000) g FETCH FIRST 20 ROWS ONLY",
        "SELECT g FROM generate_series(1,5000) g OFFSET 5 ROWS FETCH NEXT 3 ROWS ONLY",
        "SELECT g FROM generate_series(1,5000) g ORDER BY g FETCH FIRST 2 ROWS WITH TIES;",
    ],
)
def test_fetch_first_is_respected_as_user_row_limit(query):
    """FETCH FIRST|NEXT 는 사용자 LIMIT 과 같은 규칙(존중) — 두 번째 LIMIT 을 붙이지 않는다(#749)."""
    sql = _capture_select_sql(query, max_rows=10)[0]
    assert "LIMIT" not in sql.upper(), f"FETCH 뒤에 LIMIT 이 붙었다: {sql!r}"


@pytest.mark.parametrize(
    "query, expected",
    [
        ("SELECT g FROM generate_series(1,5000) g LIMIT ALL", "SELECT g FROM generate_series(1,5000) g LIMIT 10"),
        ("SELECT g FROM generate_series(1,5000) g limit all offset 5; -- c", "SELECT g FROM generate_series(1,5000) g limit 10 offset 5"),
        ("SELECT g FROM generate_series(1,5000) g OFFSET 5 LIMIT NULL", "SELECT g FROM generate_series(1,5000) g OFFSET 5 LIMIT 10"),
    ],
)
def test_limit_all_is_replaced_by_max_rows(query, expected):
    """LIMIT ALL/NULL 은 '제한 없음' — 값만 max_rows 로 바꿔 LIMIT 이 두 번 나오지 않게 한다(#749)."""
    assert _capture_select_sql(query, max_rows=10) == [expected]


# ---------------------------------------------------------------------------
# #766 — 동명 사용자 타입 오판 방지 + 컬럼 단위 격리
# ---------------------------------------------------------------------------

def test_fetch_geom_oids_limited_to_postgis_extension_schema():
    """OID 조회는 이름만이 아니라 PostGIS 확장 스키마로 한정하고, 카탈로그를 pg_catalog 로 한정한다(#766).

    다른 스키마의 `geometry` 이름 enum 이 geometry 로 판정되면 래핑이 실패해 진짜 geometry 까지 raw 로 나간다.
    """
    from app.services.query_executor import _fetch_geom_oids

    cur = MagicMock()
    cur.fetchall.return_value = [(16000,)]
    conn = MagicMock()
    conn.cursor.return_value = cur

    _fetch_geom_oids(conn)

    sql = cur.execute.call_args_list[0].args[0]
    assert "pg_catalog.pg_extension" in sql
    assert "extname = 'postgis'" in sql
    assert "e.extnamespace = t.typnamespace" in sql
    assert "pg_catalog.pg_type" in sql


def test_fetch_geom_oids_cached_per_connection():
    """같은 커넥션에서는 카탈로그를 한 번만 읽고, 다른 커넥션은 따로 읽는다(#766)."""
    from app.services.query_executor import _fetch_geom_oids

    def make():
        cur = MagicMock()
        cur.fetchall.return_value = [(16000,)]
        conn = MagicMock()
        conn.cursor.return_value = cur
        return conn, cur

    conn1, cur1 = make()
    conn2, cur2 = make()

    assert _fetch_geom_oids(conn1) == {16000}
    assert _fetch_geom_oids(conn1) == {16000}
    assert _fetch_geom_oids(conn2) == {16000}

    assert cur1.execute.call_count == 1
    assert cur2.execute.call_count == 1


def test_wrap_failure_on_one_column_does_not_revert_other_geometry_columns():
    """한 컬럼의 ST_AsGeoJSON 래핑이 실패해도 다른 geometry 컬럼은 GeoJSON 으로 나온다(#766 컬럼 단위 격리)."""
    GEOM_OID = 16000
    executed_sqls = []
    state = {"mode": "raw"}

    cursor = MagicMock()
    cursor.rowcount = 0
    raw_desc = [("e", GEOM_OID, None, None, None, None, None), ("g", GEOM_OID, None, None, None, None, None)]
    cursor.description = raw_desc

    def execute_side(sql, *args, **kwargs):
        executed_sqls.append(sql)
        if 'ST_AsGeoJSON("e")' in sql:
            raise PgError("function public.st_asgeojson(zz.geometry) does not exist", "42883")
        if 'ST_AsGeoJSON("g")' in sql:
            state["mode"] = "wrapped"
            cursor.description = [("e", 25, None, None, None, None, None), ("g", 25, None, None, None, None, None)]

    def fetchall_side():
        if state["mode"] == "wrapped":
            return [("a", '{"type":"Point","coordinates":[1,2]}')]
        return [("a", "0101000020E6100000")]

    cursor.execute.side_effect = execute_side
    cursor.fetchall.side_effect = fetchall_side

    geom_cursor = MagicMock()
    geom_cursor.fetchall.return_value = [(GEOM_OID,)]
    side_conn = MagicMock()
    side_conn.cursor.return_value = geom_cursor
    cursor.connection = side_conn

    conn = MagicMock()
    conn.cursor.return_value = cursor

    result = execute_query("SELECT e, g FROM t", max_rows=1000, read_only=False, conn=conn, tenant_id=1)

    assert result.success is True
    assert result.rows == [{"e": "a", "g": '{"type":"Point","coordinates":[1,2]}'}]
    # 마지막 실행 SQL 은 g 만 감싸고 e 는 그대로 둔다.
    final_sql = [s for s in executed_sqls if "WITH _src" in s and "_geom_probe" not in s][-1]
    assert 'ST_AsGeoJSON("g")' in final_sql
    assert 'ST_AsGeoJSON("e")' not in final_sql


# ---------------------------------------------------------------------------
# #779 — 실행 중 오류에서 사용자 SQL 을 재실행하지 않는다
# ---------------------------------------------------------------------------

def _geom_cursor(fail_on, *, raw_ok: bool = False):
    """geometry 컬럼 g 하나를 돌려주는 사용자 SQL(테이블 zz_t)용 모의 커서.

    fail_on(sql) 이 예외를 돌려주면 그 SQL 실행에서 던진다. 실행된 SQL 은 executed 리스트에 쌓는다.
    raw_ok 이면 래핑 전 원 쿼리는 성공한다(성공 경로 → 래핑 단계 실패 시나리오).
    """
    GEOM_OID = 16000
    executed: list = []
    cursor = MagicMock()
    cursor.rowcount = 0
    cursor.description = [("g", GEOM_OID, None, None, None, None, None), ("z", 23, None, None, None, None, None)]
    cursor.fetchall.return_value = [("0101000020E6100000", 1)]

    def execute_side(sql, *args, **kwargs):
        executed.append(sql)
        err = fail_on(sql)
        if err is not None:
            raise err

    cursor.execute.side_effect = execute_side

    # _detect_geometry_columns·_fetch_geom_oids 는 cursor.connection.cursor() 로 별도 커서를 연다.
    side_cursor = MagicMock()
    side_cursor.description = [("g", GEOM_OID, None, None, None, None, None), ("z", 23, None, None, None, None, None)]
    side_cursor.fetchall.return_value = [(GEOM_OID,)]
    side_conn = MagicMock()
    side_conn.cursor.return_value = side_cursor
    cursor.connection = side_conn

    conn = MagicMock()
    conn.cursor.return_value = cursor
    return conn, executed


def _user_sql_runs(executed) -> int:
    """사용자 SQL 본문(zz_t)을 실제로 실행한 횟수 — 행을 읽지 않는 LIMIT 0 시험은 빼고 센다."""
    return sum(1 for s in executed if "zz_t" in s and "LIMIT 0" not in s)


@pytest.mark.parametrize(
    "msg, pgcode",
    [
        ("division by zero", "22012"),
        ("canceling statement due to statement timeout", "57014"),
        ("invalid input syntax for type integer", "22P02"),
    ],
)
def test_runtime_error_with_geometry_column_runs_user_sql_once(msg, pgcode):
    """geometry 컬럼이 있는 쿼리가 실행 중 오류로 실패하면 래핑 재시도 없이 원 오류를 1회 실행으로 돌려준다(#779).

    수정 전에는 원 쿼리 → 래핑 → (컬럼 격리 후) 같은 래핑 재실행으로 사용자 SQL 이 3회 돌았다.
    """
    conn, executed = _geom_cursor(lambda sql: PgError(msg, pgcode) if "zz_t" in sql and "LIMIT 0" not in sql else None)

    result = execute_query("SELECT g, 1/(id-1) AS z FROM zz_t", max_rows=100, read_only=True, conn=conn, tenant_id=1)

    assert result.success is False
    assert result.error == msg
    assert _user_sql_runs(executed) == 1, executed
    assert not any("ST_AsGeoJSON" in s for s in executed), executed


def test_wrap_runtime_error_on_success_path_falls_back_without_isolation_retry():
    """원 쿼리는 성공했지만 래핑 실행이 statement_timeout 으로 실패하면 격리·재실행 없이 원본 결과로 폴백한다(#779).

    수정 전에는 LIMIT 0 격리 시험이 모두 통과해 같은 래핑 SQL 을 한 번 더 실행했다(사용자 SQL 3회).
    """
    timeout = PgError("canceling statement due to statement timeout", "57014")
    conn, executed = _geom_cursor(lambda sql: timeout if "ST_AsGeoJSON" in sql and "LIMIT 0" not in sql else None)

    result = execute_query("SELECT g, 1 AS z FROM zz_t", max_rows=100, read_only=True, conn=conn, tenant_id=1)

    assert result.success is True
    assert result.rows == [{"g": "0101000020E6100000", "z": 1}]
    assert _user_sql_runs(executed) == 2, executed
    assert not any("_geom_probe" in s for s in executed), executed


def test_wrap_failure_without_sqlstate_does_not_isolate():
    """SQLSTATE 가 없는 래핑 실패(파이썬 쪽 오류)에는 컬럼 격리 재시도를 하지 않는다(#779 — 격리는 클래스 42 만)."""
    conn, executed = _geom_cursor(lambda sql: RuntimeError("boom") if "ST_AsGeoJSON" in sql and "LIMIT 0" not in sql else None)

    result = execute_query("SELECT g, 1 AS z FROM zz_t", max_rows=100, read_only=True, conn=conn, tenant_id=1)

    assert result.success is True
    assert _user_sql_runs(executed) == 2, executed
    assert not any("_geom_probe" in s for s in executed), executed


def test_isolation_that_drops_no_column_does_not_rerun_same_wrap():
    """해석 단계 오류(42xxx)라도 격리 결과가 원래 컬럼 구성과 같으면 같은 래핑 SQL 을 재실행하지 않는다(#779)."""
    err = PgError("operator does not exist", "42883")
    # 래핑 실행만 실패하고 LIMIT 0 격리 시험은 통과 → 빠지는 컬럼이 없다.
    conn, executed = _geom_cursor(lambda sql: err if "ST_AsGeoJSON" in sql and "_geom_probe" not in sql else None)

    result = execute_query("SELECT g, 1 AS z FROM zz_t", max_rows=100, read_only=True, conn=conn, tenant_id=1)

    assert result.success is True  # 원본 결과로 폴백
    assert any("_geom_probe" in s for s in executed), executed  # 격리 시험은 했다
    assert _user_sql_runs(executed) == 2, executed


def test_original_failure_without_sqlstate_still_retries_with_wrap():
    """원 쿼리 실패에 SQLSTATE 가 없으면(파이썬 쪽 변환 오류 등) 기존대로 geometry 래핑으로 구제한다(#779 회귀 가드)."""
    def fail_on(sql):
        if "zz_t" in sql and "ST_AsGeoJSON" not in sql and "LIMIT 0" not in sql:
            return ValueError("could not decode geometry value")
        return None

    conn, executed = _geom_cursor(fail_on)

    result = execute_query("SELECT g, 1 AS z FROM zz_t", max_rows=100, read_only=True, conn=conn, tenant_id=1)

    assert result.success is True
    assert any("ST_AsGeoJSON" in s for s in executed), executed


# ---------------------------------------------------------------------------
# 같은 이름 컬럼 (WD-85) — 행을 이름 키 dict 로 담으므로 겹치면 앞 값이 덮였다
# ---------------------------------------------------------------------------

def test_duplicate_column_names_keep_each_value_under_unique_name():
    cursor = make_cursor(
        description=[("a", 23, None, None, None, None, None), ("a", 23, None, None, None, None, None)],
        fetchall_return=[(1, 2)],
    )
    conn = make_conn(cursor)

    result = execute_query("SELECT 1 AS a, 2 AS a", max_rows=1000, read_only=True, conn=conn, tenant_id=1)

    assert result.success is True
    assert result.columns == ["a", "a_2"]
    assert result.rows == [{"a": 1, "a_2": 2}]


def test_build_geojson_wrapped_sql_aliases_duplicate_names_in_cte():
    """CTE 별칭으로 고유 이름을 붙여 바깥 SELECT 의 같은 이름이 모호해지지 않게 한다."""
    sql = _build_geojson_wrapped_sql("SELECT 1 AS id, 2 AS id, g", [("id", False), ("id_2", False), ("g", True)])

    assert sql.startswith('WITH _src("id", "id_2", "g") AS (\n')
    assert sql.endswith('SELECT "id", "id_2", public.ST_AsGeoJSON("g") AS "g" FROM _src')


def test_duplicate_column_names_with_geometry_wrap_uses_unique_names():
    """성공 경로 geometry 래핑에서도 같은 이름 컬럼이 각 값을 유지한다."""
    GEOM_OID = 16000
    executed_sqls = []
    state = {"wrapped": False}

    cursor = MagicMock()
    cursor.rowcount = 0
    cursor.description = [
        ("id", 23, None, None, None, None, None),
        ("id", 23, None, None, None, None, None),
        ("geom", GEOM_OID, None, None, None, None, None),
    ]

    def execute_side(sql, *args, **kwargs):
        executed_sqls.append(sql)
        if "ST_AsGeoJSON" in sql:
            state["wrapped"] = True
            # CTE 별칭 덕분에 래핑 결과의 컬럼 이름은 이미 고유하다
            cursor.description = [
                ("id", 23, None, None, None, None, None),
                ("id_2", 23, None, None, None, None, None),
                ("geom", 25, None, None, None, None, None),
            ]

    def fetchall_side():
        if state["wrapped"]:
            return [(1, 2, '{"type":"Point","coordinates":[1,2]}')]
        return [(1, 2, "0101000000AABBCCDD")]

    cursor.execute.side_effect = execute_side
    cursor.fetchall.side_effect = fetchall_side

    geom_cursor = MagicMock()
    geom_cursor.fetchall.return_value = [(GEOM_OID,)]
    side_conn = MagicMock()
    side_conn.cursor.return_value = geom_cursor
    cursor.connection = side_conn

    conn = MagicMock()
    conn.cursor.return_value = cursor

    result = execute_query(
        "SELECT a.id, b.id, a.geom FROM t a JOIN t b ON true", max_rows=1000, read_only=True, conn=conn, tenant_id=1
    )

    assert result.success is True
    wrapped = [s for s in executed_sqls if "ST_AsGeoJSON" in s]
    assert wrapped and wrapped[0].startswith('WITH _src("id", "id_2", "geom") AS (')
    assert result.columns == ["id", "id_2", "geom"]
    assert result.rows == [{"id": 1, "id_2": 2, "geom": '{"type":"Point","coordinates":[1,2]}'}]


# ---------------------------------------------------------------------------
# 시간 초과 판정(WD-261 §5.2) — 57014 + 「그 문장의 경과 시간 ≥ 그 문장에 건 제한 − 0.5초」
# ---------------------------------------------------------------------------

def test_timeout_detected_without_english_text(fake_clock):
    # 서버 언어가 한국어라 원문에 영어 'statement timeout' 이 없어도, 제한만큼 돌았으면 시간 초과다.
    from app.services.query_executor import is_statement_timeout

    started_at = fake_clock()
    fake_clock.advance(50)
    assert is_statement_timeout(PgError("사용자 요청에 의해 문장 실행을 취소합니다", "57014"), started_at, 50) is True


def test_admin_cancel_before_limit_is_not_timeout(fake_clock):
    # 원문이 영어 'statement timeout' 이어도 제한 전에 끝난 57014 는 관리자 취소다(문구는 판정에 쓰지 않는다).
    from app.services.query_executor import is_statement_timeout

    started_at = fake_clock()
    fake_clock.advance(10)
    assert is_statement_timeout(PgError("canceling statement due to statement timeout", "57014"), started_at, 50) is False


@pytest.mark.parametrize("elapsed, expected", [(49.49, False), (49.5, True), (60.0, True)])
def test_timeout_tolerance_is_half_second(fake_clock, elapsed, expected):
    from app.services.query_executor import is_statement_timeout

    started_at = fake_clock()
    fake_clock.advance(elapsed)
    assert is_statement_timeout(PgError("x", "57014"), started_at, 50) is expected


def test_non_57014_is_never_timeout(fake_clock):
    from app.services.query_executor import is_statement_timeout

    started_at = fake_clock()
    fake_clock.advance(100)
    assert is_statement_timeout(PgError("deadlock detected", "40P01"), started_at, 50) is False
    assert is_statement_timeout(RuntimeError("boom"), started_at, 50) is False
