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
