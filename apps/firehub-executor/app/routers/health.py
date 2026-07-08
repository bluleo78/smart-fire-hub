from fastapi import APIRouter, Response

from app.db.connection import is_pool_ready

router = APIRouter()

_active_executions: int = 0


@router.get("/health")
async def health_check(response: Response) -> dict:
    pool_ready = is_pool_ready()
    if not pool_ready:
        response.status_code = 503
    return {
        "status": "UP" if pool_ready else "DOWN",
        "active_executions": _active_executions,
    }
