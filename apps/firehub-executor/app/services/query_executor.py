from __future__ import annotations

import logging
import re
import time
import weakref
from typing import TYPE_CHECKING, Any, Callable, Dict, Iterator, List, Optional, Tuple

import psycopg2
from pydantic import TypeAdapter

from app.schemas.responses import DbErrorInfo, QueryExecuteResponse, db_error_of, to_json_value
from app.tenant import resolve_read_db_url, resolve_schema

if TYPE_CHECKING:
    from app.config import Settings

logger = logging.getLogger(__name__)


# 커넥션별 PostGIS 공간 타입 OID 캐시(#766). 키를 약한 참조로 둬서 풀이 커넥션을 닫으면 항목도 사라진다.
# OID 는 확장을 재설치하지 않는 한 커넥션 수명 동안 바뀌지 않으므로 매 쿼리마다 카탈로그를 다시 읽지 않는다.
_GEOM_OID_CACHE: "weakref.WeakKeyDictionary[Any, frozenset[int]]" = weakref.WeakKeyDictionary()

# PostGIS 확장이 설치된 스키마의 geometry/geography 타입만 고른다(#766). 타입 이름만으로 고르면 다른 스키마에
# 같은 이름으로 만든 사용자 타입(enum·domain 등)까지 geometry 로 오판해 ST_AsGeoJSON 래핑이 실패한다.
# 카탈로그를 pg_catalog 로 한정해 search_path(테넌트 스키마 + public)에 같은 이름 테이블이 있어도 가려지지 않고,
# pg_type·pg_extension 은 모든 롤이 읽을 수 있어 테넌트 롤 권한에도 영향받지 않는다. 직접 경로(Java,
# AdhocSpatialTypeNames #759)가 PostGIS 타입을 "설치 스키마(public)의 타입" 으로 보는 것과 같은 기준이다.
_GEOM_OID_SQL = (
    "SELECT t.oid FROM pg_catalog.pg_type t"
    " JOIN pg_catalog.pg_extension e ON e.extnamespace = t.typnamespace"
    " WHERE e.extname = 'postgis' AND t.typname IN ('geometry', 'geography')"
)


def _fetch_geom_oids(conn) -> frozenset[int]:
    """PostGIS 확장 스키마의 geometry/geography 타입 OID 집합을 돌려준다(커넥션 단위 캐시).

    성공 경로와 에러 fallback 경로가 공유한다. 값이 아니라 컬럼의 선언된
    타입 OID로 geometry 여부를 판정하기 위한 근거를 제공한다.
    """
    try:
        cached = _GEOM_OID_CACHE.get(conn)
    except TypeError:  # 약한 참조를 지원하지 않는 커넥션 객체 — 캐시 없이 매번 조회한다
        cached = None
    if cached is not None:
        return cached
    cur = conn.cursor()
    cur.execute(_GEOM_OID_SQL)
    oids = frozenset(row[0] for row in cur.fetchall())
    cur.close()
    try:
        _GEOM_OID_CACHE[conn] = oids
    except TypeError:
        pass
    return oids


def _unique_column_names(names: List[str]) -> List[str]:
    """결과 컬럼 이름을 겹치지 않게 만든다(WD-85).

    행을 컬럼 이름 키 dict 로 돌려주므로 JOIN 의 id·region 처럼 이름이 겹치면 앞 값이 뒤 값으로 덮였다.
    처음 나온 이름은 그대로 두고, 두 번째부터 같은 이름에는 _2, _3 … 을 붙인다. 사용자가 직접 쓴 이름
    (예: a_2)과 겹치는 번호는 건너뛴다. api 의 ResultColumnNames.unique 와 같은 규칙이어야 저장한 차트가
    실행 경로(직접/executor)와 상관없이 같은 컬럼을 찾는다 — 공용 픽스처
    firehub-api/src/test/resources/fixtures/result-column-name-vectors.json 으로 대조한다.
    """
    original = set(names)
    used: set[str] = set()
    result: List[str] = []
    for name in names:
        unique = name
        if name in used:
            n = 2
            # 뒤에 나올 사용자 컬럼 이름과도 겹치지 않게 원래 이름 전체를 피한다
            while f"{name}_{n}" in used or f"{name}_{n}" in original:
                n += 1
            unique = f"{name}_{n}"
        used.add(unique)
        result.append(unique)
    return result


def _column_names(cursor) -> List[str]:
    """커서 결과의 컬럼 이름을 고유하게 바꿔 돌려준다(WD-85)."""
    return _unique_column_names([d[0] for d in cursor.description]) if cursor.description else []


def _detect_geometry_columns(cursor, sql: str) -> List[Tuple[str, bool]]:
    """Detect column names and whether they are geometry via LIMIT 0 + pg_type lookup."""
    conn = cursor.connection
    # meta_cursor 를 먼저 연다 (커서 오픈 순서 보존).
    meta_cursor = conn.cursor()
    # 사용자 SQL 을 자기 줄에 얹는다(#741) — 끝이 한 줄 주석(--)이면 개행 없이 붙인 닫는 괄호까지
    # 주석이 되어 syntax error at end of input 이 난다. API 의 SqlColumnProbe·MergeSqlBuilder 와 같은 형태.
    meta_cursor.execute(f"SELECT * FROM (\n{sql}\n) _geom_detect LIMIT 0")

    geom_oids = _fetch_geom_oids(conn)

    descs = list(meta_cursor.description or [])
    # 래핑 CTE 가 컬럼을 이름으로 가리키므로 겹치는 이름은 고유하게 바꾼다(WD-85)
    names = _unique_column_names([desc[0] for desc in descs])
    # desc[1] = type_code in psycopg2
    columns: List[Tuple[str, bool]] = [
        (name, desc[1] in geom_oids) for name, desc in zip(names, descs)
    ]

    meta_cursor.close()
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
    # CTE 컬럼 별칭으로 원래 이름 대신 column_metas 의 (고유한) 이름을 붙인다 — 같은 이름 컬럼이 있으면
    # 바깥 SELECT 의 "id" 가 모호해 실행이 실패하기 때문이다(WD-85).
    aliases = ", ".join('"' + col_name.replace('"', '""') + '"' for col_name, _ in column_metas)
    # 사용자 SQL 을 자기 줄에 얹는다(#741) — 위 _detect_geometry_columns 와 같은 이유.
    return f"WITH _src({aliases}) AS (\n{original_sql}\n) SELECT {', '.join(select_parts)} FROM _src"


def _exec_geojson_wrapped(
    cursor, clean_sql: str, column_metas: List[Tuple[str, bool]], max_rows: int
) -> Tuple[List[str], List[Dict[str, Any]]]:
    """geometry 컬럼을 ST_AsGeoJSON 으로 감싼 SQL 을 실행해 (컬럼, 행) 을 돌려준다."""
    wrapped_sql = _build_geojson_wrapped_sql(clean_sql, column_metas)
    # 판정은 사용자 SQL 로 한다 — 래핑 CTE 안의 LIMIT 은 최상위가 아니라서
    # wrapped_sql 로 보면 항상 False 가 되어 주 경로와 규칙이 갈린다.
    # max_rows 는 호출부가 넘긴 「읽을 행 수」(상한+1, WD-454)다.
    if not _has_limit(clean_sql):
        wrapped_sql = _add_limit(wrapped_sql, max_rows)
    cursor.execute(wrapped_sql)
    columns = _column_names(cursor)
    raw_rows = cursor.fetchall()
    return columns, [dict(zip(columns, row)) for row in raw_rows]


# 사용자 SQL 자체의 "실행 중" 오류(#779). 데이터 예외(클래스 22 — 0 나누기·형변환 실패 등)와
# query_canceled(57014 — statement_timeout 포함)는 같은 SQL 을 다시 돌려도 같은 오류가 난다.
# geometry 래핑으로 재시도하면 사용자 SQL 이 한 번 더 실행될 뿐(timeout 이면 30초 추가 점유) 결과는 같다.
# 40(교착·직렬화)은 재시도로 풀릴 수 있어 넣지 않는다. 메시지가 아니라 SQLSTATE 로만 판정한다.
_RUNTIME_SQLSTATE_CLASSES = ("22",)
_RUNTIME_SQLSTATES = frozenset({"57014"})


def sqlstate(exc: BaseException) -> str | None:
    """psycopg2 예외의 SQLSTATE(pgcode). 서버가 준 오류가 아니면(파이썬 쪽 변환 오류 등) None.

    파이프라인 SQL 실행(sql_executor.pg_error_info, WD-111)도 같은 판별을 쓴다 — 공개 함수로 둔다.
    """
    code = getattr(exc, "pgcode", None)
    return code if isinstance(code, str) else None


# 시간 초과 판정의 허용 오차(초, WD-261 §5.2). 경과 시간은 실행기 쪽 시계로 재므로 서버의 문장 시작·취소 시각과 수 밀리초 어긋난다 —
# 그만큼 제한보다 조금 일찍 잡혀도 시간 초과로 본다. 0.5초는 계획에서 정한 값이다.
STATEMENT_TIMEOUT_TOLERANCE_SECONDS = 0.5


def is_statement_timeout(exc: BaseException, started_at: float, limit_seconds: float) -> bool:
    """우리가 건 statement_timeout 에 걸린 취소인가(WD-261 §5.2).

    57014(query_canceled) 이면서 **오류를 낸 그 문장**이 그 문장에 건 제한 − 허용 오차 이상 돌았으면 시간 초과다. 57014 는 관리자
    ``pg_cancel_backend`` 같은 다른 취소에서도 나고 PG 는 둘을 가르는 코드를 주지 않는다 — 예전처럼 원문의 영어 ``statement timeout``
    으로 가르면 서버 언어 설정(lc_messages)에 따라 판정이 바뀌므로 문구는 보지 않고 경과 시간만 본다. 제한보다 일찍 끝난 57014 는 관리자
    취소로 보고 호출부가 원문을 둔다. 남는 한계: 관리자가 정확히 제한 시각 근처에 취소하면 시간 초과로 보인다.

    ``started_at`` 은 그 문장을 보내기 직전의 ``time.monotonic()``, ``limit_seconds`` 는 그 문장에 실제로 건 제한(초)이다 — 적재는 페이지
    (문장)마다, 선행 문장은 그 문장에 건 남은 예산이다.
    """
    if sqlstate(exc) != "57014":
        return False
    return time.monotonic() - started_at >= limit_seconds - STATEMENT_TIMEOUT_TOLERANCE_SECONDS


class RunningStatement:
    """지금(또는 마지막으로 실패한) 제한 걸린 문장의 시작 시각·제한 — 57014 가 「그 문장의 시간 초과」인지 가르는 데 쓴다(WD-261 §5.2).

    문장을 보내기 직전에 시각을 적고, 성공하면 지운다. 그래서 예외가 났을 때 남아 있는 값은 오류를 낸 바로 그 문장의 것이고, 제한을 거는
    ``SET LOCAL`` 처럼 판정 대상이 아닌 문장에서 난 오류에는 값이 없다(시간 초과로 보지 않는다).

    제한을 거는 ``SET LOCAL`` 은 ``run``/``execute`` 밖에서 보낸다 — 그래야 ``SET LOCAL`` 자체의 오류가 시간 초과로 잡히지 않는다.
    ``/execute/sql``(선행 문장·본문)과 ``/execute/load``(페이지마다) 가 같이 쓴다 — 시각은 :func:`is_statement_timeout` 과 같은
    시계(이 모듈의 ``time.monotonic``)로 잰다.
    """

    def __init__(self) -> None:
        self.started_at: Optional[float] = None
        self.limit_seconds = 0.0

    def run(self, limit_seconds: float, statement: Callable[..., Any], *args: Any) -> None:
        """``statement(*args)`` 한 번이 문장 하나다 — 그 앞뒤로 시작 시각을 적고 지운다."""
        self.started_at = time.monotonic()
        self.limit_seconds = limit_seconds
        statement(*args)
        self.started_at = None

    def execute(self, cursor, limit_seconds: float, *args: Any) -> None:
        """``cursor.execute(*args)`` 를 문장 하나로 잰다."""
        self.run(limit_seconds, cursor.execute, *args)

    def timed_out(self, exc: BaseException) -> bool:
        return self.started_at is not None and is_statement_timeout(exc, self.started_at, self.limit_seconds)


def _is_user_sql_runtime_error(exc: BaseException) -> bool:
    """사용자 SQL 실행 중에 난 오류라서 geometry 래핑 재시도로 결과가 바뀌지 않는 경우 True(#779)."""
    code = sqlstate(exc)
    if code is None:
        return False
    return code in _RUNTIME_SQLSTATES or code[:2] in _RUNTIME_SQLSTATE_CLASSES


def _is_wrap_analysis_error(exc: BaseException) -> bool:
    """래핑 SQL 의 해석(계획) 단계 오류 — 클래스 42(함수 없음 42883·타입 불일치 42804/42846 등)면 True(#779).

    컬럼 단위 격리 시험은 LIMIT 0 이라 이런 해석 단계 오류만 가려낼 수 있다. 실행 중 오류나 SQLSTATE 가
    없는 오류에서 격리를 돌리면 모든 컬럼이 시험을 통과해 방금 실패한 같은 래핑 SQL 을 또 실행하게 된다.
    """
    code = sqlstate(exc)
    return code is not None and code[:2] == "42"


def _isolate_wrappable_columns(
    cursor, clean_sql: str, column_metas: List[Tuple[str, bool]]
) -> List[Tuple[str, bool]]:
    """geometry 컬럼마다 따로 래핑을 시험해(LIMIT 0) 감쌀 수 없는 컬럼만 래핑 대상에서 뺀다(#766).

    한 컬럼의 변환 실패가 같은 결과의 다른 geometry 컬럼까지 원본(WKB 16진수)으로 되돌리지 않게
    하려는 것이다. LIMIT 0 이라 실제 행은 읽지 않고 함수 해석(계획 단계) 오류만 걸러진다.
    호출 전후로 세이브포인트 analytics_query 가 살아 있어야 한다.
    """
    result: List[Tuple[str, bool]] = []
    for col_name, is_geom in column_metas:
        if not is_geom:
            result.append((col_name, False))
            continue
        only_this = [(c, c == col_name and g) for c, g in column_metas]
        probe = f"SELECT * FROM (\n{_build_geojson_wrapped_sql(clean_sql, only_this)}\n) _geom_probe LIMIT 0"
        try:
            cursor.execute(probe)
            result.append((col_name, True))
        except Exception as exc:
            logger.warning("geometry GeoJSON wrap failed for column %r, keeping raw: %s", col_name, exc)
            cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
            cursor.execute("SAVEPOINT analytics_query")
            result.append((col_name, False))
    return result


def _run_geojson_wrapped(
    cursor, clean_sql: str, column_metas: List[Tuple[str, bool]], max_rows: int
) -> Tuple[List[str], List[Dict[str, Any]]]:
    """GeoJSON 래핑 실행. 전체 래핑이 실패하면 컬럼 단위로 격리해 감쌀 수 있는 컬럼만 다시 감싼다(#766).

    감쌀 수 있는 geometry 컬럼이 하나도 남지 않거나 재시도도 실패하면 예외를 그대로 올린다 —
    호출부가 원본 결과 유지(성공 경로) 또는 원래 오류 보고(에러 경로)로 처리한다.

    격리는 래핑 때문에 생긴 해석 단계 오류(SQLSTATE 클래스 42)일 때만 한다(#779). 0 나누기·statement_timeout
    같은 실행 중 오류에서 격리하면 사용자 SQL 이 한 번 더 실행될 뿐이다. 격리로 빠진 컬럼이 없을 때도
    같은 래핑 SQL 을 다시 돌리지 않는다.
    """
    try:
        return _exec_geojson_wrapped(cursor, clean_sql, column_metas, max_rows)
    except Exception as exc:
        if not _is_wrap_analysis_error(exc):
            raise
        logger.warning("geometry GeoJSON wrap failed, isolating per column: %s", exc)
        cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
        cursor.execute("SAVEPOINT analytics_query")
        isolated = _isolate_wrappable_columns(cursor, clean_sql, column_metas)
        # 감쌀 컬럼이 없거나, 빠진 컬럼이 없어 방금 실패한 SQL 과 똑같다면 재실행하지 않는다.
        if not any(is_geom for _, is_geom in isolated) or isolated == column_metas:
            raise exc
        return _exec_geojson_wrapped(cursor, clean_sql, isolated, max_rows)



# 달러 인용 여는 태그: $$ 또는 $tag$ (tag 는 문자/밑줄로 시작). $1 같은 위치 파라미터는 인용이 아니다.
_DOLLAR_TAG = re.compile(r"\$(?:[A-Za-z_\x80-\U0010FFFF][A-Za-z0-9_\x80-\U0010FFFF]*)?\$")
# PostgreSQL 스캐너가 공백으로 보는 문자(scan.l 의 space). str.strip() 의 유니코드 공백(NBSP 등)은
# PostgreSQL 에선 식별자 문자라서 쓰지 않는다 — Java SqlLexicalMask 와 같은 집합이어야 결과가 같다(#746).
_PG_WHITESPACE = " \t\n\r\f\v"


def _is_ident_start(ch: str) -> bool:
    """PostgreSQL 식별자 첫 글자(ident_start): ASCII 글자·밑줄·0x80 이상 문자."""
    return ("a" <= ch <= "z") or ("A" <= ch <= "Z") or ch == "_" or ord(ch) >= 0x80


def _is_ident_cont(ch: str) -> bool:
    """PostgreSQL 식별자 이어지는 글자(ident_cont): ident_start + 숫자 + ``$``."""
    return _is_ident_start(ch) or ("0" <= ch <= "9") or ch == "$"


def _leading_keyword(sql: str) -> str | None:
    """SQL 맨 앞 키워드 후보 토큰(PostgreSQL 식별자 규칙, 원문 그대로). 식별자 첫 글자로 시작하지 않으면 None.

    짝 구현: Java ``SqlLexicalMask.leadingKeyword`` 참조(규칙 근거·공용 픽스처 ``sql-lexical-vectors.json`` 의 ``leading``).
    """
    if not sql or not _is_ident_start(sql[0]):
        return None
    end = 1
    while end < len(sql) and _is_ident_cont(sql[end]):
        end += 1
    return sql[:end]


# ASCII 소문자 → 대문자 변환표. str.upper() 는 ``ſ``(U+017F)를 ``S`` 로 접어, PostgreSQL 에선 식별자인
# ``ſelect`` 를 SELECT 로 오인하게 만든다 — 키워드 판정은 ASCII 만 대문자로 바꾼다(Java maskedUpper 와 짝).
_ASCII_UPPER = str.maketrans("abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
# Java removeTrailingSemicolon 과 같은 끝 세미콜론 패턴(Java \s 는 ASCII 공백만이라 re.ASCII 로 맞춘다).
# 운영 경로(_normalize_sql 을 거친 입력)에선 no-op 이고, 픽스처를 Java 와 같은 입력으로 대조하려고 둔다.
_TRAILING_SEMICOLON = re.compile(r";\s*$", re.ASCII)
# 데이터 수정 CTE 안에 올 수 있는 키워드(이슈 #227)와 WITH 뒤 본문 키워드(PostgreSQL 15+ 는 WITH … MERGE 도 받는다).
_CTE_INNER_DML_KEYWORDS = ("DELETE", "UPDATE", "INSERT", "MERGE", "TRUNCATE")
_CTE_BODY_KEYWORDS = ("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE")


def _first_keyword(sql: str) -> str | None:
    """SQL 첫 키워드를 ASCII 대문자로 돌려준다. 맨 앞 토큰이 없거나 비ASCII 문자가 섞여 있으면 None.

    PostgreSQL 키워드는 ASCII 뿐이라 ``ſelect``·``ınsert`` 는 키워드가 아니라 식별자다(WD-91 리뷰).
    **Java ``SqlValidationUtils.firstKeyword`` 와 짝이다.**
    """
    token = _leading_keyword(sql)
    if token is None or not token.isascii():
        return None
    return token.upper()


def _keyword_at(text: str, i: int, keywords: tuple[str, ...]) -> str | None:
    """``text[i]`` 에서 ``keywords`` 중 하나가 토큰 전체로 시작하면 그 키워드, 아니면 None.

    앞뒤가 식별자 글자(ident_cont)가 아니어야 한다 — ``SELECTED``·``X$DELETE``·``INSERTED_AT`` 은 PostgreSQL 에서
    식별자 하나라 키워드가 아니다. **Java ``SqlLexicalMask.keywordAt`` 와 짝이다.**
    """
    if i > 0 and _is_ident_cont(text[i - 1]):
        return None
    for kw in keywords:
        end = i + len(kw)
        if text.startswith(kw, i) and (end >= len(text) or not _is_ident_cont(text[end])):
            return kw
    return None


def _find_keyword_at_depth(masked_upper: str, top_level: bool, keywords: tuple[str, ...]) -> str | None:
    """괄호 깊이를 추적하며 ``keywords`` 중 처음 나오는 키워드 토큰. ``top_level`` 이면 깊이 0(본문), 아니면 깊이 1
    이상(CTE 정의 안)만 본다. 없으면 None. 짝 구현: Java ``SqlValidationUtils.findKeywordAtDepth``."""
    depth = 0
    for i, c in enumerate(masked_upper):
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif (depth == 0) if top_level else (depth >= 1):
            kw = _keyword_at(masked_upper, i, keywords)
            if kw is not None:
                return kw
    return None


def _detect_query_type(sql: str) -> str:
    """문 타입을 분류한다. **Java ``SqlValidationUtils.detectQueryType`` 와 같은 결과를 낸다.**

    비WITH 문은 첫 키워드(키워드가 없거나 비ASCII 면 ``UNKNOWN``). WITH 문은 주석·문자열·따옴표 식별자·달러
    인용을 가린 뒤(``_mask_sql``) CTE 안 데이터 수정 키워드 → 최상위 본문 키워드 순으로 토큰 단위로 찾는다.

    왜 executor 에도 두나(WD-91 리뷰): 예전엔 WITH 로 시작하면 무조건 SELECT 로 보아, api 를 거치지 않고 온
    ``WITH d AS (DELETE … RETURNING *) SELECT …`` 를 read_only 에서도 실행했다. 두 구현은 공용 픽스처
    ``query-type-vectors.json`` 으로 같은 분류를 검증한다 — 한쪽만 고치지 말 것.
    """
    without_semicolon = _TRAILING_SEMICOLON.sub("", sql)
    first_word = _first_keyword(without_semicolon)
    if first_word is None:
        return "UNKNOWN"
    if first_word == "WITH":
        masked = _mask_sql(without_semicolon).translate(_ASCII_UPPER)
        inner = _find_keyword_at_depth(masked, False, _CTE_INNER_DML_KEYWORDS)
        if inner is not None:
            return inner
        # 본문 키워드를 못 찾으면(괄호로 감싼 본문·VALUES·TABLE 등) SELECT 로 단정하지 않는다(fail-closed).
        return _find_keyword_at_depth(masked, True, _CTE_BODY_KEYWORDS) or "UNKNOWN"
    return first_word


def _mask_sql(sql: str) -> str:
    """주석을 공백으로, 리터럴(문자열·따옴표 식별자·달러 인용) 내용을 ``x`` 로 가린 같은 길이 문자열을 만든다.

    왜: 끝 주석·세미콜론 제거와 최상위 LIMIT 판정은 "진짜 SQL 코드"만 봐야 한다. ``'a;--b'`` 속 ``;``,
    ``$$ -- $$`` 속 ``--``, ``/* LIMIT 5 */`` 속 LIMIT 을 코드로 오인하면 LIMIT 이 주석에 묻히거나(#745)
    가짜 LIMIT 에 속아 max_rows 가 빠진다. 인덱스를 보존하므로 마스크에서 찾은 위치로 원문을 자를 수 있다.

    인지하는 PostgreSQL 어휘: ``--`` 줄 주석(``\n``/``\r`` 에서 끝), 중첩 가능한 ``/* */`` 블록 주석,
    ``'...'``(``''`` 이스케이프, ``E'...'`` 의 백슬래시 이스케이프), ``"..."`` 식별자(``""`` 이스케이프),
    ``$tag$...$tag$`` 달러 인용. 식별자는 한 덩어리로 건너뛴다 — ``a$$b`` 의 ``$`` 는 식별자 글자라 달러
    인용이 아니고, ``E'`` 는 식별자가 정확히 ``E`` 일 때만 이스케이프 문자열이다.
    닫히지 않은 리터럴/주석은 끝까지 그 상태로 본다(DB 가 어차피 문법 오류로 거부한다).

    **Java ``SqlLexicalMask.mask`` 와 짝이다(#746).** 두 구현은 공용 픽스처
    ``firehub-api/src/test/resources/fixtures/sql-lexical-vectors.json`` 으로 같은 결과를 검증한다 —
    한쪽 규칙만 고치지 말 것.
    """
    out = list(sql)
    n = len(sql)
    i = 0

    def mask_quoted(q: int, backslash_escapes: bool) -> int:
        """sql[q] 의 따옴표로 시작하는 리터럴 내용을 가리고 닫는 따옴표 다음 위치를 돌려준다."""
        c = sql[q]
        j = q + 1
        while j < n:
            if backslash_escapes and sql[j] == "\\":
                j += 2
                continue
            if sql[j] == c:
                if j + 1 < n and sql[j + 1] == c:  # '' / "" 이스케이프
                    j += 2
                    continue
                break
            j += 1
        for k in range(q + 1, min(j, n)):
            out[k] = "x"
        return j + 1

    while i < n:
        c = sql[i]
        nxt = sql[i + 1] if i + 1 < n else ""
        if c == "-" and nxt == "-":
            # 줄 주석: 개행(\n 또는 \r) 직전까지 가린다(개행 자체는 남긴다).
            j = i
            while j < n and sql[j] not in "\n\r":
                j += 1
            for k in range(i, j):
                out[k] = " "
            i = j
        elif c == "/" and nxt == "*":
            # 블록 주석: PostgreSQL 은 중첩을 허용하므로 깊이를 센다.
            depth = 1
            j = i + 2
            while j < n and depth > 0:
                if sql.startswith("/*", j):
                    depth += 1
                    j += 2
                elif sql.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            for k in range(i, min(j, n)):
                out[k] = " "
            i = j
        elif c == "'" or c == '"':
            i = mask_quoted(i, False)
        elif _is_ident_start(c):
            # 식별자/키워드 한 덩어리. 정확히 E 이고 바로 ' 가 오면 백슬래시 이스케이프 문자열이다.
            j = i + 1
            while j < n and _is_ident_cont(sql[j]):
                j += 1
            if j - i == 1 and c in "eE" and j < n and sql[j] == "'":
                i = mask_quoted(j, True)
            else:
                i = j
        elif c == "$":
            # 식별자 밖의 $ 만 여기 온다. $1 같은 위치 파라미터는 태그 모양이 아니라 그냥 지나간다.
            m = _DOLLAR_TAG.match(sql, i)
            if not m:
                i += 1
                continue
            tag = m.group(0)
            end = sql.find(tag, m.end())
            end = n if end < 0 else end + len(tag)
            for k in range(i, end):
                out[k] = "x"
            i = end
        else:
            i += 1
    return "".join(out)


def _normalize_sql(sql: str) -> str:
    """끝의 공백·주석·세미콜론을 (여러 개여도) 걷어낸 SQL 을 돌려준다. 주석만 있으면 빈 문자열.

    #745: 끝 주석을 남긴 채 ``LIMIT`` 을 덧붙이면 LIMIT 이 주석에 묻혀 max_rows 가 무시되고, 단순히
    개행을 넣어 붙이면 ``SELECT …; -- note`` 가 두 문장이 된다. 그래서 주석·리터럴을 인지해 **진짜 끝**
    (마지막 코드 문자)까지만 남긴다. 중간의 세미콜론(여러 문장)은 건드리지 않는다 — 다중 문장
    거부는 API 쪽 검증의 몫이다.

    **Java ``SqlLexicalMask.stripTrailingCommentsAndSemicolons`` 와 짝이다(#746)** — 공용 픽스처로 같은
    결과를 검증한다. 공백은 PostgreSQL 공백 집합(``_PG_WHITESPACE``)만 걷는다.
    """
    masked = _mask_sql(sql)
    end = len(masked.rstrip(_PG_WHITESPACE))
    while end > 0 and masked[end - 1] == ";":
        end = len(masked[: end - 1].rstrip(_PG_WHITESPACE))
    return sql[:end].lstrip(_PG_WHITESPACE)


def detect_statement_type(sql: str) -> str:
    """주석을 지우지 않은 SQL 한 문장의 문 타입(``_detect_query_type`` 과 같은 값)을 돌려준다 — 공개 헬퍼(WD-93).

    ``_detect_query_type`` 은 주석이 걷힌 입력을 가정한다(애널리틱스는 API 가 미리 걷어 보낸다). 파이프라인 SQL
    실행(``sql_executor``)은 사용자 원문을 그대로 받으므로 앞 주석은 어휘 인지로(마스크), 끝
    주석·세미콜론은 ``_normalize_sql`` 로 걷어낸 뒤 분류한다.
    """
    # 앞 주석: 주석을 지운 사본이 아니라 마스크 위 첫 코드 위치에서 원문을 자른다 — 문자열 속 ``--``·``/*`` 를 주석으로 오인하지 않는다.
    masked = _mask_sql(sql)
    start = len(masked) - len(masked.lstrip(_PG_WHITESPACE))
    return _detect_query_type(_normalize_sql(sql[start:]))


def _tokenize_masked(masked: str) -> list[tuple[str, int, int, int]]:
    """마스크 위의 토큰을 ``(대문자 텍스트, 시작, 끝, 괄호 깊이)`` 로 나눈다.

    식별자/키워드는 PostgreSQL 규칙(ident_start + ident_cont*, ``$`` 포함)으로 한 덩어리로 자른다 — 정규식
    ``\\b`` 는 ``a$limit``·``한limit`` 을 경계로 쪼개 가짜 LIMIT 을 만들고, Java 와 Python 의 ``\\b`` 정의도
    달라 두 구현이 갈린다(#749). ``(`` 는 여는 쪽 깊이, ``)`` 는 닫힌 뒤 깊이로 기록한다.
    **Java ``SqlLexicalMask.tokenize`` 와 짝이다.**
    """
    tokens: list[tuple[str, int, int, int]] = []
    n = len(masked)
    depth = 0
    i = 0
    while i < n:
        c = masked[i]
        if c in _PG_WHITESPACE:
            i += 1
        elif _is_ident_start(c) or ("0" <= c <= "9") or (c == "$" and i + 1 < n and "0" <= masked[i + 1] <= "9"):
            # 식별자·키워드·숫자(1.5e3 포함)·위치 파라미터($1)를 한 토큰으로.
            j = i + 1
            while j < n and (_is_ident_cont(masked[j]) or masked[j] == "."):
                j += 1
            # 대문자화는 ASCII 토큰만 — PostgreSQL 은 키워드 판정 때 ASCII 만 접는다. 유니코드 대문자화는
            # lımıt(점 없는 ı) 을 LIMIT 으로 만들어 가짜 제한에 속는다.
            word = masked[i:j]
            tokens.append((word.upper() if word.isascii() else word, i, j, depth))
            i = j
        elif c == "(":
            tokens.append((c, i, i + 1, depth))
            depth += 1
            i += 1
        elif c == ")":
            depth = max(0, depth - 1)
            tokens.append((c, i, i + 1, depth))
            i += 1
        else:
            tokens.append((c, i, i + 1, depth))
            i += 1
    return tokens


def _is_word_token(text: str) -> bool:
    """식별자·키워드·숫자·``$n`` 토큰인지(구두점 토큰이 아닌지)."""
    c = text[0]
    return _is_ident_start(c) or ("0" <= c <= "9") or c == "$"


def _is_fetch_clause(tokens: list[tuple[str, int, int, int]], k: int) -> bool:
    """``tokens[k]`` 의 FETCH 가 ``FETCH {FIRST|NEXT} [count] {ROW|ROWS} {ONLY|WITH TIES}`` 절인지.

    FETCH/FIRST/NEXT/ROWS 는 PostgreSQL 비예약어라 컬럼명·별칭이 될 수 있다(``SELECT fetch first FROM t``).
    절 모양 전체를 확인하지 않으면 가짜 FETCH 에 속아 max_rows 가 빠진다. count 는 단일 토큰(숫자·$n·
    식별자, 앞 부호 허용) 또는 괄호 묶음만 인정한다(PostgreSQL select_fetch_first_value 문법).
    """
    n = len(tokens)
    j = k + 1
    if j >= n or tokens[j][0] not in ("FIRST", "NEXT"):
        return False
    j += 1
    if j < n and tokens[j][0] not in ("ROW", "ROWS"):
        if tokens[j][0] in ("+", "-"):
            j += 1
        if j < n and tokens[j][0] == "(":
            open_depth = tokens[j][3]
            j += 1
            while j < n and not (tokens[j][0] == ")" and tokens[j][3] == open_depth):
                j += 1
            j += 1
        elif j < n and _is_word_token(tokens[j][0]):
            j += 1
        else:
            return False
    if j >= n or tokens[j][0] not in ("ROW", "ROWS"):
        return False
    j += 1
    if j < n and tokens[j][0] == "ONLY":
        return True
    return j + 1 < n and tokens[j][0] == "WITH" and tokens[j + 1][0] == "TIES"


def _top_level_row_limit(sql: str) -> tuple[str, int, int]:
    """최상위(괄호 깊이 0) 행 수 제한 상태를 ``(종류, 시작, 끝)`` 으로 돌려준다.

    - ``"limited"``: 사용자가 ``LIMIT <값>`` 또는 ``FETCH {FIRST|NEXT} … ROW(S) {ONLY|WITH TIES}`` 로 직접 제한.
    - ``"unlimited"``: ``LIMIT ALL``/``LIMIT NULL`` — "제한 없음"의 명시라 LIMIT 을 안 쓴 것과 같다.
      (시작, 끝)은 그 ``ALL``/``NULL`` 토큰 위치(원문 기준)다.
    - ``"none"``: 최상위 행 제한 없음.

    주석·리터럴은 마스크로 가리고 서브쿼리·CTE 속 절은 결과 행 수를 제한하지 않으므로 보지 않는다(#745/#750).
    LIMIT 은 PostgreSQL 예약어라 최상위에 나오면 항상 LIMIT 절이다.
    """
    tokens = _tokenize_masked(_mask_sql(sql))
    for k, (text, start, end, depth) in enumerate(tokens):
        if depth != 0:
            continue
        if text == "LIMIT":
            if k + 1 < len(tokens) and tokens[k + 1][0] in ("ALL", "NULL"):
                return ("unlimited", tokens[k + 1][1], tokens[k + 1][2])
            return ("limited", start, end)
        if text == "FETCH" and _is_fetch_clause(tokens, k):
            return ("limited", start, end)
    return ("none", -1, -1)


def _has_limit(sql: str) -> bool:
    """최상위에 사용자 행 제한(``LIMIT n`` 또는 ``FETCH FIRST|NEXT …``)이 있으면 True.

    주석·문자열 속 LIMIT 이나 서브쿼리 속 LIMIT 은 결과 행 수를 제한하지 않으므로 무시한다(#745). 사용자가
    최상위에 직접 쓴 제한은 존중한다(API 직접 실행 경로와 같은 규칙). ``LIMIT ALL``/``LIMIT NULL`` 은 제한이
    아니므로 False 다 — 그대로 두면 max_rows 보호가 빠진다(#749).
    **Java ``SqlLexicalMask.hasTopLevelRowLimit`` 와 짝이다** — 공용 픽스처 rowLimit 으로 같은 판정을 검증한다.
    """
    return _top_level_row_limit(sql)[0] == "limited"


def _apply_row_limit(sql: str, max_rows: int) -> str:
    """정규화된 SELECT 에 max_rows 행 제한을 적용한 SQL 을 돌려준다(#749).

    - 사용자 최상위 제한(``LIMIT n``·``FETCH FIRST n``)이 있으면 그대로 둔다(존중 규칙).
    - 최상위 ``LIMIT ALL``/``LIMIT NULL`` 이면 그 값 토큰만 max_rows 로 바꾼다 — 뒤에 LIMIT 을 또 붙이면
      구문 오류이고, "제한 없음"은 LIMIT 을 안 쓴 것과 같으므로 max_rows 를 적용한다. ``OFFSET`` 순서·주석은 보존된다.
    - 없으면 뒤에 LIMIT 을 덧붙인다.
    **Java ``SqlLexicalMask.applyRowLimit`` 와 짝이다.**
    """
    kind, start, end = _top_level_row_limit(sql)
    if kind == "limited":
        return sql
    if kind == "unlimited":
        return f"{sql[:start]}{max_rows}{sql[end:]}"
    return _add_limit(sql, max_rows)


def _add_limit(sql: str, max_rows: int) -> str:
    """정규화된(끝 주석·세미콜론이 없는) SQL 뒤에 LIMIT 을 붙인다.

    개행으로 띄우는 것은 방어적 이중 안전장치다 — 혹시 끝에 줄 주석이 남아 있더라도 LIMIT 은 다음 줄이라
    주석에 먹히지 않는다. 서브쿼리 래핑 대신 덧붙이기를 택한 이유: 입력이 이미 정규화돼 있어 충분하고,
    래핑은 ``SELECT … INTO``·서브쿼리 ORDER BY 해석 등 원문 의미를 바꿀 여지가 있다.
    """
    return f"{sql}\nLIMIT {max_rows}"


def execute_query(
    query: str, max_rows: int, read_only: bool, conn, *, tenant_id: int
) -> QueryExecuteResponse:
    """분석 쿼리를 실행한다. ``tenant_id`` 는 **키워드 전용 필수** 인자다.

    스키마명을 인자로 받지 않고 ``tenant_id`` 로 파생하는 이유: 스키마 문자열을 파라미터로
    받으면 "누가 그 값을 정했는가" 가 다시 흩어지고 조립점이 우회 가능한 장식이 된다
    (Java 쪽 ``DataSchema`` 와 같은 규약).
    """
    start = time.perf_counter()
    schema = resolve_schema(tenant_id)

    # 1. Validate
    # 끝의 주석·세미콜론을 주석/리터럴 인지로 걷어낸다(#745). 이후 모든 소비처(LIMIT 부착, geometry
    # 감지·래핑, DML 실행)가 같은 정규화 SQL 을 쓴다 — 그러지 않으면 래핑 괄호 안에 ; 가 남는다.
    clean_sql = _normalize_sql(query)
    if not clean_sql:
        return QueryExecuteResponse(
            success=False,
            error="Query must not be empty",
            db_error=db_error_of("Query must not be empty"),
            execution_time_ms=0,
        )

    # 2. Detect query type
    # api SqlValidationUtils.detectQueryType 과 같은 규칙으로 분류한다(WD-91) — 첫 키워드는 PostgreSQL 어휘
    # 규칙으로, WITH 문은 CTE 안 DML·본문 키워드까지 본다. 응답의 query_type 은 네 가지 외엔 UNKNOWN 이다.
    detected = _detect_query_type(clean_sql)
    query_type = detected if detected in ("SELECT", "INSERT", "UPDATE", "DELETE") else "UNKNOWN"

    # 3. read_only check
    is_select = query_type == "SELECT"
    if read_only and not is_select:
        return QueryExecuteResponse(
            success=False,
            query_type=query_type,
            error=f"read_only mode does not allow {detected} statements",
            db_error=db_error_of(f"read_only mode does not allow {detected} statements"),
            execution_time_ms=int((time.perf_counter() - start) * 1000),
        )

    cursor = conn.cursor()

    try:
        # 4. Transaction setup
        # 읽기 전용 요청은 트랜잭션 자체를 READ ONLY 로 연다(WD-91 리뷰) — 위 분류가 어떤 형태를 놓쳐도 DB 가
        # 쓰기(데이터 수정 CTE·쓰기 함수·nextval 등)를 거부하는 2차 방어선이다. READ ONLY 전환은 트랜잭션 중간에도
        # 허용되고, 커넥션 반납 시 롤백으로 트랜잭션이 끝나 다음 요청에는 남지 않는다.
        # 스키마명은 테넌트에서 파생한다(하드코딩 'data' 제거). public 은 PostGIS 함수 때문에 유지.
        # 보간이 안전한 이유는 resolve_schema 가 _require_tenant_id 로 정규화한 값만 반환하기
        # 때문이다(사용자 입력이 아니라 리터럴+테넌트 id 에서만 파생된다) — 식별자 모양 검증
        # (_SAFE_IDENTIFIER)은 그 위의 휴면 방어선이다(tenant.py 참조).
        # 파라미터 없는 설정 문장들은 한 번의 execute 로 보내 왕복을 줄인다(순서 유지 — READ ONLY 가 첫 문장).
        setup = [
            f"SET LOCAL search_path = '{schema}', 'public'",
            "SET LOCAL statement_timeout = '30s'",
            "SAVEPOINT analytics_query",
        ]
        if read_only:
            setup.insert(0, "SET TRANSACTION READ ONLY")
        cursor.execute("; ".join(setup))

        if is_select:
            # 최상위 행 제한이 없거나 LIMIT ALL 이면 max_rows 를 적용한다(#749 — FETCH FIRST 도 사용자 제한).
            # 서버가 상한을 붙일 때는 한 행 더(max_rows+1) 읽어 「상한을 넘는 결과가 있었는지」를 정확히 판정한다(WD-454) —
            # 예전엔 정확히 max_rows 행인 결과도 잘린 것으로 표시했다. 사용자가 최상위 LIMIT/FETCH FIRST 를 직접 썼으면
            # (#749 존중) SQL 은 그대로 실행되고 아래에서 자르지도, 잘림으로 표시하지도 않는다.
            user_row_limit = _has_limit(clean_sql)
            fetch_limit = max_rows + 1
            sql_to_run = _apply_row_limit(clean_sql, fetch_limit)

            columns: List[str] = []
            rows: List[Dict[str, Any]] = []
            original_error = None

            try:
                cursor.execute(sql_to_run)
                # 행을 이름 키 dict 로 담으므로 같은 이름 컬럼은 고유하게 바꾼다 — 안 그러면 앞 값이 덮인다(WD-85)
                columns = _column_names(cursor)
                raw_rows = cursor.fetchall()
                rows = [dict(zip(columns, row)) for row in raw_rows]
            except Exception as exc:
                original_error = exc
                # 사용자 SQL 자체의 실행 중 오류(0 나누기·statement_timeout 등)는 래핑해 다시 돌려도 같은 오류다(#779).
                # 재시도 없이 그대로 올려 사용자 SQL 을 한 번만 실행한다 — 바깥 except 가 세이브포인트를
                # 되돌리고 같은 str(exc) 로 오류 응답을 만든다(재시도 후 raise original_error 와 같은 메시지).
                if _is_user_sql_runtime_error(exc):
                    raise
                # Rollback to savepoint and retry with geometry wrapping
                cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
                cursor.execute("SAVEPOINT analytics_query")

                try:
                    column_metas = _detect_geometry_columns(cursor, clean_sql)
                    has_geom = any(is_geom for _, is_geom in column_metas)

                    if has_geom:
                        columns, rows = _run_geojson_wrapped(cursor, clean_sql, column_metas, fetch_limit)
                        original_error = None
                    else:
                        raise original_error
                except Exception:
                    raise original_error

            # OID 기반 geometry 컬럼 감지 (성공 경로).
            # 값이 아니라 컬럼의 선언된 타입 OID 로 판정 → 숫자 텍스트 오탐 없음.
            if rows and original_error is None and cursor.description:
                desc_snapshot = list(cursor.description)
                geom_oids = _fetch_geom_oids(cursor.connection)
                # 이름은 위에서 고유하게 만든 columns 를 쓴다 — 래핑 CTE 별칭·컬럼 격리가 이름으로 구분한다(WD-85)
                column_metas = [
                    (name, desc[1] in geom_oids) for name, desc in zip(columns, desc_snapshot)
                ]
                if any(is_geom for _, is_geom in column_metas):
                    orig_columns, orig_rows = columns, rows
                    try:
                        cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
                        cursor.execute("SAVEPOINT analytics_query")
                        # 컬럼 단위 격리 포함(#766) — 한 컬럼 실패가 다른 geometry 컬럼까지 raw 로 되돌리지 않는다.
                        columns, rows = _run_geojson_wrapped(cursor, clean_sql, column_metas, fetch_limit)
                    except Exception as exc:
                        # 방어적 폴백: GeoJSON 변환 실패 시 원본 성공 결과를 유지한다
                        # (연결이 정상일 때. 연결 사망 등으로 아래 rollback 도 실패하면
                        #  바깥 except 가 잡아 에러 응답으로 끝난다).
                        # 침묵 강등을 관측 가능하게 로깅한다.
                        logger.warning(
                            "geometry GeoJSON wrap failed, falling back to raw result: %s",
                            exc,
                        )
                        cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
                        cursor.execute("SAVEPOINT analytics_query")
                        columns, rows = orig_columns, orig_rows

            # 서버 상한을 붙였으면 판정용으로 한 행 더 읽었으므로 상한을 넘었을 때만 잘림이고, 응답에는 max_rows 행까지만
            # 싣는다(WD-454). 사용자 제한이면 사용자가 요청한 행을 그대로 돌려주고 잘림이 아니다(서버가 자른 것이 아님).
            # GeoJSON 재실행이 rows 를 바꿀 수 있으므로 판정은 최종 rows 로 한 번만 한다.
            truncated = not user_row_limit and len(rows) > max_rows
            if truncated:
                rows = rows[:max_rows]
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
            db_error=db_error_of(str(exc), sqlstate(exc)),
            execution_time_ms=elapsed_ms,
        )
    finally:
        try:
            cursor.execute(f"SET LOCAL search_path TO {schema}")
        except Exception:
            pass
        cursor.close()


# ---------------------------------------------------------------------------
# 슬롯 스트리밍 조회(WD-78) — db-executor 와 같은 NDJSON 계약(executor-db-contract-vectors.json 의 query.streaming)
# ---------------------------------------------------------------------------

NDJSON_MEDIA_TYPE = "application/x-ndjson"

# 서버 측 커서가 한 번에 가져오는 행 수 — 큰 테이블을 메모리에 모으지 않고 나눠 받는다.
STREAM_FETCH_SIZE = 1000

# 슬롯 스트리밍의 문장 시간 제한. PG statement_timeout 은 문장(FETCH 하나) 단위라 전체 기한이 아니다(Ruling 5 와 같은 차이) —
# db-executor 는 스크립트 토큰 만료까지를 전체 기한으로 쓴다. 긴 작업 상한(db-executor app.load.timeout-seconds)과 같은 값.
STREAM_STATEMENT_TIMEOUT = "1800s"

STREAM_QUERY_ONLY = "슬롯 조회는 조회(SELECT·WITH)만 실행합니다."

# 스트리밍은 db-executor 처럼 read_only:true 를 요구한다 — 같은 문구(db-executor ExecuteController.READ_ONLY_REQUIRED).
STREAM_READ_ONLY_REQUIRED = "읽기 전용 조회만 실행할 수 있습니다."

# 슬롯 롤 접속 실패 고정 문구 — libpq 원문에는 호스트·롤 이름이 들어 있어 응답에 싣지 않는다(db-executor CONNECT_FAILED 와 같은 취지).
STREAM_CONNECT_FAILED = "슬롯 롤로 데이터베이스에 연결하지 못했습니다."

# 슬롯 롤 접속 대기 상한(초) — 닿지 않는 DB 에서 무한정 기다리지 않게.
STREAM_CONNECT_TIMEOUT_SECONDS = 10

# 한 줄 직렬화기 — 비스트리밍 응답(QueryExecuteResponse)과 같은 pydantic 직렬화를 써서 값 표기(Decimal·날짜 등)를 맞춘다.
_LINE = TypeAdapter(Dict[str, Any])


def _line(obj: Dict[str, Any]) -> bytes:
    """JSON 한 줄 + 줄바꿈."""
    return _LINE.dump_json(obj) + b"\n"


def stream_failure_line(error: str, db_error: Optional[DbErrorInfo] = None) -> bytes:
    """실패 마지막 줄 ``{"success":false,"error":...,"db_error":{...}}``. db_error 는 기존 응답처럼 실패마다 채운다."""
    info = db_error if db_error is not None else db_error_of(error)
    return _line({"success": False, "error": error, "db_error": info.model_dump()})


def stream_slot_query(query: str, *, tenant_id: int, read_slot: int, settings: "Settings") -> Iterator[bytes]:
    """슬롯 롤로 조회를 끝까지 읽어 NDJSON 줄을 하나씩 낸다(행 상한 없음).

    - 첫 줄 ``{"columns":[...]}`` (이름은 분석 조회와 같은 고유화), 행마다 ``{"row":{...}}``, 마지막 줄
      ``{"success":true,"row_count":N}`` 또는 실패 줄. 실행 전 거부(문장 검사·접속 실패)는 실패 줄 하나만 낸다.
    - 접속은 테넌트 실행 롤 풀이 아니라 **슬롯 롤 직접 로그인**(``resolve_read_db_url``)이다 — 등급 밖 테이블은 DB 권한이 거부한다.
      트랜잭션은 READ ONLY, search_path 는 테넌트 스키마. 서버 측(named) 커서로 ``STREAM_FETCH_SIZE`` 행씩 가져온다.
    - named 커서는 첫 fetch 뒤에야 ``description`` 이 생기므로 컬럼 줄은 첫 묶음을 받은 뒤 낸다(빈 결과도 컬럼 줄이 나간다).
    - 받는 쪽이 끊으면(제너레이터 close → GeneratorExit) finally 에서 커서를 닫고 롤백한 뒤 연결을 닫는다.
    - geometry 를 GeoJSON 으로 감싸는 분석 조회의 변환은 하지 않는다(스크립트 읽기용 원값).
    """
    clean_sql = _normalize_sql(query)
    if not clean_sql:
        yield stream_failure_line("Query must not be empty")
        return
    # 분석 조회와 같은 분류(WD-91) — CTE 안 DML 도 SELECT 가 아니다. 슬롯 롤 권한·READ ONLY 트랜잭션이 2차 방어선이다.
    if _detect_query_type(clean_sql) != "SELECT":
        yield stream_failure_line(STREAM_QUERY_ONLY)
        return
    schema = resolve_schema(tenant_id)
    conn = None
    cursor = None
    try:
        conn = psycopg2.connect(
            resolve_read_db_url(tenant_id, read_slot, settings),
            connect_timeout=STREAM_CONNECT_TIMEOUT_SECONDS,
        )
    except Exception as exc:  # noqa: BLE001 — 접속 실패는 고정 문구로(원문은 로그에 종류만)
        logger.warning("슬롯 롤 접속 실패 tenant=%s slot=%s 종류=%s", tenant_id, read_slot, type(exc).__name__)
        yield stream_failure_line(STREAM_CONNECT_FAILED)
        return
    try:
        conn.set_session(readonly=True)
        with conn.cursor() as setup:
            # 보간이 안전한 이유는 execute_query 와 같다(resolve_schema 는 테넌트 id 에서만 파생).
            setup.execute(
                f"SET LOCAL search_path = '{schema}', 'public'; "
                f"SET LOCAL statement_timeout = '{STREAM_STATEMENT_TIMEOUT}'"
            )
        cursor = conn.cursor(name="slot_stream")
        cursor.itersize = STREAM_FETCH_SIZE
        cursor.execute(clean_sql)
        batch = cursor.fetchmany(STREAM_FETCH_SIZE)
        columns = _column_names(cursor)
        yield _line({"columns": columns})
        count = 0
        while batch:
            for row in batch:
                yield _line({"row": {name: to_json_value(value) for name, value in zip(columns, row)}})
                count += 1
            batch = cursor.fetchmany(STREAM_FETCH_SIZE)
        yield _line({"success": True, "row_count": count})
    except Exception as exc:  # noqa: BLE001 — 모든 실패를 마지막 실패 줄로 알린다(받는 쪽은 마지막 줄로 판정)
        yield stream_failure_line(str(exc), db_error_of(str(exc), sqlstate(exc)))
    finally:
        for close in (
            (lambda: cursor.close()) if cursor is not None else None,
            (lambda: conn.rollback()) if conn is not None else None,
            (lambda: conn.close()) if conn is not None else None,
        ):
            if close is None:
                continue
            try:
                close()
            except Exception:  # noqa: BLE001 — 정리 실패는 응답에 영향 주지 않는다
                pass
