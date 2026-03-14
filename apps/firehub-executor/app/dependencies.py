from typing import Generator

from app.config import Settings, get_settings
from app.db.connection import get_connection
from app.middleware.auth import verify_internal_auth  # re-export for routers


def get_cached_settings() -> Settings:
    return get_settings()


def get_db_connection() -> Generator:
    with get_connection() as conn:
        yield conn
