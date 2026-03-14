from app.schemas.responses import SqlExecuteResponse
from app.validators.sql_validator import SqlValidationError, validate


def execute_sql(query: str, conn) -> SqlExecuteResponse:
    try:
        validate(query)
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
    is_select = stripped.startswith("SELECT") or stripped.startswith("WITH")

    try:
        with conn.cursor() as cursor:
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
