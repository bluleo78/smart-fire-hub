from fastapi import APIRouter, Depends
from starlette.concurrency import run_in_threadpool

from app.config import Settings, get_settings
from app.middleware.auth import verify_internal_auth
from app.routers.store_gate import backend_of, loader_for, require_output_table
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
    # 저장소 백엔드는 api 기술자 조회로만 정한다 — 조회 실패는 PG 로 폴백하지 않고 실패 응답으로 끝낸다(WD-75, fail-closed).
    backend, err = await backend_of(request.tenant_id, settings)
    if err:
        return python_executor.rejected_response(err)
    loader = loader_for(backend, settings)
    if loader is not None:
        # Tibero(WD-78): PG 슬롯 롤·PG 연결을 쓰지 않는다. 스크립트는 조회 도우미로 db-executor 슬롯 조회를 부르고(토큰 필요),
        # 출력은 api 가 만든 스테이징(STG_<24HEX>)으로 db-executor 원격 적재한다. 토큰·스테이징 이름이 맞지 않으면 띄우기 전에 막는다.
        token = request.script_token.get_secret_value() if request.script_token is not None else ""
        if not token.strip():
            return python_executor.rejected_response(python_executor.TIBERO_TOKEN_MISSING)
        err = require_output_table(backend, request.output_table)
        if err:
            return python_executor.rejected_response(err)
        # 스크립트 실행·원격 적재 모두 블로킹이라 스레드풀에서 돌린다(api_call Tibero 경로와 같다).
        return await run_in_threadpool(
            lambda: python_executor.execute_python_tibero(
                request.script,
                request.timeout,
                settings,
                tenant_id=request.tenant_id,
                script_token=token,
                loader=loader,
                output_table=request.output_table,
                column_type_map=request.column_type_map,
            )
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
