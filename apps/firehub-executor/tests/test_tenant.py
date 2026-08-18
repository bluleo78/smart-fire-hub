"""테넌트별 롤·비밀번호·스키마 파생 규약과 fail-closed 거부를 고정하는 테스트.

가장 중요한 것은 **언어 간 합의**다: Java 쪽 ``TenantPipelineRole.password`` 가 DB 롤의 비밀번호를
정하고, 이 Python 구현이 접속에 쓸 비밀번호를 독립적으로 파생한다. 둘이 어긋나면 executor 는
그냥 인증에 실패한다 — 아래 상수는 **실제로 Java 코드를 실행해서(jshell)** 얻은 값이며, 손으로
계산한 값이 아니다(손계산은 내 구현의 버그와 함께 틀릴 수 있다).
"""
from __future__ import annotations

import pytest

from app.config import Settings
from app.tenant import (
    TenantResolutionError,
    resolve_db_url,
    resolve_password,
    resolve_role,
    resolve_schema,
)
from tests.conftest import TEST_ROLE_SECRET

# jshell 로 Java 쪽 TenantPipelineRole.password(tenantId, "test-tenant-pipeline-secret") 를
# 그대로 실행해 얻은 기대값. 이 secret 은 Java 쪽 application-test.yml 의
# app.pipeline.role-password-secret 과 같은 값이라, test DB 의 pipeline_executor_t1 롤에
# 실제로 걸려 있는 비밀번호이기도 하다(라이브 인증으로 확인됨).
JAVA_DERIVED_PASSWORDS = {
    1: "ed3fe7de81f2c2ee70179ca788100837",
    2: "10c75c4b795e800a0381177d7352f8d4",
    42: "3f452492b5aaa69522dbcd1bbbce0db1",
}


# ---------------------------------------------------------------------------
# 롤 이름 / 스키마 규약
# ---------------------------------------------------------------------------

def test_role_name_convention():
    assert resolve_role(1) == "pipeline_executor_t1"
    assert resolve_role(42) == "pipeline_executor_t42"


def test_schema_is_tenant_specific_since_p3b2():
    """P3-b2 부터 테넌트 1 만 레거시 data 를 유지하고, 그 외는 data_t{id} 를 받는다.

    상세 고정벡터(1, 2, 7, 43259)는 tests/test_tenant_schema_vectors.py 가 별도로 고정한다 —
    이 테스트는 "더 이상 전 테넌트가 같은 스키마를 공유하지 않는다"는 계약 자체만 못박는다.
    """
    assert resolve_schema(1) == "data"
    assert resolve_schema(2) == "data_t2"


# ---------------------------------------------------------------------------
# 언어 간 비밀번호 합의 — 이 밴드에서 가장 중요한 단언
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("tenant_id,expected", sorted(JAVA_DERIVED_PASSWORDS.items()))
def test_password_matches_java_derivation(tenant_id: int, expected: str):
    assert resolve_password(tenant_id, TEST_ROLE_SECRET) == expected


def test_password_differs_per_tenant_and_per_secret():
    """테넌트 하나를 알아도 다른 테넌트의 비밀번호를 유추할 수 없어야 한다."""
    assert resolve_password(1, TEST_ROLE_SECRET) != resolve_password(2, TEST_ROLE_SECRET)
    assert resolve_password(1, TEST_ROLE_SECRET) != resolve_password(1, "other-secret")


def test_password_length_is_pinned():
    # Java 쪽 PASSWORD_LENGTH 와 같아야 한다 — 길이가 갈리면 인증이 실패한다.
    assert len(resolve_password(1, TEST_ROLE_SECRET)) == 32


# ---------------------------------------------------------------------------
# fail-closed — 모호하면 거부한다(기본 테넌트 폴백 없음)
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("bad", [0, -1, True, False, "1", 1.0, None])
def test_invalid_tenant_id_rejected(bad):
    for fn in (resolve_role, resolve_schema):
        with pytest.raises(TenantResolutionError):
            fn(bad)
    with pytest.raises(TenantResolutionError):
        resolve_password(bad, TEST_ROLE_SECRET)


def test_empty_secret_rejected():
    """secret 이 비면 모든 테넌트 비밀번호가 예측 가능해진다 → 조용히 넘기지 않는다."""
    with pytest.raises(TenantResolutionError):
        resolve_password(1, "")


# ---------------------------------------------------------------------------
# DB_URL 조립 — 공유 롤 자격증명이 새어 나가지 않아야 한다
# ---------------------------------------------------------------------------

def test_db_url_uses_tenant_role_not_shared_role():
    settings = Settings(
        db_host="db",
        db_port=5432,
        db_name="firehub",
        db_user="pipeline_executor",
        db_password="shared-role-password",
        internal_service_token="t",
        role_password_secret=TEST_ROLE_SECRET,
    )

    url = resolve_db_url(2, settings)

    assert url == (
        "postgresql://pipeline_executor_t2:"
        f"{JAVA_DERIVED_PASSWORDS[2]}@db:5432/firehub"
    )
    # 공유 롤 이름·비밀번호가 사용자 스크립트로 새면 이 밴드의 통제가 무의미해진다.
    assert "shared-role-password" not in url
    assert "pipeline_executor:" not in url
