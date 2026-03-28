from contextlib import contextmanager
from typing import Generator, Optional

import psycopg2
from psycopg2 import pool as pg_pool

from app.config import Settings

_pool: Optional[pg_pool.ThreadedConnectionPool] = None


def init_pool(settings: Settings) -> None:
    global _pool
    _pool = pg_pool.ThreadedConnectionPool(
        minconn=settings.db_pool_min,
        maxconn=settings.db_pool_max,
        host=settings.db_host,
        port=settings.db_port,
        dbname=settings.db_name,
        user=settings.db_user,
        password=settings.db_password,
    )


def close_pool() -> None:
    global _pool
    if _pool is not None:
        _pool.closeall()
        _pool = None


def _is_conn_alive(conn) -> bool:
    """Check if a pooled connection is still usable."""
    try:
        if conn.closed:
            return False
        conn.rollback()
        with conn.cursor() as cur:
            cur.execute("SELECT 1")
        return True
    except Exception:
        return False


@contextmanager
def get_connection() -> Generator:
    if _pool is None:
        raise RuntimeError("Database connection pool is not initialized")

    conn = None
    try:
        conn = _pool.getconn()
        if conn is None:
            raise RuntimeError("Connection pool exhausted — no available connections")
        if not _is_conn_alive(conn):
            _pool.putconn(conn, close=True)
            conn = _pool.getconn()
        with conn.cursor() as cur:
            cur.execute("SET search_path TO data")
        yield conn
    except pg_pool.PoolError as exc:
        raise RuntimeError(f"Connection pool error: {exc}") from exc
    finally:
        if conn is not None:
            _pool.putconn(conn)
