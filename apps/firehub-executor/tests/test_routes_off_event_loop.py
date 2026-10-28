"""PG 실행 경로 6곳이 이벤트 루프를 막지 않는지 검증한다 (WD-265).

``get_connection`` 이 테넌트 풀이 가득 차면 호출 스레드에서 빈 커넥션을 기다린다(무제한). 그 대기가 이벤트 루프 위에서 일어나면
같은 프로세스의 다른 요청(/health 포함)이 전부 멈춘다 — 그래서 sql·load·probe·query(비스트리밍)·api-call(PG)·python(PG) 은
커넥션 대여부터 실행·반납까지를 스레드풀에서 돌려야 한다.

방법: 각 경로의 서비스 함수를 ``threading.Event`` 로 막아 두는 가짜로 바꾸고, 그동안 /health 가 응답하는지 본다.
루프가 막히는 구현에서는 가짜의 ``release.wait(5)`` 가 시간 초과로 끝나 경로가 먼저 끝나 버리므로, health 응답 직후
``assert not route_task.done()`` 으로 「경로가 아직 막혀 있는데 루프는 살아 있었다」를 확인한다.
"""
from __future__ import annotations

import asyncio
import threading
from contextlib import nullcontext
from unittest.mock import MagicMock, patch

import httpx
import pytest

from app.main import app
from app.schemas import responses
from tests.test_store_gate_routes import _gate_backend

# 경로별: 요청 경로·본문, 서비스 함수 patch 대상, 커넥션 patch 대상(python 은 없음), 성공 응답 값
ROUTES = {
    "sql": dict(
        path="/execute/sql",
        body={"tenantId": 7, "query": "SELECT 1"},
        service="app.routers.sql.sql_executor.execute_sql",
        conn="app.db.connection.get_connection",
        ok=lambda: responses.SqlExecuteResponse(
            success=True, rows=[], columns=[], row_count=0, execution_log="ok"
        ),
    ),
    "load": dict(
        path="/execute/load",
        body={"tenantId": 7, "table": "t", "rows": [{"a": 1}]},
        service="app.routers.load.load_executor.execute_load",
        conn="app.db.connection.get_connection",
        ok=lambda: responses.LoadResponse(success=True, rows_loaded=1),
    ),
    "probe": dict(
        path="/execute/probe",
        body={"tenantId": 7, "query": "SELECT 1"},
        service="app.routers.probe.probe_executor.execute_probe",
        conn="app.db.connection.get_connection",
        ok=lambda: responses.ProbeResponse(success=True),
    ),
    "query": dict(
        path="/execute/query",
        body={"tenantId": 7, "query": "SELECT 1", "max_rows": 10, "read_only": True},
        service="app.routers.query.query_executor.execute_query",
        conn="app.db.connection.get_connection",
        ok=lambda: responses.QueryExecuteResponse(success=True, row_count=1, query_type="SELECT"),
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
        service="app.routers.api_call.api_call_executor.execute_api_call",
        conn="app.db.connection.get_connection",
        ok=lambda: responses.ApiCallExecuteResponse(success=True, rows_loaded=3),
    ),
    "python": dict(
        path="/execute/python",
        body={"tenantId": 7, "script": "print(1)", "readSlot": 1},
        service="app.routers.python_exec.python_executor.execute_python",
        conn=None,  # python 라우트는 get_connection 을 쓰지 않는다
        ok=lambda: responses.PythonExecuteResponse(success=True, output="hi", exit_code=0, execution_time_ms=5),
    ),
}


@pytest.mark.parametrize("name", list(ROUTES))
async def test_health_answers_while_route_blocks(mock_auth_header, name):
    r = ROUTES[name]
    entered = threading.Event()
    release = threading.Event()

    def blocking_service(*_args, **_kwargs):
        """서비스 함수 대역 — 들어왔음을 알리고 release 까지(최대 5초) 블로킹한다. 시간 제한이 있어 루프가 막혀도 스위트는 멈추지 않는다."""
        entered.set()
        release.wait(5)
        return r["ok"]()

    conn_patch = nullcontext() if r["conn"] is None else patch(r["conn"], MagicMock())
    with _gate_backend(kind="POSTGRESQL"), patch(r["service"], side_effect=blocking_service), conn_patch:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
            route_task = asyncio.create_task(client.post(r["path"], json=r["body"], headers=mock_auth_header))
            try:
                # 경로가 서비스 함수 안에 들어갈 때까지 기다린다(대기 자체는 스레드로 — 루프를 막지 않는다).
                assert await asyncio.to_thread(entered.wait, 2)
                # 경로가 블로킹된 동안에도 루프가 다른 요청에 응답해야 한다(풀 미초기화면 503 이라 200/503 모두 응답으로 본다).
                health = await asyncio.wait_for(client.get("/health"), 1.0)
                assert health.status_code in (200, 503)
                # 루프가 막혔다면 위 대기 동안 가짜가 시간 초과로 끝나 경로가 먼저 끝났을 것이다 — 아직 막혀 있어야 한다.
                assert not route_task.done()
            finally:
                release.set()
            resp = await asyncio.wait_for(route_task, 10)
    assert resp.status_code == 200
    assert resp.json()["success"] is True
