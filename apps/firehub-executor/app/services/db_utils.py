"""Shared database utilities for executor services."""
from __future__ import annotations

from typing import Any, Dict, List

from psycopg2 import sql as pgsql
from psycopg2.extras import execute_values


def insert_batch(conn, table_name: str, rows: List[Dict[str, Any]]) -> None:
    """Batch-insert rows into target table using execute_values."""
    if not rows:
        return

    columns = list(rows[0].keys())
    col_identifiers = [pgsql.Identifier(c) for c in columns]

    query = pgsql.SQL("INSERT INTO {table} ({cols}) VALUES %s").format(
        table=pgsql.Identifier(table_name),
        cols=pgsql.SQL(", ").join(col_identifiers),
    )

    values = [tuple(row[c] for c in columns) for row in rows]

    with conn.cursor() as cur:
        execute_values(cur, query.as_string(cur), values)
