"""API 호출(API_CALL 스텝) 실패를 분류해 사용자에게 보일 한국어 문구를 만든다(WD-150).

왜 필요한가: 예전에는 ``All N attempts failed. Last error: [Errno 111] Connection refused`` 처럼 영어 원문이
실행 상세에 그대로 저장됐고, 오류 본문 일부(``resp.text[:200]``)까지 붙었다. 이 모듈이 상황별 문구를 한 곳에서 만든다.

api 쪽 Java ``ApiCallErrorMessages`` (firehub-api ``pipeline/service/executor``)와 **같은 문장**을 쓴다 — 실행기 켠
경로(이 모듈)와 끈 경로·「테스트 호출」(Java)이 같은 사용자 화면에 나오기 때문이다. 문구를 바꾸면 양쪽을 함께 고칠 것
(양쪽 테스트가 같은 문자열을 단언한다).

비밀값: API_KEY 를 쿼리 문자열로 보내는 인증 방식이 있어, 문구에 URL 을 넣을 때는 항상 ``redact_url`` 로
「스킴://호스트[:포트]/경로」까지만 남긴다.
"""

from __future__ import annotations

import errno
import re
import socket
from decimal import Decimal
from urllib.parse import urlsplit

import httpx

# 자유 문장 안의 URL 에서 쿼리·프래그먼트·userinfo 를 지우는 패턴 — 분류하지 못한 원문을 쓸 때의 마지막 방어선.
_URL_QUERY_IN_TEXT = re.compile(r"(?i)(https?://[^\s?#\"'<>]*)[?#][^\s\"'<>]*")
_URL_USERINFO_IN_TEXT = re.compile(r"(?i)(https?://)[^\s/@\"'<>]+@")


class RetryableApiCallError(Exception):
    """재시도할 실패(5xx·408·429 응답). 메시지는 이미 사용자 문구다."""


# ---------------------------------------------------------------------------
# HTTP 상태
# ---------------------------------------------------------------------------

def is_retryable_status(status: int) -> bool:
    """5xx 와 408·429 만 재시도한다. 그 밖의 4xx 는 다시 보내도 같은 답이라 즉시 실패시킨다."""
    return status >= 500 or status in (408, 429)


_STATUS_MESSAGES = {
    400: "요청이 거부되었습니다(HTTP 400) — 요청 파라미터·본문 형식을 확인하세요",
    401: "인증에 실패했습니다(HTTP 401) — API 키·토큰 설정을 확인하세요",
    403: "접근이 거부되었습니다(HTTP 403) — API 키 권한이나 허용 IP 설정을 확인하세요",
    404: "요청한 주소를 찾을 수 없습니다(HTTP 404) — URL 경로를 확인하세요",
    405: "허용되지 않는 요청 방식입니다(HTTP 405) — GET/POST 설정을 확인하세요",
    408: "대상 서버가 요청 시간 초과로 응답했습니다(HTTP 408)",
    429: "요청이 너무 많아 대상 서버가 거부했습니다(HTTP 429) — 호출 빈도를 줄이거나 잠시 후 다시 시도하세요",
}


def http_status_message(status: int) -> str:
    """HTTP 오류 상태별 짧은 안내. 응답 본문은 담지 않는다."""
    if status in _STATUS_MESSAGES:
        return _STATUS_MESSAGES[status]
    if status >= 500:
        return f"대상 서버 오류(HTTP {status})"
    if status >= 400:
        return f"요청이 거부되었습니다(HTTP {status})"
    return f"예상하지 못한 응답입니다(HTTP {status})"


# ---------------------------------------------------------------------------
# 전송 오류·한도
# ---------------------------------------------------------------------------

def _seconds(ms: int) -> str:
    """밀리초를 「30」「0.3」「1.5」 같은 초 표기로 — Java BigDecimal.stripTrailingZeros 와 같은 결과."""
    text = format(Decimal(int(ms)).scaleb(-3).normalize(), "f")
    return text


def timeout_message(timeout_ms: int) -> str:
    return f"응답 시간 초과({_seconds(timeout_ms)}초) — 대상 서버 상태나 시간 제한을 확인하세요"


def connection_refused_message() -> str:
    return "연결이 거부되었습니다 — 주소·포트와 서버 실행 여부를 확인하세요"


def connection_failed_message() -> str:
    return "대상 서버에 연결하지 못했습니다 — 주소·포트와 네트워크 상태를 확인하세요"


def unknown_host_message(host: str) -> str:
    return f"호스트 이름을 찾을 수 없습니다: {host}"


def response_too_large_message(max_mb: int) -> str:
    return f"응답 크기가 제한({max_mb}MB)을 넘었습니다 — 응답 크기 제한을 늘리거나 페이지 크기를 줄이세요"


def too_many_redirects_message(max_redirects: int) -> str:
    return f"리다이렉트가 너무 많습니다(최대 {max_redirects}회)"


def redirect_without_location_message() -> str:
    return "리다이렉트 응답에 이동할 주소(Location)가 없습니다"


def not_json_message() -> str:
    return "응답이 JSON 형식이 아닙니다 — 대상 API 의 응답 형식을 확인하세요"


# ---------------------------------------------------------------------------
# 데이터 경로(JSONPath)·적재·예기치 못한 오류(WD-212)
# ---------------------------------------------------------------------------

def data_path_not_found_message(data_path: str) -> str:
    """응답에 데이터 경로가 없을 때(Java ``ApiCallErrorMessages.dataPathNotFound`` 와 같은 문장).

    예전 실행기는 이 경우 ``[]`` 를 돌려 「완료·0행」으로 끝났고, REPLACE 출력이면 옛 데이터가 남아 갱신이 멈춘 것을
    사용자가 알 수 없었다. Java 경로는 같은 상황을 실패로 알린다 — 같은 규칙·같은 문장으로 맞춘다.
    """
    return f"응답에서 데이터 경로를 찾을 수 없습니다: {data_path}"


def data_path_invalid_message(data_path: str) -> str:
    """데이터 경로가 JSONPath 문법에 맞지 않을 때(Java ``ApiCallErrorMessages.dataPathInvalid`` 와 같은 문장).

    파서 원문(영어, 라이브러리마다 다름)은 싣지 않는다 — Java(jayway)와 Python(jsonpath_ng)의 원문이 달라 두 경로 문구가 갈린다.
    """
    return f"데이터 경로(JSONPath) 형식이 올바르지 않습니다: {data_path} — 예: $.data.items"


def load_failed_message() -> str:
    """API 응답을 출력 테이블에 넣다가 DB 오류가 났을 때(psycopg2 오류).

    원문(내부 테이블 이름·SQL·값)은 로그에만 남긴다. Java 로컬 경로(ApiCallExecutor)에는 아직 대응 문구가 없어(적재 예외 원문이
    그대로 나간다) 이 문장은 실행기 전용이다 — Java 에 같은 분류를 넣게 되면 이 문장을 그대로 쓸 것.
    """
    return "API 응답을 출력 데이터셋에 적재하지 못했습니다 — 필드 매핑과 출력 데이터셋 컬럼 형식을 확인하세요"


def unexpected_error_message() -> str:
    """분류하지 못한 실행기 내부 예외. 원문은 로그에만 남긴다(load_failed_message 와 같은 이유로 실행기 전용 문장)."""
    return "API 호출 스텝 실행 중 예기치 못한 오류가 발생했습니다 — 잠시 후 다시 실행하고, 계속되면 관리자에게 문의하세요"


def retries_exhausted_message(retries: int, last_error: str) -> str:
    """재시도를 다 쓴 뒤의 문구 — 마지막 원인을 반드시 담는다. 재시도 0회면 「API 호출에 실패했습니다: 원인」."""
    if retries <= 0:
        return f"API 호출에 실패했습니다: {last_error}"
    return f"API 호출이 {retries}회 재시도 후에도 실패했습니다 — 마지막 오류: {last_error}"


# ---------------------------------------------------------------------------
# 예외 분류
# ---------------------------------------------------------------------------

def _chain(error: BaseException):
    seen = set()
    current: BaseException | None = error
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        yield current
        current = current.__cause__ or current.__context__


def describe_exception(error: BaseException, timeout_ms: int, host: str | None) -> str:
    """예외를 사용자 문구로 바꾼다. 타임아웃을 연결 오류보다 먼저 본다(httpx.ConnectTimeout 은 TimeoutException)."""
    if isinstance(error, RetryableApiCallError):
        return str(error)
    for c in _chain(error):
        if isinstance(c, (httpx.TimeoutException, socket.timeout, TimeoutError)):
            return timeout_message(timeout_ms)
    for c in _chain(error):
        if isinstance(c, socket.gaierror):
            return unknown_host_message(host or "알 수 없음")
        if isinstance(c, ConnectionRefusedError) or getattr(c, "errno", None) == errno.ECONNREFUSED:
            return connection_refused_message()
    if isinstance(error, httpx.ConnectError):
        text = str(error).lower()
        if "refused" in text:
            return connection_refused_message()
        if "name or service not known" in text or "nodename nor servname" in text:
            return unknown_host_message(host or "알 수 없음")
        return connection_failed_message()
    if isinstance(error, (httpx.TransportError, OSError)):
        return connection_failed_message()
    raw = str(error) or type(error).__name__
    return f"API 호출 중 오류가 발생했습니다: {redact_urls_in_text(raw)}"


# ---------------------------------------------------------------------------
# URL 가리기
# ---------------------------------------------------------------------------

def redact_url(url: str | None) -> str:
    """URL 을 「스킴://호스트[:포트]/경로」로 줄인다 — userinfo·쿼리·프래그먼트를 버린다. 해석 못 하면 첫 ?·# 앞까지."""
    if not url:
        return ""
    try:
        parts = urlsplit(url)
        host = parts.hostname
        if parts.scheme and host:
            if ":" in host:  # IPv6 리터럴은 대괄호를 되돌린다
                host = f"[{host}]"
            port = f":{parts.port}" if parts.port is not None else ""
            return f"{parts.scheme}://{host}{port}{parts.path}"
    except ValueError:
        pass
    head = re.split(r"[?#]", url, maxsplit=1)[0]
    return _URL_USERINFO_IN_TEXT.sub(r"\1", head)


def redact_urls_in_text(text: str) -> str:
    """자유 문장 안에 섞인 URL 의 쿼리·프래그먼트·userinfo 를 지운다."""
    return _URL_USERINFO_IN_TEXT.sub(r"\1", _URL_QUERY_IN_TEXT.sub(r"\1", text or ""))
