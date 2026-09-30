"""``_mask_sql``/``_normalize_sql`` 이 Java ``SqlLexicalMask`` 와 같은 결과를 내는지 공용 픽스처로 대조한다(#746).

**픽스처 ``apps/firehub-api/src/test/resources/fixtures/sql-lexical-vectors.json`` 은 api 의
``SqlLexicalMaskTest`` 와 이 테스트가 함께 읽는다.** 주석·리터럴 인지 규칙(줄/블록 주석, 문자열·E 문자열,
따옴표 식별자, 달러 인용, 식별자 덩어리, PostgreSQL 공백 집합)을 한쪽만 고치면 어느 한쪽 테스트가 깨진다.

왜 표를 복제하지 않고 파일을 읽는가: 두 언어가 같은 파일을 읽어야 "같은 값"이 보장된다 — 복제 표는
한쪽만 갱신되는 드리프트가 생긴다(``test_tenant_schema_vectors.py`` 의 복제 방식과 다른 이유).
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.services.query_executor import _mask_sql, _normalize_sql

# tests → firehub-executor → apps
_FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "firehub-api/src/test/resources/fixtures/sql-lexical-vectors.json"
)
_VECTORS = json.loads(_FIXTURE.read_text(encoding="utf-8"))


def test_fixture_has_vectors():
    """픽스처가 비면 아래 파라미터 테스트가 공허하게 통과한다 — 벡터가 실제로 읽혔는지 확인한다."""
    assert len(_VECTORS["mask"]) >= 10
    assert len(_VECTORS["stripTrailing"]) >= 20


@pytest.mark.parametrize("case", _VECTORS["mask"], ids=lambda c: repr(c["sql"]))
def test_mask_matches_fixture(case):
    assert _mask_sql(case["sql"]) == case["mask"]


@pytest.mark.parametrize("case", _VECTORS["stripTrailing"], ids=lambda c: repr(c["sql"]))
def test_strip_trailing_matches_fixture(case):
    assert _normalize_sql(case["sql"]) == case["expected"]
