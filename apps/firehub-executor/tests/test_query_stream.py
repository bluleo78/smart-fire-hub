"""슬롯 스트리밍 조회(WD-78) — ``/execute/query`` + ``readSlot`` + ``stream:true``.

db-executor(Tibero) 와 같은 NDJSON 계약(``executor-db-contract-vectors.json`` 의 ``query.streaming``)을 PG 슬롯 롤로 낸다.
api 의 PG 경로는 이 창구를 쓰지 않는다(대칭용). DB 는 psycopg2 대역으로 흉내 낸다 — 서버 측(named) 커서는 첫 fetch 뒤에야
``description`` 이 생기는 동작까지 따라 한다.
"""
from __future__ import annotations

import datetime as dt
import json
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from app.schemas.responses import QueryExecuteResponse
from app.services import query_executor
from tests.conftest import get_test_settings

_FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "firehub-api/src/test/resources/fixtures/executor-db-contract-vectors.json"
)
_STREAMING = json.loads(_FIXTURE.read_text(encoding="utf-8"))["endpoints"]["query"]["streaming"]


class _PgError(Exception):
    """psycopg2 오류 대역 — pgcode 만 있으면 sqlstate() 가 SQLState 로 인식한다."""

    pgcode = "42P01"


class _NamedCursor:
    """서버 측 커서 대역: fetchmany 로 나눠 주고, 첫 fetch 뒤에 description 이 생긴다."""

    def __init__(self, rows, columns, fail_after=None):
        self._rows = rows
        self._columns = columns
        self._fail_after = fail_after
        self._pos = 0
        self.description = None
        self.executed = []
        self.closed = False
        self.itersize = None
        self.fetch_calls = 0

    def execute(self, sql):
        self.executed.append(sql)

    def fetchmany(self, size):
        self.fetch_calls += 1
        self.description = [(c, 23, None, 4, None, None, None) for c in self._columns]
        if self._fail_after is not None and self._pos >= self._fail_after:
            raise _PgError('relation "t" does not exist')
        end = len(self._rows) if self._fail_after is None else min(len(self._rows), self._fail_after)
        end = min(end, self._pos + size)
        batch = self._rows[self._pos:end]
        self._pos = end
        return batch

    def close(self):
        self.closed = True


class _Conn:
    def __init__(self, named):
        self.named = named
        self.setup = MagicMock()
        self.setup.__enter__.return_value = self.setup
        self.session = None
        self.rolled_back = False
        self.closed = False
        self.cursor_names = []

    def set_session(self, **kw):
        self.session = kw

    def cursor(self, name=None):
        self.cursor_names.append(name)
        return self.named if name else self.setup

    def rollback(self):
        self.rolled_back = True

    def close(self):
        self.closed = True


def _rows(n):
    return [(i,) for i in range(1, n + 1)]


def _run(query, rows=(), columns=("id",), fail_after=None, slot=2):
    named = _NamedCursor(list(rows), list(columns), fail_after)
    conn = _Conn(named)
    with patch("app.services.query_executor.psycopg2.connect", return_value=conn) as connect:
        body = b"".join(
            query_executor.stream_slot_query(query, tenant_id=5, read_slot=slot, settings=get_test_settings())
        )
    lines = [json.loads(l) for l in body.decode("utf-8").split("\n") if l]
    assert body.endswith(b"\n")
    return lines, conn, connect


def test_streams_10001_rows_without_truncation():
    lines, conn, connect = _run("SELECT id FROM t;", rows=_rows(10_001))

    assert lines[0] == {"columns": ["id"]}
    assert len(lines) == 1 + 10_001 + 1
    assert [l["row"]["id"] for l in lines[1:-1]] == list(range(1, 10_002))
    assert lines[-1] == {"success": True, "row_count": 10_001}
    # 슬롯 롤로 직접 로그인한다(테넌트 실행 롤 폴백 없음).
    url = connect.call_args.args[0]
    assert url.startswith("postgresql://pipeline_py_t5_s2:")
    # 읽기 전용 트랜잭션, 테넌트 스키마, 행 제한 없음(LIMIT 을 붙이지 않는다), 서버 측 커서.
    assert conn.session == {"readonly": True}
    setup_sql = conn.setup.execute.call_args.args[0]
    assert "SET LOCAL search_path = 'data_t5', 'public'" in setup_sql
    assert conn.named.executed == ["SELECT id FROM t"]
    assert conn.cursor_names[-1]
    assert conn.named.itersize == query_executor.STREAM_FETCH_SIZE
    assert conn.named.closed and conn.rolled_back and conn.closed


def test_empty_result_still_has_columns_and_success_line():
    lines, conn, _ = _run("SELECT id FROM t", rows=[])
    assert lines == [{"columns": ["id"]}, {"success": True, "row_count": 0}]
    assert conn.closed


def test_mid_stream_db_error_ends_with_failure_line():
    lines, conn, _ = _run("SELECT id FROM t", rows=_rows(10), fail_after=3)

    assert lines[0] == {"columns": ["id"]}
    assert [l["row"]["id"] for l in lines[1:-1]] == [1, 2, 3]
    last = lines[-1]
    assert last["success"] is False
    assert "does not exist" in last["error"]
    assert last["db_error"]["sql_state"] == "42P01"
    assert "row_count" not in last
    assert conn.closed and conn.rolled_back


@pytest.mark.parametrize("sql", ["DELETE FROM t", "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x", "", "  ;  "])
def test_non_select_rejected_before_connecting(sql):
    with patch("app.services.query_executor.psycopg2.connect") as connect:
        body = b"".join(
            query_executor.stream_slot_query(sql, tenant_id=5, read_slot=2, settings=get_test_settings())
        )
    lines = [json.loads(l) for l in body.decode("utf-8").splitlines()]
    assert len(lines) == 1
    assert lines[0]["success"] is False and lines[0]["error"]
    assert set(lines[0]) == {"success", "error", "db_error"}
    connect.assert_not_called()


def test_connect_failure_is_fixed_message_without_libpq_text():
    """접속 실패는 libpq 원문(호스트·롤 이름)을 싣지 않은 고정 문구다(db-executor CONNECT_FAILED 와 같은 취지). 접속 대기 상한을 건다."""
    libpq = 'connection to server at "db.internal" (10.0.0.5), port 5432 failed: FATAL: password authentication failed for user "pipeline_py_t5_s2"'
    with patch("app.services.query_executor.psycopg2.connect", side_effect=_PgError(libpq)) as connect:
        body = b"".join(
            query_executor.stream_slot_query("SELECT 1", tenant_id=5, read_slot=2, settings=get_test_settings())
        )
    lines = [json.loads(l) for l in body.decode("utf-8").splitlines()]
    assert len(lines) == 1 and lines[0]["success"] is False
    assert lines[0]["error"] == query_executor.STREAM_CONNECT_FAILED
    assert "db.internal" not in body.decode("utf-8") and "pipeline_py" not in body.decode("utf-8")
    assert lines[0]["db_error"]["message"] == query_executor.STREAM_CONNECT_FAILED
    assert connect.call_args.kwargs["connect_timeout"] == query_executor.STREAM_CONNECT_TIMEOUT_SECONDS


def test_consumer_stopping_early_closes_cursor_and_connection():
    """받는 쪽이 중간에 끊으면(제너레이터 close) 서버 측 커서를 닫고 롤백한 뒤 연결을 닫는다."""
    named = _NamedCursor(_rows(100_000), ["id"])
    conn = _Conn(named)
    with patch("app.services.query_executor.psycopg2.connect", return_value=conn):
        gen = query_executor.stream_slot_query("SELECT id FROM t", tenant_id=5, read_slot=2, settings=get_test_settings())
        next(gen)
        next(gen)
        gen.close()
    assert named.closed and conn.rolled_back and conn.closed
    assert named.fetch_calls < 100_000 // query_executor.STREAM_FETCH_SIZE


def test_values_serialize_like_non_stream_response():
    """값 표기는 비스트리밍 응답(pydantic 직렬화 + to_json_value)과 같다."""
    row = (Decimal("1.50"), dt.datetime(2026, 1, 2, 3, 4, 5), memoryview(b"\x01\x02"), None)
    cols = ("amount", "created", "raw", "nothing")
    lines, _, _ = _run("SELECT * FROM t", rows=[row], columns=cols)
    expected = json.loads(QueryExecuteResponse(success=True, rows=[dict(zip(cols, row))]).model_dump_json())["rows"][0]
    assert lines[1]["row"] == expected


def test_duplicate_column_names_are_unique():
    lines, _, _ = _run("SELECT a.id, b.id FROM a, b", rows=[(1, 2)], columns=("id", "id"))
    assert lines[0] == {"columns": ["id", "id_2"]}
    assert lines[1] == {"row": {"id": 1, "id_2": 2}}


# ---------------------------------------------------------------------------
# 계약 벡터(query.streaming) — db-executor SlotStreamContractVectorsTest 와 같은 예시
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("ex", _STREAMING["examples"], ids=lambda e: e["name"])
def test_streaming_vectors(ex, test_client, mock_auth_header):
    req = ex["request"]
    if ex.get("requestLevel"):
        # 요청 형식 단계 거부(read_only 누락 등)는 창구로 보낸다 — 실행하지 않고 NDJSON 실패 줄 하나.
        with patch("app.routers.store_gate.get_store_client", return_value=_gate("POSTGRESQL")), \
                patch("app.services.query_executor.psycopg2.connect", side_effect=AssertionError("접속하면 안 된다")) as connect:
            resp = test_client.post("/execute/query", json=req, headers=mock_auth_header)
        assert resp.status_code == 200
        assert resp.headers["content-type"] == _STREAMING["contentType"]
        lines = [json.loads(l) for l in resp.text.splitlines()]
        connect.assert_not_called()
    else:
        lines, _, _ = _run(
            req["query"], rows=_rows(ex["resultRows"]), fail_after=ex.get("failAfterRows"), slot=req["readSlot"]
        )
    last = lines[-1]
    assert last["success"] is ex["expectSuccess"]
    key = "successLastLineKeys" if ex["expectSuccess"] else "failureLastLineKeys"
    assert list(last) == _STREAMING[key]
    if "expectLines" in ex:
        assert len(lines) == ex["expectLines"]
    else:
        n = ex["expectRowLines"]
        assert len(lines) == 1 + n + 1
        assert list(lines[0]) == _STREAMING["firstLineKeys"]
        assert all(list(l) == _STREAMING["rowLineKeys"] for l in lines[1:-1])
        if ex["expectSuccess"]:
            assert last["row_count"] == n


# ---------------------------------------------------------------------------
# 라우트
# ---------------------------------------------------------------------------


def _gate(backend):
    gate = MagicMock()
    gate.peek.return_value = SimpleNamespace(backend=backend)
    return gate


_STREAM_BODY = {"tenantId": 5, "query": "SELECT id FROM t", "read_only": True, "readSlot": 2, "stream": True}


def test_route_streams_ndjson(test_client, mock_auth_header):
    named = _NamedCursor(_rows(3), ["id"])
    conn = _Conn(named)
    with patch("app.routers.store_gate.get_store_client", return_value=_gate("POSTGRESQL")), \
            patch("app.services.query_executor.psycopg2.connect", return_value=conn), \
            patch("app.db.connection.get_connection") as pooled:
        resp = test_client.post("/execute/query", json=_STREAM_BODY, headers=mock_auth_header)
    assert resp.status_code == 200
    assert resp.headers["content-type"] == _STREAMING["contentType"]
    lines = [json.loads(l) for l in resp.text.splitlines()]
    assert lines[0] == {"columns": ["id"]}
    assert lines[-1] == {"success": True, "row_count": 3}
    # 테넌트 실행 롤 풀을 쓰지 않는다.
    pooled.assert_not_called()


def test_route_tibero_tenant_single_failure_line(test_client, mock_auth_header):
    from app.routers import store_gate

    with patch("app.routers.store_gate.get_store_client", return_value=_gate("TIBERO")), \
            patch("app.services.query_executor.psycopg2.connect") as connect:
        resp = test_client.post("/execute/query", json=_STREAM_BODY, headers=mock_auth_header)
    assert resp.status_code == 200
    assert resp.headers["content-type"] == _STREAMING["contentType"]
    lines = [json.loads(l) for l in resp.text.splitlines()]
    assert lines == [{"success": False, "error": store_gate.QUERY_UNSUPPORTED, "db_error": lines[0]["db_error"]}]
    assert lines[0]["db_error"]["message"] == store_gate.QUERY_UNSUPPORTED
    connect.assert_not_called()


@pytest.mark.parametrize(
    "body",
    [
        {"tenantId": 5, "query": "SELECT 1", "read_only": True, "readSlot": 2},
        {"tenantId": 5, "query": "SELECT 1", "read_only": True, "readSlot": 2, "stream": False},
        {"tenantId": 5, "query": "SELECT 1", "read_only": True, "stream": True},
        {"tenantId": 5, "query": "SELECT 1", "readSlot": 11, "stream": True},
        {"tenantId": 5, "query": "SELECT 1", "readSlot": "2", "stream": True},
    ],
)
def test_route_rejects_partial_or_invalid_slot_requests(test_client, mock_auth_header, body):
    with patch("app.routers.store_gate.get_store_client", return_value=_gate("POSTGRESQL")), \
            patch("app.services.query_executor.psycopg2.connect") as connect, \
            patch("app.db.connection.get_connection") as pooled:
        resp = test_client.post("/execute/query", json=body, headers=mock_auth_header)
    assert resp.status_code == 422
    connect.assert_not_called()
    pooled.assert_not_called()


def test_route_requires_internal_auth(test_client):
    """인증은 기존 내부 토큰 그대로다(헤더 누락은 기존처럼 422, 틀린 토큰은 401)."""
    with patch("app.services.query_executor.psycopg2.connect") as connect:
        resp = test_client.post("/execute/query", json=_STREAM_BODY, headers={"Authorization": "Internal nope"})
    assert resp.status_code == 401
    connect.assert_not_called()


@pytest.mark.parametrize("read_only", [None, False])
def test_route_stream_requires_read_only(test_client, mock_auth_header, read_only):
    """스트리밍은 db-executor 처럼 read_only:true 를 요구한다 — 아니면 실행 전에 NDJSON 실패 줄 하나(Minor 3)."""
    body = dict(_STREAM_BODY)
    if read_only is None:
        body.pop("read_only")
    else:
        body["read_only"] = read_only
    with patch("app.routers.store_gate.get_store_client", return_value=_gate("POSTGRESQL")), \
            patch("app.services.query_executor.psycopg2.connect", side_effect=AssertionError("접속하면 안 된다")) as connect:
        resp = test_client.post("/execute/query", json=body, headers=mock_auth_header)
    assert resp.status_code == 200
    assert resp.headers["content-type"] == _STREAMING["contentType"]
    lines = [json.loads(l) for l in resp.text.splitlines()]
    assert len(lines) == 1
    assert lines[0]["success"] is False
    assert lines[0]["error"] == query_executor.STREAM_READ_ONLY_REQUIRED
    assert query_executor.STREAM_READ_ONLY_REQUIRED == "읽기 전용 조회만 실행할 수 있습니다."
    connect.assert_not_called()


def test_route_client_disconnect_closes_cursor_and_connection():
    """창구 수준 끊김(리뷰 Minor 8): ASGI 로 직접 불러 첫 본문 조각 뒤 http.disconnect 를 보낸다.

    Starlette 는 끊김을 감지하면 스트림 작업을 취소한다. 동기 제너레이터는 iterate_in_threadpool 로 감싸여 있어 취소만으로는 닫히지 않으므로,
    라우트가 취소 시에도 제너레이터를 닫아(커서 close·롤백·연결 close) 남기지 않는지 본다.
    """
    import asyncio

    from app.main import app

    named = _NamedCursor(_rows(1_000_000), ["id"])
    conn = _Conn(named)
    body = json.dumps(_STREAM_BODY).encode("utf-8")
    scope = {
        "type": "http",
        "http_version": "1.1",
        "method": "POST",
        "path": "/execute/query",
        "raw_path": b"/execute/query",
        "root_path": "",
        "scheme": "http",
        "query_string": b"",
        "headers": [
            (b"content-type", b"application/json"),
            (b"authorization", b"Internal test-internal-token-secret"),
            (b"content-length", str(len(body)).encode()),
        ],
        "client": ("127.0.0.1", 1234),
        "server": ("testserver", 80),
    }

    async def drive():
        first_chunk = asyncio.Event()
        state = {"requested": False, "chunks": 0}

        async def receive():
            if not state["requested"]:
                state["requested"] = True
                return {"type": "http.request", "body": body, "more_body": False}
            await first_chunk.wait()
            return {"type": "http.disconnect"}

        async def send(message):
            if message["type"] == "http.response.body" and message.get("body"):
                state["chunks"] += 1
                first_chunk.set()

        await asyncio.wait_for(app(scope, receive, send), timeout=10)
        return state

    with patch("app.routers.store_gate.get_store_client", return_value=_gate("POSTGRESQL")), \
            patch("app.services.query_executor.psycopg2.connect", return_value=conn):
        state = asyncio.run(drive())

    assert state["chunks"] >= 1
    assert named.closed and conn.rolled_back and conn.closed
    # 백만 행을 다 읽지 않고 멈췄다.
    assert named.fetch_calls < 1_000_000 // query_executor.STREAM_FETCH_SIZE
