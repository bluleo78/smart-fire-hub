"""API_CALL 인증 공용 사례 표(WD-657) — Java ``ApiCallAuthVectorsTest`` 와 같은 표로 Python 실행기를 검증한다.

픽스처 ``apps/firehub-api/src/test/resources/fixtures/api-call-auth-vectors.json`` 은 api 의 Java 테스트도 읽는다 —
리다이렉트 인증 출처 판정, Bearer 접두 정리, 헤더 이름 공백 정리, 비밀 가림이 두 실행기에서 같아야 한다. 한쪽 규칙만 바꾸면
어느 한쪽 테스트가 깨진다.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.services import api_call_errors as errors
from app.services import api_call_executor

_FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "firehub-api/src/test/resources/fixtures/api-call-auth-vectors.json"
)
_DATA = json.loads(_FIXTURE.read_text(encoding="utf-8"))


def _ids(section: str):
    return [c["name"] for c in _DATA[section]]


def test_fixture_is_loaded():
    # 표가 비면 아래 파라미터 테스트가 공허하게 통과한다 — 갈래마다 사례가 실제로 읽혔는지 본다.
    for section in (
        "sameAuthOrigin",
        "bearerToken",
        "headerName",
        "scrub",
        "redirectLocation",
        "redirectMethod",
        "basicAuth",
        "maskUserInfo",
        "basicScrub",
        "redirectFollow",
    ):
        assert len(_DATA[section]) >= 4, section


@pytest.mark.parametrize("case", _DATA["sameAuthOrigin"], ids=_ids("sameAuthOrigin"))
def test_same_auth_origin_matches_vector(case):
    assert api_call_executor._same_auth_origin(case["origin"], case["target"]) is case["expected"]


@pytest.mark.parametrize("case", _DATA["bearerToken"], ids=_ids("bearerToken"))
def test_bearer_token_matches_vector(case):
    resolved = api_call_executor._resolve_auth({"authType": "BEARER", "token": case["raw"]})
    assert resolved.secret == case["expected"]


@pytest.mark.parametrize("case", _DATA["headerName"], ids=_ids("headerName"))
def test_header_name_matches_vector(case):
    # 기본 헤더 이름만 두 언어의 대소문자가 달라(X-API-Key·x-api-key) 대소문자를 무시하고 비교한다.
    resolved = api_call_executor._resolve_auth(case["auth"])
    assert resolved.header_name.lower() == case["expected"].lower()


@pytest.mark.parametrize("case", _DATA["scrub"], ids=_ids("scrub"))
def test_scrub_secret_matches_vector(case):
    assert errors.scrub_secret(case["text"], errors.SentSecrets(key=case["secret"])) == case["expected"]


@pytest.mark.parametrize("case", _DATA["redirectLocation"], ids=_ids("redirectLocation"))
def test_redirect_location_matches_vector(case):
    # 리다이렉트 Location 해석(WD-676) — _do_request 가 쓰는 해석(urljoin + 프래그먼트 떼기, WD-686)이 Java RedirectLocations 와
    # 같은 표에서 같은 주소를 낸다.
    assert api_call_executor._resolve_redirect_location(case["base"], case["location"]) == case["expected"]


@pytest.mark.parametrize("case", _DATA["redirectMethod"], ids=_ids("redirectMethod"))
def test_redirect_method_matches_vector(case):
    # 리다이렉트 홉 메서드·본문 판정(WD-686) — 규칙은 _redirect_keeps_method_and_body 참고.
    keeps = api_call_executor._redirect_keeps_method_and_body(case["status"], case["sameOrigin"])
    assert keeps is case["keepsBody"]


@pytest.mark.parametrize("case", _DATA["basicAuth"], ids=_ids("basicAuth"))
def test_basic_auth_matches_vector(case):
    # BASIC(WD-681) — Java ApiCallAuth 와 같은 「Authorization: Basic …」 을 붙이고, 비밀번호와 Base64 덩어리를 가릴 비밀로 본다.
    resolved = api_call_executor._resolve_auth(case["auth"])
    headers: dict = {}
    params: dict = {}
    assert api_call_executor._apply_resolved_auth(resolved, headers, params) is None
    assert headers == {"Authorization": case["expectedHeader"]}
    assert params == {}
    assert resolved.sent_secrets == errors.SentSecrets(
        None, case["expectedHeader"][len("Basic "):], case["auth"]["password"]
    )


@pytest.mark.parametrize("case", _DATA["maskUserInfo"], ids=_ids("maskUserInfo"))
def test_mask_userinfo_matches_vector(case):
    # 실행 기록 요약 줄의 URL 사용자 정보 가림(WD-681) — Java BaseUrlUserInfo.mask 와 같다.
    assert errors.mask_url_userinfo(case["url"]) == case["expected"]


@pytest.mark.parametrize("case", _DATA["basicScrub"], ids=_ids("basicScrub"))
def test_basic_scrub_matches_vector(case):
    # BASIC 비밀 가림(WD-681 리뷰) — Basic 덩어리는 늘 고정 ****, 비밀번호는 SMTP 규칙(지우고 남으면 문장을 통째로 가림). Java
    # ApiCallAuthVectorsTest.basicScrub_matchesVector 와 같은 표다.
    resolved = api_call_executor._resolve_auth(
        {"authType": "BASIC", "username": case["username"], "password": case["password"]}
    )
    assert errors.scrub_secret(case["text"], resolved.sent_secrets) == case["expected"]


@pytest.mark.parametrize("case", _DATA["redirectFollow"], ids=_ids("redirectFollow"))
def test_redirect_follow_matches_vector(case):
    # 리다이렉트로 따라가는 상태인지(WD-693) — 301·302·303·307·308 만 따라가고 300·304·305 등은 일반 응답(Java RedirectLocations.isFollowed 와 같다).
    assert api_call_executor._is_followed_redirect(case["status"]) is case["follows"]
