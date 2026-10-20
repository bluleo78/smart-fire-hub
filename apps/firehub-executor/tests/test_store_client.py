"""StoreClient: 기술자 조회·TTL 캐시·fail-closed 오류 매핑 테스트(httpx.MockTransport)."""
import threading
import time

import httpx
import pytest

from app.db.store_client import StoreClient, StoreLookupError
from tests.conftest import TEST_TOKEN, get_test_settings

PG = {"tenantId": 7, "backend": "POSTGRESQL"}


def tibero(**over):
    body = {
        "tenantId": 7, "backend": "TIBERO", "host": "h", "port": 8629, "database": "tibero",
        "schema": "GENIA_T7", "owner": {"username": "GENIA_T7", "password": "pw"},
    }
    body.update(over)
    return body


def make(handler):
    """요청 횟수를 세는 클라이언트와 가변 clock 을 만든다."""
    calls = []
    clock = [0.0]

    def wrapped(request):
        calls.append(request)
        return handler(request)

    http = httpx.Client(transport=httpx.MockTransport(wrapped), base_url="http://api")
    return StoreClient(get_test_settings(), client=http, clock=lambda: clock[0]), calls, clock


def respond(status, body):
    return lambda r: httpx.Response(status, json=body)


def test_postgres_descriptor_is_cached_for_process_lifetime():
    # PG 백엔드는 테넌트 생성 후 바뀌지 않으므로 TTL 이 지나도 다시 조회하지 않는다(api 무의존).
    c, calls, clock = make(respond(200, PG))
    assert c.get(7).backend == "POSTGRESQL"
    c.get(7)
    assert len(calls) == 1
    clock[0] = 61
    c.get(7)
    clock[0] = 10_000_000
    c.get(7)
    assert len(calls) == 1


def test_tibero_descriptor_is_refetched_after_ttl():
    # Tibero 는 비밀번호가 회전될 수 있어 TTL 을 지킨다.
    c, calls, clock = make(respond(200, tibero()))
    c.get(7)
    clock[0] = 59
    c.get(7)
    assert len(calls) == 1
    clock[0] = 61
    c.get(7)
    assert len(calls) == 2


def test_request_carries_internal_token_and_path():
    c, calls, _ = make(respond(200, PG))
    c.get(7)
    assert calls[0].url.path == "/internal/executor/tenants/7/data-store"
    assert calls[0].headers["Authorization"] == f"Internal {TEST_TOKEN}"


def test_tibero_descriptor_parsed_and_password_hidden():
    c, _, _ = make(respond(200, tibero()))
    desc = c.get(7)
    assert desc.owner.password.get_secret_value() == "pw"
    assert "pw" not in repr(desc)


def test_404_is_lookup_error_not_postgres():
    c, _, _ = make(respond(404, {}))
    with pytest.raises(StoreLookupError, match="api 를 먼저 배포"):
        c.get(7)


def test_409_message_is_surfaced_and_not_cached():
    msg = "데이터셋 저장소 계정 확인이 끝나지 않았습니다"
    c, calls, _ = make(respond(409, {"code": "DATA_STORE_NOT_READY", "message": msg}))
    with pytest.raises(StoreLookupError, match=msg):
        c.get(7)
    with pytest.raises(StoreLookupError):
        c.get(7)
    assert len(calls) == 2


def test_401_mentions_token_mismatch():
    c, _, _ = make(respond(401, {}))
    with pytest.raises(StoreLookupError, match="내부 토큰"):
        c.get(7)


def test_503_token_not_configured_is_clear():
    # 본문에 message 가 없으면 로컬 한국어 문구를 쓴다.
    c, _, _ = make(respond(503, {"code": "EXECUTOR_TOKEN_NOT_CONFIGURED"}))
    with pytest.raises(StoreLookupError, match="토큰을 설정"):
        c.get(7)


def test_503_relays_api_message_capped():
    c, _, _ = make(respond(503, {"code": "EXECUTOR_TOKEN_NOT_CONFIGURED", "message": "가" * 500}))
    with pytest.raises(StoreLookupError) as ei:
        c.get(7)
    assert "가" * 200 in str(ei.value) and "가" * 201 not in str(ei.value)


def test_409_without_message_uses_fallback():
    c, _, _ = make(respond(409, {}))
    with pytest.raises(StoreLookupError, match="준비되지 않았습니다"):
        c.get(7)


def test_409_with_blank_message_uses_code_fallback():
    c, _, _ = make(respond(409, {"code": "DATA_STORE_NOT_READY", "message": "  "}))
    with pytest.raises(StoreLookupError, match="DATA_STORE_NOT_READY"):
        c.get(7)


def test_peek_miss_returns_none_without_calling_api():
    c, calls, _ = make(respond(200, PG))
    assert c.peek(7) is None
    assert calls == []


def test_peek_pg_hit_never_expires():
    c, _, clock = make(respond(200, PG))
    c.get(7)
    clock[0] = 10_000_000
    assert c.peek(7).backend == "POSTGRESQL"


def test_peek_tibero_fresh_then_expired():
    c, _, clock = make(respond(200, tibero()))
    c.get(7)
    clock[0] = 59
    assert c.peek(7) is not None
    clock[0] = 61
    assert c.peek(7) is None


def test_transport_error_is_lookup_error_and_not_cached():
    def boom(request):
        raise httpx.ConnectTimeout("t")

    c, calls, _ = make(boom)
    for _ in range(2):
        with pytest.raises(StoreLookupError, match="api 서버에 연결할 수 없습니다"):
            c.get(7)
    assert len(calls) == 2


def test_rejects_descriptor_for_other_tenant():
    c, _, _ = make(respond(200, {"tenantId": 8, "backend": "POSTGRESQL"}))
    with pytest.raises(StoreLookupError):
        c.get(7)


def test_rejects_tibero_schema_not_matching_owner_convention():
    c, _, _ = make(respond(200, tibero(schema="SCOTT")))
    with pytest.raises(StoreLookupError):
        c.get(7)


@pytest.mark.parametrize("over", [{"owner": None}, {"host": None}, {"port": None}, {"database": None}])
def test_rejects_tibero_without_owner_or_host(over):
    c, _, _ = make(respond(200, tibero(**over)))
    with pytest.raises(StoreLookupError):
        c.get(7)


def test_rejects_unknown_backend():
    c, _, _ = make(respond(200, {"tenantId": 7, "backend": "MYSQL"}))
    with pytest.raises(StoreLookupError):
        c.get(7)


def test_malformed_descriptor_error_hides_password():
    body = tibero(port="x", owner={"username": "GENIA_T7", "password": 123})
    c, _, _ = make(respond(200, body))
    with pytest.raises(StoreLookupError) as ei:
        c.get(7)
    assert "123" not in str(ei.value)
    assert ei.value.__cause__ is None


def test_invalidate_forces_refetch():
    c, calls, _ = make(respond(200, PG))
    c.get(7)
    c.invalidate(7)
    c.get(7)
    assert len(calls) == 2


def test_timeout_comes_from_settings():
    c = StoreClient(get_test_settings())
    assert c._client.timeout.connect == 5.0


def test_postgres_entry_is_dropped_only_by_invalidate():
    c, calls, clock = make(respond(200, PG))
    c.get(7)
    clock[0] = 10_000
    c.get(7)
    assert len(calls) == 1
    c.invalidate(7)
    c.get(7)
    assert len(calls) == 2


def test_error_is_not_cached_then_success_is():
    # 실패는 캐시하지 않는다 — 다음 요청은 다시 조회하고, 성공하면 그때부터 캐시된다.
    answers = [httpx.Response(503, json={}), httpx.Response(200, json=PG)]
    c, calls, _ = make(lambda r: answers.pop(0))
    with pytest.raises(StoreLookupError):
        c.get(7)
    assert c.get(7).backend == "POSTGRESQL"
    c.get(7)
    assert len(calls) == 2


def test_invalidate_during_inflight_fetch_discards_result():
    # 조회 도중 invalidate 가 오면(예: Tibero 접속 실패 콜백) 그 조회 결과를 캐시에 다시 써넣지 않는다.
    holder = {}

    def handler(request):
        if len(calls_box[0]) == 1:
            holder["client"].invalidate(7)
        return httpx.Response(200, json=PG)

    calls_box = [None]
    c, calls, _ = make(handler)
    calls_box[0] = calls
    holder["client"] = c
    assert c.get(7).backend == "POSTGRESQL"  # 조회 결과 자체는 호출자에게 돌려준다
    c.get(7)
    assert len(calls) == 2  # 첫 결과가 캐시되지 않았으므로 다시 조회했다
    c.get(7)
    assert len(calls) == 2  # 두 번째(무효화 없는) 결과는 캐시됐다


def test_concurrent_misses_for_one_tenant_make_one_http_call():
    entered = threading.Event()
    release = threading.Event()

    def handler(request):
        entered.set()
        # 첫 조회를 붙잡아 둔 사이 두 번째 스레드가 같은 테넌트를 조회하게 한다.
        assert release.wait(5), "테스트가 release 를 보내지 않았다"
        return httpx.Response(200, json=PG)

    c, calls, _ = make(handler)
    results, errors = [], []

    def worker():
        try:
            results.append(c.get(7).backend)
        except Exception as exc:  # 스레드 예외를 단언으로 끌어올린다
            errors.append(exc)

    t1 = threading.Thread(target=worker)
    t1.start()
    assert entered.wait(5)
    t2 = threading.Thread(target=worker)
    t2.start()
    time.sleep(0.1)  # t2 가 테넌트 락에서 기다리게 될 시간
    release.set()
    t1.join(5)
    t2.join(5)
    assert not t1.is_alive() and not t2.is_alive()
    assert errors == []
    assert results == ["POSTGRESQL", "POSTGRESQL"]
    assert len(calls) == 1


def test_inflight_fetch_does_not_block_other_tenant():
    # 한 테넌트 조회가 api 에서 멈춰 있어도 다른 테넌트 조회는 진행된다(전역 락을 HTTP 동안 잡지 않는다).
    entered = threading.Event()
    release = threading.Event()

    def handler(request):
        if request.url.path.endswith("/7/data-store"):
            entered.set()
            assert release.wait(5)
            return httpx.Response(200, json=PG)
        return httpx.Response(200, json={"tenantId": 8, "backend": "POSTGRESQL"})

    c, _, _ = make(handler)
    t = threading.Thread(target=lambda: c.get(7))
    t.start()
    try:
        assert entered.wait(5)
        assert c.get(8).backend == "POSTGRESQL"
    finally:
        release.set()
        t.join(5)
    assert not t.is_alive()


def test_base_url_without_scheme_is_lookup_error_without_url():
    # 스킴 없는 주소("api:8080")는 httpx 가 'api' 를 스킴으로 읽는다 — 어떤 예외든 StoreLookupError 로 끝나야 한다.
    settings = get_test_settings().model_copy(update={"api_base_url": "api:8080"})
    c = StoreClient(settings, client=httpx.Client())
    with pytest.raises(StoreLookupError) as ei:
        c.get(7)
    assert "api:8080" not in str(ei.value)
    assert "EXECUTOR_API_BASE_URL" in str(ei.value)
    assert ei.value.__cause__ is None


def test_unexpected_send_error_is_lookup_error_without_url():
    class BrokenClient:
        """send 단계에서 httpx 밖 예외(ValueError)를 내는 클라이언트."""

        def get(self, url, headers=None):
            raise ValueError(f"broken {url} token={headers['Authorization']}")

    c = StoreClient(get_test_settings(), client=BrokenClient())
    with pytest.raises(StoreLookupError) as ei:
        c.get(7)
    msg = str(ei.value)
    assert "localhost" not in msg and TEST_TOKEN not in msg
    assert ei.value.__cause__ is None and ei.value.__suppress_context__
