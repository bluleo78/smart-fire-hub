"""기동 시 설정 경고 테스트 (WD-75): EXECUTOR_API_BASE_URL 누락 경고."""
import logging

import pytest

from app.config import Settings
from app.main import warn_if_api_base_url_unset
from tests.conftest import get_test_settings

_LOGGER = "app.main"


def _settings(**over) -> Settings:
    """.env 파일을 읽지 않는 Settings — 작업 디렉터리의 .env 가 결과를 바꾸지 않게 한다.

    공용 테스트 설정은 api_base_url 을 명시하므로, 여기서는 그 키를 빼고 다시 만들어 "명시하지 않음" 상태를 재현한다.
    """
    values = get_test_settings().model_dump(exclude={"api_base_url"})
    values.update(over)
    return Settings(_env_file=None, **values)


def _warnings(caplog):
    return [r.getMessage() for r in caplog.records if r.name == _LOGGER and r.levelno == logging.WARNING]


def test_warns_once_when_api_base_url_not_set(monkeypatch, caplog):
    monkeypatch.delenv("EXECUTOR_API_BASE_URL", raising=False)
    with caplog.at_level(logging.WARNING, logger=_LOGGER):
        warn_if_api_base_url_unset(_settings())
    msgs = _warnings(caplog)
    assert len(msgs) == 1
    assert "EXECUTOR_API_BASE_URL" in msgs[0]


@pytest.mark.parametrize("url", ["http://api:8080", "http://localhost:8080"])
def test_no_warning_when_set_by_environment(monkeypatch, caplog, url):
    # 기본값과 같은 값이어도 명시했으면 의도한 설정으로 본다.
    monkeypatch.setenv("EXECUTOR_API_BASE_URL", url)
    with caplog.at_level(logging.WARNING, logger=_LOGGER):
        warn_if_api_base_url_unset(_settings())
    assert _warnings(caplog) == []


def test_warning_does_not_leak_url(monkeypatch, caplog):
    monkeypatch.delenv("EXECUTOR_API_BASE_URL", raising=False)
    with caplog.at_level(logging.WARNING, logger=_LOGGER):
        warn_if_api_base_url_unset(_settings())
    assert all("http://" not in m and "8080" not in m for m in _warnings(caplog))
