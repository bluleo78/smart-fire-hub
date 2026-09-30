from __future__ import annotations

import logging
import re
import time
from typing import Any, Dict, List, Tuple

from app.schemas.responses import QueryExecuteResponse
from app.tenant import resolve_schema

logger = logging.getLogger(__name__)


def _fetch_geom_oids(conn) -> set[int]:
    """geometry/geography 타입의 OID 집합을 pg_type 에서 조회한다.

    성공 경로와 에러 fallback 경로가 공유한다. 값이 아니라 컬럼의 선언된
    타입 OID로 geometry 여부를 판정하기 위한 근거를 제공한다.
    """
    cur = conn.cursor()
    cur.execute("SELECT oid FROM pg_type WHERE typname IN ('geometry', 'geography')")
    oids = {row[0] for row in cur.fetchall()}
    cur.close()
    return oids


def _detect_geometry_columns(cursor, sql: str) -> List[Tuple[str, bool]]:
    """Detect column names and whether they are geometry via LIMIT 0 + pg_type lookup."""
    conn = cursor.connection
    # meta_cursor 를 먼저 연다 (커서 오픈 순서 보존).
    meta_cursor = conn.cursor()
    # 사용자 SQL 을 자기 줄에 얹는다(#741) — 끝이 한 줄 주석(--)이면 개행 없이 붙인 닫는 괄호까지
    # 주석이 되어 syntax error at end of input 이 난다. API 의 SqlColumnProbe·MergeSqlBuilder 와 같은 형태.
    meta_cursor.execute(f"SELECT * FROM (\n{sql}\n) _geom_detect LIMIT 0")

    geom_oids = _fetch_geom_oids(conn)

    columns: List[Tuple[str, bool]] = []
    for desc in meta_cursor.description or []:
        col_name = desc[0]
        type_oid = desc[1]  # type_code in psycopg2
        columns.append((col_name, type_oid in geom_oids))

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
    # 사용자 SQL 을 자기 줄에 얹는다(#741) — 위 _detect_geometry_columns 와 같은 이유.
    return f"WITH _src AS (\n{original_sql}\n) SELECT {', '.join(select_parts)} FROM _src"



# 달러 인용 여는 태그: $$ 또는 $tag$ (tag 는 문자/밑줄로 시작). $1 같은 위치 파라미터는 인용이 아니다.
_DOLLAR_TAG = re.compile(r"\$(?:[A-Za-z_\x80-\U0010FFFF][A-Za-z0-9_\x80-\U0010FFFF]*)?\$")
# 마스크 위에서 찾는 최상위 LIMIT n (주석·리터럴은 이미 가려져 있다).
_LIMIT_CLAUSE = re.compile(r"(?i)\bLIMIT\s+\d+")


def _mask_sql(sql: str) -> str:
    """주석을 공백으로, 리터럴(문자열·따옴표 식별자·달러 인용) 내용을 ``x`` 로 가린 같은 길이 문자열을 만든다.

    왜: 끝 주석·세미콜론 제거와 최상위 LIMIT 판정은 "진짜 SQL 코드"만 봐야 한다. ``'a;--b'`` 속 ``;``,
    ``$$ -- $$`` 속 ``--``, ``/* LIMIT 5 */`` 속 LIMIT 을 코드로 오인하면 LIMIT 이 주석에 묻히거나(#745)
    가짜 LIMIT 에 속아 max_rows 가 빠진다. 인덱스를 보존하므로 마스크에서 찾은 위치로 원문을 자를 수 있다.

    인지하는 PostgreSQL 어휘: ``--`` 줄 주석, 중첩 가능한 ``/* */`` 블록 주석, ``'...'``(``''`` 이스케이프,
    ``E'...'`` 의 백슬래시 이스케이프), ``"..."`` 식별자(``""`` 이스케이프), ``$tag$...$tag$`` 달러 인용(#746).
    닫히지 않은 리터럴/주석은 끝까지 그 상태로 본다(DB 가 어차피 문법 오류로 거부한다).
    """
    out = list(sql)
    n = len(sql)
    i = 0
    while i < n:
        c = sql[i]
        nxt = sql[i + 1] if i + 1 < n else ""
        if c == "-" and nxt == "-":
            # 줄 주석: 개행 직전까지 가린다(개행 자체는 남긴다).
            j = sql.find("\n", i)
            j = n if j < 0 else j
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
            for k in range(i, j):
                out[k] = " "
            i = j
        elif c == "'" or c == '"':
            # E'...' 는 백슬래시 이스케이프를 쓴다(표준 문자열은 '' 만).
            backslash_escapes = (
                c == "'"
                and i > 0
                and sql[i - 1] in "eE"
                and (i < 2 or not (sql[i - 2].isalnum() or sql[i - 2] == "_"))
            )
            j = i + 1
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
            for k in range(i + 1, min(j, n)):
                out[k] = "x"
            i = j + 1
        elif c == "$" and not (i > 0 and (sql[i - 1].isalnum() or sql[i - 1] == "_")):
            # 식별자 중간의 $ (예: a$b) 는 달러 인용이 아니다.
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
    (마지막 코드 문자)까지만 남긴다. API 의 ``MergeSqlBuilder.stripTrailingSemicolon`` 과 같은 목적이되
    블록 주석·달러 인용까지 인지한다(#746). 중간의 세미콜론(여러 문장)은 건드리지 않는다 — 다중 문장
    거부는 API 쪽 검증의 몫이다.
    """
    masked = _mask_sql(sql)
    end = len(masked.rstrip())
    while end > 0 and masked[end - 1] == ";":
        end = len(masked[: end - 1].rstrip())
    return sql[:end].lstrip()


def _has_limit(sql: str) -> bool:
    """괄호 깊이 0(최상위)에 ``LIMIT n`` 이 있으면 True.

    주석·문자열 속 LIMIT 이나 서브쿼리 속 LIMIT 은 결과 행 수를 제한하지 않으므로 무시한다 — 예전 정규식은
    ``-- LIMIT 5``/``(SELECT … LIMIT 1)`` 에 속아 max_rows LIMIT 을 빼먹었다(#745). 사용자가 최상위에 직접
    쓴 LIMIT 은 지금처럼 존중한다(API 직접 실행 경로와 같은 규칙).
    """
    masked = _mask_sql(sql)
    depth_at: list[int] = []
    depth = 0
    for ch in masked:
        if ch == "(":
            depth += 1
        depth_at.append(depth)
        if ch == ")":
            depth = max(0, depth - 1)
    return any(depth_at[m.start()] == 0 for m in _LIMIT_CLAUSE.finditer(masked))


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
            execution_time_ms=0,
        )

    # 2. Detect query type
    first_word = clean_sql.upper().split()[0]
    if first_word == "WITH":
        query_type = "SELECT"
    elif first_word in ("SELECT", "INSERT", "UPDATE", "DELETE"):
        query_type = first_word
    else:
        query_type = "UNKNOWN"

    # 3. read_only check
    is_select = query_type == "SELECT"
    if read_only and not is_select:
        return QueryExecuteResponse(
            success=False,
            query_type=query_type,
            error=f"read_only mode does not allow {first_word} statements",
            execution_time_ms=int((time.perf_counter() - start) * 1000),
        )

    cursor = conn.cursor()

    try:
        # 4. Transaction setup
        # 스키마명은 테넌트에서 파생한다(하드코딩 'data' 제거). public 은 PostGIS 함수 때문에 유지.
        # 보간이 안전한 이유는 resolve_schema 가 _require_tenant_id 로 정규화한 값만 반환하기
        # 때문이다(사용자 입력이 아니라 리터럴+테넌트 id 에서만 파생된다) — 식별자 모양 검증
        # (_SAFE_IDENTIFIER)은 그 위의 휴면 방어선이다(tenant.py 참조).
        cursor.execute(f"SET LOCAL search_path = '{schema}', 'public'")
        cursor.execute("SET LOCAL statement_timeout = '30s'")
        cursor.execute("SAVEPOINT analytics_query")

        if is_select:
            # Add LIMIT if not present
            sql_to_run = clean_sql if _has_limit(clean_sql) else _add_limit(clean_sql, max_rows)

            columns: List[str] = []
            rows: List[Dict[str, Any]] = []
            original_error = None

            try:
                cursor.execute(sql_to_run)
                columns = [desc[0] for desc in cursor.description] if cursor.description else []
                raw_rows = cursor.fetchall()
                rows = [dict(zip(columns, row)) for row in raw_rows]
            except Exception as exc:
                original_error = exc
                # Rollback to savepoint and retry with geometry wrapping
                cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
                cursor.execute("SAVEPOINT analytics_query")

                try:
                    column_metas = _detect_geometry_columns(cursor, clean_sql)
                    has_geom = any(is_geom for _, is_geom in column_metas)

                    if has_geom:
                        wrapped_sql = _build_geojson_wrapped_sql(clean_sql, column_metas)
                        # 판정은 사용자 SQL 로 한다 — 래핑 CTE 안의 LIMIT 은 최상위가 아니라서
                        # wrapped_sql 로 보면 항상 False 가 되어 주 경로와 규칙이 갈린다.
                        if not _has_limit(clean_sql):
                            wrapped_sql = _add_limit(wrapped_sql, max_rows)
                        cursor.execute(wrapped_sql)
                        columns = [desc[0] for desc in cursor.description] if cursor.description else []
                        raw_rows = cursor.fetchall()
                        rows = [dict(zip(columns, row)) for row in raw_rows]
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
                column_metas = [
                    (desc[0], desc[1] in geom_oids) for desc in desc_snapshot
                ]
                if any(is_geom for _, is_geom in column_metas):
                    orig_columns, orig_rows = columns, rows
                    try:
                        wrapped_sql = _build_geojson_wrapped_sql(clean_sql, column_metas)
                        # 판정은 사용자 SQL 로 한다 — 래핑 CTE 안의 LIMIT 은 최상위가 아니라서
                        # wrapped_sql 로 보면 항상 False 가 되어 주 경로와 규칙이 갈린다.
                        if not _has_limit(clean_sql):
                            wrapped_sql = _add_limit(wrapped_sql, max_rows)
                        cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
                        cursor.execute("SAVEPOINT analytics_query")
                        cursor.execute(wrapped_sql)
                        columns = [d[0] for d in cursor.description] if cursor.description else []
                        raw_rows = cursor.fetchall()
                        rows = [dict(zip(columns, row)) for row in raw_rows]
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

            truncated = len(rows) >= max_rows
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
            execution_time_ms=elapsed_ms,
        )
    finally:
        try:
            cursor.execute(f"SET LOCAL search_path TO {schema}")
        except Exception:
            pass
        cursor.close()
