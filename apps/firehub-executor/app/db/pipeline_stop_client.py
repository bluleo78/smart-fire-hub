"""api 의 파이프라인 실행 중지 요청 확인 클라이언트(WD-466).

REPLACE SQL 스텝은 실행기 트랜잭션의 첫 문장으로 출력 잠금을 잡고, 같은 출력을 쓰는 다른 실행이 끝날 때까지 기다린다. 이 대기는
api 러너의 「SQL 실행 직전」 체크포인트보다 뒤라 그 사이 들어온 취소를 api 가 볼 수 없었다. 그래서 잠금을 얻은 직후 api 의
``GET /internal/executor/tenants/{tenant}/pipeline-executions/{id}/stop-requested`` 로 되묻는다 — 판정은 api 가 DB 에서 하므로
어느 api 인스턴스에서 취소를 받았어도 보인다.

실패(연결 실패·200 이외·형식 오류)는 예외로 올린다 — 호출부(``sql_executor``)가 「중지 아님」으로 보고 그대로 실행한다(fail-open:
확인 실패로 정상 실행을 실패시키지 않는다).
"""
from __future__ import annotations

import threading
from typing import Callable

import httpx

from app.config import Settings

_client: httpx.Client | None = None
_client_lock = threading.Lock()


def _http(settings: Settings) -> httpx.Client:
    """프로세스 공용 httpx 클라이언트(연결 재사용). 시간 제한은 저장소 기술자 조회와 같은 값 — api 가 멈춰도 잠금을 쥔 채 오래 묶이지 않는다."""
    global _client
    with _client_lock:
        if _client is None:
            _client = httpx.Client(timeout=settings.data_store_lookup_timeout_seconds)
        return _client


def stop_check(settings: Settings, tenant_id: int, execution_id: int) -> Callable[[], bool]:
    """이 실행의 중지 요청을 api 에 묻는 함수를 돌려준다(부를 때마다 새로 묻는다 — 캐시하지 않는다)."""

    def check() -> bool:
        url = (
            f"{settings.api_base_url.rstrip('/')}/internal/executor/tenants/{tenant_id}"
            f"/pipeline-executions/{execution_id}/stop-requested"
        )
        resp = _http(settings).get(
            url, headers={"Authorization": f"Internal {settings.internal_service_token}"}
        )
        resp.raise_for_status()
        value = resp.json().get("stopRequested")
        if not isinstance(value, bool):
            raise ValueError("stopRequested 값이 올바르지 않습니다")
        return value

    return check
