"""Tibero 실서버 테스트(ODBC). 환경변수와 드라이버가 모두 있을 때만 돈다.

조건: ``TIBERO_TEST_HOST`` 가 있고, pyodbc 를 임포트할 수 있고, ``"Tibero"`` 가 ``pyodbc.drivers()`` 에 있다.
환경변수 이름은 api 실서버 테스트와 같다(``TIBERO_TEST_HOST/PORT/SID/DBA_USER/DBA_PASSWORD``).

공유 서버이고 DBA 계정이므로 **DUAL 읽기 전용 질의만** 한다 — 어떤 객체도 만들거나 지우지 않는다.
없는 테이블 조회(오류 코드 확인)도 읽기 전용 트랜잭션 안에서만 한다.
"""
import os
from datetime import date, datetime
from decimal import Decimal

import pytest

from app.db.backends.tibero import TiberoBackend
from app.db.store_client import StoreDescriptor
from tests.conftest import get_test_settings


def _skip_reason() -> str | None:
    """실서버 테스트를 건너뛸 이유. 돌 수 있으면 None."""
    if not os.environ.get("TIBERO_TEST_HOST"):
        return "TIBERO_TEST_HOST 가 비어 있다"
    try:
        import pyodbc  # noqa: F401 — 드라이버 유무만 확인한다(지연 임포트 원칙: 모듈 수준에서 실패시키지 않는다)
    except ImportError:
        return "pyodbc 를 임포트할 수 없다"
    try:
        if "Tibero" not in pyodbc.drivers():
            return "Tibero ODBC 드라이버가 등록돼 있지 않다"
    except Exception:  # 드라이버 관리자(unixODBC) 자체가 없을 때 등
        return "ODBC 드라이버 목록을 읽을 수 없다"
    return None


_REASON = _skip_reason()
pytestmark = pytest.mark.skipif(_REASON is not None, reason=_REASON or "")

# 기술자 tenant 와 connection(tenant) 인자가 같기만 하면 된다(StoreClient 검증을 거치지 않는다).
TENANT = 1


@pytest.fixture
def live():
    """DBA 계정으로 직접 만든 기술자의 백엔드. 비밀번호 값은 출력하지 않는다."""
    user = os.environ["TIBERO_TEST_DBA_USER"]
    desc = StoreDescriptor.model_validate({
        "tenantId": TENANT, "backend": "TIBERO",
        "host": os.environ["TIBERO_TEST_HOST"], "port": int(os.environ.get("TIBERO_TEST_PORT", "8629")),
        "database": os.environ["TIBERO_TEST_SID"], "schema": user.upper(),
        "owner": {"username": user, "password": os.environ["TIBERO_TEST_DBA_PASSWORD"]},
    })
    return TiberoBackend(desc, get_test_settings())


def query(backend, sql, max_rows=100):
    """읽기 전용으로 한 번 실행한다(접속·세션 초기화·닫기 포함)."""
    with backend.connection(TENANT) as conn:
        return backend.execute_query(sql, max_rows, True, conn, tenant_id=TENANT)


def test_live_session_nls_applied(live):
    # 세션 초기화가 빠지면 기본 NLS_DATE_FORMAT(YYYY/MM/DD)으로 "2024/01/02" 가 나온다.
    res = query(live, "SELECT TO_CHAR(DATE '2024-01-02') AS s FROM DUAL")
    assert res.success, res.error
    assert res.rows == [{"s": "2024-01-02"}]


def test_live_value_shapes(live):
    res = query(
        live,
        "SELECT '한글' AS k, DATE '2024-01-02' AS d, TIMESTAMP '2024-01-02 03:04:05.123456' AS ts, "
        "CAST(1 AS NUMBER(1)) AS b, CAST(42 AS NUMBER(19)) AS n, CAST(1.5 AS NUMBER(18,6)) AS m, "
        "COUNT(*) AS c FROM DUAL",
    )
    assert res.success, res.error
    r = res.rows[0]
    assert r["k"] == "한글"
    # datetime 은 date 의 하위 타입이라 type 으로 단언한다.
    assert type(r["d"]) is date and r["d"] == date(2024, 1, 2)
    assert type(r["ts"]) is datetime and r["ts"] == datetime(2024, 1, 2, 3, 4, 5, 123456)
    assert r["b"] is True
    assert type(r["n"]) is int and r["n"] == 42
    # Decimal 동등 비교는 자릿수 버그를 숨긴다 — PG numeric(18,6) 과 같은 문자열인지 본다.
    assert isinstance(r["m"], Decimal) and str(r["m"]) == "1.500000"
    assert type(r["c"]) is int and r["c"] == 1


def test_live_row_cap(live):
    res = query(live, "SELECT LEVEL AS n FROM DUAL CONNECT BY LEVEL <= 5", max_rows=3)
    assert res.success, res.error
    assert res.row_count == 3
    assert res.truncated is True


def test_live_native_error_code(live):
    res = query(live, "SELECT * FROM NO_SUCH_TABLE_WD75")
    assert res.success is False
    assert "8033" in res.error
