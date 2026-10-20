"""실행 라우트 공용 저장소 게이트 (WD-75).

WD-75 범위에서 Tibero 를 실행하는 라우트는 없다. 네 실행 라우트(query/sql/api-call/python)는 핸들러 첫 줄에서
이 게이트로 테넌트의 저장소 백엔드를 확인하고, PostgreSQL 이 아니면 기존 코드 경로로 들어가지 않고 실패 응답을 낸다.
- 백엔드는 api 기술자 조회로만 정한다(요청 페이로드는 믿지 않는다).
- 조회가 실패하면 PG 로 폴백하지 않는다(R-pg-lookup, fail-closed).
- 게이트는 기술자의 backend 값만 본다 — 백엔드 객체(PostgresBackend/TiberoBackend)를 만들지 않는다.
  판정에 필요 없는 객체 생성과 그 부수효과(접속 실패 콜백 연결 등)를 게이트에 끌어들이지 않기 위해서다.
"""
from typing import Optional

from starlette.concurrency import run_in_threadpool

from app.config import Settings
from app.db.backends.base import BACKEND_POSTGRESQL
from app.db.store_client import StoreLookupError, get_store_client

# 사용자 실행 로그에 그대로 보이는 문구라 이슈 번호는 넣지 않는다.
QUERY_UNSUPPORTED = "Tibero 저장소 워크스페이스의 SQL 조회는 실행기에서 처리하지 않습니다"
SQL_UNSUPPORTED = "Tibero 저장소 워크스페이스에서는 파이프라인 SQL 단계를 아직 실행할 수 없습니다"
API_CALL_UNSUPPORTED = "Tibero 저장소 워크스페이스에서는 API 수집 적재를 아직 실행할 수 없습니다"
PYTHON_UNSUPPORTED = "Tibero 저장소 워크스페이스에서는 Python 단계를 아직 실행할 수 없습니다"


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
