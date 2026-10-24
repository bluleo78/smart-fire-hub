"""probe_executor 단위 테스트 — LIMIT 0 래핑, cursor.description → 컬럼, OID → java.sql.Types 매핑."""
from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from app.services.probe_executor import execute_probe


def _conn(description, exc=None):
    cur = MagicMock()
    cur.description = description
    if exc:
        cur.execute.side_effect = exc
    cur.__enter__.return_value = cur
    conn = MagicMock()
    conn.cursor.return_value = cur
    return conn, cur


@pytest.mark.parametrize(
    "oid,jdbc",
    [(21, 4), (23, 4), (20, -5), (1700, 2), (700, 8), (701, 8), (16, 16), (1043, 12), (25, 12),
     (1082, 91), (1114, 93), (1184, 2014), (2950, 1111)],
)
def test_oid_mapping(oid, jdbc):
    conn, _ = _conn([("c", oid, None, 4, 10, 2, None)])
    r = execute_probe("SELECT 1", conn)
    assert r.success is True
    col = r.columns[0]
    assert (col.name, col.jdbc_type) == ("c", jdbc)


def test_wraps_with_limit_zero_and_strips_semicolon():
    conn, cur = _conn([("a", 23, None, 4, None, None, None)])
    execute_probe("SELECT 1 AS a;  ", conn)
    sql = cur.execute.call_args[0][0]
    assert sql == "SELECT * FROM (SELECT 1 AS a) AS _probe LIMIT 0"
    conn.rollback.assert_called()


def test_numeric_precision_scale():
    conn, _ = _conn([("n", 1700, None, None, 12, 3, None)])
    col = execute_probe("SELECT 1", conn).columns[0]
    assert (col.precision, col.scale) == (12, 3)


def test_error_returns_db_error():
    class E(Exception):
        pgcode = "42P01"

    conn, _ = _conn(None, E("relation missing"))
    r = execute_probe("SELECT * FROM nope", conn)
    assert r.success is False and r.columns == []
    assert r.db_error.sql_state == "42P01" and r.db_error.code == 0
    conn.rollback.assert_called()


def test_empty_query():
    conn, cur = _conn(None)
    r = execute_probe("  ", conn)
    assert r.success is False and r.db_error.sql_state is None
    cur.execute.assert_not_called()
