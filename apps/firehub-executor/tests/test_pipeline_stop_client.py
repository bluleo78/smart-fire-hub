"""api 중지 요청 확인 클라이언트(WD-466) — 경로·토큰·응답 해석."""
import httpx
import pytest

from app.db import pipeline_stop_client
from tests.conftest import get_test_settings


@pytest.fixture
def settings():
    return get_test_settings(internal_service_token="tok", api_base_url="http://api:8080/")


def _use_transport(monkeypatch, handler):
    monkeypatch.setattr(
        pipeline_stop_client, "_client", httpx.Client(transport=httpx.MockTransport(handler))
    )


def test_stop_check_calls_internal_endpoint_with_token(monkeypatch, settings):
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers.get("Authorization")
        return httpx.Response(200, json={"stopRequested": True})

    _use_transport(monkeypatch, handler)

    assert pipeline_stop_client.stop_check(settings, 7, 42)() is True
    assert seen["url"] == "http://api:8080/internal/executor/tenants/7/pipeline-executions/42/stop-requested"
    assert seen["auth"] == "Internal tok"


def test_stop_check_raises_on_non_200_or_bad_body(monkeypatch, settings):
    """실패는 예외로 올린다 — 호출부(sql_executor)가 fail-open 으로 처리한다."""
    _use_transport(monkeypatch, lambda r: httpx.Response(401))
    with pytest.raises(httpx.HTTPStatusError):
        pipeline_stop_client.stop_check(settings, 7, 42)()

    _use_transport(monkeypatch, lambda r: httpx.Response(200, json={"stopRequested": "yes"}))
    with pytest.raises(ValueError):
        pipeline_stop_client.stop_check(settings, 7, 42)()
