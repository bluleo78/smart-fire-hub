# 파이프라인 스텝 타입 상세 스펙

## 1. SQL 스텝

**용도**: SQL 쿼리로 데이터 변환/집계

**필수 필드**:
- scriptType: "SQL"
- scriptContent: SQL 쿼리 (SELECT 문 권장)

**자동 적재 규칙**:
- SELECT 문을 작성하면 결과가 자동으로 출력 데이터셋에 INSERT됨
- INSERT INTO를 직접 작성할 필요 없음 (작성하면 오히려 오류 발생 가능)
- 출력 컬럼은 SELECT 절의 컬럼과 출력 데이터셋의 컬럼이 자동 매칭됨

**스텝 참조 문법**: {{#N}}
- N은 1부터 시작하는 스텝 순서 번호
- 실행 시 해당 스텝의 출력 테이블명으로 치환됨
- 예: `SELECT * FROM {{#1}} WHERE amount > 1000`
- 명시적 테이블은: `data."tableName"` 형식

**loadStrategy**:
- REPLACE (기본): 기존 데이터 삭제 후 새 데이터 삽입
- APPEND: 기존 데이터에 추가

**예시**:
```json
{
  "name": "monthly_agg",
  "scriptType": "SQL",
  "scriptContent": "SELECT DATE_TRUNC('month', order_date) AS month, SUM(amount) AS total FROM data.\"sales\" GROUP BY 1 ORDER BY 1",
  "dependsOnStepNames": [],
  "loadStrategy": "REPLACE"
}
```

## 2. PYTHON 스텝

**용도**: Python으로 복잡한 데이터 처리 (API 호출, 데이터 가공 등)

**필수 필드**:
- scriptType: "PYTHON"
- scriptContent: Python 코드

**자동 적재 규칙**:
- stdout에 JSON 배열을 출력하면 자동으로 출력 데이터셋에 적재
- `print(json.dumps([{"col1": "val1", ...}]))` 형식
- stderr는 로그용: `print("진행 중...", file=sys.stderr)`

**pythonConfig.outputColumns**:
- 출력 데이터셋 없이 temp 데이터셋을 자동 생성하려면 정의
- `{ outputColumns: [{ name: "col_name", type: "TEXT" }] }`
- 지원 타입: TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP
- stdout JSON의 키 이름이 outputColumns의 name과 일치해야 함

**환경변수** (자동 제공):
- DB_URL, DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, DB_SCHEMA(=data)

**예시**:
```json
{
  "name": "fetch_exchange_rate",
  "scriptType": "PYTHON",
  "scriptContent": "import json, urllib.request\nurl = 'https://api.example.com/rates'\ndata = json.loads(urllib.request.urlopen(url).read())\nresult = [{'currency': k, 'rate': v} for k, v in data['rates'].items()]\nprint(json.dumps(result))",
  "pythonConfig": {
    "outputColumns": [
      { "name": "currency", "type": "TEXT" },
      { "name": "rate", "type": "DECIMAL" }
    ]
  }
}
```

## 3. API_CALL 스텝

**용도**: 외부 REST API를 호출하여 데이터 수집

**필수 필드**:
- scriptType: "API_CALL"
- apiConfig: API 호출 설정 객체

**apiConfig 핵심 필드**:
- url: API 엔드포인트 (SSRF 보호: 사설 IP 차단)
- method: GET/POST/PUT/DELETE
- dataPath: JSONPath로 데이터 배열 추출 (예: "$.data", "$.items")
- fieldMappings: 소스 필드 → 대상 컬럼 매핑 (선택)

**인증**:
- apiConnectionId: 저장된 API 연결 참조
- 또는 inlineAuth: 직접 인증 정보 (일회성)

**페이지네이션**:
- pagination: { type: "OFFSET", pageSize: N, offsetParam, limitParam, totalPath }

**예시**:
```json
{
  "name": "fetch_users",
  "scriptType": "API_CALL",
  "apiConfig": {
    "url": "https://api.example.com/users",
    "method": "GET",
    "dataPath": "$.data",
    "fieldMappings": [
      { "sourceField": "id", "targetColumn": "user_id" },
      { "sourceField": "name", "targetColumn": "user_name" },
      { "sourceField": "email", "targetColumn": "email" }
    ],
    "pagination": {
      "type": "OFFSET",
      "pageSize": 100,
      "offsetParam": "offset",
      "limitParam": "limit",
      "totalPath": "$.total"
    }
  },
  "apiConnectionId": 1
}
```

## 4. AI_CLASSIFY 스텝

**용도**: LLM으로 데이터 분류/변환/추출

**필수 필드**:
- scriptType: "AI_CLASSIFY"
- aiConfig: AI 처리 설정

**aiConfig 핵심 필드**:
- prompt: 처리 지시 (어떤 컬럼을 읽고 어떤 결과를 생성할지)
- outputColumns: 출력 컬럼 스키마 [{ name, type }]
  - source_id(INTEGER)는 자동 추가됨 (입력 row 추적용)
- inputColumns: LLM에 전달할 컬럼 필터 (미지정 시 전체, 토큰 절감용)
- batchSize: 배치 크기 (1~100, 기본 20)
- onError: CONTINUE(기본) / RETRY_BATCH / FAIL_STEP

**예시**:
```json
{
  "name": "classify_sentiment",
  "scriptType": "AI_CLASSIFY",
  "aiConfig": {
    "prompt": "각 행의 review 컬럼을 읽고 감성을 분류하세요. positive/negative/neutral 중 하나를 label로 반환하세요.",
    "outputColumns": [
      { "name": "label", "type": "TEXT" },
      { "name": "confidence", "type": "DECIMAL" }
    ],
    "inputColumns": ["review"],
    "batchSize": 20,
    "onError": "CONTINUE"
  },
  "dependsOnStepNames": ["fetch_reviews"]
}
```
