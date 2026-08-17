from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field


class TenantScopedRequest(BaseModel):
    """테넌트 식별자를 **필수**로 요구하는 실행 요청의 공통 베이스.

    **왜 필수이고 기본값(예: 테넌트 1) 폴백이 없는가.** executor 는 요청 본문의 테넌트 id 로
    접속 롤과 스키마를 정한다. 값이 없을 때 기본 테넌트로 떨어지면 **한 테넌트의 파이프라인이
    남의 데이터에 실행된다** — 그것도 조용히. 그래서 모호하면 추측하지 않고 422 로 거부한다
    (이 이니셔티브의 확정 규율: fail-closed).

    필드명은 wire 상 ``tenantId`` (camelCase) 다 — Java ``ExecutorClient.withTenant()`` 가 보내는
    이름. ``populate_by_name=True`` 로 파이썬 쪽 이름(``tenant_id``)도 받아들여 테스트가 굳이
    camelCase 로 적지 않아도 되게 한다.

    ``gt=0`` 은 Java ``TenantPipelineRole`` 과 같은 이유의 방어선이다(0·음수는 롤 식별자로 쓸 수
    없다). ``strict=True`` 는 ``"1"``·``1.0`` 같은 값이 조용히 정수로 강제 변환되는 것을 막는다 —
    변환 결과의 문자열 표기가 달라지면 HMAC 비밀번호 파생이 Java 쪽과 어긋난다.
    """

    model_config = ConfigDict(populate_by_name=True)

    tenant_id: int = Field(alias="tenantId", gt=0, strict=True)


class SqlExecuteRequest(TenantScopedRequest):
    query: str
    params: Optional[dict] = None


class PythonExecuteRequest(TenantScopedRequest):
    script: str
    timeout: Optional[int] = None
    output_table: Optional[str] = None
    column_type_map: Optional[dict] = None


class QueryExecuteRequest(TenantScopedRequest):
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


class ApiCallExecuteRequest(TenantScopedRequest):
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
