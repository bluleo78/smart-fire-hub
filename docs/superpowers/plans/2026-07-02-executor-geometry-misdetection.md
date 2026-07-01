# Executor geometry 오탐 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** executor가 값 기반 hex 휴리스틱으로 숫자 텍스트 컬럼(예: `report_date="20260131235609"`)을 geometry로 오판해 `ST_AsGeoJSON` 래핑 재실행이 실패하고 전체 쿼리가 에러로 끝나는 버그를 제거한다.

**Architecture:** 성공한 SELECT의 geometry 판정을 값(hex) 휴리스틱에서 **컬럼 타입 OID 기반**으로 교체한다. `pg_type`의 geometry/geography OID를 조회하는 로직을 `_fetch_geom_oids` 헬퍼로 추출해 성공 경로와 기존 에러-fallback 경로가 공유한다. 진짜 geometry 컬럼은 여전히 GeoJSON으로 변환하되, 변환(재실행)이 실패하면 원본 성공 결과를 그대로 반환하는 방어적 폴백을 둔다.

**Tech Stack:** Python 3, FastAPI, psycopg2 (동기, `ThreadedConnectionPool`), pytest + `unittest.mock`.

## Global Constraints

- 대상 파일 단일: `apps/firehub-executor/app/services/query_executor.py` (+ 테스트 `apps/firehub-executor/tests/test_query_executor.py`).
- geometry 판정은 **값이 아니라 컬럼 타입 OID**로만 한다 (`cursor.description[i][1]` ∈ geometry/geography OID 집합).
- 진짜 geometry 컬럼의 `ST_AsGeoJSON` GeoJSON 변환 기능은 **보존**한다.
- 테스트는 실 DB 없이 `MagicMock` 커서를 쓴다. `description` 항목 형식은 `(name, type_oid, None, None, None, None, None)` (index 1 = 타입 OID).
- `_fetch_geom_oids`는 `cursor.connection`(= 연결 객체)을 인자로 받아 그 위에서 새 커서로 `pg_type`를 조회한다. 성공 경로는 `_fetch_geom_oids(cursor.connection)`으로 호출한다.
- `_detect_geometry_columns` 내부에서는 **LIMIT 0 meta_cursor를 먼저 열고 그 다음 `_fetch_geom_oids`를 호출**한다 (커서 오픈 순서 보존 — 기존 `test_geometry_detection_via_limit0` 목이 이 순서에 의존).
- 범위 밖: `report_date`의 `CAST(... AS DATE)` 실패(YYYYMMDDHHMMSS 텍스트 포맷) 이슈는 건드리지 않는다.
- 커밋/배포는 사용자 승인 후. 배포 시 `./scripts/deploy.sh executor` (`.claude/docs/deploy.md` 참조).

---

## File Structure

- `apps/firehub-executor/app/services/query_executor.py` — 수정
  - 추가: `_fetch_geom_oids(conn) -> set`
  - 수정: `_detect_geometry_columns` (OID 조회를 헬퍼로 위임, 순서 보존)
  - 삭제: `_detect_geometry_in_rows`
  - 수정: `execute_query`의 성공-후 geometry 재스캔 블록(값 휴리스틱 → OID 기반 + 방어적 폴백)
- `apps/firehub-executor/tests/test_query_executor.py` — 수정
  - 추가: `_fetch_geom_oids` 단위 테스트, 회귀 테스트, OID 래핑 테스트, 방어적 폴백 테스트
  - 삭제: `_detect_geometry_in_rows` 관련 테스트 3종 + import

---

### Task 1: `_fetch_geom_oids` 헬퍼 추출 + `_detect_geometry_columns` 리팩터

geometry OID 조회를 헬퍼로 분리한다. 순수 리팩터 — 외부 동작 불변. 커서 오픈 순서를 보존해 기존 에러-fallback 테스트가 그대로 통과해야 한다.

**Files:**
- Modify: `apps/firehub-executor/app/services/query_executor.py:10-31`
- Test: `apps/firehub-executor/tests/test_query_executor.py`

**Interfaces:**
- Produces: `_fetch_geom_oids(conn) -> set[int]` — 인자 `conn`의 새 커서로 `pg_type`에서 geometry/geography OID 집합을 반환.
- Produces: `_detect_geometry_columns(cursor, sql) -> List[Tuple[str, bool]]` — 시그니처 불변, 내부만 헬퍼 사용.

- [ ] **Step 1: `_fetch_geom_oids` 단위 테스트 작성**

`apps/firehub-executor/tests/test_query_executor.py`의 헬퍼 유닛 테스트 섹션(파일 하단, `test_build_geojson_wrapped_sql` 근처)에 추가:

```python
def test_fetch_geom_oids_returns_pg_type_oids():
    """pg_type 조회 결과의 OID 집합을 반환한다."""
    from app.services.query_executor import _fetch_geom_oids

    cur = MagicMock()
    cur.fetchall.return_value = [(16000,), (16001,)]
    conn = MagicMock()
    conn.cursor.return_value = cur

    oids = _fetch_geom_oids(conn)

    assert oids == {16000, 16001}
    assert any("pg_type" in c.args[0] for c in cur.execute.call_args_list)
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd apps/firehub-executor && pytest tests/test_query_executor.py::test_fetch_geom_oids_returns_pg_type_oids -v`
Expected: FAIL — `ImportError: cannot import name '_fetch_geom_oids'`

- [ ] **Step 3: `_fetch_geom_oids` 구현 + `_detect_geometry_columns` 리팩터**

`query_executor.py`의 `_detect_geometry_columns`(현재 `:10-31`)를 아래로 교체하고, 그 위에 `_fetch_geom_oids`를 추가:

```python
def _fetch_geom_oids(conn) -> set:
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
    meta_cursor.execute(f"SELECT * FROM ({sql}) _geom_detect LIMIT 0")

    geom_oids = _fetch_geom_oids(conn)

    columns: List[Tuple[str, bool]] = []
    for desc in meta_cursor.description or []:
        col_name = desc[0]
        type_oid = desc[1]  # type_code in psycopg2
        columns.append((col_name, type_oid in geom_oids))

    meta_cursor.close()
    return columns
```

- [ ] **Step 4: 헬퍼 테스트 + 기존 geometry 테스트 통과 확인**

Run: `cd apps/firehub-executor && pytest tests/test_query_executor.py::test_fetch_geom_oids_returns_pg_type_oids tests/test_query_executor.py::test_geometry_detection_via_limit0 -v`
Expected: PASS (둘 다). `test_geometry_detection_via_limit0`는 커서 오픈 순서(meta_cursor→pg_type) 보존으로 그대로 통과.

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-executor/app/services/query_executor.py apps/firehub-executor/tests/test_query_executor.py
git commit -m "refactor(executor): geometry OID 조회를 _fetch_geom_oids 헬퍼로 추출

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: 성공 경로 OID 판정 + 방어적 폴백 (값 휴리스틱 제거)

성공한 SELECT의 geometry 재스캔을 값 hex 휴리스틱에서 OID 기반으로 교체하고, `ST_AsGeoJSON` 재실행 실패 시 원본 성공 결과를 반환하는 폴백을 추가한다. `_detect_geometry_in_rows`와 그 테스트를 제거한다.

**Files:**
- Modify: `apps/firehub-executor/app/services/query_executor.py:50-66` (삭제), `:156-173` (교체)
- Modify: `apps/firehub-executor/tests/test_query_executor.py:7-11` (import), `:351-373` (삭제)

**Interfaces:**
- Consumes: `_fetch_geom_oids(cursor.connection)`, `_build_geojson_wrapped_sql`, `_has_limit`, `_add_limit` (Task 1 및 기존).
- Produces: 동작 변경만. 공개 시그니처 불변 (`execute_query`).

- [ ] **Step 1: 회귀 테스트 작성 (숫자 텍스트 오탐 방지)**

`test_query_executor.py`의 SELECT 테스트 섹션에 추가:

```python
def test_numeric_text_not_misdetected_as_geometry():
    """YYYYMMDDHHMMSS 같은 숫자 텍스트 컬럼을 geometry 로 오판하지 않는다 (회귀)."""
    executed_sqls = []
    cursor = MagicMock()
    cursor.description = [("report_date", 25, None, None, None, None, None)]  # 25 = text OID
    cursor.fetchall.return_value = [("20260131235609",)]
    cursor.rowcount = 0

    def capture_execute(sql, *args, **kwargs):
        executed_sqls.append(sql)

    cursor.execute.side_effect = capture_execute

    # geometry OID 조회용 사이드 커서 (geometry OID=16000; text 25 는 미포함)
    geom_cursor = MagicMock()
    geom_cursor.fetchall.return_value = [(16000,)]
    side_conn = MagicMock()
    side_conn.cursor.return_value = geom_cursor
    cursor.connection = side_conn

    conn = MagicMock()
    conn.cursor.return_value = cursor

    result = execute_query("SELECT report_date FROM survey", max_rows=1000, read_only=False, conn=conn)

    assert result.success is True
    assert result.rows == [{"report_date": "20260131235609"}]
    assert not any("ST_AsGeoJSON" in s for s in executed_sqls), \
        f"text 컬럼이 geometry 로 오판되어 래핑됨: {executed_sqls}"
```

- [ ] **Step 2: 회귀 테스트 실패 확인 (현재 버그 재현)**

Run: `cd apps/firehub-executor && pytest tests/test_query_executor.py::test_numeric_text_not_misdetected_as_geometry -v`
Expected: FAIL — 현재 값 휴리스틱이 `"20260131235609"`를 geometry로 오판해 `ST_AsGeoJSON` 래핑이 `executed_sqls`에 들어가므로 `assert not any("ST_AsGeoJSON" ...)`에서 실패.

- [ ] **Step 3: OID 래핑 테스트 + 방어적 폴백 테스트 작성**

같은 섹션에 추가:

```python
def test_geometry_column_wrapped_via_oid_on_success():
    """성공한 SELECT 결과에 실제 geometry 컬럼(OID 매칭)이 있으면 ST_AsGeoJSON 로 래핑한다."""
    GEOM_OID = 16000
    executed_sqls = []
    state = {"wrapped": False}

    cursor = MagicMock()
    cursor.rowcount = 0
    cursor.description = [("geom", GEOM_OID, None, None, None, None, None)]

    def execute_side(sql, *args, **kwargs):
        executed_sqls.append(sql)
        if "ST_AsGeoJSON" in sql:
            state["wrapped"] = True
            cursor.description = [("geom", 25, None, None, None, None, None)]

    def fetchall_side():
        if state["wrapped"]:
            return [('{"type":"Point","coordinates":[1,2]}',)]
        return [("0101000000AABBCCDD",)]

    cursor.execute.side_effect = execute_side
    cursor.fetchall.side_effect = fetchall_side

    geom_cursor = MagicMock()
    geom_cursor.fetchall.return_value = [(GEOM_OID,)]
    side_conn = MagicMock()
    side_conn.cursor.return_value = geom_cursor
    cursor.connection = side_conn

    conn = MagicMock()
    conn.cursor.return_value = cursor

    result = execute_query("SELECT geom FROM shapes", max_rows=1000, read_only=False, conn=conn)

    assert result.success is True
    assert any("ST_AsGeoJSON" in s for s in executed_sqls)
    assert result.rows == [{"geom": '{"type":"Point","coordinates":[1,2]}'}]


def test_geometry_wrap_failure_falls_back_to_original_result():
    """geometry 로 판정됐으나 ST_AsGeoJSON 재실행이 실패하면 원본 성공 결과를 반환한다."""
    GEOM_OID = 16000
    executed_sqls = []

    cursor = MagicMock()
    cursor.rowcount = 0
    cursor.description = [("geom", GEOM_OID, None, None, None, None, None)]
    cursor.fetchall.return_value = [("0101000000DEADBEEF",)]

    def execute_side(sql, *args, **kwargs):
        executed_sqls.append(sql)
        if "ST_AsGeoJSON" in sql:
            raise Exception("ST_AsGeoJSON 실패")

    cursor.execute.side_effect = execute_side

    geom_cursor = MagicMock()
    geom_cursor.fetchall.return_value = [(GEOM_OID,)]
    side_conn = MagicMock()
    side_conn.cursor.return_value = geom_cursor
    cursor.connection = side_conn

    conn = MagicMock()
    conn.cursor.return_value = cursor

    result = execute_query("SELECT geom FROM shapes", max_rows=1000, read_only=False, conn=conn)

    assert result.success is True
    assert result.rows == [{"geom": "0101000000DEADBEEF"}]
    assert any("ST_AsGeoJSON" in s for s in executed_sqls)  # 시도는 했음
```

- [ ] **Step 4: 두 테스트 실패 확인**

Run: `cd apps/firehub-executor && pytest tests/test_query_executor.py::test_geometry_column_wrapped_via_oid_on_success tests/test_query_executor.py::test_geometry_wrap_failure_falls_back_to_original_result -v`
Expected: FAIL — 현재 성공 경로는 값 휴리스틱을 쓰므로 hex 문자열이면 래핑을 시도하지만, geometry OID 매칭이 아니라 hex 판정 기반이라 폴백 로직이 없어 기대 동작과 어긋난다(특히 폴백 테스트는 예외가 그대로 전파되어 `success is False`가 되어 실패).

- [ ] **Step 5: 값 휴리스틱 삭제 + 성공 경로 OID 교체 + 폴백 구현**

5a. `query_executor.py`에서 `_detect_geometry_in_rows` 함수(현재 `:50-66`) **전체 삭제**:

```python
def _detect_geometry_in_rows(
    rows: List[Dict[str, Any]], columns: List[str]
) -> set:
    """Detect geometry columns by checking if values look like hex WKB strings."""
    if not rows:
        return set()
    geom_cols = set()
    first_row = rows[0]
    for col in columns:
        val = first_row.get(col)
        if isinstance(val, str) and len(val) > 10:
            try:
                bytes.fromhex(val)
                geom_cols.add(col)
            except ValueError:
                pass
    return geom_cols
```

5b. `execute_query`의 성공-후 재스캔 블록(현재 `:156-173`, `# Check for hex WKB geometry in result rows` 주석부터 `rows = [dict(zip(columns, row)) for row in raw_rows]`까지)을 아래로 **교체**:

```python
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
                        if not _has_limit(wrapped_sql):
                            wrapped_sql = _add_limit(wrapped_sql, max_rows)
                        cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
                        cursor.execute("SAVEPOINT analytics_query")
                        cursor.execute(wrapped_sql)
                        columns = [d[0] for d in cursor.description] if cursor.description else []
                        raw_rows = cursor.fetchall()
                        rows = [dict(zip(columns, row)) for row in raw_rows]
                    except Exception:
                        # 방어적 폴백: GeoJSON 변환 실패 시 원본 성공 결과를 유지한다.
                        cursor.execute("ROLLBACK TO SAVEPOINT analytics_query")
                        cursor.execute("SAVEPOINT analytics_query")
                        columns, rows = orig_columns, orig_rows
```

- [ ] **Step 6: import·삭제된 함수의 기존 테스트 정리**

6a. `test_query_executor.py`의 import(현재 `:7-11`)에서 `_detect_geometry_in_rows` 제거:

```python
from app.services.query_executor import (
    _build_geojson_wrapped_sql,
    execute_query,
)
```

6b. 삭제된 함수를 검증하던 테스트 3종(`test_detect_geometry_in_rows`, `test_detect_geometry_in_rows_no_geom`, `test_detect_geometry_in_rows_empty`, 현재 `:351-373`)을 **모두 삭제**.

- [ ] **Step 7: 전체 테스트 통과 확인**

Run: `cd apps/firehub-executor && pytest tests/test_query_executor.py -v`
Expected: PASS (전부). 신규 4종 통과 + 기존 테스트 회귀 없음.

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-executor/app/services/query_executor.py apps/firehub-executor/tests/test_query_executor.py
git commit -m "fix(executor): geometry 판정을 OID 기반으로 교체 + 방어적 폴백

값 hex 휴리스틱이 YYYYMMDDHHMMSS 등 숫자 텍스트 컬럼을 geometry 로
오판해 ST_AsGeoJSON 래핑이 실패하던 버그 제거. 성공 경로에서 컬럼
타입 OID 로만 판정하고, 래핑 재실행 실패 시 원본 결과를 반환한다.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- 값 휴리스틱 제거 → Task 2 Step 5a. ✓
- 성공 경로 OID 교체 → Task 2 Step 5b. ✓
- `_fetch_geom_oids` 헬퍼 공유 → Task 1. ✓
- 방어적 폴백 → Task 2 Step 5b (try/except). ✓
- 에러 fallback 경로 OID 유지 → Task 1 (순서 보존 리팩터), 로직 미변경. ✓
- 회귀·진짜 geometry·방어적 폴백 TC → Task 2 Steps 1,3. ✓
- 기존 `_detect_geometry_in_rows` 테스트 정리 → Task 2 Step 6. ✓
- 범위 밖(report_date CAST) → 계획에 포함 안 함. ✓

**Placeholder scan:** 모든 코드/명령/기대출력 구체 명시. 없음. ✓

**Type consistency:** `_fetch_geom_oids(conn) -> set`를 Task 1에서 정의, Task 2에서 `_fetch_geom_oids(cursor.connection)`로 호출(인자=연결 객체 일관). `_build_geojson_wrapped_sql(sql, List[Tuple[str,bool]])` 시그니처 기존과 동일. `description[i][1]`=타입 OID 규약 전 테스트 일관. ✓

**주의(구현자용):** `_fetch_geom_oids`는 `conn.cursor()`로 새 커서를 연다. 일부 기존 테스트는 `conn.cursor()`가 메인 커서를 재반환(`return_value`)하거나 bare `MagicMock`을 반환한다 — `MagicMock().fetchall()`은 기본 `__iter__`가 빈 이터레이터라 `geom_oids=set()`가 되어 래핑을 건너뛴다(무해). 신규 테스트는 `cursor.connection`을 전용 사이드 커넥션으로 두어 geometry OID를 명시 제어한다.
