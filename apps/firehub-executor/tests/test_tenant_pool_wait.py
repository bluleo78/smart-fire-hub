"""WD-265: 테넌트 풀이 차면 PoolError 대신 빈 커넥션을 기다린다.

psycopg2 ``ThreadedConnectionPool.getconn()`` 은 maxconn 에서 기다리지 않고 PoolError 를 던진다.
경로를 스레드풀에서 동시에 돌리면 같은 테넌트 요청이 maxconn 을 넘게 몰릴 수 있어, get_connection 이
테넌트별 세마포어로 넘는 호출을 기다리게 한다(순서 보장 없음). 실제 DB 없이 getconn 의 이 성질만 흉내 낸 가짜 풀로 검증한다.
"""
import threading
from contextlib import contextmanager
from unittest.mock import patch

import psycopg2
import pytest
from psycopg2 import pool as pg_pool

from app.db import connection
from tests.conftest import get_test_settings

# 모든 대기·조인에 시간 제한을 두고, 스레드는 daemon 으로 띄운다 — 구현 버그로 acquire 에서 영영
# 막힌 스레드가 있어도 스위트(프로세스 종료)가 멈추지 않게 한다.
JOIN_TIMEOUT = 5.0


class FakeConn:
    """cursor() 가 컨텍스트 매니저인 가짜 커넥션."""

    closed = False

    @contextmanager
    def cursor(self):
        class _Cur:
            def execute(self, *_args, **_kwargs):
                return None

        yield _Cur()


class FakePool:
    """빌려 간 수가 maxconn 이면 PoolError 를 던지는 가짜 풀(실제 ThreadedConnectionPool 과 같은 성질)."""

    def __init__(self, maxconn: int):
        self.maxconn = maxconn
        self.borrowed = 0
        self._lock = threading.Lock()

    def getconn(self):
        with self._lock:
            if self.borrowed >= self.maxconn:
                raise pg_pool.PoolError("connection pool exhausted")
            self.borrowed += 1
        return FakeConn()

    def putconn(self, conn, close=False):
        with self._lock:
            self.borrowed -= 1


def _reset_state():
    """모듈 전역(풀·사용 수·세마포어)을 비운다 — 테스트끼리 상태가 새지 않게."""
    connection._tenant_pools.clear()
    connection._tenant_pool_in_use.clear()
    connection._tenant_slots.clear()


@pytest.fixture(autouse=True)
def clean_state():
    _reset_state()
    yield
    _reset_state()


@pytest.fixture
def settings():
    return get_test_settings().model_copy(update={"tenant_pool_max": 2})


@pytest.fixture
def fake_pools():
    """_create_tenant_pool 을 가짜 풀로, _is_conn_alive 를 True 로 patch 한다."""
    with patch.object(
        connection,
        "_create_tenant_pool",
        side_effect=lambda tenant_id, settings: FakePool(settings.tenant_pool_max),
    ), patch.object(connection, "_is_conn_alive", return_value=True):
        yield


def _hold(tenant_id, settings, got, release, errors):
    """커넥션을 빌린 뒤 release 가 설정될 때까지 쥐고 있는 스레드 본문."""
    try:
        with connection.get_connection(tenant_id, settings):
            got.set()
            release.wait(JOIN_TIMEOUT)
    except Exception as exc:  # noqa: BLE001 - 스레드 안 예외를 메인에서 단언하려고 모은다
        errors.append(exc)


def _start_holders(tenant_id, settings, count, errors, timeout=JOIN_TIMEOUT):
    """``count`` 개 스레드가 커넥션을 빌려 쥐게 하고, 모두 빌릴 때까지 기다린다 — ``(thread, release)`` 목록을 돌려준다.

    제때 못 빌리면(슬롯이 샜거나 막힘) 이미 띄운 스레드를 풀어 준 뒤 단언 실패로 끝낸다.
    """
    started = []
    for _ in range(count):
        got, release = threading.Event(), threading.Event()
        t = threading.Thread(target=_hold, args=(tenant_id, settings, got, release, errors), daemon=True)
        t.start()
        started.append((t, got, release))
    holders = [(t, release) for t, _got, release in started]
    try:
        for _t, got, _release in started:
            assert got.wait(timeout), "슬롯이 반납되지 않아 대여가 막혔다"
    except BaseException:
        _release_holders(holders)
        raise
    return holders


def _release_holders(holders):
    """``_start_holders`` 가 띄운 스레드를 모두 풀어 주고 끝날 때까지(시간 제한) 기다린다."""
    for t, release in holders:
        release.set()
        t.join(JOIN_TIMEOUT)


def test_extra_borrower_waits_instead_of_pool_error(settings, fake_pools):
    errors = []
    holders = _start_holders(1, settings, settings.tenant_pool_max, errors)

    third_got, third_release = threading.Event(), threading.Event()
    third_release.set()  # 받자마자 바로 놓는다
    third = threading.Thread(target=_hold, args=(1, settings, third_got, third_release, errors), daemon=True)
    third.start()

    # 둘이 쥐고 있는 동안 3번째는 오류 없이 대기 중이어야 한다.
    third.join(0.3)
    assert third.is_alive()
    assert not third_got.is_set()

    # 하나를 풀어 주면 3번째가 커넥션을 받고 끝난다.
    holders[0][1].set()
    assert third_got.wait(2.0)
    third.join(2.0)
    assert not third.is_alive()

    _release_holders(holders)
    assert all(not t.is_alive() for t, _release in holders)
    assert errors == []


def test_semaphore_released_when_body_raises(settings, fake_pools):
    with pytest.raises(ValueError):
        with connection.get_connection(1, settings):
            raise ValueError("본문 오류")

    assert 1 not in connection._tenant_pool_in_use
    # 슬롯이 반납됐다면 tenant_pool_max 개를 동시에 막힘 없이 빌릴 수 있다.
    _borrow_max_without_blocking(1, settings)


def test_semaphore_released_when_getconn_fails(settings, fake_pools):
    class FailingPool(FakePool):
        def getconn(self):
            raise pg_pool.PoolError("boom")

    # 이 블록 안에서만 실패하는 풀을 쓰고, 나가면 fake_pools 의 정상 가짜 풀로 돌아온다.
    with patch.object(connection, "_create_tenant_pool", return_value=FailingPool(2)):
        with pytest.raises(RuntimeError, match="^Connection pool error: boom$"):
            with connection.get_connection(1, settings):
                pytest.fail("본문에 들어오면 안 된다")

    assert 1 not in connection._tenant_pool_in_use
    # 정상 풀로 돌아오면 막히지 않는다(풀은 캐시돼 있으므로 비워서 새로 만든다).
    connection._tenant_pools.clear()
    _borrow_max_without_blocking(1, settings)


def test_semaphore_released_when_pool_creation_fails(settings, fake_pools):
    # 실제 ThreadedConnectionPool(minconn=1) 은 생성자에서 접속하므로 DB 다운은 여기서 난다.
    with patch.object(
        connection, "_create_tenant_pool", side_effect=psycopg2.OperationalError("db down")
    ):
        # 슬롯 수보다 많이 실패시켜도 슬롯이 새지 않는다는 것을 확인한다.
        # 슬롯이 새면 마지막 시도가 acquire 에서 영영 막히므로, 메인 스레드가 아니라 별도 스레드에서 시도한다.
        for _ in range(settings.tenant_pool_max + 1):
            outcome = _attempt_in_thread(1, settings)
            assert isinstance(outcome, psycopg2.OperationalError), outcome

    assert 1 not in connection._tenant_pool_in_use
    # 실패 patch 를 벗어나면 fake_pools 의 정상 가짜 풀로 돌아온다.
    _borrow_max_without_blocking(1, settings)


def test_slot_is_per_tenant(settings, fake_pools):
    errors = []
    holders = _start_holders(1, settings, settings.tenant_pool_max, errors)

    try:
        # 테넌트 1 이 슬롯을 다 쥐고 있어도 테넌트 2 는 바로 빌린다.
        other_got, other_release = threading.Event(), threading.Event()
        other_release.set()
        other = threading.Thread(target=_hold, args=(2, settings, other_got, other_release, errors), daemon=True)
        other.start()
        assert other_got.wait(2.0)
        other.join(JOIN_TIMEOUT)
        assert not other.is_alive()
    finally:
        _release_holders(holders)
    assert errors == []


def test_python_pg_load_uses_same_get_connection():
    from app.db import connection as conn_module
    from app.services import python_executor

    assert python_executor.get_connection is conn_module.get_connection


def _attempt_in_thread(tenant_id, settings):
    """get_connection 을 별도 스레드에서 한 번 시도해 그 예외(없으면 None)를 돌려준다. 막히면 실패 처리."""
    outcome = []

    def run():
        try:
            with connection.get_connection(tenant_id, settings):
                outcome.append(None)
        except Exception as exc:  # noqa: BLE001
            outcome.append(exc)

    t = threading.Thread(target=run, daemon=True)
    t.start()
    t.join(2.0)
    assert not t.is_alive(), "get_connection 이 슬롯 대기에서 막혔다(슬롯이 반납되지 않음)"
    return outcome[0]


def _borrow_max_without_blocking(tenant_id, settings):
    """``tenant_pool_max`` 개를 동시에 빌려 본다 — 슬롯이 새고 있으면 마지막 대여에서 막힌다.

    막힘은 스레드로 감지한다(메인 스레드가 영구 대기하지 않도록 조인에 시간 제한).
    """
    errors = []
    holders = _start_holders(tenant_id, settings, settings.tenant_pool_max, errors, timeout=2.0)
    _release_holders(holders)
    assert errors == []
