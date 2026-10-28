from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.db.connection import run_with_tenant_connection
from app.dependencies import verify_internal_auth
from app.routers.store_gate import PROBE_UNSUPPORTED, unsupported_error
from app.schemas.requests import ProbeRequest
from app.schemas.responses import ProbeResponse, db_error_of
from app.services import probe_executor

router = APIRouter(prefix="/execute", tags=["execute"])


@router.post("/probe", response_model=ProbeResponse)
async def execute_probe_endpoint(
    request: ProbeRequest,
    _user_id: str = Depends(verify_internal_auth),
    settings: Settings = Depends(get_settings),
) -> ProbeResponse:
    # Tibero 테넌트·조회 실패는 PG 연결을 열기 전에 막는다(/execute/query 와 같은 WD-75 게이트).
    err = await unsupported_error(request.tenant_id, settings, PROBE_UNSUPPORTED)
    if err:
        return ProbeResponse(success=False, error=err, db_error=db_error_of(err))
    # 요청의 테넌트 롤로 연다 — 컬럼 탐지도 그 테넌트가 볼 수 있는 객체만 대상이어야 한다.
    # 커넥션 대기와 탐지 쿼리가 블로킹이라 대여부터 반납까지 스레드풀에서 돈다(WD-265, run_with_tenant_connection).
    return await run_with_tenant_connection(
        request.tenant_id, settings, lambda conn: probe_executor.execute_probe(request.query, conn)
    )
