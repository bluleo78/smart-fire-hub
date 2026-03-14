from __future__ import annotations

import re
import time
from typing import Any, Dict, List, Tuple

from app.schemas.responses import QueryExecuteResponse


def _detect_geometry_columns(cursor, sql: str) -> List[Tuple[str, bool]]:
    """Detect column names and whether they are geometry via LIMIT 0 + pg_type lookup."""
    conn = cursor.connection
    meta_cursor = conn.cursor()
    meta_cursor.execute(f"SELECT * FROM ({sql}) _geom_detect LIMIT 0")

    meta_cursor2 = conn.cursor()
    meta_cursor2.execute(
        "SELECT oid FROM pg_type WHERE typname IN ('geometry', 'geography')"
    )
    geom_oids = {row[0] for row in meta_cursor2.fetchall()}

    columns: List[Tuple[str, bool]] = []
    for desc in meta_cursor.description or []:
        col_name = desc[0]
        type_oid = desc[1]  # type_code in psycopg2
        is_geom = type_oid in geom_oids
        columns.append((col_name, is_geom))

    meta_cursor.close()
    meta_cursor2.close()
    return columns


def _build_geojson_wrapped_sql(
    original_sql: str, column_metas: List[Tuple[str, bool]]
) -> str:
    """Build CTE that wraps geometry columns with public.ST_AsGeoJSON()."""
    select_parts = []
    for col_name, is_geom in column_metas:
        escaped = col_name.replace('"', '""')
        if is_geom:
            select_parts.append(
                f'public.ST_AsGeoJSON("{escaped}") AS "{escaped}"'
            )
        else:
            select_parts.append(f'"{escaped}"')
    return f"WITH _src AS ({original_sql}) SELECT {', '.join(select_parts)} FROM _src"


def _detect_geometry_in_rows(
    rows: List[Dict[str, Any]], columns: List[str]
) -> set:
    """Detect geometry columns by checking if values look like hex WKB strings."""
    if not rows:
        return set()
    geom_cols = set()
    first_row = rows[0]
    for col in columns:
        val = first_row.get(col)
        if isinstance(val, str) and len(val) > 10:
            try:
                bytes.fromhex(val)
                geom_cols.add(col)
            except ValueError:
                pass
    return geom_cols


def _has_limit(sql: str) -> bool:
    """Return True if the SQL already contains a LIMIT clause."""
    return bool(re.match(r"(?si).*\bLIMIT\s+\d+", sql))


def _add_limit(sql: str, max_rows: int) -> str:
    return f"{sql} LIMIT {max_rows}"


def execute_query(
    query: str, max_rows: int, read_only: bool, conn
) -> QueryExecuteResponse:
    start = time.perf_counter()

    # 1. Validate
    clean_sql = query.strip().rstrip(";").strip()
    if not clean_sql:
        return QueryExecuteResponse(
            success=False,
            error="Query must not be empty",
            execution_time_ms=0,
        )

    # 2. Detect query type
    first_word = clean_sql.upper().split()[0]
    if first_word == "WITH":
        query_type = "SELECT"
    elif first_word in ("SELECT", "INSERT", "UPDATE", "DELETE"):
        query_type = first_word
    else:
        query_type = "UNKNOWN"

    # 3. read_only check
    is_select = query_type == "SELECT"
    if read_only and not is_select:
        return QueryExecuteResponse(
            success=False,
            query_type=query_type,
            error=f"read_only mode does not allow {first_word} statements",
            execution_time_ms=int((time.perf_counter() - start) * 1000),
        )

    cursor = conn.cursor()

    try:
        # 4. Transaction setup
        cursor.execute("SET LOCAL search_path = 'data', 'public'")
        cursor.execute("SET LOCAL statement_timeout = '30s'")
        cursor.execute("SAVEPOINT analytics_query")

        if is_select:
            # Add LIMIT if not present
            sql_to_run = clean_sql if _has_limit(clean_sql) else _add_limit(clean_sql, max_rows)

            columns: List[str] = []
            rows: List[Dict[str, Any]] = []
            original_error = None

            try:
                cursor.execute(sql_to_run)
                columns = [desc[0] for desc in cursor.description] if cursor.description else []
                raw_rows = cursor.fetchall()
                rows = [dict(zip(columns, row)) for row in raw_rows]
            except Exception as exc:
                original_error = exc
                # Rollback to savepoint and retry with geometry wrapping
                cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
                cursor.execute("SAVEPOINT analytics_query")

                try:
                    column_metas = _detect_geometry_columns(cursor, clean_sql)
                    has_geom = any(is_geom for _, is_geom in column_metas)

                    if has_geom:
                        wrapped_sql = _build_geojson_wrapped_sql(clean_sql, column_metas)
                        if not _has_limit(wrapped_sql):
                            wrapped_sql = _add_limit(wrapped_sql, max_rows)
                        cursor.execute(wrapped_sql)
                        columns = [desc[0] for desc in cursor.description] if cursor.description else []
                        raw_rows = cursor.fetchall()
                        rows = [dict(zip(columns, row)) for row in raw_rows]
                        original_error = None
                    else:
                        raise original_error
                except Exception:
                    raise original_error

            # Check for hex WKB geometry in result rows
            if rows and original_error is None:
                geom_cols = _detect_geometry_in_rows(rows, columns)
                if geom_cols:
                    # Re-run with geometry wrapping
                    # Build column_metas using detected geom cols
                    column_metas_from_rows = [
                        (col, col in geom_cols) for col in columns
                    ]
                    wrapped_sql = _build_geojson_wrapped_sql(clean_sql, column_metas_from_rows)
                    if not _has_limit(wrapped_sql):
                        wrapped_sql = _add_limit(wrapped_sql, max_rows)
                    cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
                    cursor.execute("SAVEPOINT analytics_query")
                    cursor.execute(wrapped_sql)
                    columns = [desc[0] for desc in cursor.description] if cursor.description else []
                    raw_rows = cursor.fetchall()
                    rows = [dict(zip(columns, row)) for row in raw_rows]

            truncated = len(rows) >= max_rows
            cursor.execute("RELEASE SAVEPOINT analytics_query")

            elapsed_ms = int((time.perf_counter() - start) * 1000)
            return QueryExecuteResponse(
                success=True,
                query_type=query_type,
                columns=columns,
                rows=rows,
                row_count=len(rows),
                affected_rows=0,
                execution_time_ms=elapsed_ms,
                truncated=truncated,
            )

        else:
            # DML execution
            cursor.execute(clean_sql)
            affected_rows = cursor.rowcount if cursor.rowcount is not None else 0
            conn.commit()
            cursor.execute("RELEASE SAVEPOINT analytics_query")

            elapsed_ms = int((time.perf_counter() - start) * 1000)
            return QueryExecuteResponse(
                success=True,
                query_type=query_type,
                columns=[],
                rows=[],
                row_count=0,
                affected_rows=affected_rows,
                execution_time_ms=elapsed_ms,
                truncated=False,
            )

    except Exception as exc:
        try:
            cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
        except Exception:
            pass
        elapsed_ms = int((time.perf_counter() - start) * 1000)
        return QueryExecuteResponse(
            success=False,
            query_type=query_type,
            error=str(exc),
            execution_time_ms=elapsed_ms,
        )
    finally:
        try:
            cursor.execute("SET LOCAL search_path TO data")
        except Exception:
            pass
        cursor.close()
