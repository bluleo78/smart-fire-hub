from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel


class SqlExecuteResponse(BaseModel):
    success: bool
    rows: Optional[list] = None
    columns: Optional[list] = None
    row_count: int
    execution_log: str
    error: Optional[str] = None


class PythonExecuteResponse(BaseModel):
    success: bool
    output: str
    exit_code: int
    error: Optional[str] = None
    execution_time_ms: int
    rows_loaded: int = 0


class QueryExecuteResponse(BaseModel):
    success: bool
    query_type: str = "UNKNOWN"
    columns: List[str] = []
    rows: List[Dict[str, Any]] = []
    row_count: int = 0
    affected_rows: int = 0
    execution_time_ms: int = 0
    truncated: bool = False
    error: Optional[str] = None


class ApiCallExecuteResponse(BaseModel):
    success: bool
    rows_loaded: int = 0
    total_pages: int = 0
    execution_log: str = ""
    error: Optional[str] = None
    execution_time_ms: int = 0
