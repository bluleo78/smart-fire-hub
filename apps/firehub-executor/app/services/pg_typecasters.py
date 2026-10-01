"""psycopg2 타입 캐스터 보강 — 한 값의 파싱 실패가 쿼리 전체를 실패시키지 않게 한다(#762).

왜 필요한가:
- psycopg2(2.9) 의 C 배열 캐스터는 하한이 지정된 **다차원** 배열 텍스트(``[0:1][0:1]={{1,2},{3,4}}``)의
  차원 장식을 걷어내지 못해 ``array does not start with '{'`` (DataError) 를 던진다(1차원 ``[2:3]=`` 는 처리).
  이 예외는 ``cursor.fetchall()`` 에서 나므로 그 값 하나 때문에 쿼리 전체가 실패한다.
- 범위를 벗어난 날짜/시각(``10000-01-01``, ``BC``)·거대한 interval 도 Python 객체로 바꿀 수 없어
  ``ValueError``/``OverflowError`` 로 같은 방식으로 쿼리 전체를 실패시킨다.

계약(API 직접 경로 ``AdhocMultiDimArrays`` 와 같다):
- 하한 장식은 버리고 중첩 리스트로 준다(직접 경로도 하한을 버린다).
- 그래도 변환할 수 없는 값은 **그 값만** PG 리터럴 텍스트(장식 포함 원문)로 폴백한다.

두 번째 책임(#763) — **JSON 으로 표현할 수 없는 Python 객체를 만드는 캐스터는 PG 원문 텍스트로 둔다**:
- psycopg2 는 range 타입(int4range·int8range·numrange·daterange·tsrange·tstzrange)을 ``psycopg2._range.Range``
  객체로 바꾸는데, 이 객체는 응답 직렬화(pydantic)가 다룰 수 없어 쿼리 전체가 500 이 된다. 파싱은 성공하므로
  "파싱 실패 폴백" 이 아니라 "애초에 객체로 바꾸지 않는다" 이다. 직렬화 계층에서 객체를 다시 텍스트로 조립하면
  numrange 의 ``1E-7``·tstzrange 의 따옴표·시간대 표기를 PG 와 똑같이 재현해야 하므로, 정확한 원문은 캐스터에서만
  얻을 수 있다. (multirange 는 psycopg2 가 모르는 타입이라 이미 텍스트다 — 같은 형태.)
- bytea 는 여기서 다루지 않는다 — ``memoryview`` 는 파싱 결과로 정상이고, 응답에서 Base64 로 바꾸는 것은
  JSON 표현의 결정이므로 직렬화 계층(``app/schemas/responses.py``)이 맡는다.

등록은 프로세스 전역(``register_type`` 의 전역 형태)이다 — 애드혹 분석(query_executor)과 파이프라인
SQL 스텝 SELECT(sql_executor) 가 같은 연결 풀을 쓰므로 두 경로가 함께 고쳐진다. 커넥션 단위 등록이 없으므로
(앱 어디에서도 캐스터를 등록하지 않는다) 전역 등록이 가려지지 않는다.
"""

from __future__ import annotations

import logging
import re
import threading
from typing import Any, Callable, Optional

import psycopg2.extensions as ext

logger = logging.getLogger(__name__)

# 배열 텍스트 앞의 차원 장식: ``[하한:상한]`` 이 1개 이상 이어지고 ``=`` 로 끝난다(음수 하한 허용).
_BOUNDS_PREFIX = re.compile(r"^(?:\[-?\d+:-?\d+\])+=")

# 범위 초과로 Python 변환이 실패할 수 있는 스칼라 타입 OID — date, timestamp, timestamptz, interval.
# 스칼라는 값마다 Python 호출이 끼므로 실제로 실패가 관측된 타입만 감싼다(전 타입 래핑은 하지 않는다).
_FALLBACK_SCALAR_OIDS = (1082, 1114, 1184, 1186)

# 원문 텍스트로 둘 range 타입: (스칼라 OID, 배열 OID, 이름). 이름은 psycopg2 기본 등록과 같게 둔다.
_TEXT_RANGE_TYPES = (
    (3904, 3905, "int4range"),
    (3926, 3927, "int8range"),
    (3906, 3907, "numrange"),
    (3912, 3913, "daterange"),
    (3908, 3909, "tsrange"),
    (3910, 3911, "tstzrange"),
)

_installed = False
_install_lock = threading.Lock()


def make_array_caster(orig: Callable[[Optional[str], Any], Any]) -> Callable[[Optional[str], Any], Any]:
    """원래 배열 캐스터를 감싸 하한 장식을 걷어내고, 실패하면 원문 텍스트로 폴백하는 함수를 만든다.

    장식을 걷어도 의미가 바뀌지 않는 이유: 장식은 첨자 시작값일 뿐이고 원소·중첩 구조는 ``=`` 뒤에 그대로
    있다. 직접 경로도 하한을 버린 중첩 리스트를 준다.
    """

    def cast(value: Optional[str], cur: Any) -> Any:
        if value is None:
            return None
        body = value
        m = _BOUNDS_PREFIX.match(value)
        if m:
            body = value[m.end():]
        try:
            return orig(body, cur)
        except Exception as exc:  # noqa: BLE001 — 어떤 파싱 실패든 값 단위 폴백이 계약이다
            logger.debug("array typecast failed, falling back to literal text: %s", exc)
            return value

    return cast


def make_fallback_caster(orig: Callable[[Optional[str], Any], Any]) -> Callable[[Optional[str], Any], Any]:
    """원래 스칼라 캐스터를 감싸 변환 실패(범위 초과 등) 시 원문 텍스트로 폴백하는 함수를 만든다."""

    def cast(value: Optional[str], cur: Any) -> Any:
        if value is None:
            return None
        try:
            return orig(value, cur)
        except Exception as exc:  # noqa: BLE001 — 값 단위 폴백이 계약이다
            logger.debug("scalar typecast failed, falling back to literal text: %s", exc)
            return value

    return cast


def _keep_text(value: Optional[str], cur: Any) -> Optional[str]:
    """PG 가 보낸 텍스트를 그대로 돌려준다(``'[1,5)'``·``'empty'``)."""
    return value


def _register_text_ranges() -> None:
    """range 스칼라·배열 캐스터를 원문 텍스트 캐스터로 바꾼다.

    배열도 다시 등록해야 하는 이유: psycopg2 의 기존 range 배열 캐스터는 Range 를 만드는 원래 스칼라 캐스터에
    묶여 있어 스칼라만 바꾸면 배열 원소는 여전히 Range 객체가 된다. 새 배열 타입 이름도 ``ARRAY`` 로 끝나므로
    뒤이은 배열 래핑 루프가 하한 장식 걷기·값 단위 폴백을 똑같이 입힌다.
    """
    for oid, array_oid, name in _TEXT_RANGE_TYPES:
        scalar = ext.new_type((oid,), name.upper(), _keep_text)
        ext.register_type(scalar)
        ext.register_type(ext.new_array_type((array_oid,), f"{name.upper()}ARRAY", scalar))


def install_safe_typecasters() -> None:
    """psycopg2 전역 캐스터를 보강한다. 여러 번 불려도 한 번만 감싼다(이중 래핑 방지)."""
    global _installed
    with _install_lock:
        if _installed:
            return
        # range 를 먼저 텍스트 캐스터로 바꿔야 아래 루프가 새 range 배열 캐스터를 감싼다.
        _register_text_ranges()
        # 등록 중 string_types 가 바뀌므로 스냅샷을 순회한다.
        for oid, orig in list(ext.string_types.items()):
            name = orig.name
            if name.endswith("ARRAY"):
                ext.register_type(ext.new_type((oid,), name, make_array_caster(orig)))
            elif oid in _FALLBACK_SCALAR_OIDS:
                ext.register_type(ext.new_type((oid,), name, make_fallback_caster(orig)))
        _installed = True
