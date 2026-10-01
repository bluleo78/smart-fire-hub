from __future__ import annotations

from unittest.mock import MagicMock, call, patch

import pytest

from app.services.query_executor import (
    _build_geojson_wrapped_sql,
    execute_query,
)


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

    assert result.startswith("WITH _src AS (\nSELECT id, geom, name FROM t\n) SELECT")
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
            raise Exception("function public.st_asgeojson(zz.geometry) does not exist")
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
