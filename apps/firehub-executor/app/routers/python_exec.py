from fastapi import APIRouter, Depends

from app.config import Settings, get_settings
from app.middleware.auth import verify_internal_auth
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
    return python_executor.execute_python(request.script, request.timeout, settings)
