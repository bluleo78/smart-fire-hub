from __future__ import annotations

from unittest.mock import patch

import pytest

from app.validators.ssrf_protection import SsrfException, validate_url


# ---------------------------------------------------------------------------
# Valid public URLs
# ---------------------------------------------------------------------------

def test_valid_http_url_passes():
    """Valid public HTTP URL should not raise."""
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("1.2.3.4", 80))]
        validate_url("http://example.com/api/data")  # should not raise


def test_valid_https_url_passes():
    """Valid public HTTPS URL should not raise."""
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("93.184.216.34", 443))]
        validate_url("https://api.example.com/v1/data")  # should not raise


# ---------------------------------------------------------------------------
# Scheme validation
# ---------------------------------------------------------------------------

def test_ftp_scheme_blocked():
    with pytest.raises(SsrfException, match="scheme not allowed"):
        validate_url("ftp://example.com/file.txt")


def test_file_scheme_blocked():
    with pytest.raises(SsrfException, match="scheme not allowed"):
        validate_url("file:///etc/passwd")


def test_empty_url_blocked():
    with pytest.raises(SsrfException, match="null or blank"):
        validate_url("")


def test_blank_url_blocked():
    with pytest.raises(SsrfException, match="null or blank"):
        validate_url("   ")


def test_none_url_blocked():
    with pytest.raises(SsrfException, match="null or blank"):
        validate_url(None)


# ---------------------------------------------------------------------------
# Loopback addresses
# ---------------------------------------------------------------------------

def test_loopback_ipv4_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("127.0.0.1", 80))]
        with pytest.raises(SsrfException, match="loopback"):
            validate_url("http://localhost/admin")


def test_loopback_127_0_0_2_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("127.0.0.2", 80))]
        with pytest.raises(SsrfException, match="loopback"):
            validate_url("http://127.0.0.2/secret")


def test_loopback_ipv6_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("::1", 80))]
        with pytest.raises(SsrfException, match="loopback"):
            validate_url("http://[::1]/admin")


# ---------------------------------------------------------------------------
# Private addresses
# ---------------------------------------------------------------------------

def test_private_10_x_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("10.0.0.1", 80))]
        with pytest.raises(SsrfException, match="private"):
            validate_url("http://10.0.0.1/internal")


def test_private_172_16_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("172.16.0.1", 80))]
        with pytest.raises(SsrfException, match="private"):
            validate_url("http://172.16.0.1/internal")


def test_private_192_168_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("192.168.1.100", 80))]
        with pytest.raises(SsrfException, match="private"):
            validate_url("http://192.168.1.100/internal")


# ---------------------------------------------------------------------------
# Link-local addresses
# ---------------------------------------------------------------------------

def test_link_local_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("169.254.169.254", 80))]
        with pytest.raises(SsrfException, match="link-local"):
            validate_url("http://169.254.169.254/latest/meta-data/")


def test_link_local_ipv6_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("fe80::1", 80))]
        with pytest.raises(SsrfException, match="link-local"):
            validate_url("http://[fe80::1]/")


# ---------------------------------------------------------------------------
# Unresolvable hostname
# ---------------------------------------------------------------------------

def test_unresolvable_hostname_blocked():
    import socket as sock
    with patch("socket.getaddrinfo", side_effect=sock.gaierror("Name or service not known")):
        with pytest.raises(SsrfException, match="Could not resolve hostname"):
            validate_url("http://this-host-does-not-exist.invalid/data")


# ---------------------------------------------------------------------------
# Multicast
# ---------------------------------------------------------------------------

def test_multicast_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("224.0.0.1", 80))]
        with pytest.raises(SsrfException, match="multicast"):
            validate_url("http://224.0.0.1/data")


# ---------------------------------------------------------------------------
# WD-148: is_private 가 놓치는 비공용 대역 · IPv4 를 품은 IPv6 우회
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "ip",
    [
        "100.64.0.1",  # CGNAT
        "100.100.100.200",  # CGNAT 대역 클라우드 메타데이터
        "fd00:ec2::254",  # AWS IMDS IPv6(ULA)
        "fd07:b51a:cc66:0:a617:db5e:ab7:e9f1",
        "64:ff9b::7f00:1",  # NAT64 → 127.0.0.1
        "64:ff9b::a9fe:a9fe",  # NAT64 → 169.254.169.254
        "2002:a00:1::1",  # 6to4 → 10.0.0.1
        "2002:808:808::1",  # 6to4 는 품은 IPv4 가 공용이어도 막는다(폐기 대역, Java 가드와 같음)
        "::ffff:10.0.0.5",  # 사상
    ],
)
def test_non_global_and_embedded_private_blocked(ip):
    family_addr = (ip, 80) if ":" not in ip else (ip, 80, 0, 0)
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, family_addr)]
        with pytest.raises(SsrfException):
            validate_url("http://internal.example.test/")


@pytest.mark.parametrize(
    "ip",
    [
        "8.8.8.8",
        "100.128.0.1",  # CGNAT 바로 위
        "2606:4700:4700::1111",
        "64:ff9b::808:808",  # NAT64 → 8.8.8.8
    ],
)
def test_public_and_embedded_public_pass(ip):
    family_addr = (ip, 80) if ":" not in ip else (ip, 80, 0, 0)
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, family_addr)]
        validate_url("http://public.example.test/")  # should not raise
