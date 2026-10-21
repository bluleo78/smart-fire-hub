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
    with pytest.raises(SsrfException, match=r"http 또는 https 주소만 호출할 수 있습니다\(입력: "):
        validate_url("ftp://example.com/file.txt")


def test_file_scheme_blocked():
    with pytest.raises(SsrfException, match=r"http 또는 https 주소만 호출할 수 있습니다\(입력: "):
        validate_url("file:///etc/passwd")


def test_empty_url_blocked():
    with pytest.raises(SsrfException, match="호출할 URL 이 비어 있습니다"):
        validate_url("")


def test_blank_url_blocked():
    with pytest.raises(SsrfException, match="호출할 URL 이 비어 있습니다"):
        validate_url("   ")


def test_none_url_blocked():
    with pytest.raises(SsrfException, match="호출할 URL 이 비어 있습니다"):
        validate_url(None)


# ---------------------------------------------------------------------------
# Loopback addresses
# ---------------------------------------------------------------------------

def test_loopback_ipv4_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("127.0.0.1", 80))]
        with pytest.raises(SsrfException, match=r"내부망 주소는 호출할 수 없습니다\(루프백\)"):
            validate_url("http://localhost/admin")


def test_loopback_127_0_0_2_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("127.0.0.2", 80))]
        with pytest.raises(SsrfException, match=r"내부망 주소는 호출할 수 없습니다\(루프백\)"):
            validate_url("http://127.0.0.2/secret")


def test_loopback_ipv6_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("::1", 80))]
        with pytest.raises(SsrfException, match=r"내부망 주소는 호출할 수 없습니다\(루프백\)"):
            validate_url("http://[::1]/admin")


# ---------------------------------------------------------------------------
# Private addresses
# ---------------------------------------------------------------------------

def test_private_10_x_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("10.0.0.1", 80))]
        with pytest.raises(SsrfException, match=r"내부망 주소는 호출할 수 없습니다\(사설망\)"):
            validate_url("http://10.0.0.1/internal")


def test_private_172_16_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("172.16.0.1", 80))]
        with pytest.raises(SsrfException, match=r"내부망 주소는 호출할 수 없습니다\(사설망\)"):
            validate_url("http://172.16.0.1/internal")


def test_private_192_168_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("192.168.1.100", 80))]
        with pytest.raises(SsrfException, match=r"내부망 주소는 호출할 수 없습니다\(사설망\)"):
            validate_url("http://192.168.1.100/internal")


# ---------------------------------------------------------------------------
# Link-local addresses
# ---------------------------------------------------------------------------

def test_link_local_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("169.254.169.254", 80))]
        with pytest.raises(SsrfException, match=r"내부망 주소는 호출할 수 없습니다\(링크 로컬\)"):
            validate_url("http://169.254.169.254/latest/meta-data/")


def test_link_local_ipv6_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("fe80::1", 80))]
        with pytest.raises(SsrfException, match=r"내부망 주소는 호출할 수 없습니다\(링크 로컬\)"):
            validate_url("http://[fe80::1]/")


# ---------------------------------------------------------------------------
# Unresolvable hostname
# ---------------------------------------------------------------------------

def test_unresolvable_hostname_blocked():
    import socket as sock
    with patch("socket.getaddrinfo", side_effect=sock.gaierror("Name or service not known")):
        with pytest.raises(SsrfException, match="호스트 이름을 찾을 수 없습니다: this-host-does-not-exist.invalid"):
            validate_url("http://this-host-does-not-exist.invalid/data")


# ---------------------------------------------------------------------------
# Multicast
# ---------------------------------------------------------------------------

def test_multicast_blocked():
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, ("224.0.0.1", 80))]
        with pytest.raises(SsrfException, match=r"내부망 주소는 호출할 수 없습니다\(멀티캐스트\)"):
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


# ---------------------------------------------------------------------------
# WD-150: 한국어 문구(Java SsrfProtectionService 와 같은 문장)·URL 쿼리 비노출
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "ip, kind",
    [
        ("127.0.0.1", "루프백"),
        ("10.1.2.3", "사설망"),
        ("169.254.169.254", "링크 로컬"),
        ("100.64.0.1", "사설·예약 대역"),
        ("0.0.0.0", "와일드카드"),
        ("224.0.0.1", "멀티캐스트"),
        ("fd00:ec2::254", "사설·예약 대역"),
        ("192.0.2.1", "사설·예약 대역"),
        ("64:ff9b::7f00:1", "사설·예약 대역"),
    ],
)
def test_blocked_message_exact_korean(ip, kind):
    with patch("socket.getaddrinfo") as mock_gai:
        mock_gai.return_value = [(None, None, None, None, (ip, 80))]
        with pytest.raises(SsrfException) as exc:
            validate_url(f"http://[{ip}]/x" if ":" in ip else f"http://{ip}/x")
    assert str(exc.value) == f"내부망 주소는 호출할 수 없습니다({kind}): {ip}"


def test_ftp_scheme_exact_korean():
    with pytest.raises(SsrfException) as exc:
        validate_url("ftp://example.com/file")
    assert str(exc.value) == "http 또는 https 주소만 호출할 수 있습니다(입력: ftp)"


def test_missing_scheme_separate_message_without_query():
    """스킴이 없으면 빈 스킴 대신 Java 와 같은 안내 — URL 을 넣되 쿼리(키)는 뺀다."""
    with pytest.raises(SsrfException) as exc:
        validate_url("example.com/api?serviceKey=SECRET123")
    msg = str(exc.value)
    assert msg == "URL에 스킴(http:// 또는 https://)이 없습니다: example.com/api"
    assert "SECRET123" not in msg


def test_invalid_url_message_without_query():
    with pytest.raises(SsrfException) as exc:
        validate_url("http://[::1/api?serviceKey=SECRET123")
    msg = str(exc.value)
    assert msg.startswith("URL 형식이 올바르지 않습니다: ")
    assert "SECRET123" not in msg
