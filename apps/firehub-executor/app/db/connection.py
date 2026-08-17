import logging
import threading
from collections import OrderedDict
from contextlib import contextmanager
from typing import Dict, Generator, Optional

from psycopg2 import pool as pg_pool

from app.config import Settings
from app.tenant import resolve_password, resolve_role, resolve_schema

logger = logging.getLogger(__name__)

# 레거시 공유 롤(pipeline_executor) 풀.
# **실행 경로는 더 이상 이 풀을 쓰지 않는다** — readiness 프로브(/health)가 "DB 에 붙을 수 있는가"
# 를 확인하는 데만 남겨 둔다. 레거시 롤은 dev·prod 의 이전 경로가 아직 쓰고 있어 이 밴드에서
# 은퇴시키지 않는다(P3-b1 R5). 이 풀과 롤은 P3-b2 에서 함께 사라진다.
_pool: Optional[pg_pool.ThreadedConnectionPool] = None

# 테넌트별 커넥션 풀. LRU 순서를 유지하려고 OrderedDict 를 쓴다(맨 앞 = 가장 오래 안 쓴 것).
_tenant_pools: "OrderedDict[int, pg_pool.ThreadedConnectionPool]" = OrderedDict()

# 각 테넌트 풀에서 현재 밖으로 나가 있는(체크아웃된) 커넥션 수. LRU 축출 시 **사용 중인 풀을
# 닫아 실행 중인 쿼리를 죽이지 않기** 위해 필요하다.
_tenant_pool_in_use: Dict[int, int] = {}

# FastAPI 의 동기 핸들러는 스레드풀에서 돈다 → 풀 생성·축출이 경쟁한다. 재진입 락으로 감싼다.
_tenant_lock = threading.RLock()


def init_pool(settings: Settings) -> None:
    global _pool
    _pool = pg_pool.ThreadedConnectionPool(
        minconn=settings.db_pool_min,
        maxconn=settings.db_pool_max,
        host=settings.db_host,
        port=settings.db_port,
        dbname=settings.db_name,
        user=settings.db_user,
        password=settings.db_password,
    )


def close_pool() -> None:
    global _pool
    if _pool is not None:
        _pool.closeall()
        _pool = None
    close_tenant_pools()


def close_tenant_pools() -> None:
    """모든 테넌트 풀을 닫는다(프로세스 종료 경로)."""
    with _tenant_lock:
        for tenant_id, pool in _tenant_pools.items():
            try:
                pool.closeall()
            except Exception:
                logger.exception("테넌트 %s 커넥션 풀 종료 실패", tenant_id)
        _tenant_pools.clear()
        _tenant_pool_in_use.clear()


def is_pool_ready() -> bool:
    """커넥션 풀 초기화 여부. health check가 실제 DB 연결 가능 상태를 반영하도록 함."""
    return _pool is not None


def _is_conn_alive(conn) -> bool:
    """Check if a pooled connection is still usable."""
    try:
        if conn.closed:
            return False
        conn.rollback()
        with conn.cursor() as cur:
            cur.execute("SELECT 1")
        return True
    except Exception:
        return False


def _create_tenant_pool(
    tenant_id: int, settings: Settings
) -> pg_pool.ThreadedConnectionPool:
    """테넌트 전용 커넥션 풀을 만든다 — 접속 주체는 그 테넌트의 롤이다.

    **왜 공유 롤 + ``SET ROLE`` 이 아니라 별도 자격증명인가**(P3-b1 R2). 사용자 SQL·Python 이
    ``RESET ROLE`` 로 스스로 되돌릴 수 있고, 이 경로에는 그것을 막을 문장 검증기가 없다. 접속
    자체를 테넌트 롤로 하면 되돌릴 대상이 없다.

    비밀번호는 저장하지 않고 매번 파생한다 — Java 쪽 Flyway 콜백이 같은 규약으로
    ``ALTER ROLE ... PASSWORD`` 를 걸어 두므로 동기화 지점이 애초에 존재하지 않는다.
    규약이 갈라지면 여기서 **인증 실패**로 즉시 드러난다(조용한 오접속이 아니다).
    """
    return pg_pool.ThreadedConnectionPool(
        minconn=settings.tenant_pool_min,
        maxconn=settings.tenant_pool_max,
        host=settings.db_host,
        port=settings.db_port,
        dbname=settings.db_name,
        user=resolve_role(tenant_id),
        password=resolve_password(tenant_id, settings.role_password_secret),
    )


def _evict_if_needed(settings: Settings, serving_tenant_id: int) -> None:
    """풀 개수가 상한을 넘으면 **사용 중이 아닌** 가장 오래된 풀부터 닫는다.

    사용 중(체크아웃 커넥션 > 0)인 풀은 건너뛴다 — 실행 중인 파이프라인의 커넥션을 닫으면
    무관한 실패가 된다. 축출할 수 있는 풀이 하나도 없으면 상한을 일시적으로 넘기고 경고만
    남긴다: 상한은 커넥션 고갈을 늦추기 위한 것이지, 진행 중인 작업을 죽여서 지킬 값이 아니다.

    ``serving_tenant_id`` 는 **지금 이 호출을 위해 막 확보한 풀**이라 반드시 제외한다. 체크아웃
    카운트는 커넥션을 실제로 빌린 뒤에 올라가므로, 이 시점의 그 풀은 ``in_use == 0`` 으로 보여
    (다른 풀들이 모두 바쁠 때) 자기 자신이 축출 대상으로 뽑힌다 → 방금 닫힌 풀을 호출부에
    돌려주게 되고, 성공해야 할 요청이 PoolError 로 실패한다.
    """
    while len(_tenant_pools) > settings.tenant_pool_limit:
        victim = next(
            (
                tid
                for tid in _tenant_pools
                if tid != serving_tenant_id and _tenant_pool_in_use.get(tid, 0) == 0
            ),
            None,
        )
        if victim is None:
            logger.warning(
                "테넌트 커넥션 풀 상한(%s) 초과 — 모든 풀이 사용 중이라 축출을 건너뜀 (현재 %s개)",
                settings.tenant_pool_limit,
                len(_tenant_pools),
            )
            return
        pool = _tenant_pools.pop(victim)
        _tenant_pool_in_use.pop(victim, None)
        try:
            pool.closeall()
        except Exception:
            logger.exception("테넌트 %s 커넥션 풀 축출 중 종료 실패", victim)


def _get_tenant_pool(
    tenant_id: int, settings: Settings
) -> pg_pool.ThreadedConnectionPool:
    with _tenant_lock:
        pool = _tenant_pools.pop(tenant_id, None)
        if pool is None:
            pool = _create_tenant_pool(tenant_id, settings)
        # 다시 맨 뒤에 넣어 LRU 순서를 갱신한다.
        _tenant_pools[tenant_id] = pool
        _evict_if_needed(settings, serving_tenant_id=tenant_id)
        return pool


@contextmanager
def get_connection(tenant_id: int, settings: Settings) -> Generator:
    """테넌트 ``tenant_id`` 의 롤로 접속한 커넥션을 빌려준다.

    **인자가 둘 다 필수인 것이 의도다.** 이전에는 무인자 ``get_connection()`` 이 공유 롤 풀에서
    커넥션을 주고 ``search_path`` 를 ``data`` 로 하드코딩했다. 무인자 형태를 남겨 두면 테넌트를
    모르는 새 호출부가 조용히 그 경로로 흘러간다 — 그래서 같은 이름을 필수 인자로 바꿔,
    이관되지 않은 호출부가 ``TypeError`` 로 크게 실패하게 만든다.
    """
    schema = resolve_schema(tenant_id)
    pool = _get_tenant_pool(tenant_id, settings)

    conn = None
    checked_out = False
    try:
        conn = pool.getconn()
        if conn is None:
            raise RuntimeError("Connection pool exhausted — no available connections")
        # 체크아웃 카운트는 커넥션을 실제로 손에 쥔 뒤에만 올린다. 실패 경로에서 올려 두면
        # 카운트가 영구히 0 으로 돌아오지 않아 그 테넌트의 풀이 절대 축출되지 않는다.
        with _tenant_lock:
            _tenant_pool_in_use[tenant_id] = _tenant_pool_in_use.get(tenant_id, 0) + 1
        checked_out = True
        if not _is_conn_alive(conn):
            pool.putconn(conn, close=True)
            conn = pool.getconn()
        with conn.cursor() as cur:
            # 롤 레벨 search_path 가 이미 설정돼 있어도 세션에서 명시한다 — 롤 설정은 운영
            # 절차(마이그레이션)에 의존하는데, 스키마명의 출처는 코드 한 곳(resolve_schema)이어야
            # P3-b2 에서 바꿀 곳이 하나로 남는다.
            # 보간이 안전한 이유는 resolve_schema 의 식별자 모양 검증에 있다.
            cur.execute(f"SET search_path TO {schema}")
        yield conn
    except pg_pool.PoolError as exc:
        raise RuntimeError(f"Connection pool error: {exc}") from exc
    finally:
        if conn is not None:
            pool.putconn(conn)
        if checked_out:
            with _tenant_lock:
                remaining = _tenant_pool_in_use.get(tenant_id, 1) - 1
                if remaining <= 0:
                    _tenant_pool_in_use.pop(tenant_id, None)
                else:
                    _tenant_pool_in_use[tenant_id] = remaining
