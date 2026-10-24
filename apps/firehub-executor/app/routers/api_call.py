from __future__ import annotations

from fastapi import APIRouter, Depends
from starlette.concurrency import run_in_threadpool

from app.config import Settings, get_settings
from app.db.connection import get_connection
from app.dependencies import verify_internal_auth
from app.db.backends.base import BACKEND_TIBERO
from app.db.backends.remote import RemoteLoader, require_staging_table
from app.routers.store_gate import backend_of
from app.schemas.requests import ApiCallExecuteRequest
from app.schemas.responses import ApiCallExecuteResponse
from app.services import api_call_executor

router = APIRouter(prefix="/execute", tags=["execute"])


@router.post("/api-call", response_model=ApiCallExecuteResponse)
async def execute_api_call(
    request: ApiCallExecuteRequest,
    _user_id: str = Depends(verify_internal_auth),
    settings: Settings = Depends(get_settings),
) -> ApiCallExecuteResponse:
    # 저장소 백엔드는 api 기술자 조회로만 정한다 — 조회 실패는 PG 로 폴백하지 않고 실패 응답으로 끝낸다(WD-75, fail-closed).
    backend, err = await backend_of(request.tenant_id, settings)
    if err:
        return ApiCallExecuteResponse(success=False, error=err)
    if backend == BACKEND_TIBERO:
        # Tibero: PG 커넥션을 열지 않는다. 출력은 api 가 만든 스테이징(STG_<24HEX>)뿐이고, 행은 db-executor 로 원격 적재한다
        # (WD-77). 이름이 그 형태가 아니면 db-executor 로 보내기 전에 막는다. 블로킹 호출이라 스레드풀에서 돌린다.
        try:
            require_staging_table(request.output_table)
        except ValueError as e:
            return ApiCallExecuteResponse(success=False, error=str(e))
        return await run_in_threadpool(
            api_call_executor.execute_api_call_remote, request, RemoteLoader(settings)
        )
    # 적재 대상 테이블은 요청 테넌트의 스키마에 있다 → 그 테넌트 롤로 접속한 커넥션을 넘긴다.
    with get_connection(request.tenant_id, settings) as conn:
        return api_call_executor.execute_api_call(request, conn)
