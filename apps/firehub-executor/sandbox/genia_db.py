"""Tibero 저장소 테넌트의 Python 스크립트용 조회 도우미(WD-78, 스펙 §5.2).

샌드박스에는 Tibero 드라이버가 없다. 스크립트는 이 모듈로 입력 테이블을 읽는다 — db-executor ``POST /execute/query`` 에
실행 1회용 스크립트 토큰(``Authorization: Script <token>``)과 슬롯 번호를 실어 부르면, db-executor 가 실행 주체의 **슬롯 계정**으로
문장을 실행하고 결과를 NDJSON 으로 끝까지 흘려보낸다. 볼 수 없는 테이블은 DB 권한이 거부한다.

사용 예::

    import genia_db
    rows = genia_db.query("SELECT ID, NAME FROM ORDERS")      # 전체를 list[dict] 로
    for row in genia_db.iter_query("SELECT * FROM BIG_TABLE"):  # 한 행씩(메모리 절약)
        ...

규칙
- 표준 라이브러리만 쓴다(``urllib.request``, ``json``) — 샌드박스 패키지 구성에 기대지 않는다.
- 설정은 환경 변수 ``GENIA_DB_URL``(db-executor 주소)·``GENIA_DB_TOKEN``(스크립트 토큰) 둘뿐이다. 테넌트·슬롯은 토큰
  ``v1.<tenantId>.<slot>.<epoch>.<exp>.<sig>`` 의 앞 필드에서 꺼내 요청 본문에 싣는다(db-executor 는 토큰 값이 이기며 본문이 다르면 거부한다).
- 결과를 **조용히 자르지 않는다.** 마지막 줄이 성공(``{"success": true, "row_count": N}``)이 아니거나, 받은 행 수가 N 과 다르거나,
  스트림이 중간에 끊기면 ``QueryError`` 다. 실패 줄의 메시지는 그 줄의 ``error`` 그대로다.
- 토큰·주소는 어떤 예외 메시지에도 싣지 않는다(실행 로그에 남으므로). 내부 예외는 체인을 끊어(``from None``) 원문이 새지 않게 한다.
"""
from __future__ import annotations

import http.client
import json
import os
import urllib.error
import urllib.request
from typing import Any, Dict, Iterator, List, Optional, Tuple

__all__ = ["QueryError", "query", "iter_query"]

_ENV_URL = "GENIA_DB_URL"
_ENV_TOKEN = "GENIA_DB_TOKEN"

# 소켓 읽기 한 번의 상한(초). 큰 정렬은 첫 행까지 오래 걸릴 수 있어 넉넉히 두되, 서버가 멈춘 채 연결만 살아 있으면 스크립트가 영원히
# 기다리지 않게 한다. 전체 시간은 단계 시간 제한과 토큰 만료가 따로 묶는다.
_READ_TIMEOUT_SECONDS = 600

_MISSING_CONFIG = "이 실행 환경에는 조회 도우미 설정이 없습니다."
_BAD_TOKEN = "조회 도우미의 실행 토큰 형식이 올바르지 않습니다."
_UNAUTHORIZED = "조회 요청이 인증되지 않았습니다 — 실행 토큰이 만료됐거나 보안 등급 구성이 바뀌었습니다. 단계를 다시 실행하세요."
_CONNECT_FAILED = "조회 서버(db-executor)에 연결할 수 없습니다."
_CUT_OFF = "조회 결과를 끝까지 받지 못했습니다(연결이 중간에 끊겼습니다)."
_BAD_FORMAT = "조회 결과 형식이 올바르지 않습니다."


class QueryError(Exception):
    """조회 실패. 메시지는 사용자가 읽는 한국어이며 토큰·주소를 담지 않는다."""


def _ascii_digits(text: str) -> bool:
    """ASCII 숫자로만 이뤄졌는가. ``str.isdigit()`` 은 '²'·'٣' 같은 비 ASCII 숫자도 참이라 ``int()`` 가 ValueError 를 낸다."""
    return text.isascii() and text.isdigit()


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """리다이렉트를 따라가지 않는다. urllib 기본 동작은 ``Authorization`` 헤더를 새 주소로 다시 보내므로, 토큰이 다른 곳으로 가지 않게
    3xx 를 그대로 오류(HTTPError)로 끝낸다."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401 — urllib 규약
        return None


# 리다이렉트를 거부하는 전용 opener(전역 urlopen 설치 상태와 무관하게 쓴다).
_OPENER = urllib.request.build_opener(_NoRedirect)


def _open(request: urllib.request.Request, timeout: float):
    """요청을 보낸다 — 리다이렉트 거부 opener 를 거친다(테스트가 이 함수를 바꿔 끼운다)."""
    return _OPENER.open(request, timeout=timeout)


def _config() -> Tuple[str, str, int, int]:
    """(주소, 토큰, 테넌트, 슬롯). 설정이 없거나 토큰 모양이 아니면 ``QueryError``."""
    base = (os.environ.get(_ENV_URL) or "").strip().rstrip("/")
    token = (os.environ.get(_ENV_TOKEN) or "").strip()
    if not base or not token:
        raise QueryError(_MISSING_CONFIG)
    # v1.<tenantId>.<slot>.<epoch>.<exp>.<sig> — 서명(base64url)에는 점이 없으므로 정확히 6조각이다.
    parts = token.split(".")
    if len(parts) != 6 or parts[0] != "v1" or not _ascii_digits(parts[1]) or not _ascii_digits(parts[2]):
        raise QueryError(_BAD_TOKEN)
    return base, token, int(parts[1]), int(parts[2])


def query(sql: str) -> List[Dict[str, Any]]:
    """``sql`` 의 결과 전체를 ``list[dict]`` 로 돌려준다.

    스트림을 **끝까지 다 읽고** 응답을 닫은 뒤에 돌려준다 — db-executor 슬롯 풀은 같은 (테넌트, 슬롯)에 동시 조회 3개까지라, 다 읽지 않은
    스트림을 쥔 채 다음 조회를 열면 슬롯을 낭비한다.
    """
    return list(iter_query(sql))


def iter_query(sql: str) -> Iterator[Dict[str, Any]]:
    """``sql`` 의 결과를 한 행(dict)씩 돌려주는 지연 반복자. 큰 테이블을 메모리에 다 올리지 않고 읽을 때 쓴다.

    주의
    - 반복자 하나가 열려 있는 동안 db-executor 슬롯 연결 하나를 쥔다. 같은 슬롯에 **동시에 열 수 있는 반복자는 3개까지**이고, 넷째는
      "같은 등급 슬롯의 동시 조회가 많습니다…" 실패로 ``QueryError`` 가 난다.
    - 끝까지 돌지 않고 버리는 반복자는 HTTP 응답을 닫아야 슬롯이 풀린다. 생성기의 ``finally`` 가 닫으므로 ``close()`` 를 부르거나
      ``contextlib.closing(genia_db.iter_query(...))`` 로 감싸 쓰면 된다(가비지 수집에 맡기면 언제 닫힐지 모른다).
    - 마지막 줄이 성공이 아니거나 스트림이 끊기면 반복 도중 ``QueryError`` 가 난다 — 그때까지 받은 행은 전체가 아니다.
    - 반복하면서 행마다 느린 처리(예: 행마다 외부 호출)를 하면 db-executor 쪽 전송이 막혀 시간 초과로 스트림이 끊기고 ``QueryError`` 가
      난다 — 오래 걸리는 처리는 ``query()`` 로 먼저 다 받아 두고 하거나, 반복자는 지체 없이 읽어 낸다.

    설정·토큰 모양 확인은 부르는 즉시 한다. 요청은 첫 ``next()`` 에서 보낸다.
    """
    if not isinstance(sql, str) or not sql.strip():
        raise QueryError("조회할 SQL 문장을 문자열로 주세요.")
    base, token, tenant_id, slot = _config()
    return _stream(base, token, tenant_id, slot, sql)


def _stream(base: str, token: str, tenant_id: int, slot: int, sql: str) -> Iterator[Dict[str, Any]]:
    """요청을 보내고 NDJSON 줄을 읽어 행을 내보낸다. 응답은 어떤 경로로 끝나든(성공·실패·버림) ``finally`` 에서 닫는다.

    요청을 생성기 **안에서** 여는 이유: 생성기 밖에서 열고 생성기를 돌려주면, 한 번도 시작하지 않은 생성기는 ``finally`` 가 돌지 않아
    응답(=슬롯 연결)이 샌다.
    """
    body = json.dumps(
        {"tenantId": tenant_id, "readSlot": slot, "stream": True, "read_only": True, "query": sql},
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        base + "/execute/query",
        data=body,
        method="POST",
        headers={
            "Authorization": "Script " + token,
            "Content-Type": "application/json",
            "Accept": "application/x-ndjson",
        },
    )
    resp: Optional[http.client.HTTPResponse] = None
    try:
        try:
            resp = _open(request, _READ_TIMEOUT_SECONDS)
        except urllib.error.HTTPError as e:
            raise QueryError(_http_error_message(e)) from None
        except (OSError, http.client.HTTPException, ValueError):
            # URLError·소켓 시간 초과도 OSError 다. 원문에는 주소가 담길 수 있어 체인을 끊는다.
            raise QueryError(_CONNECT_FAILED) from None

        received = 0
        while True:
            try:
                raw = resp.readline()
            except (OSError, http.client.HTTPException):
                # IncompleteRead(청크 중간 끊김)·소켓 시간 초과·연결 재설정
                raise QueryError(_CUT_OFF) from None
            if not raw:
                # 성공 줄 없이 끝났다 — 받은 행이 전체라는 보장이 없다.
                raise QueryError(_CUT_OFF)
            line = raw.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except ValueError:
                raise QueryError(_BAD_FORMAT) from None
            if not isinstance(obj, dict):
                raise QueryError(_BAD_FORMAT)
            if "success" in obj:
                # 마지막 줄 — 실행 전 거부도 이 실패 줄 하나로 온다.
                if obj.get("success") is not True:
                    error = obj.get("error")
                    raise QueryError(error if isinstance(error, str) and error.strip() else "조회에 실패했습니다.")
                expected = obj.get("row_count")
                if isinstance(expected, bool) or not isinstance(expected, int) or expected != received:
                    raise QueryError(f"조회 결과 행 수가 맞지 않습니다(받음 {received}, 서버 {expected}).")
                return
            if "row" in obj:
                row = obj["row"]
                if not isinstance(row, dict):
                    raise QueryError(_BAD_FORMAT)
                received += 1
                yield row
            elif "columns" in obj:
                continue
            else:
                raise QueryError(_BAD_FORMAT)
    finally:
        if resp is not None:
            resp.close()


def _http_error_message(e: urllib.error.HTTPError) -> str:
    """HTTP 오류 응답을 사용자 문구로. 401 은 토큰 문제, 그 밖은 본문의 ``error``(있으면) 또는 상태 코드."""
    try:
        status = e.code
        if status == 401:
            return _UNAUTHORIZED
        detail = None
        try:
            text = e.read().decode("utf-8", "replace").strip()
            last = text.splitlines()[-1] if text else ""
            parsed = json.loads(last) if last else None
            if isinstance(parsed, dict) and isinstance(parsed.get("error"), str):
                detail = parsed["error"].strip() or None
        except (OSError, ValueError, http.client.HTTPException):
            detail = None
        return detail or f"조회 요청이 거부되었습니다(HTTP {status})."
    finally:
        e.close()
