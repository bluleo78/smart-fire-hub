"""쿼리 파라미터로 보내는 API 키(API_KEY + placement=query)를 URL 쿼리에 실을 형태로 바꾸는 규칙(WD-182).

**왜 필요한가.** 공공데이터포털은 같은 키를 인코딩 키(``abc%2Bd%3D%3D``)와 디코딩 키(``abc+d==``) 두 형태로
준다. 예전에는 이 실행기가 키를 httpx ``params=`` 로 넘겨 httpx 가 다시 인코딩했고, 인코딩 키의 ``%2B`` 가
``%252B`` 로 이중 인코딩됐다. api(Java)의 연결 테스트·상태 점검·미리보기·실행기는 ``QueryApiKeyEncoding`` 한 곳의
규칙을 쓰고, 이 모듈은 같은 규칙을 Python 으로 구현한다. 두 구현은 공용 사례 표
``apps/firehub-api/src/test/resources/fixtures/query-api-key-encoding-vectors.json`` 로 함께 검증된다 —
한쪽만 바꾸면 어느 한쪽 테스트가 깨진다.

**규칙(방안 C, 자동 판별).**

- 값에 유효한 퍼센트 인코딩(``%[0-9A-Fa-f]{2}``)이 있으면 이미 인코딩된 키로 본다. 그 ``%XX`` 는 바이트 그대로
  (대소문자 포함) 보존한다.
- 나머지 글자는 쿼리 컴포넌트 기준으로 한 번 인코딩한다 — RFC 3986 비예약 문자(``A-Z a-z 0-9 - . _ ~``)만 그대로
  두고 그 밖은 UTF-8 바이트별 ``%XX``(대문자 hex). 공백은 ``%20``, ``+`` 는 ``%2B``, ``=`` 는 ``%3D``, ``/`` 는
  ``%2F``. ``&``·``#`` 도 인코딩해 파라미터 주입·URL 잘림을 막는다.
- 유효하지 않은 ``%``(뒤에 hex 두 자리가 아님)는 원문 ``%`` 로 보고 ``%25`` 로 인코딩한다.

섞인 키(``%2B`` 와 생 ``=``)는 이미 인코딩된 것으로 보되 ``%XX`` 는 보존하고 나머지만 인코딩한다(어느 쪽으로 해석해도
URL 이 유효하게 남는 쪽). 진짜 ``%`` 뒤에 hex 두 자리가 오는 디코딩 키는 잘못 판정되는데, 공공 키에서는 드물어 받아들였다.
"""
from __future__ import annotations

import re
from typing import Optional
from urllib.parse import quote

# 유효한 퍼센트 인코딩 한 조각 — 이것이 하나라도 있으면 「이미 인코딩됨」이다.
_PERCENT_ESCAPE = re.compile(r"%[0-9A-Fa-f]{2}")


def is_pre_encoded(value: Optional[str]) -> bool:
    """값에 유효한 퍼센트 인코딩(``%XX``)이 하나라도 있어 「이미 인코딩된 키」로 보는지. None 은 False."""
    return value is not None and _PERCENT_ESCAPE.search(value) is not None


def encode_query_key(value: Optional[str]) -> Optional[str]:
    """키를 쿼리에 실을 형태로 바꾼다(모듈 설명의 규칙). None 이면 None.

    유효한 ``%XX`` 조각 사이의 나머지 글자만 ``quote(safe="")`` 로 인코딩한다 — ``quote`` 는 영숫자와 ``_.-~`` 만
    그대로 두고 ``%`` 를 포함한 나머지를 UTF-8 ``%XX``(대문자)로 바꾸므로 Java ``QueryApiKeyEncoding.encode`` 와 같은
    결과다. 조각 사이에 남은 ``%`` 는 유효한 조각이 아니므로(정규식이 이미 떼어 냈다) ``%25`` 가 된다.
    """
    if value is None:
        return None
    out = []
    pos = 0
    for m in _PERCENT_ESCAPE.finditer(value):
        out.append(quote(value[pos : m.start()], safe=""))
        # 이미 인코딩된 조각은 대소문자까지 그대로 — 같은 바이트를 보내야 서버가 같은 키로 읽는다.
        out.append(m.group(0))
        pos = m.end()
    out.append(quote(value[pos:], safe=""))
    return "".join(out)
