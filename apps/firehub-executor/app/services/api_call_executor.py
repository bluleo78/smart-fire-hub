from __future__ import annotations

import logging
import random
import time
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlsplit, urlunsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import httpx
import psycopg2
from jsonpath_ng import jsonpath as jp
from jsonpath_ng.ext import parse as jsonpath_parse
from jsonpath_ng.ext.filter import Filter as JsonPathFilter
from app.schemas.requests import ApiCallExecuteRequest, FieldMapping
from app.schemas.responses import ApiCallExecuteResponse
from app.services import api_call_errors as errors
from app.services.api_call_errors import RetryableApiCallError
from app.services.db_utils import insert_batch
from app.services.query_key_encoding import encode_query_key
from app.validators.ssrf_protection import SsrfException, validate_url

logger = logging.getLogger(__name__)

MAX_REDIRECTS = 5
DEFAULT_PAGE_SIZE = 100
MAX_PAGES = 10_000

class ApiCallException(Exception):
    """Non-retryable API call error."""
    pass


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

def execute_api_call(request: ApiCallExecuteRequest, conn) -> ApiCallExecuteResponse:
    start_time = time.monotonic()
    try:
        # 1. SSRF validation on initial URL
        validate_url(request.url)

        total_rows = 0
        total_pages = 0
        log_lines: List[str] = []

        # 2. Pagination
        pagination_type = (request.pagination.type.upper() if request.pagination else "NONE")

        if pagination_type == "OFFSET":
            pag = request.pagination
            page_size = pag.page_size or DEFAULT_PAGE_SIZE
            offset = 0
            total_count: Optional[int] = None

            while total_pages < MAX_PAGES:
                # Build pagination params
                pag_params: Dict[str, str] = {}
                if pag.offset_param:
                    pag_params[pag.offset_param] = str(offset)
                if pag.limit_param:
                    pag_params[pag.limit_param] = str(page_size)

                response_body = _execute_request(request, pag_params)
                rows = _parse_and_map(
                    response_body,
                    request.data_path,
                    request.field_mappings,
                    request.column_type_map,
                )

                # Extract total count (first page only)
                if total_count is None and pag.total_path:
                    raw_total = _read_path_safe(response_body, pag.total_path)
                    if raw_total is not None:
                        try:
                            total_count = int(raw_total)
                        except (TypeError, ValueError):
                            total_count = None

                if rows:
                    insert_batch(conn, request.output_table, rows)
                    total_rows += len(rows)
                total_pages += 1

                # Duration check
                elapsed_ms = int((time.monotonic() - start_time) * 1000)
                if elapsed_ms >= request.max_duration_ms:
                    log_lines.append(
                        f"[WARN] maxDurationMs ({request.max_duration_ms} ms) exceeded "
                        f"after {total_pages} pages. Partial result."
                    )
                    break

                # Next page check
                if not _has_next_page(offset, page_size, total_count, len(rows)):
                    break
                offset += page_size
        else:
            # Single request (NONE or unknown pagination type)
            response_body = _execute_request(request, {})
            rows = _parse_and_map(
                response_body,
                request.data_path,
                request.field_mappings,
                request.column_type_map,
            )
            if rows:
                insert_batch(conn, request.output_table, rows)
                total_rows += len(rows)
            total_pages = 1

        elapsed_ms = int((time.monotonic() - start_time) * 1000)
        summary = (
            f"url={request.url} method={request.method} "
            f"pages={total_pages} rows={total_rows} duration={elapsed_ms}ms"
        )
        log_lines.insert(0, summary)

        conn.commit()

        return ApiCallExecuteResponse(
            success=True,
            rows_loaded=total_rows,
            total_pages=total_pages,
            execution_log="\n".join(log_lines),
            execution_time_ms=elapsed_ms,
        )

    except (SsrfException, ApiCallException) as e:
        conn.rollback()
        elapsed_ms = int((time.monotonic() - start_time) * 1000)
        return ApiCallExecuteResponse(
            success=False, error=str(e), execution_time_ms=elapsed_ms
        )
    except Exception as e:
        conn.rollback()
        elapsed_ms = int((time.monotonic() - start_time) * 1000)
        # 원문(psycopg2 오류의 내부 테이블·SQL·값, 파이썬 예외 문자열)은 로그에만 남기고 사용자에게는 한국어 문구를 보인다(WD-212).
        # 예전에는 str(e) 원문이 그대로 스텝 오류가 됐다. 적재(DB) 오류와 그 밖의 오류를 나눠, 사용자가 매핑을 볼지 관리자에게 물을지
        # 알 수 있게 한다. PG 데이터 오류(22xxx) 사유 문장은 이번엔 만들지 않는다(api 가 pg_error 를 읽는 WD-211 이 아직 기반에 없다).
        if isinstance(e, psycopg2.Error):
            logger.exception(
                "API call load failed: table=%s pgcode=%s", request.output_table, getattr(e, "pgcode", None)
            )
            message = errors.load_failed_message()
        else:
            logger.exception("Unexpected error in execute_api_call")
            message = errors.unexpected_error_message()
        return ApiCallExecuteResponse(
            success=False, error=message, execution_time_ms=elapsed_ms
        )


# ---------------------------------------------------------------------------
# HTTP request with retry + manual redirect + SSRF check
# ---------------------------------------------------------------------------

def _execute_request(request: ApiCallExecuteRequest, extra_params: Dict[str, str]) -> Any:
    """Execute a single HTTP request with retry and manual redirect following."""
    retry_cfg = request.retry
    max_retries = retry_cfg.max_retries if retry_cfg else 3
    initial_backoff_ms = retry_cfg.initial_backoff_ms if retry_cfg else 1000
    max_backoff_ms = retry_cfg.max_backoff_ms if retry_cfg else 30000

    timeout_sec = request.timeout_ms / 1000.0
    max_bytes = request.max_response_size_mb * 1024 * 1024

    attempt = 0
    last_error: Optional[Exception] = None
    try:
        target_host = urlsplit(request.url).hostname
    except ValueError:
        target_host = None

    while attempt <= max_retries:
        try:
            response_body = _do_request(
                url=request.url,
                method=request.method,
                headers=request.headers or {},
                query_params={**(request.query_params or {}), **extra_params},
                body=request.body,
                auth=request.auth,
                timeout_sec=timeout_sec,
                max_bytes=max_bytes,
            )
            return response_body
        except (ApiCallException, SsrfException):
            # 재시도 안 함: 고쳐지지 않는 4xx·응답 크기 초과 등과 리다이렉트 대상이 내부망인 경우(SsrfException).
            # 예전에는 SsrfException 이 아래로 떨어져 같은 차단을 N회 재시도했다(WD-150, Java 와 같게).
            raise
        except Exception as e:
            # 타임아웃·연결 오류·5xx/408/429 — 재시도. 원인을 사용자 문구로 바꿔 둔다(httpx 영어 원문 대신).
            last_error = e
            last_message = errors.describe_exception(e, request.timeout_ms, target_host)
            attempt += 1
            if attempt > max_retries:
                break
            backoff_ms = min(initial_backoff_ms * (2 ** (attempt - 1)), max_backoff_ms)
            # Add jitter: ±25%
            jitter_ms = backoff_ms * 0.25 * (random.random() * 2 - 1)
            sleep_sec = max(0.0, (backoff_ms + jitter_ms) / 1000.0)
            logger.warning(
                "Attempt %d/%d failed (%s). Retrying in %.2fs",
                attempt, max_retries, last_message, sleep_sec,
            )
            time.sleep(sleep_sec)

    # 마지막 원인을 반드시 담는다(WD-150). 횟수는 Java 와 같게 「재시도 횟수」(max_retries)로 센다 — 예전 「All N attempts」는
    # 첫 시도까지 세어 Java 문구와 숫자가 달랐다.
    last_message = (
        errors.describe_exception(last_error, request.timeout_ms, target_host)
        if last_error is not None
        else ""
    )
    raise ApiCallException(errors.retries_exhausted_message(max_retries, last_message))


def _do_request(
    url: str,
    method: str,
    headers: Dict[str, str],
    query_params: Dict[str, str],
    body: Optional[str],
    auth: Optional[dict],
    timeout_sec: float,
    max_bytes: int,
) -> Any:
    """Perform a single HTTP call with manual redirect following and SSRF checks."""
    # Build auth headers
    req_headers = dict(headers)
    req_params = dict(query_params)
    auth_query = _apply_auth(auth, req_headers, req_params)

    # 쿼리 문자열은 직접 만들어 URL 에 붙이고 httpx 에 params= 를 넘기지 않는다(WD-182). params= 를 넘기면 httpx 가
    # (1) 값을 다시 인코딩해 인코딩 키의 %2B 가 %252B 로 나가고 (2) URL 에 이미 있던 쿼리를 통째로 바꿔 버린다(httpx 0.28,
    # params={} 여도 쿼리가 사라진다). 문자열 URL 의 쿼리는 httpx 가 유효한 %XX 를 그대로 보낸다.
    current_url = _build_request_url(url, req_params, auth_query)
    redirect_count = 0

    with httpx.Client(follow_redirects=False, timeout=timeout_sec) as client:
        while True:
            # 리다이렉트 대상(Location)은 받은 그대로 쓴다 — 예전에는 params={} 로 Location 의 쿼리(흔한 http→https 리다이렉트가
            # 옮겨 주는 키 포함)까지 지워졌다(WD-182).
            resp = client.request(
                method=method,
                url=current_url,
                headers=req_headers,
                content=body.encode() if body else None,
            )

            if resp.status_code in (301, 302, 303, 307, 308):
                redirect_count += 1
                if redirect_count > MAX_REDIRECTS:
                    raise ApiCallException(errors.too_many_redirects_message(MAX_REDIRECTS))
                location = resp.headers.get("location")
                if not location:
                    raise ApiCallException(errors.redirect_without_location_message())
                # SSRF check on redirect target
                validate_url(location)
                current_url = location
                # For 303, switch to GET
                if resp.status_code == 303:
                    method = "GET"
                    body = None
                continue

            # HTTP 오류: 상태별 한국어 안내(WD-150). 5xx·408·429 만 재시도, 나머지 4xx 는 즉시 실패 —
            # 예전에는 400/401/403/404 만 즉시 실패라 405 같은 오류도 재시도하며 오래 걸렸다. 응답 본문은 붙이지 않는다
            # (Java 와 같은 문구를 쓰고, 본문에 무엇이 담겼는지 모르므로 실행 기록에 남기지 않는다).
            if resp.status_code >= 400:
                message = errors.http_status_message(resp.status_code)
                if errors.is_retryable_status(resp.status_code):
                    raise RetryableApiCallError(message)
                raise ApiCallException(message)

            # Check response size
            max_mb = max_bytes // (1024 * 1024)
            content_length = resp.headers.get("content-length")
            if content_length and int(content_length) > max_bytes:
                raise ApiCallException(errors.response_too_large_message(max_mb))

            content = resp.content
            if len(content) > max_bytes:
                raise ApiCallException(errors.response_too_large_message(max_mb))

            # JSON 이 아닌 응답(HTML 오류 페이지 등)은 다시 보내도 같다 — 재시도하지 않는다(Java 도 파싱 실패는 재시도 밖).
            try:
                return resp.json()
            except ValueError as e:
                raise ApiCallException(errors.not_json_message()) from e


def _build_request_url(
    url: str,
    params: Dict[str, str],
    auth_query: Optional[Tuple[str, str]],
) -> str:
    """URL 에 정적·페이지네이션 파라미터와 쿼리 인증 키를 붙인 호출 URL 을 만든다(WD-182).

    순서는 Java(``AuthQueryMasker.build``)와 같다 — URL 에 이미 있던 쿼리(그대로), 정적·페이지네이션 파라미터, 인증 키.
    붙이는 파라미터는 이름·값 모두 api 와 공용 규칙(``encode_query_key``)으로 한 번만 인코딩한다 — 인증 키(WD-182)뿐 아니라
    정적·페이지네이션 파라미터도(WD-200). 예전에는 정적 파라미터를 httpx ``QueryParams`` 로 다시 인코딩해 이미 인코딩된 값
    (``abc%2Bd%3D%3D``)이 ``abc%252Bd%253D%253D`` 로 나가 대상 서버 인증이 깨졌고, api 미리보기와 바이트가 달랐다.
    공용 사례 표 ``fixtures/query-param-encoding-vectors.json`` 으로 Java 와 같은 쿼리 문자열인지 검증한다.
    """
    parts = urlsplit(url)
    pieces = []
    if parts.query:
        pieces.append(parts.query)
    for name, value in params.items():
        pieces.append(f"{_encode_param(name)}={_encode_param(value)}")
    if auth_query is not None:
        name, raw_key = auth_query
        pieces.append(f"{_encode_param(name)}={_encode_param(raw_key)}")
    if not pieces:
        return url
    return urlunsplit(parts._replace(query="&".join(pieces)))


def _encode_param(raw: Any) -> str:
    """쿼리 파라미터 이름·값 하나를 공용 규칙으로 인코딩한다(WD-200). None 은 빈 값, 그 밖은 ``str`` 로 바꾼 뒤 인코딩한다 —
    Java(``AuthQueryMasker.encodeParam``)도 null 을 빈 값으로 보내 ``name=`` 으로 같은 바이트가 나간다."""
    if raw is None:
        return ""
    # bool 은 예전 httpx QueryParams 처럼 소문자 true/false 로 보낸다 — str(True) 의 "True" 로 바이트가 바뀌지 않게.
    if isinstance(raw, bool):
        return "true" if raw else "false"
    return encode_query_key(str(raw))


def _apply_auth(
    auth: Optional[dict],
    headers: Dict[str, str],
    params: Dict[str, str],
) -> Optional[Tuple[str, str]]:
    """Apply authentication to request headers or query params.

    Supports both Java decryptedAuth format (authType, headerName, apiKey,
    placement, paramName) and generic format (type, key_name, key_value, location).

    쿼리 인증(API_KEY + placement=query)은 ``params`` 에 넣지 않고 ``(파라미터 이름, 키 원문)`` 을 돌려준다(WD-182) —
    ``params`` 는 httpx 식으로 인코딩되므로, 키는 ``_build_request_url`` 이 공용 규칙으로 따로 붙인다. 같은 이름의 정적
    파라미터가 있으면 ``params`` 에서 지운다(Java 처럼 그 이름으로는 키 하나만 나간다 — 위치는 맨 끝). 그 밖에는 None.
    """
    if not auth:
        return None
    auth_type = (auth.get("authType") or auth.get("type") or "").upper()
    if auth_type == "BEARER":
        token = auth.get("token") or auth.get("value") or ""
        headers["Authorization"] = f"Bearer {token}"
    elif auth_type == "API_KEY":
        placement = (auth.get("placement") or auth.get("location") or "header").upper()
        if placement == "QUERY":
            param_name = auth.get("paramName") or auth.get("key_name") or "api_key"
            api_key = auth.get("apiKey") or auth.get("key_value") or auth.get("value") or ""
            params.pop(param_name, None)
            return (param_name, api_key)
        else:
            header_name = auth.get("headerName") or auth.get("key_name") or "x-api-key"
            api_key = auth.get("apiKey") or auth.get("key_value") or auth.get("value") or ""
            headers[header_name] = api_key
    return None


# ---------------------------------------------------------------------------
# JSON parsing + field mapping
# ---------------------------------------------------------------------------

def _parse_and_map(
    response_body: Any,
    data_path: str,
    field_mappings: List[FieldMapping],
    column_type_map: Optional[dict],
) -> List[Dict[str, Any]]:
    """Extract items from response using jsonpath, map fields."""
    items = _extract_data(response_body, data_path)
    if not items:
        return []

    rows: List[Dict[str, Any]] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        row: Dict[str, Any] = {}
        for fm in field_mappings:
            raw_value = item.get(fm.source_field)
            converted = _convert_value(raw_value, fm)
            row[fm.target_column] = converted
        rows.append(row)
    return rows


def _extract_data(response_body: Any, data_path: str) -> List[Any]:
    """데이터 경로(JSONPath)로 응답에서 행 후보 목록을 꺼낸다 — Java ``JsonResponseParser.parseAndMap`` 과 같은 규칙(WD-212).

    예전에는 매치가 없거나 파싱 오류가 나도 ``[]`` 를 돌려 「완료·0행」으로 끝났다. REPLACE 출력이면 옛 데이터가 그대로 남아
    응답 모양이 바뀌어 갱신이 멈춘 것을 사용자가 알 수 없었고, 같은 설정을 Java 경로는 실패로 알렸다. 규칙(jayway 실측):

    - 경로가 비었거나 ``$`` 면 응답 전체(목록이면 그대로, 아니면 한 행 후보).
    - 문법 오류 → ``data_path_invalid_message`` 실패(Java ``InvalidPathException``).
    - **정의된 경로**(루트·필드 하나·인덱스 하나로만 된 경로): 매치가 없으면 ``data_path_not_found_message`` 실패
      (Java ``PathNotFoundException`` — 없는 키, 범위 밖 인덱스, 배열에 필드 접근 등). 값이 목록이면 그 목록(``[]`` 면 0행 정상),
      객체·스칼라면 한 행 후보, JSON null 이면 0행(Java 도 같게 고쳤다).
    - **정의되지 않은 경로**(``[*]``·``..``·슬라이스·필터·``*``·여러 필드/인덱스): 첫 비정의 구간 **앞까지**의 경로가 없을 때만
      실패한다(jayway: ``$.missing[*]`` 실패, ``$..missing`` 은 빈 목록). 그 밖에는 매치된 값 전부를 돌려준다(없으면 0행 정상).
      예전 구현은 첫 매치만 써 ``$.list[*]`` 가 첫 원소 하나만 적재했다 — Java 처럼 전부 적재한다.

    문구는 Java ``ApiCallErrorMessages`` 와 글자 단위로 같아야 한다(양쪽 테스트가 같은 문자열을 단언한다).
    """
    if not data_path or data_path.strip() in ("$", ""):
        if isinstance(response_body, list):
            return response_body
        return [] if response_body is None else [response_body]

    try:
        expr = jsonpath_parse(_bracket_non_ascii_keys(data_path))
    except Exception as e:
        # 파서 원문(영어)은 로그에만 — 사용자 문구는 Java 와 같은 한국어.
        logger.warning("jsonpath parse error for path '%s': %s", data_path, e)
        raise ApiCallException(errors.data_path_invalid_message(data_path)) from e

    segments = _flatten_child(expr)
    definite_len = 0
    for seg in segments:
        if not _is_definite_segment(seg):
            break
        definite_len += 1

    matches = _find_safe(expr, response_body, data_path)

    if definite_len == len(segments):
        # 정의된 경로 — 값 하나. 매치가 없으면 Java 처럼 실패.
        if not matches:
            raise ApiCallException(errors.data_path_not_found_message(data_path))
        value = matches[0].value
        if value is None:
            return []
        if isinstance(value, list):
            return value
        return [value]

    # 정의되지 않은 경로 — 첫 비정의 구간 앞까지가 응답에 있어야 한다(없으면 Java 처럼 실패).
    if definite_len > 0 and not _is_root_only(segments[:definite_len]):
        prefix = _join_child(segments[:definite_len])
        if not _find_safe(prefix, response_body, data_path):
            raise ApiCallException(errors.data_path_not_found_message(data_path))
    return [m.value for m in matches]


def _find_safe(expr: Any, response_body: Any, data_path: str) -> list:
    """``find`` 중 예외(필터의 타입 비교 등)는 「매치 없음」으로 본다 — jayway 는 같은 경우 예외 없이 매치하지 않는다."""
    try:
        return expr.find(response_body)
    except Exception as e:
        logger.warning("jsonpath find error for path '%s': %s", data_path, e)
        return []


def _flatten_child(expr: Any) -> List[Any]:
    """``Child(Child(a, b), c)`` 를 ``[a, b, c]`` 로 편다(왼쪽부터 평가 순서).

    ``a..b``(Descendants)는 왼쪽(``a``)을 펴고 자기 자신을 비정의 구간 하나로 붙인다 — ``$.missing..id`` 의 앞부분
    ``$.missing`` 이 없으면 Java 처럼 실패시키려고. 구간은 정의 여부 판정과 앞부분 재조립에만 쓰고, 매치는 원래 식으로 찾는다.
    """
    if isinstance(expr, jp.Child):
        return _flatten_child(expr.left) + _flatten_child(expr.right)
    if isinstance(expr, jp.Descendants):
        return _flatten_child(expr.left) + [expr]
    return [expr]


def _join_child(segments: List[Any]) -> Any:
    """``_flatten_child`` 의 반대 — 구간 목록을 다시 ``Child`` 식으로 묶는다."""
    expr = segments[0]
    for seg in segments[1:]:
        expr = jp.Child(expr, seg)
    return expr


def _is_root_only(segments: List[Any]) -> bool:
    return all(isinstance(s, (jp.Root, jp.This)) for s in segments)


def _is_definite_segment(seg: Any) -> bool:
    """jayway 의 「정의된 경로」 구간인지 — 루트, 필드 하나(``*`` 아님), 인덱스 하나. 모르는 노드는 비정의로 본다
    (정상 경로를 새로 실패시키지 않으려고 — 비정의는 앞부분만 확인한다)."""
    if isinstance(seg, (jp.Root, jp.This)):
        return True
    if isinstance(seg, JsonPathFilter):
        return False
    if isinstance(seg, jp.Fields):
        return len(seg.fields) == 1 and seg.fields[0] != "*"
    if type(seg) is jp.Index:
        return len(seg.indices) == 1
    return False


def _bracket_non_ascii_keys(path: str) -> str:
    """점 표기 키에 jsonpath_ng 가 못 읽는 글자(한글 등)가 있으면 대괄호 표기로 바꾼다 — ``$.결과.목록`` → ``$['결과']['목록']``.

    jayway(Java)는 ``$.결과.목록`` 을 읽지만 jsonpath_ng 렉서는 영문·숫자·_·@·-·한자만 키로 받아, 그대로 두면 Java 에서 되는
    경로가 실행기에서만 문법 오류가 된다. 대괄호·따옴표 안(필터 식·이미 대괄호인 키)은 건드리지 않는다.
    """
    out: List[str] = []
    i, n = 0, len(path)
    depth = 0
    quote: Optional[str] = None
    while i < n:
        c = path[i]
        if quote:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(path[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in ("'", '"'):
            quote = c
        elif c == "[":
            depth += 1
        elif c == "]":
            depth = max(0, depth - 1)
        elif c == "." and depth == 0:
            # 「..」(하위 전체)는 점 두 개를 그대로 두고 그 뒤 키만 본다.
            dots = 1
            while i + dots < n and path[i + dots] == ".":
                dots += 1
            j = i + dots
            while j < n and path[j] not in ".[":
                j += 1
            key = path[i + dots:j]
            if key and any(ord(ch) > 127 for ch in key):
                escaped = key.replace("\\", "\\\\").replace("'", "\\'")
                # 「.키」는 「['키']」로, 「..키」는 「..['키']」로(하위 전체 검색은 유지).
                out.append(".." if dots > 1 else "")
                out.append(f"['{escaped}']")
                i = j
                continue
            out.append(path[i:j])
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _read_path_safe(response_body: Any, path: str) -> Any:
    """Read a single value at path, returning None on error."""
    try:
        expr = jsonpath_parse(path)
        matches = expr.find(response_body)
        if matches:
            return matches[0].value
    except Exception:
        pass
    return None


def _convert_value(value: Any, fm: FieldMapping) -> Any:
    """Convert a raw value to the target data type specified in FieldMapping."""
    if value is None:
        return None

    data_type = (fm.data_type or "TEXT").upper()

    try:
        if data_type in ("TEXT", "VARCHAR", "STRING"):
            return str(value)

        if data_type == "INTEGER":
            str_val = str(value)
            if fm.number_format and "comma_separated" in fm.number_format.lower():
                str_val = str_val.replace(",", "")
            return int(str_val.strip())

        if data_type in ("DECIMAL", "NUMERIC", "FLOAT", "DOUBLE"):
            str_val = str(value)
            if fm.number_format and "comma_separated" in fm.number_format.lower():
                str_val = str_val.replace(",", "")
            return Decimal(str_val.strip())

        if data_type == "BOOLEAN":
            if isinstance(value, bool):
                return value
            str_val = str(value).strip().lower()
            return str_val in ("true", "1", "yes")

        if data_type == "DATE":
            if isinstance(value, date):
                return value
            str_val = str(value).strip()
            if fm.date_format:
                return datetime.strptime(str_val, fm.date_format).date()
            # Try ISO format
            return date.fromisoformat(str_val)

        if data_type in ("TIMESTAMP", "DATETIME"):
            if isinstance(value, datetime):
                dt = value
            else:
                str_val = str(value).strip()
                if fm.date_format:
                    dt = datetime.strptime(str_val, fm.date_format)
                else:
                    # Try ISO format
                    dt = datetime.fromisoformat(str_val.replace("Z", "+00:00"))

            # Convert to UTC
            if fm.source_timezone and dt.tzinfo is None:
                try:
                    tz = ZoneInfo(fm.source_timezone)
                    dt = dt.replace(tzinfo=tz)
                except ZoneInfoNotFoundError:
                    pass
            if dt.tzinfo is not None:
                dt = dt.astimezone(timezone.utc).replace(tzinfo=None)
            return dt

        if data_type == "GEOMETRY":
            return str(value)

        # Default: return as-is
        return value

    except (ValueError, InvalidOperation, TypeError) as e:
        logger.warning(
            "Type conversion failed for field '%s' value=%r type=%s: %s",
            fm.source_field, value, data_type, e,
        )
        return None


# ---------------------------------------------------------------------------
# Pagination helper
# ---------------------------------------------------------------------------

def _has_next_page(
    offset: int,
    page_size: int,
    total_count: Optional[int],
    current_page_rows: int,
) -> bool:
    """Determine whether there is a next page."""
    if current_page_rows < page_size:
        # Partial page — no more data
        return False
    if total_count is not None:
        return offset + page_size < total_count
    # No total count: continue as long as we got a full page
    return True


