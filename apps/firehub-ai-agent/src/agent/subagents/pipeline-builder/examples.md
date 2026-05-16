# 파이프라인 생성 예제

## 예제 0: 단순 필터 — DESIGN 단계 출력 + SELECT * 회피 (필수 패턴)

**요구사항 (사용자 짧은 요청)**: "데이터셋 화재 발생 위치(id=67)에서 사망자수 > 0 행만 필터링하는 SQL 파이프라인 만들어줘. 이름은 'fatal_fires_filter'."

이 짧은 한 줄은 **위임 신호가 아님** — 반드시 Phase 1 (`get_dataset`) → Phase 2 DESIGN 텍스트 → 사용자 승인 → Phase 4 `create_pipeline` 순서를 지킨다.

### Phase 1 — 스키마 확인 (도구 호출)
`get_dataset(id=67)`로 컬럼 목록 획득 → `occurred_at` (TIMESTAMP), `death_count` (INTEGER), `location` (GEOMETRY).

### Phase 2 — DESIGN 텍스트 (사용자에게 출력)

```
## 설계안: fatal_fires_filter

| 스텝 | 타입 | 입력 | 출력 |
|------|------|------|------|
| filter_fatal_fires | SQL | data."fire_incidents" | temp 자동 생성 |

### SQL
```sql
SELECT
  occurred_at,
  death_count,
  location
FROM data."fire_incidents"
WHERE death_count > 0
```

### 검증 체크리스트
- [✅] 컬럼명이 실제 스키마와 일치 (occurred_at / death_count / location)
- [✅] SELECT * 미사용 — 필요한 컬럼만 명시
- [✅] 예약 컬럼(id/import_id/created_at) 충돌 없음
- [✅] {{#N}} 미사용 (단일 스텝)
- [✅] 순환 의존성 없음

**이대로 생성할까요?** (예 / 수정 요청)
```

### Phase 4 — 사용자 승인 후 create_pipeline 호출

사용자가 "예" / "응" / "ok" / "생성해" 등으로 응답한 다음에만 `create_pipeline`을 호출. `SELECT *`가 절대 들어가지 않은 scriptContent로 호출한다.

**핵심**:
- DESIGN 텍스트 없이 곧장 `create_pipeline`을 호출하지 않는다.
- `SELECT *`를 쓰지 않는다. 필터링만 해도 컬럼을 모두 풀어 적는다.
- 사용자가 짧게 요청해도 설계안을 보여주고 승인받는 단계를 생략하지 않는다.

---

## 예제 1: 단순 SQL 집계

**요구사항**: sales 테이블을 월별로 집계하여 monthly_summary에 저장

```json
{
  "name": "월별 매출 집계",
  "description": "sales 데이터를 월별로 집계합니다",
  "steps": [
    {
      "name": "monthly_agg",
      "scriptType": "SQL",
      "scriptContent": "SELECT DATE_TRUNC('month', order_date) AS month, COUNT(*) AS order_count, SUM(amount) AS total_amount, AVG(amount) AS avg_amount FROM data.\"sales\" GROUP BY 1 ORDER BY 1",
      "outputDatasetId": 15,
      "loadStrategy": "REPLACE"
    }
  ]
}
```

## 예제 2: 멀티스텝 SQL (스텝 참조)

**요구사항**: 주문 데이터를 카테고리별로 집계 후 순위 매기기

```json
{
  "name": "카테고리별 매출 순위",
  "steps": [
    {
      "name": "category_agg",
      "scriptType": "SQL",
      "scriptContent": "SELECT category, SUM(amount) AS total FROM data.\"orders\" GROUP BY category"
    },
    {
      "name": "add_rank",
      "scriptType": "SQL",
      "scriptContent": "SELECT category, total, RANK() OVER (ORDER BY total DESC) AS rank FROM {{#1}}",
      "dependsOnStepNames": ["category_agg"],
      "outputDatasetId": 20,
      "loadStrategy": "REPLACE"
    }
  ]
}
```

**포인트**:
- 첫 번째 스텝: outputDatasetId 미지정 → temp 자동 생성
- 두 번째 스텝: {{#1}}로 첫 번째 스텝의 temp 테이블 참조
- dependsOnStepNames로 실행 순서 보장

## 예제 3: Python + SQL 조합

**요구사항**: 외부 API에서 환율 데이터 수집 → 매출에 환율 적용

```json
{
  "name": "환율 적용 매출",
  "steps": [
    {
      "name": "fetch_rates",
      "scriptType": "PYTHON",
      "scriptContent": "import json, urllib.request\ndata = json.loads(urllib.request.urlopen('https://api.exchangerate.host/latest?base=USD').read())\nresult = [{'currency': k, 'rate': v} for k, v in data['rates'].items()]\nprint(json.dumps(result))",
      "pythonConfig": {
        "outputColumns": [
          { "name": "currency", "type": "TEXT" },
          { "name": "rate", "type": "DECIMAL" }
        ]
      }
    },
    {
      "name": "apply_rate",
      "scriptType": "SQL",
      "scriptContent": "SELECT s.*, r.rate, s.amount * r.rate AS amount_usd FROM data.\"sales\" s JOIN {{#1}} r ON s.currency = r.currency",
      "dependsOnStepNames": ["fetch_rates"],
      "outputDatasetId": 25,
      "loadStrategy": "REPLACE"
    }
  ]
}
```

## 예제 4: AI_CLASSIFY 분류

**요구사항**: 고객 리뷰를 감성 분석

```json
{
  "name": "리뷰 감성 분석",
  "steps": [
    {
      "name": "classify_sentiment",
      "scriptType": "AI_CLASSIFY",
      "aiConfig": {
        "prompt": "각 행의 review_text 컬럼을 읽고 감성을 분류하세요. positive/negative/neutral 중 하나를 선택하고 확신도를 0~1로 반환하세요.",
        "outputColumns": [
          { "name": "sentiment", "type": "TEXT" },
          { "name": "confidence", "type": "DECIMAL" }
        ],
        "inputColumns": ["review_text"],
        "batchSize": 20
      },
      "outputDatasetId": 30,
      "loadStrategy": "REPLACE"
    }
  ]
}
```
