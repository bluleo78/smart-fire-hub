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
