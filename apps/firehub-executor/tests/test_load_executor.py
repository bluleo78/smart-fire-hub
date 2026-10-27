"""``/execute/load``(PG) 와 ``/execute/sql`` 의 ``timeout_seconds`` 테스트(WD-77).

db-executor(Tibero) 와 같은 요청·응답 모양을 검증한다. DB 는 쓰지 않고 연결·커서를 대역으로 둔다.
"""
from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from contextlib import contextmanager
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from app.config import Settings
from app.routers import store_gate
from app.services import load_executor, sql_executor
from tests.conftest import PgError

# 서비스 함수는 기본 제한을 갖지 않는다(기본값은 Settings 한 곳) — 단위 테스트는 라우터처럼 설정 기본값을 넘긴다.
SQL_DEFAULT_TIMEOUT = Settings.model_fields["sql_statement_timeout_seconds"].default
LOAD_DEFAULT_TIMEOUT = Settings.model_fields["load_statement_timeout_seconds"].default


def _load(*args, **kwargs):
    kwargs.setdefault("default_timeout_seconds", LOAD_DEFAULT_TIMEOUT)
    return load_executor.execute_load(*args, **kwargs)


def _sql(*args, **kwargs):
    kwargs.setdefault("default_timeout_seconds", SQL_DEFAULT_TIMEOUT)
    kwargs.setdefault(
        "pre_statement_timeout_seconds",
        Settings.model_fields["sql_pre_statement_timeout_seconds"].default,
    )
    return sql_executor.execute_sql(*args, **kwargs)


class _PgError(Exception):
    """psycopg2 오류 대역 — pgcode 만 있으면 sqlstate() 가 SQLState 로 인식한다."""

    pgcode = "23505"


def _conn():
    conn = MagicMock()
    cur = MagicMock()
    cur.__enter__.return_value = cur
    conn.cursor.return_value = cur
    return conn, cur


def _executed(cur):
    return [c.args[0] for c in cur.execute.call_args_list]


@contextmanager
def _patch_insert(**page_kwargs):
    """INSERT 조립(``build_insert_query``)과 페이지 실행(``execute_insert_page``)을 대역으로 둔다 — psycopg2 의 문장 조립·
    ``execute_values`` 는 모의 커서로 동작하지 않는다. 조립 결과는 ``"INSERT"`` 이고, 페이지 대역(호출 인자: 커서·문장·컬럼·행·페이지
    크기)을 돌려준다."""
    with patch("app.services.load_executor.build_insert_query", return_value="INSERT"), \
            patch("app.services.load_executor.execute_insert_page", **page_kwargs) as page:
        yield page


# ---------------------------------------------------------------------------
# 서비스 단위
# ---------------------------------------------------------------------------

def test_load_inserts_rows_and_commits():
    conn, cur = _conn()
    rows = [{"a": 1, "b": "x"}, {"a": 2, "b": "y"}]
    with _patch_insert() as ins:
        res = _load("rtmp_ab12", rows, None, conn)
    assert res.success is True and res.rows_loaded == 2
    assert res.error is None and res.db_error is None
    ins.assert_called_once_with(cur, "INSERT", ["a", "b"], rows, load_executor.PAGE_SIZE)
    conn.commit.assert_called_once()
    conn.rollback.assert_not_called()


def test_load_applies_column_type_map():
    conn, _ = _conn()
    rows = [{"n": "42", "d": "2024-03-05", "t": "2024-03-05T01:02:03", "m": "1.5", "f": "yes"}]
    types = {"n": "INTEGER", "d": "DATE", "t": "TIMESTAMP", "m": "DECIMAL", "f": "BOOLEAN"}
    with _patch_insert() as ins:
        _load("out_tbl", rows, types, conn)
    row = ins.call_args.args[3][0]
    assert row == {
        "n": 42,
        "d": date(2024, 3, 5),
        "t": datetime(2024, 3, 5, 1, 2, 3),
        "m": Decimal("1.5"),
        "f": True,
    }


def test_load_fills_missing_keys_with_none_in_first_seen_order():
    conn, _ = _conn()
    with _patch_insert() as ins:
        res = _load("t1", [{"a": 1}, {"b": "x"}], None, conn)
    assert res.success is True
    assert ins.call_args.args[3] == [{"a": 1, "b": None}, {"a": None, "b": "x"}]


def test_load_empty_rows_is_success_without_touching_db():
    conn, _ = _conn()
    with _patch_insert() as ins:
        res = _load("t1", [], None, conn)
    assert res.success is True and res.rows_loaded == 0
    ins.assert_not_called()
    conn.commit.assert_not_called()


@pytest.mark.parametrize(
    "table",
    ["", "Orders", "1abc", 'a"b', "a b", "public.t", "t\n", "x" * 64, "stg_x; drop table y", "한글"],
)
def test_load_rejects_bad_table_before_any_sql(table):
    conn, _ = _conn()
    with _patch_insert() as ins:
        res = _load(table, [{"a": 1}], None, conn)
    assert res.success is False and res.error == load_executor.BAD_TABLE
    assert res.db_error.message == load_executor.BAD_TABLE and res.db_error.sql_state is None
    ins.assert_not_called()
    conn.cursor.assert_not_called()


@pytest.mark.parametrize("col", ['a"b', "A", "1a", "a b", "_seq", "a;b"])
def test_load_rejects_bad_column(col):
    conn, _ = _conn()
    with _patch_insert() as ins:
        res = _load("t1", [{col: 1}], None, conn)
    assert res.success is False and res.error == load_executor.BAD_COLUMN
    ins.assert_not_called()


@pytest.mark.parametrize("bad_rows", [[1], [{"a": 1}, None], ["x"], [{}], [{"a": 1}, []]])
def test_load_non_dict_or_empty_row_is_failure_not_exception(bad_rows):
    conn, _ = _conn()
    with _patch_insert() as ins:
        res = _load("t1", bad_rows, None, conn)
    assert res.success is False and res.rows_loaded == 0
    assert res.db_error.message == res.error
    ins.assert_not_called()
    conn.commit.assert_not_called()


def test_load_conversion_error_is_failure_not_exception():
    conn, _ = _conn()
    with _patch_insert(), patch(
        "app.services.load_executor._apply_type_conversion", side_effect=RuntimeError("변환 폭발")
    ):
        res = _load("t1", [{"a": 1}], {"a": "INTEGER"}, conn)
    assert res.success is False and "변환 폭발" in res.error
    assert res.db_error is not None


def test_load_rollback_failure_does_not_mask_original_error():
    conn, _ = _conn()
    conn.rollback.side_effect = RuntimeError("연결 끊김")
    with _patch_insert(side_effect=_PgError("duplicate key")):
        res = _load("t1", [{"a": 1}], None, conn)
    assert res.success is False and "duplicate key" in res.error
    assert res.db_error.sql_state == "23505"


def test_load_failure_rolls_back_with_db_error():
    conn, _ = _conn()
    with _patch_insert(side_effect=_PgError("duplicate key")):
        res = _load("t1", [{"a": 1}], None, conn)
    assert res.success is False and res.rows_loaded == 0
    assert "duplicate key" in res.error
    assert res.db_error.code == 0 and res.db_error.sql_state == "23505"
    conn.rollback.assert_called_once()
    conn.commit.assert_not_called()


def test_load_timeout_sets_local_statement_timeout_before_insert():
    conn, cur = _conn()
    order = []
    cur.execute.side_effect = lambda sql, *a: order.append(sql)
    with _patch_insert(side_effect=lambda *a, **kw: order.append("INSERT")):
        _load("t1", [{"a": 1}], None, conn, timeout_seconds=90)
    assert order == ["SET LOCAL statement_timeout = 90000", "INSERT"]


def test_load_without_timeout_uses_1800_second_default():
    conn, cur = _conn()
    with _patch_insert():
        _load("t1", [{"a": 1}], None, conn)
    assert _executed(cur) == ["SET LOCAL statement_timeout = 1800000"]


def test_load_uses_configured_default_when_request_has_no_timeout():
    conn, cur = _conn()
    with _patch_insert():
        _load("t1", [{"a": 1}], None, conn, default_timeout_seconds=7)
    assert _executed(cur) == ["SET LOCAL statement_timeout = 7000"]


# ---------------------------------------------------------------------------
# /execute/sql timeout_seconds
# ---------------------------------------------------------------------------

def test_sql_timeout_seconds_sets_local_statement_timeout_first():
    conn, cur = _conn()
    cur.rowcount = 1
    res = _sql(
        "INSERT INTO t SELECT 1", conn, [], timeout_seconds=120
    )
    assert res.success is True
    assert _executed(cur)[0] == "SET LOCAL statement_timeout = 120000"
    assert _executed(cur)[1] == "INSERT INTO t SELECT 1"


def test_sql_without_timeout_seconds_uses_50_second_default():
    conn, cur = _conn()
    cur.rowcount = 1
    _sql("INSERT INTO t SELECT 1", conn, [])
    assert _executed(cur) == ["SET LOCAL statement_timeout = 50000", "INSERT INTO t SELECT 1"]


def test_sql_uses_configured_default_when_request_has_no_timeout():
    conn, cur = _conn()
    _sql("INSERT INTO t SELECT 1", conn, default_timeout_seconds=7)
    assert _executed(cur) == ["SET LOCAL statement_timeout = 7000", "INSERT INTO t SELECT 1"]


def _runs_then_raises(clock, seconds, exc):
    """문장이 ``seconds`` 초 돌다가 ``exc`` 로 끝나는 대역 — 판정은 그 문장의 경과 시간만 본다(WD-261)."""

    def run(*_args, **_kwargs):
        clock.advance(seconds)
        raise exc

    return run


def test_sql_pg_timeout_returns_korean_message_and_rolls_back(fake_clock):
    # 본문이 제한(50초)만큼 돌고 57014 → 시간 초과. db-executor 처럼 구조화 오류 없이(db_error·pg_error 없음) 한국어 문구만 보낸다 —
    # api 는 구조화 오류가 없으면 실행기 문구를 그대로 보여 준다(WD-261 §5.3).
    conn, cur = _conn()
    body = _runs_then_raises(fake_clock, 50, PgError("canceling statement due to statement timeout", "57014"))
    cur.execute.side_effect = lambda sql, *a: body() if sql == "INSERT INTO t SELECT 1" else None
    res = _sql("INSERT INTO t SELECT 1", conn)
    assert res.success is False
    assert res.error == "실행 시간이 50초를 넘어 취소했습니다."
    assert res.db_error is None and res.pg_error is None
    conn.rollback.assert_called_once()


def test_load_pg_timeout_returns_korean_message_and_rolls_back(fake_clock):
    conn, _ = _conn()
    insert = _runs_then_raises(fake_clock, 1800, PgError("canceling statement due to statement timeout", "57014"))
    with _patch_insert(side_effect=insert):
        res = _load("t1", [{"a": 1}], None, conn)
    assert res.success is False
    assert res.error == "적재 시간이 1800초를 넘어 취소했습니다."
    assert res.db_error is None
    conn.rollback.assert_called_once()


def test_admin_cancel_before_limit_keeps_original(fake_clock):
    # 57014 는 관리자 취소(pg_cancel_backend)에서도 난다 — 제한(50초)보다 일찍 끝났으면 「N초 초과」가 아니라 원문과 구조화 오류를
    # 그대로 둔다(api 가 「시간 제한을 넘거나 취소되어」 안내 + 원문으로 보여 준다). 원문에 영어 'statement timeout' 이 있어도 같다.
    conn, cur = _conn()
    body = _runs_then_raises(fake_clock, 3, PgError("canceling statement due to statement timeout", "57014"))
    cur.execute.side_effect = lambda sql, *a: body() if sql == "INSERT INTO t SELECT 1" else None
    res = _sql("INSERT INTO t SELECT 1", conn)
    assert res.success is False
    assert res.error == "canceling statement due to statement timeout"
    assert res.db_error.sql_state == "57014"
    assert res.pg_error.sql_state == "57014"


def test_sql_pg_user_cancel_keeps_original_message(fake_clock):
    # 57014 는 관리자 취소(pg_cancel_backend)에서도 난다 — 그때는 「N초 초과」가 아니라 원문을 그대로 둔다(리뷰 M-5).
    conn, cur = _conn()
    cur.execute.side_effect = [None, PgError("canceling statement due to user request", "57014")]
    res = _sql("INSERT INTO t SELECT 1", conn)
    assert res.success is False
    assert res.error == "canceling statement due to user request"
    assert res.db_error.sql_state == "57014"


def test_load_pg_user_cancel_keeps_original_message(fake_clock):
    conn, _ = _conn()
    with _patch_insert(side_effect=PgError("canceling statement due to user request", "57014")):
        res = _load("t1", [{"a": 1}], None, conn)
    assert res.success is False
    assert res.error == "canceling statement due to user request"
    assert res.db_error.sql_state == "57014"


def _rows(n):
    return [{"a": i} for i in range(n)]


def test_load_inserts_in_pages_of_100():
    # 페이지(문장)마다 시작 시각을 재려고 적재를 100행씩 나눠 보낸다 — execute_values 의 기본 페이지 크기와 같아 나가는 문장은 그대로다.
    # INSERT 조립은 한 번이고, 모든 페이지가 같은 커서·같은 문장으로 나간다.
    conn, cur = _conn()
    with patch("app.services.load_executor.build_insert_query", return_value="INSERT") as build, \
            patch("app.services.load_executor.execute_insert_page") as insert:
        res = _load("t1", _rows(250), None, conn)
    assert res.success is True and res.rows_loaded == 250
    build.assert_called_once_with(cur, "t1", ["a"])
    assert [len(c.args[3]) for c in insert.call_args_list] == [100, 100, 50]
    assert all(c.args[:3] == (cur, "INSERT", ["a"]) for c in insert.call_args_list)
    assert all(c.args[4] == load_executor.PAGE_SIZE for c in insert.call_args_list)
    conn.commit.assert_called_once()


def test_load_timeout_measured_per_page(fake_clock):
    # 앞 페이지들이 합쳐서 제한(60초)보다 오래 걸려도, 마지막 페이지가 제한 전에(5초 만에) 취소됐으면 시간 초과가 아니다 — 문장 제한은
    # 페이지(문장)마다 걸리므로 그 페이지의 경과 시간으로 판정한다(WD-261 §5.2).
    conn, _ = _conn()
    calls = []

    def insert(_cur, _query, _columns, page, _page_size):
        calls.append(len(page))
        if len(calls) < 3:
            fake_clock.advance(55)
            return
        fake_clock.advance(5)
        raise PgError("canceling statement due to user request", "57014")

    with _patch_insert(side_effect=insert):
        res = _load("t1", _rows(250), None, conn, timeout_seconds=60)
    assert calls == [100, 100, 50]
    assert res.success is False
    assert res.error == "canceling statement due to user request"
    assert res.db_error.sql_state == "57014"


def test_load_timeout_on_one_page_reaching_limit(fake_clock):
    # 반대로 한 페이지가 제한만큼 돌았으면 시간 초과다(앞 페이지가 빨랐어도).
    conn, _ = _conn()
    calls = []

    def insert(_cur, _query, _columns, page, _page_size):
        calls.append(len(page))
        if len(calls) < 2:
            fake_clock.advance(1)
            return
        fake_clock.advance(60)
        raise PgError("x", "57014")

    with _patch_insert(side_effect=insert):
        res = _load("t1", _rows(250), None, conn, timeout_seconds=60)
    assert calls == [100, 100]
    assert res.error == "적재 시간이 60초를 넘어 취소했습니다."
    assert res.db_error is None


# ---------------------------------------------------------------------------
# HTTP 라우트
# ---------------------------------------------------------------------------

def _gate(backend: str):
    gate = MagicMock()
    gate.peek.return_value = SimpleNamespace(backend=backend)
    return gate


def _conn_cm(conn):
    cm = MagicMock()
    cm.return_value.__enter__.return_value = conn
    return cm


def test_route_load_pg_success(test_client, mock_auth_header):
    conn, cur = _conn()
    with patch("app.routers.store_gate.get_store_client", return_value=_gate("POSTGRESQL")), \
            patch("app.routers.load.get_connection", _conn_cm(conn)), \
            _patch_insert() as ins:
        resp = test_client.post(
            "/execute/load",
            json={
                "tenantId": 7,
                "table": "rtmp_ab12",
                "rows": [{"a": "1"}],
                "column_type_map": {"a": "INTEGER"},
                "timeout_seconds": 30,
            },
            headers=mock_auth_header,
        )
    assert resp.status_code == 200
    assert resp.json() == {"success": True, "rows_loaded": 1, "error": None, "db_error": None}
    ins.assert_called_once_with(cur, "INSERT", ["a"], [{"a": 1}], load_executor.PAGE_SIZE)
    conn.commit.assert_called_once()


def test_route_load_tibero_refused_without_opening_connection(test_client, mock_auth_header):
    opener = MagicMock()
    with patch("app.routers.store_gate.get_store_client", return_value=_gate("TIBERO")), \
            patch("app.routers.load.get_connection", opener):
        data = test_client.post(
            "/execute/load",
            json={"tenantId": 7, "table": "rtmp_ab12", "rows": [{"a": 1}]},
            headers=mock_auth_header,
        ).json()
    assert data["success"] is False and data["rows_loaded"] == 0
    assert data["error"] == store_gate.LOAD_UNSUPPORTED
    assert data["db_error"]["message"] == store_gate.LOAD_UNSUPPORTED
    opener.assert_not_called()


@pytest.mark.parametrize(
    "body",
    [
        {"table": "t1", "rows": []},
        {"tenantId": 7, "rows": []},
        {"tenantId": 7, "table": "t1"},
        {"tenantId": 7, "table": "t1", "rows": [], "timeout_seconds": 0},
        {"tenantId": 7, "table": "t1", "rows": [], "timeout_seconds": -3},
    ],
)
def test_route_load_invalid_request_is_422(test_client, mock_auth_header, body):
    assert test_client.post("/execute/load", json=body, headers=mock_auth_header).status_code == 422


def test_route_sql_timeout_seconds_must_be_positive(test_client, mock_auth_header):
    resp = test_client.post(
        "/execute/sql",
        json={"tenantId": 7, "query": "SELECT 1", "timeout_seconds": 0},
        headers=mock_auth_header,
    )
    assert resp.status_code == 422


def test_route_sql_passes_timeout_seconds_to_executor(test_client, mock_auth_header):
    conn, _ = _conn()
    with patch("app.routers.store_gate.get_store_client", return_value=_gate("POSTGRESQL")), \
            patch("app.routers.sql.get_connection", _conn_cm(conn)), \
            patch("app.routers.sql.sql_executor.execute_sql") as ex:
        ex.return_value = sql_executor.SqlExecuteResponse(
            success=True, row_count=0, execution_log=""
        )
        test_client.post(
            "/execute/sql",
            json={"tenantId": 7, "query": "SELECT 1", "timeout_seconds": 45},
            headers=mock_auth_header,
        )
    assert ex.call_args.args[3] == 45


def test_execute_insert_page_passes_page_size_to_execute_values():
    # 「한 번 호출 = 한 문장(페이지)」 전제: 적재가 명시한 page_size 가 execute_values 까지 그대로 간다.
    from app.services import db_utils

    cur = MagicMock()
    with patch("app.services.db_utils.execute_values") as ev:
        db_utils.execute_insert_page(cur, "INSERT", ["a", "b"], [{"a": 1, "b": "x"}], load_executor.PAGE_SIZE)
    assert ev.call_args.args == (cur, "INSERT", [(1, "x")])
    assert ev.call_args.kwargs["page_size"] == load_executor.PAGE_SIZE
