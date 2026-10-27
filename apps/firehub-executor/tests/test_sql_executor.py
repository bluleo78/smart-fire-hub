from unittest.mock import MagicMock, patch

import pytest

from app.services.query_executor import detect_statement_type
from app.config import Settings
from app.services import sql_executor
from app.schemas.responses import SqlExecuteResponse


def execute_sql(*args, **kwargs):
    """서비스 함수는 기본 제한을 갖지 않는다(기본값은 Settings 한 곳) — 라우터처럼 설정 기본값을 넘긴다."""
    kwargs.setdefault(
        "default_timeout_seconds", Settings.model_fields["sql_statement_timeout_seconds"].default
    )
    kwargs.setdefault(
        "pre_statement_timeout_seconds",
        Settings.model_fields["sql_pre_statement_timeout_seconds"].default,
    )
    return sql_executor.execute_sql(*args, **kwargs)


LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))"


def make_conn(cursor_mock):
    conn = MagicMock()
    conn.cursor.return_value.__enter__ = MagicMock(return_value=cursor_mock)
    conn.cursor.return_value.__exit__ = MagicMock(return_value=False)
    return conn


def test_select_returns_rows_and_columns():
    cursor = MagicMock()
    cursor.description = [("id",), ("name",)]
    cursor.fetchall.return_value = [(1, "Alice"), (2, "Bob")]
    conn = make_conn(cursor)

    result = execute_sql("SELECT id, name FROM users", conn)

    assert result.success is True
    assert result.columns == ["id", "name"]
    assert result.rows == [{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]
    assert result.row_count == 2
    conn.commit.assert_not_called()


def test_dml_commits_and_returns_rowcount():
    cursor = MagicMock()
    cursor.rowcount = 3
    conn = make_conn(cursor)

    result = execute_sql("UPDATE users SET active = true WHERE id > 0", conn)

    assert result.success is True
    assert result.row_count == 3
    assert result.rows is None
    conn.commit.assert_called_once()


def test_blocked_keyword_returns_error():
    conn = MagicMock()

    result = execute_sql("DROP TABLE users", conn)

    assert result.success is False
    assert result.error is not None
    assert "DROP" in result.error
    conn.cursor.assert_not_called()


def test_db_error_rollbacks():
    cursor = MagicMock()
    cursor.execute.side_effect = Exception("DB connection lost")
    conn = make_conn(cursor)

    result = execute_sql("SELECT * FROM users", conn)

    assert result.success is False
    assert "DB connection lost" in result.error
    conn.rollback.assert_called_once()


def test_pre_statements_run_before_query_in_same_transaction():
    # pre-statement(출력 비우기)와 본 쿼리가 같은 트랜잭션에서 순서대로 실행되고,
    # 성공 시 한 번에 커밋되는지 확인한다.
    cursor = MagicMock()
    cursor.rowcount = 1
    conn = make_conn(cursor)

    result = execute_sql(
        'INSERT INTO "data"."out" ("a") SELECT 1',
        conn,
        pre_statements=['DELETE FROM "data"."out"'],
    )

    assert result.success
    # 출력 테이블 직렬화 잠금(#731)이 DELETE **앞에, 별도 문장으로** 실행돼야 한다 — 같은 문장이거나
    # DELETE 뒤라면 겹친 실행의 미커밋 행을 지우지 못해 행이 중복된다. 키는 스키마까지 포함한다.
    # 선행 문장 직전마다 남은 예산으로 SET LOCAL 이 먼저 나가므로(WD-262) 잠금 문장은 인덱스 1 이다.
    assert cursor.execute.call_args_list[1].args == (LOCK_SQL, ('"data"."out"',))
    executed = [c.args[0] for c in cursor.execute.call_args_list]
    assert executed[0].startswith("SET LOCAL statement_timeout = ")
    assert executed[2].startswith("SET LOCAL statement_timeout = ")  # 비우기 직전에 남은 예산으로 다시 건다
    assert [executed[1]] + executed[3:] == [
        LOCK_SQL,
        'DELETE FROM "data"."out"',
        "SET LOCAL statement_timeout = 50000",
        'INSERT INTO "data"."out" ("a") SELECT 1',
    ]
    conn.commit.assert_called_once()


def test_output_lock_key_is_scoped_to_tenant_schema():
    # advisory 키 공간은 DB 전체가 공유한다 — 같은 테이블명이라도 테넌트 스키마가 다르면 키가 달라야
    # 서로 무관한 테넌트의 실행이 직렬화되지 않는다.
    cursor = MagicMock()
    cursor.rowcount = 1
    conn = make_conn(cursor)

    execute_sql(
        'INSERT INTO "data_t7"."out" ("a") SELECT 1',
        conn,
        pre_statements=['DELETE FROM "data_t7"."out"'],
    )

    lock_calls = [c for c in cursor.execute.call_args_list if c.args[0] == LOCK_SQL]
    assert [c.args[1] for c in lock_calls] == [('"data_t7"."out"',)]


def test_no_lock_without_pre_statements():
    # 선행 문장이 없는 실행(APPEND·MERGE)은 잠금을 잡지 않는다. 사용자가 직접 쓴 DML 도 REPLACE 면
    # API 가 출력 비우기 선행 문장을 함께 보내므로(#735) 위 선행 문장 테스트와 같은 경로를 탄다.
    cursor = MagicMock()
    cursor.rowcount = 1
    conn = make_conn(cursor)

    execute_sql('INSERT INTO "data"."out" ("a") SELECT 1', conn)

    assert [c.args[0] for c in cursor.execute.call_args_list] == [
        "SET LOCAL statement_timeout = 50000",
        'INSERT INTO "data"."out" ("a") SELECT 1'
    ]


def test_user_dml_update_with_pre_statement_locks_clears_then_commits_once():
    # #735 — 사용자가 직접 쓴 비SELECT DML(여기서는 UPDATE)도 REPLACE 면 비우기 선행 문장과 함께 온다.
    # 잠금 → 비우기 → 사용자 DML 이 한 트랜잭션에서 순서대로 돌고 한 번만 커밋돼야, 겹친 실행이
    # 서로의 비우기와 적재 사이에 끼어들지 못한다. 결과 행을 읽으려 하지 않는다(SELECT 로 오판 금지).
    cursor = MagicMock()
    cursor.rowcount = 3
    conn = make_conn(cursor)

    result = execute_sql(
        'UPDATE "data"."out" SET "a" = 1',
        conn,
        pre_statements=['DELETE FROM "data"."out"'],
    )

    assert result.success
    assert result.row_count == 3
    executed = [c.args[0] for c in cursor.execute.call_args_list]
    # 잠금 → 비우기 → 본문 순서(각 선행 문장 앞의 SET LOCAL 은 걸러 순서만 본다).
    assert [e for e in executed if not e.startswith("SET LOCAL")] == [
        LOCK_SQL,
        'DELETE FROM "data"."out"',
        'UPDATE "data"."out" SET "a" = 1',
    ]
    assert executed[-2] == "SET LOCAL statement_timeout = 50000"
    cursor.fetchall.assert_not_called()
    conn.commit.assert_called_once()


def test_query_failure_rolls_back_pre_statements():
    # 본 쿼리가 실패하면 이미 실행한 pre-statement 도 같은 트랜잭션이므로 롤백돼야 한다 —
    # 그렇지 않으면 DELETE 만 반영되고 INSERT 는 실패해 출력 테이블이 비게 된다(원자성 붕괴).
    cursor = MagicMock()
    # 실행 순서: SET LOCAL → 직렬화 잠금 → SET LOCAL → DELETE → SET LOCAL → 본 쿼리(실패)
    cursor.execute.side_effect = [None, None, None, None, None, Exception("boom")]
    conn = make_conn(cursor)

    result = execute_sql(
        'INSERT INTO "data"."out" ("a") SELECT 1',
        conn,
        pre_statements=['DELETE FROM "data"."out"'],
    )

    assert not result.success
    conn.rollback.assert_called_once()
    conn.commit.assert_not_called()


def test_pre_statements_with_with_clause_query_still_commits():
    # 본 쿼리가 WITH/SELECT 로 시작해도(예: WITH 를 쓴 MERGE 형태) pre_statements 가 있으면
    # 무조건 쓰기 경로로 취급해 commit 해야 한다. SELECT 로 오판하면 DELETE 로 비운 내용이
    # 커밋되지 않아 사라진다.
    cursor = MagicMock()
    cursor.rowcount = 1
    conn = make_conn(cursor)

    result = execute_sql(
        'WITH src AS (SELECT 1 AS a) INSERT INTO "data"."out" ("a") SELECT a FROM src',
        conn,
        pre_statements=['DELETE FROM "data"."out"'],
    )

    assert result.success
    assert result.rows is None
    conn.commit.assert_called_once()


def test_disallowed_pre_statement_is_rejected_before_execution():
    # 허용되지 않은 pre-statement(조건부 DELETE)는 커서를 열기도 전에 거부돼야 한다.
    cursor = MagicMock()
    conn = make_conn(cursor)

    result = execute_sql(
        "SELECT 1", conn, pre_statements=['DELETE FROM "data"."out" WHERE 1=1']
    )

    assert not result.success
    cursor.execute.assert_not_called()


def test_empty_select_returns_empty_rows():
    cursor = MagicMock()
    cursor.description = [("id",), ("name",)]
    cursor.fetchall.return_value = []
    conn = make_conn(cursor)

    result = execute_sql("SELECT id, name FROM users WHERE id = -1", conn)

    assert result.success is True
    assert result.rows == []
    assert result.row_count == 0


@pytest.mark.parametrize(
    "query, description",
    [
        # WD-93 — 선행 문장 없는(APPEND·출력 없음) WITH DML 도 쓰기다. 예전에는 첫 단어 WITH 로 읽기로 보고 fetchall 을 불러
        # "no results to fetch" 로 실패했다(실제 PostgreSQL 에서 확인).
        ('WITH x AS (SELECT 1 AS v) DELETE FROM "data"."t" WHERE id IN (SELECT v FROM x)', None),
        # RETURNING 이 있어도 쓰기다. 예전에는 행만 읽고 커밋하지 않아 풀 반납 시 롤백됐다(성공으로 보고된 무동작). 쓰기 경로는
        # 결과 행을 읽지 않는다 — 러너는 실행 로그만 쓰고, 큰 결과를 응답에 실으면 커밋 뒤에 API 쪽에서 실패할 수 있다.
        ('WITH x AS (SELECT 1 AS v) DELETE FROM "data"."t" WHERE id IN (SELECT v FROM x) RETURNING id', [("id",)]),
        # 회귀 가드 — WITH 없는 DML + RETURNING.
        ('UPDATE "data"."t" SET a = 1 RETURNING id', [("id",)]),
        # 앞 주석 속 SELECT 단어에 속지 않는다.
        ('-- SELECT 처럼 보이는 주석\nWITH x AS (SELECT 1 AS v) DELETE FROM "data"."t" WHERE id IN (SELECT v FROM x)', None),
    ],
)
def test_write_without_pre_statements_commits_without_fetching(query, description):
    cursor = MagicMock()
    cursor.rowcount = 2
    cursor.description = description
    conn = make_conn(cursor)

    result = execute_sql(query, conn)

    assert result.success
    assert result.rows is None
    assert result.row_count == 2
    cursor.fetchall.assert_not_called()
    conn.commit.assert_called_once()


def test_leading_comment_select_without_pre_statements_is_read():
    # 앞 주석은 어휘 인지로 걷은 뒤 분류한다 — 주석 붙은 SELECT 도 읽기 경로(행 반환, 커밋 없음)다.
    cursor = MagicMock()
    cursor.description = [("a",)]
    cursor.fetchall.return_value = [(1,)]
    conn = make_conn(cursor)

    result = execute_sql("-- it's\n/* note */ SELECT 1 AS a", conn)

    assert result.success
    assert result.rows == [{"a": 1}]
    conn.commit.assert_not_called()


@pytest.mark.parametrize(
    "sql, expected",
    [
        ("SELECT 1", "SELECT"),
        ("-- a\n/* b */ select 1", "SELECT"),
        ("'--x'", "UNKNOWN"),
        ("/* 닫히지 않음 SELECT 1", "UNKNOWN"),
        ("-- 주석만", "UNKNOWN"),
        ("WITH x AS (SELECT 1) UPDATE t SET a = 1; -- 끝 주석", "UPDATE"),
        ("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d", "DELETE"),
    ],
)
def test_detect_statement_type_skips_comments_lexically(sql, expected):
    assert detect_statement_type(sql) == expected


class _FakeDiag:
    """psycopg2 ``Diagnostics`` 흉내 — 실제 오류 객체와 같은 속성 이름만 둔다."""

    def __init__(self, primary, detail=None, hint=None, column=None):
        self.message_primary = primary
        self.message_detail = detail
        self.message_hint = hint
        self.column_name = column


class _FakePgError(Exception):
    """psycopg2 ``Error`` 흉내 — ``pgcode``(SQLState)·``diag`` 와, 위치 표시 줄에 내부 SQL 이 실린 ``str()``."""

    def __init__(self, text, pgcode, diag):
        super().__init__(text)
        self.pgcode = pgcode
        self.diag = diag


def test_pg_error_returns_structured_sqlstate_and_fields():
    # WD-111: API 가 SQLState 로 오류를 분류하고 원문을 주 메시지·DETAIL 만으로 만들 수 있게 구조화 필드를 함께 보낸다.
    cursor = MagicMock()
    cursor.execute.side_effect = _FakePgError(
        'duplicate key value violates unique constraint "ux_out_pk"\n'
        "DETAIL:  Key (k)=(1) already exists.\n",
        "23505",
        _FakeDiag('duplicate key value violates unique constraint "ux_out_pk"', "Key (k)=(1) already exists."),
    )
    conn = make_conn(cursor)

    result = execute_sql('INSERT INTO "data"."out" ("k") SELECT 1', conn)

    assert result.success is False
    assert result.pg_error is not None
    assert result.pg_error.sql_state == "23505"
    assert result.pg_error.message == 'duplicate key value violates unique constraint "ux_out_pk"'
    assert result.pg_error.detail == "Key (k)=(1) already exists."
    assert result.pg_error.hint is None
    # 기존 error 문자열은 그대로 유지한다(구버전 API 호환).
    assert "duplicate key" in result.error
    dumped = result.model_dump()
    assert dumped["pg_error"]["sql_state"] == "23505"


def test_non_pg_error_has_no_structured_fields():
    # PG 오류가 아닌 실패(연결 끊김·목 예외)는 SQLState 가 없으므로 pg_error 를 비운다 — API 는 error 문자열만 쓴다.
    cursor = MagicMock()
    cursor.execute.side_effect = Exception("DB connection lost")
    conn = make_conn(cursor)

    result = execute_sql("SELECT 1", conn)

    assert result.success is False
    assert result.pg_error is None


def test_validation_error_has_no_structured_fields():
    result = execute_sql("DROP TABLE users", MagicMock())

    assert result.success is False
    assert result.pg_error is None


def test_timeout_is_set_after_pre_statements_just_before_body():
    # WD-236: 요청별 시간 제한(timeout_seconds)은 본문 직전에만 건다 — 선행 문장은 사용자 제한이 아니라 선행 예산(WD-262)을 받는다.
    cursor = MagicMock()
    cursor.rowcount = 1
    conn = make_conn(cursor)

    result = execute_sql(
        'INSERT INTO "data"."out" ("a") SELECT 1',
        conn,
        pre_statements=['DELETE FROM "data"."out"'],
        timeout_seconds=30,
    )

    assert result.success
    executed = [c.args[0] for c in cursor.execute.call_args_list]
    # 선행 문장(잠금·비우기) 각각 직전에 예산 SET LOCAL, 본문 직전에는 요청값 30초로 다시 건다.
    assert [e for e in executed if not e.startswith("SET LOCAL")] == [
        LOCK_SQL,
        'DELETE FROM "data"."out"',
        'INSERT INTO "data"."out" ("a") SELECT 1',
    ]
    assert executed[-2:] == [
        "SET LOCAL statement_timeout = 30000",
        'INSERT INTO "data"."out" ("a") SELECT 1',
    ]
    pre_limits = [_ms(e) for e in executed[:-2] if e.startswith("SET LOCAL")]
    assert pre_limits and all(ms != 30000 for ms in pre_limits)


def test_timeout_without_pre_statements_precedes_body():
    # 선행 문장이 없으면 기존과 같이 SET LOCAL -> 본문 순서다.
    cursor = MagicMock()
    cursor.description = [("a",)]
    cursor.fetchall.return_value = [(1,)]
    conn = make_conn(cursor)

    result = execute_sql("SELECT 1 AS a", conn, timeout_seconds=5)

    assert result.success
    executed = [c.args[0] for c in cursor.execute.call_args_list]
    assert executed == ["SET LOCAL statement_timeout = 5000", "SELECT 1 AS a"]


# ---------------------------------------------------------------------------
# WD-262: 선행 문장 시간 제한 — db-executor SqlRunner 와 같은 의미(전체 합계 예산, 초과 문구 동일)
# ---------------------------------------------------------------------------

PRE_CAP = Settings.model_fields["sql_pre_statement_timeout_seconds"].default
TWO_PRE = ['DELETE FROM "data"."out"', 'DELETE FROM "data"."out2"']


def _ms(set_local_sql: str) -> int:
    """``SET LOCAL statement_timeout = N`` 문장에서 N(밀리초)을 꺼낸다."""
    return int(set_local_sql.rsplit("=", 1)[1])


class _FakeClock:
    """``time.monotonic`` 대역 — 호출할 때마다 ``step`` 초씩 흐른다(첫 호출은 0)."""

    def __init__(self, step: float):
        self.step = step
        self.now = -step

    def __call__(self) -> float:
        self.now += self.step
        return self.now


class _Cancel57014(Exception):
    """문장 제한 취소 대역 — SQLState 57014 만 있으면 된다(이 태스크의 판정은 SQLState 뿐이다)."""

    pgcode = "57014"


def test_pre_statement_cap_default_is_1800():
    assert PRE_CAP == 1800


def test_pre_statements_get_budget_before_each():
    # 선행 문장 2개 — 각 선행 문장 직전에 SET LOCAL 이 실행되고, 값은 남은 예산이라 뒤로 갈수록 작아진다.
    # 본문 직전에는 본문 제한(기본 50초)으로 다시 건다.
    cursor = MagicMock()
    cursor.rowcount = 1
    conn = make_conn(cursor)

    with patch("app.services.sql_executor.time.monotonic", _FakeClock(step=10)):
        result = execute_sql('INSERT INTO "data"."out" ("a") SELECT 1', conn, pre_statements=TWO_PRE)

    assert result.success
    executed = [c.args[0] for c in cursor.execute.call_args_list]
    # 각 선행 문장은 [SET LOCAL, 잠금, SET LOCAL, DELETE] 로 나가고, 마지막에 [SET LOCAL(본문), 본문].
    assert [e.startswith("SET LOCAL") for e in executed] == [
        True, False, True, False, True, False, True, False, True, False,
    ]
    pre_limits = [_ms(e) for e in executed[:-2] if e.startswith("SET LOCAL")]
    assert len(pre_limits) == 4
    assert pre_limits[0] <= PRE_CAP * 1000
    # 시계가 앞으로만 가므로 남은 예산은 줄기만 한다(두 번째 선행 문장의 값 < 첫 번째).
    assert pre_limits == sorted(pre_limits, reverse=True) and pre_limits[2] < pre_limits[0]
    assert executed[-2] == "SET LOCAL statement_timeout = 50000"
    conn.commit.assert_called_once()


def test_pre_statement_timeout_returns_korean_message():
    # 선행 문장에서 57014(제한 도달) → 응답 error 가 db-executor 와 같은 문구, 본문은 실행되지 않고 롤백한다.
    cursor = MagicMock()
    # 첫 SET LOCAL, 잠금, 두 번째 SET LOCAL 은 통과하고 비우기 DELETE 가 57014.
    cursor.execute.side_effect = [None, None, None, _Cancel57014("canceling statement due to statement timeout")]
    conn = make_conn(cursor)

    result = execute_sql('INSERT INTO "data"."out" ("a") SELECT 1', conn, pre_statements=TWO_PRE)

    assert result.success is False
    assert result.error == "기존 출력 정리(선행 문장)가 1800초 안에 끝나지 않아 취소했습니다."
    assert result.db_error.message == result.error
    assert result.db_error.sql_state == "57014"
    executed = [c.args[0] for c in cursor.execute.call_args_list]
    assert 'INSERT INTO "data"."out" ("a") SELECT 1' not in executed
    conn.rollback.assert_called_once()
    conn.commit.assert_not_called()


def test_pre_statement_message_uses_configured_cap():
    cursor = MagicMock()
    cursor.execute.side_effect = [None, _Cancel57014("x")]
    conn = make_conn(cursor)

    result = execute_sql(
        'INSERT INTO "data"."out" ("a") SELECT 1', conn, pre_statements=TWO_PRE,
        pre_statement_timeout_seconds=600,
    )

    assert result.error == "기존 출력 정리(선행 문장)가 600초 안에 끝나지 않아 취소했습니다."


def test_pre_statement_budget_exhausted_before_next():
    # 남은 예산이 0 이하이면 다음 선행 문장을 보내지 않고(0 은 PG 에서 '제한 없음' 이라 SET LOCAL 도 걸 수 없다) 같은 문구로 실패한다.
    cursor = MagicMock()
    conn = make_conn(cursor)

    # 시계: 시작 0 → 첫 arm 1000초(남은 800) → 두 번째 arm 에서 이미 2000초(예산 소진).
    ticks = iter([0.0, 1000.0, 2000.0, 2000.0, 2000.0])
    with patch("app.services.sql_executor.time.monotonic", lambda: next(ticks)):
        result = execute_sql(
            'INSERT INTO "data"."out" ("a") SELECT 1', conn,
            pre_statements=TWO_PRE, pre_statement_timeout_seconds=1800,
        )

    assert result.success is False
    assert result.error == "기존 출력 정리(선행 문장)가 1800초 안에 끝나지 않아 취소했습니다."
    executed = [c.args[0] for c in cursor.execute.call_args_list]
    # 첫 SET LOCAL 과 잠금만 나가고, 그 뒤 문장(두 번째 SET LOCAL·DELETE·본문)은 보내지 않는다.
    assert executed == [f"SET LOCAL statement_timeout = {800 * 1000}", LOCK_SQL]
    conn.rollback.assert_called_once()
    conn.commit.assert_not_called()


def test_body_timeout_is_not_reported_as_pre_statement_timeout():
    # 본문에서 난 57014 는 기존 본문 문구(WD-259)를 유지한다 — 선행 문구는 선행 문장 단계에서만.
    cursor = MagicMock()
    cursor.execute.side_effect = [
        None, None, None, None, None, _Cancel57014("canceling statement due to statement timeout"),
    ]
    conn = make_conn(cursor)

    result = execute_sql(
        'INSERT INTO "data"."out" ("a") SELECT 1', conn, pre_statements=['DELETE FROM "data"."out"']
    )

    assert result.error == "실행 시간이 50초를 넘어 취소했습니다."


def test_set_local_statement_timeout_accepts_fractional_seconds_and_never_zero():
    from app.services.db_utils import set_local_statement_timeout

    cur = MagicMock()
    set_local_statement_timeout(cur, 12.3)
    set_local_statement_timeout(cur, 50)
    set_local_statement_timeout(cur, 0.0004)  # 0ms 는 PG 에서 '제한 없음' 이라 최소 1ms 로 올린다
    assert [c.args[0] for c in cur.execute.call_args_list] == [
        "SET LOCAL statement_timeout = 12300",
        "SET LOCAL statement_timeout = 50000",
        "SET LOCAL statement_timeout = 1",
    ]


def test_pre_statement_timeout_message_text_matches_db_executor():
    from app.services.db_utils import pre_statement_timeout_message

    assert (
        pre_statement_timeout_message(1800)
        == "기존 출력 정리(선행 문장)가 1800초 안에 끝나지 않아 취소했습니다."
    )
