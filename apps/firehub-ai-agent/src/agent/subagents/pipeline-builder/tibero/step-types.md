# 파이프라인 스텝 타입 상세 스펙

> **Tibero 테넌트**: SQL 스텝만 실행된다. PYTHON·API_CALL·AI_CLASSIFY 스텝은 api 가 실행을 거부하므로(`rules.md` 맨 위 절) 아래 2~4절은 참고용이며 이 테넌트에서는 설계하지 않는다.

## 1. SQL 스텝

**용도**: SQL 쿼리로 데이터 변환/집계

**필수 필드**:
- scriptType: "SQL"
- scriptContent: SQL 쿼리 (SELECT 문 권장)

**자동 적재 규칙**:
- SELECT 문을 작성하면 결과가 자동으로 출력 데이터셋에 INSERT됨
- INSERT INTO를 직접 작성할 필요 없음 (작성하면 오히려 오류 발생 가능)
- 출력 컬럼은 SELECT 절의 컬럼과 출력 데이터셋의 컬럼이 자동 매칭됨
- **Tibero SQL 단계는 반드시 outputDatasetId 를 지정한다.** 비우면 실행 시 "Tibero 저장소 테넌트의 SQL 단계는 출력 데이터셋을 지정해야 합니다." 로 거부된다.

**스텝 참조 문법**: {{#N}}
- N은 1부터 시작하는 스텝 순서 번호
- 실행 시 해당 스텝의 출력 테이블명으로 치환됨
- 예: `SELECT * FROM {{#1}} WHERE amount > 1000`
- 명시적 테이블은: `genia_t5.tablename` 형식(`get_data_schema` 의 `schema` 로 한정, 따옴표 없이)

**loadStrategy**:
- REPLACE (기본): 기존 데이터 삭제 후 새 데이터 삽입
- APPEND: 기존 데이터에 추가
- MERGE: 출력 데이터셋 PK 기준 upsert (SQL 스텝 전용, 출력 데이터셋에 PK가 있어야 함)

**예시**:
```json
{
  "name": "monthly_agg",
  "scriptType": "SQL",
  "scriptContent": "SELECT TRUNC(order_date, 'MM') AS mon, SUM(amount) AS total FROM genia_t5.sales GROUP BY TRUNC(order_date, 'MM') ORDER BY 1",
  "outputDatasetId": 15,
  "dependsOnStepNames": [],
  "loadStrategy": "REPLACE"
}
```

## 2. PYTHON 스텝

> Tibero 테넌트: "Tibero 저장소 테넌트에서는 아직 Python 단계를 실행할 수 없습니다."

**용도**: Python으로 복잡한 데이터 처리 (API 호출, 데이터 가공 등)

**필수 필드**:
- scriptType: "PYTHON"
- scriptContent: Python 코드

**자동 적재 규칙**:
- stdout에 JSON 배열을 출력하면 자동으로 출력 데이터셋에 적재
- `print(json.dumps([{"col1": "val1", ...}]))` 형식
- stderr는 로그용: `print("진행 중...", file=sys.stderr)`

**pythonConfig.outputColumns**:
- 출력 컬럼 스키마 정의 (Tibero 테넌트는 Python 스텝을 실행하지 않는다)
- `{ outputColumns: [{ name: "col_name", type: "TEXT" }] }`
- 지원 타입: TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP
- stdout JSON의 키 이름이 outputColumns의 name과 일치해야 함

**환경변수** (자동 제공):
- Tibero 테넌트는 Python 단계를 실행하지 않으므로 DB 접속용 환경변수를 쓰지 않는다.
- ⚠️ 금지(저장·실행 시 차단됨): 셸 실행(`subprocess`/`os.system`/`os.popen`), 동적 코드 실행(`eval`/`exec`/`__import__`/`importlib`/`ctypes`). 데이터 가공은 pandas/numpy 로 한다.

**⚠️ 이전 스텝 temp 출력 참조 불가 (refs #654)**: PYTHON scriptContent에는 SQL의 `{{#N}}` 같은 치환이 적용되지 않고, `dependsOnStepNames` → 입력 자동 resolve도 구현되어 있지 않다(AI_CLASSIFY 전용). PYTHON 스텝이 조회할 수 있는 것은 **`inputDatasetIds`로 명시 지정된 영구(TABLE) 데이터셋**뿐이며, 조회할 실제 테이블명을 하드코딩해야 한다. 이전 스텝(SQL/PYTHON 무관)의 `outputDatasetId` 미지정 temp 출력에만 의존하는 PYTHON 스텝 설계는 현재 지원하지 않는다 — 아래 예시는 모두 체인의 **첫 스텝**(외부 API 호출)이거나 영구 데이터셋을 조회하는 경우이며, "이전 스텝 temp 출력을 이어받는 PYTHON" 예시는 의도적으로 없다. 자세한 대안은 `rules.md`의 "PYTHON 입력 데이터셋 (제약)" 절 참조.

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

> Tibero 테넌트: "Tibero 저장소 테넌트에서는 아직 API 수집 단계를 실행할 수 없습니다."

**용도**: 외부 REST API를 호출하여 데이터 수집

**필수 필드**:
- scriptType: "API_CALL"
- apiConfig: API 호출 설정 객체

**apiConfig 핵심 필드**:
- path: apiConnectionId가 있을 때 사용. baseUrl 뒤에 결합됨 (예: `/v1/users`, `/15085601/v1/uddi`)
- customUrl: apiConnectionId 없이 호출할 때의 full URL (예: `https://api.example.com/data`)
- method: GET/POST/PUT/DELETE
- dataPath: JSONPath로 데이터 배열 추출 (예: "$.data", "$.items")
- fieldMappings: 소스 필드 → 대상 컬럼 매핑 (선택)

**URL 조합 규칙**:
- apiConnectionId 있음 → `baseUrl(연결에 저장) + path` 로 최종 URL 조합. `path`는 `/`로 시작해야 함.
- apiConnectionId 없음 → `customUrl`을 그대로 사용.

**인증**:
- apiConnectionId: 저장된 API 연결 참조 (권장 — 인증 정보 암호화 저장)
- 또는 inlineAuth: 직접 인증 정보 (일회성)

**페이지네이션**:
- pagination: { type: "OFFSET", pageSize: N, offsetParam, limitParam, totalPath }

**예시 1 — 저장된 연결 사용 (apiConnectionId + path)**:
```json
{
  "name": "fetch_users",
  "scriptType": "API_CALL",
  "apiConfig": {
    "path": "/v1/users",
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

**예시 2 — 저장된 연결 없이 인라인 URL 사용 (customUrl)**:
```json
{
  "name": "fetch_public_data",
  "scriptType": "API_CALL",
  "apiConfig": {
    "customUrl": "https://api.example.com/public/data",
    "method": "GET",
    "dataPath": "$.items"
  }
}
```

## 4. AI_CLASSIFY 스텝

> Tibero 테넌트: "Tibero 저장소 테넌트에서는 아직 AI 분류 단계를 실행할 수 없습니다."

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
