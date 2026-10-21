from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlparse

from app.services.api_call_errors import redact_url, unknown_host_message


class SsrfException(Exception):
    pass


def validate_url(url: str) -> None:
    """URL의 SSRF 안전성 검증.

    문구는 api 쪽 Java SsrfProtectionService 와 같은 한국어 문장이다(WD-150) — 실행 상세에 그대로 보이므로 원인을 담고,
    URL 을 넣을 때는 쿼리(API 키가 실릴 수 있음)를 뺀다.
    """
    if not url or not url.strip():
        raise SsrfException("호출할 URL 이 비어 있습니다")

    try:
        parsed = urlparse(url)
        hostname = parsed.hostname
    except ValueError as e:
        # 닫히지 않은 IPv6 대괄호 등 — urlparse/hostname 이 ValueError 를 던진다.
        raise SsrfException(f"URL 형식이 올바르지 않습니다: {redact_url(url)}") from e

    scheme = parsed.scheme
    # 스킴이 아예 없는 경우와 허용되지 않는 스킴을 구분한다 — Java 쪽(#561)과 같은 이유로 빈 값이 문구에 보이지 않게.
    if not scheme:
        raise SsrfException(f"URL에 스킴(http:// 또는 https://)이 없습니다: {redact_url(url)}")
    if scheme.lower() not in ("http", "https"):
        raise SsrfException(f"http 또는 https 주소만 호출할 수 있습니다(입력: {scheme})")

    if not hostname:
        raise SsrfException(f"URL 에 호스트가 없습니다: {redact_url(url)}")

    # Resolve all IPs to prevent DNS rebinding
    try:
        addr_infos = socket.getaddrinfo(hostname, None)
    except socket.gaierror as e:
        raise SsrfException(unknown_host_message(hostname)) from e

    seen: set[str] = set()
    for _family, _type, _proto, _canonname, sockaddr in addr_infos:
        ip_str = sockaddr[0]
        if ip_str in seen:
            continue
        seen.add(ip_str)
        validate_resolved_address(ipaddress.ip_address(ip_str))


def validate_resolved_address(addr: ipaddress.IPv4Address | ipaddress.IPv6Address) -> None:
    """해석된 IP의 안전성 검증."""
    if addr.is_loopback:
        raise SsrfException(_blocked("루프백", addr))
    if addr.is_link_local:
        raise SsrfException(_blocked("링크 로컬", addr))
    # 차단 범위는 그대로 두고 「종류」 표기만 Java 가드와 맞춘다(WD-150) — 같은 주소가 「테스트 호출」(Java)과 실행 상세
    # (이 가드)에서 다른 종류로 보이지 않게. 0.0.0.0·:: 는 is_private 이기도 해서 와일드카드를 먼저 보고,
    # is_private 중 「사설망」은 Java isSiteLocalAddress() 와 같은 RFC 1918 IPv4 만이다(ULA·문서용 등은 「사설·예약 대역」).
    if addr.is_unspecified:
        raise SsrfException(_blocked("와일드카드", addr))
    if addr.is_private:
        kind = "사설망" if any(addr in net for net in _RFC1918) else "사설·예약 대역"
        raise SsrfException(_blocked(kind, addr))
    if addr.is_multicast:
        raise SsrfException(_blocked("멀티캐스트", addr))
    # IPv4 를 품는 IPv6(사상·NAT64)는 품은 IPv4 로 다시 본다 — 64:ff9b::7f00:1 처럼 IPv6 로 감싸
    # 루프백·사설 IPv4 를 부르는 우회를 막는다(WD-148). NAT64 대역 자체는 공용(is_global)으로 분류된다.
    # 막힐 때의 표기는 Java 처럼 원래 IPv6 주소의 「사설·예약 대역」이다.
    embedded = _embedded_ipv4(addr)
    if embedded is not None:
        try:
            validate_resolved_address(embedded)
        except SsrfException as e:
            raise SsrfException(_blocked("사설·예약 대역", addr)) from e
        return
    # is_private 에 없는 비공용 대역(CGNAT 100.64.0.0/10 — 일부 클라우드 메타데이터·k8s 파드 대역 등)도 막는다.
    # api 쪽 Java 가드(SsrfProtectionService)와 같은 범위다.
    if not addr.is_global:
        raise SsrfException(_blocked("사설·예약 대역", addr))


def _blocked(kind: str, addr: ipaddress.IPv4Address | ipaddress.IPv6Address) -> str:
    """차단 문구 — 「내부망 주소는 호출할 수 없습니다(종류): IP」(WD-150, Java SsrfProtectionService 와 같은 문장)."""
    return f"내부망 주소는 호출할 수 없습니다({kind}): {addr}"


_NAT64 = ipaddress.ip_network("64:ff9b::/96")
_RFC1918 = tuple(
    ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
)


def _embedded_ipv4(addr: ipaddress.IPv4Address | ipaddress.IPv6Address) -> ipaddress.IPv4Address | None:
    """IPv6 주소가 품은 IPv4(사상 ::ffff:a.b.c.d, NAT64 64:ff9b::/96)를 돌려준다. 없으면 None.

    6to4(2002::/16)는 폐기된 대역(RFC 7526)이고 is_global 이 거짓이라 아래 비공용 판정이 통째로 막는다.
    """
    if not isinstance(addr, ipaddress.IPv6Address):
        return None
    if addr.ipv4_mapped is not None:
        return addr.ipv4_mapped
    if addr in _NAT64:
        return ipaddress.IPv4Address(int(addr) & 0xFFFFFFFF)
    return None
