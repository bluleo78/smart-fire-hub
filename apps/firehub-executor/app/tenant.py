"""테넌트별 DB 롤·비밀번호·스키마 이름을 파생하는 **Python 쪽 유일한 조립점**.

executor 는 사용자가 작성한 SQL·Python 을 DB 에 직접 실행하는 프로세스다. 그 경로에는
Java 쪽 ``SqlValidator`` 같은 문장 검증기가 **없다**(#270 이후로도 마찬가지). 따라서 테넌트
격리를 실제로 강제할 수 있는 수단은 **접속에 쓰는 DB 롤의 권한**뿐이다 — 이 모듈이 그 롤
이름과 비밀번호를 만든다.

**왜 Java 와 같은 규약을 두 번 구현하는가.**
executor 는 별도 프로세스이므로 Java 의 ``TenantPipelineRole`` 을 호출할 수 없다. 그래서 같은
규약(``pipeline_executor_t{id}`` / HMAC-SHA256 파생)을 여기서 독립적으로 구현한다. 두 구현이
갈라지면 **비밀번호가 어긋나 DB 인증이 실패**하고, 요청은 즉시 에러로 끝난다. 즉 규약 드리프트는
"조용히 남의 테넌트에 붙는" 실패가 아니라 **큰 소리로 나는 인증 실패**로 드러난다 — 이것이 이
이중 구현의 안전판이며, 의도된 설계다. **그러므로 인증 실패를 무마하는 폴백(공유 롤로 재시도 등)을
절대 추가하지 말 것.** 그 폴백을 넣는 순간 드리프트가 다시 조용해지고, 격리는 종이 위에만 남는다.

Java 쪽 대응 코드:
``apps/firehub-api/src/main/java/com/smartfirehub/global/tenant/TenantPipelineRole.java``
(롤 이름·비밀번호), ``.../DataSchema.java`` (스키마명).
"""
from __future__ import annotations

import hashlib
import hmac
import re
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # 런타임 순환 임포트를 피하려고 타입 검사에서만 불러온다.
    from app.config import Settings

# 오늘의 물리 스키마명. Java 쪽 DataSchema.PHYSICAL_SCHEMA 와 같은 값이며,
# P3-b2 에서 `data_t{tenantId}` 로 개명될 때 두 곳을 함께 바꾼다.
_PHYSICAL_SCHEMA = "data"

# 테넌트별 파이프라인 실행 롤 이름의 접두사. Java 쪽 TenantPipelineRole.roleName 과 동일.
_ROLE_PREFIX = "pipeline_executor_t"

# HMAC 다이제스트를 hex 로 표기했을 때 비밀번호로 잘라 쓰는 길이(문자 수).
# Java 쪽 TenantPipelineRole.PASSWORD_LENGTH 와 반드시 같아야 한다.
_PASSWORD_LENGTH = 32

# 인용 없이 SQL 에 끼워 넣어도 안전한 식별자 모양. resolve_schema 의 반환값이 `SET search_path`
# 문장에 문자열 보간으로 들어가므로, 조립점에서 모양을 한 번 확인한다. 오늘은 상수라 항상
# 통과하지만, P3-b2 에서 이름이 파생값(`data_t{id}`)이 되면 이 가드가 실제 방어선이 된다.
_SAFE_IDENTIFIER = re.compile(r"[a-z_][a-z0-9_]*")


class TenantResolutionError(ValueError):
    """테넌트 식별자가 없거나 유효하지 않을 때. 기본 테넌트로 폴백하지 않는다."""


def _require_tenant_id(tenant_id: object) -> int:
    """테넌트 id 를 검증해 정수로 정규화한다 — 모호하면 **거부**(fail-closed).

    왜 이렇게까지 엄격한가: 여기서 통과한 값이 그대로 롤 이름·비밀번호 파생의 입력이 된다.
    ``1`` 과 ``1.0`` 이 서로 다른 문자열이 되면(Java 는 ``Long.toString`` 을 쓴다) 비밀번호가
    어긋나고, ``True`` 는 파이썬에서 ``int`` 의 서브클래스라 자칫 테넌트 1 로 조용히 해석된다.
    음수·0 은 Java 쪽과 같은 이유(식별자에 하이픈 불가)로 거부한다.
    """
    if isinstance(tenant_id, bool):
        raise TenantResolutionError(f"tenantId 가 bool 입니다: {tenant_id!r}")
    if not isinstance(tenant_id, int):
        raise TenantResolutionError(f"tenantId 는 정수여야 합니다: {tenant_id!r}")
    if tenant_id <= 0:
        raise TenantResolutionError(f"tenantId 는 양수여야 합니다: {tenant_id}")
    return int(tenant_id)


def resolve_schema(tenant_id: int) -> str:
    """테넌트의 데이터 스키마 식별자(인용 없음)를 돌려준다.

    **테넌트 id 를 실제로 요구하면서 반환값에 쓰지 않는 이유**는 Java 쪽 ``DataSchema.current()``
    와 같다 — 오늘 물리 스키마는 ``data`` 하나뿐이지만, P3-b2 가 ``data_t{tenantId}`` 로 개명하는
    순간 테넌트 id 없이는 답을 만들 수 없다. 지금부터 요구해 두면 테넌트를 모르는 경로가 그때
    한꺼번에 터지지 않고 지금 하나씩 드러난다.
    """
    _require_tenant_id(tenant_id)
    schema = _PHYSICAL_SCHEMA
    if not _SAFE_IDENTIFIER.fullmatch(schema):
        raise TenantResolutionError(f"스키마명이 안전한 식별자 모양이 아닙니다: {schema!r}")
    return schema


def resolve_role(tenant_id: int) -> str:
    """테넌트의 파이프라인 실행 DB 롤 이름 — ``pipeline_executor_t1`` 형태."""
    return f"{_ROLE_PREFIX}{_require_tenant_id(tenant_id)}"


def resolve_password(tenant_id: int, secret: str) -> str:
    """테넌트 롤 비밀번호를 ``secret`` 으로부터 결정적으로 파생한다(32자 소문자 hex).

    ``secret`` 을 HMAC 키로, 테넌트 id 의 10진 문자열을 메시지로 쓴다 — Java 쪽
    ``TenantPipelineRole.password`` 와 **바이트 단위로 동일**해야 하므로 인코딩(UTF-8),
    메시지(``str(tenant_id)``), 자르는 길이(32)를 임의로 바꾸지 말 것.
    단순 해시 연결이 아니라 HMAC 을 쓰는 이유는 길이 확장 공격을 피하고, secret 이 유출되지
    않는 한 다른 테넌트의 비밀번호를 유추할 수 없게 하기 위해서다.
    """
    normalized = _require_tenant_id(tenant_id)
    if not secret:
        # 비밀값이 비어 있으면 모든 테넌트의 비밀번호가 예측 가능해진다 → 조용히 넘기지 않는다.
        raise TenantResolutionError("롤 비밀번호 파생 secret 이 비어 있습니다")
    digest = hmac.new(
        secret.encode("utf-8"), str(normalized).encode("utf-8"), hashlib.sha256
    ).hexdigest()
    return digest[:_PASSWORD_LENGTH]


def resolve_db_url(tenant_id: int, settings: "Settings") -> str:
    """사용자 Python 스크립트에 넘길 ``DB_URL`` 을 테넌트 롤 자격증명으로 조립한다.

    nsjail 경로와 비nsjail 경로가 **같은** 함수를 쓰도록 여기 한 곳에 둔다. 두 경로가 각자
    문자열을 이어 붙이면 nsjail 설정에 따라 접속 주체가 조용히 갈릴 수 있다(#270 의 주석이
    "동작이 일치한다"고 보장하는 지점이다).
    """
    role = resolve_role(tenant_id)
    password = resolve_password(tenant_id, settings.role_password_secret)
    return (
        f"postgresql://{role}:{password}"
        f"@{settings.db_host}:{settings.db_port}/{settings.db_name}"
    )
