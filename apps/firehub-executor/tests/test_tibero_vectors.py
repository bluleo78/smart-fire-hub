"""api 와 공유하는 Tibero 벡터 픽스처로 executor 파생 규약을 고정한다(이 Task 는 ownerUsername 만)."""
import json
from pathlib import Path

import pytest

from app.tenant import resolve_tibero_owner

_FIXTURE = Path(__file__).resolve().parents[2] / "firehub-api/src/test/resources/fixtures/tibero-executor-vectors.json"
_CASES = json.loads(_FIXTURE.read_text(encoding="utf-8"))["ownerUsername"]


@pytest.mark.parametrize("case", _CASES)
def test_owner_username_matches_fixture(case):
    assert resolve_tibero_owner(case["tenantId"]) == case["out"]


# --- WD-75 Task 3: 세션 초기화 SQL · 시퀀스 이름 벡터 ---
from app.db.backends.tibero_sql import SESSION_INIT_SQL, sequence_name  # noqa: E402

_ALL = json.loads(_FIXTURE.read_text(encoding="utf-8"))


def test_session_init_sql_matches_fixture():
    assert SESSION_INIT_SQL == _ALL["sessionInitSql"]


@pytest.mark.parametrize("case", _ALL["sequenceName"])
def test_sequence_name_matches_fixture(case):
    assert sequence_name(case["schema"], case["table"]) == case["out"]


# --- WD-77 Task 5: 결과 컬럼명 정규화 벡터(db-executor ValueConverter.columnName 과 같은 규칙) ---
# valueConversions 는 db-executor(JDBC) 전용 규칙이라 여기서는 검증하지 않는다(Python 은 NUMBER(1,0)→bool,
# DATE→date 로 PG 타입을 맞추는 다른 규약을 쓴다).
from app.db.backends.tibero import _result_column_name  # noqa: E402


@pytest.mark.parametrize("case", _ALL["columnNames"])
def test_result_column_name_matches_fixture(case):
    assert _result_column_name(case["in"]) == case["out"]
