"""쿼리 API 키 인코딩 규칙이 api(Java ``QueryApiKeyEncoding``)와 같은지 공용 사례 표로 대조한다(WD-182).

**픽스처 ``apps/firehub-api/src/test/resources/fixtures/query-api-key-encoding-vectors.json`` 은 api 의
``QueryApiKeyEncodingTest`` 와 이 테스트가 함께 읽는다.** 판정(유효한 ``%XX`` 가 있으면 이미 인코딩됨)이나
인코딩 규칙을 한쪽만 고치면 어느 한쪽 테스트가 깨진다 — 연결 테스트·미리보기·Java 실행기와 Python 실행기가 대상
서버에 같은 바이트를 보낸다는 근거다.

아래쪽 TC 는 실제 ``_do_request`` 를 httpx ``MockTransport`` 로 돌려, httpx 가 대상 서버로 보내는 원시
경로·쿼리(``raw_path``)를 단언한다 — 규칙 함수만 맞고 httpx 가 다시 인코딩하면(예전 ``params=`` 경로) 잡지 못하기
때문이다. 키 값은 모두 시험용 가짜 키다.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import List
from unittest.mock import patch

import httpx
import pytest

from app.services import api_call_executor
from app.services.query_key_encoding import encode_query_key, is_pre_encoded

# tests → firehub-executor → apps
_FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "firehub-api/src/test/resources/fixtures/query-api-key-encoding-vectors.json"
)
_CASES = json.loads(_FIXTURE.read_text(encoding="utf-8"))["cases"]

# api 의 ApiConnectionTestUrlMaskingTest·ApiCallPreviewServiceTest·ApiCallExecutorTest 와 같은 기대값.
_PUBLIC_KEY_ENCODED = "rP1x%2FAbC%2Bd%3D%3D"
_PUBLIC_KEY_DECODED = "rP1x/AbC+d=="


def test_fixture_has_both_judgements():
    """표가 비면 아래 파라미터 테스트가 공허하게 통과한다 — 사례가 실제로 읽혔고 두 판정이 모두 있는지 확인한다."""
    assert len(_CASES) >= 20
    assert any(c["preEncoded"] for c in _CASES)
    assert any(not c["preEncoded"] for c in _CASES)


@pytest.mark.parametrize("case", _CASES, ids=[c["name"] for c in _CASES])
def test_matches_shared_vector(case):
    assert is_pre_encoded(case["input"]) is case["preEncoded"]
    assert encode_query_key(case["input"]) == case["encoded"]


@pytest.mark.parametrize("case", _CASES, ids=[c["name"] for c in _CASES])
def test_idempotent(case):
    """한 번 인코딩한 결과를 다시 넣어도 바뀌지 않는다 — 인코딩 키·디코딩 키가 같은 값으로 모이는 근거(이중 인코딩 없음)."""
    assert encode_query_key(case["encoded"]) == case["encoded"]


def test_none():
    assert encode_query_key(None) is None
    assert is_pre_encoded(None) is False


# ---------------------------------------------------------------------------
# 실제로 나가는 바이트 — _do_request 를 MockTransport 로 돌린다
# ---------------------------------------------------------------------------

_REAL_CLIENT = httpx.Client


def _send(url: str, query_params: dict, auth: dict, responses=None) -> List[bytes]:
    """_do_request 를 한 번 돌리고 대상 서버가 받은 원시 경로+쿼리 목록을 돌려준다."""
    received: List[bytes] = []
    queue = list(responses or [])

    def handler(request: httpx.Request) -> httpx.Response:
        received.append(request.url.raw_path)
        if queue:
            return queue.pop(0)
        return httpx.Response(200, json={"items": []})

    def client_factory(*args, **kwargs):
        kwargs["transport"] = httpx.MockTransport(handler)
        return _REAL_CLIENT(*args, **kwargs)

    with patch("httpx.Client", side_effect=client_factory), patch(
        "app.services.api_call_executor.validate_url"
    ):
        api_call_executor._do_request(
            url=url,
            method="GET",
            headers={},
            query_params=query_params,
            body=None,
            auth=auth,
            timeout_sec=5,
            max_bytes=1024 * 1024,
        )
    return received


def _query_auth(key: str) -> dict:
    # Java decryptedAuth 형식(실행 요청의 auth 그대로).
    return {"authType": "API_KEY", "placement": "query", "paramName": "serviceKey", "apiKey": key}


def test_encoded_and_decoded_keys_send_same_bytes():
    """인코딩 키는 그대로(예전엔 %252B 로 이중 인코딩), 디코딩 키는 한 번 인코딩 — 둘 다 같은 바이트, api 와 같은 값."""
    enc = _send("http://public.example.com/api/q", {}, _query_auth(_PUBLIC_KEY_ENCODED))
    dec = _send("http://public.example.com/api/q", {}, _query_auth(_PUBLIC_KEY_DECODED))
    expected = b"/api/q?serviceKey=" + _PUBLIC_KEY_ENCODED.encode()
    assert enc == [expected]
    assert dec == [expected]


def test_special_char_key_encoded_once():
    sent = _send("http://public.example.com/api/q", {}, _query_auth("ex+k/y==비밀 Ww11"))
    assert sent == [b"/api/q?serviceKey=ex%2Bk%2Fy%3D%3D%EB%B9%84%EB%B0%80%20Ww11"]


def test_lowercase_hex_preserved_on_wire():
    sent = _send("http://public.example.com/api/q", {}, _query_auth("abc%2bd%3d"))
    assert sent == [b"/api/q?serviceKey=abc%2bd%3d"]


def test_url_query_static_and_pagination_params_kept_in_java_order():
    """URL 에 있던 쿼리 → 정적·페이지네이션 → 키 순서(Java 와 같음). 예전에는 params= 가 URL 의 쿼리를 지웠다."""
    sent = _send(
        "http://public.example.com/api/q?type=json",
        {"pageNo": "1", "numOfRows": "100"},
        _query_auth(_PUBLIC_KEY_DECODED),
    )
    assert sent == [
        b"/api/q?type=json&pageNo=1&numOfRows=100&serviceKey=" + _PUBLIC_KEY_ENCODED.encode()
    ]


def test_static_param_same_rule_as_key():
    """정적 파라미터도 키와 같은 공용 규칙(WD-200) — 이미 인코딩된 값은 그대로(예전 httpx 경로는 %252B), 생 값은 한 번 인코딩
    (공백은 %20, 예전 httpx 는 +). api 미리보기·Java 실행기와 같은 바이트다."""
    enc = _send("http://public.example.com/api/q", {"token": "abc%2Bd%3D%3D"}, None)
    dec = _send("http://public.example.com/api/q", {"token": "abc+d=="}, None)
    assert enc == [b"/api/q?token=abc%2Bd%3D%3D"]
    assert dec == [b"/api/q?token=abc%2Bd%3D%3D"]
    assert _send("http://public.example.com/api/q", {"q": "a b%2B"}, None) == [b"/api/q?q=a%20b%2B"]


def test_static_param_with_key_on_wire():
    """정적(한글·=·공백)·인증 키가 함께 — 실제로 나가는 바이트가 api 의 ApiCallPreviewServiceTest·ApiCallExecutorTest 기대값과 같다."""
    sent = _send(
        "http://public.example.com/api/q",
        {"q": "a=b 한글"},
        _query_auth("ex+k/y==비밀 Ww11"),
    )
    assert sent == [
        b"/api/q?q=a%3Db%20%ED%95%9C%EA%B8%80&serviceKey=ex%2Bk%2Fy%3D%3D%EB%B9%84%EB%B0%80%20Ww11"
    ]


def test_same_name_static_param_replaced_by_key_at_end():
    """인증 키와 같은 이름의 정적 파라미터는 지우고 키 하나를 맨 끝에 — Java AuthQueryMasker.build 와 같은 순서(WD-200)."""
    sent = _send(
        "http://public.example.com/api/q",
        {"serviceKey": "static-value", "pageNo": "1"},
        _query_auth(_PUBLIC_KEY_ENCODED),
    )
    assert sent == [b"/api/q?pageNo=1&serviceKey=" + _PUBLIC_KEY_ENCODED.encode()]


# ---------------------------------------------------------------------------
# 쿼리 파라미터 공용 사례 표(WD-200) — api 의 QueryParamEncodingVectorsTest 와 같은 파일
# ---------------------------------------------------------------------------

_PARAM_FIXTURE = _FIXTURE.with_name("query-param-encoding-vectors.json")
_PARAM_CASES = json.loads(_PARAM_FIXTURE.read_text(encoding="utf-8"))["cases"]


def test_param_fixture_loaded():
    """표가 비면 아래 파라미터 테스트가 공허하게 통과한다 — 사례가 읽혔고 인증 키 있는·없는 사례가 모두 있는지 확인한다."""
    assert len(_PARAM_CASES) >= 15
    assert any(c["auth"] is not None for c in _PARAM_CASES)
    assert any(c["auth"] is None for c in _PARAM_CASES)


def _params_with_auth(case):
    """실행 요청과 같은 모양으로 만든다 — 정적·페이지네이션 파라미터(순서 유지) + _apply_auth 가 키를 떼어 낸다."""
    params = {name: value for name, value in case["params"]}
    auth = None
    if case["auth"] is not None:
        auth = _query_auth(case["auth"][1])
        auth["paramName"] = case["auth"][0]
    return params, auth


@pytest.mark.parametrize("case", _PARAM_CASES, ids=[c["name"] for c in _PARAM_CASES])
def test_build_request_url_matches_shared_param_vector(case):
    params, auth = _params_with_auth(case)
    headers: dict = {}
    auth_query = api_call_executor._apply_auth(auth, headers, params)
    assert api_call_executor._build_request_url(case["url"], params, auth_query) == case["expected"]


@pytest.mark.parametrize("case", _PARAM_CASES, ids=[c["name"] for c in _PARAM_CASES])
def test_wire_matches_shared_param_vector(case):
    """httpx 가 URL 을 다시 인코딩하지 않고 표의 쿼리 문자열을 그대로 보내는지 — 실제 _do_request 로 확인한다."""
    params, auth = _params_with_auth(case)
    sent = _send(case["url"], params, auth)
    # 대상 서버가 받은 경로+쿼리(디코딩 안 한 원형)가 표의 기대 URL 의 경로+쿼리와 바이트로 같아야 한다.
    assert sent == [case["expected"].split("h.example", 1)[1].encode()]


def test_none_param_value_sent_as_empty():
    """None 값은 빈 값(name=) — Java 도 null 을 빈 값으로 보낸다."""
    assert _send("http://public.example.com/api/q", {"q": None}, None) == [b"/api/q?q="]


def test_no_params_url_untouched():
    sent = _send("http://public.example.com/api/q?x=1", {}, None)
    assert sent == [b"/api/q?x=1"]


def test_redirect_location_query_kept():
    """리다이렉트 Location 의 쿼리를 그대로 따라간다 — 예전에는 params={} 로 Location 의 쿼리(옮겨 온 키)가 지워졌다."""
    redirect = httpx.Response(
        302, headers={"location": "http://public.example.com/v2/q?serviceKey=" + _PUBLIC_KEY_ENCODED}
    )
    sent = _send(
        "http://public.example.com/api/q", {}, _query_auth(_PUBLIC_KEY_DECODED), responses=[redirect]
    )
    assert sent == [
        b"/api/q?serviceKey=" + _PUBLIC_KEY_ENCODED.encode(),
        b"/v2/q?serviceKey=" + _PUBLIC_KEY_ENCODED.encode(),
    ]
