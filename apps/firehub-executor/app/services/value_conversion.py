"""파이프라인 출력 값 변환 실패(WD-431)의 공용 규칙 — PYTHON 스텝(``python_executor``)과 API_CALL 스텝(``api_call_executor``)이 함께 쓴다.

예전에는 컬럼 타입으로 바꿀 수 없는 값을 경고 로그만 남기고 None(NULL)으로 넣어 스텝이 「완료」로 끝났다. REPLACE 출력이면 멀쩡한 기존
데이터가 NULL 섞인 데이터로 바뀌었는데 사용자는 알 수 없었다. 이제 변환할 수 없는 값은 스텝을 실패시키고, 어느 행·칸·값·타입이 문제인지
한국어로 짚는다. 불리언도 예전엔 true/1/yes 가 아니면 모두 거짓이었다('Y'·'예' → False) — 데이터 임포트와 같은 허용 값만 받는다.

API 로컬 경로(firehub-api ``ValueConversion``)와 타입 라벨·값 자르기·불리언 허용 값·빈 문자열 규칙·정수 규칙이 같아야 한다. 한쪽을
바꾸면 다른 쪽도 함께 바꾼다(API 의 ``ValueConversion`` 이 기준이다).
"""
from __future__ import annotations

import math
from decimal import Decimal, InvalidOperation
from typing import Any, Optional

# 오류 문구에 인용하는 값의 최대 길이(문자 = 코드 포인트). 넘으면 잘라 말줄임표를 붙인다(사용자 데이터가 길게 실리지 않게).
VALUE_EXCERPT_MAX = 50

# 참·거짓으로 받는 문자열(앞뒤 공백 제거·소문자 기준). 데이터 임포트(api DataValidationService)와 같다.
_TRUE_TOKENS = frozenset({"true", "1", "yes"})
_FALSE_TOKENS = frozenset({"false", "0", "no"})

# 빈 문자열(공백뿐 포함)을 None(NULL)으로 보는 타입(별칭 포함, 대문자) — 데이터 임포트와 같은 규칙. 텍스트·알 수 없는 타입은 그대로 둔다.
BLANK_IS_NULL_TYPES = frozenset(
    {"INTEGER", "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "BOOLEAN", "DATE", "TIMESTAMP", "DATETIME"}
)


class ConversionFailed(ValueError):
    """값 하나를 컬럼 타입으로 바꾸지 못했다 — 행·칸 정보는 호출자가 붙인다(이 예외는 값만 안다)."""


def is_blank_null(value: Any, upper_type: Optional[str]) -> bool:
    """이 타입에서 None(NULL)으로 볼 빈 문자열(공백뿐 포함)인가. ``upper_type`` 은 호출자가 대문자로 바꿔 넘긴다."""
    return isinstance(value, str) and not value.strip() and upper_type in BLANK_IS_NULL_TYPES


def parse_boolean(value: Any) -> Optional[bool]:
    """불리언 해석. bool 은 그대로, 숫자는 1·0 만, 문자열은 true/false/1/0/yes/no(대소문자·앞뒤 공백 무시)만 받는다.

    받을 수 없는 값이면 None(호출자가 변환 실패로 처리한다 — 빈 문자열은 호출자가 먼저 None 으로 거른다).
    """
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float, Decimal)):
        # 1.0 처럼 값이 1·0 과 같으면 받는다. NaN 은 어느 쪽과도 같지 않아 거부된다.
        if value == 1:
            return True
        if value == 0:
            return False
        return None
    if isinstance(value, str):
        token = value.strip().lower()
        if token in _TRUE_TOKENS:
            return True
        if token in _FALSE_TOKENS:
            return False
    return None


def excerpt(text: str) -> str:
    """문구에 넣을 값 — ``VALUE_EXCERPT_MAX`` 자를 넘으면 잘라 「…」을 붙인다."""
    if len(text) <= VALUE_EXCERPT_MAX:
        return text
    return text[:VALUE_EXCERPT_MAX] + "…"


def target(dtype: str) -> str:
    """「… 을(를) **정수(INTEGER)로** 변환할 수 없습니다」의 대상 부분(조사 포함). 별칭은 정식 타입 이름으로 보인다."""
    upper = str(dtype).upper()
    if upper == "INTEGER":
        return "정수(INTEGER)로"
    if upper in ("DECIMAL", "NUMERIC", "FLOAT", "DOUBLE"):
        return "소수(DECIMAL)로"
    if upper == "BOOLEAN":
        return "참/거짓(BOOLEAN)으로"
    if upper == "DATE":
        return "날짜(DATE)로"
    if upper in ("TIMESTAMP", "DATETIME"):
        return "날짜시간(TIMESTAMP)으로"
    return f"{upper} 타입으로"


def hint(dtype: str) -> str:
    """타입별 덧붙임 안내 — 불리언만 허용 값을 알려 준다(예전엔 그 밖의 값이 조용히 거짓이었다)."""
    return "(허용 값: true/false/1/0/yes/no)" if str(dtype).upper() == "BOOLEAN" else ""


def to_boolean_strict(value: Any) -> bool:
    """불리언 엄격 변환 — ``parse_boolean`` 이 받지 않는 값이면 ``ConversionFailed`` (Java ``ValueConversion.toBooleanStrict``)."""
    parsed = parse_boolean(value)
    if parsed is None:
        raise ConversionFailed()
    return parsed


# 64비트 부호 있는 정수 범위 — 데이터셋 INTEGER 의 물리 타입(PG BIGINT·Tibero NUMBER(19))과 Java long 이 같은 범위다.
_INT64_MIN = -(2 ** 63)
_INT64_MAX = 2 ** 63 - 1


def to_int_strict(value: Any) -> int:
    """정수 엄격 변환(64비트 부호 있는 범위) — Java ``ValueConversion.toLongStrict`` 와 같은 규칙.

    정수 값이면 실수 표기도 받는다(3.0·"3.0"·"1e2" → 3·3·100). 소수부가 있거나(1.5) 범위를 넘으면(1e20) ``ConversionFailed`` —
    값을 조용히 잘라 다른 값을 넣지 않는다. bool·dict·list 도 실패다.
    """
    if isinstance(value, bool):
        raise ConversionFailed()
    if isinstance(value, int):
        n = value
    elif isinstance(value, float):
        if not math.isfinite(value) or not value.is_integer():
            raise ConversionFailed()
        n = int(value)
    elif isinstance(value, str):
        text = value.strip()
        try:
            n = int(text)
        except ValueError:
            try:
                n = _decimal_to_int(Decimal(text))
            except InvalidOperation:
                raise ConversionFailed() from None
    elif isinstance(value, Decimal):
        n = _decimal_to_int(value)
    else:
        raise ConversionFailed()
    if n < _INT64_MIN or n > _INT64_MAX:
        raise ConversionFailed()
    return n


def _decimal_to_int(d: Decimal) -> int:
    """정수 값인 유한 Decimal 을 int 로. 지수가 큰 값(1e999999999)을 펼치지 않도록 자릿수부터 본다 — 19자리를 넘으면 어차피 범위 밖이다."""
    if not d.is_finite() or d.adjusted() > 18 or d != d.to_integral_value():
        raise ConversionFailed()
    return int(d)
