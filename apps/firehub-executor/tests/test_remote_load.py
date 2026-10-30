"""Python 실행기의 db-executor 원격 적재(RemoteLoader) 테스트 (WD-77 Task 6).

핵심 계약: 호출자(PYTHON 스텝)가 값을 ``_apply_type_conversion`` 으로 먼저 변환하고(변환 불가 값이면 ``ValueConversionError`` —
WD-431), RemoteLoader 는 JSON 으로 직렬화만 해 db-executor ``POST /execute/load`` 로 보낸다. db-executor 는 변환 불가 값·빈
문자열(숫자·날짜)을 요청 전체 실패로 처리하므로 빈 문자열은 None 이 되어 도달해야 한다. 직렬화 형태는 공유 벡터
(tibero-bind-vectors.json)가 받아들이는 입력 형태여야 한다.
"""
from __future__ import annotations

import json
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from unittest.mock import MagicMock

import httpx
import pytest

import copy

from app.services.python_executor import ValueConversionError, _apply_type_conversion
from app.db.backends.remote import (
    RemoteLoadError,
    RemoteLoader,
    require_staging_table,
    to_wire_rows,
    to_wire_value,
)
from tests.conftest import get_test_settings

VECTORS = json.loads(
    (
        Path(__file__).resolve().parents[2]
        / "firehub-api/src/test/resources/fixtures/tibero-bind-vectors.json"
    ).read_text(encoding="utf-8")
)
STAGING = "STG_0123456789ABCDEF01234567"


def _accepted(app_type, wire) -> bool:
    """공유 벡터의 bindings(성공 입력)에 (appType, json) 이 **그대로** 있는가 — 같은 JSON 형태를 db-executor 가 받는다."""
    return any(
        b["appType"] == app_type and b["json"] == wire and type(b["json"]) is type(wire)
        for b in VECTORS["bindings"]
    )


def _rejected(app_type, wire) -> bool:
    return any(b["appType"] == app_type and b["json"] == wire for b in VECTORS["expectFailure"])


def _settings(**kw):
    base = dict(db_executor_base_url="http://db-executor:8090", db_executor_load_token="load-secret")
    base.update(kw)
    return get_test_settings(_env_file=None, **base)


def _client(handler):
    return httpx.Client(transport=httpx.MockTransport(handler))


def _converted(rows, types):
    """PYTHON 스텝처럼 적재 전에 행 사본을 변환한다(_run_script 가 RemoteLoader 에 넘기기 전에 하는 일)."""
    rows = copy.deepcopy(rows)
    _apply_type_conversion(rows, types)
    return rows


def _convert_then_wire(rows, types):
    """변환 → 직렬화 — PYTHON 스텝의 원격 적재 경로가 보내는 본문."""
    return to_wire_rows(_converted(rows, types), types)


# ---------------------------------------------------------------------------
# 직렬화 — 변환된 값이 벡터가 받는 JSON 형태가 되는가
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "value, app_type, wire",
    [
        (7, "INTEGER", 7),
        (True, "BOOLEAN", True),
        (False, "BOOLEAN", False),
        (Decimal("12345678901234567890.123456"), "DECIMAL", "12345678901234567890.123456"),
        (date(2024, 3, 5), "DATE", "2024-03-05"),
        (datetime(2024, 3, 5, 1, 2, 3), "TIMESTAMP", "2024-03-05T01:02:03"),
        (datetime(2024, 3, 5, 1, 2, 3, 123456), "TIMESTAMP", "2024-03-05T01:02:03.123456"),
        (
            datetime(2024, 3, 5, 10, 2, 3, tzinfo=timezone(timedelta(hours=9))),
            "TIMESTAMP",
            "2024-03-05T10:02:03+09:00",
        ),
        ("hello", "TEXT", "hello"),
        ("", "TEXT", ""),
        ("POINT(1 2)", "GEOMETRY", "POINT(1 2)"),
        (None, "INTEGER", None),
    ],
)
def test_wire_value_is_an_input_form_the_bind_vectors_accept(value, app_type, wire):
    out = to_wire_value(value, app_type)
    assert out == wire and type(out) is type(wire)
    if value is not None:
        assert _accepted(app_type, out), f"{app_type} {out!r} 가 벡터 bindings 에 없다"


def test_converted_values_roundtrip_through_existing_conversion():
    # 기존 Python 변환(_apply_type_conversion) → 직렬화 → 벡터 입력 형태. JSON 입력(원시 값)에서 출발한다.
    types = {"n": "INTEGER", "d": "DECIMAL", "dt": "DATE", "ts": "TIMESTAMP", "b": "BOOLEAN", "t": "TEXT"}
    rows = [{"n": " 7 ", "d": "12345678901234567890.123456", "dt": "2024-03-05",
             "ts": "2024-03-05T01:02:03", "b": "yes", "t": 123}]
    wire = _convert_then_wire(rows, types)
    assert wire == [{"n": 7, "d": "12345678901234567890.123456", "dt": "2024-03-05",
                     "ts": "2024-03-05T01:02:03", "b": True, "t": "123"}]
    for col, app_type in types.items():
        assert _accepted(app_type, wire[0][col]) or app_type == "TEXT"
    # 입력 행은 건드리지 않는다(호출자가 같은 행을 재사용할 수 있다).
    assert rows[0]["n"] == " 7 "


@pytest.mark.parametrize("app_type, raw", [(r["appType"], r["json"]) for r in VECTORS["expectFailure"]])
def test_expect_failure_inputs_never_reach_db_executor(app_type, raw):
    # 벡터 expectFailure 는 db-executor 가 요청 전체를 실패시킨다 → 원격 경로는 빈 문자열이면 None 으로 보내고, 변환 불가 값이면 보내기 전에
    # 행·키·값을 짚는 오류로 실패한다(WD-431 — 예전엔 None 으로 보내 「완료」였다).
    if isinstance(raw, str) and not raw.strip():
        [row] = _convert_then_wire([{"c": raw}], {"c": app_type})
        assert row["c"] is None
        assert not _rejected(app_type, row["c"])
    else:
        with pytest.raises(ValueConversionError, match="출력 JSON 의 1번째 행 'c' 값"):
            _convert_then_wire([{"c": raw}], {"c": app_type})


@pytest.mark.parametrize("app_type", ["INTEGER", "DECIMAL", "DATE", "TIMESTAMP", "NUMERIC", "FLOAT", "DOUBLE", "DATETIME"])
def test_empty_or_blank_string_becomes_none_for_numeric_and_date_types(app_type):
    # 기존 변환이 빈 문자열을 None 으로 만든다. 변환을 거치지 않은 값(타입 미지정 등)도 직렬화가 막는다.
    assert _convert_then_wire([{"c": ""}], {"c": app_type}) == [{"c": None}]
    assert to_wire_value("  ", app_type) is None


def test_boolean_empty_string_is_none():
    # WD-431: BOOLEAN 빈 문자열은 거짓이 아니라 None(NULL — 데이터 임포트와 같은 규칙). db-executor 는 빈 문자열을 거부하므로 None 으로 보낸다.
    assert _convert_then_wire([{"c": ""}], {"c": "BOOLEAN"}) == [{"c": None}]
    assert to_wire_value(" ", "BOOLEAN") is None


def test_non_finite_decimal_is_none_not_a_request_failure():
    # Decimal("NaN")·"Infinity" 는 기존 변환을 통과하지만 db-executor BigDecimal 이 거부 → None.
    assert to_wire_value(Decimal("NaN"), "DECIMAL") is None
    assert to_wire_value(Decimal("Infinity"), "DECIMAL") is None
    assert _convert_then_wire([{"c": "NaN"}], {"c": "DECIMAL"}) == [{"c": None}]


def test_datetime_in_date_column_is_cut_to_a_date():
    # _convert_single 은 DATE 에 datetime 을 그대로 통과시킨다 — 그대로 보내면 LocalDate.parse 가 요청 전체를 실패시킨다.
    assert to_wire_value(datetime(2024, 3, 5, 1, 2, 3), "DATE") == "2024-03-05"


def test_nested_values_become_json_text():
    assert to_wire_value({"a": 1}, None) == '{"a": 1}'
    assert to_wire_value([1, 2], None) == "[1, 2]"


def test_column_type_names_are_normalised_for_db_executor():
    # db-executor 는 INTEGER·DECIMAL·BOOLEAN·DATE·TIMESTAMP·TEXT·VARCHAR·GEOMETRY 만 안다. 별칭은 같은 뜻으로 바꿔 보낸다.
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={"success": True, "rows_loaded": 1})

    loader = RemoteLoader(_settings(), client=_client(handler))
    types = {"a": "FLOAT", "b": "DATETIME", "c": "STRING"}
    loader.load(7, STAGING, _converted([{"a": "1.5", "b": "2024-03-05T01:02:03Z", "c": 1}], types), types)
    assert captured["body"]["column_type_map"] == {"a": "DECIMAL", "b": "TIMESTAMP", "c": "TEXT"}
    assert captured["body"]["rows"] == [{"a": "1.5", "b": "2024-03-05T01:02:03+00:00", "c": "1"}]


# ---------------------------------------------------------------------------
# RemoteLoader — 요청 모양·인증·오류
# ---------------------------------------------------------------------------

def test_load_posts_to_execute_load_with_load_token_and_returns_rows_loaded():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["method"] = request.method
        seen["auth"] = request.headers.get("authorization")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"success": True, "rows_loaded": 2, "error": None, "db_error": None})

    loader = RemoteLoader(_settings(), client=_client(handler))
    n = loader.load(7, STAGING, [{"id": 1}, {"id": 2}], {"id": "INTEGER"})
    assert n == 2
    assert seen["url"] == "http://db-executor:8090/execute/load"
    assert seen["method"] == "POST"
    assert seen["auth"] == "Internal load-secret"
    assert seen["body"] == {
        "tenantId": 7,
        "table": STAGING,
        "rows": [{"id": 1}, {"id": 2}],
        "column_type_map": {"id": "INTEGER"},
        # 최종 리뷰 I3: 요청마다 Python 쪽 읽기 제한(기본 600초)을 보내 두 쪽이 함께 포기하게 한다.
        "timeout_seconds": 600,
    }


def test_load_sends_read_timeout_as_timeout_seconds_and_caps_explicit_value():
    # 최종 리뷰 I3: db-executor 기한을 Python 읽기 제한과 맞춘다 — Python 이 먼저 포기했는데 db-executor 가 계속 커밋하지 않게.
    # 설정이 소수면 내림(db-executor 가 Python 보다 오래 버티지 않게), 명시값은 읽기 제한을 넘지 못한다.
    bodies = []

    def handler(request: httpx.Request) -> httpx.Response:
        bodies.append(json.loads(request.content))
        return httpx.Response(200, json={"success": True, "rows_loaded": 1})

    loader = RemoteLoader(_settings(db_executor_load_timeout_seconds=123.9), client=_client(handler))
    loader.load(7, STAGING, [{"id": 1}], None)
    loader.load(7, STAGING, [{"id": 1}], None, timeout_seconds=30)
    loader.load(7, STAGING, [{"id": 1}], None, timeout_seconds=5000)
    assert "column_type_map" not in bodies[0]
    assert [b["timeout_seconds"] for b in bodies] == [123, 30, 123]


def test_tiny_read_timeout_still_sends_at_least_one_second():
    # db-executor 는 timeout_seconds <= 0 을 거부한다 — 1초 미만 설정도 1 로 보낸다.
    bodies = []

    def handler(request: httpx.Request) -> httpx.Response:
        bodies.append(json.loads(request.content))
        return httpx.Response(200, json={"success": True, "rows_loaded": 1})

    RemoteLoader(_settings(db_executor_load_timeout_seconds=0.5), client=_client(handler)).load(
        7, STAGING, [{"id": 1}], None
    )
    assert bodies[0]["timeout_seconds"] == 1


# ---------------------------------------------------------------------------
# 청크 적재(최종 리뷰 I3) — 한 요청이 db-executor 공용 힙을 다 쓰지 않게 나눠 보낸다
# ---------------------------------------------------------------------------

def test_large_load_is_sent_in_chunks_in_order_and_rows_loaded_summed():
    from app.db.backends.remote import LOAD_CHUNK_ROWS

    assert LOAD_CHUNK_ROWS == 5000
    bodies = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        bodies.append(body)
        return httpx.Response(200, json={"success": True, "rows_loaded": len(body["rows"])})

    rows = [{"id": i} for i in range(12_001)]
    loaded = RemoteLoader(_settings(), client=_client(handler)).load(7, STAGING, rows, {"id": "INTEGER"})

    assert loaded == 12_001
    assert [len(b["rows"]) for b in bodies] == [5000, 5000, 2001]
    # 순서 보존 — 이어 붙이면 입력 그대로(db-executor 가 호출 사이 _SEQ 를 이어 매긴다).
    assert [r["id"] for b in bodies for r in b["rows"]] == list(range(12_001))
    # 모든 청크가 같은 테넌트·스테이징·타입·기한을 싣는다.
    assert all(b["tenantId"] == 7 and b["table"] == STAGING for b in bodies)
    assert all(b["column_type_map"] == {"id": "INTEGER"} and b["timeout_seconds"] == 600 for b in bodies)


def test_chunk_failure_stops_and_reports_that_failure():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        calls.append(len(body["rows"]))
        if len(calls) == 2:
            return httpx.Response(200, json={"success": False, "rows_loaded": 0, "error": "둘째 청크 값 오류"})
        return httpx.Response(200, json={"success": True, "rows_loaded": len(body["rows"])})

    rows = [{"id": i} for i in range(12_001)]
    with pytest.raises(RemoteLoadError, match="둘째 청크 값 오류"):
        RemoteLoader(_settings(), client=_client(handler)).load(7, STAGING, rows, None)
    # 실패한 청크 뒤로는 보내지 않는다(이미 들어간 행은 스테이징에만 있고 api 가 실패 시 스테이징을 지운다).
    assert calls == [5000, 5000]


def test_chunk_row_count_mismatch_stops_with_count_error():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(1)
        return httpx.Response(200, json={"success": True, "rows_loaded": 4999})

    with pytest.raises(RemoteLoadError, match="적재 행 수"):
        RemoteLoader(_settings(), client=_client(handler)).load(7, STAGING, [{"id": i} for i in range(6000)], None)
    assert len(calls) == 1


def test_empty_rows_do_not_call_db_executor():
    # db-executor 는 빈 rows 를 요청 전체 실패로 처리한다 — 보내지 않고 0 을 돌려준다.
    handler = MagicMock()
    loader = RemoteLoader(_settings(), client=_client(handler))
    assert loader.load(7, STAGING, [], {"id": "INTEGER"}) == 0
    handler.assert_not_called()


def test_failure_response_raises_with_response_error_message():
    def handler(request):
        return httpx.Response(200, json={"success": False, "rows_loaded": 0, "error": "적재 실패 — 값 오류", "db_error": None})

    loader = RemoteLoader(_settings(), client=_client(handler))
    with pytest.raises(RemoteLoadError, match="적재 실패 — 값 오류"):
        loader.load(7, STAGING, [{"id": 1}], None)


@pytest.mark.parametrize("status", [401, 403, 500])
def test_http_error_status_raises_without_token(status):
    def handler(request):
        return httpx.Response(status, text="boom")

    loader = RemoteLoader(_settings(), client=_client(handler))
    with pytest.raises(RemoteLoadError) as e:
        loader.load(7, STAGING, [{"id": 1}], None)
    assert "load-secret" not in str(e.value)
    assert str(status) in str(e.value)


def test_transport_error_raises_remote_load_error_without_url_or_token():
    def handler(request):
        raise httpx.ConnectError("connection refused to http://db-executor:8090 token load-secret")

    loader = RemoteLoader(_settings(), client=_client(handler))
    with pytest.raises(RemoteLoadError) as e:
        loader.load(7, STAGING, [{"id": 1}], None)
    assert "load-secret" not in str(e.value) and "8090" not in str(e.value)
    assert "db-executor" in str(e.value)


def test_non_json_body_raises():
    def handler(request):
        return httpx.Response(200, text="<html>")

    loader = RemoteLoader(_settings(), client=_client(handler))
    with pytest.raises(RemoteLoadError):
        loader.load(7, STAGING, [{"id": 1}], None)


@pytest.mark.parametrize("kw", [dict(db_executor_base_url=""), dict(db_executor_load_token="")])
def test_unconfigured_loader_fails_closed_before_any_request(kw):
    handler = MagicMock()
    loader = RemoteLoader(_settings(**kw), client=_client(handler))
    with pytest.raises(RemoteLoadError, match="db-executor") as e:
        loader.load(7, STAGING, [{"id": 1}], None)
    handler.assert_not_called()
    # 이 문구는 api 스텝 오류로 사용자에게 보인다 — 환경 변수 이름을 싣지 않는다(코드리뷰 3, 자세한 사유는 실행기 WARN 로그).
    assert "DB_EXECUTOR_" not in str(e.value)


@pytest.mark.parametrize(
    "table",
    ["rtmp_abc", "stg_0123456789abcdef01234567", "STG_0123456789ABCDEF0123456", "STG_0123456789ABCDEF012345678",
     "STG_0123456789ABCDEF0123456G", STAGING + "\n", "", "t; DROP TABLE x"],
)
def test_load_refuses_non_staging_table_names(table):
    handler = MagicMock()
    loader = RemoteLoader(_settings(), client=_client(handler))
    with pytest.raises(RemoteLoadError):
        loader.load(7, table, [{"id": 1}], None)
    handler.assert_not_called()


def test_require_staging_table_accepts_only_uppercase_hex_24():
    assert require_staging_table(STAGING) == STAGING
    with pytest.raises(ValueError):
        require_staging_table(STAGING.lower())


def test_default_client_has_explicit_timeouts_and_is_shared_by_all_chunks_of_one_load(monkeypatch):
    # 기본 httpx 타임아웃(5초)이면 실제 적재가 끊긴다 — 읽기는 적재 제한 시간, 연결은 10초(요청=청크마다 적용).
    # 코드리뷰 8: 클라이언트는 load() 한 번에 하나만 열어 모든 청크가 같이 쓰고(연결 재사용), load() 가 끝나면 닫는다.
    made = []

    class FakeClient:
        def __init__(self, timeout):
            self.timeout = timeout
            self.closed = False
            self.posts = 0
            made.append(self)

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            self.closed = True

        def post(self, url, json, headers):
            assert not self.closed
            self.posts += 1
            return httpx.Response(200, json={"success": True, "rows_loaded": len(json["rows"])})

    monkeypatch.setattr("app.db.backends.remote.httpx.Client", FakeClient)
    monkeypatch.setattr("app.db.backends.remote.LOAD_CHUNK_ROWS", 2)
    loader = RemoteLoader(_settings(db_executor_load_timeout_seconds=123.0))
    assert loader.load(7, STAGING, [{"id": i} for i in range(5)], None) == 5
    assert len(made) == 1 and made[0].posts == 3 and made[0].closed
    assert made[0].timeout.read == 123.0 and made[0].timeout.connect == 10.0
    # 다음 load() 는 새 클라이언트 — 호출 사이에 소켓·풀을 남기지 않는다.
    assert loader.load(7, STAGING, [{"id": 9}], None) == 1
    assert len(made) == 2 and made[1].closed
    # 보낼 행이 없거나 설정이 없으면 클라이언트를 만들지 않는다.
    assert loader.load(7, STAGING, [], None) == 0
    with pytest.raises(RemoteLoadError):
        RemoteLoader(_settings(db_executor_base_url="")).load(7, STAGING, [{"id": 1}], None)
    assert len(made) == 2


@pytest.mark.parametrize("status", [400, 401, 500])
def test_non_200_with_json_error_body_raises_that_error(status):
    def handler(request):
        return httpx.Response(status, json={"success": False, "error": "스테이징 이름이 올바르지 않습니다"})

    loader = RemoteLoader(_settings(), client=_client(handler))
    with pytest.raises(RemoteLoadError, match="스테이징 이름이 올바르지 않습니다"):
        loader.load(7, STAGING, [{"id": 1}], None)


@pytest.mark.parametrize("body", [{"success": True}, {"success": True, "rows_loaded": None},
                                  {"success": True, "rows_loaded": 1}, {"success": True, "rows_loaded": 3},
                                  {"success": True, "rows_loaded": "2"}, {"success": True, "rows_loaded": True}])
def test_success_requires_rows_loaded_equal_to_rows_sent(body):
    def handler(request):
        return httpx.Response(200, json=body)

    loader = RemoteLoader(_settings(), client=_client(handler))
    with pytest.raises(RemoteLoadError, match="적재 행 수"):
        loader.load(7, STAGING, [{"id": 1}, {"id": 2}], None)


def test_wire_fixture_matches_what_remote_loader_sends():
    # 공유 사례(python-remote-load-wire.json): 원시 입력 → 변환(PYTHON 스텝) → RemoteLoader 가 db-executor 로 보내는 본문. 같은 사례를
    # db-executor 실서버 테스트(TiberoLiveLoadTest)가 그대로 적재해 값까지 확인한다.
    fixture = json.loads(
        (
            Path(__file__).resolve().parents[2]
            / "firehub-api/src/test/resources/fixtures/python-remote-load-wire.json"
        ).read_text(encoding="utf-8")
    )
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={"success": True, "rows_loaded": len(captured["body"]["rows"])})

    loader = RemoteLoader(_settings(), client=_client(handler))
    loader.load(7, STAGING, _converted(fixture["input"], fixture["rawColumnTypes"]), fixture["rawColumnTypes"])
    assert captured["body"]["rows"] == fixture["wire"]
    assert captured["body"]["column_type_map"] == fixture["wireColumnTypes"]
