"""TiberoBackend·backend_for 단위 테스트.

pyodbc 는 개발 venv 에 없으므로 가짜 odbc 모듈(SimpleNamespace)을 주입한다. 커넥션·커서는 MagicMock 이고
``cursor.description`` 은 pyodbc 형식 튜플 ``(name, type_code, display_size, internal_size, precision, scale, null_ok)`` 이다.
"""
import logging
import sys
from datetime import date, datetime
from decimal import Decimal
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from app.db.backends import backend_for
from app.db.backends.postgres import PostgresBackend
from app.db.backends.tibero import (
    TiberoBackend,
    TiberoConnectError,
    TiberoDriverUnavailable,
    load_odbc,
)
from app.db.backends.tibero_sql import SESSION_INIT_SQL, connection_string, insert_sql
from app.db.store_client import StoreDescriptor, StoreLookupError
from tests.conftest import get_test_settings


class FakeOdbcError(Exception):
    """pyodbc.Error 흉내: args = (SQLSTATE, 메시지)."""


def fake_odbc():
    """가짜 odbc 모듈. connect 는 커넥션 MagicMock 을, 커넥션의 cursor() 는 같은 커서를 돌려준다."""
    odbc = SimpleNamespace(connect=MagicMock(), Error=FakeOdbcError, SQL_CHAR=1, SQL_WCHAR=-8)
    conn = MagicMock(name="conn")
    cursor = MagicMock(name="cursor")
    conn.cursor.return_value = cursor
    odbc.connect.return_value = conn
    return odbc, conn, cursor


def descriptor(tenant_id=7):
    return StoreDescriptor.model_validate({
        "tenantId": tenant_id, "backend": "TIBERO", "host": "tb-host", "port": 8629,
        "database": "tibero", "schema": f"GENIA_T{tenant_id}",
        "owner": {"username": f"GENIA_T{tenant_id}", "password": "pw"},
    })


def backend(odbc, on_fail=None):
    return TiberoBackend(descriptor(), get_test_settings(), odbc=odbc, on_connect_failure=on_fail)


def col(name, precision=None, scale=None, type_code=str):
    """pyodbc description 한 항목."""
    return (name, type_code, None, None, precision, scale, True)


def executed(cursor):
    """커서에서 실행된 SQL 목록(순서대로)."""
    return [c.args[0] for c in cursor.execute.call_args_list]


# --- 지연 임포트 -------------------------------------------------------------------------------


def test_lazy_import_reports_korean_error():
    # 모듈 임포트는 위에서 이미 성공했다(pyodbc 없이). 호출 시점에만 드라이버를 요구한다.
    with patch.dict(sys.modules, {"pyodbc": None}):
        with pytest.raises(TiberoDriverUnavailable) as ei:
            load_odbc()
    assert "pyodbc" in str(ei.value)


def test_constructor_does_not_load_driver():
    # odbc 를 주지 않아도 생성은 성공해야 한다 — backend_for 가 pyodbc 없는 환경에서도 동작해야 한다.
    with patch.dict(sys.modules, {"pyodbc": None}):
        b = TiberoBackend(descriptor(), get_test_settings())
        assert b.kind == "TIBERO"
        with pytest.raises(TiberoDriverUnavailable):
            with b.connection(7):
                pass


# --- connection() ------------------------------------------------------------------------------


def test_connection_opens_with_timeout_encoding_and_session_init():
    odbc, conn, cursor = fake_odbc()
    with backend(odbc).connection(7) as c:
        assert c is conn
        conn.close.assert_not_called()
    odbc.connect.assert_called_once_with(
        connection_string("tb-host", 8629, "tibero", "GENIA_T7", "pw"), autocommit=False, timeout=10
    )
    conn.setdecoding.assert_any_call(1, encoding="utf-8")
    conn.setdecoding.assert_any_call(-8, encoding="utf-16le")
    conn.setencoding.assert_called_once_with(encoding="utf-16le")
    assert conn.timeout == 30
    assert executed(cursor)[0] == SESSION_INIT_SQL
    conn.close.assert_called_once()


def test_connection_closes_on_exception_in_block():
    odbc, conn, _ = fake_odbc()
    with pytest.raises(RuntimeError):
        with backend(odbc).connection(7):
            raise RuntimeError("boom")
    conn.close.assert_called_once()


def test_session_init_failure_closes_and_raises():
    odbc, conn, cursor = fake_odbc()
    cursor.execute.side_effect = FakeOdbcError("HY000", "init failed")
    with pytest.raises(FakeOdbcError):
        with backend(odbc).connection(7):
            pytest.fail("세션 초기화가 실패하면 블록에 들어가면 안 된다")
    conn.close.assert_called_once()


def test_query_timeout_rejected_at_cursor_retries_without_timeout(caplog):
    # pyodbc 는 conn.timeout 을 저장만 하고 cursor() 때 적용한다 — 드라이버 거부는 cursor() 에서 난다.
    odbc, conn, cursor = fake_odbc()
    timeouts_at_cursor = []

    def open_cursor():
        timeouts_at_cursor.append(conn.timeout)
        if len(timeouts_at_cursor) == 1:
            raise FakeOdbcError("HYC00", "optional feature not implemented")
        return cursor

    conn.cursor.side_effect = open_cursor
    with caplog.at_level(logging.WARNING, logger="app.db.backends.tibero"):
        with backend(odbc).connection(7) as c:
            assert c is conn
    # 첫 시도는 타임아웃 30 으로, 재시도는 타임아웃 0(없음)으로 열었다.
    assert timeouts_at_cursor == [30, 0]
    assert executed(cursor)[0] == SESSION_INIT_SQL
    warnings = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert any("타임아웃" in m and "HYC00" in m for m in warnings)
    assert not any("pw" in m for m in warnings)
    conn.close.assert_called_once()


def test_cursor_failure_after_timeout_retry_closes_and_raises():
    odbc, conn, _ = fake_odbc()
    conn.cursor.side_effect = FakeOdbcError("08S01", "link failure")
    with pytest.raises(FakeOdbcError):
        with backend(odbc).connection(7):
            pytest.fail("커서를 못 열면 블록에 들어가면 안 된다")
    assert conn.cursor.call_count == 2  # 타임아웃 없이 딱 한 번만 다시 시도한다
    conn.close.assert_called_once()


def test_session_init_is_committed_before_read_only_transaction():
    # 세션 초기화가 연 트랜잭션을 바로 끝내야 SET TRANSACTION READ ONLY 가 트랜잭션 첫 문장이 된다.
    odbc, conn, cursor = fake_odbc()
    order = []
    cursor.execute.side_effect = lambda sql, *a: order.append(sql)
    conn.commit.side_effect = lambda: order.append("COMMIT")
    cursor.description = [col("X")]
    cursor.fetchmany.return_value = []
    b = backend(odbc)
    with b.connection(7) as c:
        b.execute_query("SELECT 1 FROM DUAL", 10, True, c, tenant_id=7)
    assert order == [SESSION_INIT_SQL, "COMMIT", "SET TRANSACTION READ ONLY", "SELECT 1 FROM DUAL"]


def test_connection_rejects_other_tenant():
    odbc, _, _ = fake_odbc()
    with pytest.raises(ValueError):
        with backend(odbc).connection(8):
            pass
    odbc.connect.assert_not_called()


def test_connect_failure_invalidates_store_cache_and_hides_secret():
    odbc, _, _ = fake_odbc()
    odbc.connect.side_effect = FakeOdbcError("28000", "login denied PWD=pw")
    on_fail = MagicMock()
    with pytest.raises(TiberoConnectError) as ei:
        with backend(odbc, on_fail).connection(7):
            pass
    msg = str(ei.value)
    assert "pw" not in msg and "PWD" not in msg
    assert "28000" in msg and "tb-host:8629" in msg
    # 원본 예외(드라이버 메시지에 접속 문자열이 섞일 수 있다)를 체인에서 끊는다.
    assert ei.value.__cause__ is None and ei.value.__suppress_context__
    on_fail.assert_called_once_with(7)


def test_connect_failure_without_sqlstate_uses_fallback():
    odbc, _, _ = fake_odbc()
    odbc.connect.side_effect = FakeOdbcError()
    with pytest.raises(TiberoConnectError) as ei:
        with backend(odbc).connection(7):
            pass
    assert "tb-host:8629" in str(ei.value)


# --- execute_query -----------------------------------------------------------------------------


def run(cursor, conn, sql="SELECT * FROM T", max_rows=100, read_only=True, odbc=None):
    return backend(odbc or fake_odbc()[0]).execute_query(sql, max_rows, read_only, conn, tenant_id=7)


def test_select_lowercases_and_dedupes_column_names():
    _, conn, cursor = fake_odbc()
    cursor.description = [col("ID", 19, 0, int), col("ID", 19, 0, int), col("NAME")]
    cursor.fetchmany.return_value = [(1, 2, "a")]
    res = run(cursor, conn)
    assert res.success
    assert res.columns == ["id", "id_2", "name"]
    assert res.rows == [{"id": 1, "id_2": 2, "name": "a"}]


def test_select_caps_with_fetchmany_and_sets_truncated():
    _, conn, cursor = fake_odbc()
    cursor.description = [col("N", 19, 0, int)]
    cursor.fetchmany.return_value = [(1,), (2,)]
    res = run(cursor, conn, max_rows=2)
    cursor.fetchmany.assert_called_once_with(2)
    cursor.fetchall.assert_not_called()
    assert res.truncated is True
    assert res.row_count == 2


def test_select_lowercases_only_unquoted_uppercase_names():
    # 대문자만인 이름(따옴표 없는 식별자)만 소문자로, 섞인 대소문자(따옴표 별칭)는 그대로 둔다.
    _, conn, cursor = fake_odbc()
    cursor.description = [col("TOTAL", 19, 0, int), col("TotalAmt", 19, 0, int), col("COUNT(*)", 19, 0, int)]
    cursor.fetchmany.return_value = [(1, 2, 3)]
    res = run(cursor, conn)
    assert res.columns == ["total", "TotalAmt", "count(*)"]
    assert res.rows == [{"total": 1, "TotalAmt": 2, "count(*)": 3}]


def test_select_under_cap_is_not_truncated():
    _, conn, cursor = fake_odbc()
    cursor.description = [col("N", 19, 0, int)]
    cursor.fetchmany.return_value = [(1,)]
    res = run(cursor, conn, max_rows=2)
    assert res.truncated is False and res.row_count == 1 and res.query_type == "SELECT"


def test_select_converts_values():
    _, conn, cursor = fake_odbc()
    cursor.description = [
        col("N", 19, 0, Decimal), col("B", 1, 0, Decimal), col("D", 19, 0, datetime), col("M", 18, 6, Decimal),
    ]
    cursor.fetchmany.return_value = [(Decimal("42"), Decimal("1"), datetime(2024, 1, 2), Decimal("1.5"))]
    row = run(cursor, conn).rows[0]
    assert {k: row[k] for k in ("n", "b", "d")} == {"n": 42, "b": True, "d": date(2024, 1, 2)}
    assert type(row["n"]) is int and row["b"] is True and type(row["d"]) is date
    # Decimal 동등 비교는 자릿수 버그를 숨긴다(Decimal("1.5") == Decimal("1.500000")) — 문자열로 비교한다.
    assert isinstance(row["m"], Decimal) and str(row["m"]) == "1.500000"


def test_read_only_select_runs_set_transaction_first_and_rolls_back():
    _, conn, cursor = fake_odbc()
    cursor.description = [col("X")]
    cursor.fetchmany.return_value = []
    res = run(cursor, conn, sql="SELECT 1 FROM DUAL", read_only=True)
    assert res.success
    assert executed(cursor) == ["SET TRANSACTION READ ONLY", "SELECT 1 FROM DUAL"]
    conn.rollback.assert_called_once()
    conn.commit.assert_not_called()
    cursor.close.assert_called_once()


def test_select_without_read_only_skips_set_transaction():
    _, conn, cursor = fake_odbc()
    cursor.description = [col("X")]
    cursor.fetchmany.return_value = []
    run(cursor, conn, sql="SELECT 1 FROM DUAL", read_only=False)
    assert executed(cursor) == ["SELECT 1 FROM DUAL"]
    conn.rollback.assert_called_once()


def test_read_only_rejects_dml_without_db_call():
    _, conn, cursor = fake_odbc()
    res = run(cursor, conn, sql="DELETE FROM T", read_only=True)
    assert res.success is False
    assert res.error == "read_only mode does not allow DELETE statements"
    assert res.query_type == "DELETE"
    cursor.execute.assert_not_called()


def test_empty_query_rejected_with_pg_message():
    _, conn, cursor = fake_odbc()
    res = run(cursor, conn, sql="  -- only comment\n;")
    assert res.success is False and res.error == "Query must not be empty"
    cursor.execute.assert_not_called()


def test_trailing_semicolon_and_comment_stripped():
    _, conn, cursor = fake_odbc()
    cursor.description = [col("X")]
    cursor.fetchmany.return_value = []
    run(cursor, conn, sql="SELECT 1 FROM DUAL; -- x", read_only=False)
    assert executed(cursor) == ["SELECT 1 FROM DUAL"]


def test_dml_commits_and_reports_rowcount():
    _, conn, cursor = fake_odbc()
    cursor.rowcount = 3
    res = run(cursor, conn, sql="UPDATE T SET A = 1", read_only=False)
    assert res.success and res.query_type == "UPDATE"
    assert res.affected_rows == 3 and res.rows == [] and res.truncated is False
    assert executed(cursor) == ["UPDATE T SET A = 1"]
    conn.commit.assert_called_once()
    conn.rollback.assert_not_called()


def test_dml_unknown_rowcount_reports_zero():
    # pyodbc 는 행 수를 모르면 -1 을 준다 — 음수를 그대로 응답에 싣지 않는다.
    _, conn, cursor = fake_odbc()
    cursor.rowcount = -1
    res = run(cursor, conn, sql="UPDATE T SET A = 1", read_only=False)
    assert res.affected_rows == 0


def test_query_error_returns_failure_and_rolls_back():
    _, conn, cursor = fake_odbc()
    cursor.execute.side_effect = FakeOdbcError("42S02", "[Tibero] TBR-8033: Specified schema object was not found.")
    res = run(cursor, conn, sql="SELECT * FROM NO_SUCH", read_only=False)
    assert res.success is False
    assert "8033" in res.error
    assert res.query_type == "SELECT"
    conn.rollback.assert_called_once()
    conn.commit.assert_not_called()
    cursor.close.assert_called_once()


def test_query_error_uses_driver_message_only():
    # pyodbc.Error 의 str() 은 튜플 표기라 SQLSTATE 와 다른 인자까지 섞인다 — 드라이버 메시지(args[1])만 싣는다.
    _, conn, cursor = fake_odbc()
    driver_msg = "[Tibero] TBR-8033: Specified schema object was not found."
    cursor.execute.side_effect = FakeOdbcError("42S02", driver_msg, "DRIVER={Tibero};PWD=pw")
    res = run(cursor, conn, sql="SELECT * FROM NO_SUCH", read_only=False)
    assert res.error == driver_msg
    assert "PWD" not in res.error and "42S02" not in res.error


@pytest.mark.parametrize("exc,expected", [
    (FakeOdbcError("HY000"), "HY000"),
    (FakeOdbcError("HY000", 123), "('HY000', 123)"),
    (RuntimeError("plain"), "plain"),
])
def test_query_error_falls_back_to_str_without_string_message(exc, expected):
    _, conn, cursor = fake_odbc()
    cursor.execute.side_effect = exc
    res = run(cursor, conn, sql="SELECT 1 FROM DUAL", read_only=False)
    assert res.success is False and res.error == expected


def test_execute_query_rejects_other_tenant():
    _, conn, cursor = fake_odbc()
    b = backend(fake_odbc()[0])
    with pytest.raises(ValueError):
        b.execute_query("SELECT 1 FROM DUAL", 10, True, conn, tenant_id=8)
    cursor.execute.assert_not_called()


# --- insert_batch ------------------------------------------------------------------------------


def test_insert_batch_uses_qualified_insert_and_binds():
    _, conn, cursor = fake_odbc()
    backend(fake_odbc()[0]).insert_batch(conn, "sales", [{"region": "서울", "flag": True}])
    cursor.executemany.assert_called_once_with(
        insert_sql("GENIA_T7", "sales", ["region", "flag"]), [("서울", 1)]
    )
    conn.commit.assert_not_called()
    cursor.close.assert_called_once()


def test_insert_batch_chunks_500():
    _, conn, cursor = fake_odbc()
    rows = [{"n": i} for i in range(1001)]
    backend(fake_odbc()[0]).insert_batch(conn, "sales", rows)
    sizes = [len(c.args[1]) for c in cursor.executemany.call_args_list]
    assert sizes == [500, 500, 1]


def test_insert_batch_empty_rows_is_noop():
    _, conn, cursor = fake_odbc()
    backend(fake_odbc()[0]).insert_batch(conn, "sales", [])
    conn.cursor.assert_not_called()


@pytest.mark.parametrize("table,column", [("Sales", "region"), ("sales", "x;y"), ("sales", "id")])
def test_insert_batch_rejects_bad_table_or_column(table, column):
    _, conn, cursor = fake_odbc()
    with pytest.raises(ValueError):
        backend(fake_odbc()[0]).insert_batch(conn, table, [{column: 1}])
    cursor.execute.assert_not_called()
    cursor.executemany.assert_not_called()


# --- backend_for -------------------------------------------------------------------------------


def test_backend_for_selects_by_descriptor():
    settings = get_test_settings()
    client = MagicMock()
    with patch("app.db.backends.get_store_client", return_value=client) as gsc:
        client.get.return_value = StoreDescriptor.model_validate({"tenantId": 7, "backend": "POSTGRESQL"})
        pg = backend_for(7, settings)
        assert isinstance(pg, PostgresBackend)

        client.get.return_value = descriptor()
        tb = backend_for(7, settings)
    assert isinstance(tb, TiberoBackend)
    assert tb._on_connect_failure == client.invalidate
    gsc.assert_called_with(settings)
    client.get.assert_called_with(7)


def test_backend_for_propagates_lookup_error():
    client = MagicMock()
    client.get.side_effect = StoreLookupError("데이터셋 저장소 정보를 조회하지 못했습니다")
    with patch("app.db.backends.get_store_client", return_value=client):
        with pytest.raises(StoreLookupError):
            backend_for(7, get_test_settings())
