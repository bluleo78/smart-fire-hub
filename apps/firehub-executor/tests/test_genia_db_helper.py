"""Tibero Python 조회 도우미(``sandbox/genia_db.py``, WD-78) 테스트.

가짜 HTTP 서버(표준 라이브러리 ``http.server``)가 db-executor ``/execute/query`` 슬롯 스트리밍 응답을 흉내 낸다. 응답 모양은 공용 계약
벡터(``executor-db-contract-vectors.json`` 의 ``query.streaming``)의 줄 키를 그대로 쓴다 — 도우미가 db-executor 와 같은 계약을 읽는지
여기서 고정한다. 도우미는 샌드박스 패키지 경로(``/opt/python-packages``)에 놓이는 단독 모듈이라 앱 패키지가 아니라 파일 경로로 불러온다.
"""
from __future__ import annotations

import importlib.util
import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

_HELPER_PATH = Path(__file__).resolve().parents[1] / "sandbox" / "genia_db.py"
_FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "firehub-api/src/test/resources/fixtures/executor-db-contract-vectors.json"
)
_STREAMING = json.loads(_FIXTURE.read_text(encoding="utf-8"))["endpoints"]["query"]["streaming"]

# 토큰 모양은 api ScriptTokenIssuer 와 같다: v1.<tenantId>.<slot>.<epoch>.<exp>.<sig>
TOKEN = "v1.5.2.0.1999999999.c2VjcmV0LXNpZ25hdHVyZS12YWx1ZS1mb3ItdGVzdA"


def _load_helper():
    spec = importlib.util.spec_from_file_location("genia_db", _HELPER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


genia_db = _load_helper()


# ---------------------------------------------------------------------------
# 가짜 db-executor
# ---------------------------------------------------------------------------


class _Handler(BaseHTTPRequestHandler):
    """요청을 기록하고 서버에 걸린 ``respond(handler)`` 로 응답을 쓴다."""

    protocol_version = "HTTP/1.0"

    def do_POST(self):  # noqa: N802 — http.server 규약
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length)
        self.server.requests.append(
            {"path": self.path, "headers": dict(self.headers), "body": json.loads(body.decode("utf-8"))}
        )
        self.server.respond(self)

    def do_GET(self):  # noqa: N802 — 리다이렉트를 따라간 요청(303 은 GET 으로 바뀐다)도 기록한다
        self.server.requests.append({"path": self.path, "headers": dict(self.headers), "body": None})
        self.server.respond(self)

    def log_message(self, *args):  # 테스트 출력을 깨끗하게
        pass


def _ndjson(handler, lines, *, finish=True):
    """HTTP/1.0 + 연결 종료로 NDJSON 줄들을 보낸다(길이 헤더 없음 — 끝은 연결 종료)."""
    handler.send_response(200)
    handler.send_header("Content-Type", _STREAMING["contentType"])
    handler.end_headers()
    for line in lines:
        handler.wfile.write((json.dumps(line, ensure_ascii=False) + "\n").encode("utf-8"))
    handler.wfile.flush()


def _columns_line(names):
    return {_STREAMING["firstLineKeys"][0]: names}


def _row_line(row):
    return {_STREAMING["rowLineKeys"][0]: row}


def _success_line(count):
    keys = _STREAMING["successLastLineKeys"]
    return dict(zip(keys, [True, count]))


def _failure_line(error, db_error=None):
    keys = _STREAMING["failureLastLineKeys"]
    return dict(zip(keys, [False, error, db_error]))


@pytest.fixture
def server(monkeypatch):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    srv.requests = []
    srv.respond = lambda h: _ndjson(h, [_columns_line(["id"]), _success_line(0)])
    thread = threading.Thread(target=srv.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
    thread.start()
    monkeypatch.setenv("GENIA_DB_URL", f"http://127.0.0.1:{srv.server_address[1]}/")
    monkeypatch.setenv("GENIA_DB_TOKEN", TOKEN)
    yield srv
    srv.shutdown()
    srv.server_close()


# ---------------------------------------------------------------------------
# 정상 흐름·요청 모양
# ---------------------------------------------------------------------------


def test_query_returns_all_rows_and_sends_script_token_request(server):
    rows = [{"id": 1, "name": "가"}, {"id": 2, "name": None}]
    server.respond = lambda h: _ndjson(
        h, [_columns_line(["id", "name"])] + [_row_line(r) for r in rows] + [_success_line(2)]
    )

    assert genia_db.query("SELECT id, name FROM t") == rows

    (req,) = server.requests
    assert req["path"] == "/execute/query"
    assert req["headers"]["Authorization"] == f"Script {TOKEN}"
    assert req["headers"]["Content-Type"] == "application/json"
    # 테넌트·슬롯은 토큰 앞 필드에서 꺼낸다(db-executor 는 본문이 토큰과 다르면 401).
    assert req["body"] == {
        "tenantId": 5,
        "readSlot": 2,
        "stream": True,
        "read_only": True,
        "query": "SELECT id, name FROM t",
    }


@pytest.mark.parametrize(
    "example",
    [e for e in _STREAMING["examples"] if not e.get("requestLevel")],
    ids=lambda e: e["name"],
)
def test_streaming_vector_examples(server, example):
    """공용 벡터의 각 예시를 db-executor 처럼 흘려보내고 도우미의 판정이 벡터 기대와 같은지 본다."""
    rows = example["resultRows"]
    fail_after = example.get("failAfterRows")
    expect_success = example["expectSuccess"]

    def respond(h):
        if example.get("expectLines") == 1:
            # 실행 전 거부 — 실패 줄 하나만.
            _ndjson(h, [_failure_line("조회 문장만 실행할 수 있습니다.")])
            return
        lines = [_columns_line(["id"])]
        sent = rows if fail_after is None else fail_after
        lines += [_row_line({"id": i}) for i in range(sent)]
        lines.append(_success_line(rows) if expect_success else _failure_line("ORA-00942: 테이블 없음", {"code": "-942"}))
        _ndjson(h, lines)

    server.respond = respond
    got = []
    if expect_success:
        got = list(genia_db.iter_query(example["request"]["query"]))
    else:
        with pytest.raises(genia_db.QueryError):
            for row in genia_db.iter_query(example["request"]["query"]):
                got.append(row)
    assert len(got) == example.get("expectRowLines", 0)
    assert server.requests[0]["body"]["stream"] is True


# ---------------------------------------------------------------------------
# 실패 — 실패 줄·끊김·행 수 불일치·인증
# ---------------------------------------------------------------------------


def test_failure_last_line_raises_with_its_error_message_after_partial_rows(server):
    server.respond = lambda h: _ndjson(
        h,
        [_columns_line(["id"]), _row_line({"id": 1}), _row_line({"id": 2}), _failure_line("JDBC-8033: 권한이 없습니다")],
    )
    got = []
    with pytest.raises(genia_db.QueryError) as ei:
        for row in genia_db.iter_query("SELECT id FROM secret"):
            got.append(row)
    assert str(ei.value) == "JDBC-8033: 권한이 없습니다"
    assert got == [{"id": 1}, {"id": 2}]


def test_pre_execution_rejection_single_failure_line(server):
    busy = "같은 등급 슬롯의 동시 조회가 많습니다 — 잠시 후 다시 실행하세요"
    server.respond = lambda h: _ndjson(h, [_failure_line(busy)])
    with pytest.raises(genia_db.QueryError) as ei:
        genia_db.query("SELECT 1 FROM DUAL")
    assert str(ei.value) == busy


def test_stream_ending_without_last_line_raises(server):
    # 행만 오고 마지막(성공) 줄 없이 연결이 닫힌다 — 받은 행이 전체라는 보장이 없으므로 실패다.
    server.respond = lambda h: _ndjson(h, [_columns_line(["id"]), _row_line({"id": 1})])
    with pytest.raises(genia_db.QueryError) as ei:
        genia_db.query("SELECT id FROM t")
    assert "끝까지 받지 못했습니다" in str(ei.value)


def test_chunked_stream_cut_mid_chunk_raises(server):
    def respond(h):
        h.protocol_version = "HTTP/1.1"
        h.send_response(200)
        h.send_header("Content-Type", "application/x-ndjson")
        h.send_header("Transfer-Encoding", "chunked")
        h.send_header("Connection", "close")
        h.end_headers()
        first = (json.dumps(_columns_line(["id"])) + "\n").encode()
        h.wfile.write(f"{len(first):x}\r\n".encode() + first + b"\r\n")
        # 길이 100 짜리 청크를 선언하고 일부만 보낸 뒤 끊는다.
        h.wfile.write(b"64\r\n" + b'{"row":{"id":1}}\n')
        h.wfile.flush()
        h.close_connection = True

    server.respond = respond
    with pytest.raises(genia_db.QueryError) as ei:
        genia_db.query("SELECT id FROM t")
    assert "끝까지 받지 못했습니다" in str(ei.value)


def test_row_count_mismatch_raises(server):
    server.respond = lambda h: _ndjson(h, [_columns_line(["id"]), _row_line({"id": 1}), _success_line(2)])
    with pytest.raises(genia_db.QueryError) as ei:
        genia_db.query("SELECT id FROM t")
    assert "행 수가 맞지 않습니다" in str(ei.value)


def test_malformed_line_raises(server):
    def respond(h):
        _ndjson(h, [_columns_line(["id"])])
        h.wfile.write(b"not-json\n")

    server.respond = respond
    with pytest.raises(genia_db.QueryError):
        genia_db.query("SELECT id FROM t")


def test_unauthorized_401_raises_without_token(server):
    def respond(h):
        h.send_response(401)
        h.send_header("Content-Type", "application/json")
        h.end_headers()
        h.wfile.write('{"success":false,"error":"인증되지 않은 요청입니다."}'.encode())

    server.respond = respond
    with pytest.raises(genia_db.QueryError) as ei:
        genia_db.query("SELECT 1 FROM DUAL")
    message = str(ei.value)
    assert "인증되지 않았습니다" in message
    assert TOKEN not in message and TOKEN.split(".")[-1] not in message
    assert ei.value.__cause__ is None and ei.value.__suppress_context__


@pytest.mark.parametrize("scenario", ["failure_line", "cut", "401", "connect"])
def test_failure_messages_never_contain_token_or_url(server, monkeypatch, scenario):
    """D3: 어떤 실패에서도 예외 메시지·체인에 토큰과 주소가 없다(실행 로그로 흘러가므로)."""
    url = f"http://127.0.0.1:{server.server_address[1]}"
    if scenario == "failure_line":
        server.respond = lambda h: _ndjson(h, [_failure_line("실패")])
    elif scenario == "cut":
        server.respond = lambda h: _ndjson(h, [_columns_line(["id"])])
    elif scenario == "401":
        server.respond = lambda h: (h.send_response(401), h.end_headers())
    else:
        # 아무도 듣지 않는 포트 — 연결 거부. 원문 예외(주소 포함)는 체인에서 끊긴다.
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            dead_port = s.getsockname()[1]
        url = f"http://127.0.0.1:{dead_port}"
        monkeypatch.setenv("GENIA_DB_URL", url)
    with pytest.raises(genia_db.QueryError) as ei:
        genia_db.query("SELECT 1 FROM DUAL")
    rendered = repr(ei.value) + str(ei.value) + repr(ei.value.__cause__) + repr(ei.value.__context__ if not ei.value.__suppress_context__ else None)
    assert TOKEN not in rendered
    assert TOKEN.split(".")[-1] not in rendered
    assert url not in rendered and "127.0.0.1" not in rendered


# ---------------------------------------------------------------------------
# 설정·입력 확인
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("missing", ["GENIA_DB_URL", "GENIA_DB_TOKEN"])
def test_missing_env_raises_exact_message(monkeypatch, missing):
    monkeypatch.setenv("GENIA_DB_URL", "http://127.0.0.1:9")
    monkeypatch.setenv("GENIA_DB_TOKEN", TOKEN)
    monkeypatch.delenv(missing)
    with pytest.raises(genia_db.QueryError) as ei:
        genia_db.query("SELECT 1 FROM DUAL")
    assert str(ei.value) == "이 실행 환경에는 조회 도우미 설정이 없습니다."


@pytest.mark.parametrize(
    "bad",
    ["abc", "v1.5.2.0.1999999999", "v2.5.2.0.1.sig", "v1.x.2.0.1.sig", "v1.5.2.0.1.si.g", "v1.\u00b2.2.0.1.sig", "v1.5.\u0663.0.1.sig"],
)
def test_malformed_token_raises_without_echoing_it(monkeypatch, bad):
    monkeypatch.setenv("GENIA_DB_URL", "http://127.0.0.1:9")
    monkeypatch.setenv("GENIA_DB_TOKEN", bad)
    with pytest.raises(genia_db.QueryError) as ei:
        genia_db.iter_query("SELECT 1 FROM DUAL")
    assert bad not in str(ei.value)


# ---------------------------------------------------------------------------
# 응답 수명 — 버린 반복자·읽기 시간 제한(D1)
# ---------------------------------------------------------------------------


class _FakeResponse:
    def __init__(self, lines):
        self._lines = [(json.dumps(x) + "\n").encode() for x in lines]
        self.closed = False

    def readline(self):
        return self._lines.pop(0) if self._lines else b""

    def close(self):
        self.closed = True


def test_abandoned_iterator_closes_response_and_unstarted_one_sends_nothing(monkeypatch):
    monkeypatch.setenv("GENIA_DB_URL", "http://db-executor:8090")
    monkeypatch.setenv("GENIA_DB_TOKEN", TOKEN)
    opened = []

    def fake_urlopen(request, timeout=None):
        resp = _FakeResponse([_columns_line(["id"])] + [_row_line({"id": i}) for i in range(5)] + [_success_line(5)])
        opened.append((resp, timeout))
        return resp

    monkeypatch.setattr(genia_db, "_open", fake_urlopen)

    # 시작하지 않고 버린 반복자는 요청을 보내지 않는다(슬롯을 쥐지 않는다).
    unstarted = genia_db.iter_query("SELECT id FROM t")
    unstarted.close()
    assert opened == []

    # 한 행만 읽고 버리면 응답이 닫힌다.
    it = genia_db.iter_query("SELECT id FROM t")
    assert next(it) == {"id": 0}
    resp, timeout = opened[0]
    assert resp.closed is False
    it.close()
    assert resp.closed is True
    # 읽기는 소켓 시간 제한으로 묶인다.
    assert isinstance(timeout, (int, float)) and 0 < timeout <= 3600


def test_query_drains_and_closes_before_returning(monkeypatch):
    monkeypatch.setenv("GENIA_DB_URL", "http://db-executor:8090")
    monkeypatch.setenv("GENIA_DB_TOKEN", TOKEN)
    opened = []

    def fake_urlopen(request, timeout=None):
        resp = _FakeResponse([_columns_line(["id"]), _row_line({"id": 1}), _success_line(1)])
        opened.append(resp)
        return resp

    monkeypatch.setattr(genia_db, "_open", fake_urlopen)
    assert genia_db.query("SELECT id FROM t") == [{"id": 1}]
    assert opened[0].closed is True


@pytest.mark.parametrize("code", [301, 302, 303, 307, 308])
def test_redirect_is_refused_and_token_is_never_resent(server, code):
    """M6: 리다이렉트를 따라가지 않는다 — urllib 기본 동작은 Authorization 을 새 주소로 다시 보낸다."""

    def respond(h):
        if h.path == "/execute/query":
            h.send_response(code)
            h.send_header("Location", "/stolen")
            h.end_headers()
        else:
            _ndjson(h, [_columns_line(["id"]), _success_line(0)])

    server.respond = respond
    with pytest.raises(genia_db.QueryError) as ei:
        genia_db.query("SELECT 1 FROM DUAL")
    assert str(code) in str(ei.value)
    assert TOKEN not in str(ei.value)
    # 첫 요청 하나뿐 — 리다이렉트 대상으로 토큰이 다시 가지 않았다.
    assert [r["path"] for r in server.requests] == ["/execute/query"]
