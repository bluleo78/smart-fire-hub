from fastapi import APIRouter, Depends

from app.dependencies import get_db_connection, verify_internal_auth
from app.schemas.requests import SqlExecuteRequest
from app.schemas.responses import SqlExecuteResponse
from app.services import sql_executor

router = APIRouter(prefix="/execute", tags=["execute"])


@router.post("/sql", response_model=SqlExecuteResponse)
async def execute_sql(
    request: SqlExecuteRequest,
    _user_id: str = Depends(verify_internal_auth),
    conn=Depends(get_db_connection),
) -> SqlExecuteResponse:
    return sql_executor.execute_sql(request.query, conn)
