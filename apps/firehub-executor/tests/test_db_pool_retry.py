import asyncio
from unittest.mock import patch

import pytest

from app.main import _retry_init_pool_forever
from app.db import connection
from tests.conftest import get_test_settings


@pytest.mark.asyncio
async def test_retry_init_pool_forever_recovers_after_transient_failure():
    """db 컨테이너가 늦게 뜨는 등 일시적 원인으로 초기 init_pool()이 실패해도,
    백그라운드 재시도가 이후 성공하면 풀이 사용 가능 상태로 복구되어야 한다."""
    settings = get_test_settings()
    call_count = 0

    def flaky_init_pool(_settings):
        nonlocal call_count
        call_count += 1
        if call_count < 3:
            raise ConnectionError("connection refused")
        connection._pool = object()  # 성공 시나리오를 흉내낸 풀 객체

    connection._pool = None
    with patch("app.main.init_pool", side_effect=flaky_init_pool), patch(
        "asyncio.sleep", return_value=asyncio.sleep(0)
    ):
        await _retry_init_pool_forever(settings)

    assert call_count == 3
    assert connection.is_pool_ready() is True
    connection._pool = None
