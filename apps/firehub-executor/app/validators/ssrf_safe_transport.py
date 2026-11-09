"""연결 시점 SSRF 가드 — DNS 리바인딩(TOCTOU) 차단(WD-613).

``validate_url`` 은 요청 전에 호스트를 한 번 해석해 검사하지만, httpx 는 연결할 때 DNS 를 다시 따로 해석한다. TTL 0 인
공격자 DNS 가 검사 때는 공용 IP, 연결 때는 127.0.0.1·169.254.169.254 를 돌려주면 검사를 통과한 뒤 내부망에 붙는다.

그래서 실제 TCP 연결을 만드는 httpcore 네트워크 백엔드에서 호스트를 직접 해석하고, 해석된 주소 전부를 ``validate_url`` 과
같은 정책(``validate_addr_infos``)으로 검사한 뒤, 검사를 통과한 IP 로만 연결한다. 검사한 주소와 연결하는 주소가 같으므로
사이에 DNS 응답이 바뀔 틈이 없다. httpcore 는 요청 URL 의 호스트 이름으로 ``connect_tcp`` 를 부르고 TLS 의 SNI·인증서 검증도
그 이름(``origin.host``)으로 하므로, IP 로 연결해도 Host 헤더·SNI·인증서 검증은 바뀌지 않는다.
"""

from __future__ import annotations

import socket
import typing

import httpcore
import httpx

from app.validators.ssrf_protection import validate_addr_infos


class SsrfGuardedNetworkBackend(httpcore.NetworkBackend):
    """연결할 때마다 호스트를 해석·검사하고 통과한 IP 로만 붙는 httpcore 동기 네트워크 백엔드(WD-613).

    실제 소켓 생성·소켓 옵션·타임아웃·예외 매핑은 감싼 ``httpcore.SyncBackend`` 에 맡긴다 — IP 문자열을 넘기므로 그 안의
    ``socket.create_connection`` 은 DNS 를 다시 묻지 않는다(숫자 주소는 그대로 쓰인다).
    """

    def __init__(self, inner: httpcore.NetworkBackend | None = None) -> None:
        self._inner = inner if inner is not None else httpcore.SyncBackend()

    def connect_tcp(
        self,
        host: str,
        port: int,
        timeout: float | None = None,
        local_address: str | None = None,
        socket_options: typing.Iterable[httpcore.SOCKET_OPTION] | None = None,
    ) -> httpcore.NetworkStream:
        """호스트를 해석해 모든 주소를 검사하고, 통과한 주소에 차례로 연결을 시도한다.

        - 내부망 주소가 하나라도 섞이면 ``SsrfException`` 을 그대로 올린다 — httpcore·httpx 는 자기 예외가 아닌 것은 바꾸지
          않고 다시 던지므로, 호출부(``_execute_request``)가 사전 검사와 같은 경로로 받아 재시도하지 않고 같은 문구로 실패한다.
        - 해석 자체가 실패하면(일시적 DNS 장애 등) 예전과 같은 연결 오류(``httpcore.ConnectError``, 원인 gaierror 연결)로
          올린다 — 기존처럼 재시도 대상이고 사용자 문구도 「호스트를 찾을 수 없음」으로 같다.
        - 여러 주소 중 앞 주소 연결이 실패하면 다음 주소로 넘어간다(``socket.create_connection`` 과 같은 동작).
        """
        try:
            # socket 모듈 속성으로 부른다 — 테스트가 socket.getaddrinfo 를 바꿔 해석 결과를 주입할 수 있게.
            addr_infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        except OSError as e:
            raise httpcore.ConnectError(str(e)) from e

        # 사전 검사와 같은 정책 — 막히면 SsrfException(같은 한국어 문구).
        ips = validate_addr_infos(addr_infos)
        if not ips:
            raise httpcore.ConnectError(f"주소를 해석하지 못했습니다: {host}")

        last_error: Exception | None = None
        for ip in ips:
            try:
                return self._inner.connect_tcp(
                    ip,
                    port,
                    timeout=timeout,
                    local_address=local_address,
                    socket_options=socket_options,
                )
            except (httpcore.ConnectError, httpcore.ConnectTimeout) as e:
                last_error = e
        assert last_error is not None
        raise last_error

    def connect_unix_socket(
        self,
        path: str,
        timeout: float | None = None,
        socket_options: typing.Iterable[httpcore.SOCKET_OPTION] | None = None,
    ) -> httpcore.NetworkStream:
        """유닉스 소켓 연결은 사용자 URL 호출에 쓰일 일이 없다 — 우회 경로가 되지 않게 막는다."""
        raise httpcore.ConnectError("유닉스 소켓 연결은 허용되지 않습니다")

    def sleep(self, seconds: float) -> None:
        """httpcore 재시도 대기 — 감싼 백엔드에 그대로 맡긴다."""
        self._inner.sleep(seconds)


def create_ssrf_safe_transport() -> httpx.HTTPTransport:
    """사용자 URL 호출용 httpx 전송 계층 — 기본 ``HTTPTransport`` 와 같고 연결만 ``SsrfGuardedNetworkBackend`` 로 한다(WD-613).

    httpx 0.28 의 ``HTTPTransport`` 는 network_backend 인자를 받지 않는다. 그래서 httpx 가 평소대로 만든 연결 풀(TLS 컨텍스트·
    연결 한도·keep-alive·HTTP/1.1 설정이 ``httpx.Client`` 기본값과 똑같다)을 그대로 두고, 그 풀의 네트워크 백엔드만 바꾼다.
    풀을 직접 새로 만들면 httpx 기본 설정을 손으로 복제해야 해 버전이 바뀔 때 조용히 어긋날 수 있어서다. 풀은 연결을 만들 때마다
    ``_network_backend`` 를 읽으므로 생성 뒤에 바꿔도 모든 연결에 적용된다.

    두 속성(httpx ``_pool``, httpcore ``_network_backend``)은 내부 이름이다 — 라이브러리가 바뀌어 기대한 모양이 아니면 가드 없이
    조용히 호출하지 않도록 ``RuntimeError`` 로 실패한다(fail-closed). 연결 경로는 테스트가 실제 소켓으로 확인한다.
    """
    transport = httpx.HTTPTransport()
    pool = getattr(transport, "_pool", None)
    if not isinstance(pool, httpcore.ConnectionPool) or not isinstance(
        getattr(pool, "_network_backend", None), httpcore.NetworkBackend
    ):
        transport.close()
        raise RuntimeError("httpx/httpcore 내부 구조가 예상과 달라 SSRF 연결 가드를 걸 수 없습니다")
    pool._network_backend = SsrfGuardedNetworkBackend(pool._network_backend)
    return transport
