from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import get_settings
from app.db.connection import init_pool, close_pool
from app.routers import health, sql, python_exec, query


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    try:
        init_pool(settings)
    except Exception:
        # Allow startup even without DB (useful for health checks in isolated env)
        pass
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
