"""실행기 DB 계약 벡터(WD-77)를 Python 실행기에 대조한다.

벡터 파일 ``executor-db-contract-vectors.json`` 은 db-executor(Java)의 ``ExecutorContractVectorsTest`` 와 공유한다.
``required`` 는 db-executor 기준이라(예: max_rows·read_only 필수) Python 에는 글자 그대로 적용하지 않는다 —
여기서는 응답 키 집합, 오류 응답의 ``db_error`` 모양, 예시 요청의 성공/실패만 본다. ``backend`` 가
``db-executor`` 인 예시는 건너뛴다.
"""
from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

_FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "firehub-api/src/test/resources/fixtures/executor-db-contract-vectors.json"
)
_VECTORS = json.loads(_FIXTURE.read_text(encoding="utf-8"))
_ENDPOINTS = _VECTORS["endpoints"]
_DB_ERROR_KEYS = {f["name"] for f in _VECTORS["dbErrorFields"]}

_COLS = [("a", 23, None, 4, None, None, None)]


class _PgError(Exception):
    """psycopg2 오류 대역 — pgcode 만 있으면 sqlstate() 가 SQLState 로 인식한다."""

    pgcode = "42601"


def _cursor(fail: bool):
    """성공/실패를 흉내 내는 커서. fail 이면 execute 가 PG 오류를 던진다."""
    cur = MagicMock()
    cur.description = _COLS
    cur.fetchall.return_value = [(1,)]
    cur.rowcount = 1
    if fail:
        cur.execute.side_effect = _PgError("syntax error")
    cur.__enter__.return_value = cur
    return cur


def _conn_cm(cur):
    conn = MagicMock()
    conn.cursor.return_value = cur
    cm = MagicMock()
    cm.return_value.__enter__.return_value = conn
    return cm


def _post(client, headers, name, body, fail):
    path = _ENDPOINTS[name]["path"]
    gate = MagicMock()
    gate.peek.return_value = SimpleNamespace(backend="POSTGRESQL")
    cm = _conn_cm(_cursor(fail))
    # load 는 psycopg2 execute_values 가 모의 커서로는 동작하지 않아 insert_batch 자체를 대역으로 둔다(성공/실패만 흉내).
    insert = patch(
        "app.services.load_executor.insert_batch", side_effect=_PgError("syntax error") if fail else None
    )
    with patch("app.routers.store_gate.get_store_client", return_value=gate), \
            patch(f"app.routers.{name}.get_connection", cm), insert:
        return client.post(path, json=body, headers=headers)


def _cases():
    for name, ep in _ENDPOINTS.items():
        for ex in ep["examples"]:
            if ex.get("backend") in (None, "python"):
                yield pytest.param(name, ex, id=f"{name}-{ex['name']}")


def test_vectors_loaded():
    assert set(_ENDPOINTS) >= {"sql", "query", "probe", "load"}


@pytest.mark.parametrize(
    "name,ex",
    [
        pytest.param(name, ex, id=f"{name}-{ex['name']}")
        for name in ("sql", "load")
        for ex in _ENDPOINTS[name]["examples"]
        if "expectedStatementTimeoutMs" in ex and ex.get("backend") in (None, "python")
    ],
)
def test_default_timeout_vectors_apply_to_pg_body(test_client, mock_auth_header, name, ex):
    # 벡터의 기대값은 실제 PG 경계로 나가는 SET LOCAL 문장에 대조한다.
    cur = _cursor(False)
    gate = MagicMock()
    gate.peek.return_value = SimpleNamespace(backend="POSTGRESQL")
    with patch("app.routers.store_gate.get_store_client", return_value=gate), \
            patch(f"app.routers.{name}.get_connection", _conn_cm(cur)), \
            patch("app.services.load_executor.insert_batch"):
        resp = test_client.post(_ENDPOINTS[name]["path"], json=ex["request"], headers=mock_auth_header)
    assert resp.status_code == 200 and resp.json()["success"] is True
    assert [call.args[0] for call in cur.execute.call_args_list][0] == (
        f"SET LOCAL statement_timeout = {ex['expectedStatementTimeoutMs']}"
    )


def test_tibero_default_load_deadline_vector_matches_python_default():
    # 리뷰 I-5: 「값이 없으면 두 실행기 모두 기본 1800초」— db-executor 는 이 벡터의 expectedDeadlineSeconds 를 실제 기한과 대조하고,
    # Python 실행기는 같은 값이 자기 기본 적재 제한(설정 기본값)과 같은지 본다. 어느 한쪽 기본값만 바뀌면 여기서 깨진다.
    from app.config import Settings

    vectors = [
        ex for ex in _ENDPOINTS["load"]["examples"]
        if ex.get("backend") == "db-executor" and "expectedDeadlineSeconds" in ex
    ]
    assert vectors, "Tibero 기본 적재 제한 벡터가 없다"
    for ex in vectors:
        assert ex["expectedDeadlineSeconds"] == Settings.model_fields["load_statement_timeout_seconds"].default


@pytest.mark.parametrize("name,ex", list(_cases()))
def test_examples(test_client, mock_auth_header, name, ex):
    resp = _post(test_client, mock_auth_header, name, ex["request"], fail=False)
    if not ex["expectSuccess"]:
        # Python 은 필수 필드 누락·timeout_seconds ≤ 0 을 스키마 검증 422 로 거부한다(fail-closed) — 계약의 '실패' 에 해당한다.
        assert resp.status_code == 422
        return
    assert resp.status_code == 200
    data = resp.json()
    assert data["success"] is ex["expectSuccess"]
    assert set(_ENDPOINTS[name]["responseFields"]) <= set(data)
    if ex["expectSuccess"]:
        assert data["db_error"] is None


@pytest.mark.parametrize("name", ["sql", "query", "probe", "load"])
def test_failure_has_db_error_shape(test_client, mock_auth_header, name):
    ex = next(e for e in _ENDPOINTS[name]["examples"] if e["expectSuccess"] and e.get("backend") in (None, "python"))
    data = _post(test_client, mock_auth_header, name, ex["request"], fail=True).json()
    assert data["success"] is False and data["error"]
    assert set(_ENDPOINTS[name]["responseFields"]) <= set(data)
    assert set(data["db_error"]) == _DB_ERROR_KEYS
    assert data["db_error"]["code"] == 0
    assert data["db_error"]["sql_state"] == "42601"
    assert data["db_error"]["message"]


def test_sql_keeps_pg_error(test_client, mock_auth_header):
    """pg_error 는 PG 경로 그대로 유지된다(db_error 는 추가일 뿐)."""
    ex = _ENDPOINTS["sql"]["examples"][0]
    data = _post(test_client, mock_auth_header, "sql", ex["request"], fail=True).json()
    assert data["pg_error"]["sql_state"] == "42601"


def test_probe_tibero_refused(test_client, mock_auth_header):
    from app.routers import store_gate

    gate = MagicMock()
    gate.peek.return_value = SimpleNamespace(backend="TIBERO")
    conn = MagicMock()
    with patch("app.routers.store_gate.get_store_client", return_value=gate), \
            patch("app.routers.probe.get_connection", conn):
        data = test_client.post(
            "/execute/probe", json={"tenantId": 7, "query": "SELECT 1"}, headers=mock_auth_header
        ).json()
    assert data["success"] is False
    assert data["error"] == store_gate.PROBE_UNSUPPORTED
    assert data["db_error"]["message"] == store_gate.PROBE_UNSUPPORTED
    conn.assert_not_called()
