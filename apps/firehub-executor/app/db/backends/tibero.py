"""Tibero(ODBC) 백엔드.

설계 원칙:
- **pyodbc 지연 임포트**: pyodbc 는 이미지 전용 ``requirements-odbc.txt`` 로만 설치한다. 개발 맥·CI 에는 없으므로
  모듈 임포트·생성자·``backend_for`` 는 pyodbc 를 건드리지 않고, 실제 접속하는 순간에만 ``load_odbc()`` 로 부른다.
- **커넥션 풀 없음(WD-75 판정)**: 호출마다 접속하고 닫는다. executor 작업은 초~분 단위라 접속 비용이 작다.
- **비밀번호 비노출**: 비밀번호는 ``connection_string()`` 인자로만 꺼내고, 접속 실패 메시지에는 SQLSTATE 와
  ``host:port`` 만 싣는다. 드라이버 원본 예외는 접속 문자열을 담을 수 있어 체인을 끊는다(``from None``).
- **PG 와 같은 응답 규약**: 빈 쿼리·read_only 거부 문구, 문 종류 판정, 컬럼 이름 고유화, 값의 Python 타입을
  PG 경로(``query_executor``)와 같게 맞춘다 — 응답 JSON 이 백엔드와 무관하게 같아야 한다.
"""
from __future__ import annotations

import logging
import time
from contextlib import contextmanager
from typing import Any, Callable, ContextManager, Dict, Iterator, List

from app.config import Settings
from app.db.backends.base import BACKEND_TIBERO
from app.db.backends.tibero_sql import SESSION_INIT_SQL, connection_string, insert_sql
from app.db.backends.tibero_values import bind_value, convert_row
from app.db.store_client import StoreDescriptor
from app.schemas.responses import QueryExecuteResponse

# 정규화·문 종류 판정·컬럼 이름 고유화는 PG 경로와 같은 규칙을 쓴다(읽기 전용 재사용). 주석·문자열 어휘는
# Oracle 계열과 겹치는 범위에서만 쓰며, PG 전용 LIMIT 렉서(_apply_row_limit)는 쓰지 않는다 — 행 상한은
# fetchmany 로 지킨다.
from app.services.query_executor import _detect_query_type, _normalize_sql, _unique_column_names

logger = logging.getLogger(__name__)

# executemany 한 번에 보내는 행 수. fast_executemany 는 켜지 않는다(WD-77 에서 실측 후 결정).
INSERT_CHUNK_SIZE = 500

# 응답 query_type 으로 내보내는 문 종류(PG 경로와 같다). 그 밖은 UNKNOWN.
_REPORTED_QUERY_TYPES = ("SELECT", "INSERT", "UPDATE", "DELETE")


class TiberoDriverUnavailable(RuntimeError):
    """Tibero ODBC 연결 모듈(pyodbc)을 쓸 수 없다. 이미지에 ODBC 레이어가 없을 때 난다."""


class TiberoConnectError(RuntimeError):
    """Tibero 접속 실패. 메시지는 SQLSTATE 와 host:port 만 담는다(비밀번호·접속 문자열 없음)."""


def load_odbc() -> Any:
    """pyodbc 를 지연 임포트해 돌려준다. 없으면 한국어 메시지의 ``TiberoDriverUnavailable``."""
    try:
        import pyodbc  # 지연 임포트: 개발 환경에는 없다
    except ImportError:
        raise TiberoDriverUnavailable("Tibero ODBC 연결 모듈(pyodbc)이 설치되어 있지 않습니다") from None
    return pyodbc


def _sqlstate_of(exc: BaseException) -> str:
    """pyodbc 예외의 SQLSTATE(``args[0]``). 형태가 다르면 원문을 싣지 않고 대체 문구를 쓴다.

    ``args[0]`` 가 SQLSTATE 가 아니라 드라이버 메시지일 수도 있어(ODBC 메시지 형식 미검증) 짧은 영숫자 코드일
    때만 그대로 쓴다 — 메시지에 접속 문자열 조각이 섞여 나가는 일을 막는다.
    """
    state = exc.args[0] if exc.args else None
    if isinstance(state, str) and 0 < len(state) <= 5 and state.isascii() and state.isalnum():
        return state
    return "알 수 없음"


def _result_column_name(name: str) -> str:
    """결과 컬럼 이름을 PG 응답 규약에 맞춘다.

    따옴표 없는 Oracle 식별자는 대문자로 저장·반환되므로(``total`` → ``TOTAL``) 그런 이름만 소문자로 바꾼다 —
    PG 가 같은 쿼리에 주는 이름과 같아진다. 대소문자가 섞인 이름은 사용자가 따옴표로 지정한 별칭(``"TotalAmt"``)이라
    그대로 둔다(PG 도 따옴표 별칭은 그대로 돌려준다).
    """
    return name.lower() if name == name.upper() else name


def _driver_error_message(exc: BaseException) -> str:
    """사용자에게 보일 쿼리 오류 문구.

    pyodbc.Error 는 ``args = (SQLSTATE, 드라이버 메시지)`` 형태라 ``str(e)`` 는 튜플 표기("('42S02', '...')")가 된다.
    ``args[1]`` 이 문자열이면 그 드라이버 메시지만 쓰고, 아니면 ``str(e)`` 로 돌아간다. 쿼리 실행 오류에는 접속 문자열이
    들어가지 않는다 — 접속 단계 오류는 ``TiberoConnectError`` 가 SQLSTATE·host:port 만 싣는다.
    """
    args = getattr(exc, "args", ())
    if len(args) > 1 and isinstance(args[1], str):
        return args[1]
    return str(exc)


class TiberoBackend:
    """테넌트 Tibero 스키마에 OWNER 계정으로 접속하는 ``DbBackend`` 구현.

    ``descriptor`` 는 api 기술자 조회(StoreClient)가 검증한 값이다. 한 인스턴스는 한 테넌트 전용이며,
    다른 테넌트 id 로 부르면 거부한다(테넌트 격리 방어선).
    """

    kind = BACKEND_TIBERO

    def __init__(
        self,
        descriptor: StoreDescriptor,
        settings: Settings,
        *,
        odbc: Any = None,
        on_connect_failure: Callable[[int], None] | None = None,
    ) -> None:
        # odbc: 테스트가 가짜 모듈을 주입하는 자리. None 이면 접속 시점에 pyodbc 를 지연 임포트한다.
        # on_connect_failure: 접속 실패 시 기술자 캐시를 버리는 콜백(계정 회전 후 옛 비밀번호를 60초간 붙잡지 않게).
        self._desc = descriptor
        self._settings = settings
        self._odbc = odbc
        self._on_connect_failure = on_connect_failure

    def _require_tenant(self, tenant_id: int) -> None:
        """기술자의 테넌트와 다른 테넌트로 부르면 거부한다 — 다른 테넌트 스키마 접근을 원천 차단한다."""
        if tenant_id != self._desc.tenant_id:
            raise ValueError(
                f"Tibero 백엔드의 테넌트({self._desc.tenant_id})와 요청 테넌트({tenant_id})가 다릅니다"
            )

    def connection(self, tenant_id: int) -> ContextManager[Any]:
        """테넌트 OWNER 계정으로 접속한 커넥션을 빌려준다. 블록을 나오면(예외여도) 닫는다."""
        return self._connection(tenant_id)

    @contextmanager
    def _connection(self, tenant_id: int) -> Iterator[Any]:
        self._require_tenant(tenant_id)
        odbc = self._odbc if self._odbc is not None else load_odbc()
        d = self._desc
        try:
            conn = odbc.connect(
                connection_string(d.host, d.port, d.database, d.owner.username, d.owner.password.get_secret_value()),
                autocommit=False,
                timeout=self._settings.tibero_connect_timeout_seconds,
            )
        except odbc.Error as exc:
            message = f"Tibero 저장소에 접속하지 못했습니다({d.host}:{d.port}, SQLSTATE {_sqlstate_of(exc)})"
            # 계정 회전 등으로 기술자가 낡았을 수 있으니 캐시를 버려 다음 요청이 다시 조회하게 한다.
            if self._on_connect_failure is not None:
                self._on_connect_failure(tenant_id)
            # 원본 예외는 접속 문자열(비밀번호)을 담을 수 있어 체인을 끊는다.
            raise TiberoConnectError(message) from None

        try:
            # 한글이 깨지지 않도록 문자 인코딩을 명시한다(CHAR 는 UTF-8, WCHAR 는 UTF-16LE).
            conn.setdecoding(odbc.SQL_CHAR, encoding="utf-8")
            conn.setdecoding(odbc.SQL_WCHAR, encoding="utf-16le")
            conn.setencoding(encoding="utf-16le")
            timeout_applied = self._apply_query_timeout(conn, tenant_id)
            # 서버 기본 NLS_DATE_FORMAT 이 YYYY/MM/DD 라서 세션 NLS 초기화가 필수다(api 와 같은 문장).
            cursor = self._first_cursor(conn, odbc, tenant_id, timeout_applied)
            try:
                cursor.execute(SESSION_INIT_SQL)
            finally:
                cursor.close()
            # 초기화 블록이 트랜잭션을 열었을 수 있으니 바로 끝낸다 — 이후 execute_query 의
            # SET TRANSACTION READ ONLY 가 트랜잭션 첫 문장이 되게 하기 위해서다(비용은 거의 없다).
            conn.commit()
        except BaseException:
            # 초기화가 덜 된 세션을 넘기지 않는다 — 닫고 원래 예외를 그대로 올린다.
            self._close_quietly(conn)
            raise

        try:
            yield conn
        finally:
            self._close_quietly(conn)

    def _apply_query_timeout(self, conn: Any, tenant_id: int) -> bool:
        """쿼리 타임아웃 값을 커넥션에 건다. 걸었으면 True.

        pyodbc 는 ``conn.timeout`` 을 저장만 하고 실제 적용(SQL_ATTR_QUERY_TIMEOUT)은 ``cursor()`` 때 하므로, 드라이버
        거부는 보통 여기가 아니라 ``_first_cursor`` 에서 드러난다. 여기서는 설정 자체가 실패하는 경우만 방어한다.
        예외 원문은 드라이버 메시지라 싣지 않고 종류만 남긴다.
        """
        try:
            conn.timeout = self._settings.tibero_query_timeout_seconds
        except Exception as exc:
            logger.warning(
                "Tibero 드라이버가 쿼리 타임아웃 설정을 지원하지 않아 타임아웃 없이 진행합니다(tenant=%s, %s)",
                tenant_id,
                type(exc).__name__,
            )
            return False
        return True

    def _first_cursor(self, conn: Any, odbc: Any, tenant_id: int, timeout_applied: bool) -> Any:
        """접속 후 첫 커서를 연다. 드라이버가 쿼리 타임아웃을 거부하면 타임아웃 없이 한 번 다시 연다.

        ODBC 쿼리 타임아웃 지원은 실서버에서 미검증이라, 지원하지 않는 드라이버 때문에 접속 자체가 실패하면 안 된다.
        ``timeout = 0`` 으로 바꾸면 이후 커서(execute_query·insert_batch)도 타임아웃 없이 열린다. 재시도도 실패하면
        그 예외를 그대로 올린다(호출 측이 커넥션을 닫는다).
        """
        try:
            return conn.cursor()
        except odbc.Error as exc:
            if not timeout_applied:
                raise
            logger.warning(
                "Tibero 드라이버가 쿼리 타임아웃을 거부해 타임아웃 없이 다시 시도합니다(tenant=%s, SQLSTATE %s)",
                tenant_id,
                _sqlstate_of(exc),
            )
            conn.timeout = 0
            return conn.cursor()

    @staticmethod
    def _close_quietly(conn: Any) -> None:
        """커넥션을 닫는다. 닫기 실패가 원래 예외·결과를 가리지 않게 경고로만 남긴다."""
        try:
            conn.close()
        except Exception as exc:
            logger.warning("Tibero 커넥션을 닫지 못했습니다(%s)", type(exc).__name__)

    def insert_batch(self, conn: Any, table_name: str, rows: List[Dict[str, Any]]) -> None:
        """``rows`` 를 테넌트 스키마의 ``table_name`` 에 일괄 삽입한다. 커밋은 호출자 몫이다(PG 와 같다).

        열 목록은 PG 와 같이 첫 행의 키 순서다. SQL 은 커서를 열기 전에 만든다 — 이름 검증 실패 시 DB 를
        건드리지 않는다.
        """
        if not rows:
            return
        columns = list(rows[0].keys())
        sql = insert_sql(self._desc.schema_name, table_name, columns)
        cursor = conn.cursor()
        # WD-77 TODO: pyodbc 는 fast_executemany 없이는 행마다 서버 왕복을 한다. Tibero ODBC 드라이버가
        # fast_executemany(배열 바인딩)를 지원하는지 WD-77 에서 실서버로 확인한 뒤 켠다.
        try:
            for i in range(0, len(rows), INSERT_CHUNK_SIZE):
                chunk = rows[i : i + INSERT_CHUNK_SIZE]
                cursor.executemany(sql, [tuple(bind_value(row[c]) for c in columns) for row in chunk])
        finally:
            cursor.close()

    def execute_query(
        self, query: str, max_rows: int, read_only: bool, conn: Any, *, tenant_id: int
    ) -> QueryExecuteResponse:
        """분석 쿼리를 실행한다. 응답 규약(문구·문 종류·컬럼 이름·값 타입)은 PG 경로와 같다.

        PG 와 다른 점: 행 상한은 SQL 에 LIMIT 을 붙이지 않고 ``fetchmany(max_rows)`` 로 지킨다(Oracle 계열은
        LIMIT 문법이 다르다). 세이브포인트 대신 트랜잭션 단위로 rollback/commit 한다.
        """
        self._require_tenant(tenant_id)
        start = time.perf_counter()

        # WD-77 TODO: _normalize_sql 은 끝의 ';' 를 지운다 — PL/SQL 블록(END;)이 이 경로로 들어오면 깨진다.
        # WD-75 에서는 PL/SQL 이 execute_query 에 오지 않는다. Tibero 쓰기 경로(WD-77)에서 PL/SQL 을 받게 되면 여기서 처리한다.
        clean_sql = _normalize_sql(query)
        if not clean_sql:
            return QueryExecuteResponse(success=False, error="Query must not be empty", execution_time_ms=0)

        detected = _detect_query_type(clean_sql)
        query_type = detected if detected in _REPORTED_QUERY_TYPES else "UNKNOWN"
        is_select = query_type == "SELECT"
        if read_only and not is_select:
            # DB 에 닿기 전에 거부한다(PG 와 같은 문구).
            return QueryExecuteResponse(
                success=False,
                query_type=query_type,
                error=f"read_only mode does not allow {detected} statements",
                execution_time_ms=self._elapsed_ms(start),
            )

        cursor = conn.cursor()
        try:
            if read_only:
                # 분류가 놓친 쓰기도 DB 가 거부하게 하는 2차 방어선. 트랜잭션 첫 문장이어야 한다.
                cursor.execute("SET TRANSACTION READ ONLY")
            cursor.execute(clean_sql)

            if is_select:
                description = list(cursor.description or [])
                # Oracle 계열은 따옴표 없는 이름을 대문자로 돌려준다 — PG 응답과 같게 소문자로 맞추고 고유화한다.
                columns = _unique_column_names([_result_column_name(d[0]) for d in description])
                raw_rows = cursor.fetchmany(max_rows) if description else []
                rows = [dict(zip(columns, convert_row(r, description))) for r in raw_rows]
                # 읽기만 했으므로 트랜잭션을 정리한다(다음 요청의 SET TRANSACTION 이 첫 문장이 되게).
                conn.rollback()
                return QueryExecuteResponse(
                    success=True,
                    query_type=query_type,
                    columns=columns,
                    rows=rows,
                    row_count=len(rows),
                    affected_rows=0,
                    execution_time_ms=self._elapsed_ms(start),
                    truncated=len(rows) >= max_rows,
                )

            # pyodbc 는 행 수를 모르면 -1 을 준다 — 응답에는 0 으로 싣는다.
            rowcount = cursor.rowcount
            affected_rows = rowcount if isinstance(rowcount, int) and rowcount >= 0 else 0
            conn.commit()
            return QueryExecuteResponse(
                success=True,
                query_type=query_type,
                columns=[],
                rows=[],
                row_count=0,
                affected_rows=affected_rows,
                execution_time_ms=self._elapsed_ms(start),
                truncated=False,
            )
        except Exception as exc:
            try:
                conn.rollback()
            except Exception:
                pass
            # Tibero 고유 오류 코드(예: 8033 테이블 없음)가 메시지에 남도록 드라이버 메시지를 싣는다(PG 경로와 같은 방식).
            return QueryExecuteResponse(
                success=False,
                query_type=query_type,
                error=_driver_error_message(exc),
                execution_time_ms=self._elapsed_ms(start),
            )
        finally:
            try:
                cursor.close()
            except Exception:
                pass

    @staticmethod
    def _elapsed_ms(start: float) -> int:
        return int((time.perf_counter() - start) * 1000)
