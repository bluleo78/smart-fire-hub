"""``resolve_schema`` 가 Java ``DataSchema.current()`` 와 같은 값을 내는지 고정벡터로 대조한다.

**이 표는 apps/firehub-api/src/test/java/com/smartfirehub/tenant/TenantSchemaVectorConformanceTest.java
와 짝이다. 한쪽만 고치지 말 것** — 스키마 파생 규약이 바뀌면 두 표를 함께 갱신하고, 둘 다 테스트를
다시 통과시켜야 한다.

**왜 Java 를 호출하지 않고 값을 복제하는가**: executor 는 별도 프로세스이고 Java 코드를 부를 수
없다. 스키마명을 요청 페이로드로 받으면 클라이언트 제공 식별자를 신뢰하게 되는 보안 후퇴다
(기존 ``resolve_role``/``resolve_password`` 가 같은 이유로 이미 미러링이다 — ``app/tenant.py``
모듈 Javadoc 참조). 드리프트 방어는 이 고정벡터 표를 양 언어에 같은 값으로 두고 대조하는
것이다.
"""
from __future__ import annotations

import pytest

from app.tenant import resolve_schema

# Java DataSchema.current() 의 파생 규약(테넌트 1 → data, 그 외 → data_t{id})을 그대로 옮긴
# 고정벡터. TenantSchemaVectorConformanceTest 의 표와 완전히 같은 값이어야 한다.
SCHEMA_VECTORS = [(1, "data"), (2, "data_t2"), (7, "data_t7"), (43259, "data_t43259")]


@pytest.mark.parametrize("tenant_id,expected", SCHEMA_VECTORS)
def test_resolve_schema_vectors(tenant_id: int, expected: str):
    assert resolve_schema(tenant_id) == expected
