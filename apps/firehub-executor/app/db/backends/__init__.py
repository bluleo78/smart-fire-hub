"""DB 백엔드 추상화 패키지. Python 실행기가 직접 접속하는 백엔드는 PostgreSQL 뿐이다.

``backend_for`` 는 테넌트의 백엔드를 **api 기술자 조회로만** 정한다(요청 페이로드를 믿지 않는다, spec §3).
Tibero 는 Python 실행기가 접속하지 않는다(ODBC 경로 제거, 스펙 §3.4) — db-executor 가 전담한다.
"""
from app.config import Settings
from app.db.backends.base import BACKEND_POSTGRESQL, BACKEND_TIBERO, DbBackend
from app.db.backends.postgres import PostgresBackend
from app.db.store_client import StoreLookupError, get_store_client


def backend_for(tenant_id: int, settings: Settings) -> DbBackend:
    """테넌트 저장소 기술자에 맞는 백엔드를 돌려준다.

    조회 실패(``StoreLookupError``)는 잡지 않고 그대로 올린다(R-pg-lookup, fail-closed) — Tibero 테넌트가 조용히
    PG 에서 실행되는 것이 실패보다 나쁘다.
    """
    client = get_store_client(settings)
    desc = client.get(tenant_id)
    if desc.backend == BACKEND_POSTGRESQL:
        return PostgresBackend(settings)
    if desc.backend == BACKEND_TIBERO:
        # 접속 정보가 없으므로 만들 수 있는 백엔드가 없다. PG 로 흘러가지 않게 닫는다.
        raise StoreLookupError("Tibero 저장소는 Python 실행기가 직접 접속하지 않고 db-executor 로만 처리합니다")
    # 기술자 모델이 두 값만 허용하지만, 새 값이 생겨도 PG 로 흘러가지 않게 닫아 둔다.
    raise StoreLookupError(f"지원하지 않는 데이터셋 저장소입니다: {desc.backend}")
