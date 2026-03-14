from __future__ import annotations

import json
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock, call, patch

import pytest

from app.schemas.requests import (
    ApiCallExecuteRequest,
    FieldMapping,
    PaginationConfig,
    RetryConfig,
)
from app.services.api_call_executor import (
    ApiCallException,
    _apply_auth,
    _convert_value,
    _has_next_page,
    _insert_batch,
    _parse_and_map,
    execute_api_call,
)
from app.validators.ssrf_protection import SsrfException


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def make_request(**kwargs) -> ApiCallExecuteRequest:
    defaults = dict(
        url="http://public.example.com/api/data",
        method="GET",
        data_path="$.items",
        field_mappings=[
            FieldMapping(source_field="id", target_column="id", data_type="INTEGER"),
            FieldMapping(source_field="name", target_column="name", data_type="TEXT"),
        ],
        output_table="test_table",
        retry=RetryConfig(max_retries=0, initial_backoff_ms=0, max_backoff_ms=0),
    )
    defaults.update(kwargs)
    return ApiCallExecuteRequest(**defaults)


def make_conn():
    conn = MagicMock()
    cursor = MagicMock()
    conn.cursor.return_value.__enter__ = MagicMock(return_value=cursor)
    conn.cursor.return_value.__exit__ = MagicMock(return_value=False)
    return conn, cursor


def mock_response(data: Any) -> MagicMock:
    resp = MagicMock()
    resp.status_code = 200
    resp.json.return_value = data
    resp.content = json.dumps(data).encode()
    resp.headers = {}
    return resp


# ---------------------------------------------------------------------------
# SSRF rejection
# ---------------------------------------------------------------------------

def test_ssrf_rejection_returns_failure():
    req = make_request(url="http://192.168.1.1/internal")
    conn, _ = make_conn()

    with patch("app.services.api_call_executor.validate_url", side_effect=SsrfException("private")):
        result = execute_api_call(req, conn)

    assert result.success is False
    assert "private" in result.error
    conn.rollback.assert_called_once()


# ---------------------------------------------------------------------------
# Single page fetch + insert
# ---------------------------------------------------------------------------

def test_single_page_fetch_and_insert():
    req = make_request()
    conn, cursor = make_conn()
    body = {"items": [{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]}

    with patch("app.services.api_call_executor.validate_url"):
        with patch("app.services.api_call_executor._do_request", return_value=body):
            with patch("app.services.api_call_executor._insert_batch") as mock_insert:
                result = execute_api_call(req, conn)

    assert result.success is True
    assert result.rows_loaded == 2
    assert result.total_pages == 1
    mock_insert.assert_called_once()
    conn.commit.assert_called_once()


def test_single_page_empty_result():
    req = make_request()
    conn, cursor = make_conn()
    body = {"items": []}

    with patch("app.services.api_call_executor.validate_url"):
        with patch("app.services.api_call_executor._do_request", return_value=body):
            result = execute_api_call(req, conn)

    assert result.success is True
    assert result.rows_loaded == 0
    conn.commit.assert_called_once()


# ---------------------------------------------------------------------------
# Offset pagination
# ---------------------------------------------------------------------------

def test_offset_pagination_multiple_pages():
    req = make_request(
        pagination=PaginationConfig(
            type="OFFSET",
            page_size=2,
            offset_param="offset",
            limit_param="limit",
            total_path="$.total",
        )
    )
    conn, cursor = make_conn()

    page1 = {"total": 4, "items": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}]}
    page2 = {"total": 4, "items": [{"id": 3, "name": "C"}, {"id": 4, "name": "D"}]}
    responses = [page1, page2]
    call_count = [0]

    def fake_do_request(**kwargs):
        r = responses[call_count[0]]
        call_count[0] += 1
        return r

    with patch("app.services.api_call_executor.validate_url"):
        with patch("app.services.api_call_executor._do_request", side_effect=fake_do_request):
            with patch("app.services.api_call_executor._insert_batch"):
                result = execute_api_call(req, conn)

    assert result.success is True
    assert result.rows_loaded == 4
    assert result.total_pages == 2


def test_offset_pagination_stops_on_partial_page():
    """Stops when page returns fewer rows than page_size."""
    req = make_request(
        pagination=PaginationConfig(
            type="OFFSET",
            page_size=3,
            offset_param="offset",
            limit_param="limit",
        )
    )
    conn, cursor = make_conn()

    page1 = {"items": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}, {"id": 3, "name": "C"}]}
    page2 = {"items": [{"id": 4, "name": "D"}]}  # partial
    responses = [page1, page2]
    call_count = [0]

    def fake_do_request(**kwargs):
        r = responses[call_count[0]]
        call_count[0] += 1
        return r

    with patch("app.services.api_call_executor.validate_url"):
        with patch("app.services.api_call_executor._do_request", side_effect=fake_do_request):
            with patch("app.services.api_call_executor._insert_batch"):
                result = execute_api_call(req, conn)

    assert result.success is True
    assert result.rows_loaded == 4
    assert result.total_pages == 2


def test_offset_pagination_stops_when_total_reached():
    """Stops when offset + page_size >= total_count."""
    req = make_request(
        pagination=PaginationConfig(
            type="OFFSET",
            page_size=2,
            offset_param="offset",
            limit_param="limit",
            total_path="$.total",
        )
    )
    conn, cursor = make_conn()

    page1 = {"total": 2, "items": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}]}
    call_count = [0]

    def fake_do_request(**kwargs):
        call_count[0] += 1
        return page1

    with patch("app.services.api_call_executor.validate_url"):
        with patch("app.services.api_call_executor._do_request", side_effect=fake_do_request):
            with patch("app.services.api_call_executor._insert_batch"):
                result = execute_api_call(req, conn)

    assert result.success is True
    assert result.rows_loaded == 2
    assert result.total_pages == 1
    assert call_count[0] == 1


# ---------------------------------------------------------------------------
# Duration limit
# ---------------------------------------------------------------------------

def test_max_duration_exceeded_partial_result():
    req = make_request(
        max_duration_ms=0,  # immediately exceeded
        pagination=PaginationConfig(
            type="OFFSET",
            page_size=1,
            offset_param="offset",
            limit_param="limit",
        )
    )
    conn, cursor = make_conn()
    body = {"items": [{"id": 1, "name": "A"}]}

    with patch("app.services.api_call_executor.validate_url"):
        with patch("app.services.api_call_executor._do_request", return_value=body):
            with patch("app.services.api_call_executor._insert_batch"):
                result = execute_api_call(req, conn)

    assert result.success is True
    assert "WARN" in result.execution_log or "maxDurationMs" in result.execution_log


# ---------------------------------------------------------------------------
# Field mapping type conversion
# ---------------------------------------------------------------------------

def _fm(source_field, target_column, data_type, **kwargs) -> FieldMapping:
    return FieldMapping(
        source_field=source_field,
        target_column=target_column,
        data_type=data_type,
        **kwargs,
    )


def test_convert_text():
    fm = _fm("f", "c", "TEXT")
    assert _convert_value(123, fm) == "123"
    assert _convert_value("hello", fm) == "hello"


def test_convert_integer():
    fm = _fm("f", "c", "INTEGER")
    assert _convert_value("42", fm) == 42
    assert _convert_value(42, fm) == 42


def test_convert_decimal():
    fm = _fm("f", "c", "DECIMAL")
    assert _convert_value("3.14", fm) == Decimal("3.14")


def test_convert_boolean_true_variants():
    fm = _fm("f", "c", "BOOLEAN")
    assert _convert_value("true", fm) is True
    assert _convert_value("1", fm) is True
    assert _convert_value("yes", fm) is True
    assert _convert_value(True, fm) is True


def test_convert_boolean_false_variants():
    fm = _fm("f", "c", "BOOLEAN")
    assert _convert_value("false", fm) is False
    assert _convert_value("0", fm) is False
    assert _convert_value(False, fm) is False


def test_convert_date_iso():
    fm = _fm("f", "c", "DATE")
    result = _convert_value("2024-03-15", fm)
    assert result == date(2024, 3, 15)


def test_convert_date_with_format():
    fm = _fm("f", "c", "DATE", date_format="%d/%m/%Y")
    result = _convert_value("15/03/2024", fm)
    assert result == date(2024, 3, 15)


def test_convert_timestamp_iso():
    fm = _fm("f", "c", "TIMESTAMP")
    result = _convert_value("2024-03-15T10:30:00", fm)
    assert isinstance(result, datetime)
    assert result.year == 2024


def test_convert_timestamp_with_timezone_to_utc():
    fm = _fm("f", "c", "TIMESTAMP", source_timezone="America/New_York")
    result = _convert_value("2024-03-15T10:30:00", fm)
    assert isinstance(result, datetime)
    assert result.tzinfo is None  # stripped after UTC conversion


def test_convert_geometry():
    fm = _fm("f", "c", "GEOMETRY")
    assert _convert_value("POINT(0 0)", fm) == "POINT(0 0)"


def test_convert_none_returns_none():
    fm = _fm("f", "c", "INTEGER")
    assert _convert_value(None, fm) is None


# ---------------------------------------------------------------------------
# Comma-separated number format
# ---------------------------------------------------------------------------

def test_integer_comma_separated():
    fm = _fm("f", "c", "INTEGER", number_format="comma_separated")
    assert _convert_value("1,234,567", fm) == 1234567


def test_decimal_comma_separated():
    fm = _fm("f", "c", "DECIMAL", number_format="comma_separated")
    assert _convert_value("1,234.56", fm) == Decimal("1234.56")


# ---------------------------------------------------------------------------
# Retry behaviour
# ---------------------------------------------------------------------------

def test_retry_on_5xx():
    req = make_request(
        retry=RetryConfig(max_retries=2, initial_backoff_ms=0, max_backoff_ms=0)
    )
    conn, cursor = make_conn()
    call_count = [0]

    def fake_do_request(**kwargs):
        call_count[0] += 1
        if call_count[0] < 3:
            raise Exception("HTTP error 503: Service Unavailable")
        return {"items": []}

    with patch("app.services.api_call_executor.validate_url"):
        with patch("app.services.api_call_executor._do_request", side_effect=fake_do_request):
            with patch("time.sleep"):
                result = execute_api_call(req, conn)

    assert result.success is True
    assert call_count[0] == 3


def test_non_retryable_4xx_fails_immediately():
    req = make_request(
        retry=RetryConfig(max_retries=3, initial_backoff_ms=0, max_backoff_ms=0)
    )
    conn, cursor = make_conn()
    call_count = [0]

    def fake_do_request(**kwargs):
        call_count[0] += 1
        raise ApiCallException("Non-retryable HTTP error 404: Not Found")

    with patch("app.services.api_call_executor.validate_url"):
        with patch("app.services.api_call_executor._do_request", side_effect=fake_do_request):
            result = execute_api_call(req, conn)

    assert result.success is False
    assert "404" in result.error
    assert call_count[0] == 1  # no retries


# ---------------------------------------------------------------------------
# Auth: API_KEY header, API_KEY query, BEARER
# ---------------------------------------------------------------------------

def test_auth_bearer():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    auth = {"type": "BEARER", "token": "my-token"}
    _apply_auth(auth, headers, params)
    assert headers.get("Authorization") == "Bearer my-token"


def test_auth_api_key_header():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    auth = {"type": "API_KEY", "key_name": "X-API-Key", "key_value": "secret", "location": "header"}
    _apply_auth(auth, headers, params)
    assert headers.get("X-API-Key") == "secret"


def test_auth_api_key_query():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    auth = {"type": "API_KEY", "key_name": "api_key", "key_value": "secret", "location": "query"}
    _apply_auth(auth, headers, params)
    assert params.get("api_key") == "secret"
    assert "api_key" not in headers


def test_no_auth():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    _apply_auth(None, headers, params)
    assert headers == {}
    assert params == {}


# Java decryptedAuth format compatibility

def test_auth_java_bearer():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    auth = {"authType": "BEARER", "token": "jwt-token-123"}
    _apply_auth(auth, headers, params)
    assert headers.get("Authorization") == "Bearer jwt-token-123"


def test_auth_java_api_key_header():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    auth = {"authType": "API_KEY", "placement": "header", "headerName": "X-Custom-Key", "apiKey": "secret-key"}
    _apply_auth(auth, headers, params)
    assert headers.get("X-Custom-Key") == "secret-key"


def test_auth_java_api_key_query():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    auth = {"authType": "API_KEY", "placement": "query", "paramName": "service_key", "apiKey": "qry-secret"}
    _apply_auth(auth, headers, params)
    assert params.get("service_key") == "qry-secret"
    assert len(headers) == 0


# ---------------------------------------------------------------------------
# Redirect following with SSRF check
# ---------------------------------------------------------------------------

def test_redirect_to_private_ip_blocked():
    import httpx as httpx_module

    req = make_request()
    conn, cursor = make_conn()

    # First response: redirect to private IP
    redirect_resp = MagicMock()
    redirect_resp.status_code = 301
    redirect_resp.headers = {"location": "http://192.168.1.1/internal"}

    def fake_request(*args, **kwargs):
        return redirect_resp

    with patch("app.services.api_call_executor.validate_url") as mock_validate:
        # First call (initial URL) passes; second call (redirect) raises
        def validate_side_effect(url):
            if "192.168.1.1" in url:
                raise SsrfException("private")
        mock_validate.side_effect = validate_side_effect

        with patch("httpx.Client") as mock_client_cls:
            mock_client = MagicMock()
            mock_client.__enter__ = MagicMock(return_value=mock_client)
            mock_client.__exit__ = MagicMock(return_value=False)
            mock_client.request.return_value = redirect_resp
            mock_client_cls.return_value = mock_client

            result = execute_api_call(req, conn)

    assert result.success is False
    assert "private" in result.error


# ---------------------------------------------------------------------------
# _has_next_page unit tests
# ---------------------------------------------------------------------------

def test_has_next_page_partial_page():
    assert _has_next_page(0, 10, None, 5) is False


def test_has_next_page_full_page_no_total():
    assert _has_next_page(0, 10, None, 10) is True


def test_has_next_page_with_total_more_remaining():
    assert _has_next_page(0, 10, 25, 10) is True


def test_has_next_page_with_total_none_remaining():
    assert _has_next_page(20, 10, 25, 10) is False


def test_has_next_page_with_total_exact():
    assert _has_next_page(10, 10, 20, 10) is False


# ---------------------------------------------------------------------------
# _parse_and_map unit tests
# ---------------------------------------------------------------------------

def test_parse_and_map_basic():
    body = {"data": [{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]}
    mappings = [
        FieldMapping(source_field="id", target_column="user_id", data_type="INTEGER"),
        FieldMapping(source_field="name", target_column="user_name", data_type="TEXT"),
    ]
    rows = _parse_and_map(body, "$.data", mappings, None)
    assert len(rows) == 2
    assert rows[0] == {"user_id": 1, "user_name": "Alice"}
    assert rows[1] == {"user_id": 2, "user_name": "Bob"}


def test_parse_and_map_empty_path():
    body = [{"id": 1}]
    mappings = [FieldMapping(source_field="id", target_column="id", data_type="INTEGER")]
    rows = _parse_and_map(body, "$", mappings, None)
    assert len(rows) == 1
    assert rows[0] == {"id": 1}


def test_parse_and_map_missing_field_returns_none():
    body = {"items": [{"id": 1}]}
    mappings = [
        FieldMapping(source_field="id", target_column="id", data_type="INTEGER"),
        FieldMapping(source_field="missing", target_column="col", data_type="TEXT"),
    ]
    rows = _parse_and_map(body, "$.items", mappings, None)
    assert rows[0]["col"] is None
