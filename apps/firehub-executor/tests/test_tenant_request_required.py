"""요청 본문의 ``tenantId`` 가 **필수**임을 고정하는 테스트.

왜 별도 파일인가: 이 단언은 특정 실행기의 동작이 아니라 **네 엔드포인트 전체에 걸친 규율**
(fail-closed)이다. 하나라도 기본 테넌트로 폴백하면 그 경로로 크로스테넌트 실행이 성립한다.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.schemas.requests import (
    ApiCallExecuteRequest,
    PythonExecuteRequest,
    QueryExecuteRequest,
    SqlExecuteRequest,
)

# tenantId 를 뺀 최소 요청 본문 — 다른 필드는 모두 유효하다.
# (즉 422 의 원인은 tenantId 누락 하나뿐이다.)
BODIES_WITHOUT_TENANT = {
    "/execute/sql": {"query": "SELECT 1"},
    "/execute/query": {"query": "SELECT 1", "max_rows": 10, "read_only": True},
    "/execute/python": {"script": "print(1)"},
    "/execute/api-call": {
        "url": "http://public.example.com/api",
        "data_path": "$.items",
        "field_mappings": [{"source_field": "id", "target_column": "id"}],
        "output_table": "t",
    },
}


def test_camelcase_wire_name_is_accepted():
    """Java ``ExecutorClient.withTenant()`` 가 보내는 이름(``tenantId``)이 실제로 받아들여져야 한다.

    **왜 이 양성 테스트가 반드시 있어야 하는가.** 아래 거부 테스트들만 있으면 alias 가 깨져도
    (예: ``tenantID``) 필드가 그냥 "없는" 것이 되어 여전히 422 가 나므로 전부 초록이다. 그 상태의
    prod 는 **네 엔드포인트가 모든 요청을 422 로 거부**한다. 그래서 수용 방향을 따로 못 박는다.
    """
    payloads = {
        SqlExecuteRequest: {"query": "SELECT 1"},
        QueryExecuteRequest: {"query": "SELECT 1"},
        PythonExecuteRequest: {"script": "pass"},
        ApiCallExecuteRequest: {
            "url": "http://public.example.com/api",
            "data_path": "$.items",
            "field_mappings": [{"source_field": "id", "target_column": "id"}],
            "output_table": "t",
        },
    }
    for model, body in payloads.items():
        parsed = model.model_validate({**body, "tenantId": 3})
        assert parsed.tenant_id == 3, model.__name__


@pytest.mark.parametrize("path", sorted(BODIES_WITHOUT_TENANT))
def test_missing_tenant_id_is_rejected(path: str, test_client: TestClient, mock_auth_header: dict):
    response = test_client.post(path, headers=mock_auth_header, json=BODIES_WITHOUT_TENANT[path])

    assert response.status_code == 422, response.text
    # 거부 사유가 tenantId 여야 한다 — 다른 필드 때문에 우연히 422 가 난 것이면 이 단언이 잡는다.
    assert "tenantId" in response.text


@pytest.mark.parametrize("bad", [0, -1, "1", 1.5])
def test_non_positive_or_non_integer_tenant_id_is_rejected(
    bad, test_client: TestClient, mock_auth_header: dict
):
    """0·음수·문자열·소수는 거부한다. 특히 ``"1"``·``1.5`` 를 조용히 정수로 강제 변환하면
    HMAC 파생 입력의 문자열 표기가 Java 쪽과 달라질 수 있다."""
    response = test_client.post(
        "/execute/sql",
        headers=mock_auth_header,
        json={"query": "SELECT 1", "tenantId": bad},
    )

    assert response.status_code == 422, response.text
