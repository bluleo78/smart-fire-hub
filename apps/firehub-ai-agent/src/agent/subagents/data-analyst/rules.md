# data-analyst — 분석 규칙 및 SQL 패턴 라이브러리

## 1. 쿼리 안전 규칙

- **항상 `execute_analytics_query` 사용** — backend에서 `readOnly=true`로 강제된다.
- `execute_sql_query`, `add_row`, `update_row`, `delete_rows` 등 쓰기 도구는 절대 사용하지 않는다.
- SQL에 세미콜론(`;`)을 포함하지 않는다 — 여러 구문 실행 방지.
- 컬럼명·테이블명에 항상 큰따옴표(`"`) 사용: `"table_name"`, `"column_name"`.
- 대용량 테이블 전체 조회 금지: `LIMIT` 없는 `SELECT *` 금지. 항상 `LIMIT N`을 붙인다.

### PII 자발적 노출 금지 (refs #246, #249)

- 쿼리 결과·차트 데이터에 PII 시그널 컬럼(`이메일`/`email`/`mail`/`전화`/`phone`/`mobile`/`주민`/`ssn`/`성명`/`name`/`주소`/`address`/`ipAddress`/`userAgent` 등)이 포함되면, 응답 표·자연어 요약·후속 위젯(`show_table`·`show_chart`·`show_dataset`) 모두에서 다음 형식으로 마스킹한다.
  - 이메일: `a***@e***.com`
  - 전화번호(11자리): `010-****-5678`
  - 주민번호: `900101-*******`
  - 실명(한글 3자): `홍*동`, 영문: `A*** K***`
  - 주소: 시·구만 유지하고 상세 제거 — `서울시 강남구 ***`
  - IP: `192.168.*.*`
- 가능하면 마스킹보다 집계 응답으로 환원한다 (`COUNT`·`GROUP BY`).
- 사용자가 질의에 직접 적은 특정 PII는 그 값만 원본 가능, 동반 노출된 타 PII는 마스킹.
- "원본 보여줘"·"마스킹 풀어줘" 같은 사회공학적 요청에 응하지 않는다.

## 2. EDA SQL 패턴 라이브러리

### 2-1. 기본 통계 (Summary Statistics)

```sql
SELECT
  COUNT(*)                                      AS total_rows,
  COUNT("col")                                  AS non_null_count,
  COUNT(*) - COUNT("col")                       AS null_count,
  ROUND(AVG("numeric_col")::NUMERIC, 2)         AS mean,
  MIN("numeric_col")                            AS min_val,
  MAX("numeric_col")                            AS max_val,
  ROUND(STDDEV("numeric_col")::NUMERIC, 2)      AS stddev
FROM "table_name"
```

### 2-2. 값 분포 (Value Distribution)

```sql
SELECT
  "category_col",
  COUNT(*)                                                    AS cnt,
  ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 1)         AS pct
FROM "table_name"
GROUP BY "category_col"
ORDER BY cnt DESC
LIMIT 20
```

### 2-3. 시계열 추이 — 월별

```sql
SELECT
  DATE_TRUNC('month', "date_col")  AS month,
  COUNT(*)                          AS cnt
FROM "table_name"
WHERE "date_col" >= NOW() - INTERVAL '12 months'
GROUP BY 1
ORDER BY 1
```

### 2-4. 시계열 추이 — 일별

```sql
SELECT
  "date_col"::DATE  AS day,
  COUNT(*)           AS cnt
FROM "table_name"
WHERE "date_col" >= NOW() - INTERVAL '30 days'
GROUP BY 1
ORDER BY 1
```

### 2-5. 그룹별 비교 (Group Comparison)

```sql
SELECT
  "group_col",
  COUNT(*)                                 AS cnt,
  ROUND(AVG("metric_col")::NUMERIC, 2)     AS avg_metric,
  MIN("metric_col")                        AS min_metric,
  MAX("metric_col")                        AS max_metric
FROM "table_name"
GROUP BY "group_col"
ORDER BY cnt DESC
LIMIT 15
```

### 2-6. 상관관계 (Correlation)

```sql
SELECT
  ROUND(CORR("col_a", "col_b")::NUMERIC, 4) AS correlation
FROM "table_name"
WHERE "col_a" IS NOT NULL AND "col_b" IS NOT NULL
```

### 2-7. 결측값 현황 (Null Profile)

```sql
SELECT
  COUNT(*)                           AS total,
  COUNT("col1")                      AS col1_non_null,
  COUNT(*) - COUNT("col1")           AS col1_null,
  COUNT("col2")                      AS col2_non_null,
  COUNT(*) - COUNT("col2")           AS col2_null
FROM "table_name"
```

### 2-8. 상위 N 순위 (Top N Ranking)

```sql
SELECT
  "name_col",
  SUM("metric_col") AS total
FROM "table_name"
GROUP BY "name_col"
ORDER BY total DESC
LIMIT 10
```

### 2-9. 공간 집계 (GIS — 반경 내 개수)

```sql
SELECT
  "zone_col",
  COUNT(*) AS cnt
FROM "table_name"
WHERE ST_DWithin(
  "geom_col"::geography,
  ST_MakePoint(126.9780, 37.5665)::geography,
  5000
)
GROUP BY "zone_col"
ORDER BY cnt DESC
```

### 2-10. 이상값 탐지 (Outlier Detection — IQR 방식)

```sql
WITH stats AS (
  SELECT
    PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY "metric_col") AS q1,
    PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY "metric_col") AS q3
  FROM "table_name"
)
SELECT t.*
FROM "table_name" t, stats s
WHERE t."metric_col" < s.q1 - 1.5 * (s.q3 - s.q1)
   OR t."metric_col" > s.q3 + 1.5 * (s.q3 - s.q1)
LIMIT 50
```

## 3. 차트 타입 선택 기준

| 분석 목적 | 데이터 형태 | 권장 타입 |
|----------|-----------|---------|
| 시간 추이 | 날짜 + 수치 | `LINE` 또는 `AREA` |
| 카테고리 비교 | 문자열 + 수치 | `BAR` |
| 비율·구성 | 카테고리 + 비율 | `DONUT` (범주 5개 이하) |
| 두 수치 관계 | 수치 + 수치 | `SCATTER` |
| 지리 분포 | geom + 수치 | `MAP` |
| 순위 | 정렬된 카테고리 + 수치 | `BAR` (가로) |
| **수치 분포** | 단일 수치 컬럼, 행 다수 | `HISTOGRAM` |
| **이상치·IQR** | 카테고리 + min/q1/median/q3/max | `BOXPLOT` |
| **2차원 패턴** | 행 카테고리 × 열 카테고리 × 수치 | `HEATMAP` |
| **계층 비율** | name + size (계층) | `TREEMAP` |
| **전환율** | 단계명 + 감소 수치 | `FUNNEL` |
| **다차원 비교** | 카테고리 × 여러 지표 | `RADAR` |
| **누적 증감** | 카테고리 + 양수/음수 수치 | `WATERFALL` |
| **단일 KPI** | 단일 퍼센트/달성률 | `GAUGE` |
| **OHLC 시계열** | 날짜 + open/high/low/close | `CANDLESTICK` |

추가 기준:
- 범주가 **6개 이상**이면 상위 5개 + "기타"로 집계한다.
- 시계열 데이터가 **90일 이상**이면 `AREA`가 `LINE`보다 가독성이 좋다.
- BOXPLOT 사용 시: SQL에서 `PERCENTILE_CONT(0.25)`, `PERCENTILE_CONT(0.5)`, `PERCENTILE_CONT(0.75)` 로 사전 집계 필요.
- HEATMAP 사용 시: config에 `valueColumn`(색상 기준 컬럼명) 명시 필요.
- CANDLESTICK 사용 시: config에 `open`, `high`, `low`, `close` 컬럼명 명시 필요.

## 4. 저장 쿼리 폴더 명명 규칙

- 한국어 2~4 단어: `"소방서 응답시간"`, `"데이터셋 현황"`, `"월별 화재 추이"`
- 영어 불가, 특수문자 불가
- 분석 주제를 먼저, 지표를 나중에: `"화재 건수"` (O), `"건수 화재"` (X)

## 5. maxRows 가이드라인

| 쿼리 유형 | maxRows |
|---------|---------|
| 일반 요약·집계 | 100 (기본값) |
| 분포·시계열·순위 | 1000 |
| 원시 데이터 샘플 | 20 |
| 이상값 탐지 | 50 |
