from typing import Optional

from app.schemas.responses import PgErrorInfo, SqlExecuteResponse, db_error_of
from app.services.query_executor import detect_statement_type, sqlstate
from app.validators.sql_validator import (
    SqlValidationError,
    output_lock_key,
    validate,
    validate_pre_statement,
)

# 출력 테이블 단위 직렬화 잠금 문장(#731) — 트랜잭션 범위 advisory 잠금. 키는 선행 문장에 적힌 한정 이름("<스키마>"."<테이블>").
OUTPUT_LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))"


def execute_sql(
    query: str, conn, pre_statements=(), timeout_seconds: Optional[int] = None
) -> SqlExecuteResponse:
    # 본 쿼리와 선행 문장(pre-statement) 모두 실행 전에 검증한다.
    # pre-statement 는 화이트리스트 기반 validate_pre_statement 로 본 쿼리보다 엄격하게 검사한다.
    try:
        validate(query)
        for stmt in pre_statements:
            validate_pre_statement(stmt)
    except SqlValidationError as exc:
        return SqlExecuteResponse(
            success=False,
            rows=None,
            columns=None,
            row_count=0,
            execution_log="",
            error=str(exc),
            db_error=db_error_of(str(exc)),
        )

    # 선행 문장이 있으면 "출력 비우기 + 쓰기"인 쓰기 트랜잭션이다.
    # SELECT 로 오판해 커밋을 건너뛰면 DELETE 로 비운 내용이 롤백되지 않고 남을 수 있으므로
    # pre_statements 가 있을 때는 무조건 쓰기 경로(커밋)로 취급한다.
    # 문 종류는 애널리틱스와 같은 분류기(앞 주석은 어휘 인지로 걷음)로 정한다(WD-93) — 예전엔 WITH 로 시작하면 읽기로 봐서 CTE DML 이
    # 실패하거나(no results to fetch) 커밋 없이 끝났다. 분류기가 틀려도 커밋 쪽으로만 틀린다.
    is_select = not pre_statements and detect_statement_type(query) == "SELECT"

    try:
        with conn.cursor() as cursor:
            # 선행 문장(예: 출력 비우기 DELETE)과 본 쿼리를 같은 트랜잭션에서 실행한다.
            # 본 쿼리가 실패하면 아래 except 에서 rollback 하므로 선행 문장도 함께 취소된다 —
            # 이것이 "비우기와 쓰기를 원자적으로 만든다"는 이번 기능의 핵심이다.
            for stmt in pre_statements:
                # 출력 테이블 단위 직렬화(#731). 비우기(DELETE) **직전에, 별도 문장으로** 이 테이블의
                # advisory 잠금을 잡는다. 같은 출력에 "비우기 + 적재"를 하는 두 실행이 겹치면
                # READ COMMITTED 에서 뒤 실행의 DELETE 가 앞 실행의 미커밋 행을 보지 못해 두 실행의
                # INSERT 가 모두 남는다(모든 행 중복). 잠금은 커밋·롤백 때 자동으로 풀리므로 뒤 실행은
                # 앞 실행 커밋 뒤에 비우기를 시작한다 — 거부되지 않고 기다렸다가 실행된다.
                # DELETE 와 같은 문장에서 기다리면 안 된다: 스냅샷이 문장 시작 시점에 잡히기 때문에
                # 대기가 풀려도 옛 스냅샷으로 지운다.
                # 키·문장은 API 의 SqlScriptExecutor.OUTPUT_LOCK_SQL 과 같아야 한다.
                cursor.execute(OUTPUT_LOCK_SQL, (output_lock_key(stmt),))
                cursor.execute(stmt)
            if timeout_seconds is not None:
                # 요청별 시간 제한(WD-77, WD-236): 이 트랜잭션에만 걸리는 statement_timeout(밀리초). 선행 문장(잠금·비우기)은
                # 사용자 제한을 받지 않고 본문만 받는다 — db-executor 와 같은 의미다(스펙 §6.2). 그래서 선행 문장 루프 뒤,
                # 본문 직전에 건다. SET 은 값을 바인딩하지 못하므로 검증된 정수(요청 스키마가 1 이상 정수만 받는다)를 int() 로
                # 한 번 더 고정해 문장에 넣는다. LOCAL 이라 커밋·롤백 때 풀려 풀에 반납된 연결로 새지 않는다. 없으면 건너뛴다.
                cursor.execute(f"SET LOCAL statement_timeout = {int(timeout_seconds) * 1000}")
            cursor.execute(query)
            if is_select:
                columns = [desc[0] for desc in cursor.description] if cursor.description else []
                raw_rows = cursor.fetchall()
                rows = [dict(zip(columns, row)) for row in raw_rows]
                return SqlExecuteResponse(
                    success=True,
                    rows=rows,
                    columns=columns,
                    row_count=len(rows),
                    execution_log=f"{len(rows)} row(s) returned",
                    error=None,
                )
            else:
                conn.commit()
                row_count = cursor.rowcount if cursor.rowcount is not None else 0
                return SqlExecuteResponse(
                    success=True,
                    rows=None,
                    columns=None,
                    row_count=row_count,
                    execution_log=f"{row_count} row(s) affected",
                    error=None,
                )
    except Exception as exc:
        conn.rollback()
        return SqlExecuteResponse(
            success=False,
            rows=None,
            columns=None,
            row_count=0,
            execution_log="",
            error=str(exc),
            pg_error=pg_error_info(exc),
            db_error=db_error_of(str(exc), sqlstate(exc)),
        )


def pg_error_info(exc: BaseException) -> Optional[PgErrorInfo]:
    """psycopg2 오류에서 SQLState·주 메시지·DETAIL·HINT·컬럼을 꺼낸다(WD-111). PG 오류가 아니면 None.

    SQLState 판별은 애드혹 질의 실행기와 같은 ``query_executor.sqlstate`` 를 쓴다. 주 메시지 등은 ``diag`` 에서
    읽는다 — ``str(exc)`` 는 위치 표시 줄(내부 SQL)까지 담는다.
    psycopg2 를 직접 import 하지 않고 속성으로 판별해, 목(mock) 예외나 다른 드라이버 예외에서도 안전하게 None 이 된다.
    """
    sql_state = sqlstate(exc)
    if not sql_state:
        return None
    diag = getattr(exc, "diag", None)

    def field(name: str) -> Optional[str]:
        value = getattr(diag, name, None) if diag is not None else None
        return value if isinstance(value, str) and value else None

    return PgErrorInfo(
        sql_state=sql_state,
        message=field("message_primary"),
        detail=field("message_detail"),
        hint=field("message_hint"),
        column=field("column_name"),
    )
