"""api 의 데이터셋 저장소 기술자 조회 클라이언트 + 캐시(PG 영구·Tibero TTL).

백엔드(PostgreSQL/Tibero) 선택은 **요청 페이로드를 믿지 않고** api 의
``GET /internal/executor/tenants/{id}/data-store`` 응답으로만 정한다(spec §3).

fail-closed 원칙(R-pg-lookup): 조회가 어떤 이유로든 실패하면 ``StoreLookupError`` 를 던진다.
PG 로 폴백하지 않는다 — Tibero 테넌트가 조용히 PG 에서 실행되는 것이 실패보다 나쁘다.
Python 실행기 토큰으로는 backend·tenantId 만 받는다 — Tibero 접속 정보·비밀번호는 받지도 들지도 않는다(스펙 §3.4).
"""
from __future__ import annotations

import math
import threading
import time
from typing import Callable, Literal

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.config import Settings

_PREFIX = "데이터셋 저장소 정보를 조회하지 못했습니다"
# api 본문 message 를 사용자 메시지에 싣을 때의 상한(과도하게 긴 본문이 로그를 채우지 않게).
_MAX_RELAY_LEN = 200


class StoreLookupError(RuntimeError):
    """저장소 기술자 조회·검증 실패. 메시지는 한국어이며 비밀번호를 담지 않는다."""


class StoreDescriptor(BaseModel):
    """api 가 알려주는 테넌트 저장소 기술자. Python 실행기에게는 backend 와 tenantId 만 온다.

    접속 정보·계정 필드를 일부러 두지 않는다 — 본문에 실려 와도 모델이 받아 두지 않는다(pydantic 기본 extra=ignore).
    """

    model_config = ConfigDict(populate_by_name=True)

    tenant_id: int = Field(alias="tenantId")
    backend: Literal["POSTGRESQL", "TIBERO"]


class StoreClient:
    """기술자 조회 + 캐시. 200 성공만 캐시한다(실패를 캐시하면 복구가 늦어진다).

    캐시 수명은 백엔드마다 다르다:
    - **POSTGRESQL 기술자는 프로세스 수명 동안 만료 없이 캐시한다.** 테넌트의 백엔드는 테넌트 생성 때 한 번 정해지고
      바뀌지 않는다 — api 에서 ``tenant_data_store`` 행을 쓰는 곳은 ``TenantDataStoreRepository.insert`` 하나뿐이고,
      그 호출자는 테넌트 생성 트랜잭션 안의 ``TenantDataStoreSetupService`` 뿐이다(행이 없으면 POSTGRESQL). api 의
      ``DataStoreResolver`` 도 같은 전제로 만료 없이 캐시한다. 그래서 PG 테넌트는 워커 프로세스당 테넌트별 첫 조회
      이후로 api 에 의존하지 않는다 — api 재기동·과부하가 PG 파이프라인을 막지 않고, api→executor→api 재진입
      호출이 매 실행마다 생기지도 않는다. PG 항목은 ``invalidate`` 로만 버려진다.
    - **TIBERO 기술자는 TTL(``data_store_cache_ttl_seconds``)을 지킨다.** 저장소 종류·상태가 바뀔 수 있기 때문이다(접속 정보는 받지 않는다).

    동시성:
    - 테넌트별 **세대(generation)**: ``invalidate`` 가 세대를 올린다. 조회는 시작할 때의 세대를 기억했다가 끝났을 때
      세대가 그대로일 때만 결과를 저장한다 — 조회 도중 무효화되면 낡은 값을 다시 써넣지 않는다.
    - 테넌트별 **단일 조회(single-flight)**: 테넌트별 락으로 한 테넌트에 대한 HTTP 조회를 한 번에 하나만 보낸다.
      전역 락은 사전 조작에만 짧게 잡고, HTTP 호출 동안에는 테넌트 락만 잡는다. 전역 락을 쥔 채 테넌트 락을
      잡지 않는다(교착 방지). ``invalidate`` 는 전역 락만 잡으므로 조회 중(테넌트 락 보유 중)에 불려도 막히지 않는다.
    """

    def __init__(
        self,
        settings: Settings,
        *,
        client: httpx.Client | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._settings = settings
        # 조회 타임아웃을 설정에서 가져온다 — api 가 멈춰도 실행 요청이 5초 안에 fail-closed 로 끝난다.
        self._client = client or httpx.Client(timeout=settings.data_store_lookup_timeout_seconds)
        self._clock = clock
        # 테넌트 → (만료 시각, 기술자). 쓰기는 전역 락으로 보호하고, peek 의 읽기는 락 없이 한다.
        # 만료 시각은 저장 시점에 정해 둔다 — PG 는 math.inf(만료 없음), Tibero 는 저장 시각 + TTL.
        self._cache: dict[int, tuple[float, StoreDescriptor]] = {}
        # 테넌트 → 세대. invalidate 가 올린다. 전역 락으로 보호한다.
        self._generation: dict[int, int] = {}
        # 테넌트 → 단일 조회용 락. 테넌트 수만큼만 생기고 버리지 않는다(테넌트 수가 상한).
        self._tenant_locks: dict[int, threading.Lock] = {}
        self._lock = threading.Lock()

    def get(self, tenant_id: int) -> StoreDescriptor:
        """tenant 의 기술자를 돌려준다. 캐시가 유효하면(PG 는 항상, Tibero 는 TTL 안) api 를 호출하지 않는다."""
        hit = self.peek(tenant_id)
        if hit is not None:
            return hit
        # 테넌트 락은 전역 락 밖에서 잡는다 — 전역 락을 쥔 채 테넌트 락을 기다리면 교착이 생길 수 있다.
        with self._tenant_lock(tenant_id):
            # 앞서 같은 테넌트를 조회한 스레드가 채웠으면 그 값을 쓴다(동시 미스에서 HTTP 1회).
            hit = self.peek(tenant_id)
            if hit is not None:
                return hit
            with self._lock:
                started_gen = self._generation.get(tenant_id, 0)
            # HTTP 호출은 전역 락 밖에서 — 느린 api 가 다른 테넌트 조회를 막지 않게 한다. 실패는 예외로 나가 캐시되지 않는다.
            desc = self._fetch(tenant_id)
            with self._lock:
                # 조회 도중 invalidate 가 있었으면 세대가 바뀌었으므로 이 (낡았을 수 있는) 결과는 저장하지 않는다.
                if self._generation.get(tenant_id, 0) == started_gen:
                    # PG 백엔드는 테넌트 생성 후 바뀌지 않으므로 만료하지 않는다(클래스 주석 참고).
                    expires_at = (
                        math.inf
                        if desc.backend == "POSTGRESQL"
                        else self._clock() + self._settings.data_store_cache_ttl_seconds
                    )
                    self._cache[tenant_id] = (expires_at, desc)
            return desc

    def invalidate(self, tenant_id: int) -> None:
        """해당 테넌트 캐시를 버리고 세대를 올린다(계정 회전·접속 실패 후 재조회용). PG 항목도 여기서만 버려진다."""
        with self._lock:
            self._cache.pop(tenant_id, None)
            self._generation[tenant_id] = self._generation.get(tenant_id, 0) + 1

    def peek(self, tenant_id: int) -> StoreDescriptor | None:
        """유효한 캐시 항목이 있으면 기술자를, 없거나 만료됐으면 None 을 돌려준다. 블로킹·락 없음.

        전역 락을 잡지 않는다 — dict 단일 읽기는 GIL 아래서 원자적이고, 항목은 한 번 쓰이면 바뀌지 않는 튜플이라
        낡은 값을 읽어도 최악이 "방금 무효화된 항목 1회 사용" 이다(invalidate 와 경합하는 순간뿐). 시계는 만료가
        유한한 항목(Tibero)에서만 읽는다. 이벤트 루프에서 호출해도 안전해, 라우트 게이트가 캐시 적중 시 스레드풀로
        넘어가지 않고 인라인으로 판정하는 데 쓴다.
        """
        hit = self._cache.get(tenant_id)
        if hit is None:
            return None
        expires_at, desc = hit
        if expires_at != math.inf and self._clock() >= expires_at:
            return None
        return desc

    def _tenant_lock(self, tenant_id: int) -> threading.Lock:
        """테넌트별 단일 조회 락을 찾거나 만든다. 사전 조작만 전역 락 안에서 한다."""
        with self._lock:
            lock = self._tenant_locks.get(tenant_id)
            if lock is None:
                lock = threading.Lock()
                self._tenant_locks[tenant_id] = lock
            return lock

    def _fetch(self, tenant_id: int) -> StoreDescriptor:
        url = f"{self._settings.api_base_url.rstrip('/')}/internal/executor/tenants/{tenant_id}/data-store"
        headers = {"Authorization": f"Internal {self._settings.internal_service_token}"}
        try:
            resp = self._client.get(url, headers=headers)
        except (httpx.InvalidURL, httpx.UnsupportedProtocol):
            # api 주소 자체가 잘못된 경우(스킴 누락 등). 메시지에 URL 을 싣지 않는다(자격증명이 섞일 수 있다).
            raise StoreLookupError(f"{_PREFIX} — api 주소(EXECUTOR_API_BASE_URL)가 올바르지 않습니다") from None
        except httpx.HTTPError:
            raise StoreLookupError(f"{_PREFIX} — api 서버에 연결할 수 없습니다") from None
        except Exception:
            # 요청을 만들거나 보내는 중 난 그 밖의 예외(httpx 밖 오류 포함)도 fail-closed 로 같은 오류 타입에 싣는다.
            # 그대로 올리면 게이트가 잡지 못해 500 이 된다. 원문에는 URL 이 담길 수 있어 체인을 끊는다.
            raise StoreLookupError(f"{_PREFIX} — api 요청을 보내는 중 예기치 않은 오류가 났습니다") from None
        if resp.status_code != 200:
            raise StoreLookupError(self._status_message(resp))
        try:
            desc = StoreDescriptor.model_validate(resp.json())
        except ValidationError as exc:
            # str(ValidationError) 는 input_value 로 본문 값을 그대로 싣는다 → 필드 위치만 쓴다.
            reason = ", ".join(".".join(map(str, e["loc"])) for e in exc.errors())
            raise StoreLookupError(f"데이터셋 저장소 정보가 올바르지 않습니다: {reason}") from None
        except ValueError:
            raise StoreLookupError("데이터셋 저장소 정보가 올바르지 않습니다: 본문이 JSON 이 아닙니다") from None
        self._verify(tenant_id, desc)
        return desc

    @staticmethod
    def _status_message(resp: httpx.Response) -> str:
        """200 이외 응답을 한국어 메시지로 바꾼다. 본문 message 는 api ErrorResponse(비밀번호 없음)."""
        status = resp.status_code
        if status == 401:
            return f"{_PREFIX} — executor 내부 토큰이 api 와 다릅니다"
        if status == 404:
            return f"{_PREFIX} — api 가 저장소 조회를 지원하지 않습니다(api 를 먼저 배포하세요)"
        body: dict = {}
        try:
            parsed = resp.json()
            if isinstance(parsed, dict):
                body = parsed
        except ValueError:
            pass
        raw = body.get("message")
        # api 가 코드별로 정한 사유를 그대로 전달한다(상한 적용). 비었거나 문자열이 아니면 로컬 문구로 대체한다.
        msg = raw.strip()[:_MAX_RELAY_LEN] if isinstance(raw, str) and raw.strip() else None
        if status == 409:
            return f"데이터셋 저장소를 사용할 수 없습니다: {msg or body.get('code') or '준비되지 않았습니다'}"
        if status == 503 and msg:
            return f"{_PREFIX} — {msg}"
        if status == 503 and body.get("code") == "EXECUTOR_TOKEN_NOT_CONFIGURED":
            return (
                f"{_PREFIX} — api 의 executor 내부 토큰이 기본값이거나 비어 있어 "
                "비밀번호를 내려줄 수 없습니다(토큰을 설정하세요)"
            )
        return f"{_PREFIX} — api 응답 {status}"

    @staticmethod
    def _verify(tenant_id: int, desc: StoreDescriptor) -> None:
        """다른 테넌트의 기술자를 거부한다(backend 는 모델의 Literal 이 이미 두 값으로 좁힌다)."""
        if desc.tenant_id != tenant_id:
            raise StoreLookupError("데이터셋 저장소 정보가 올바르지 않습니다: 다른 테넌트의 기술자입니다")


_singleton: StoreClient | None = None
_singleton_lock = threading.Lock()


def get_store_client(settings: Settings) -> StoreClient:
    """프로세스 싱글턴 — 캐시를 요청 간에 공유하려면 하나여야 한다."""
    global _singleton
    with _singleton_lock:
        if _singleton is None:
            _singleton = StoreClient(settings)
        return _singleton


def reset_store_client() -> None:
    """테스트용: 싱글턴을 버린다."""
    global _singleton
    with _singleton_lock:
        _singleton = None
