# Executor geometry 오탐 수정 — 설계

- 날짜: 2026-07-01
- 대상: `apps/firehub-executor/app/services/query_executor.py`
- 유형: 버그 수정 (backend)

## 배경 / 문제

`execute_analytics_query`로 `SELECT report_date FROM data.survey_119_2026 LIMIT 5`
같은 단순 조회를 하면 executor가 다음 에러를 반환한다.

```
parse error - invalid geometry
HINT:  "20" <-- parse error at position 2 within geometry
```

그러나 해당 표에는 geometry 컬럼이 없다(전부 `text`/`int8`/`timestamp`). `report_date`는
`text`이고 값은 `"20260131235609"`(YYYYMMDDHHMMSS). 원시 psql로는 정상 조회된다. 즉 DB나
모델(gemma) 문제가 아니라 **executor의 geometry 판정 로직 결함**이다.

### 근본 원인

executor는 SELECT 성공 후 "결과에 geometry 컬럼이 있으면 GeoJSON으로 변환"하는 기능을 가진다.
그 판정을 `_detect_geometry_in_rows`(`query_executor.py:50-66`)가 **값 기반 hex 휴리스틱**으로 한다.

```python
val = first_row.get(col)
if isinstance(val, str) and len(val) > 10:
    try:
        bytes.fromhex(val)   # "20260131235609" 도 성공
        geom_cols.add(col)   # text 컬럼을 geometry로 오판
    except ValueError:
        pass
```

`"20260131235609"`는 (1) 문자열, (2) 길이 14 > 10, (3) 전부 0-9라 유효 hex → `bytes.fromhex()`가
예외 없이 성공 → geometry로 오판된다. WKB 매직바이트/SRID/타입 검증이 전혀 없다.

오판되면 executor는 쿼리를 다음처럼 다시 짜서 재실행한다(`_build_geojson_wrapped_sql`, `:34-47`; 호출 `:165`).

```sql
WITH _src AS (SELECT report_date FROM data.survey_119_2026)
SELECT public.ST_AsGeoJSON("report_date") AS "report_date" FROM _src LIMIT 5
```

PostGIS `ST_AsGeoJSON`이 `"20260131235609"`를 지오메트리로 파싱하려다 실패 → 위 에러가 발생하고,
**원래 성공했던 결과를 복구하지 않고** 그대로 사용자에게 반환된다(`:170`에서 예외 → 바깥
`except` `:209-220`).

### 영향 범위

값 기반 휴리스틱이므로 **길이 11자 이상 + hex 호환 텍스트 컬럼**은 모두 오판 대상이다:
YYYYMMDDHHMMSS 날짜 문자열, 전화번호, 긴 숫자 ID/코드 등. 특정 데이터셋에 국한되지 않고
다수 데이터셋의 분석 쿼리가 잠재적으로 깨진다. 모델과 무관하다.

## 대책 (선택안: A — OID 교체 + 방어적 폴백)

코드에는 이미 정확한 판정 방법 `_detect_geometry_columns`(`:10-31`)가 있다. 값이 아니라
PostgreSQL 컬럼 타입 **OID**(`pg_type`의 `geometry`/`geography` OID를 `cursor.description`의
type_code와 비교)로 판정한다. `report_date`는 타입이 `text`라 이 방식으로는 절대 오판되지 않는다.
현재는 이 정확한 방식이 "최초 실행이 예외났을 때"(fallback 경로 `:132-154`)에만 쓰이고, 정상 성공
경로에서는 값 휴리스틱이 쓰이는 게 문제다.

### 변경 내용

대상 파일: `apps/firehub-executor/app/services/query_executor.py` (단일 파일)

1. **값 휴리스틱 제거** — `_detect_geometry_in_rows`(`:50-66`) 함수 삭제.

2. **성공 경로를 OID 기반으로 교체**(`:156-173`)
   - 최초 실행이 성공하면 `cursor.description`이 이미 각 컬럼의 타입 OID(`desc[1]`)를 가진다.
     별도 재조회 불필요.
   - geometry/geography OID 집합을 `pg_type`에서 1회 조회. 기존 `_detect_geometry_columns`의
     OID 조회 부분을 `_fetch_geom_oids(conn) -> set` 헬퍼로 분리해 두 경로가 공유한다.
   - 판정: `column_metas = [(d[0], d[1] in geom_oids) for d in cursor.description]`.
   - 진짜 geometry 컬럼이 하나라도 있을 때만 `ST_AsGeoJSON` 래핑 재실행. `report_date`(text)는
     OID가 달라 걸리지 않는다.

3. **방어적 폴백**(선택안 A의 핵심)
   - 래핑 재실행을 `try/except`로 감싼다. 성공한 원본 `columns`/`rows`는 이미 Python 메모리에
     확보돼 있으므로:
     - 재실행 성공 → GeoJSON 결과로 교체
     - 재실행 실패 → `ROLLBACK TO SAVEPOINT` 후 SAVEPOINT 재설정, **원본 성공 결과를 그대로
       `success=True`로 반환**(에러로 끝나지 않음)

4. **에러 fallback 경로 유지** — `:132-154`(최초 실행이 예외난 경우 OID 기반 래핑 재실행)는 이미
   OID 기반이라 로직 유지. `_fetch_geom_oids` 헬퍼만 공유하도록 정리. 진짜 geometry의 GeoJSON 변환
   기능은 보존된다.

### 범위 밖 (별도 이슈)

- `report_date`가 `CAST(... AS DATE)`에서 깨지는 문제(텍스트가 YYYYMMDDHHMMSS 포맷 →
  `date/time field value out of range`)는 executor 버그와 무관한 데이터/프롬프트 이슈(#293 계열).
  이번 수정 범위에서 제외한다.

## 테스트 (backend TC 필수)

대상: `apps/firehub-executor/tests/test_query_executor.py`

테스트는 실 DB 없이 `MagicMock` 커서를 쓴다. `description[i][1]`에 타입 OID를 지정하고, `pg_type`
조회는 `fetchall` side_effect로 geometry OID를 반환하며, `execute` 호출별 SQL을 검증한다(기존
테스트의 목 패턴을 따른다).

추가 TC:

1. **회귀(핵심 버그 재현)** — text 컬럼(OID 25) 값 `"20260131235609"` → geometry로 오판되지
   않는다. `ST_AsGeoJSON` 래핑 재실행이 호출되지 않고, 값이 원본 그대로 반환됨을 검증. (수정 전
   실패 → 수정 후 통과)
2. **진짜 geometry(OID 기반)** — 컬럼 타입 OID가 geometry OID일 때 `ST_AsGeoJSON`으로 래핑
   재실행되고 GeoJSON 결과가 반환된다. 실행된 wrapped SQL에 `ST_AsGeoJSON` 포함을 검증.
3. **방어적 폴백** — geometry로 판정됐으나 래핑 재실행이 예외를 던지면 원본 성공 결과가
   `success=True`로 반환됨을 검증(에러로 끝나지 않음).

기존 TC 정리:

- `_detect_geometry_in_rows` import·검증 테스트 제거/교체.
- 값 hex 휴리스틱에 의존하던 geometry 테스트를 OID 기반으로 재작성.

검증 명령: executor 디렉터리에서 `pytest tests/test_query_executor.py` (또는 루트 `pnpm test`).

## 배포

수정 후 이미지 재빌드·배포가 필요하다: `./scripts/deploy.sh executor` (빌드 규칙은
`.claude/docs/deploy.md` 참조). 사용자 명시적 승인 후 진행.
