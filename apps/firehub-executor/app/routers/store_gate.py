"""실행 라우트 공용 저장소 게이트 (WD-75, WD-77).

Python 실행기는 Tibero 에 직접 접속하지 않는다. 실행 라우트는 핸들러 첫 줄에서 이 게이트로 테넌트의 저장소 백엔드를 확인한다.
- sql/query/probe/load 는 PostgreSQL 이 아니면 기존 코드 경로로 들어가지 않고 실패 응답을 낸다(``unsupported_error``).
- api-call·python 은 창구별로 다르다: Tibero 테넌트는 거부하지 않고 db-executor 원격 적재로 보낸다(``backend_of`` 로 분기, WD-77·WD-78).
  원격 적재기(``loader_for``)와 출력 대상 이름 검사(``require_output_table``)도 이 게이트가 백엔드별로 정한다 — 두 창구가 같은 규칙을 쓴다.
- 백엔드는 api 기술자 조회로만 정한다(요청 페이로드는 믿지 않는다).
- 조회가 실패하면 PG 로 폴백하지 않는다(R-pg-lookup, fail-closed).
- 게이트는 기술자의 backend 값만 본다 — 백엔드 객체(PostgresBackend)를 만들지 않는다.
  판정에 필요 없는 객체 생성과 그 부수효과(접속 실패 콜백 연결 등)를 게이트에 끌어들이지 않기 위해서다.
"""
from typing import Optional, Tuple

from starlette.concurrency import run_in_threadpool

from app.config import Settings
from app.db.backends.base import BACKEND_POSTGRESQL, BACKEND_TIBERO
from app.db.backends.remote import RemoteLoadBackend, RemoteLoader, require_staging_table
from app.db.store_client import StoreLookupError, get_store_client

# 사용자 실행 로그에 그대로 보이는 문구라 이슈 번호는 넣지 않는다.
QUERY_UNSUPPORTED = "Tibero 저장소 워크스페이스의 SQL 조회는 실행기에서 처리하지 않습니다"
PROBE_UNSUPPORTED = "Tibero 저장소 워크스페이스의 컬럼 탐지는 실행기에서 처리하지 않습니다"
SQL_UNSUPPORTED = "Tibero 저장소 워크스페이스에서는 파이프라인 SQL 단계를 아직 실행할 수 없습니다"
# /execute/load 는 PG 만 처리한다 — Tibero 적재는 db-executor 의 /execute/load 가 맡는다(Python→db-executor 원격 적재는 후속 작업).
LOAD_UNSUPPORTED = "Tibero 저장소 워크스페이스의 적재는 이 실행기에서 처리하지 않습니다"


def _lookup_backend(tenant_id: int, settings: Settings) -> Tuple[Optional[str], Optional[str]]:
    """(backend, None) 또는 (None, 조회 실패 메시지). 블로킹 httpx 조회라 이벤트 루프 밖에서 부른다."""
    try:
        return get_store_client(settings).get(tenant_id).backend, None
    except StoreLookupError as e:
        return None, str(e)


async def backend_of(tenant_id: int, settings: Settings) -> Tuple[Optional[str], Optional[str]]:
    """테넌트의 저장소 백엔드를 ``(backend, error)`` 로 돌려준다 — 둘 중 하나만 채워진다(창구별 분기용, WD-77).

    ``backend`` 는 ``"POSTGRESQL"`` 또는 ``"TIBERO"``, ``error`` 는 조회 실패의 한국어 메시지다(fail-closed — PG 로 폴백하지 않는다).
    기술자가 캐시돼 있으면 블로킹 없이 이벤트 루프에서 바로 판정하고, 없을 때만 스레드풀에서 조회한다(``unsupported_error`` 와 같다).
    """
    descriptor = get_store_client(settings).peek(tenant_id)
    if descriptor is not None:
        return descriptor.backend, None
    return await run_in_threadpool(_lookup_backend, tenant_id, settings)


def postgres_only_error(tenant_id: int, settings: Settings, unsupported_message: str) -> Optional[str]:
    """PG 테넌트면 None, 아니면 라우트가 돌려줄 한국어 오류 메시지를 반환한다.

    api ExecutorClient 가 ``.retrieve()`` 를 써서 non-2xx 에서는 본문 메시지를 잃으므로, 호출 측은 이 메시지를
    HTTP 200 + success=False 응답에 실어 보낸다.
    """
    try:
        descriptor = get_store_client(settings).get(tenant_id)
    except StoreLookupError as e:
        return str(e)
    if descriptor.backend == BACKEND_POSTGRESQL:
        return None
    return unsupported_message


async def unsupported_error(tenant_id: int, settings: Settings, unsupported_message: str) -> Optional[str]:
    """네 실행 라우트가 핸들러 첫 줄에서 부르는 비동기 게이트. PG 테넌트면 None, 아니면 오류 메시지.

    기술자가 캐시돼 있으면(``StoreClient.peek``) 블로킹 없이 이벤트 루프에서 바로 판정한다 — PG 테넌트는 첫 조회 이후
    매 요청이 여기서 끝난다. 캐시에 없을 때만 블로킹 httpx 조회를 스레드풀에서 돌려 이벤트 루프를 막지 않는다.
    """
    descriptor = get_store_client(settings).peek(tenant_id)
    if descriptor is not None:
        return None if descriptor.backend == BACKEND_POSTGRESQL else unsupported_message
    return await run_in_threadpool(postgres_only_error, tenant_id, settings, unsupported_message)


def loader_for(backend: Optional[str], settings: Settings) -> Optional[RemoteLoadBackend]:
    """백엔드가 원격 적재를 쓰면 그 적재기를, 아니면 None 을 돌려준다(api-call·python 창구 공용, WD-77·WD-78).

    Tibero 테넌트의 출력은 PG 연결이 아니라 db-executor ``/execute/load`` 로 간다(``RemoteLoader``). PG 는 None — 호출 측은 기존
    PG 연결 경로를 그대로 탄다(PG 경로에서는 적재기를 만들지 않는다).
    """
    return RemoteLoader(settings) if backend == BACKEND_TIBERO else None


def require_output_table(backend: Optional[str], name: Optional[str]) -> Optional[str]:
    """출력 대상 이름 검사 — 통과하면 None, 아니면 라우트가 돌려줄 한국어 거부 문구.

    Tibero 의 출력 대상은 api 가 만든 스테이징(``STG_`` + 대문자 16진 24자)뿐이다 — 그 형태가 아니면 db-executor 로 보내기 전에(스크립트는
    띄우기 전에) 막는다. PG 는 기존 이름 규칙 그대로라 보지 않는다. ``name`` 이 None 이면 출력이 없는 실행(python)이라 볼 것이 없다.
    """
    if backend != BACKEND_TIBERO or name is None:
        return None
    try:
        require_staging_table(name)
    except ValueError as e:
        return str(e)
    return None
