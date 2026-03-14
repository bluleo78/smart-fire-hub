from __future__ import annotations

from fastapi import APIRouter, Depends

from app.dependencies import get_db_connection, verify_internal_auth
from app.schemas.requests import ApiCallExecuteRequest
from app.schemas.responses import ApiCallExecuteResponse
from app.services import api_call_executor

router = APIRouter(prefix="/execute", tags=["execute"])


@router.post("/api-call", response_model=ApiCallExecuteResponse)
async def execute_api_call(
    request: ApiCallExecuteRequest,
    _user_id: str = Depends(verify_internal_auth),
    conn=Depends(get_db_connection),
) -> ApiCallExecuteResponse:
    return api_call_executor.execute_api_call(request, conn)
