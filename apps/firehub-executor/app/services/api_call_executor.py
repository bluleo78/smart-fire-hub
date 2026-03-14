from __future__ import annotations

import logging
import random
import time
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Any, Dict, List, Optional
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import httpx
from jsonpath_ng import parse as jsonpath_parse
from psycopg2 import sql as pgsql
from psycopg2.extras import execute_values

from app.schemas.requests import ApiCallExecuteRequest, FieldMapping
from app.schemas.responses import ApiCallExecuteResponse
from app.validators.ssrf_protection import SsrfException, validate_url

logger = logging.getLogger(__name__)

MAX_REDIRECTS = 5
DEFAULT_PAGE_SIZE = 100
MAX_PAGES = 10_000

# Non-retryable HTTP status codes
NON_RETRYABLE_STATUS = {400, 401, 403, 404}


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
                    _insert_batch(conn, request.output_table, rows)
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
                _insert_batch(conn, request.output_table, rows)
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
        logger.exception("Unexpected error in execute_api_call")
        return ApiCallExecuteResponse(
            success=False, error=str(e), execution_time_ms=elapsed_ms
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
        except ApiCallException:
            # Non-retryable — propagate immediately
            raise
        except Exception as e:
            last_error = e
            attempt += 1
            if attempt > max_retries:
                break
            backoff_ms = min(initial_backoff_ms * (2 ** (attempt - 1)), max_backoff_ms)
            # Add jitter: ±25%
            jitter_ms = backoff_ms * 0.25 * (random.random() * 2 - 1)
            sleep_sec = max(0.0, (backoff_ms + jitter_ms) / 1000.0)
            logger.warning(
                "Attempt %d/%d failed (%s). Retrying in %.2fs",
                attempt, max_retries, e, sleep_sec,
            )
            time.sleep(sleep_sec)

    raise ApiCallException(f"All {max_retries + 1} attempts failed. Last error: {last_error}")


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
    _apply_auth(auth, req_headers, req_params)

    current_url = url
    redirect_count = 0

    with httpx.Client(follow_redirects=False, timeout=timeout_sec) as client:
        while True:
            resp = client.request(
                method=method,
                url=current_url,
                headers=req_headers,
                params=req_params if redirect_count == 0 else {},
                content=body.encode() if body else None,
            )

            if resp.status_code in (301, 302, 303, 307, 308):
                redirect_count += 1
                if redirect_count > MAX_REDIRECTS:
                    raise ApiCallException(
                        f"Too many redirects (max {MAX_REDIRECTS})"
                    )
                location = resp.headers.get("location")
                if not location:
                    raise ApiCallException("Redirect response missing Location header")
                # SSRF check on redirect target
                validate_url(location)
                current_url = location
                # For 303, switch to GET
                if resp.status_code == 303:
                    method = "GET"
                    body = None
                continue

            # Check for non-retryable client errors
            if resp.status_code in NON_RETRYABLE_STATUS:
                raise ApiCallException(
                    f"Non-retryable HTTP error {resp.status_code}: {resp.text[:200]}"
                )

            # Raise for other HTTP errors (retryable 5xx etc.)
            if resp.status_code >= 400:
                raise Exception(
                    f"HTTP error {resp.status_code}: {resp.text[:200]}"
                )

            # Check response size
            content_length = resp.headers.get("content-length")
            if content_length and int(content_length) > max_bytes:
                raise ApiCallException(
                    f"Response size {content_length} bytes exceeds limit {max_bytes} bytes"
                )

            content = resp.content
            if len(content) > max_bytes:
                raise ApiCallException(
                    f"Response body {len(content)} bytes exceeds limit {max_bytes} bytes"
                )

            return resp.json()


def _apply_auth(
    auth: Optional[dict],
    headers: Dict[str, str],
    params: Dict[str, str],
) -> None:
    """Apply authentication to request headers or query params.

    Supports both Java decryptedAuth format (authType, headerName, apiKey,
    placement, paramName) and generic format (type, key_name, key_value, location).
    """
    if not auth:
        return
    auth_type = (auth.get("authType") or auth.get("type") or "").upper()
    if auth_type == "BEARER":
        token = auth.get("token") or auth.get("value") or ""
        headers["Authorization"] = f"Bearer {token}"
    elif auth_type == "API_KEY":
        placement = (auth.get("placement") or auth.get("location") or "header").upper()
        if placement == "QUERY":
            param_name = auth.get("paramName") or auth.get("key_name") or "api_key"
            api_key = auth.get("apiKey") or auth.get("key_value") or auth.get("value") or ""
            params[param_name] = api_key
        else:
            header_name = auth.get("headerName") or auth.get("key_name") or "x-api-key"
            api_key = auth.get("apiKey") or auth.get("key_value") or auth.get("value") or ""
            headers[header_name] = api_key


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
    """Use jsonpath_ng to extract list of items at data_path."""
    if not data_path or data_path.strip() in ("$", ""):
        if isinstance(response_body, list):
            return response_body
        return [response_body]

    try:
        expr = jsonpath_parse(data_path)
        matches = expr.find(response_body)
        if not matches:
            return []
        # If the matched value is a list, return it; otherwise wrap
        value = matches[0].value
        if isinstance(value, list):
            return value
        return [value]
    except Exception as e:
        logger.warning("jsonpath parse/find error for path '%s': %s", data_path, e)
        return []


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


# ---------------------------------------------------------------------------
# DB insert
# ---------------------------------------------------------------------------

def _insert_batch(conn, table_name: str, rows: List[Dict[str, Any]]) -> None:
    """Batch-insert rows into target table using execute_values."""
    if not rows:
        return

    columns = list(rows[0].keys())
    col_identifiers = [pgsql.Identifier(c) for c in columns]

    query = pgsql.SQL("INSERT INTO {table} ({cols}) VALUES %s").format(
        table=pgsql.Identifier(table_name),
        cols=pgsql.SQL(", ").join(col_identifiers),
    )

    values = [tuple(row[c] for c in columns) for row in rows]

    with conn.cursor() as cur:
        execute_values(cur, query.as_string(cur), values)
