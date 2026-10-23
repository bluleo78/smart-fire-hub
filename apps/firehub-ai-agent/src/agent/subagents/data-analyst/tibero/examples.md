# data-analyst — 대화 예시

## 예시 1: 기본 EDA + 차트 생성

**사용자**: 화재 발생 데이터 분석해줘

**에이전트**:

[Phase 1 — EXPLORE]
1. `find_datasets({query: "화재"})` → `[{id: 11, tableName: "fire_incidents", name: "화재 출동 조사 데이터", storageType: "TABLE"}]`
2. `get_data_schema({datasetIds: [11]})` → 컬럼 8개 (`incident_date`, `incident_type`, `station_id`, `response_time_sec`, ...)
   - 응답의 `schema` 는 `GENIA_T5` → 테이블은 `genia_t5.fire_incidents` 로 쓴다(따옴표 없이).
3. `get_row_count(11)` → 24,150 행

`fire_incidents` 테이블에서 분석합니다. 총 24,150건, 주요 컬럼: `incident_date`, `incident_type`, `station_id`, `response_time_sec`.

[Phase 2 — ANALYZE] 기본 통계 실행

```sql
SELECT
  COUNT(*)                                  AS total_rows,
  MIN(incident_date)                        AS oldest,
  MAX(incident_date)                        AS latest,
  ROUND(AVG(response_time_sec) / 60, 1)     AS avg_response_min
FROM genia_t5.fire_incidents
```

**분석 결과:**

| 항목 | 값 |
|------|-----|
| 총 건수 | 24,150건 |
| 기간 | 2022-01-01 ~ 2025-12-31 |
| 평균 응답시간 | 7.3분 |

다음으로 사건 유형 분포와 월별 추이를 확인해드릴까요?

**사용자**: 응, 차트도 만들어줘

**에이전트**:

[Phase 5 — VISUALIZE] create_chart(type="BAR", title="사건 유형 분포") 호출

사건 유형 분포 BAR 차트를 생성했습니다. 화재조사(42%)와 구조출동(28%)이 가장 큰 비중을 차지합니다.

**다음 분석 제안**
1. 사건 유형별 평균 응답시간 비교
2. 최근 12개월로 기간을 좁힌 추이 확인

---

## 예시 2: 시계열 추이 + 저장 쿼리 + 차트

**사용자**: 월별 화재 건수 추이 보여줘, 저장도 해줘

**에이전트**:

[Phase 2 — ANALYZE]

```sql
SELECT
  TRUNC(incident_date, 'MM')  AS mon,
  COUNT(*)                    AS cnt
FROM genia_t5.fire_incidents
WHERE incident_date >= ADD_MONTHS(TRUNC(SYSDATE), -12)
GROUP BY TRUNC(incident_date, 'MM')
ORDER BY 1
```

**월별 화재 건수 (최근 12개월):**

| 월 | 건수 |
|-----|------|
| 2025-01 | 312 |
| 2025-02 | 287 |
| 2025-03 | 301 |
| ... | ... |
| 2025-12 | 341 |

겨울철(1월, 12월)에 건수가 높은 계절성 패턴이 보입니다.

[Phase 4 — PERSIST] create_saved_query(name="월별 화재 건수", folder="화재 건수 추이", ...) 호출

쿼리를 "화재 건수 추이" 폴더에 저장했습니다.

[Phase 5 — VISUALIZE] create_chart(type="LINE", title="월별 화재 건수") 호출

LINE 차트도 생성했습니다. 겨울철(1월, 12월)이 다른 달보다 뚜렷하게 높습니다.

**다음 분석 제안**
1. 전년 동기 대비 겨울철 증가폭 확인
2. 계절별 사건 유형 구성 차이 분석

---

## 예시 3: 그룹 비교 분석

**사용자**: 소방서별 평균 응답시간 비교해줘

**에이전트**:

[Phase 2 — ANALYZE]

```sql
SELECT
  station_id,
  COUNT(*)                                       AS total_incidents,
  ROUND(AVG(response_time_sec) / 60, 1)          AS avg_min,
  ROUND(MIN(response_time_sec) / 60, 1)          AS min_min,
  ROUND(MAX(response_time_sec) / 60, 1)          AS max_min
FROM genia_t5.fire_incidents
GROUP BY station_id
ORDER BY avg_min ASC
FETCH FIRST 15 ROWS ONLY
```

**소방서별 응답시간 (상위 15개):**

| 소방서 | 건수 | 평균(분) | 최소(분) | 최대(분) |
|--------|------|---------|---------|---------|
| 서초119 | 1,240 | 4.2 | 1.1 | 23.7 |
| 강남119 | 1,580 | 4.8 | 0.9 | 31.2 |
| ... | ... | ... | ... | ... |

서초119가 가장 빠른 평균 응답시간을 기록했습니다. 분석을 스마트 작업으로 등록해 매주 자동 리포트를 받으시겠어요?

---

## 예시 4: SQL 에러 자체 정정 (retry loop 차단)

**사용자**: 만족도 데이터 평균 보여줘

**에이전트**:

[Phase 1 — EXPLORE]
1. `find_datasets({query: "만족도"})` → `[{id: 7, tableName: "survey_119_2026", storageType: "TABLE"}]`
2. `get_data_schema({datasetIds: [7]})` → 컬럼: `survey1_kindness`, `survey2_kindness_detail`, `survey4_speed`, ...

[Phase 2 — ANALYZE] 만족도 평균을 확인합니다.

```sql
SELECT AVG(survey1_kindness) AS avg_kindness FROM survey_119_2026
```

→ 에러 응답:
```
테이블 참조에 스키마가 없습니다: 'survey_119_2026'. Tibero 저장소에서는 genia_t5.survey_119_2026 처럼 스키마와 함께 써 주세요.
```

오류 문구와 Phase 1 `get_data_schema` 의 `schema`(`GENIA_T5`)를 확인했습니다 — 스키마를 붙여 정정합니다.

```sql
SELECT AVG(survey1_kindness) AS avg_kindness FROM genia_t5.survey_119_2026
```

→ 결과: `{avg: 8.7}`

[Phase 3 — INTERPRET] 친절도 평균은 **8.7점** 입니다 (10점 만점 기준).

---

## ❌ 잘못된 패턴 (retry loop 의 원인)

### 1. 컨텍스트 없이 SQL 추측

```
execute_analytics_query("SELECT kindness FROM genia_t5.survey")
→ 오류(없는 컬럼)
→ (같은 SQL 재시도) → 같은 오류 → ... → retry loop
```

Phase 1 (`find_datasets` + `get_data_schema`) 없이 SQL 을 작성하면 컬럼명·테이블명을 추측할 수밖에 없다.

### 2. `get_data_schema` 빈 호출

```
get_data_schema()
→ InputValidationError: datasetIds is required
```

SDK 가 즉시 차단한다. `find_datasets` 먼저 호출해 id 를 확보한 뒤 다시 호출.

### 3. 에러 무시 후 같은 SQL 반복

```
execute_analytics_query(...) → 오류 + 안내 문구
execute_analytics_query(같은 SQL)  ← 안내 문구 안 읽음
→ 같은 오류
```

`error` 필드의 한국어 안내 문구와 Tibero 오류 원문(`JDBC-NNNN:`)은 자체 정정을 위한 정보다. 읽지 않으면 retry loop 가 발생한다.

---

## 예시 5: 비담당 요청 — 위임

**사용자**: 화재 데이터셋 새로 만들어줘

**에이전트**:

데이터셋 생성은 제 담당이 아닙니다. `dataset-manager` 에이전트가 처리합니다.
