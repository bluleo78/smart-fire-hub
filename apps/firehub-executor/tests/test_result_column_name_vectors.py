"""``_unique_column_names`` 가 api ``ResultColumnNames.unique`` 와 같은 결과를 내는지 공용 픽스처로 대조한다(WD-85).

**픽스처 ``apps/firehub-api/src/test/resources/fixtures/result-column-name-vectors.json`` 은 api 의
``ResultColumnNamesTest`` 와 이 테스트가 함께 읽는다.** 운영은 executor 경로, 로컬·테스트는 직접 실행 경로라서
두 구현의 이름 규칙이 갈리면 저장한 차트가 가리키는 컬럼(예: ``name_2``)을 한쪽 환경에서 못 찾는다.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.services.query_executor import _unique_column_names

# tests → firehub-executor → apps
_FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "firehub-api/src/test/resources/fixtures/result-column-name-vectors.json"
)
_VECTORS = json.loads(_FIXTURE.read_text(encoding="utf-8"))


def test_fixture_has_vectors():
    """픽스처가 비면 아래 파라미터 테스트가 공허하게 통과한다 — 벡터가 실제로 읽혔는지 확인한다."""
    assert len(_VECTORS["unique"]) >= 8


@pytest.mark.parametrize("case", _VECTORS["unique"], ids=lambda c: repr(c["in"]))
def test_unique_column_names_matches_fixture(case):
    assert _unique_column_names(case["in"]) == case["out"]
