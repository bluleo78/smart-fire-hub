from app.schemas.responses import SqlExecuteResponse
from app.validators.sql_validator import (
    SqlValidationError,
    output_lock_key,
    validate,
    validate_pre_statement,
)

# 출력 테이블 단위 직렬화 잠금 문장(#731) — 트랜잭션 범위 advisory 잠금. 키는 선행 문장에 적힌 한정 이름("<스키마>"."<테이블>").
OUTPUT_LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))"


def execute_sql(query: str, conn, pre_statements=()) -> SqlExecuteResponse:
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
        )

    stripped = query.strip().upper()
    # 선행 문장이 있으면 "출력 비우기 + 쓰기"인 쓰기 트랜잭션이다.
    # SELECT 로 오판해 커밋을 건너뛰면 DELETE 로 비운 내용이 롤백되지 않고 남을 수 있으므로
    # pre_statements 가 있을 때는 무조건 쓰기 경로(커밋)로 취급한다.
    is_select = not pre_statements and (stripped.startswith("SELECT") or stripped.startswith("WITH"))

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
        )
