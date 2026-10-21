from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlparse


class SsrfException(Exception):
    pass


def validate_url(url: str) -> None:
    """URL의 SSRF 안전성 검증."""
    if not url or not url.strip():
        raise SsrfException("URL must not be null or blank")

    parsed = urlparse(url)
    scheme = parsed.scheme
    if scheme not in ("http", "https"):
        raise SsrfException(
            f"URL scheme not allowed: {scheme}. Only http and https are permitted."
        )

    hostname = parsed.hostname
    if not hostname:
        raise SsrfException(f"URL has no valid hostname: {url}")

    # Resolve all IPs to prevent DNS rebinding
    try:
        addr_infos = socket.getaddrinfo(hostname, None)
    except socket.gaierror as e:
        raise SsrfException(f"Could not resolve hostname: {hostname}") from e

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
        raise SsrfException(f"Requests to loopback addresses are not allowed: {addr}")
    if addr.is_link_local:
        raise SsrfException(f"Requests to link-local addresses are not allowed: {addr}")
    if addr.is_private:
        raise SsrfException(f"Requests to private addresses are not allowed: {addr}")
    if addr.is_multicast:
        raise SsrfException(f"Requests to multicast addresses are not allowed: {addr}")
    if addr.is_unspecified:
        raise SsrfException(f"Requests to wildcard addresses are not allowed: {addr}")
    # IPv4 를 품는 IPv6(사상·NAT64)는 품은 IPv4 로 다시 본다 — 64:ff9b::7f00:1 처럼 IPv6 로 감싸
    # 루프백·사설 IPv4 를 부르는 우회를 막는다(WD-148). NAT64 대역 자체는 공용(is_global)으로 분류된다.
    embedded = _embedded_ipv4(addr)
    if embedded is not None:
        validate_resolved_address(embedded)
        return
    # is_private 에 없는 비공용 대역(CGNAT 100.64.0.0/10 — 일부 클라우드 메타데이터·k8s 파드 대역 등)도 막는다.
    # api 쪽 Java 가드(SsrfProtectionService)와 같은 범위다.
    if not addr.is_global:
        raise SsrfException(f"Requests to private/reserved addresses are not allowed: {addr}")


_NAT64 = ipaddress.ip_network("64:ff9b::/96")


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
