"""네 실행 라우트의 저장소 게이트 테스트 (WD-75).

Tibero 테넌트·기술자 조회 실패는 기존 PG 경로(커넥션·서비스)를 건드리기 전에 HTTP 200 + success=False 로 끝나야 하고,
PG 테넌트는 기존 경로를 같은 인자로 그대로 탄다.
"""
from __future__ import annotations

from contextlib import nullcontext
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from app.db.store_client import StoreLookupError
from app.routers import store_gate

# 경로별: 요청 본문, 게이트 사본, 실패 응답 필드 검증, patch 대상(커넥션 / 서비스 함수)
ROUTES = {
    "query": dict(
        path="/execute/query",
        body={"tenantId": 7, "query": "SELECT 1", "max_rows": 10, "read_only": True},
        message=store_gate.QUERY_UNSUPPORTED,
        conn="app.routers.query.get_connection",
        service="app.routers.query.query_executor.execute_query",
    ),
    "sql": dict(
        path="/execute/sql",
        body={"tenantId": 7, "query": "SELECT 1"},
        message=store_gate.SQL_UNSUPPORTED,
        conn="app.routers.sql.get_connection",
        service="app.routers.sql.sql_executor.execute_sql",
    ),
    "api-call": dict(
        path="/execute/api-call",
        body={
            "tenantId": 7,
            "url": "http://public.example.com/api",
            "data_path": "$.items",
            "field_mappings": [{"source_field": "id", "target_column": "id"}],
            "output_table": "t",
        },
        # Tibero 는 이 창구를 거부하지 않는다(원격 적재, WD-77 Task 6) — 조회 실패 거부 메시지만 쓴다.
        message=None,
        conn="app.routers.api_call.get_connection",
        service="app.routers.api_call.api_call_executor.execute_api_call",
    ),
    "python": dict(
        path="/execute/python",
        body={"tenantId": 7, "script": "print(1)", "readSlot": 1},
        message=store_gate.PYTHON_UNSUPPORTED,
        conn=None,  # python 라우트는 get_connection 을 쓰지 않는다
        service="app.routers.python_exec.python_executor.execute_python",
    ),
}
NAMES = list(ROUTES)
# Tibero 테넌트를 거부하는 창구. api-call 은 Tibero 에서 원격 적재로 가므로 빠진다(아래 별도 테스트).
TIBERO_BLOCKED_NAMES = [n for n in NAMES if n != "api-call"]


def _gate_backend(kind=None, error=None):
    """게이트가 쓰는 저장소 클라이언트(get_store_client)를 대체한다. kind 는 기술자의 backend 값이다."""
    client = MagicMock()
    # 기본은 캐시 미스(peek → None)라 스레드풀 조회 경로를 탄다. 적중 경로는 아래 별도 테스트가 다룬다.
    client.peek.return_value = None
    if error is not None:
        client.get.side_effect = error
    else:
        client.get.return_value = SimpleNamespace(backend=kind)
    return patch("app.routers.store_gate.get_store_client", return_value=client)


def _conn_patch(route):
    """라우트의 get_connection patch. python 라우트는 커넥션을 쓰지 않으므로 아무것도 patch 하지 않는다(None 반환)."""
    if route["conn"] is None:
        return nullcontext(None)
    return patch(route["conn"], MagicMock())


def _post(client, headers, name):
    r = ROUTES[name]
    return client.post(r["path"], json=r["body"], headers=headers)


def _assert_blocked_shape(name, data, message):
    assert data["success"] is False
    assert data["error"] == message
    if name == "sql":
        assert data["row_count"] == 0 and data["execution_log"] == ""
        assert data["rows"] is None and data["columns"] is None
    if name == "python":
        assert data["output"] == "" and data["exit_code"] == 1
        assert data["execution_time_ms"] == 0


@pytest.mark.parametrize("name", TIBERO_BLOCKED_NAMES)
def test_tibero_tenant_blocked_on_every_route(test_client, mock_auth_header, name):
    r = ROUTES[name]
    service = MagicMock()
    with _gate_backend(kind="TIBERO"), patch(r["service"], service), _conn_patch(r) as conn:
        resp = _post(test_client, mock_auth_header, name)
    assert resp.status_code == 200
    _assert_blocked_shape(name, resp.json(), r["message"])
    service.assert_not_called()
    if conn is not None:
        conn.assert_not_called()


@pytest.mark.parametrize("name", NAMES)
def test_lookup_failure_blocked_on_every_route(test_client, mock_auth_header, name):
    r = ROUTES[name]
    service = MagicMock()
    err = StoreLookupError("데이터셋 저장소 정보를 가져오지 못했습니다 — 테스트")
    with _gate_backend(error=err), patch(r["service"], service), _conn_patch(r) as conn:
        resp = _post(test_client, mock_auth_header, name)
    assert resp.status_code == 200
    _assert_blocked_shape(name, resp.json(), str(err))
    service.assert_not_called()
    if conn is not None:
        conn.assert_not_called()


# PG 이면 서비스 함수가 기존과 같은 인자로 1회 불리고 그 반환값이 응답이 된다.
PG_RETURNS = {
    "query": ("QueryExecuteResponse", dict(success=True, row_count=1, query_type="SELECT")),
    "sql": ("SqlExecuteResponse", dict(success=True, rows=[], columns=[], row_count=0, execution_log="ok")),
    "api-call": ("ApiCallExecuteResponse", dict(success=True, rows_loaded=3)),
    "python": ("PythonExecuteResponse", dict(success=True, output="hi", exit_code=0, execution_time_ms=5)),
}


@pytest.mark.parametrize("name", NAMES)
def test_postgres_tenant_runs_existing_path(test_client, mock_auth_header, mock_settings, name):
    from app.schemas import responses

    r = ROUTES[name]
    cls, fields = PG_RETURNS[name]
    ret = getattr(responses, cls)(**fields)
    conn_cm = MagicMock()
    conn_obj = object()
    conn_cm.return_value.__enter__.return_value = conn_obj
    conn_patch = nullcontext(None) if r["conn"] is None else patch(r["conn"], conn_cm)
    with _gate_backend(kind="POSTGRESQL"), patch(r["service"], return_value=ret) as service, conn_patch:
        resp = _post(test_client, mock_auth_header, name)
    assert resp.status_code == 200
    data = resp.json()
    assert data["success"] is True
    service.assert_called_once()
    args, kwargs = service.call_args
    if name == "query":
        assert args[:3] == ("SELECT 1", 10, True)
        assert args[3] is conn_obj
        assert kwargs == {"tenant_id": 7}
        assert conn_cm.call_args.args[0] == 7
        assert data["row_count"] == 1
    elif name == "sql":
        assert args[0] == "SELECT 1" and args[2] == []
        assert args[1] is conn_obj
        assert conn_cm.call_args.args[0] == 7
        assert data["row_count"] == 0 and data["execution_log"] == "ok"
    elif name == "api-call":
        assert args[0].tenant_id == 7
        assert args[1] is conn_obj
        assert conn_cm.call_args.args[0] == 7
        assert data["rows_loaded"] == 3
    else:
        assert args[0] == "print(1)"
        assert kwargs["tenant_id"] == 7 and kwargs["read_slot"] == 1
        assert data["output"] == "hi"


@pytest.mark.parametrize("kind", ["POSTGRESQL", "TIBERO"])
def test_gate_reads_descriptor_without_building_backend(mock_settings, kind):
    # 게이트는 기술자의 backend 값만 비교한다 — 백엔드 객체를 만들지 않는다.
    client = MagicMock()
    client.get.return_value = SimpleNamespace(backend=kind)
    with patch("app.routers.store_gate.get_store_client", return_value=client) as gsc, \
            patch("app.db.backends.PostgresBackend") as pg_cls, \
            patch("app.db.backends.backend_for") as bf:
        err = store_gate.postgres_only_error(7, mock_settings, store_gate.SQL_UNSUPPORTED)
    assert err == (None if kind == "POSTGRESQL" else store_gate.SQL_UNSUPPORTED)
    gsc.assert_called_once_with(mock_settings)
    client.get.assert_called_once_with(7)
    pg_cls.assert_not_called()
    bf.assert_not_called()


@pytest.mark.parametrize("kind", ["POSTGRESQL", "TIBERO"])
async def test_unsupported_error_cache_hit_skips_threadpool(mock_settings, kind):
    # 기술자가 캐시돼 있으면 스레드풀·블로킹 조회 없이 이벤트 루프에서 바로 판정한다.
    client = MagicMock()
    client.peek.return_value = SimpleNamespace(backend=kind)
    with patch("app.routers.store_gate.get_store_client", return_value=client), \
            patch("app.routers.store_gate.run_in_threadpool") as rtp:
        err = await store_gate.unsupported_error(7, mock_settings, store_gate.SQL_UNSUPPORTED)
    assert err == (None if kind == "POSTGRESQL" else store_gate.SQL_UNSUPPORTED)
    rtp.assert_not_called()
    client.get.assert_not_called()


async def test_unsupported_error_cache_miss_resolves_via_threadpool(mock_settings):
    client = MagicMock()
    client.peek.return_value = None
    client.get.return_value = SimpleNamespace(backend="TIBERO")
    with patch("app.routers.store_gate.get_store_client", return_value=client):
        err = await store_gate.unsupported_error(7, mock_settings, store_gate.SQL_UNSUPPORTED)
    assert err == store_gate.SQL_UNSUPPORTED
    client.get.assert_called_once_with(7)


# ---------------------------------------------------------------------------
# API 수집(api-call) 창구 — Tibero 는 원격 적재로 간다(WD-77 Task 6). sql/query/probe/load/python 은 위 테스트가 계속 거부를 고정한다.
# ---------------------------------------------------------------------------

STAGING = "STG_0123456789ABCDEF01234567"
REMOTE_SERVICE = "app.routers.api_call.api_call_executor.execute_api_call_remote"
PG_SERVICE = "app.routers.api_call.api_call_executor.execute_api_call"


def _api_call_body(**overrides):
    body = dict(ROUTES["api-call"]["body"])
    body.update(overrides)
    return body


def test_tibero_api_call_goes_to_remote_load_not_pg(test_client, mock_auth_header):
    from app.db.backends.remote import RemoteLoader
    from app.schemas.responses import ApiCallExecuteResponse

    ret = ApiCallExecuteResponse(success=True, rows_loaded=5, total_pages=1)
    conn = MagicMock()
    with _gate_backend(kind="TIBERO"), patch(REMOTE_SERVICE, return_value=ret) as remote, \
            patch(PG_SERVICE) as pg, patch("app.routers.api_call.get_connection", conn):
        resp = test_client.post(
            "/execute/api-call", json=_api_call_body(output_table=STAGING), headers=mock_auth_header
        )
    assert resp.status_code == 200
    assert resp.json()["success"] is True and resp.json()["rows_loaded"] == 5
    remote.assert_called_once()
    request, loader = remote.call_args.args
    assert request.tenant_id == 7 and request.output_table == STAGING
    assert isinstance(loader, RemoteLoader)
    pg.assert_not_called()
    conn.assert_not_called()  # PG 커넥션을 열지 않는다


@pytest.mark.parametrize(
    "bad",
    ["test_table", STAGING.lower(), "STG_0123456789ABCDEF0123456", STAGING + "\n", "STG_0123456789ABCDEF012345ZZ"],
)
def test_tibero_api_call_rejects_non_staging_output_table(test_client, mock_auth_header, bad):
    with _gate_backend(kind="TIBERO"), patch(REMOTE_SERVICE) as remote, patch(PG_SERVICE) as pg, \
            patch("app.routers.api_call.get_connection") as conn:
        resp = test_client.post(
            "/execute/api-call", json=_api_call_body(output_table=bad), headers=mock_auth_header
        )
    assert resp.status_code == 200
    data = resp.json()
    assert data["success"] is False and "STG_" in data["error"]
    remote.assert_not_called()
    pg.assert_not_called()
    conn.assert_not_called()


def test_postgres_api_call_never_builds_remote_loader_and_keeps_pg_names(test_client, mock_auth_header):
    # PG 는 STG 형식을 요구하지 않고(기존 PG 이름 그대로), 원격 적재기를 만들지 않는다.
    from app.schemas.responses import ApiCallExecuteResponse

    ret = ApiCallExecuteResponse(success=True, rows_loaded=1)
    conn_cm = MagicMock()
    with _gate_backend(kind="POSTGRESQL"), patch(PG_SERVICE, return_value=ret) as pg, \
            patch(REMOTE_SERVICE) as remote, patch("app.routers.api_call.RemoteLoader") as loader_cls, \
            patch("app.routers.api_call.get_connection", conn_cm):
        resp = test_client.post("/execute/api-call", json=_api_call_body(), headers=mock_auth_header)
    assert resp.status_code == 200 and resp.json()["rows_loaded"] == 1
    pg.assert_called_once()
    remote.assert_not_called()
    loader_cls.assert_not_called()


def test_tibero_api_call_remote_load_failure_is_success_false(test_client, mock_auth_header):
    # 원격 적재 실패가 응답 success=False 로 나간다(서비스 계층 실제 코드 + 가짜 적재기).
    from app.db.backends.remote import RemoteLoadError

    loader = MagicMock()
    loader.load.side_effect = RemoteLoadError("적재 실패 — 테스트")
    page = {"items": [{"id": 1, "name": "a"}]}
    with _gate_backend(kind="TIBERO"), patch("app.routers.api_call.RemoteLoader", return_value=loader), \
            patch("app.services.api_call_executor.validate_url"), \
            patch("app.services.api_call_executor._do_request", return_value=page):
        resp = test_client.post(
            "/execute/api-call",
            json=_api_call_body(
                output_table=STAGING,
                field_mappings=[{"source_field": "id", "target_column": "id", "data_type": "INTEGER"}],
            ),
            headers=mock_auth_header,
        )
    assert resp.status_code == 200
    data = resp.json()
    assert data["success"] is False and data["error"] == "적재 실패 — 테스트"
    assert data["rows_loaded"] == 0


async def test_backend_of_returns_backend_or_error(mock_settings):
    client = MagicMock()
    client.peek.return_value = SimpleNamespace(backend="TIBERO")
    with patch("app.routers.store_gate.get_store_client", return_value=client):
        assert await store_gate.backend_of(7, mock_settings) == ("TIBERO", None)
    client.peek.return_value = SimpleNamespace(backend="POSTGRESQL")
    with patch("app.routers.store_gate.get_store_client", return_value=client):
        assert await store_gate.backend_of(7, mock_settings) == ("POSTGRESQL", None)
    client.peek.return_value = None
    client.get.side_effect = StoreLookupError("조회 실패")
    with patch("app.routers.store_gate.get_store_client", return_value=client):
        assert await store_gate.backend_of(7, mock_settings) == (None, "조회 실패")
