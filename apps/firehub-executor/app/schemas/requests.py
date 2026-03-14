from __future__ import annotations

from typing import Optional

from pydantic import BaseModel


class SqlExecuteRequest(BaseModel):
    query: str
    params: Optional[dict] = None


class PythonExecuteRequest(BaseModel):
    script: str
    timeout: Optional[int] = None


class QueryExecuteRequest(BaseModel):
    query: str
    max_rows: int = 1000
    read_only: bool = False
