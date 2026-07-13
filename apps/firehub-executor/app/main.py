import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import get_settings
from app.db.connection import init_pool, close_pool, is_pool_ready
from app.routers import health, sql, python_exec, query, api_call

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


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
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

app.include_router(health.router)
app.include_router(sql.router)
app.include_router(python_exec.router)
app.include_router(query.router)
app.include_router(api_call.router)
