# 파이프라인 규칙 및 제약사항

## DAG (방향 비순환 그래프)
- 스텝 간 의존성은 `dependsOnStepNames`로 설정
- **순환 의존성 불가**: A→B→C→A 같은 순환은 생성 시 에러
- **자기 참조 불가**: {{#N}}에서 자기 스텝 번호 사용 불가
- 의존하는 스텝이 FAILED/SKIPPED면 해당 스텝도 SKIPPED

## {{#N}} 참조 규칙
- N은 **1부터 시작** (0이 아님!)
- N은 steps 배열 내 순서 기준 (첫 번째 스텝 = {{#1}})
- SQL scriptContent 내에서만 사용
- 실행 시 `data."ptmp_{pipelineId}_{stepname}_{hash}"` 형태로 치환
- 참조하는 스텝이 출력 데이터셋을 가져야 함 (temp 자동 생성 포함)

## 출력 데이터셋
- outputDatasetId 지정: 해당 데이터셋에 결과 적재
- outputDatasetId 미지정: TEMP 타입 임시 데이터셋 자동 생성
  - SQL SELECT: 쿼리 컬럼에서 스키마 자동 추론
  - Python: pythonConfig.outputColumns에서 스키마 결정
  - AI_CLASSIFY: aiConfig.outputColumns에서 스키마 결정
- temp 데이터셋은 {{#N}}으로 다른 스텝에서 참조 가능

### 예약 컬럼 (자동 추가됨 — 절대 SELECT하지 마세요!)
데이터셋 생성 시 시스템이 자동으로 다음 컬럼을 추가합니다:
- `id` (BIGSERIAL PRIMARY KEY)
- `import_id` (BIGINT)
- `created_at` (TIMESTAMP DEFAULT NOW())

**따라서 SQL SELECT에서 `id`, `import_id`, `created_at` 컬럼을 포함하면 "column specified more than once" 에러가 발생합니다.**

방지법:
- 소스 테이블의 `id` 컬럼이 필요하면 반드시 별칭 사용: `SELECT id AS source_id, ...`
- `import_id`, `created_at`도 마찬가지로 별칭 사용
- `SELECT *` 대신 필요한 컬럼만 명시적으로 나열하세요

## AI_CLASSIFY 입력 데이터셋
AI_CLASSIFY 스텝의 입력 데이터는 두 가지 방법으로 지정합니다:

1. **inputDatasetIds로 명시적 지정**: 기존 데이터셋 ID를 직접 지정
2. **dependsOnStepNames에서 자동 resolve** (권장): inputDatasetIds를 비우고
   dependsOnStepNames만 설정하면, 실행 시 이전 스텝의 출력 데이터셋이 자동으로
   입력으로 사용됩니다. temp 데이터셋도 자동 resolve됩니다.

파이프라인 생성 시 AI_CLASSIFY에 inputDatasetIds를 지정하지 마세요.
dependsOnStepNames만 설정하면 됩니다.

## loadStrategy
- REPLACE (기본): 실행 전 기존 데이터 전체 삭제 → 새 데이터 삽입
- APPEND: 기존 데이터 유지 + 새 데이터 추가

## 보안
- API_CALL: SSRF 보호 (사설 IP 127.0.0.1, 10.x, 172.16-31.x, 192.168.x 차단)
- Python: `pipeline:python_execute` 권한 필요 (ADMIN 역할에 포함)
- SQL: DML만 허용 (DDL 불가), 30초 타임아웃
- API 연결 인증 정보는 AES-256-GCM 암호화 저장

## 트리거
- SCHEDULE: cronExpression (예: "0 0 9 * * ?" = 매일 오전 9시)
- API: 자동 생성 토큰으로 외부 호출
- PIPELINE_CHAIN: 상위 파이프라인 완료 시 자동 실행
- WEBHOOK: UUID + 선택적 HMAC-SHA256 검증
- DATASET_CHANGE: 데이터셋 변경 감지 (30초 폴링)

## 흔한 실수와 방지법
| 실수 | 원인 | 방지 |
|------|------|------|
| column does not exist | 스키마 미확인 | Phase 1에서 get_data_schema 필수 |
| INSERT INTO 직접 작성 | SQL 자동 적재 미인지 | SELECT만 작성 (자동 INSERT) |
| {{#0}} 사용 | 0-indexed 착각 | {{#1}}부터 시작 |
| 순환 의존성 | DAG 미검증 | 설계 시 의존 그래프 확인 |
| temp 데이터셋 수동 생성 | 자동 생성 미인지 | outputDatasetId 미지정으로 자동 생성 |
| column "id" specified more than once | SELECT에 id/import_id/created_at 포함 | 예약 컬럼은 별칭 사용 (id → source_id) |
| AI_CLASSIFY "No input rows found" | inputDatasetIds가 비어있고 의존 스텝 없음 | dependsOnStepNames로 이전 스텝 연결 (자동 resolve) |
