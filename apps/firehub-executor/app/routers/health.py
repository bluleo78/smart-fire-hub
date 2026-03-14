from fastapi import APIRouter

router = APIRouter()

_active_executions: int = 0


@router.get("/health")
async def health_check() -> dict:
    return {"status": "UP", "active_executions": _active_executions}
