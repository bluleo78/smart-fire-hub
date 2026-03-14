from __future__ import annotations

from typing import List, Optional

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


class FieldMapping(BaseModel):
    source_field: str
    target_column: str
    data_type: Optional[str] = None
    date_format: Optional[str] = None
    number_format: Optional[str] = None
    source_timezone: Optional[str] = None


class PaginationConfig(BaseModel):
    type: str = "NONE"
    page_size: Optional[int] = None
    offset_param: Optional[str] = None
    limit_param: Optional[str] = None
    total_path: Optional[str] = None


class RetryConfig(BaseModel):
    max_retries: int = 3
    initial_backoff_ms: int = 1000
    max_backoff_ms: int = 30000


class ApiCallExecuteRequest(BaseModel):
    url: str
    method: str = "GET"
    headers: Optional[dict] = None
    query_params: Optional[dict] = None
    body: Optional[str] = None
    data_path: str
    field_mappings: List[FieldMapping]
    pagination: Optional[PaginationConfig] = None
    retry: Optional[RetryConfig] = None
    timeout_ms: int = 30000
    max_duration_ms: int = 3600000
    max_response_size_mb: int = 10
    output_table: str
    load_strategy: str = "REPLACE"
    column_type_map: Optional[dict] = None
    auth: Optional[dict] = None
