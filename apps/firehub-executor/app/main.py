import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.config import get_settings
from app.db.connection import init_pool, close_pool, is_pool_ready
from app.routers import health, sql, python_exec, query, api_call, probe, load

logger = logging.getLogger(__name__)

_RETRY_INTERVAL_SECONDS = 5
_RETRY_MAX_INTERVAL_SECONDS = 30


async def _retry_init_pool_forever(settings) -> None:
    """기동 시 init_pool()이 실패하면(예: db 컨테이너가 아직 준비되지 않은 race condition)
    백그라운드에서 계속 재시도한다. 재시도 없이 한 번만 시도하면 실패 시 풀이 영구히
    비어 있는 상태로 남아 컨테이너 재시작 전까지 모든 쿼리가 500을 반환하게 된다."""
    interval = _RETRY_INTERVAL_SECONDS
    while not is_pool_ready():
        await asyncio.sleep(interval)
        try:
            init_pool(settings)
            logger.info("DB connection pool 재시도 초기화 성공")
            return
        except Exception:
            logger.exception("DB connection pool 재시도 초기화 실패 — %s초 후 재시도", interval)
            interval = min(interval * 2, _RETRY_MAX_INTERVAL_SECONDS)


def warn_if_api_base_url_unset(settings) -> None:
    """``EXECUTOR_API_BASE_URL`` 을 명시하지 않았으면 기동 시 경고를 한 번 남긴다(기동은 막지 않는다).

    executor 는 테넌트 저장소 기술자를 api 에서 조회해야 실행할 수 있다(WD-75). 기본값은 compose·k8s 의 api 서비스명
    주소라 배포 환경에서는 대개 맞지만, 서비스명이 다르거나 로컬 개발(api 5010)이면 조회가 실패해 모든 실행이 fail-closed
    로 실패한다. 이 executor 에는 운영 환경을 가리는 설정이 없으므로 "환경변수·.env 로 명시했는가"(``model_fields_set``)로
    판정한다. 주소 값은 싣지 않는다(자격증명이 섞일 수 있다).
    """
    if "api_base_url" not in settings.model_fields_set:
        logger.warning(
            "EXECUTOR_API_BASE_URL 이 설정되지 않아 기본 api 주소(서비스명 api)를 사용합니다 — api 서비스명이 다르거나 "
            "로컬 개발이면 저장소 기술자 조회가 실패해 모든 실행 요청이 실패합니다. 필요하면 api 주소를 명시하세요."
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    # 설정 누락은 경고만 한다 — 로컬·격리 환경 기동(health check 등)을 막지 않는다.
    warn_if_api_base_url_unset(settings)
    retry_task = None
    try:
        init_pool(settings)
    except Exception:
        # DB 없이도 기동은 허용하되(격리 환경 health check용), 원인은 로그로 남긴다.
        # 풀 초기화 실패 상태는 /health가 is_pool_ready()로 확인해 UP으로 숨기지 않는다.
        logger.exception("DB connection pool 초기화 실패 — 백그라운드 재시도 시작")
        retry_task = asyncio.create_task(_retry_init_pool_forever(settings))
    yield
    if retry_task is not None:
        retry_task.cancel()
    close_pool()


app = FastAPI(
    title="firehub-executor",
    description="FireHub pipeline sandboxed execution service",
    version="0.1.0",
    lifespan=lifespan,
)

@app.exception_handler(RequestValidationError)
async def validation_error_without_input(request: Request, exc: RequestValidationError) -> JSONResponse:
    """요청 형식 오류(422) 응답에서 각 오류의 ``input``(받은 값)을 뺀다 — 나머지 모양(``detail`` 의 type·loc·msg·ctx)은 FastAPI 기본과 같다.

    기본 처리기는 오류마다 받은 값(누락 필드면 본문 전체)을 ``input`` 으로 되돌려 준다. 그러면 형식이 틀린 Tibero Python 요청이 스크립트
    토큰(WD-78)을 응답에 그대로 싣는다. ``SecretStr`` 은 repr 만 가릴 뿐 이 경로는 막지 못한다. ``ctx`` 는 제약 값(최소·최대 등)이나 검증기의 고정 오류 문구만 담아
    받은 값을 싣지 않으므로 그대로 둔다. 모든 창구에 적용한다 — 비밀이 아닌 창구에서도 받은 값을 되돌려 줄 이유가 없다.
    """
    errors = [{k: v for k, v in err.items() if k != "input"} for err in exc.errors()]
    return JSONResponse(status_code=422, content={"detail": jsonable_encoder(errors)})


app.include_router(health.router)
app.include_router(sql.router)
app.include_router(python_exec.router)
app.include_router(query.router)
app.include_router(api_call.router)
app.include_router(probe.router)
app.include_router(load.router)
