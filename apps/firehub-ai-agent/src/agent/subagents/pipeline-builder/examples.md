# 파이프라인 생성 예제

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
