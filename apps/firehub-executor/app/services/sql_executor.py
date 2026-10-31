import logging
from time import monotonic
from typing import Callable, Optional

from app.schemas.responses import DbErrorInfo, PgErrorInfo, SqlExecuteResponse, db_error_of
from app.services.db_utils import (
    pre_statement_timeout_message,
    set_local_statement_timeout,
    statement_timeout_message,
)
from app.services.query_executor import RunningStatement, detect_statement_type, sqlstate
from app.validators.sql_validator import (
    SqlValidationError,
    output_lock_key,
    validate,
    validate_pre_statement,
    validate_pre_statement_pairing,
)

logger = logging.getLogger(__name__)

# 잠금 뒤 중지 확인으로 롤백했을 때의 응답 문구(WD-466). api 는 cancelled 로 판정하고 이 문구는 로그용이다.
CANCELLED_MESSAGE = "파이프라인 실행이 취소되어 출력 잠금을 얻은 뒤 SQL 을 실행하지 않았습니다"


class _StopRequested(Exception):
    """출력 잠금을 얻은 뒤 파이프라인 중지 요청을 확인했다(WD-466) — 비우기·본문을 보내지 않고 롤백한다."""


def _stop_requested_quietly(stop_requested: Callable[[], bool]) -> bool:
    """중지 확인 — 확인마저 실패하면(api 장애 등) 중지 요청이 없는 것으로 보고 원래대로 실행한다(api 러너의 isStopRequestedQuietly 와
    같은 판단: 확인 실패로 정상 실행을 실패시키지 않는다)."""
    try:
        return bool(stop_requested())
    except Exception:  # noqa: BLE001 — 어떤 실패든 실행을 막지 않는다
        logger.warning("파이프라인 중지 요청 확인 실패 — 그대로 실행합니다", exc_info=True)
        return False


# 출력 테이블 단위 직렬화 잠금 문장(#731) — 트랜잭션 범위 advisory 잠금. 키는 선행 문장에 적힌 한정 이름("<스키마>"."<테이블>").
OUTPUT_LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))"


class PreStatementBudgetExhausted(Exception):
    """선행 문장 합계 예산이 다음 문장을 보내기 전에 이미 바닥났다(WD-262) — 문장을 보내지 않고 실패로 끝낸다."""


def arm_pre_statement_budget(cursor, started_at: float, budget_seconds: int) -> float:
    """선행 문장 하나를 보내기 직전에 남은 예산을 ``statement_timeout`` 으로 걸고 그 값(초)을 돌려준다. 예산이 소진됐으면 문장을 보내지
    않고 예외를 낸다.

    남은 시간은 ``budget_seconds - (지금 - started_at)`` 이다(``monotonic`` — 시스템 시계 조정에 흔들리지 않는다).
    0 이하이면 SET LOCAL 도 걸지 않는다: PG 에서 0 은 「제한 없음」이라 소진된 예산이 오히려 무제한이 되기 때문이다.
    돌려준 값은 그 문장의 시간 초과 판정 기준이다(WD-261 — 선행 문장은 전체 상한이 아니라 그 문장에 건 남은 예산과 대조한다).
    """
    remaining = budget_seconds - (monotonic() - started_at)
    if remaining <= 0:
        raise PreStatementBudgetExhausted()
    set_local_statement_timeout(cursor, remaining)
    return remaining


def _failed(
    message: str, pg_error: Optional[PgErrorInfo] = None, db_error: Optional[DbErrorInfo] = None
) -> SqlExecuteResponse:
    """실패 응답 — 행·컬럼 없이 문구와 (있으면) 구조화 오류만 싣는다. 검증 거부·시간 초과·그 밖의 실패가 같은 모양을 쓴다."""
    return SqlExecuteResponse(
        success=False,
        rows=None,
        columns=None,
        row_count=0,
        execution_log="",
        error=message,
        pg_error=pg_error,
        db_error=db_error,
    )


def execute_sql(
    query: str, conn, pre_statements=(), timeout_seconds: Optional[int] = None,
    *, default_timeout_seconds: int, pre_statement_timeout_seconds: int,
    stop_requested: Optional[Callable[[], bool]] = None,
) -> SqlExecuteResponse:
    """``default_timeout_seconds`` 는 요청에 제한이 없을 때 쓸 값 — 기본값은 ``Settings.sql_statement_timeout_seconds``
    한 곳뿐이라 여기서는 기본값을 두지 않고 라우터가 설정값을 넘긴다.

    ``pre_statement_timeout_seconds`` 는 선행 문장 **전체** 합계 상한(WD-262, ``Settings.sql_pre_statement_timeout_seconds``) —
    같은 이유로 라우터가 넘긴다. 사용자 시간 제한(``timeout_seconds``)은 본문에만 걸린다.

    ``stop_requested`` 는 파이프라인 중지 요청 확인(WD-466, 요청에 실행 id 가 있을 때 라우터가 넘긴다). 출력 잠금을 얻은 **직후**
    부른다 — 잠금 대기는 api 러너의 「SQL 실행 직전」 체크포인트보다 뒤라 그 사이 들어온 취소를 api 가 볼 수 없었고, 잠금을 얻은 뒤
    사용자 SQL 이 끝까지 돌아 출력을 다시 교체했다. 중지면 비우기·본문을 보내지 않고 롤백해 ``cancelled=True`` 로 답한다."""
    # 본 쿼리와 선행 문장(pre-statement) 모두 실행 전에 검증한다.
    # pre-statement 는 화이트리스트 기반 validate_pre_statement 로 본 쿼리보다 엄격하게 검사한다.
    try:
        validate(query)
        for stmt in pre_statements:
            validate_pre_statement(stmt)
        # 잠금 전용 선행 문장과 자기 출력 다시 채우기 문장은 서로의 짝으로만 받는다(WD-426) — 형태 검사를 지난 뒤 조합을 본다.
        validate_pre_statement_pairing(query, pre_statements)
    except SqlValidationError as exc:
        return _failed(str(exc), db_error=db_error_of(str(exc)))

    # 선행 문장이 있으면 "출력 비우기 + 쓰기"인 쓰기 트랜잭션이다.
    # SELECT 로 오판해 커밋을 건너뛰면 DELETE 로 비운 내용이 롤백되지 않고 남을 수 있으므로
    # pre_statements 가 있을 때는 무조건 쓰기 경로(커밋)로 취급한다.
    # 문 종류는 애널리틱스와 같은 분류기(앞 주석은 어휘 인지로 걷음)로 정한다(WD-93) — 예전엔 WITH 로 시작하면 읽기로 봐서 CTE DML 이
    # 실패하거나(no results to fetch) 커밋 없이 끝났다. 분류기가 틀려도 커밋 쪽으로만 틀린다.
    is_select = not pre_statements and detect_statement_type(query) == "SELECT"
    # 요청별 값 또는 기본 제한(WD-259) — 한 번만 정해 SET LOCAL 과 시간 초과 안내 문구가 같은 값을 쓴다(최종 리뷰).
    effective_timeout = timeout_seconds if timeout_seconds is not None else default_timeout_seconds

    # 지금 단계의 시간 초과 문구 — 선행 문장 단계에서 난 시간 초과만 「선행 시간 초과」 문구를 쓰고, 본문 직전에 본문 문구로 바꾼다.
    # 선행 문장이 없으면 선행 단계에서 시간 초과가 날 수 없으므로(제한 걸린 문장도 예산 소진도 없다) 처음 값은 쓰이지 않는다.
    timeout_message = pre_statement_timeout_message(pre_statement_timeout_seconds)
    # 제한 걸린 문장마다 시작 시각·제한을 적어 둔다 — 57014 가 그 문장의 시간 초과인지 가른다(WD-261).
    running = RunningStatement()
    try:
        with conn.cursor() as cursor:
            # 선행 문장(예: 출력 비우기 DELETE)과 본 쿼리를 같은 트랜잭션에서 실행한다.
            # 본 쿼리가 실패하면 아래 except 에서 rollback 하므로 선행 문장도 함께 취소된다 —
            # 이것이 "비우기와 쓰기를 원자적으로 만든다"는 이번 기능의 핵심이다.
            # 선행 문장 합계 예산(WD-262): 첫 선행 문장 시작부터 monotonic 으로 재고, 문장을 보내기 직전마다 남은 시간을
            # statement_timeout 으로 건다. db-executor SqlRunner 의 「선행 상한」과 같은 의미다(대형 출력의 DELETE 가 끝없이 도는 것을 막는다).
            pre_started_at = monotonic()
            for stmt in pre_statements:
                # 출력 테이블 단위 직렬화(#731). 비우기(DELETE) **직전에, 별도 문장으로** 이 테이블의
                # advisory 잠금을 잡는다. 같은 출력에 "비우기 + 적재"를 하는 두 실행이 겹치면
                # READ COMMITTED 에서 뒤 실행의 DELETE 가 앞 실행의 미커밋 행을 보지 못해 두 실행의
                # INSERT 가 모두 남는다(모든 행 중복). 잠금은 커밋·롤백 때 자동으로 풀리므로 뒤 실행은
                # 앞 실행 커밋 뒤에 비우기를 시작한다 — 거부되지 않고 기다렸다가 실행된다.
                # DELETE 와 같은 문장에서 기다리면 안 된다: 스냅샷이 문장 시작 시점에 잡히기 때문에
                # 대기가 풀려도 옛 스냅샷으로 지운다.
                # 키·문장은 API 의 SqlScriptExecutor.OUTPUT_LOCK_SQL 과 같아야 한다.
                # 잠금 대기도 선행 시간이다 — 잠금과 비우기를 각각 보내기 직전에 남은 예산을 다시 건다(잠금에서 오래 기다린 만큼
                # 비우기가 쓸 수 있는 시간이 줄어야 합계가 상한을 넘지 않는다).
                # 각 문장의 시간 초과 판정은 그 문장에 건 남은 예산과 그 문장의 경과 시간으로 한다(WD-261 §5.2).
                limit = arm_pre_statement_budget(cursor, pre_started_at, pre_statement_timeout_seconds)
                running.execute(cursor, limit, OUTPUT_LOCK_SQL, (output_lock_key(stmt),))
                # 잠금 대기 중에 들어온 취소(WD-466) — 비우기·사용자 SQL 전에 확인한다. 중지면 아래 except 가 롤백한다(출력 그대로).
                if stop_requested is not None and _stop_requested_quietly(stop_requested):
                    raise _StopRequested()
                limit = arm_pre_statement_budget(cursor, pre_started_at, pre_statement_timeout_seconds)
                running.execute(cursor, limit, stmt)
            timeout_message = statement_timeout_message("실행", effective_timeout)
            # 요청별 값 또는 기본 제한(WD-259). 선행 문장(잠금·비우기)은 사용자 제한을 받지 않고 위의 선행 예산을 받으며, 본문만 이
            # 제한을 받는다 — db-executor 와 같은 의미다(스펙 §6.2). 그래서 선행 문장 루프 뒤, 본문 직전에 다시 건다(앞서 건 선행
            # 예산의 남은 시간이 본문에 남지 않게). 기본값이 늘 있으므로 언제나 건다(리뷰 M-4).
            set_local_statement_timeout(cursor, effective_timeout)
            running.execute(cursor, effective_timeout, query)
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
        if isinstance(exc, _StopRequested):
            # 실패가 아니라 취소다 — 구조화 오류 없이 cancelled 만 싣는다(api 가 스텝을 CANCELLED 로 기록).
            return SqlExecuteResponse(
                success=False, rows=None, columns=None, row_count=0, execution_log="",
                error=CANCELLED_MESSAGE, cancelled=True,
            )
        if isinstance(exc, PreStatementBudgetExhausted) or running.timed_out(exc):
            # 우리가 건 제한에 걸렸다 — 선행 문장이면 합계 상한 초과(예산 소진으로 다음 문장을 못 보냈거나 선행 문장이 그 문장의 남은 예산만큼
            # 돌고 취소됨, 본문은 실행되지 않았고 위 rollback 으로 선행 문장도 취소됐다), 본문이면 본문 제한 초과다. 문구는 db-executor 와 같다.
            # 응답 값도 db-executor 의 시간 초과와 같게 구조화 오류(pg_error·db_error)를 싣지 않는다(WD-261 §5.3): 시간 초과는 DB 가 SQL 을
            # 거부한 것이 아니라 실행기가 판정한 실패이고, api 는 구조화 오류가 없으면 이 한국어 문구를 그대로 보여 준다 — pg_error(57014)를
            # 실으면 api 가 그쪽을 골라 「N초」 문구가 화면에서 사라진다.
            return _failed(timeout_message)
        # 그 밖의 실패(관리자 취소처럼 제한보다 일찍 끝난 57014 포함)는 원문과 구조화 오류를 그대로 둔다 — api 가 SQLState 로 안내를 붙인다.
        message = str(exc)
        return _failed(message, pg_error_info(exc), db_error_of(message, sqlstate(exc)))


def pg_error_info(exc: BaseException) -> Optional[PgErrorInfo]:
    """psycopg2 오류에서 SQLState·주 메시지·DETAIL·HINT·컬럼을 꺼낸다(WD-111). PG 오류가 아니면 None.

    SQLState 판별은 애드혹 질의 실행기와 같은 ``query_executor.sqlstate`` 를 쓴다. 주 메시지 등은 ``diag`` 에서
    읽는다 — ``str(exc)`` 는 위치 표시 줄(내부 SQL)까지 담는다.
    psycopg2 를 직접 import 하지 않고 속성으로 판별해, 목(mock) 예외나 다른 드라이버 예외에서도 안전하게 None 이 된다.
    """
    sql_state = sqlstate(exc)
    if not sql_state:
        return None
    diag = getattr(exc, "diag", None)

    def field(name: str) -> Optional[str]:
        value = getattr(diag, name, None) if diag is not None else None
        return value if isinstance(value, str) and value else None

    return PgErrorInfo(
        sql_state=sql_state,
        message=field("message_primary"),
        detail=field("message_detail"),
        hint=field("message_hint"),
        column=field("column_name"),
    )
