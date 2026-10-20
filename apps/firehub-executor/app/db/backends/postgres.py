"""PostgreSQL 백엔드: 기존 함수에 **인자를 그대로 위임**만 하는 얇은 어댑터.

PG 경로의 동작이 바이트 단위로 같아야 하므로 로직을 추가하지 않는다. 기존 테스트가
모듈 수준 이름(``get_connection`` 등)을 patch 하므로 같은 방식으로 import 해 둔다.
"""
from typing import Any, ContextManager, Dict, List

from app.config import Settings
from app.db.backends.base import BACKEND_POSTGRESQL
from app.db.connection import get_connection
from app.schemas.responses import QueryExecuteResponse
from app.services.db_utils import insert_batch
from app.services.query_executor import execute_query


class PostgresBackend:
    """기존 PG 구현으로 위임하는 ``DbBackend`` 구현."""

    kind = BACKEND_POSTGRESQL

    def __init__(self, settings: Settings) -> None:
        # get_connection 이 설정(풀 크기·접속 정보)을 요구하므로 보관한다.
        self._settings = settings

    def connection(self, tenant_id: int) -> ContextManager[Any]:
        return get_connection(tenant_id, self._settings)

    def insert_batch(self, conn: Any, table_name: str, rows: List[Dict[str, Any]]) -> None:
        insert_batch(conn, table_name, rows)

    def execute_query(
        self, query: str, max_rows: int, read_only: bool, conn: Any, *, tenant_id: int
    ) -> QueryExecuteResponse:
        return execute_query(query, max_rows, read_only, conn, tenant_id=tenant_id)
