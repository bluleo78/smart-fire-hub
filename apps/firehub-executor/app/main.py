import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import get_settings
from app.db.connection import init_pool, close_pool
from app.routers import health, sql, python_exec, query, api_call

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    try:
        init_pool(settings)
    except Exception:
        # DB 없이도 기동은 허용하되(격리 환경 health check용), 원인은 로그로 남긴다.
        # 풀 초기화 실패 상태는 /health가 is_pool_ready()로 확인해 UP으로 숨기지 않는다.
        logger.exception("DB connection pool 초기화 실패 — DB 없이 기동")
    yield
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
