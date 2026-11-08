"""executor 가 DB 종류에 상관없이 쓰는 백엔드 계약.

호출부가 PG·Tibero 를 직접 알지 않고 이 계약만 보게 해, 백엔드 추가가 라우트 수정으로
번지지 않게 한다.
"""
from typing import Any, ContextManager, Dict, List, Optional, Protocol

from app.config import QUERY_STATEMENT_TIMEOUT_DEFAULT_SECONDS

from app.schemas.responses import QueryExecuteResponse

# api 기술자(StoreDescriptor.backend)의 값과 같은 문자열이다.
BACKEND_POSTGRESQL = "POSTGRESQL"
BACKEND_TIBERO = "TIBERO"


class DbBackend(Protocol):
    """테넌트 저장소 접근 계약: 커넥션 대여, 일괄 INSERT, 분석 쿼리 실행."""

    kind: str

    def connection(self, tenant_id: int) -> ContextManager[Any]:
        """테넌트의 계정으로 접속한 커넥션을 컨텍스트로 빌려준다."""
        ...

    def insert_batch(self, conn: Any, table_name: str, rows: List[Dict[str, Any]]) -> None:
        """``rows`` 를 ``table_name`` 에 일괄 삽입한다."""
        ...

    def execute_query(
        self,
        query: str,
        max_rows: int,
        read_only: bool,
        conn: Any,
        *,
        tenant_id: int,
        timeout_seconds: Optional[int] = None,
        default_timeout_seconds: int = QUERY_STATEMENT_TIMEOUT_DEFAULT_SECONDS,
    ) -> QueryExecuteResponse:
        """분석 쿼리를 실행해 응답 모델로 돌려준다. 문장 제한 인자는 서비스 함수(``query_executor.execute_query``)와 같다(WD-641)."""
        ...
