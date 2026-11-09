"""데이터셋 테이블 구조 잠금(WD-670) — 실행기가 데이터 저장소에 직접 쓰는 트랜잭션의 첫 문장.

왜 필요한가: 파이프라인 SQL 스텝과 PG 적재(Python·API_CALL APPEND)는 api 의 플랫폼 데이터셋 행 잠금 없이 이 실행기의 테넌트 연결에서
바로 쓴다. 그래서 api 의 구조 변경(필드 추가·변경·삭제, truncate·replace, 데이터셋 삭제)이 쓰기 도중 커밋되면 원문 오류(「column does not
exist」)로 실패하거나 구조와 어긋난 행이 남았다. 이제 api 가 만든 키(``ds-structure:<스키마>."<테이블>"``)마다 공유 advisory 잠금을
트랜잭션 첫 문장으로 잡고(키 순서), 잡은 뒤 테이블 존재·계획 컬럼을 다시 확인한다. api 의 구조 변경은 같은 키를 배타로 잡으므로(최대 수 초 뒤
409) 이 쓰기가 커밋된 뒤에만 DDL 을 낸다. 키·문장·확인 규칙은 api ``DatasetStructureLock``·``SqlScriptExecutor`` 와 같다.

키는 api 가 보낸 문자열을 그대로 쓰되, 요청 테넌트의 스키마 형식(``ds-structure:<스키마>."[a-z0-9_]+"``)인지 먼저 검증한다 — 남의 테넌트
테이블 키나 임의 문자열을 받지 않는다(advisory 키 공간은 DB 전체가 공유한다).
"""
from __future__ import annotations

import re
from typing import Iterable, List, Optional, Sequence

from app.tenant import resolve_schema

# 공유 잠금 문장 — api DatasetStructureLock.SHARED_LOCK_SQL 과 같은 키 식(hashtextextended(키, 0)).
STRUCTURE_SHARED_LOCK_SQL = "SELECT pg_advisory_xact_lock_shared(hashtextextended(%s, 0))"

# 잠금 뒤 확인 문장 — api SqlScriptExecutor.STRUCTURE_COLUMNS_SQL 과 같다. 테이블이 없으면 행이 없다(to_regclass 가 NULL).
STRUCTURE_COLUMNS_SQL = (
    "SELECT a.attname::text, (a.attnotnull AND NOT a.atthasdef AND a.attidentity = '')"
    " FROM pg_attribute a WHERE a.attrelid = to_regclass(%s) AND a.attnum > 0"
    " AND NOT a.attisdropped"
)

KEY_PREFIX = "ds-structure:"

# api OutputStructureChangedException 과 같은 문구 — 사용자가 할 일(다시 실행)을 알린다.
STRUCTURE_CHANGED_MESSAGE = "실행 중 출력 데이터셋 구조가 바뀌었습니다. 다시 실행해 주세요"
BAD_KEY_MESSAGE = "허용되지 않은 구조 잠금 키입니다"

# 키 형식 — 스키마는 api DataSchema.qualify 처럼 인용 없이, 테이블은 큰따옴표 안의 앱 이름 규칙.
_KEY_PATTERN = re.compile(r'ds-structure:(?P<schema>data|data_t\d+)\."(?P<table>[a-z0-9_]+)"')


class StructureLockError(ValueError):
    """구조 잠금 키가 형식·테넌트에 맞지 않는다 — 아무것도 잠그거나 쓰지 않고 거부한다."""


class StructureChangedError(Exception):
    """잠금 뒤 확인에서 대상 테이블이 없거나 계획 컬럼이 바뀌었다 — 쓰기 전에 롤백한다."""

    def __init__(self, field: Optional[str] = None):
        if field:
            message = f"실행 중 출력 데이터셋 구조가 바뀌었습니다(필드 '{field}' 이(가) 없습니다). 다시 실행해 주세요"
        else:
            message = STRUCTURE_CHANGED_MESSAGE
        super().__init__(message)


def _field(lock, name: str):
    """요청 모델(StructureLock)과 dict 둘 다 받는다 — 서비스 함수를 직접 부르는 테스트·경로를 위해."""
    if isinstance(lock, dict):
        return lock.get(name)
    return getattr(lock, name, None)


def validate_key(key: object, tenant_id: int) -> str:
    """키가 요청 테넌트 스키마의 데이터셋 테이블 키인지 확인하고 한정 이름(``<스키마>."<테이블>"``)을 돌려준다."""
    if not isinstance(key, str):
        raise StructureLockError(BAD_KEY_MESSAGE)
    match = _KEY_PATTERN.fullmatch(key)
    if not match or match.group("schema") != resolve_schema(tenant_id):
        raise StructureLockError(f"{BAD_KEY_MESSAGE}: {key}")
    return key[len(KEY_PREFIX):]


def validate_locks(locks: Optional[Sequence], tenant_id: int) -> None:
    """연결을 쓰기 전에 모든 키를 검증한다 — 하나라도 틀리면 아무것도 하지 않고 거부한다."""
    for lock in locks or ():
        validate_key(_field(lock, "key"), tenant_id)
        columns = _field(lock, "columns")
        if columns is not None and not all(isinstance(c, str) for c in columns):
            raise StructureLockError(BAD_KEY_MESSAGE)


def acquire_structure_locks(cursor, tenant_id: int, locks: Optional[Sequence]) -> None:
    """트랜잭션의 첫 문장들로 공유 잠금을 키 순서로 잡고, 모두 잡은 뒤 대상마다 구조를 확인한다.

    확인 규칙(api StructureGuard 와 같다): 테이블이 없으면 바뀐 것. ``columns`` 가 있으면 그 컬럼이 모두 있어야 하고, 값이 꼭 필요한 물리
    컬럼(NOT NULL·기본값 없음·identity 아님)이 ``columns`` 밖에 있으면 안 된다(실행 중 추가된 NOT NULL 필드). ``columns`` 가 없으면 존재만 본다.

    :raises StructureLockError: 키가 형식·테넌트에 맞지 않을 때(잠그기 전)
    :raises StructureChangedError: 잠금 뒤 구조가 계획과 다를 때
    """
    if not locks:
        return
    validate_locks(locks, tenant_id)
    ordered = sorted(locks, key=lambda lock: _field(lock, "key"))
    for lock in ordered:
        cursor.execute(STRUCTURE_SHARED_LOCK_SQL, (_field(lock, "key"),))
    for lock in ordered:
        _verify(cursor, validate_key(_field(lock, "key"), tenant_id), _field(lock, "columns"))


def _verify(cursor, qualified: str, columns: Optional[Iterable[str]]) -> None:
    cursor.execute(STRUCTURE_COLUMNS_SQL, (qualified,))
    rows: List = cursor.fetchall()
    if not rows:
        raise StructureChangedError()
    if columns is None:
        return
    known = list(columns)
    physical = {row[0] for row in rows}
    for column in known:
        if column not in physical:
            raise StructureChangedError(column)
    known_set = set(known)
    for name, required in rows:
        if required and name not in known_set:
            raise StructureChangedError()
