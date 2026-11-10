"""연결 시점 SSRF 가드(WD-613, DNS 리바인딩/TOCTOU) 테스트.

사전 검사(validate_url) 때와 연결 때 DNS 응답이 다르게 오는 상황을 가짜 해석기(socket.getaddrinfo 교체)로 만들고,
연결이 SsrfException 으로 막히며 재시도하지 않는지, 허용된 주소로는 Host 헤더를 바꾸지 않고 붙는지 확인한다.
"""

from __future__ import annotations

import ipaddress
import socket
import threading
import types
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Dict, List
from unittest.mock import patch

import httpcore
import httpx
import pytest

from app.schemas.requests import ApiCallExecuteRequest, FieldMapping, RetryConfig
from app.services.api_call_errors import describe_exception, unknown_host_message
from app.services import api_call_executor
from app.services.api_call_executor import execute_api_call
from app.validators.ssrf_protection import SsrfException
from app.validators.ssrf_safe_transport import SsrfGuardedNetworkBackend, create_ssrf_safe_transport

_REAL_GETADDRINFO = socket.getaddrinfo


def _is_ip_literal(host) -> bool:
    """IP 문자열이면 True — 숫자 주소 해석은 진짜 getaddrinfo 에 넘긴다(DNS 를 묻지 않는다)."""
    try:
        ipaddress.ip_address(host.decode() if isinstance(host, bytes) else host)
        return True
    except ValueError:
        return False


class FakeResolver:
    """호스트 이름을 정해 둔 IP 목록으로 차례로 답하는 가짜 DNS(TTL 0 공격자 DNS 흉내).

    ``answers[host]`` 는 호출마다 하나씩 꺼내 쓰고, 마지막 답은 계속 반복한다. 이름 해석 횟수를 ``calls`` 로 센다.
    """

    def __init__(self, answers: Dict[str, List[List[str]]]):
        self._answers = {h: list(a) for h, a in answers.items()}
        self.calls: List[str] = []

    def __call__(self, host, port, *args, **kwargs):
        if _is_ip_literal(host):
            return _REAL_GETADDRINFO(host, port, *args, **kwargs)
        self.calls.append(host)
        seq = self._answers[host]
        ips = seq.pop(0) if len(seq) > 1 else seq[0]
        result = []
        for ip in ips:
            if ":" in ip:
                result.append((socket.AF_INET6, socket.SOCK_STREAM, 6, "", (ip, port or 0, 0, 0)))
            else:
                result.append((socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, port or 0)))
        return result


def _request(url: str, max_retries: int = 3) -> ApiCallExecuteRequest:
    return ApiCallExecuteRequest(
        tenant_id=1,
        url=url,
        method="GET",
        data_path="$.items",
        field_mappings=[
            FieldMapping(source_field="id", target_column="id", data_type="INTEGER"),
            FieldMapping(source_field="name", target_column="name", data_type="TEXT"),
        ],
        output_table="test_table",
        retry=RetryConfig(max_retries=max_retries, initial_backoff_ms=0, max_backoff_ms=0),
    )


class _Conn:
    """execute_api_call 이 부르는 commit/rollback 만 있는 가짜 커넥션."""

    def commit(self):
        pass

    def rollback(self):
        pass


# ---------------------------------------------------------------------------
# DNS 리바인딩: 사전 검사 땐 공용 IP, 연결 땐 내부망 IP
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "rebound_ip, expected",
    [
        ("127.0.0.1", "내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1"),
        ("169.254.169.254", "내부망 주소는 호출할 수 없습니다(링크 로컬): 169.254.169.254"),
    ],
)
def test_dns_rebinding_is_refused_at_connect_without_retry(monkeypatch, rebound_ip, expected):
    resolver = FakeResolver({"rebind.attacker.example": [["93.184.216.34"], [rebound_ip]]})
    monkeypatch.setattr(socket, "getaddrinfo", resolver)
    with patch.object(httpcore.SyncBackend, "connect_tcp", side_effect=AssertionError("연결되면 안 된다")) as raw_connect, \
            patch("time.sleep") as sleep:
        result = execute_api_call(_request("http://rebind.attacker.example/data", max_retries=3), _Conn())

    assert result.success is False
    # 사전 검사와 같은 문구 — httpx 원문이 새지 않는다.
    assert result.error == expected
    # 사전 검사 1번 + 연결 시점 1번 — 재시도했다면 해석이 더 일어난다.
    assert resolver.calls == ["rebind.attacker.example", "rebind.attacker.example"]
    raw_connect.assert_not_called()
    sleep.assert_not_called()


def test_api_call_client_ignores_environment(monkeypatch):
    """실행기 클라이언트는 trust_env=False 로 만든다(WD-700) — 컨테이너에 HTTP(S)_PROXY 가 생겨도 프록시가 DNS 를 따로 풀어
    연결 시점 가드를 건너뛰지 않게. 지금 httpx 는 transport 를 넘기면 환경 프록시를 이미 쓰지 않지만 문서에 없는 내부 규칙이라,
    동작(현 버전에선 인자 없이도 같음) 대신 생성 인자를 고정한다.
    """
    seen: List[dict] = []
    real_client = httpx.Client

    def spy(*args, **kwargs):
        seen.append(kwargs)
        return real_client(*args, **kwargs)

    resolver = FakeResolver({"rebind.attacker.example": [["93.184.216.34"], ["127.0.0.1"]]})
    monkeypatch.setattr(socket, "getaddrinfo", resolver)
    # 공용 httpx 모듈의 Client 를 바꾸면 같은 프로세스의 다른 클라이언트까지 잡히므로, 실행기 모듈이 보는 httpx 만 바꾼다.
    local_httpx = types.SimpleNamespace(**{**vars(httpx), "Client": spy})
    monkeypatch.setattr(api_call_executor, "httpx", local_httpx)
    with patch("time.sleep"):
        execute_api_call(_request("http://rebind.attacker.example/data", max_retries=0), _Conn())

    assert seen and all(kw.get("trust_env") is False for kw in seen)


def test_connect_time_refusal_raises_ssrf_exception_through_httpx(monkeypatch):
    """httpx 가 SsrfException 을 자기 예외(ConnectError 등)로 바꾸지 않고 그대로 올린다."""
    resolver = FakeResolver({"rebind.attacker.example": [["169.254.169.254"]]})
    monkeypatch.setattr(socket, "getaddrinfo", resolver)
    with httpx.Client(transport=create_ssrf_safe_transport()) as client:
        with pytest.raises(SsrfException, match=r"링크 로컬"):
            client.get("http://rebind.attacker.example/latest/meta-data/")


def test_mixed_public_and_private_answers_are_refused(monkeypatch):
    """해석 결과에 내부망 주소가 하나라도 섞이면 공용 주소가 있어도 막는다(어느 주소에 붙을지 고를 수 없게)."""
    resolver = FakeResolver({"mixed.attacker.example": [["93.184.216.34", "10.0.0.5"]]})
    monkeypatch.setattr(socket, "getaddrinfo", resolver)
    backend = SsrfGuardedNetworkBackend()
    with patch.object(httpcore.SyncBackend, "connect_tcp") as raw_connect:
        with pytest.raises(SsrfException, match=r"사설망"):
            backend.connect_tcp("mixed.attacker.example", 80)
    raw_connect.assert_not_called()


# ---------------------------------------------------------------------------
# IP 리터럴 — 연결 시점 가드도 직접 막는다
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "url, kind",
    [
        ("http://127.0.0.1:9/", "루프백"),
        ("http://169.254.169.254/latest/meta-data/", "링크 로컬"),
        ("http://[::1]:9/", "루프백"),
        # IPv4 사상 주소의 「종류」 표기는 파이썬 버전마다 다르다(3.11 은 ::ffff:127.0.0.1 을 루프백으로 본다) — 막히는지만 본다.
        ("http://[::ffff:127.0.0.1]:9/", "[^)]+"),
        ("http://[::ffff:169.254.169.254]/", "[^)]+"),
        ("http://[fd00::1]:9/", "사설·예약 대역"),
    ],
)
def test_ip_literal_internal_addresses_are_refused(url, kind):
    with patch.object(httpcore.SyncBackend, "connect_tcp") as raw_connect:
        with httpx.Client(transport=create_ssrf_safe_transport()) as client:
            with pytest.raises(SsrfException, match=rf"내부망 주소는 호출할 수 없습니다\({kind}\)"):
                client.get(url)
    raw_connect.assert_not_called()


# ---------------------------------------------------------------------------
# 허용 경로 — 검사한 IP 로 붙고 Host 헤더는 호스트 이름 그대로
# ---------------------------------------------------------------------------

class _RecordingHandler(BaseHTTPRequestHandler):
    """받은 Host 헤더를 서버에 남기고 고정 JSON 을 돌려준다."""

    def do_GET(self):  # noqa: N802
        self.server.seen_hosts.append(self.headers.get("Host"))
        body = b'{"items": [{"id": 1, "name": "a"}]}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


@pytest.fixture
def local_server():
    server = HTTPServer(("127.0.0.1", 0), _RecordingHandler)
    server.seen_hosts = []
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()


def test_allowed_address_connects_with_original_host_header(monkeypatch, local_server):
    port = local_server.server_address[1]
    resolver = FakeResolver({"api.allowed.example": [["127.0.0.1"]]})
    monkeypatch.setattr(socket, "getaddrinfo", resolver)
    # 로컬 테스트 서버(루프백)에 붙을 수 있게 주소 정책만 열어 둔다 — 사전 검사·연결 시점 검사가 같은 함수를 쓰므로 둘 다 열린다.
    monkeypatch.setattr("app.validators.ssrf_protection.validate_resolved_address", lambda addr: None)
    real_connect = httpcore.SyncBackend.connect_tcp
    connected_hosts: List[str] = []

    def spy_connect(self, host, port, **kwargs):
        connected_hosts.append(host)
        return real_connect(self, host, port, **kwargs)

    monkeypatch.setattr(httpcore.SyncBackend, "connect_tcp", spy_connect)
    with patch("app.services.api_call_executor.insert_batch") as insert:
        result = execute_api_call(_request(f"http://api.allowed.example:{port}/data"), _Conn())

    assert result.success is True, result.error
    insert.assert_called_once()
    # Host 헤더는 IP 가 아니라 요청 호스트 이름이다.
    assert local_server.seen_hosts == [f"api.allowed.example:{port}"]
    # 실제 소켓은 가드가 검사한 IP 로 열렸다(이름으로 다시 해석하지 않았다).
    assert connected_hosts == ["127.0.0.1"]
    assert resolver.calls == ["api.allowed.example", "api.allowed.example"]


def test_dns_failure_at_connect_stays_a_retryable_connect_error(monkeypatch):
    """연결 시점 해석 실패(일시적 DNS 장애)는 SSRF 차단이 아니라 예전과 같은 연결 오류다 — 원인 gaierror 를 잇는다."""

    def failing(host, port, *args, **kwargs):
        raise socket.gaierror(socket.EAI_NONAME, "Name or service not known")

    monkeypatch.setattr(socket, "getaddrinfo", failing)
    with httpx.Client(transport=create_ssrf_safe_transport()) as client:
        with pytest.raises(httpx.ConnectError) as info:
            client.get("http://flaky.example/")
    # 기존 기본 백엔드와 같은 모양이라 사용자 문구도 예전처럼 「호스트를 찾을 수 없음」이다.
    assert describe_exception(info.value, 1000, "flaky.example") == unknown_host_message("flaky.example")


def test_transport_fails_closed_when_httpx_internals_change():
    """httpx 내부 구조가 바뀌어 가드를 걸 수 없으면 가드 없는 전송을 돌려주지 않고 실패한다."""

    class _Odd:
        _pool = object()

        def close(self):
            pass

    with patch("httpx.HTTPTransport", return_value=_Odd()):
        with pytest.raises(RuntimeError):
            create_ssrf_safe_transport()
