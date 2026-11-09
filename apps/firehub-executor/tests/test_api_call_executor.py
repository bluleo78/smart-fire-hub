from __future__ import annotations

import json
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Dict, List, NamedTuple, Optional
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
    _convert_value,
    _resolve_mapping,
    _has_next_page,
    _parse_and_map,
    execute_api_call,
)
from app.services.db_utils import insert_batch
from app.services.value_conversion import ConversionFailed
from app.validators.ssrf_protection import SsrfException
from tests.conftest import REFUSED_BASE_URL, apply_auth


def _conv(value, fm):
    """매핑을 실행기와 같이 풀어(_resolve_mapping) 값 하나를 변환한다."""
    return _convert_value(value, _resolve_mapping(fm))


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
# WD-431: 값 변환 실패는 None 이 아니라 스텝 실패(롤백) — Java JsonResponseParser 와 같은 문구
# ---------------------------------------------------------------------------

def test_conversion_error_on_later_page_fails_and_rolls_back():
    # 둘째 페이지 첫 행(전체 3번째)의 값이 정수가 아니다 — 첫 페이지를 이미 넣었어도 커밋하지 않고 롤백한다(전부 반영 또는 전부 취소).
    req = make_request(
        pagination=PaginationConfig(type="OFFSET", page_size=2, offset_param="offset", limit_param="limit"),
        field_mappings=[
            FieldMapping(source_field="id", target_column="id_col", data_type="INTEGER"),
            FieldMapping(source_field="name", target_column="name", data_type="TEXT"),
        ],
    )
    conn, _ = make_conn()
    responses = [
        {"items": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}]},
        {"items": [{"id": "not-a-number", "name": "C"}, {"id": 4, "name": "D"}]},
    ]

    with patch("app.services.api_call_executor.validate_url"):
        with patch("app.services.api_call_executor._do_request", side_effect=responses):
            with patch("app.services.api_call_executor.insert_batch") as mock_insert:
                result = execute_api_call(req, conn)

    assert result.success is False
    assert result.error == (
        "응답 데이터의 3번째 행 'id' 필드 값 'not-a-number' 을(를) 'id_col' 컬럼의 정수(INTEGER)로 변환할 수 없습니다"
        " — 필드 매핑의 타입·형식을 확인하세요"
    )
    assert mock_insert.call_count == 1  # 첫 페이지만 — 둘째 페이지는 변환 단계에서 멈춘다
    conn.rollback.assert_called_once()
    conn.commit.assert_not_called()


@pytest.mark.parametrize(
    "dtype, raw, expected",
    [
        ("DECIMAL", "1,2x", "필드 값 '1,2x' 을(를) 'c' 컬럼의 소수(DECIMAL)로 변환할 수 없습니다"),
        ("DATE", "2024-13-40", "필드 값 '2024-13-40' 을(를) 'c' 컬럼의 날짜(DATE)로 변환할 수 없습니다"),
        ("TIMESTAMP", "nope", "필드 값 'nope' 을(를) 'c' 컬럼의 날짜시간(TIMESTAMP)으로 변환할 수 없습니다"),
        ("BOOLEAN", "Y", "필드 값 'Y' 을(를) 'c' 컬럼의 참/거짓(BOOLEAN)으로 변환할 수 없습니다(허용 값: true/false/1/0/yes/no)"),
        ("BOOLEAN", "예", "필드 값 '예' 을(를)"),
        ("INTEGER", True, "필드 값 'True' 을(를) 'c' 컬럼의 정수(INTEGER)로"),
        ("INTEGER", "y" * 60, "필드 값 '" + "y" * 50 + "…' 을(를)"),
    ],
)
def test_parse_and_map_conversion_error_raises_instead_of_none(dtype, raw, expected):
    body = {"d": [{"v": raw}]}
    with pytest.raises(ApiCallException) as exc:
        _parse_and_map(body, "$.d", [FieldMapping(source_field="v", target_column="c", data_type=dtype)], None)
    assert str(exc.value).startswith("응답 데이터의 1번째 행 'v' ")
    assert expected in str(exc.value)
    assert str(exc.value).endswith(" — 필드 매핑의 타입·형식을 확인하세요")


@pytest.mark.parametrize(
    "raw, expected",
    [("yes", True), ("FALSE", False), ("0", False), ("no", False), (True, True), (1, True), (0, False),
     ("", None), (None, None)],
)
def test_convert_boolean_strict_tokens_blank_is_null(raw, expected):
    assert _conv(raw, _fm("f", "c", "BOOLEAN")) is expected


def test_convert_blank_numeric_is_null():
    assert _conv(" ", _fm("f", "c", "INTEGER")) is None
    assert _conv("", _fm("f", "c", "DATE")) is None
    assert _conv("", _fm("f", "c", "TEXT")) == ""


def test_row_number_counts_non_object_items_like_java():
    # 객체가 아닌 원소도 행 번호에 센다 — Java JsonResponseParser 와 같은 번호(WD-431)
    body = {"d": [{"v": 1}, "junk", {"v": "x"}]}
    with pytest.raises(ApiCallException, match="응답 데이터의 3번째 행 'v' 필드 값 'x'"):
        _parse_and_map(body, "$.d", [FieldMapping(source_field="v", target_column="c", data_type="INTEGER")], None)


def test_column_type_map_wins_over_mapping_type_like_java():
    # 출력 컬럼 타입이 매핑 타입보다 먼저다(Java 경로와 같다) — 그래서 Tibero 적재기가 다시 변환할 필요가 없다
    rows, _ = _parse_and_map(
        {"d": [{"v": "7"}]}, "$.d", [FieldMapping(source_field="v", target_column="c", data_type="TEXT")], {"c": "INTEGER"}
    )
    assert rows == [{"c": 7}]


@pytest.mark.parametrize("raw, expected", [(3.0, 3), ("3.0", 3), ("1e2", 100), (" 7 ", 7), ("1,234", None)])
def test_integer_accepts_integral_float_forms(raw, expected):
    fm = _fm("f", "c", "INTEGER", number_format="comma_separated") if raw == "1,234" else _fm("f", "c", "INTEGER")
    assert _conv(raw, fm) == (1234 if expected is None else expected)


@pytest.mark.parametrize("raw", [1.5, "1.5", 1e20, "1e20", "abc", True])
def test_integer_rejects_fraction_and_out_of_range(raw):
    with pytest.raises(ConversionFailed):
        _conv(raw, _fm("f", "c", "INTEGER"))


def test_bad_date_format_is_a_config_error_not_a_value_error():
    fm = FieldMapping(source_field="when", target_column="c", data_type="DATE", date_format="%Q")
    with pytest.raises(ApiCallException) as exc:
        _parse_and_map({"d": [{"when": "2024-01-01"}]}, "$.d", [fm], None)
    assert str(exc.value) == "'when' 필드 매핑의 날짜 형식 '%Q' 이(가) 올바르지 않습니다 — 필드 매핑 설정을 확인하세요"


@pytest.mark.parametrize(
    "fmt", ["%d/%m/%Y", "%Y%m%d", "%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%d %H:%M:%S %Z", "%I:%M %p %d %b %Y", "%Y-%m-%d %H:%M:%S.%f", "%j/%Y", "%c"]
)
def test_valid_date_formats_pass_the_upfront_format_check(fmt):
    # 날짜 형식은 행 루프 전에 한 번 검증한다(WD-431) — 올바른 형식이 설정 오류로 잘못 걸리지 않아야 한다
    for dtype in ("DATE", "TIMESTAMP"):
        _resolve_mapping(FieldMapping(source_field="w", target_column="c", data_type=dtype, date_format=fmt))


def test_bad_timezone_is_a_config_error_not_ignored():
    # 예전엔 잘못된 시간대를 조용히 무시했다 — Java 경로와 같이 설정 오류로 실패한다(WD-431)
    fm = FieldMapping(source_field="ts", target_column="c", data_type="TIMESTAMP", source_timezone="Mars/Phobos")
    with pytest.raises(ApiCallException) as exc:
        _parse_and_map({"d": [{"ts": "2024-01-01T00:00:00"}]}, "$.d", [fm], None)
    assert str(exc.value) == "'ts' 필드 매핑의 시간대 'Mars/Phobos' 이(가) 올바르지 않습니다 — 필드 매핑 설정을 확인하세요"


def test_date_value_mismatch_with_valid_format_is_a_value_error():
    fm = FieldMapping(source_field="when", target_column="c", data_type="DATE", date_format="%d/%m/%Y")
    with pytest.raises(ApiCallException, match="필드 값 '2024-01-01' 을\\(를\\) 'c' 컬럼의 날짜\\(DATE\\)로"):
        _parse_and_map({"d": [{"when": "2024-01-01"}]}, "$.d", [fm], None)


def test_remote_and_pg_paths_report_the_same_conversion_message():
    # Tibero(원격 적재) 경로도 PG 경로와 같은 API_CALL 문구·전체 행 번호로 실패한다 — 적재기가 PYTHON 문구로 다시 변환하지 않는다
    from app.services.api_call_executor import execute_api_call_remote

    def make():
        return make_request(
            output_table=STAGING,
            pagination=PaginationConfig(type="OFFSET", page_size=2, offset_param="offset", limit_param="limit"),
            column_type_map={"id": "INTEGER", "name": "TEXT"},
        )

    pages = [{"items": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}]}, {"items": [{"id": 3.5, "name": "C"}]}]
    with patch("app.services.api_call_executor.validate_url"), \
            patch("app.services.api_call_executor._do_request", side_effect=list(pages)), \
            patch("app.services.api_call_executor.insert_batch"):
        pg = execute_api_call(make(), make_conn()[0])
    loader = MagicMock()
    loader.load.side_effect = lambda tenant, table, rows, types: len(rows)
    with patch("app.services.api_call_executor.validate_url"), \
            patch("app.services.api_call_executor._do_request", side_effect=list(pages)):
        remote = execute_api_call_remote(make(), loader)
    expected = (
        "응답 데이터의 3번째 행 'id' 필드 값 '3.5' 을(를) 'id' 컬럼의 정수(INTEGER)로 변환할 수 없습니다"
        " — 필드 매핑의 타입·형식을 확인하세요"
    )
    assert pg.success is False and remote.success is False
    assert pg.error == expected
    assert remote.error == expected


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
    assert _conv(123, fm) == "123"
    assert _conv("hello", fm) == "hello"


def test_convert_integer():
    fm = _fm("f", "c", "INTEGER")
    assert _conv("42", fm) == 42
    assert _conv(42, fm) == 42


def test_convert_decimal():
    fm = _fm("f", "c", "DECIMAL")
    assert _conv("3.14", fm) == Decimal("3.14")


def test_convert_boolean_true_variants():
    fm = _fm("f", "c", "BOOLEAN")
    assert _conv("true", fm) is True
    assert _conv("1", fm) is True
    assert _conv("yes", fm) is True
    assert _conv(True, fm) is True


def test_convert_boolean_false_variants():
    fm = _fm("f", "c", "BOOLEAN")
    assert _conv("false", fm) is False
    assert _conv("0", fm) is False
    assert _conv(False, fm) is False


def test_convert_date_iso():
    fm = _fm("f", "c", "DATE")
    result = _conv("2024-03-15", fm)
    assert result == date(2024, 3, 15)


def test_convert_date_with_format():
    fm = _fm("f", "c", "DATE", date_format="%d/%m/%Y")
    result = _conv("15/03/2024", fm)
    assert result == date(2024, 3, 15)


def test_convert_timestamp_iso():
    fm = _fm("f", "c", "TIMESTAMP")
    result = _conv("2024-03-15T10:30:00", fm)
    assert isinstance(result, datetime)
    assert result.year == 2024


def test_convert_timestamp_with_timezone_to_utc():
    fm = _fm("f", "c", "TIMESTAMP", source_timezone="America/New_York")
    result = _conv("2024-03-15T10:30:00", fm)
    assert isinstance(result, datetime)
    assert result.tzinfo is None  # stripped after UTC conversion


def test_convert_geometry():
    fm = _fm("f", "c", "GEOMETRY")
    assert _conv("POINT(0 0)", fm) == "POINT(0 0)"


def test_convert_none_returns_none():
    fm = _fm("f", "c", "INTEGER")
    assert _conv(None, fm) is None


# ---------------------------------------------------------------------------
# Comma-separated number format
# ---------------------------------------------------------------------------

def test_integer_comma_separated():
    fm = _fm("f", "c", "INTEGER", number_format="comma_separated")
    assert _conv("1,234,567", fm) == 1234567


def test_decimal_comma_separated():
    fm = _fm("f", "c", "DECIMAL", number_format="comma_separated")
    assert _conv("1,234.56", fm) == Decimal("1234.56")


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
    apply_auth(auth, headers, params)
    assert headers.get("Authorization") == "Bearer my-token"


def test_auth_api_key_header():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    auth = {"type": "API_KEY", "key_name": "X-API-Key", "key_value": "secret", "location": "header"}
    apply_auth(auth, headers, params)
    assert headers.get("X-API-Key") == "secret"


def test_auth_api_key_query():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    auth = {"type": "API_KEY", "key_name": "api_key", "key_value": "secret", "location": "query"}
    # 쿼리 키는 params(httpx 식 인코딩)에 넣지 않고 돌려준다 — 공용 규칙으로 따로 인코딩해 붙인다(WD-182).
    assert apply_auth(auth, headers, params) == ("api_key", "secret")
    assert params == {}
    assert "api_key" not in headers


def test_no_auth():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    apply_auth(None, headers, params)
    assert headers == {}
    assert params == {}


# Java decryptedAuth format compatibility

def test_auth_java_bearer():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    auth = {"authType": "BEARER", "token": "jwt-token-123"}
    apply_auth(auth, headers, params)
    assert headers.get("Authorization") == "Bearer jwt-token-123"


def test_auth_java_api_key_header():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    auth = {"authType": "API_KEY", "placement": "header", "headerName": "X-Custom-Key", "apiKey": "secret-key"}
    apply_auth(auth, headers, params)
    assert headers.get("X-Custom-Key") == "secret-key"


def test_auth_java_api_key_query():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {"service_key": "static-value", "pageNo": "1"}
    auth = {"authType": "API_KEY", "placement": "query", "paramName": "service_key", "apiKey": "qry-secret"}
    assert apply_auth(auth, headers, params) == ("service_key", "qry-secret")
    # 같은 이름의 정적 파라미터는 지운다 — Java 처럼 그 이름으로는 키 하나만 나간다.
    assert params == {"pageNo": "1"}
    assert len(headers) == 0


# WD-647 — Java(ApiCallAuth.resolve)와 같은 정리 규칙: 검사하는 값과 보내는 값이 두 실행기에서 같아야 한다

def test_auth_bearer_token_is_normalized_like_java():
    # 앞뒤 공백·「Bearer 」 접두어(겹친 것까지)를 뗀 값을 보낸다 — 「Bearer Bearer abc」 가 나가면 안 된다.
    for raw in ("Bearer abc", " Bearer abc ", "bearer Bearer abc", "abc"):
        headers: Dict[str, str] = {}
        apply_auth({"authType": "BEARER", "value": raw}, headers, {})
        assert headers["Authorization"] == "Bearer abc", raw


def test_auth_whitespace_only_names_are_absent():
    # 공백뿐인 headerName·placement 는 없는 것으로 보고 다음 키·기본값을 쓴다(Java 와 같다).
    headers: Dict[str, str] = {}
    apply_auth({"authType": "API_KEY", "headerName": "  ", "apiKey": "k"}, headers, {})
    assert headers == {"x-api-key": "k"}

    headers = {}
    apply_auth({"authType": "API_KEY", "headerName": " ", "key_name": "X-Alt", "apiKey": "k"}, headers, {})
    assert headers == {"X-Alt": "k"}

    # 공백뿐인 authType 이면 type 을 본다
    headers = {}
    apply_auth({"authType": " ", "type": "BEARER", "token": "t"}, headers, {})
    assert headers == {"Authorization": "Bearer t"}

    # 공백뿐인 placement 면 location 을 본다
    assert apply_auth(
        {"authType": "API_KEY", "placement": " ", "location": "query", "paramName": " ", "key_name": "svc",
         "apiKey": "k"},
        {},
        {},
    ) == ("svc", "k")


def test_auth_api_key_value_is_not_stripped():
    # API 키는 Java 처럼 원문 그대로 보낸다(정리는 BEARER 만).
    headers: Dict[str, str] = {}
    apply_auth({"authType": "API_KEY", "apiKey": " k "}, headers, {})
    assert headers == {"x-api-key": " k "}


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
    rows, _ = _parse_and_map(body, "$.data", mappings, None)
    assert len(rows) == 2
    assert rows[0] == {"user_id": 1, "user_name": "Alice"}
    assert rows[1] == {"user_id": 2, "user_name": "Bob"}


def test_parse_and_map_empty_path():
    body = [{"id": 1}]
    mappings = [FieldMapping(source_field="id", target_column="id", data_type="INTEGER")]
    rows, _ = _parse_and_map(body, "$", mappings, None)
    assert len(rows) == 1
    assert rows[0] == {"id": 1}


def test_parse_and_map_missing_field_returns_none():
    body = {"items": [{"id": 1}]}
    mappings = [
        FieldMapping(source_field="id", target_column="id", data_type="INTEGER"),
        FieldMapping(source_field="missing", target_column="col", data_type="TEXT"),
    ]
    rows, _ = _parse_and_map(body, "$.items", mappings, None)
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
    """실제 닫힌 포트(REFUSED_BASE_URL)로 보내 httpx 가 내는 예외를 그대로 분류한다(영어 [Errno …] 원문 대신 한국어 안내)."""
    req = make_request(
        url=f"{REFUSED_BASE_URL}/api",
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
    # 행은 _parse_and_map 이 이미 변환했다(WD-431) — 적재기는 직렬화만 한다
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


# ---------------------------------------------------------------------------
# WD-362 — 1 미만 페이지 크기 보정
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("bad", [-5, 0])
def test_offset_pagination_non_positive_page_size_falls_back_to_default(bad):
    """저장 검증 전에 저장된 0·음수 페이지 크기는 기본값(100)으로 보정해 끝없이 요청하지 않는다(WD-362).

    보정 전에는 「받은 행 수 < 페이지 크기」가 결코 참이 아니라 offset 0, -5, -10… 으로 MAX_PAGES 까지 요청했다.
    """
    req = make_request(
        pagination=PaginationConfig(
            type="OFFSET",
            page_size=bad,
            offset_param="offset",
            limit_param="limit",
        )
    )
    conn, cursor = make_conn()
    # API 가 limit 를 무시하고 같은 2행을 준다 — 기본값 100 보다 적으니 첫 페이지에서 멈춰야 한다
    body = {"items": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}]}

    with (
        patch("app.services.api_call_executor.validate_url"),
        patch("app.services.api_call_executor._do_request", return_value=body) as do_request,
        patch("app.services.api_call_executor.insert_batch"),
    ):
        result = execute_api_call(req, conn)

    assert result.success is True
    assert do_request.call_count == 1
    # 첫 요청의 limit 도 기본값으로 보낸다
    assert do_request.call_args.kwargs["query_params"]["limit"] == "100"


# ---------------------------------------------------------------------------
# WD-657: 다른 출처 리다이렉트에 인증 떼기·Bearer 끝 앵커·헤더 이름 공백·오류문 비밀 가림
# ---------------------------------------------------------------------------

def _redirect(status: int, location: str) -> MagicMock:
    resp = _status_response(status)
    resp.headers = {"location": location}
    return resp


class _Run(NamedTuple):
    """``_run_with_responses`` 결과 — 실행 결과와, 단언에 쓰는 가짜 객체(요청 기록 client·주소 검사 validate)."""

    result: Any
    client: MagicMock
    validate: MagicMock


def _run_with_responses(req, responses) -> _Run:
    """httpx.Client 를 바꿔 차례로 응답하게 하고 실행 결과와 가짜 객체를 돌려준다 — 테스트마다 같은 patch 묶음을 다시 쓰지 않게 한다."""
    conn, _ = make_conn()
    client = _patched_client(responses)
    with patch("app.services.api_call_executor.validate_url") as validate, \
            patch("httpx.Client", return_value=client), patch("time.sleep"), \
            patch("app.services.api_call_executor.insert_batch"):
        result = execute_api_call(req, conn)
    return _Run(result, client, validate)


def _sent(client, index):
    """index 번째 요청의 (url, headers)."""
    kwargs = client.request.call_args_list[index].kwargs
    return kwargs["url"], kwargs["headers"]


def test_cross_origin_redirect_drops_bearer_and_same_named_custom_header():
    req = make_request(
        url="https://a.example.com/api",
        headers={"authorization": "Bearer custom-copy", "X-Trace": "t1"},
        auth={"authType": "BEARER", "token": "CrossTok657"},
    )
    result, client, _ = _run_with_responses(
        req, [_redirect(302, "https://b.example.com/next"), mock_response({"items": []})]
    )

    assert result.success is True
    _, first_headers = _sent(client, 0)
    assert first_headers["Authorization"] == "Bearer CrossTok657"
    url, headers = _sent(client, 1)
    assert url == "https://b.example.com/next"
    assert {k.lower() for k in headers} == {"x-trace"}


def test_cross_origin_redirect_drops_api_key_header_and_echoed_query_key():
    req = make_request(
        url="https://a.example.com/api",
        auth={"authType": "API_KEY", "headerName": "X-Key", "apiKey": "HdrKey657"},
    )
    _, client, _ = _run_with_responses(
        req, [_redirect(301, "https://a.example.com:8443/next"), mock_response({"items": []})]
    )
    assert "X-Key" not in _sent(client, 1)[1]

    req = make_request(
        url="http://a.example.com/api",
        auth={"authType": "API_KEY", "placement": "query", "paramName": "serviceKey", "apiKey": "QryKey657"},
    )
    _, client, _ = _run_with_responses(
        req,
        [_redirect(302, "http://b.example.com/api?serviceKey=QryKey657&x=1#f"), mock_response({"items": []})],
    )
    assert _sent(client, 0)[0] == "http://a.example.com/api?serviceKey=QryKey657"
    # 다른 호스트 — 되울린 키를 빼고 나머지 쿼리는 둔다. 프래그먼트는 Location 을 풀 때 뗀다(WD-686, Java 와 같게).
    assert _sent(client, 1)[0] == "http://b.example.com/api?x=1"


def test_http_to_https_upgrade_keeps_header_and_query_key():
    # 같은 호스트 http→https 승격(기본 포트끼리)은 같은 출처 — serviceKey 가 따라가던 WD-182 동작을 지킨다.
    req = make_request(
        url="http://a.example.com/api",
        headers={"X-Key": "HdrUp657"},
        auth={"authType": "API_KEY", "placement": "query", "paramName": "serviceKey", "apiKey": "QryUp657"},
    )
    _, client, _ = _run_with_responses(
        req,
        [_redirect(301, "https://a.example.com/api?serviceKey=QryUp657"), mock_response({"items": []})],
    )
    url, headers = _sent(client, 1)
    assert url == "https://a.example.com/api?serviceKey=QryUp657"
    assert headers["X-Key"] == "HdrUp657"

    req = make_request(url="http://a.example.com/api", auth={"authType": "BEARER", "token": "UpTok657"})
    _, client, _ = _run_with_responses(req, [_redirect(301, "https://a.example.com/v2"), mock_response({"items": []})])
    assert _sent(client, 1)[1]["Authorization"] == "Bearer UpTok657"


def test_https_to_http_downgrade_drops_header_and_query_key():
    req = make_request(
        url="https://a.example.com/api",
        auth={"authType": "API_KEY", "placement": "query", "paramName": "serviceKey", "apiKey": "QryDown657"},
    )
    _, client, _ = _run_with_responses(
        req,
        [_redirect(302, "http://a.example.com/api?serviceKey=QryDown657&x=1"), mock_response({"items": []})],
    )
    assert _sent(client, 1)[0] == "http://a.example.com/api?x=1"

    req = make_request(url="https://a.example.com/api", auth={"authType": "BEARER", "token": "DownTok657"})
    _, client, _ = _run_with_responses(req, [_redirect(302, "http://a.example.com/v2"), mock_response({"items": []})])
    assert "Authorization" not in _sent(client, 1)[1]


def test_relative_location_is_resolved_first_and_followed_as_same_origin():
    req = make_request(url="https://a.example.com/api/v1", auth={"authType": "BEARER", "token": "RelTok657"})
    result, client, mock_validate = _run_with_responses(
        req, [_redirect(302, "/v2/items?page=2"), mock_response({"items": []})]
    )

    assert result.success is True
    url, headers = _sent(client, 1)
    assert url == "https://a.example.com/v2/items?page=2"
    assert headers["Authorization"] == "Bearer RelTok657"
    # 주소 검사도 푼 절대 주소로 한다
    mock_validate.assert_any_call("https://a.example.com/v2/items?page=2")


def test_redirect_a_to_b_to_a_restores_query_key():
    req = make_request(
        url="https://a.example.com/one",
        auth={"authType": "API_KEY", "placement": "query", "paramName": "serviceKey", "apiKey": "AbaQry657"},
    )
    _, client, _ = _run_with_responses(
        req,
        [
            _redirect(302, "https://b.example.com/two?serviceKey=AbaQry657"),
            _redirect(302, "https://a.example.com/three?p=1"),
            mock_response({"items": []}),
        ],
    )
    assert _sent(client, 1)[0] == "https://b.example.com/two"
    assert _sent(client, 2)[0] == "https://a.example.com/three?p=1&serviceKey=AbaQry657"


def test_cross_origin_redirect_drops_user_credential_headers_with_query_auth():
    req = make_request(
        url="https://a.example.com/api",
        headers={"Authorization": "Basic dXNlcjpwdw==", "proxy-authorization": "Basic cA==", "COOKIE": "sid=1",
                 "X-Trace": "t"},
        auth={"authType": "API_KEY", "placement": "query", "paramName": "serviceKey", "apiKey": "ChKey657"},
    )
    _, client, _ = _run_with_responses(
        req, [_redirect(302, "https://b.example.com/next"), mock_response({"items": []})]
    )
    assert _sent(client, 0)[1]["Authorization"] == "Basic dXNlcjpwdw=="
    assert _sent(client, 1)[1] == {"X-Trace": "t"}


def test_same_origin_redirect_keeps_auth_with_default_port_normalized():
    req = make_request(
        url="https://a.example.com/api",
        auth={"authType": "BEARER", "token": "SameTok657"},
    )
    _, client, _ = _run_with_responses(
        req, [_redirect(302, "https://A.EXAMPLE.com:443/next"), mock_response({"items": []})]
    )
    assert _sent(client, 1)[1]["Authorization"] == "Bearer SameTok657"


def test_redirect_a_to_b_to_a_uses_original_origin():
    req = make_request(
        url="https://a.example.com/one",
        auth={"authType": "BEARER", "token": "AbaTok657"},
    )
    _, client, _ = _run_with_responses(
        req,
        [
            _redirect(302, "https://b.example.com/two"),
            _redirect(302, "https://a.example.com/three"),
            mock_response({"items": []}),
        ],
    )
    assert "Authorization" not in _sent(client, 1)[1]
    url, headers = _sent(client, 2)
    assert url == "https://a.example.com/three"
    assert headers["Authorization"] == "Bearer AbaTok657"


def test_strip_query_param_helper_matches_java_rules():
    # 출처 판정 사례는 공용 사례 표(api-call-auth-vectors.json, test_api_call_auth_vectors.py)가 본다.
    from app.services.api_call_executor import _strip_query_param

    assert _strip_query_param("https://b.example/p?svc=K&x=1&svc=K2#frag", "svc") == "https://b.example/p?x=1#frag"
    assert _strip_query_param("https://b.example/p?s%76c=K", "svc") == "https://b.example/p"
    assert _strip_query_param("https://b.example/p?svcx=1", "svc") == "https://b.example/p?svcx=1"


def test_describe_exception_scrubs_sent_secret():
    from app.services import api_call_errors as errors

    message = errors.describe_exception(
        RuntimeError("boom Bearer HdrSecret657xyz"), 1000, "h", errors.SentSecrets(key="HdrSecret657xyz")
    )
    assert "HdrSecret657xyz" not in message
    assert "****7xyz" in message
    # 최소 길이(4자) 미만 비밀은 Java 처럼 손대지 않는다 — 평범한 글자를 지우지 않게
    assert errors.describe_exception(RuntimeError("abc"), 1000, "h", errors.SentSecrets(key="abc")).endswith("abc")


def test_retry_warning_and_failure_message_scrub_header_secret(caplog):
    req = make_request(
        retry=RetryConfig(max_retries=1, initial_backoff_ms=0, max_backoff_ms=0),
        auth={"authType": "API_KEY", "headerName": "X-Key", "apiKey": "LeakKey657abc"},
    )
    with caplog.at_level("WARNING"):
        result, _, _ = _run_with_responses(req, [RuntimeError("upstream echoed LeakKey657abc")])

    assert result.success is False
    assert "LeakKey657abc" not in result.error
    assert "****7abc" in result.error
    assert "LeakKey657abc" not in caplog.text


def test_execution_log_scrubs_secret_in_url():
    req = make_request(
        url="http://public.example.com/api/data?key=UrlKey657abc",
        auth={"authType": "API_KEY", "headerName": "X-Key", "apiKey": "UrlKey657abc"},
    )
    result, _, _ = _run_with_responses(req, [mock_response({"items": []})])
    assert result.success is True
    assert "UrlKey657abc" not in result.execution_log


# ---------------------------------------------------------------------------
# WD-676: 다른 출처 307/308 리다이렉트에 POST 본문을 싣지 않는다(Java 와 같게)
# ---------------------------------------------------------------------------

def _sent_method_body(client, index):
    """index 번째 요청의 (method, content)."""
    kwargs = client.request.call_args_list[index].kwargs
    return kwargs["method"], kwargs["content"]


@pytest.mark.parametrize("status", [307, 308])
def test_cross_origin_307_308_post_follows_as_get_without_body(status):
    req = make_request(
        url="https://a.example.com/token",
        method="POST",
        body='{"client_secret":"x"}',
        headers={"Content-Type": "application/json", "X-Trace": "t676"},
    )
    result, client, _ = _run_with_responses(
        req, [_redirect(status, "https://b.example.com/next"), mock_response({"items": []})]
    )

    assert result.success is True
    assert _sent_method_body(client, 0) == ("POST", b'{"client_secret":"x"}')
    assert _sent_method_body(client, 1) == ("GET", None)
    # 본문을 버린 홉에는 본문 헤더도 없다(Java 리다이렉트 GET 홉과 같다). 다른 헤더는 그대로 간다.
    first_headers = {k.lower(): v for k, v in _sent(client, 0)[1].items()}
    second_headers = {k.lower(): v for k, v in _sent(client, 1)[1].items()}
    assert first_headers["content-type"] == "application/json"
    assert "content-type" not in second_headers
    assert "content-length" not in second_headers
    assert second_headers["x-trace"] == "t676"


def test_redirect_a_to_b_to_a_does_not_restore_post_body():
    # 처음 출처로 돌아와도(인증은 다시 붙지만) 본문은 다시 싣지 않는다.
    req = make_request(
        url="https://a.example.com/one",
        method="POST",
        body='{"client_secret":"x"}',
        headers={"Content-Type": "application/json"},
    )
    _, client, _ = _run_with_responses(
        req,
        [
            _redirect(307, "https://b.example.com/two"),
            _redirect(307, "https://a.example.com/three"),
            mock_response({"items": []}),
        ],
    )
    assert _sent_method_body(client, 1) == ("GET", None)
    assert _sent_method_body(client, 2) == ("GET", None)
    # 처음 출처로 돌아와 인증 헤더가 다시 붙어도 본문 헤더는 붙지 않는다.
    assert "content-type" not in {k.lower() for k in _sent(client, 2)[1]}


def test_same_origin_307_keeps_post_and_body():
    # 같은 출처 307 은 지금 동작(POST·본문 유지)을 고정한다 — 운영 동작 보존.
    req = make_request(
        url="https://a.example.com/one", method="POST", body='{"client_secret":"x"}'
    )
    _, client, _ = _run_with_responses(
        req, [_redirect(307, "https://a.example.com/two"), mock_response({"items": []})]
    )
    assert _sent_method_body(client, 1) == ("POST", b'{"client_secret":"x"}')


# ---------------------------------------------------------------------------
# WD-686: 같은 출처 301/302 도 본문 없는 GET — Java 실행기와 규칙 하나, Location 프래그먼트 떼기
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("status", [301, 302, 303])
def test_same_origin_301_302_303_post_follows_as_get_without_body(status):
    # RFC 9110·브라우저 관례 — 301·302·303 은 같은 출처여도 본문 없는 GET. 예전에는 같은 출처 301·302 에서 POST·본문을 유지해
    # 모든 리다이렉트를 GET 으로 보내던 Java 실행기와 달랐다.
    req = make_request(
        url="https://a.example.com/one",
        method="POST",
        body='{"client_secret":"x"}',
        headers={"Content-Type": "application/json", "X-Trace": "t686"},
    )
    result, client, _ = _run_with_responses(
        req, [_redirect(status, "https://a.example.com/two"), mock_response({"items": []})]
    )
    assert result.success is True
    assert _sent_method_body(client, 1) == ("GET", None)
    second_headers = {k.lower(): v for k, v in _sent(client, 1)[1].items()}
    assert "content-type" not in second_headers
    assert second_headers["x-trace"] == "t686"


def test_same_origin_308_keeps_post_and_body():
    # 308 도 307 처럼 같은 출처면 메서드·본문(본문 헤더 포함)을 유지한다 — Java 실행기와 같다(WD-686).
    req = make_request(
        url="https://a.example.com/one",
        method="POST",
        body='{"client_secret":"x"}',
        headers={"Content-Type": "application/json"},
    )
    _, client, _ = _run_with_responses(
        req, [_redirect(308, "https://a.example.com/two"), mock_response({"items": []})]
    )
    assert _sent_method_body(client, 1) == ("POST", b'{"client_secret":"x"}')
    assert {k.lower(): v for k, v in _sent(client, 1)[1].items()}["content-type"] == "application/json"


def test_body_dropped_by_303_stays_get_on_later_same_origin_307():
    # 한 번 본문을 버리면 그 뒤 같은 출처 307 이 와도 본문 없는 GET 이 이어진다 — Java 실행기와 같다(WD-686).
    req = make_request(
        url="https://a.example.com/one",
        method="POST",
        body='{"client_secret":"x"}',
        headers={"Content-Type": "application/json"},
    )
    _, client, _ = _run_with_responses(
        req,
        [
            _redirect(303, "https://a.example.com/two"),
            _redirect(307, "https://a.example.com/three"),
            mock_response({"items": []}),
        ],
    )
    assert _sent_method_body(client, 2) == ("GET", None)
    assert "content-type" not in {k.lower() for k in _sent(client, 2)[1]}


def test_redirect_location_fragment_is_dropped_before_ssrf_check_and_request():
    # Location 의 프래그먼트는 떼고 따라간다(WD-686) — 요청·주소 검사(SSRF)·로그에 섞이지 않게. Java RedirectLocations 와 같다.
    req = make_request(url="https://a.example.com/one")
    _, client, validate = _run_with_responses(
        req, [_redirect(302, "/two?page=2#frag"), mock_response({"items": []})]
    )
    assert _sent(client, 1)[0] == "https://a.example.com/two?page=2"
    assert "https://a.example.com/two?page=2" in [c.args[0] for c in validate.call_args_list]
    assert all("#" not in c.args[0] for c in validate.call_args_list)


# ---------------------------------------------------------------------------
# WD-681: BASIC 인증 — 헤더·다른 출처 리다이렉트 떼기·비밀 가림·실행 기록 URL 사용자 정보 가림
# ---------------------------------------------------------------------------

import base64 as _b64  # noqa: E402


def _basic(username: str, password: str) -> str:
    """Basic 인증 헤더 값의 Base64 덩어리(UTF-8)."""
    return _b64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")


def test_auth_basic_sets_authorization_header():
    headers: Dict[str, str] = {}
    params: Dict[str, str] = {}
    auth = {"authType": "BASIC", "username": "홍길동", "password": "pa:ss 1"}
    assert apply_auth(auth, headers, params) is None
    assert headers == {"Authorization": "Basic " + _basic("홍길동", "pa:ss 1")}
    assert params == {}


def test_cross_origin_redirect_drops_basic_authorization():
    req = make_request(
        url="https://a.example.com/api",
        headers={"X-Trace": "t1"},
        auth={"authType": "BASIC", "username": "alice", "password": "BasicPw681"},
    )
    result, client, _ = _run_with_responses(
        req,
        [
            _redirect(302, "https://a.example.com/same"),
            _redirect(302, "https://b.example.com/next"),
            mock_response({"items": []}),
        ],
    )

    assert result.success is True
    _, first = _sent(client, 0)
    assert first["Authorization"] == "Basic " + _basic("alice", "BasicPw681")
    _, same = _sent(client, 1)
    assert same["Authorization"] == "Basic " + _basic("alice", "BasicPw681")
    url, other = _sent(client, 2)
    assert url == "https://b.example.com/next"
    assert {k.lower() for k in other} == {"x-trace"}


def test_basic_secret_and_base64_scrubbed_from_errors(caplog):
    blob = _basic("alice", "LeakPw681abc")
    req = make_request(
        retry=RetryConfig(max_retries=1, initial_backoff_ms=0, max_backoff_ms=0),
        auth={"authType": "BASIC", "username": "alice", "password": "LeakPw681abc"},
    )
    with caplog.at_level("WARNING"):
        result, _, _ = _run_with_responses(
            req, [RuntimeError(f"upstream echoed LeakPw681abc and Basic {blob}")]
        )

    assert result.success is False
    assert "LeakPw681abc" not in result.error
    assert blob not in result.error
    assert "LeakPw681abc" not in caplog.text
    assert blob not in caplog.text


def test_execution_log_masks_url_userinfo_password():
    # 옛 연결(API_KEY + baseUrl 사용자 정보)은 이관하지 않아 URL 에 사용자 정보가 남는다 — 실행 기록 요약 줄에서는 비밀번호를 가린다.
    req = make_request(
        url="http://svc:LegacyPw681@public.example.com/api/data",
        auth={"authType": "API_KEY", "headerName": "X-Key", "apiKey": "k681"},
    )
    result, client, _ = _run_with_responses(req, [mock_response({"items": []})])

    assert result.success is True
    assert "LegacyPw681" not in result.execution_log
    assert "url=http://svc:****@public.example.com/api/data" in result.execution_log
    # 호출은 원래대로(사용자 정보 포함 URL) — 옛 연결 동작은 바꾸지 않는다
    assert client.request.call_args.kwargs["url"] == "http://svc:LegacyPw681@public.example.com/api/data"


def test_basic_strips_url_userinfo_so_configured_credentials_win():
    # WD-681 리뷰: URL 에 사용자 정보가 남으면 httpx 가 그것으로 Basic 인증을 만들어 설정한 헤더를 덮는다 — BASIC 은 떼고 보낸다.
    req = make_request(
        url="https://old:OldPw681@a.example.com/api",
        auth={"authType": "BASIC", "username": "alice", "password": "NewPw681"},
    )
    result, client, _ = _run_with_responses(
        req,
        [_redirect(302, "https://evil:EvilPw@a.example.com/next"), mock_response({"items": []})],
    )

    assert result.success is True
    url, first = _sent(client, 0)
    assert url == "https://a.example.com/api"
    assert first["Authorization"] == "Basic " + _basic("alice", "NewPw681")
    url2, second = _sent(client, 1)
    assert url2 == "https://a.example.com/next"
    assert second["Authorization"] == "Basic " + _basic("alice", "NewPw681")


def test_basic_short_password_withholds_error_text():
    # WD-681 리뷰: 4자 미만 비밀번호는 지울 수 없어 남으면 문장을 통째로 가린다(SMTP 규칙) — 비밀번호가 실행 기록에 나가지 않는다.
    req = make_request(
        retry=RetryConfig(max_retries=0, initial_backoff_ms=0, max_backoff_ms=0),
        auth={"authType": "BASIC", "username": "alice", "password": "q7z"},
    )
    result, _, _ = _run_with_responses(req, [RuntimeError("upstream said q7z is wrong")])

    assert result.success is False
    assert "q7z" not in result.error
