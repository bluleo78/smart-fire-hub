from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.middleware.auth import verify_internal_auth
from app.routers.store_gate import PYTHON_UNSUPPORTED, unsupported_error
from app.schemas.requests import PythonExecuteRequest
from app.schemas.responses import PythonExecuteResponse
from app.services import python_executor

router = APIRouter(prefix="/execute", tags=["execute"])


@router.post("/python", response_model=PythonExecuteResponse)
async def execute_python(
    request: PythonExecuteRequest,
    _user: str = Depends(verify_internal_auth),
    settings: Settings = Depends(get_settings),
) -> PythonExecuteResponse:
    # Tibero 테넌트·조회 실패는 기존 PG 경로에 들어가기 전에 막는다(WD-75 게이트).
    err = await unsupported_error(request.tenant_id, settings, PYTHON_UNSUPPORTED)
    if err:
        return PythonExecuteResponse(
            success=False, output="", exit_code=1, error=err, execution_time_ms=0
        )
    return python_executor.execute_python(
        request.script,
        request.timeout,
        settings,
        tenant_id=request.tenant_id,
        # 스크립트 DB_URL 을 이 슬롯 롤로 만든다(WD-29) — 요청 모델에서 이미 1~10 정수로 검증됐다.
        read_slot=request.read_slot,
        output_table=request.output_table,
        column_type_map=request.column_type_map,
    )
