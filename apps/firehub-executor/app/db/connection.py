import logging
import threading
from collections import OrderedDict
from contextlib import contextmanager
from typing import Any, Callable, Dict, Generator, Optional, TypeVar

from psycopg2 import pool as pg_pool
from starlette.concurrency import run_in_threadpool

from app.config import Settings
from app.services.pg_typecasters import install_safe_typecasters
from app.tenant import resolve_password, resolve_role, resolve_schema

logger = logging.getLogger(__name__)

# 이 모듈이 모든 실행 경로(애드혹 분석·파이프라인 SQL 스텝)의 연결을 내주므로, 여기서 psycopg2 전역 캐스터를
# 보강한다(#762) — 하한 지정 다차원 배열·범위 초과 날짜가 쿼리 전체를 실패시키지 않게. 워커 프로세스마다
# 이 모듈이 임포트되므로 각 워커에 한 번씩 설치된다(멱등).
install_safe_typecasters()

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

# 테넌트별 동시 대여 슬롯(WD-265). 크기는 풀의 maxconn(tenant_pool_max)과 같다 — 이 설계의 근거는 여기 한 곳에 둔다.
#
# 왜 필요한가: psycopg2 ``ThreadedConnectionPool.getconn()`` 은 maxconn 에 닿으면 기다리지 않고 곧바로 PoolError 를
# 던진다. 경로가 스레드풀에서 동시에 돌면 같은 테넌트 요청이 maxconn 을 넘게 몰릴 수 있어, 풀에 가기 전에 슬롯을
# 먼저 얻어 넘는 호출을 기다리게 한다. 슬롯 수 = maxconn 이므로 슬롯을 얻은 호출의 getconn 은 PoolError 로 실패하지 않는다.
#
# 왜 풀(_tenant_pools)과 별개로 두고 지우지 않는가: 풀은 LRU 축출·close_tenant_pools 로 사라지고 다시 만들어지지만,
# "지금 몇 개가 나가 있나"(동시 대여 수 상한)는 풀의 생애와 무관하게 이어져야 한다. 슬롯을 풀에 묶으면 축출 후 새
# 세마포어가 생겨 옛 풀의 대여자 수를 잊고 상한이 두 배로 샌다. 테넌트 id 당 객체 하나라 메모리는 무시할 만하다.
#
# 대기 방식: threading.Semaphore 는 FIFO 가 아니다 — 먼저 기다린 호출이 먼저 받는다는 보장이 없고 새로 온 호출이
# 끼어들 수 있다. 대기에 시간 상한을 두지 않는다(호출한 api 쪽 대기 한도가 지나면 api 가 포기한다).
#
# 알려진 한계: (1) 한 테넌트의 대기자가 anyio 스레드풀(프로세스당 기본 40개)을 다 차지할 수 있다 — ``/health`` 는
# 스레드풀을 쓰지 않아 영향이 없다. (2) PG 커넥션 수의 최악 상한이 올랐다 — 워커마다 스레드 최대 약 40개가 동시에
# 돌 수 있고 사용 중인 풀은 축출하지 않으므로 tenant_pool_limit 을 넘을 수 있다. 실제로는 api 쪽 실행기 풀이 동시
# 호출 수를 막아 주고, 동시성 한도 재설계는 이번 범위 밖이다.
_tenant_slots: Dict[int, threading.Semaphore] = {}


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
        # **락을 놓기 전에** 사용 예약을 걸어 둔다(코드리뷰 지적 5).
        #
        # 예약이 없으면 창(window)이 생긴다: 이 함수가 풀을 돌려주고 락이 풀린 뒤 호출부가
        # ``getconn()`` 하기까지 이 풀의 in_use 는 0 이다. 그 사이 다른 테넌트의 요청이 상한을
        # 넘기면 이 풀이 축출 대상으로 뽑혀 ``closeall()`` 되고, 성공해야 할 요청이 PoolError 로
        # 실패한다. ``serving_tenant_id`` 가드는 **같은 호출** 안에서만 유효해 이 창을 막지 못한다.
        _tenant_pool_in_use[tenant_id] = _tenant_pool_in_use.get(tenant_id, 0) + 1
        _evict_if_needed(settings, serving_tenant_id=tenant_id)
        return pool


def _tenant_slot(tenant_id: int, settings: Settings) -> threading.Semaphore:
    """테넌트의 동시 대여 슬롯(세마포어)을 돌려준다 — 없으면 ``tenant_pool_max`` 개로 만든다.

    ``_tenant_lock`` 안에서 만들어 두 스레드가 서로 다른 세마포어를 갖는 일을 막는다. 크기는
    처음 만들 때의 설정으로 고정된다(설정은 프로세스 수명 동안 바뀌지 않는다).
    """
    with _tenant_lock:
        slot = _tenant_slots.get(tenant_id)
        if slot is None:
            slot = threading.Semaphore(settings.tenant_pool_max)
            _tenant_slots[tenant_id] = slot
        return slot


@contextmanager
def get_connection(tenant_id: int, settings: Settings) -> Generator:
    """테넌트 ``tenant_id`` 의 롤로 접속한 커넥션을 빌려준다.

    **인자가 둘 다 필수인 것이 의도다.** 이전에는 무인자 ``get_connection()`` 이 공유 롤 풀에서
    커넥션을 주고 ``search_path`` 를 ``data`` 로 하드코딩했다. 무인자 형태를 남겨 두면 테넌트를
    모르는 새 호출부가 조용히 그 경로로 흘러간다 — 그래서 같은 이름을 필수 인자로 바꿔,
    이관되지 않은 호출부가 ``TypeError`` 로 크게 실패하게 만든다.

    풀이 차면 PoolError 대신 빈 커넥션이 생길 때까지 기다린다(WD-265, 근거는 ``_tenant_slots`` 주석).
    중첩 금지: 한 요청이 슬롯을 쥔 채 같은 테넌트로 또 부르면 동시 요청이 몰릴 때 서로를 영구히 기다린다
    (예전엔 PoolError 로 끝났다). 현재 호출처에는 중첩이 없다.
    """
    schema = resolve_schema(tenant_id)
    # Semaphore 를 with 로 쓰면 진입 전에 acquire 하고(획득이 실패하면 release 하지 않는다) 나갈 때 정확히 한 번
    # release 한다. 안쪽 _borrow 가 putconn 까지 끝낸 뒤에 반납되므로, 대기자가 아직 안 돌아온 커넥션에 getconn 해
    # PoolError 를 맞는 일이 없다.
    with _tenant_slot(tenant_id, settings):
        with _borrow(tenant_id, settings, schema) as conn:
            yield conn


@contextmanager
def _borrow(tenant_id: int, settings: Settings, schema: str) -> Generator:
    """테넌트 풀에서 커넥션 하나를 빌려 ``search_path`` 를 맞춰 내주고, 끝나면 반납·사용 수를 되돌린다.

    슬롯 대기(WD-265) 이전의 ``get_connection`` 본문 그대로다 — 슬롯을 쥔 ``get_connection`` 만 부른다.
    """
    pool = _get_tenant_pool(tenant_id, settings)

    conn = None
    # _get_tenant_pool 이 **락 안에서** 이미 예약을 걸어 뒀다(축출 창 방지). 따라서 여기서 다시
    # 올리지 않고, 아래 finally 가 어떤 경로로든 반드시 한 번 내려 준다 — 예외로 빠져나가도
    # 카운트가 남으면 그 테넌트의 풀이 영구히 축출 불가가 된다.
    #
    # 플래그를 두지 않는다: 이 try 에 들어왔다는 것 자체가 예약이 성공했다는 뜻이다(_get_tenant_pool
    # 이 실패하면 try 에 진입하지 못하므로 내릴 것도 없다).
    try:
        conn = pool.getconn()
        if conn is None:
            raise RuntimeError("Connection pool exhausted — no available connections")
        if not _is_conn_alive(conn):
            pool.putconn(conn, close=True)
            conn = pool.getconn()
        with conn.cursor() as cur:
            # 롤 레벨 search_path 가 이미 설정돼 있어도 세션에서 명시한다 — 롤 설정은 운영
            # 절차(마이그레이션)에 의존하는데, 스키마명의 출처는 코드 한 곳(resolve_schema)이어야
            # P3-b2 에서 바꿀 곳이 하나로 남는다.
            # 보간이 안전한 이유는 resolve_schema 가 _require_tenant_id 로 정규화한 값만
            # 반환하기 때문이다(data_t<양의 정수> 형태로 고정) — 식별자 모양 검증
            # (_SAFE_IDENTIFIER)은 그 위의 휴면 방어선이다(tenant.py 참조).
            cur.execute(f"SET search_path TO {schema}")
        yield conn
    except pg_pool.PoolError as exc:
        raise RuntimeError(f"Connection pool error: {exc}") from exc
    finally:
        if conn is not None:
            pool.putconn(conn)
        with _tenant_lock:
            remaining = _tenant_pool_in_use.get(tenant_id, 1) - 1
            if remaining <= 0:
                _tenant_pool_in_use.pop(tenant_id, None)
            else:
                _tenant_pool_in_use[tenant_id] = remaining


T = TypeVar("T")


async def run_with_tenant_connection(tenant_id: int, settings: Settings, fn: Callable[[Any], T]) -> T:
    """테넌트 커넥션을 빌려 ``fn(conn)`` 의 결과를 돌려준다 — 대여부터 실행·반납까지 모두 스레드풀에서 한다.

    대여를 워커 스레드 **안에서** 하는 것이 요점이다(WD-265): ``get_connection`` 은 테넌트 풀이 차면 빈 커넥션을
    시간 상한 없이 기다리는데, 그 대기가 이벤트 루프 위에서 일어나면 같은 프로세스의 다른 요청(/health 포함)이
    전부 멈춘다. ``get_connection`` 은 호출 시점에 이 모듈 전역에서 찾는다(테스트는 이 모듈의 이름을 patch 한다).
    """

    def _run() -> T:
        with get_connection(tenant_id, settings) as conn:
            return fn(conn)

    return await run_in_threadpool(_run)
