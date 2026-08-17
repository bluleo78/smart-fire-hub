from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.db.connection import get_connection
from app.dependencies import verify_internal_auth
from app.schemas.requests import QueryExecuteRequest
from app.schemas.responses import QueryExecuteResponse
from app.services import query_executor

router = APIRouter(prefix="/execute", tags=["execute"])


@router.post("/query", response_model=QueryExecuteResponse)
async def execute_query_endpoint(
    request: QueryExecuteRequest,
    user_id: str = Depends(verify_internal_auth),
    settings: Settings = Depends(get_settings),
) -> QueryExecuteResponse:
    with get_connection(request.tenant_id, settings) as conn:
        return query_executor.execute_query(
            request.query,
            request.max_rows,
            request.read_only,
            conn,
            tenant_id=request.tenant_id,
        )
