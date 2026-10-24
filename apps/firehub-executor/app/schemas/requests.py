from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field, SecretStr, model_validator

# 슬롯 상한은 tenant.py 한 곳에서 정한다 — 여기 숫자를 따로 두면 슬롯 수가 바뀔 때 검증과 롤 이름 조립이 어긋난다.
from app.tenant import MAX_READ_SLOT


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
    # 출력 비우기(DELETE) 등 본 쿼리와 같은 트랜잭션에서 먼저 실행할 선행 문장들.
    # API 서버가 생성하는 문장만 들어오므로 validate_pre_statement 로 엄격히 검증한다.
    # alias 표기는 TenantScopedRequest.tenant_id(→ tenantId)와 같은 camelCase 규칙을 따른다.
    pre_statements: List[str] = Field(default_factory=list, alias="preStatements")
    # 요청별 시간 제한(초, 선택). 있으면 이 요청의 트랜잭션에 SET LOCAL statement_timeout 으로 적용한다(긴 SQL 용, WD-77).
    # 주의: PG statement_timeout 은 문장 단위 제한이다 — Python 실행기에서는 문장 단위 제한, db-executor 는 요청 전체 기한.
    # 선행 문장이 여러 개면 각 문장마다 이 시간이 다시 주어진다.
    # 없으면 기존 동작 그대로. 0 이하는 PG 에서 '제한 없음' 이 되어 의도와 반대라 422 로 거부한다(db-executor 도 거부).
    timeout_seconds: Optional[int] = Field(default=None, gt=0, strict=True)


class PythonExecuteRequest(TenantScopedRequest):
    script: str
    timeout: Optional[int] = None
    output_table: Optional[str] = None
    column_type_map: Optional[dict] = None
    # 실행 주체의 등급 읽기 슬롯(1~10, WD-29). **필수**이고 기본값이 없다 — 없으면 422.
    # 기본값(또는 테넌트 롤 폴백)을 두면 구 API·위조 요청이 등급 밖 데이터를 읽는다.
    # strict=True: "2"·2.5·True 같은 값이 조용히 정수로 바뀌지 않게(HMAC 메시지 표기가 Java 와 어긋난다).
    read_slot: int = Field(alias="readSlot", ge=1, le=MAX_READ_SLOT, strict=True)
    # Tibero 저장소 테넌트 전용 실행 1회용 스크립트 토큰(WD-78). api 가 서명해 Tibero 요청에만 싣고, PG 요청에는 키 자체가 없다.
    # 스크립트에 GENIA_DB_TOKEN 으로 넘겨 조회 도우미가 db-executor 슬롯 조회에 쓴다. SecretStr 이라 요청 모델을 repr·로그로 찍어도
    # 값이 나오지 않는다. Tibero 인데 없으면 실행하지 않는다(라우터).
    script_token: Optional[SecretStr] = None


# 슬롯 조회 요청 조합 거부 문구 — db-executor ExecuteController 와 같은 문장(두 실행기 같은 계약, WD-78).
SLOT_REQUIRES_STREAM = "슬롯 조회(readSlot)는 stream:true 로만 실행할 수 있습니다."
STREAM_REQUIRES_SLOT = "스트리밍 조회(stream)는 슬롯 조회(readSlot)에서만 쓸 수 있습니다."


class QueryExecuteRequest(TenantScopedRequest):
    query: str
    max_rows: int = 1000
    read_only: bool = False
    # 슬롯 스트리밍 조회(WD-78) — readSlot 과 stream:true 를 함께 보낼 때만 받는다. 슬롯 롤(resolve_read_db_url)로 끝까지
    # 읽어 NDJSON 으로 흘려보낸다(행 상한 없음, max_rows 무시). api 의 PG 경로는 쓰지 않는다 — db-executor 와 같은 계약을 위한 대칭.
    # strict=True: "2"·True 같은 값이 조용히 정수·불리언으로 바뀌지 않게(python 실행 요청의 readSlot 과 같은 규칙).
    read_slot: Optional[int] = Field(default=None, alias="readSlot", ge=1, le=MAX_READ_SLOT, strict=True)
    stream: bool = Field(default=False, strict=True)

    @model_validator(mode="after")
    def _slot_and_stream_together(self) -> "QueryExecuteRequest":
        """readSlot·stream 은 함께여야 한다 — 하나만 오면 422(fail-closed). 슬롯 조회의 길을 하나로 둔다(db-executor 와 같은 규칙)."""
        if self.read_slot is not None and not self.stream:
            raise ValueError(SLOT_REQUIRES_STREAM)
        if self.stream and self.read_slot is None:
            raise ValueError(STREAM_REQUIRES_SLOT)
        return self


class ProbeRequest(TenantScopedRequest):
    """결과 컬럼 탐지 요청 — 쿼리만 받는다(행 수 제한·읽기전용 같은 옵션이 없다)."""

    query: str


class LoadRequest(TenantScopedRequest):
    """``/execute/load`` 요청 — 행 목록을 테넌트 스키마의 테이블에 한 트랜잭션으로 적재한다(WD-77).

    db-executor(Tibero) 와 같은 모양이다: ``table``·``rows``·``column_type_map``·``timeout_seconds``.
    """

    table: str
    rows: List[dict]
    column_type_map: Optional[dict] = None
    # 요청별 시간 제한(초, 선택). Python 실행기에서는 문장 단위 제한(SET LOCAL statement_timeout — execute_values 가 페이지마다
    # 문장을 실행하므로 페이지 하나에 적용된다), db-executor 는 요청 전체 기한이다. 의도된 차이이며 계약 벡터 notes 에도 적었다.
    timeout_seconds: Optional[int] = Field(default=None, gt=0, strict=True)


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
