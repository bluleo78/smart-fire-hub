import anyio
from fastapi import APIRouter, Depends
from fastapi.responses import Response, StreamingResponse
from starlette.concurrency import iterate_in_threadpool, run_in_threadpool

from app.config import Settings, get_settings
from app.db.connection import run_with_tenant_connection
from app.dependencies import verify_internal_auth
from app.routers.store_gate import QUERY_UNSUPPORTED, unsupported_error
from app.schemas.requests import QueryExecuteRequest
from app.schemas.responses import QueryExecuteResponse, db_error_of
from app.services import query_executor
from app.services.query_executor import (
    NDJSON_MEDIA_TYPE,
    STREAM_READ_ONLY_REQUIRED,
    stream_failure_line,
)

router = APIRouter(prefix="/execute", tags=["execute"])


@router.post("/query", response_model=QueryExecuteResponse)
async def execute_query_endpoint(
    request: QueryExecuteRequest,
    user_id: str = Depends(verify_internal_auth),
    settings: Settings = Depends(get_settings),
) -> QueryExecuteResponse:
    # Tibero 테넌트·조회 실패는 기존 PG 경로에 들어가기 전에 막는다(WD-75 게이트).
    err = await unsupported_error(request.tenant_id, settings, QUERY_UNSUPPORTED)
    if request.stream:
        # 슬롯 스트리밍(WD-78): 요청 모델이 readSlot 이 함께 있음을 이미 확인했다. 실행 전 실패도 NDJSON 실패 줄 하나로 낸다.
        # read_only:true 는 db-executor 와 같이 요구한다(두 실행기 같은 계약).
        if request.read_only is not True:
            return Response(content=stream_failure_line(STREAM_READ_ONLY_REQUIRED), media_type=NDJSON_MEDIA_TYPE)
        if err:
            return Response(content=stream_failure_line(err), media_type=NDJSON_MEDIA_TYPE)
        return StreamingResponse(
            _closing_stream(
                query_executor.stream_slot_query(
                    request.query,
                    tenant_id=request.tenant_id,
                    read_slot=request.read_slot,
                    settings=settings,
                )
            ),
            media_type=NDJSON_MEDIA_TYPE,
        )
    if err:
        return QueryExecuteResponse(success=False, error=err, db_error=db_error_of(err))
    # 비스트리밍 경로: 커넥션 대기와 쿼리 실행이 블로킹이라 대여부터 반납까지 스레드풀에서 돈다(WD-265, run_with_tenant_connection).
    return await run_with_tenant_connection(
        request.tenant_id,
        settings,
        lambda conn: query_executor.execute_query(
            request.query,
            request.max_rows,
            request.read_only,
            conn,
            tenant_id=request.tenant_id,
        ),
    )


async def _closing_stream(gen):
    """동기 NDJSON 제너레이터를 스레드풀에서 흘려보내고, 끝나거나 끊기면(취소) 반드시 닫는다.

    Starlette 는 받는 쪽이 끊으면 스트림 작업을 취소한다. 동기 제너레이터를 그대로 넘기면 iterate_in_threadpool 이 취소된 채 남아
    제너레이터가 닫히지 않고(GC 때까지) 서버 측 커서·슬롯 롤 연결을 쥔다. 여기서 finally 로 gen.close() 를 불러 커서 close·롤백·연결
    close 를 바로 하게 한다. 취소 중에도 정리가 끝나도록 정리는 취소를 막은(shield) 스레드에서 한다.
    """
    try:
        async for chunk in iterate_in_threadpool(gen):
            yield chunk
    finally:
        with anyio.CancelScope(shield=True):
            await run_in_threadpool(gen.close)
