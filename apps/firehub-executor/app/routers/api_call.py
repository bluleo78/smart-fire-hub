from __future__ import annotations

from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.db.connection import get_connection
from app.dependencies import verify_internal_auth
from app.routers.store_gate import API_CALL_UNSUPPORTED, unsupported_error
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
    # Tibero 테넌트·조회 실패는 기존 PG 경로에 들어가기 전에 막는다(WD-75 게이트).
    err = await unsupported_error(request.tenant_id, settings, API_CALL_UNSUPPORTED)
    if err:
        return ApiCallExecuteResponse(success=False, error=err)
    # 적재 대상 테이블은 요청 테넌트의 스키마에 있다 → 그 테넌트 롤로 접속한 커넥션을 넘긴다.
    with get_connection(request.tenant_id, settings) as conn:
        return api_call_executor.execute_api_call(request, conn)
