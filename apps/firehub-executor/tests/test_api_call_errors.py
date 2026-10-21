"""WD-150: API 호출 오류 문구 헬퍼.

문자열은 api 쪽 Java ``ApiCallErrorMessages`` 와 같아야 한다 — 같은 문장을 ``ApiCallErrorMessagesTest`` 가 단언한다
(한쪽만 바꾸면 이 테스트나 그쪽이 깨진다).
"""
from __future__ import annotations

import socket

import httpx
import pytest

from app.services import api_call_errors as errors


@pytest.mark.parametrize(
    "status, expected",
    [
        (400, False), (401, False), (403, False), (404, False), (405, False), (409, False), (422, False),
        (408, True), (429, True), (500, True), (502, True), (503, True), (504, True),
    ],
)
def test_is_retryable_status(status, expected):
    assert errors.is_retryable_status(status) is expected


def test_shared_literals_match_java():
    assert errors.http_status_message(401) == "인증에 실패했습니다(HTTP 401) — API 키·토큰 설정을 확인하세요"
    assert errors.http_status_message(405) == "허용되지 않는 요청 방식입니다(HTTP 405) — GET/POST 설정을 확인하세요"
    assert errors.http_status_message(418) == "요청이 거부되었습니다(HTTP 418)"
    assert errors.http_status_message(503) == "대상 서버 오류(HTTP 503)"
    assert errors.timeout_message(30_000) == "응답 시간 초과(30초) — 대상 서버 상태나 시간 제한을 확인하세요"
    assert errors.retries_exhausted_message(3, errors.connection_refused_message()) == (
        "API 호출이 3회 재시도 후에도 실패했습니다 — 마지막 오류: 연결이 거부되었습니다 — 주소·포트와 서버 실행 여부를 확인하세요"
    )
    assert errors.retries_exhausted_message(0, "대상 서버 오류(HTTP 500)") == (
        "API 호출에 실패했습니다: 대상 서버 오류(HTTP 500)"
    )
    assert errors.response_too_large_message(10) == (
        "응답 크기가 제한(10MB)을 넘었습니다 — 응답 크기 제한을 늘리거나 페이지 크기를 줄이세요"
    )
    assert errors.too_many_redirects_message(5) == "리다이렉트가 너무 많습니다(최대 5회)"


@pytest.mark.parametrize("ms, expected", [(30000, "30"), (300, "0.3"), (1500, "1.5"), (1000, "1"), (50, "0.05")])
def test_seconds_format(ms, expected):
    assert errors._seconds(ms) == expected


@pytest.mark.parametrize(
    "url, expected",
    [
        ("https://api.example.com/v1/data?serviceKey=SECRET123", "https://api.example.com/v1/data"),
        ("http://user:pass@host.example:8080/p?x=1#frag", "http://host.example:8080/p"),
        ("https://api.example.com", "https://api.example.com"),
        ("example.com/x?k=SECRET123", "example.com/x"),
    ],
)
def test_redact_url(url, expected):
    assert errors.redact_url(url) == expected


def test_describe_exception_classification():
    assert errors.describe_exception(httpx.ReadTimeout("timed out"), 2000, "h") == errors.timeout_message(2000)
    # httpx.ConnectTimeout 은 TimeoutException — 「연결 거부」가 아니라 타임아웃으로 보인다
    assert errors.describe_exception(httpx.ConnectTimeout("timed out"), 1000, "h") == errors.timeout_message(1000)
    assert errors.describe_exception(
        httpx.ConnectError("[Errno 111] Connection refused"), 1000, "h"
    ) == errors.connection_refused_message()

    dns = httpx.ConnectError("[Errno 8] nodename nor servname provided, or not known")
    assert errors.describe_exception(dns, 1000, "nope.invalid") == "호스트 이름을 찾을 수 없습니다: nope.invalid"

    try:
        try:
            raise socket.gaierror(-2, "Name or service not known")
        except socket.gaierror as inner:
            raise httpx.ConnectError("boom") from inner
    except httpx.ConnectError as wrapped:
        assert errors.describe_exception(wrapped, 1000, "x.invalid") == "호스트 이름을 찾을 수 없습니다: x.invalid"

    other = RuntimeError("bad thing at https://a.example/p?serviceKey=SECRET123 now")
    assert errors.describe_exception(other, 1000, "h") == "API 호출 중 오류가 발생했습니다: bad thing at https://a.example/p now"
