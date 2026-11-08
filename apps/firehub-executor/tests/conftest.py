import time
from contextlib import nullcontext
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import app


TEST_TOKEN = "test-internal-token-secret"
# Java 쪽 application-test.yml 의 app.pipeline.role-password-secret 과 같은 값 —
# 두 언어의 비밀번호 파생이 일치함을 이 값으로 검증한다.
TEST_ROLE_SECRET = "test-tenant-pipeline-secret"


def get_test_settings(**overrides) -> Settings:
    """테스트용 Settings. 기본값 위에 ``overrides`` 를 덮어쓴다(예: ``_env_file=None`` 으로 .env 무시)."""
    values = dict(
        db_host="localhost",
        db_port=5432,
        db_name="firehub_test",
        db_user="pipeline_executor",
        db_password="",
        internal_service_token=TEST_TOKEN,
        role_password_secret=TEST_ROLE_SECRET,
        nsjail_enabled=False,
        # 기본값(서비스명 api)은 테스트 머신에서 DNS 조회로 느려질 수 있어, 즉시 거부되는 루프백 discard 포트를 쓴다.
        api_base_url="http://127.0.0.1:9",
    )
    values.update(overrides)
    return Settings(**values)


def _settings_dependency() -> Settings:
    """FastAPI 의존성 오버라이드용 무인자 래퍼 — **overrides 가 쿼리 파라미터로 오인되지 않게 한다."""
    return get_test_settings()


@pytest.fixture(autouse=True)
def override_settings():
    app.dependency_overrides[get_settings] = _settings_dependency
    yield
    app.dependency_overrides.clear()


@pytest.fixture
def test_client() -> TestClient:
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def mock_settings() -> Settings:
    return get_test_settings()


@pytest.fixture
def mock_auth_header() -> dict:
    return {
        "Authorization": f"Internal {TEST_TOKEN}",
        "X-On-Behalf-Of": "user-123",
    }


class FakeClock:
    """``monotonic`` 대역(WD-261) — 시간 초과 판정이 「그 문장의 경과 시간」이라 테스트가 시계를 정확히 움직여야 한다.

    ``step`` 만큼 부를 때마다 흐르고(기본 0), ``advance(s)`` 로 문장 실행 중에 흐른 시간을 직접 더한다. 무한히 부를 수 있다
    (유한 반복자처럼 한 번 더 부르면 StopIteration 이 나는 일이 없다).
    """

    def __init__(self, step: float = 0.0):
        self.step = step
        self.now = -step

    def __call__(self) -> float:
        self.now += self.step
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class _ClockedTime:
    """``time`` 모듈 대역 — ``monotonic`` 만 가짜 시계로 바꾸고 나머지(``perf_counter`` 등)는 진짜 ``time`` 에 맡긴다."""

    def __init__(self, clock: FakeClock):
        self.monotonic = clock

    def __getattr__(self, name: str):
        return getattr(time, name)


@pytest.fixture
def fake_clock(monkeypatch) -> FakeClock:
    # 전역 ``time.monotonic`` 은 바꾸지 않고, 시계를 읽는 모듈의 이름만 같은 대역으로 바꾼다. 시작 시각과 「지금」을 다른 시계로 재면
    # 경과 시간이 엉터리가 되므로 둘을 함께 바꾼다:
    #  - query_executor 는 ``import time`` 의 ``time.monotonic()`` 으로 읽는다(시간 초과 판정·RunningStatement 의 문장 시작 시각) —
    #    그 모듈의 ``time`` 이름만 대역 객체로 바꾼다.
    #  - sql_executor 는 ``from time import monotonic`` 으로 선행 문장 예산을 잰다.
    clock = FakeClock()
    monkeypatch.setattr("app.services.query_executor.time", _ClockedTime(clock))
    monkeypatch.setattr("app.services.sql_executor.monotonic", clock)
    return clock


class PgError(Exception):
    """서버가 보낸 오류처럼 SQLSTATE(pgcode)를 가진 psycopg2 오류 대역 — ``sqlstate()`` 는 ``pgcode`` 만 본다.

    psycopg2.errors.DivisionByZero(...) 를 직접 만들면 pgcode 가 None 이라(서버가 채우는 값) SQLSTATE 판정 경로를 타지 않는다 —
    그래서 pgcode 를 명시적으로 단 예외를 쓴다. 문장 제한 취소는 ``PgError(msg, "57014")`` 다(시간 초과인지는 그 문장의 경과 시간으로
    가른다, WD-261).
    """

    def __init__(self, msg: str, pgcode: str, position=None):
        super().__init__(msg)
        self.pgcode = pgcode
        # 서버 오류 위치(diag.statement_position, WD-641) — libpq 처럼 문자열로 둔다. 주지 않으면 diag 자체가 없다.
        if position is not None:
            self.diag = SimpleNamespace(statement_position=position)


def gate_backend(kind=None, error=None):
    """게이트가 쓰는 저장소 클라이언트(get_store_client)를 대체한다. kind 는 기술자의 backend 값이다.

    여러 라우트 테스트(저장소 게이트·이벤트 루프·분석 조회 시간 제한)가 같은 대역을 쓰도록 여기 둔다.
    """
    client = MagicMock()
    # 기본은 캐시 미스(peek → None)라 스레드풀 조회 경로를 탄다. 적중 경로는 test_store_gate_routes 의 별도 테스트가 다룬다.
    client.peek.return_value = None
    if error is not None:
        client.get.side_effect = error
    else:
        client.get.return_value = SimpleNamespace(backend=kind)
    return patch("app.routers.store_gate.get_store_client", return_value=client)


def conn_patch(target):
    """라우트의 get_connection patch. 커넥션을 쓰지 않는 라우트(target None)는 아무것도 patch 하지 않는다(None 반환)."""
    if target is None:
        return nullcontext(None)
    return patch(target, MagicMock())


def apply_auth(auth, headers, params):
    """인증 설정(dict)을 실행기와 같은 두 단계(``_resolve_auth`` → ``_apply_resolved_auth``)로 요청에 붙인다(WD-657).

    실행기는 실행마다 한 번 푼 값을 붙이고 dict 를 바로 받는 함수는 없앴다 — 테스트는 이 도우미 하나로 같은 경로를 탄다.
    반환값은 쿼리 인증이면 ``(파라미터 이름, 키 원문)``, 아니면 None.
    """
    from app.services.api_call_executor import _apply_resolved_auth, _resolve_auth

    return _apply_resolved_auth(_resolve_auth(auth), headers, params)
