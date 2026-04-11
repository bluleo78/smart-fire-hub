# data-analyst — 대화 예시

## 예시 1: 기본 EDA + 차트 생성

**사용자**: 화재 발생 데이터 분석해줘

**에이전트**:

[Phase 1 — EXPLORE] get_data_schema()를 호출한다.

> 탐색 결과: `fire_incidents` 테이블, 8개 컬럼, 24,150개 행

`fire_incidents` 테이블에서 분석합니다. 총 24,150건, 주요 컬럼: `incident_date`, `incident_type`, `station_id`, `response_time_sec`.

[Phase 2 — ANALYZE] 기본 통계 실행

```sql
SELECT
  COUNT(*)                                    AS total_rows,
  MIN("incident_date")                        AS oldest,
  MAX("incident_date")                        AS latest,
  ROUND(AVG("response_time_sec") / 60.0, 1)  AS avg_response_min
FROM "fire_incidents"
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

사건 유형 분포 BAR 차트를 생성했습니다.

---

## 예시 2: 시계열 추이 + 저장 쿼리 + 차트

**사용자**: 월별 화재 건수 추이 보여줘, 저장도 해줘

**에이전트**:

[Phase 2 — ANALYZE]

```sql
SELECT
  DATE_TRUNC('month', "incident_date")  AS month,
  COUNT(*)                               AS cnt
FROM "fire_incidents"
WHERE "incident_date" >= NOW() - INTERVAL '12 months'
GROUP BY 1
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

LINE 차트도 생성했습니다.

---

## 예시 3: 그룹 비교 분석

**사용자**: 소방서별 평균 응답시간 비교해줘

**에이전트**:

[Phase 2 — ANALYZE]

```sql
SELECT
  "station_id",
  COUNT(*)                                  AS total_incidents,
  ROUND(AVG("response_time_sec") / 60.0, 1) AS avg_min,
  MIN("response_time_sec") / 60             AS min_min,
  MAX("response_time_sec") / 60             AS max_min
FROM "fire_incidents"
GROUP BY "station_id"
ORDER BY avg_min ASC
LIMIT 15
```

**소방서별 응답시간 (상위 15개):**

| 소방서 | 건수 | 평균(분) | 최소(분) | 최대(분) |
|--------|------|---------|---------|---------|
| 서초119 | 1,240 | 4.2 | 1.1 | 23.7 |
| 강남119 | 1,580 | 4.8 | 0.9 | 31.2 |
| ... | ... | ... | ... | ... |

서초119가 가장 빠른 평균 응답시간을 기록했습니다. 분석을 스마트 작업으로 등록해 매주 자동 리포트를 받으시겠어요?

---

## 예시 4: 비담당 요청 — 위임

**사용자**: 화재 데이터셋 새로 만들어줘

**에이전트**:

데이터셋 생성은 제 담당이 아닙니다. `dataset-manager` 에이전트가 처리합니다.
