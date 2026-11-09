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

import base64
import errno
import re
import socket
from decimal import Decimal
from typing import NamedTuple, Optional
from urllib.parse import quote_plus, unquote_plus, urlsplit

import httpx

from app.services import value_conversion
from app.services.query_key_encoding import encode_query_key, has_invalid_percent

# 자유 문장 안의 URL 에서 쿼리·프래그먼트·userinfo 를 지우는 패턴 — 분류하지 못한 원문을 쓸 때의 마지막 방어선.
_URL_QUERY_IN_TEXT = re.compile(r"(?i)(https?://[^\s?#\"'<>]*)[?#][^\s\"'<>]*")
# userinfo 가 @ 로 여럿 이어져도(「a:x@b:y@host」) 마지막 @ 까지 한 번에 지운다(WD-676) — Java URL_USERINFO_IN_TEXT 와 같다.
_URL_USERINFO_IN_TEXT = re.compile(r"(?i)(https?://)(?:[^\s/@\"'<>]+@)+")


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


def value_conversion_message(row: int, field: str, column: str, value_text: str, dtype: str) -> str:
    """응답 값을 출력 컬럼 타입으로 바꿀 수 없을 때(WD-431 — 예전엔 None 으로 넣고 「완료」였다).

    Java ``ApiCallErrorMessages.valueConversion`` 과 같은 문장이다. ``value_text`` 는 str(값)(자르기 전 — 여기서 자른다), 행 번호는
    응답 데이터 안의 위치(1부터, 페이지를 넘어 이어 센다).
    """
    return (
        f"응답 데이터의 {row}번째 행 '{field}' 필드 값 '{value_conversion.excerpt(value_text)}' 을(를) '{column}' 컬럼의 "
        f"{value_conversion.target(dtype)} 변환할 수 없습니다{value_conversion.hint(dtype)} — 필드 매핑의 타입·형식을 확인하세요"
    )


def invalid_date_format_message(field: str, pattern: str) -> str:
    """필드 매핑의 날짜 형식이 잘못됐을 때(WD-431) — 값 변환 실패와 구분한다. Java ``ApiCallErrorMessages.invalidDateFormat`` 과 같은 문장."""
    return f"'{field}' 필드 매핑의 날짜 형식 '{pattern}' 이(가) 올바르지 않습니다 — 필드 매핑 설정을 확인하세요"


def invalid_timezone_message(field: str, tz: str) -> str:
    """필드 매핑의 원본 시간대가 잘못됐을 때(WD-431 — 예전엔 조용히 무시했다). Java ``ApiCallErrorMessages.invalidTimezone`` 과 같은 문장."""
    return f"'{field}' 필드 매핑의 시간대 '{tz}' 이(가) 올바르지 않습니다 — 필드 매핑 설정을 확인하세요"


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


def describe_exception(
    error: BaseException, timeout_ms: int, host: str | None, secrets: Optional["SentSecrets"] = None
) -> str:
    """예외를 사용자 문구로 바꾼다. 타임아웃을 연결 오류보다 먼저 본다(httpx.ConnectTimeout 은 TimeoutException).

    ``secrets`` 는 이번 요청에 실제로 실은 비밀(쿼리 키·헤더 키·Bearer 토큰·Basic, WD-657·WD-681)과 그 가림 규칙이다. 문구는 실행
    기록·경고 로그로 가므로 마지막에 ``scrub_secret`` 으로 가린다 — Java ``auth.sentSecrets().scrubText(…)`` 와 같다. 예전에는 URL 의
    쿼리만 지워, 분류하지 못한 원문에 헤더 키·토큰이 실려 오면 그대로 남았다. None 이면 가릴 비밀이 없다.
    """
    return scrub_secret(_describe_exception(error, timeout_ms, host), secrets or SentSecrets())


def _describe_exception(error: BaseException, timeout_ms: int, host: str | None) -> str:
    """``describe_exception`` 의 분류 본체 — 비밀 가림 전 문구."""
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
# 비밀 가리기(WD-657) — Java AuthQueryMasker.maskSecret·scrubSecret 과 같은 규칙
# ---------------------------------------------------------------------------

_MASK_PREFIX = "****"

# 자유 문장에서 비밀을 찾아 지울 최소 길이 — 너무 짧은 값을 지우면 문장의 평범한 글자까지 지워진다(Java MIN_SCRUB_LENGTH 와 같다).
_MIN_SCRUB_LENGTH = 4


def mask_secret(value: Optional[str]) -> str:
    """비밀을 ``****`` + 끝 4자로 가린다. 4자 미만이면 ``****`` (Java ``AuthQueryMasker.maskSecret`` 과 같다)."""
    if value is None or len(value) < 4:
        return _MASK_PREFIX
    return _MASK_PREFIX + value[-4:]


def _secret_forms(secret: str) -> list:
    """비밀이 문장에 나타날 수 있는 형태(Java ``AuthQueryMasker.secretForms`` 와 같다) — 실제로 보낸 형태(공용 인코딩), 원문,
    form 인코딩(Java URLEncoder: 영숫자와 ``. - * _`` 만 그대로, 공백은 ``+``), 그 ``+`` 를 ``%20`` 으로 바꾼 것, 디코딩형. 긴 것부터.
    """
    form_encoded = quote_plus(secret, safe="*").replace("~", "%7E")
    candidates = [encode_query_key(secret), form_encoded, form_encoded.replace("+", "%20"), secret]
    # 잘못된 % 시퀀스가 있으면 Java URLDecoder 가 예외를 내 디코딩형을 넣지 않는다 — 같게 맞춘다.
    if not has_invalid_percent(secret):
        candidates.append(unquote_plus(secret))
    forms = []
    for form in candidates:
        if form not in forms:
            forms.append(form)
    # 길이 내림차순(같은 길이는 넣은 순서) — Java 의 안정 정렬과 같은 순서다.
    return sorted(forms, key=len, reverse=True)


# 비밀번호·Basic 덩어리·URL 사용자 정보 비밀번호 자리에 넣는 고정 가림(WD-681 리뷰) — Java ``AuthQueryMasker.PASSWORD_MASK`` 와
# 같다(끝 4자를 보이지 않는다). API 키 가림의 접두와 같은 글자라 한 값을 쓴다.
PASSWORD_MASK = _MASK_PREFIX

# 가린 뒤에도 비밀번호가 글자 그대로 남은 문장(지우기엔 짧은 비밀번호) 대신 쓰는 문구 — Java ``AuthQueryMasker.PASSWORD_WITHHELD`` 와
# 같다. SMTP 실패 문구(WD-676)가 조각을 버리는 것과 같은 규칙이다.
PASSWORD_WITHHELD = "(비밀번호와 같은 글자가 들어 있어 내용을 가렸습니다)"


class SentSecrets(NamedTuple):
    """이번 요청에 실은 비밀과 그 가림 규칙(WD-681 리뷰) — Java ``AuthQueryMasker.SentSecrets`` 와 같다.

    - ``key``(API 키·Bearer 토큰): 예전 그대로 ``****``+끝 4자, 4자 미만이면 손대지 않는다.
    - ``basic_token``(base64(아이디:비밀번호)): 언제나 고정 ``****`` — 끝 4자는 비밀번호 끝 3바이트를 드러낸다.
    - ``password``(Basic 비밀번호): SMTP 비밀번호와 같은 규칙(Java ``AuthQueryMasker.scrubPassword``) — 원문·인코딩형·Base64 형을
      고정 ``****`` 로 지우고, 지운 뒤에도 남으면(4자 미만) 문장을 ``PASSWORD_WITHHELD`` 로 바꾼다.

    공용 사례 표 ``api-call-auth-vectors.json`` 의 ``basicScrub`` 으로 Java 와 같은 결과인지 본다.
    """

    key: Optional[str] = None
    basic_token: Optional[str] = None
    password: Optional[str] = None

    def scrub(self, text: Optional[str]) -> Optional[str]:
        """오류 문구·실행 로그 같은 자유 문장을 가린다(Java ``SentSecrets.scrubText``)."""
        if text is None:
            return None
        out = _scrub_one(text, self.key, None)
        out = _scrub_one(out, self.basic_token, PASSWORD_MASK)
        if not self.password:
            return out
        out = _scrub_one(out, self.password, PASSWORD_MASK)
        # Base64 형(AUTH LOGIN 형태)도 지운다 — Java scrubPassword(SMTP 와 같은 규칙)와 같다.
        out = out.replace(base64.b64encode(self.password.encode("utf-8")).decode("ascii"), PASSWORD_MASK)
        return PASSWORD_WITHHELD if self.password in out else out


def _scrub_one(text: Optional[str], secret: Optional[str], mask: Optional[str]) -> Optional[str]:
    """비밀 하나를 원문·인코딩형·디코딩형 모두 가린다. ``mask`` 가 None 이면 ``mask_secret``(끝 4자) — Java ``scrubSecret`` 두 갈래와 같다."""
    if text is None or secret is None or len(secret) < _MIN_SCRUB_LENGTH:
        return text
    replacement = mask if mask is not None else mask_secret(secret)
    out = text
    for form in _secret_forms(secret):
        if len(form) >= _MIN_SCRUB_LENGTH:
            out = out.replace(form, replacement)
    return out


def scrub_secret(text: Optional[str], secrets: SentSecrets) -> Optional[str]:
    """자유 문장(오류 문구·로그)에서 보낸 비밀을 ``secrets`` 의 규칙으로 가린다(WD-657·WD-681 리뷰).

    API 키·토큰은 Java ``AuthQueryMasker.scrubSecret`` 과 같은 규칙(원문·인코딩형·디코딩형을 ``mask_secret`` 으로, 4자 미만이면 손대지
    않음), Basic 덩어리·비밀번호는 고정 가림(짧은 비밀번호는 문장을 통째로 가림)이다 — 비밀은 늘 ``SentSecrets`` 하나로 받는다.
    """
    return secrets.scrub(text)


def strip_url_userinfo(url: Optional[str]) -> Optional[str]:
    """URL 의 사용자 정보(``아이디:비밀번호@``)를 뗀다(WD-681 리뷰) — Java ``BaseUrlUserInfo.strip`` 과 같은 문자열 해석이다.

    BASIC 인증은 설정한 아이디·비밀번호로 ``Authorization: Basic`` 을 보내는데, URL 에 사용자 정보가 남아 있으면 httpx 가 그것으로 Basic
    인증을 만들어 설정한 헤더를 덮는다. 사용자 정보가 없으면 그대로다.
    """
    parts = _split_userinfo(url)
    if parts is None:
        return url
    prefix, _, host, rest = parts
    return f"{prefix}{host}{rest}"


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


# URL 의 「스킴://」 뒤 authority(첫 / ? # 앞) — 사용자 정보 가림(WD-681)에 쓴다. Java BaseUrlUserInfo 와 같은 문자열 해석이다.
_URL_AUTHORITY = re.compile(r"^([A-Za-z][A-Za-z0-9+.\-]*://)([^/?#]*)(.*)$", re.DOTALL)


def _split_userinfo(url: Optional[str]) -> Optional[tuple]:
    """URL 을 (``스킴://``, 인코딩된 사용자 정보, ``호스트[:포트]``, 나머지)로 나눈다 — Java ``BaseUrlUserInfo.split`` 과 같은 문자열
    해석(authority 안의 마지막 ``@`` 앞이 사용자 정보)이다. URL 모양이 아니거나 사용자 정보가 없으면 None.
    ``strip_url_userinfo``·``mask_url_userinfo`` 가 같은 해석을 쓰도록 한 곳에 둔다.
    """
    if not url:
        return None
    m = _URL_AUTHORITY.match(url)
    if not m:
        return None
    prefix, authority, rest = m.groups()
    user_info, at, host = authority.rpartition("@")
    if not at:
        return None
    return prefix, user_info, host, rest


def mask_url_userinfo(url: Optional[str]) -> Optional[str]:
    """URL 사용자 정보(``아이디:비밀번호@``)의 비밀번호만 ``****`` 로 가린다(WD-681) — Java ``BaseUrlUserInfo.mask`` 와 같다.

    옛 연결(API_KEY·BEARER + baseUrl 사용자 정보)은 이관하지 않아 호출 URL 에 사용자 정보가 남는다. 실행 기록 요약 줄에는 비밀번호를
    남기지 않는다. 사용자 정보가 없거나 비밀번호가 비었으면(``아이디@``·``아이디:@``) 그대로다.
    """
    parts = _split_userinfo(url)
    if parts is None:
        return url
    prefix, user_info, host, rest = parts
    username, colon, password = user_info.partition(":")
    if not colon or not password:
        return url
    return f"{prefix}{username}:{PASSWORD_MASK}@{host}{rest}"


def redact_urls_in_text(text: str) -> str:
    """자유 문장 안에 섞인 URL 의 쿼리·프래그먼트·userinfo 를 지운다."""
    return _URL_USERINFO_IN_TEXT.sub(r"\1", _URL_QUERY_IN_TEXT.sub(r"\1", text or ""))
