"""Tibero 원격 적재 백엔드 — Python 실행기가 Tibero 에 접속하지 않고 db-executor 의 ``POST /execute/load`` 로 행을 보낸다(WD-77).

``DbBackend`` 처럼 연결(``connection()``)을 빌려주지 않는다. 연결 없는 계약(``RemoteLoadBackend``)만 만족한다.

**값 변환 순서(결정 4).** db-executor 는 변환 불가 값과 숫자·날짜 타입의 빈 문자열을 요청 전체 실패로 처리한다(fail-closed).
그 대가로 Python 실행기가 보내기 *전에* 값을 변환한다: 기존 ``python_executor._apply_type_conversion`` 이 변환에 실패한 값을
None 으로 만들고, 이 모듈의 직렬화가 JSON 으로 만든다. 직렬화 형태는 공유 벡터(``tibero-bind-vectors.json``)의 입력 형태다.
"""
from __future__ import annotations

import contextlib
import json
import logging
import math
import re
from datetime import date, datetime
from decimal import Decimal
from typing import AbstractSet, Any, Dict, List, Optional, Protocol

import httpx

from app.config import Settings
from app.services.python_executor import _apply_type_conversion

logger = logging.getLogger(__name__)

# db-executor 가 적재 대상으로 허용하는 스테이징 이름과 같은 규칙(대문자 16진 24자). fullmatch 로 본다 — ``$`` 는 끝 개행을 허용한다.
_STAGING_RE = re.compile(r"STG_[0-9A-F]{24}")

# db-executor LoadValues 가 아는 앱 타입으로 바꾸는 별칭 표. Python 변환(_convert_single)은 이 별칭들을 같은 뜻으로 다룬다.
_TYPE_ALIASES = {
    "STRING": "TEXT",
    "NUMERIC": "DECIMAL",
    "FLOAT": "DECIMAL",
    "DOUBLE": "DECIMAL",
    "DATETIME": "TIMESTAMP",
}
# 빈 문자열이 db-executor 에서 요청 전체 실패가 되는 타입(벡터 expectFailure). 변환을 거치지 않은 빈 값도 None 으로 보낸다.
_NULL_ON_BLANK = {"INTEGER", "DECIMAL", "DATE", "TIMESTAMP"}

_PREFIX = "Tibero 적재(db-executor)"

# ``/execute/load`` 한 번에 보내는 최대 행 수(최종 리뷰 I3). db-executor 는 요청 본문을 Jackson 목록과 변환 배열로 두 번 메모리에 올리고
# 그 JVM 을 모든 테넌트가 함께 쓴다 — 수백만 행을 한 요청으로 보내면 OOM 이 전 테넌트의 Tibero 작업을 끊는다. 대상은 스테이징이고
# ``_SEQ`` 가 호출 사이에 이어지며(db-executor 가 NVL(MAX(_SEQ),0) 다음부터 매긴다) 원자성은 api 의 옮기기가 맡으므로 나눠 보내도 안전하다.
LOAD_CHUNK_ROWS = 5000


class RemoteLoadError(RuntimeError):
    """원격 적재 실패. 메시지는 한국어이며 토큰·주소를 담지 않는다."""


class RemoteLoadBackend(Protocol):
    """연결 없는 원격 적재 계약 — 행을 (테넌트, 스테이징 테이블)로 보내고 적재 행 수를 돌려받는다."""

    def load(
        self,
        tenant_id: int,
        table: str,
        rows: List[Dict[str, Any]],
        column_type_map: Optional[dict],
        timeout_seconds: Optional[int] = None,
        drop_keys: AbstractSet[str] = frozenset(),
    ) -> int: ...


def require_staging_table(table: str) -> str:
    """스테이징 이름(``STG_`` + 대문자 16진 24자)이 아니면 ValueError. 통과하면 같은 이름을 돌려준다."""
    if not isinstance(table, str) or _STAGING_RE.fullmatch(table) is None:
        raise ValueError("Tibero 적재 대상은 임시 적재 테이블(STG_ + 대문자 16진 24자)이어야 합니다")
    return table


def _normalise_type(app_type: Optional[str]) -> Optional[str]:
    """앱 타입 이름을 db-executor ``LoadValues`` 가 아는 정식 이름(대문자)으로 바꾼다. 비었으면 None.

    Python 변환(``_convert_single``)은 STRING·NUMERIC·FLOAT·DOUBLE·DATETIME 같은 별칭을 정식 타입과 같은 뜻으로 받지만 db-executor 는
    정식 이름(TEXT·DECIMAL·TIMESTAMP 등)으로 값을 해석한다. 별칭을 그대로 보내면 두 쪽이 같은 컬럼을 다른 타입으로 볼 수 있으므로, 직렬화
    판단(``to_wire_value``)과 요청의 ``column_type_map`` 모두 이 함수로 같은 이름을 쓴다.
    """
    if not app_type:
        return None
    upper = str(app_type).upper()
    return _TYPE_ALIASES.get(upper, upper)


def to_wire_value(value: Any, app_type: Optional[str]) -> Any:
    """변환이 끝난 값 하나를 db-executor 가 받는 JSON 값으로 만든다(공유 벡터의 입력 형태).

    - Decimal → 문자열(정밀도 보존; NaN·Infinity 는 BigDecimal 이 거부하므로 None)
    - datetime → ISO 문자열(DATE 열이면 날짜만 — LocalDate.parse 는 시각이 붙으면 요청 전체를 실패시킨다). ``datetime`` 이 ``date``
      의 하위 클래스라 먼저 본다.
    - date → ISO 문자열, bool 은 bool 그대로(int 보다 먼저), 비유한 float 는 None
    - 숫자·날짜 타입의 빈 문자열 → None
    - dict·list → JSON 문자열(중첩 값은 db-executor 가 바인딩하지 못한다)
    """
    if value is None:
        return None
    kind = _normalise_type(app_type)
    if isinstance(value, bool):
        return value
    if isinstance(value, Decimal):
        return str(value) if value.is_finite() else None
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, datetime):
        return value.date().isoformat() if kind == "DATE" else value.isoformat()
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, str):
        if kind in _NULL_ON_BLANK and not value.strip():
            return None
        return value
    if isinstance(value, (int,)):
        return value
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    return str(value)


def to_wire_rows(
    rows: List[Dict[str, Any]],
    column_type_map: Optional[dict],
    drop_keys: AbstractSet[str] = frozenset(),
) -> List[Dict[str, Any]]:
    """행 목록을 **복사해** 기존 Python 변환을 적용하고 JSON 값으로 직렬화한다(입력 행은 바꾸지 않는다).

    ``drop_keys`` 가 있으면 복사하면서 그 키를 뺀다(변환 전에 — 뺄 키는 변환하지 않는다). 없으면 기존처럼 ``dict(row)`` 로 복사한다.
    """
    if drop_keys:
        copied = [{k: v for k, v in row.items() if k not in drop_keys} for row in rows]
    else:
        copied = [dict(row) for row in rows]
    _apply_type_conversion(copied, column_type_map)
    types = column_type_map or {}
    return [{col: to_wire_value(val, types.get(col)) for col, val in row.items()} for row in copied]


class RemoteLoader:
    """db-executor ``/execute/load`` 호출기. 적재 토큰은 그 창구 하나만 허용된다(``Authorization: Internal <load token>``).

    주소·토큰이 비어 있으면 요청을 보내지 않고 실패한다(fail-closed). 실패 응답·전송 오류는 모두 ``RemoteLoadError`` 로,
    메시지에 토큰·주소를 싣지 않는다. 행은 ``LOAD_CHUNK_ROWS`` 행씩 나눠 보내며, 청크 하나가 db-executor 의 한 트랜잭션이다 —
    모든 청크의 응답이 성공이어야 적재가 끝난 것이다. ``load()`` 한 번은 HTTP 클라이언트 하나를 열어 모든 청크가 같이 쓴다(연결 재사용).
    """

    def __init__(self, settings: Settings, *, client: Optional[httpx.Client] = None) -> None:
        self._settings = settings
        # 주입된 클라이언트(테스트)는 호출자 소유라 닫지 않는다. 없으면 load() 한 번마다 하나 만들어 모든 청크가 같이 쓰고 load() 가 끝나면
        # 닫는다 — 청크마다 새로 열면 청크마다 TCP 연결을 다시 맺고, 호출 사이에 들고 있으면 소켓·풀이 남는다(코드리뷰 8).
        self._injected_client = client

    def _timeout(self) -> httpx.Timeout:
        """기본 httpx 타임아웃(5초)이면 실제 적재가 끊긴다 — 읽기·쓰기는 설정의 적재 제한 시간, 연결은 10초로 따로 짧게 건다."""
        return httpx.Timeout(self._settings.db_executor_load_timeout_seconds, connect=10.0)

    def _request_timeout_seconds(self, timeout_seconds: Optional[int]) -> int:
        """db-executor 에 보낼 ``timeout_seconds``. Python 읽기 제한(설정)을 내림한 값이 상한이다(최소 1초).

        두 쪽이 함께 포기하게 하려는 것이다 — Python 이 읽기 제한으로 먼저 끊었는데 db-executor 가 더 긴 자체 기한으로 계속 돌면,
        api 가 곧 지울 스테이징에 커밋하는 헛일이 남는다. 올림하지 않는다(db-executor 가 Python 보다 오래 버티면 안 된다).
        """
        cap = max(1, int(self._settings.db_executor_load_timeout_seconds))
        if timeout_seconds is None:
            return cap
        return max(1, min(int(timeout_seconds), cap))

    def load(
        self,
        tenant_id: int,
        table: str,
        rows: List[Dict[str, Any]],
        column_type_map: Optional[dict],
        timeout_seconds: Optional[int] = None,
        drop_keys: AbstractSet[str] = frozenset(),
    ) -> int:
        """rows 를 스테이징 ``table`` 에 적재하고 적재 행 수(전 청크 합)를 돌려준다.

        ``drop_keys`` 에 든 키는 청크마다 행을 복사할 때 뺀다(Python Tibero 출력의 시스템 컬럼, WD-78) — 호출자가 전체 결과의 사본을
        따로 만들지 않게. 비어 있으면(api-call) 행을 그대로 복사한다.

        ``LOAD_CHUNK_ROWS`` 행씩 순서대로 나눠 보낸다. 어느 청크든 실패하면 그 자리에서 멈추고 그 청크의 실패를 ``RemoteLoadError``
        (메시지 = 응답 ``error``)로 올린다 — 앞 청크가 넣은 행은 스테이징에만 있고, 실패한 단계의 스테이징 정리는 api 몫이다.
        """
        base = (self._settings.db_executor_base_url or "").strip().rstrip("/")
        token = self._settings.db_executor_load_token or ""
        if not base or not token:
            # 이 문구는 api 스텝 오류로 사용자 화면에 나간다 — 환경 변수 이름은 실행기 WARN 로그에만 남긴다(코드리뷰 3).
            logger.warning(
                "Tibero 원격 적재 불가: EXECUTOR_DB_EXECUTOR_BASE_URL 또는 EXECUTOR_DB_EXECUTOR_LOAD_TOKEN 이 설정되지 않았습니다"
            )
            raise RemoteLoadError(f"{_PREFIX} 설정이 준비되지 않았습니다. 운영자에게 문의하세요.")
        try:
            require_staging_table(table)
        except ValueError as e:
            raise RemoteLoadError(str(e)) from None
        # db-executor 는 빈 rows 를 요청 전체 실패로 본다 — 보낼 행이 없으면 호출하지 않는다.
        if not rows:
            return 0

        wire_types = (
            {col: _normalise_type(t) or t for col, t in column_type_map.items()} if column_type_map else None
        )
        request_timeout = self._request_timeout_seconds(timeout_seconds)
        url = f"{base}/execute/load"
        headers = {"Authorization": f"Internal {token}"}
        total = 0
        # 클라이언트 하나를 모든 청크가 같이 쓴다. 시간 제한은 httpx 가 요청마다 적용하므로 청크마다 같은 제한이 걸린다(총 기한이 아니다).
        # 주입된 클라이언트는 호출자 소유라 닫지 않는다(nullcontext).
        client_ctx = (
            contextlib.nullcontext(self._injected_client)
            if self._injected_client is not None
            else httpx.Client(timeout=self._timeout())
        )
        with client_ctx as client:
            for start in range(0, len(rows), LOAD_CHUNK_ROWS):
                chunk = rows[start:start + LOAD_CHUNK_ROWS]
                # 청크마다 변환·직렬화한다 — 전체 행의 직렬화 사본을 한꺼번에 들고 있지 않게.
                body: Dict[str, Any] = {
                    "tenantId": tenant_id,
                    "table": table,
                    "rows": to_wire_rows(chunk, column_type_map, drop_keys),
                    "timeout_seconds": request_timeout,
                }
                if wire_types:
                    body["column_type_map"] = wire_types
                total += self._post_chunk(client, url, headers, body, len(chunk))
        return total

    def _post_chunk(
        self, client: httpx.Client, url: str, headers: Dict[str, str], body: Dict[str, Any], sent: int
    ) -> int:
        """청크 하나를 ``client`` 로 보내고 적재 행 수를 돌려준다. 실패·행 수 불일치는 ``RemoteLoadError``(토큰·주소를 싣지 않는다)."""
        try:
            resp = client.post(url, json=body, headers=headers)
        except httpx.TimeoutException:
            raise RemoteLoadError(f"{_PREFIX} 응답 시간이 초과되었습니다") from None
        except httpx.HTTPError:
            # 예외 원문에는 주소가 담길 수 있어 체인을 끊는다.
            raise RemoteLoadError(f"{_PREFIX} 서버(db-executor)에 연결할 수 없습니다") from None

        try:
            data = resp.json()
        except ValueError:
            data = None
        if resp.status_code != 200:
            # 본문이 JSON 객체이고 error 가 있으면 그 문구를 쓴다(db-executor 의 거부 사유). 없으면 상태 코드만 알린다.
            detail = data.get("error") if isinstance(data, dict) else None
            if isinstance(detail, str) and detail.strip():
                raise RemoteLoadError(detail)
            raise RemoteLoadError(f"{_PREFIX} 요청이 거부되었습니다(HTTP {resp.status_code})")
        if not isinstance(data, dict):
            raise RemoteLoadError(f"{_PREFIX} 응답이 올바르지 않습니다(JSON 객체가 아닙니다)")
        if not data.get("success"):
            raise RemoteLoadError(str(data.get("error") or f"{_PREFIX} 실패"))
        loaded = data.get("rows_loaded")
        # 성공 응답의 적재 행 수가 보낸 행 수와 다르면 일부만 반영된 것이므로 성공으로 보지 않는다.
        if isinstance(loaded, bool) or not isinstance(loaded, int) or loaded != sent:
            raise RemoteLoadError(
                f"{_PREFIX} 적재 행 수가 보낸 행 수와 다릅니다(보냄 {sent}, 응답 {loaded})"
            )
        return loaded
