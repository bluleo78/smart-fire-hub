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
    _parse_and_map,
    execute_api_call,
)
from app.services.db_utils import insert_batch
from app.validators.ssrf_protection import SsrfException


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def make_request(**kwargs) -> ApiCallExecuteRequest:
    defaults = dict(
        tenant_id=1,
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
            with patch("app.services.api_call_executor.insert_batch") as mock_insert:
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
            with patch("app.services.api_call_executor.insert_batch"):
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
            with patch("app.services.api_call_executor.insert_batch"):
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
            with patch("app.services.api_call_executor.insert_batch"):
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
            with patch("app.services.api_call_executor.insert_batch"):
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
    # 쿼리 키는 params(httpx 식 인코딩)에 넣지 않고 돌려준다 — 공용 규칙으로 따로 인코딩해 붙인다(WD-182).
    assert _apply_auth(auth, headers, params) == ("api_key", "secret")
    assert params == {}
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
    params: Dict[str, str] = {"service_key": "static-value", "pageNo": "1"}
    auth = {"authType": "API_KEY", "placement": "query", "paramName": "service_key", "apiKey": "qry-secret"}
    assert _apply_auth(auth, headers, params) == ("service_key", "qry-secret")
    # 같은 이름의 정적 파라미터는 지운다 — Java 처럼 그 이름으로는 키 하나만 나간다.
    assert params == {"pageNo": "1"}
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


# ---------------------------------------------------------------------------
# WD-150: 재시도 판정·한국어 원인 문구 — 실제 _do_request 분기를 타도록 httpx.Client 만 바꾼다
# ---------------------------------------------------------------------------

def _status_response(status: int) -> MagicMock:
    resp = MagicMock()
    resp.status_code = status
    resp.headers = {}
    resp.text = "server said something"
    return resp


def _patched_client(responses):
    """httpx.Client 를 바꿔 request() 가 responses 를 차례로 돌려주게 한다(마지막 값은 반복)."""
    mock_client = MagicMock()
    mock_client.__enter__ = MagicMock(return_value=mock_client)
    mock_client.__exit__ = MagicMock(return_value=False)
    seq = list(responses)

    def fake_request(*args, **kwargs):
        item = seq.pop(0) if len(seq) > 1 else seq[0]
        if isinstance(item, BaseException):
            raise item
        return item

    mock_client.request.side_effect = fake_request
    return mock_client


def test_405_fails_immediately_without_retry():
    req = make_request(retry=RetryConfig(max_retries=3, initial_backoff_ms=0, max_backoff_ms=0))
    conn, _ = make_conn()
    client = _patched_client([_status_response(405)])
    with patch("app.services.api_call_executor.validate_url"), \
            patch("httpx.Client", return_value=client), patch("time.sleep"):
        result = execute_api_call(req, conn)

    assert result.success is False
    assert result.error == "허용되지 않는 요청 방식입니다(HTTP 405) — GET/POST 설정을 확인하세요"
    assert client.request.call_count == 1


def test_429_is_retried_then_succeeds():
    req = make_request(retry=RetryConfig(max_retries=2, initial_backoff_ms=0, max_backoff_ms=0))
    conn, _ = make_conn()
    client = _patched_client([_status_response(429), mock_response({"items": [{"id": 1, "name": "a"}]})])
    with patch("app.services.api_call_executor.validate_url"), \
            patch("httpx.Client", return_value=client), patch("time.sleep"), \
            patch("app.services.api_call_executor.insert_batch"):
        result = execute_api_call(req, conn)

    assert result.success is True
    assert result.rows_loaded == 1
    assert client.request.call_count == 2


def test_503_exhausted_message_includes_cause_and_no_body():
    req = make_request(retry=RetryConfig(max_retries=2, initial_backoff_ms=0, max_backoff_ms=0))
    conn, _ = make_conn()
    client = _patched_client([_status_response(503)])
    with patch("app.services.api_call_executor.validate_url"), \
            patch("httpx.Client", return_value=client), patch("time.sleep"):
        result = execute_api_call(req, conn)

    assert result.error == "API 호출이 2회 재시도 후에도 실패했습니다 — 마지막 오류: 대상 서버 오류(HTTP 503)"
    assert "server said" not in result.error
    assert client.request.call_count == 3


def test_401_message_has_no_query_key():
    req = make_request(
        retry=RetryConfig(max_retries=2, initial_backoff_ms=0, max_backoff_ms=0),
        auth={"authType": "API_KEY", "placement": "query", "paramName": "serviceKey", "apiKey": "SECRET123"},
    )
    conn, _ = make_conn()
    client = _patched_client([_status_response(401)])
    with patch("app.services.api_call_executor.validate_url"), \
            patch("httpx.Client", return_value=client), patch("time.sleep"):
        result = execute_api_call(req, conn)

    assert result.error == "인증에 실패했습니다(HTTP 401) — API 키·토큰 설정을 확인하세요"
    assert "SECRET123" not in result.error
    assert client.request.call_count == 1
    # 키는 실제로 쿼리로 보냈다(문구에서만 빠진 것). WD-182 부터 쿼리는 URL 에 직접 붙이고 params= 를 넘기지 않는다.
    assert client.request.call_args.kwargs["url"] == "http://public.example.com/api/data?serviceKey=SECRET123"
    assert "params" not in client.request.call_args.kwargs


def test_redirect_ssrf_block_is_not_retried():
    req = make_request(retry=RetryConfig(max_retries=2, initial_backoff_ms=0, max_backoff_ms=0))
    conn, _ = make_conn()
    redirect = _status_response(302)
    redirect.headers = {"location": "http://127.0.0.1/meta"}
    client = _patched_client([redirect])

    def validate_side_effect(url):
        if "127.0.0.1" in url:
            raise SsrfException("내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1")

    with patch("app.services.api_call_executor.validate_url", side_effect=validate_side_effect), \
            patch("httpx.Client", return_value=client), patch("time.sleep"):
        result = execute_api_call(req, conn)

    assert result.error == "내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1"
    assert client.request.call_count == 1


def test_non_json_response_is_not_retried():
    req = make_request(retry=RetryConfig(max_retries=2, initial_backoff_ms=0, max_backoff_ms=0))
    conn, _ = make_conn()
    resp = _status_response(200)
    resp.content = b"<html>oops</html>"
    resp.json.side_effect = ValueError("Expecting value")
    client = _patched_client([resp])
    with patch("app.services.api_call_executor.validate_url"), \
            patch("httpx.Client", return_value=client), patch("time.sleep"):
        result = execute_api_call(req, conn)

    assert result.error == "응답이 JSON 형식이 아닙니다 — 대상 API 의 응답 형식을 확인하세요"
    assert client.request.call_count == 1


def test_connection_refused_real_socket_retried_with_cause():
    """실제 닫힌 포트로 보내 httpx 가 내는 예외를 그대로 분류한다(영어 [Errno …] 원문 대신 한국어 안내)."""
    import socket as sock

    s = sock.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()

    req = make_request(
        url=f"http://127.0.0.1:{port}/api",
        retry=RetryConfig(max_retries=1, initial_backoff_ms=0, max_backoff_ms=0),
    )
    conn, _ = make_conn()
    with patch("app.services.api_call_executor.validate_url"), patch("time.sleep"):
        result = execute_api_call(req, conn)

    assert result.error == (
        "API 호출이 1회 재시도 후에도 실패했습니다 — 마지막 오류: "
        "연결이 거부되었습니다 — 주소·포트와 서버 실행 여부를 확인하세요"
    )


def test_timeout_real_socket_retried_with_cause():
    """연결은 받되 응답하지 않는 소켓 — 읽기 타임아웃이 「응답 시간 초과(0.3초)」로 보인다."""
    import socket as sock

    server = sock.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(8)
    port = server.getsockname()[1]
    try:
        req = make_request(
            url=f"http://127.0.0.1:{port}/api",
            timeout_ms=300,
            retry=RetryConfig(max_retries=1, initial_backoff_ms=0, max_backoff_ms=0),
        )
        conn, _ = make_conn()
        with patch("app.services.api_call_executor.validate_url"), patch("time.sleep"):
            result = execute_api_call(req, conn)
    finally:
        server.close()

    assert result.error == (
        "API 호출이 1회 재시도 후에도 실패했습니다 — 마지막 오류: "
        "응답 시간 초과(0.3초) — 대상 서버 상태나 시간 제한을 확인하세요"
    )


# ---------------------------------------------------------------------------
# WD-212: 데이터 경로 규칙을 Java JsonResponseParser(jayway)와 맞춘다 — 매치 없음은 실패, [] 매치는 0행 정상
# ---------------------------------------------------------------------------

from app.services import api_call_errors as errors  # noqa: E402
from app.services.api_call_executor import _extract_data  # noqa: E402

NESTED = {
    "response": {
        "body": {
            "items": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}],
            "empty": [],
            "single": {"id": 3, "name": "C"},
            "nul": None,
        }
    },
    "list": [{"id": 1}, {"id": 2}],
    "결과": {"목록": [{"id": 9}]},
}


@pytest.mark.parametrize(
    "path, expected",
    [
        # 정의된 경로 — 목록이면 그 목록, 객체면 한 행 후보, [] 는 0행, null 은 0행(Java 도 같게 고침)
        ("$.response.body.items", NESTED["response"]["body"]["items"]),
        ("$.response.body.empty", []),
        ("$.response.body.single", [{"id": 3, "name": "C"}]),
        ("$.response.body.nul", []),
        ("$.list[0]", [{"id": 1}]),
        ("$.list[-1]", [{"id": 2}]),
        # 정의되지 않은 경로 — 매치 전부(예전에는 첫 매치만 써 [*] 가 첫 원소 하나뿐이었다)
        ("$.list[*]", [{"id": 1}, {"id": 2}]),
        ("$.response.body.items[*]", NESTED["response"]["body"]["items"]),
        ("$.response.body.empty[*]", []),
        ("$.list[?(@.id > 1)]", [{"id": 2}]),
        ("$.list[?(@.id > 9)]", []),
        ("$..zz", []),
        ("$.list[*].zz", []),
        # 한글 키 점 표기 — jayway 는 읽는다(jsonpath_ng 렉서용으로 대괄호 표기로 바꿔 읽는다)
        ("$.결과.목록", [{"id": 9}]),
        ("$['결과']['목록']", [{"id": 9}]),
    ],
)
def test_extract_data_matches_java_rules(path, expected):
    assert _extract_data(NESTED, path) == expected


@pytest.mark.parametrize(
    "path",
    [
        "$.response.body.missing",   # 없는 키(Java PathNotFoundException)
        "$.nope.items",              # 중간 키 없음
        "$.list[5]",                 # 범위 밖 인덱스
        "$.response.body.items.id",  # 배열에 필드 접근
        "$.missing[*]",              # 와일드카드 앞 경로가 없음(jayway 도 실패)
        "$.missing..id",
    ],
)
def test_extract_data_not_found_raises_java_message(path):
    with pytest.raises(ApiCallException) as ei:
        _extract_data(NESTED, path)
    assert str(ei.value) == f"응답에서 데이터 경로를 찾을 수 없습니다: {path}"


# 두 라이브러리(jayway·jsonpath_ng)가 모두 문법 오류로 보는 경로만 둔다. 끝의 「[」(예: $.data[)는 jayway 가 받아들여 서로 다르다(알려진 차이).
@pytest.mark.parametrize("path", ["$..", "$.a b", "$.list[?(@.id >", "$[\"data\""])
def test_extract_data_syntax_error_raises_java_message(path):
    with pytest.raises(ApiCallException) as ei:
        _extract_data(NESTED, path)
    assert str(ei.value) == errors.data_path_invalid_message(path)
    assert "Parse error" not in str(ei.value) and "Unexpected" not in str(ei.value)


def test_path_not_found_fails_step_and_rolls_back():
    """수정 전에는 success=True·rows_loaded=0 이었다 — REPLACE 면 api 가 옛 데이터를 그대로 두고 「완료」로 기록했다."""
    req = make_request(data_path="$.data.items")
    conn, _ = make_conn()
    with patch("app.services.api_call_executor.validate_url"), patch(
        "app.services.api_call_executor._do_request", return_value={"result": {"rows": []}}
    ), patch("app.services.api_call_executor.insert_batch") as mock_insert:
        result = execute_api_call(req, conn)
    assert result.success is False
    assert result.error == "응답에서 데이터 경로를 찾을 수 없습니다: $.data.items"
    mock_insert.assert_not_called()
    conn.rollback.assert_called_once()
    conn.commit.assert_not_called()


def test_empty_array_match_is_zero_row_success():
    req = make_request(data_path="$.data.items")
    conn, _ = make_conn()
    with patch("app.services.api_call_executor.validate_url"), patch(
        "app.services.api_call_executor._do_request", return_value={"data": {"items": []}}
    ):
        result = execute_api_call(req, conn)
    assert result.success is True and result.rows_loaded == 0 and result.error is None
    conn.commit.assert_called_once()


def test_single_object_match_loads_one_row():
    req = make_request(data_path="$.data.item")
    conn, _ = make_conn()
    with patch("app.services.api_call_executor.validate_url"), patch(
        "app.services.api_call_executor._do_request", return_value={"data": {"item": {"id": 7, "name": "Z"}}}
    ), patch("app.services.api_call_executor.insert_batch") as mock_insert:
        result = execute_api_call(req, conn)
    assert result.success is True and result.rows_loaded == 1
    assert mock_insert.call_args.args[2] == [{"id": 7, "name": "Z"}]


def test_offset_pagination_path_missing_on_later_page_fails():
    """둘째 페이지에서 경로가 사라지면 실패(롤백) — 첫 페이지 적재분도 커밋하지 않는다."""
    req = make_request(
        data_path="$.items",
        pagination=PaginationConfig(type="OFFSET", page_size=2, offset_param="offset", limit_param="limit"),
    )
    conn, _ = make_conn()
    pages = [{"items": [{"id": 1, "name": "a"}, {"id": 2, "name": "b"}]}, {"error": "bad offset"}]
    with patch("app.services.api_call_executor.validate_url"), patch(
        "app.services.api_call_executor._do_request", side_effect=pages
    ), patch("app.services.api_call_executor.insert_batch"):
        result = execute_api_call(req, conn)
    assert result.success is False
    assert result.error == "응답에서 데이터 경로를 찾을 수 없습니다: $.items"
    conn.commit.assert_not_called()


def test_load_db_error_is_korean_and_raw_only_in_log(caplog):
    """psycopg2 적재 오류 원문(내부 테이블·값)은 사용자 문구에 싣지 않는다 — 로그에만 남는다."""
    import psycopg2

    req = make_request()
    conn, _ = make_conn()
    raw = 'invalid input syntax for type bigint: "abc"\nCONTEXT: COPY ptmp_secret_table'
    with patch("app.services.api_call_executor.validate_url"), patch(
        "app.services.api_call_executor._do_request", return_value={"items": [{"id": 1, "name": "x"}]}
    ), patch("app.services.api_call_executor.insert_batch", side_effect=psycopg2.DataError(raw)):
        with caplog.at_level("ERROR"):
            result = execute_api_call(req, conn)
    assert result.success is False
    assert result.error == errors.load_failed_message()
    assert "ptmp_secret_table" not in result.error
    assert "ptmp_secret_table" in caplog.text
    conn.rollback.assert_called_once()


def test_unexpected_error_is_korean_not_raw():
    """HTTP 요청 밖(파싱·매핑 등)의 예기치 못한 예외는 원문 대신 한국어 일반 문구. 요청 중 예외는 기존 WD-150 분류
    (describe_exception — Java describe 4단계와 같은 「API 호출 중 오류가 발생했습니다: …」)를 그대로 탄다."""
    req = make_request()
    conn2, _ = make_conn()
    with patch("app.services.api_call_executor.validate_url"), patch(
        "app.services.api_call_executor._do_request", return_value={"items": [{"id": 1, "name": "x"}]}
    ), patch("app.services.api_call_executor._parse_and_map", side_effect=KeyError("internal_key")):
        result2 = execute_api_call(req, conn2)
    assert result2.success is False
    assert result2.error == errors.unexpected_error_message()
    assert "internal_key" not in result2.error


# ---------------------------------------------------------------------------
# WD-212 라이브 대체: 실제 FastAPI 엔드포인트(/execute/api-call)를 TestClient 로 호출 — httpx.Client 만 실제 모양 응답으로 바꾼다
# ---------------------------------------------------------------------------

def _json_response(payload: Any) -> MagicMock:
    resp = MagicMock()
    resp.status_code = 200
    raw = json.dumps(payload, ensure_ascii=False).encode()
    resp.content = raw
    resp.headers = {"content-type": "application/json", "content-length": str(len(raw))}
    resp.json.side_effect = lambda: json.loads(raw)
    return resp


def _post_api_call(test_client, headers, data_path, payload, insert_side_effect=None):
    from unittest.mock import AsyncMock

    conn_cm = MagicMock()
    conn, _ = make_conn()
    conn_cm.return_value.__enter__.return_value = conn
    body = {
        "tenantId": 7,
        "url": "http://public.example.com/openapi/list?pageNo=1",
        "method": "GET",
        "data_path": data_path,
        "field_mappings": [
            {"source_field": "stationName", "target_column": "station_name", "data_type": "TEXT"},
            {"source_field": "pm10Value", "target_column": "pm10", "data_type": "INTEGER"},
        ],
        "output_table": "ptmp_out",
        "retry": {"max_retries": 0, "initial_backoff_ms": 0, "max_backoff_ms": 0},
    }
    with patch("app.routers.api_call.backend_of", new=AsyncMock(return_value=("POSTGRESQL", None))), patch(
        "app.db.connection.get_connection", conn_cm
    ), patch("app.services.api_call_executor.validate_url"), patch(
        "app.services.api_call_executor.httpx.Client", return_value=_patched_client([_json_response(payload)])
    ), patch("app.services.api_call_executor.insert_batch", side_effect=insert_side_effect) as mock_insert:
        resp = test_client.post("/execute/api-call", json=body, headers=headers)
    return resp, conn, mock_insert


# 공공데이터 API 모양 응답(response.body.items.item)
AIR_OK = {
    "response": {
        "header": {"resultCode": "00", "resultMsg": "NORMAL_CODE"},
        "body": {"items": {"item": [{"stationName": "종로구", "pm10Value": "41"}]}, "totalCount": 1},
    }
}
AIR_ERROR = {"response": {"header": {"resultCode": "30", "resultMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}}}
AIR_EMPTY = {"response": {"header": {"resultCode": "00"}, "body": {"items": {"item": []}, "totalCount": 0}}}


def test_route_path_ok(test_client, mock_auth_header):
    resp, conn, mock_insert = _post_api_call(test_client, mock_auth_header, "$.response.body.items.item", AIR_OK)
    data = resp.json()
    assert resp.status_code == 200 and data["success"] is True and data["rows_loaded"] == 1
    assert mock_insert.call_args.args[2] == [{"station_name": "종로구", "pm10": 41}]


def test_route_path_missing_fails_with_java_message(test_client, mock_auth_header):
    """인증키 오류 응답처럼 body 가 없는 응답 — 예전에는 success=true·0행이었다."""
    resp, conn, mock_insert = _post_api_call(test_client, mock_auth_header, "$.response.body.items.item", AIR_ERROR)
    data = resp.json()
    assert resp.status_code == 200
    assert data["success"] is False
    assert data["error"] == "응답에서 데이터 경로를 찾을 수 없습니다: $.response.body.items.item"
    mock_insert.assert_not_called()
    conn.commit.assert_not_called()


def test_route_empty_array_is_zero_row_success(test_client, mock_auth_header):
    resp, _, mock_insert = _post_api_call(test_client, mock_auth_header, "$.response.body.items.item", AIR_EMPTY)
    data = resp.json()
    assert data["success"] is True and data["rows_loaded"] == 0 and data["error"] is None
    mock_insert.assert_not_called()


def test_route_single_object_is_one_row(test_client, mock_auth_header):
    payload = {"response": {"body": {"items": {"item": {"stationName": "중구", "pm10Value": "12"}}}}}
    resp, _, mock_insert = _post_api_call(test_client, mock_auth_header, "$.response.body.items.item", payload)
    data = resp.json()
    assert data["success"] is True and data["rows_loaded"] == 1
    assert mock_insert.call_args.args[2] == [{"station_name": "중구", "pm10": 12}]


def test_route_syntax_error(test_client, mock_auth_header):
    resp, _, _ = _post_api_call(test_client, mock_auth_header, "$.response..", AIR_OK)
    data = resp.json()
    assert data["success"] is False
    assert data["error"] == "데이터 경로(JSONPath) 형식이 올바르지 않습니다: $.response.. — 예: $.data.items"


def test_route_load_error_is_korean(test_client, mock_auth_header):
    import psycopg2

    resp, conn, _ = _post_api_call(
        test_client,
        mock_auth_header,
        "$.response.body.items.item",
        AIR_OK,
        insert_side_effect=psycopg2.errors.lookup("22P02")('invalid input syntax for type bigint: "x"'),
    )
    data = resp.json()
    assert data["success"] is False
    assert data["error"] == errors.load_failed_message()
    assert "bigint" not in data["error"]
    conn.rollback.assert_called_once()


# ---------------------------------------------------------------------------
# Tibero 원격 적재 경로(execute_api_call_remote, WD-77 Task 6)
# ---------------------------------------------------------------------------

STAGING = "STG_0123456789ABCDEF01234567"


def _remote_request(**kwargs) -> ApiCallExecuteRequest:
    return make_request(output_table=STAGING, **kwargs)


def test_remote_single_page_loads_through_loader_without_pg_connection():
    from app.services.api_call_executor import execute_api_call_remote

    loader = MagicMock()
    loader.load.return_value = 2
    body = {"items": [{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]}
    req = _remote_request(column_type_map={"id": "INTEGER", "name": "TEXT"})
    with patch("app.services.api_call_executor.validate_url"), \
            patch("app.services.api_call_executor._do_request", return_value=body), \
            patch("app.services.api_call_executor.insert_batch") as pg_insert:
        result = execute_api_call_remote(req, loader)
    assert result.success is True and result.rows_loaded == 2 and result.total_pages == 1
    loader.load.assert_called_once_with(
        1, STAGING, [{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}], {"id": "INTEGER", "name": "TEXT"}
    )
    pg_insert.assert_not_called()


def test_remote_pages_all_go_to_the_same_staging_in_order():
    from app.services.api_call_executor import execute_api_call_remote

    loader = MagicMock()
    loader.load.side_effect = lambda tenant, table, rows, types: len(rows)
    pages = [
        {"items": [{"id": 1, "name": "a"}, {"id": 2, "name": "b"}]},
        {"items": [{"id": 3, "name": "c"}, {"id": 4, "name": "d"}]},
        {"items": [{"id": 5, "name": "e"}]},
    ]
    req = _remote_request(pagination=PaginationConfig(type="OFFSET", page_size=2, offset_param="o", limit_param="l"))
    with patch("app.services.api_call_executor.validate_url"), \
            patch("app.services.api_call_executor._do_request", side_effect=pages):
        result = execute_api_call_remote(req, loader)
    assert result.success is True and result.rows_loaded == 5 and result.total_pages == 3
    assert [c.args[1] for c in loader.load.call_args_list] == [STAGING] * 3
    assert [[r["id"] for r in c.args[2]] for c in loader.load.call_args_list] == [[1, 2], [3, 4], [5]]


def test_remote_load_failure_stops_immediately_and_reports_error():
    from app.db.backends.remote import RemoteLoadError
    from app.services.api_call_executor import execute_api_call_remote

    loader = MagicMock()
    loader.load.side_effect = [2, RemoteLoadError("적재 실패 — 두 번째 페이지")]
    pages = [
        {"items": [{"id": 1, "name": "a"}, {"id": 2, "name": "b"}]},
        {"items": [{"id": 3, "name": "c"}, {"id": 4, "name": "d"}]},
        {"items": [{"id": 5, "name": "e"}]},
    ]
    req = _remote_request(pagination=PaginationConfig(type="OFFSET", page_size=2))
    with patch("app.services.api_call_executor.validate_url"), \
            patch("app.services.api_call_executor._do_request", side_effect=pages) as fetch:
        result = execute_api_call_remote(req, loader)
    assert result.success is False and result.error == "적재 실패 — 두 번째 페이지"
    assert result.rows_loaded == 0
    assert loader.load.call_count == 2  # 세 번째 페이지는 가져오지도 않는다
    assert fetch.call_count == 2


def test_remote_empty_result_loads_nothing_and_succeeds_with_zero_rows():
    from app.services.api_call_executor import execute_api_call_remote

    loader = MagicMock()
    with patch("app.services.api_call_executor.validate_url"), \
            patch("app.services.api_call_executor._do_request", return_value={"items": []}):
        result = execute_api_call_remote(_remote_request(), loader)
    assert result.success is True and result.rows_loaded == 0
    loader.load.assert_not_called()


def test_remote_http_failure_is_success_false_and_nothing_loaded():
    from app.services.api_call_executor import execute_api_call_remote

    loader = MagicMock()
    with patch("app.services.api_call_executor.validate_url"), \
            patch("app.services.api_call_executor._do_request", side_effect=ApiCallException("HTTP 404")):
        result = execute_api_call_remote(_remote_request(), loader)
    assert result.success is False and result.error == "HTTP 404"
    loader.load.assert_not_called()


def test_remote_ssrf_rejection_loads_nothing():
    from app.services.api_call_executor import execute_api_call_remote

    loader = MagicMock()
    with patch("app.services.api_call_executor.validate_url", side_effect=SsrfException("blocked")):
        result = execute_api_call_remote(_remote_request(), loader)
    assert result.success is False and "blocked" in result.error
    loader.load.assert_not_called()


def test_remote_max_duration_keeps_partial_result_like_pg():
    from app.services.api_call_executor import execute_api_call_remote

    loader = MagicMock()
    loader.load.side_effect = lambda tenant, table, rows, types: len(rows)
    page = {"items": [{"id": 1, "name": "a"}, {"id": 2, "name": "b"}]}
    req = _remote_request(
        pagination=PaginationConfig(type="OFFSET", page_size=2), max_duration_ms=0
    )
    with patch("app.services.api_call_executor.validate_url"), \
            patch("app.services.api_call_executor._do_request", return_value=page):
        result = execute_api_call_remote(req, loader)
    assert result.success is True and result.total_pages == 1 and "Partial result" in result.execution_log


@pytest.mark.parametrize("bad", ["test_table", STAGING.lower(), STAGING + "\n"])
def test_remote_refuses_non_staging_output_table_without_any_call(bad):
    from app.services.api_call_executor import execute_api_call_remote

    loader = MagicMock()
    with patch("app.services.api_call_executor._do_request") as fetch:
        result = execute_api_call_remote(make_request(output_table=bad), loader)
    assert result.success is False and "STG_" in result.error
    fetch.assert_not_called()
    loader.load.assert_not_called()
