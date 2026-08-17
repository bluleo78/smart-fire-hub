from app.config import Settings, get_settings
from app.middleware.auth import verify_internal_auth  # re-export for routers


def get_cached_settings() -> Settings:
    return get_settings()


# NOTE: 테넌트를 모르는 `get_db_connection()` 의존성은 **의도적으로 제거했다**.
# FastAPI 의존성은 요청 본문의 tenantId 를 볼 수 없으므로, 여기서 커넥션을 만들면 반드시
# 공유 롤·고정 스키마로 접속하게 된다 — 그것이 정확히 이 밴드가 없애려는 경로다.
# 각 라우터가 요청의 tenant_id 로 `app.db.connection.get_connection(tenant_id, settings)` 을
# 직접 열고 닫는다.
