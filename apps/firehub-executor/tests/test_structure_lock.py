"""구조 잠금(WD-670) — 실행기가 데이터 저장소에 직접 쓰는 트랜잭션의 첫 문장이 구조 공유 잠금이고, 키 검증·잠금 뒤 확인이 api 와 같은 규칙인지.

수정 전에는 sql_executor 가 구조 잠금을 몰라 첫 문장이 출력 잠금(#731)·선행 문장이었고, 구조 변경이 실행 도중 커밋되면 원문 오류로 실패했다.
"""
from unittest.mock import MagicMock

import pytest

from app.config import Settings
from app.services import api_call_executor, load_executor, sql_executor
from app.services.structure_lock import (
    STRUCTURE_COLUMNS_SQL,
    STRUCTURE_SHARED_LOCK_SQL,
    StructureChangedError,
    StructureLockError,
    acquire_structure_locks,
    validate_key,
)

KEY = 'ds-structure:data_t7."out_tbl"'
OTHER = 'ds-structure:data_t7."a_tbl"'
PRE = 'DELETE FROM "data_t7"."out_tbl"'


def _execute_sql(*args, **kwargs):
    kwargs.setdefault("default_timeout_seconds", Settings.model_fields["sql_statement_timeout_seconds"].default)
    kwargs.setdefault(
        "pre_statement_timeout_seconds", Settings.model_fields["sql_pre_statement_timeout_seconds"].default
    )
    return sql_executor.execute_sql(*args, **kwargs)


def _conn(cursor):
    conn = MagicMock()
    conn.cursor.return_value.__enter__ = MagicMock(return_value=cursor)
    conn.cursor.return_value.__exit__ = MagicMock(return_value=False)
    return conn


def _cursor(columns_by_table):
    """구조 확인 문장에는 표의 컬럼 행을, 그 밖의 문장에는 빈 결과를 돌려주는 커서."""
    cursor = MagicMock()
    cursor.rowcount = 1
    state = {"last": None}

    def execute(sql, params=None):
        state["last"] = (sql, params)

    def fetchall():
        sql, params = state["last"]
        if sql == STRUCTURE_COLUMNS_SQL:
            return columns_by_table.get(params[0], [])
        return []

    cursor.execute.side_effect = execute
    cursor.fetchall.side_effect = fetchall
    return cursor


OUT_COLUMNS = [("id", False), ("code", False), ("name", False), ("created_at", False)]


# ── 키 검증 ──────────────────────────────────────────────────────────────


def test_validate_key_accepts_own_tenant_schema():
    assert validate_key(KEY, 7) == 'data_t7."out_tbl"'
    assert validate_key('ds-structure:data."t1"', 1) == 'data."t1"'


@pytest.mark.parametrize(
    "key",
    [
        'ds-structure:data_t8."out_tbl"',  # 남의 테넌트 스키마
        'data_t7."out_tbl"',  # 접두사 없음(#731 출력 잠금 키 꼴)
        'ds-structure:data_t7."Out"',  # 앱 이름 규칙 밖
        'ds-structure:data_t7."x"; SELECT 1',  # 꼬리 문장
        'ds-structure:data_t7."x"\n',  # 끝 개행
        "",
        None,
        123,
    ],
)
def test_validate_key_rejects_bad_keys(key):
    with pytest.raises(StructureLockError):
        validate_key(key, 7)


def test_bad_key_is_rejected_before_touching_connection():
    conn = MagicMock()
    result = _execute_sql(
        "INSERT INTO t SELECT 1", conn, [], tenant_id=7,
        structure_locks=[{"key": 'ds-structure:data_t8."out_tbl"', "columns": None}],
    )
    assert result.success is False
    conn.cursor.assert_not_called()


# ── /execute/sql: 첫 문장 ────────────────────────────────────────────────


def test_shared_structure_lock_is_the_first_statement_before_output_lock():
    cursor = _cursor({'data_t7."out_tbl"': OUT_COLUMNS, 'data_t7."a_tbl"': [("id", False)]})
    conn = _conn(cursor)

    result = _execute_sql(
        'INSERT INTO data_t7."out_tbl" ("code", "name") SELECT 1, 2', conn, [PRE], tenant_id=7,
        structure_locks=[{"key": KEY, "columns": ["code", "name"]}, {"key": OTHER, "columns": None}],
    )

    assert result.success is True, result.error
    sqls = [c.args[0] for c in cursor.execute.call_args_list]
    # 키 순서(a_tbl → out_tbl)로 공유 잠금 둘이 맨 앞이다 — 그다음 확인, 그다음에야 출력 잠금·비우기·본문.
    assert sqls[0] == STRUCTURE_SHARED_LOCK_SQL and cursor.execute.call_args_list[0].args[1] == (OTHER,)
    assert sqls[1] == STRUCTURE_SHARED_LOCK_SQL and cursor.execute.call_args_list[1].args[1] == (KEY,)
    assert sqls[2] == STRUCTURE_COLUMNS_SQL and sqls[3] == STRUCTURE_COLUMNS_SQL
    lock_index = next(i for i, s in enumerate(sqls) if s == sql_executor.OUTPUT_LOCK_SQL)
    assert lock_index > 3
    conn.commit.assert_called_once()


def test_without_structure_locks_statements_are_unchanged():
    cursor = _cursor({})
    conn = _conn(cursor)
    _execute_sql('INSERT INTO t SELECT 1', conn, [PRE])
    sqls = [c.args[0] for c in cursor.execute.call_args_list]
    assert STRUCTURE_SHARED_LOCK_SQL not in sqls


def test_dropped_field_while_waiting_fails_cleanly_and_rolls_back():
    # 계획 컬럼 name 이 물리 테이블에 없다(잠금을 기다리는 동안 필드 삭제가 커밋됨).
    cursor = _cursor({'data_t7."out_tbl"': [("id", False), ("code", False)]})
    conn = _conn(cursor)

    result = _execute_sql(
        "INSERT INTO t SELECT 1", conn, [PRE], tenant_id=7,
        structure_locks=[{"key": KEY, "columns": ["code", "name"]}],
    )

    assert result.success is False
    assert "구조가 바뀌었습니다" in result.error and "name" in result.error
    assert result.pg_error is None
    sqls = [c.args[0] for c in cursor.execute.call_args_list]
    assert sql_executor.OUTPUT_LOCK_SQL not in sqls and PRE not in sqls
    conn.rollback.assert_called_once()
    conn.commit.assert_not_called()


def test_new_not_null_field_or_dropped_table_fails_cleanly():
    cursor = _cursor({'data_t7."out_tbl"': OUT_COLUMNS + [("must", True)]})
    result = _execute_sql(
        "INSERT INTO t SELECT 1", _conn(cursor), [], tenant_id=7,
        structure_locks=[{"key": KEY, "columns": ["code", "name"]}],
    )
    assert result.success is False and "구조가 바뀌었습니다" in result.error

    cursor = _cursor({})  # 테이블 없음 — to_regclass NULL → 행 없음
    result = _execute_sql(
        "UPDATE t SET a = 1", _conn(cursor), [], tenant_id=7,
        structure_locks=[{"key": KEY, "columns": None}],
    )
    assert result.success is False and "구조가 바뀌었습니다" in result.error


# ── 적재 경로 ────────────────────────────────────────────────────────────


def test_acquire_takes_shared_lock_first_and_checks_known_columns():
    cursor = _cursor({'data_t7."out_tbl"': OUT_COLUMNS})
    acquire_structure_locks(cursor, 7, [{"key": KEY, "columns": ["code", "name"]}])
    assert cursor.execute.call_args_list[0].args == (STRUCTURE_SHARED_LOCK_SQL, (KEY,))

    cursor = _cursor({'data_t7."out_tbl"': [("id", False), ("code", False)]})
    with pytest.raises(StructureChangedError):
        acquire_structure_locks(cursor, 7, [{"key": KEY, "columns": ["code", "name"]}])


def test_load_takes_structure_lock_before_statement_timeout(monkeypatch):
    # INSERT 조립·페이지 실행은 실제 psycopg2 연결이 필요해 대신한다 — 여기서 보는 것은 문장 순서뿐이다.
    monkeypatch.setattr(load_executor, "build_insert_query", lambda cur, table, cols: "INSERT")
    monkeypatch.setattr(load_executor, "execute_insert_page", lambda cur, q, cols, rows, size: cur.execute(q))
    cursor = _cursor({'data_t7."out_tbl"': OUT_COLUMNS})
    conn = _conn(cursor)
    result = load_executor.execute_load(
        "out_tbl", [{"code": "a", "name": "A"}], None, conn,
        default_timeout_seconds=30, tenant_id=7,
        structure_locks=[{"key": KEY, "columns": ["code", "name"]}],
    )
    assert result.success is True, result.error
    assert cursor.execute.call_args_list[0].args == (STRUCTURE_SHARED_LOCK_SQL, (KEY,))


def test_load_rejects_bad_key_without_touching_connection():
    conn = MagicMock()
    result = load_executor.execute_load(
        "out_tbl", [{"code": "a"}], None, conn, default_timeout_seconds=30, tenant_id=7,
        structure_locks=[{"key": 'ds-structure:data_t9."out_tbl"', "columns": None}],
    )
    assert result.success is False
    conn.cursor.assert_not_called()


def test_api_call_pg_takes_structure_lock_before_fetching(monkeypatch):
    from app.schemas.requests import ApiCallExecuteRequest

    order = []
    cursor = _cursor({'data_t7."out_tbl"': [("id", False), ("code", False)]})
    original_execute = cursor.execute.side_effect

    def execute(sql, params=None):
        order.append(sql)
        original_execute(sql, params)

    cursor.execute.side_effect = execute

    # 인증 설정(WD-657)이 네 번째 인자로 넘어온다 — 이 테스트는 쓰지 않는다.
    def fake_fetch(request, write, start, auth=None):
        order.append("FETCH")
        return 0, 1, []

    monkeypatch.setattr(api_call_executor, "_fetch_and_write_pages", fake_fetch)
    request = ApiCallExecuteRequest(
        tenantId=7, url="https://example.com", data_path="$", field_mappings=[],
        output_table="out_tbl", load_strategy="APPEND",
        structure_locks=[{"key": KEY, "columns": ["code"]}],
    )
    result = api_call_executor.execute_api_call(request, _conn(cursor))

    assert result.success is True, result.error
    assert order[0] == STRUCTURE_SHARED_LOCK_SQL and order.index("FETCH") > 0

    # 수집 전에 구조가 바뀌었으면(필드 code 삭제) 아무것도 받지 않고 깔끔하게 실패한다.
    order.clear()
    cursor2 = _cursor({'data_t7."out_tbl"': [("id", False)]})
    result = api_call_executor.execute_api_call(request, _conn(cursor2))
    assert result.success is False and "code" in result.error
    assert "FETCH" not in order
