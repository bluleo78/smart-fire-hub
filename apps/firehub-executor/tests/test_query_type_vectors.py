"""``_detect_query_type`` 가 api ``SqlValidationUtils.detectQueryType`` 와 같은 분류를 내는지 공용 픽스처로 대조한다(WD-91).

**픽스처 ``apps/firehub-api/src/test/resources/fixtures/query-type-vectors.json`` 은 api 의
``SqlValidationUtilsTest`` 와 이 테스트가 함께 읽는다.** 문 타입은 읽기 전용 판정의 근거라, 운영(executor 경로)과
api 의 분류가 갈리면 한쪽만 DML 을 SELECT 로 보아 읽기 전용을 지나게 된다.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.services.query_executor import _detect_query_type

# tests → firehub-executor → apps
_FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "firehub-api/src/test/resources/fixtures/query-type-vectors.json"
)
_VECTORS = json.loads(_FIXTURE.read_text(encoding="utf-8"))


def test_fixture_has_vectors():
    """픽스처가 비면 아래 파라미터 테스트가 공허하게 통과한다 — 벡터가 실제로 읽혔는지 확인한다."""
    assert len(_VECTORS["classify"]) >= 50


@pytest.mark.parametrize("case", _VECTORS["classify"], ids=lambda c: repr(c["sql"]))
def test_detect_query_type_matches_fixture(case):
    assert _detect_query_type(case["sql"]) == case["type"]
