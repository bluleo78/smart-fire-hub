from fastapi import APIRouter, Depends

from app.dependencies import get_db_connection, verify_internal_auth
from app.schemas.requests import QueryExecuteRequest
from app.schemas.responses import QueryExecuteResponse
from app.services import query_executor

router = APIRouter(prefix="/execute", tags=["execute"])


@router.post("/query", response_model=QueryExecuteResponse)
async def execute_query_endpoint(
    request: QueryExecuteRequest,
    user_id: str = Depends(verify_internal_auth),
    conn=Depends(get_db_connection),
) -> QueryExecuteResponse:
    return query_executor.execute_query(
        request.query, request.max_rows, request.read_only, conn
    )
