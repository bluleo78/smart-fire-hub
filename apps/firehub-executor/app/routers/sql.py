from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.db.connection import get_connection
from app.dependencies import verify_internal_auth
from app.routers.store_gate import SQL_UNSUPPORTED, unsupported_error
from app.schemas.requests import SqlExecuteRequest
from app.schemas.responses import SqlExecuteResponse
from app.services import sql_executor

router = APIRouter(prefix="/execute", tags=["execute"])


@router.post("/sql", response_model=SqlExecuteResponse)
async def execute_sql(
    request: SqlExecuteRequest,
    _user_id: str = Depends(verify_internal_auth),
    settings: Settings = Depends(get_settings),
) -> SqlExecuteResponse:
    # Tibero 테넌트·조회 실패는 기존 PG 경로에 들어가기 전에 막는다(WD-75 게이트).
    err = await unsupported_error(request.tenant_id, settings, SQL_UNSUPPORTED)
    if err:
        return SqlExecuteResponse(
            success=False, rows=None, columns=None, row_count=0, execution_log="", error=err
        )
    # 커넥션은 요청의 테넌트 롤로 연다 — 의존성이 아니라 핸들러에서 여는 이유는
    # 요청 본문(tenantId)을 알아야 접속 주체가 정해지기 때문이다(app/dependencies.py 주석 참고).
    with get_connection(request.tenant_id, settings) as conn:
        return sql_executor.execute_sql(request.query, conn, request.pre_statements)
