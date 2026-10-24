from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.db.connection import get_connection
from app.dependencies import verify_internal_auth
from app.routers.store_gate import LOAD_UNSUPPORTED, unsupported_error
from app.schemas.requests import LoadRequest
from app.schemas.responses import LoadResponse, db_error_of
from app.services import load_executor

router = APIRouter(prefix="/execute", tags=["execute"])


@router.post("/load", response_model=LoadResponse)
async def execute_load_endpoint(
    request: LoadRequest,
    _user_id: str = Depends(verify_internal_auth),
    settings: Settings = Depends(get_settings),
) -> LoadResponse:
    # Tibero 테넌트·조회 실패는 PG 연결을 열기 전에 막는다(다른 실행 라우트와 같은 WD-75 게이트).
    err = await unsupported_error(request.tenant_id, settings, LOAD_UNSUPPORTED)
    if err:
        return LoadResponse(success=False, rows_loaded=0, error=err, db_error=db_error_of(err))
    # 요청의 테넌트 롤로 연다 — 적재도 그 테넌트가 쓸 수 있는 스키마의 테이블만 대상이어야 한다.
    with get_connection(request.tenant_id, settings) as conn:
        return load_executor.execute_load(
            request.table, request.rows, request.column_type_map, conn, request.timeout_seconds
        )
