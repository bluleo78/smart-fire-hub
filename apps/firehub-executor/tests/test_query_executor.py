from __future__ import annotations

from unittest.mock import MagicMock, call, patch

import pytest

from app.services.query_executor import (
    _build_geojson_wrapped_sql,
    _detect_geometry_in_rows,
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

    result = execute_query("SELECT id, name FROM users", max_rows=1000, read_only=False, conn=conn)

    assert result.success is True
    assert result.columns == ["id", "name"]
    assert result.rows == [{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]
    assert result.row_count == 2
    assert result.affected_rows == 0
    assert result.query_type == "SELECT"


def test_read_only_rejects_insert():
    conn = MagicMock()
    conn.cursor.return_value = MagicMock()

    result = execute_query("INSERT INTO t VALUES (1)", max_rows=1000, read_only=True, conn=conn)

    assert result.success is False
    assert result.error is not None
    assert "read_only" in result.error.lower() or "INSERT" in result.error


def test_read_only_allows_select():
    cursor = make_cursor(
        description=[("?column?", 23, None, None, None, None, None)],
        fetchall_return=[(1,)],
    )
    conn = make_conn(cursor)

    result = execute_query("SELECT 1", max_rows=1000, read_only=True, conn=conn)

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

    execute_query("SELECT id FROM t", max_rows=500, read_only=False, conn=conn)

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

    execute_query("SELECT id FROM t LIMIT 10", max_rows=500, read_only=False, conn=conn)

    select_calls = [s for s in executed_sqls if "FROM" in s.upper() and "LIMIT" in s.upper()]
    # Should not have two LIMIT keywords in the same statement
    for s in select_calls:
        assert s.upper().count("LIMIT") == 1, f"Double LIMIT found in: {s}"


def test_dml_returns_affected_rows():
    cursor = make_cursor(rowcount=7)
    conn = make_conn(cursor)

    result = execute_query("UPDATE t SET x = 1", max_rows=1000, read_only=False, conn=conn)

    assert result.success is True
    assert result.affected_rows == 7
    assert result.query_type == "UPDATE"
    assert result.rows == []
    conn.commit.assert_called_once()


def test_empty_query_rejected():
    conn = MagicMock()

    result = execute_query("   ", max_rows=1000, read_only=False, conn=conn)

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

    execute_query("SELECT 1", max_rows=1000, read_only=False, conn=conn)

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

    result = execute_query("SELECT bad syntax !!!", max_rows=1000, read_only=False, conn=conn)

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

    execute_query("SELECT 1", max_rows=1000, read_only=False, conn=conn)

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

    execute_query("SELECT 1", max_rows=1000, read_only=False, conn=conn)

    assert any("statement_timeout" in s and "30s" in s for s in executed_sqls), \
        f"Expected statement_timeout in: {executed_sqls}"


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

    result = execute_query("SELECT geom FROM shapes", max_rows=1000, read_only=False, conn=main_conn)

    # Verify that wrapped SQL with ST_AsGeoJSON was attempted
    assert any("ST_AsGeoJSON" in s for s in executed_sqls), \
        f"Expected ST_AsGeoJSON wrap in one of: {executed_sqls}"


# ---------------------------------------------------------------------------
# Unit tests for helper functions
# ---------------------------------------------------------------------------

def test_build_geojson_wrapped_sql():
    column_metas = [("id", False), ("geom", True), ("name", False)]
    result = _build_geojson_wrapped_sql("SELECT id, geom, name FROM t", column_metas)

    assert result.startswith("WITH _src AS (SELECT id, geom, name FROM t) SELECT")
    assert 'public.ST_AsGeoJSON("geom") AS "geom"' in result
    assert '"id"' in result
    assert '"name"' in result
    # geom column should be wrapped in ST_AsGeoJSON (appears as argument and alias)
    parts = result.split("SELECT", 2)[-1]  # after the final SELECT
    assert 'ST_AsGeoJSON("geom")' in parts  # wrapped
    assert parts.count('"id"') == 1  # id is plain, appears once


def test_detect_geometry_in_rows():
    # Valid hex WKB string (endianness byte + hex-encoded data)
    hex_wkb = "0101000000000000000000F03F0000000000000040"
    rows = [{"id": 1, "geom": hex_wkb, "name": "test"}]
    columns = ["id", "geom", "name"]

    result = _detect_geometry_in_rows(rows, columns)

    assert "geom" in result
    assert "id" not in result
    assert "name" not in result


def test_detect_geometry_in_rows_no_geom():
    rows = [{"id": 1, "name": "hello"}]
    columns = ["id", "name"]
    result = _detect_geometry_in_rows(rows, columns)
    assert result == set()


def test_detect_geometry_in_rows_empty():
    result = _detect_geometry_in_rows([], ["id"])
    assert result == set()


def test_execution_time_measured():
    cursor = make_cursor(
        description=[("x", 23, None, None, None, None, None)],
        fetchall_return=[(1,)],
    )
    conn = make_conn(cursor)

    result = execute_query("SELECT 1", max_rows=1000, read_only=False, conn=conn)

    assert result.execution_time_ms >= 0


def test_truncated_flag_when_rows_equal_max_rows():
    """truncated=True when returned rows == max_rows."""
    max_rows = 3
    cursor = make_cursor(
        description=[("id", 23, None, None, None, None, None)],
        fetchall_return=[(1,), (2,), (3,)],  # exactly max_rows
    )
    conn = make_conn(cursor)

    result = execute_query("SELECT id FROM t", max_rows=max_rows, read_only=False, conn=conn)

    assert result.truncated is True


def test_truncated_flag_false_when_fewer_rows():
    """truncated=False when returned rows < max_rows."""
    max_rows = 10
    cursor = make_cursor(
        description=[("id", 23, None, None, None, None, None)],
        fetchall_return=[(1,), (2,)],  # fewer than max_rows
    )
    conn = make_conn(cursor)

    result = execute_query("SELECT id FROM t LIMIT 10", max_rows=max_rows, read_only=False, conn=conn)

    assert result.truncated is False
