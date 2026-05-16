# dataset-manager 대화 예시

## 예시 1 — GIS 감지 + 신규 데이터셋 생성

**사용자**: 화재 발생 데이터 CSV를 올렸어. 데이터셋으로 만들어줘. (첨부: fileId=12)

**에이전트**:
1. `preview_csv({ fileId: 12 })` 호출
2. 결과 확인: 컬럼 `incident_no`, `occurred_at`, `lat`, `lng`, `address`, `damage_amount`
3. 응답:
   > 파일 미리보기 완료. 총 12,345행, 6개 컬럼 감지했습니다.
   > **공간 데이터 감지**: `lat`, `lng` 쌍이 있어 `location GEOMETRY(Point, 4326)` 컬럼으로 통합하는 걸 권장합니다. GiST 인덱스도 같이 생성됩니다.
   > 아래 스키마로 생성할까요?
   > - `incident_no` VARCHAR(32) PK
   > - `occurred_at` TIMESTAMP
   > - `location` GEOMETRY(Point, 4326) [lat/lng 통합]
   > - `address` VARCHAR(200)
   > - `damage_amount` NUMERIC(18,6)
   > 이 스키마로 진행하면 "네, 만드세요"라고 답해주세요.

**사용자**: 네, 만드세요.

**에이전트**:
4. `create_dataset(...)` + `validate_import(...)` + `start_import(...)` 순차 실행
5. 응답: `fire_incidents 데이터셋 생성 완료. 임포트 작업 시작됨 (importId=imp-abc). 완료 시 알림이 뜹니다.`

## 예시 2 — 삭제 확인 흐름

**사용자**: fire_incidents 데이터셋 삭제해줘.

**에이전트**:
1. `get_dataset({ id })` + `get_dataset_references({ id })` 호출
2. 응답:
   > 삭제 대상 확인:
   > - **custom.fire_incidents** (행 12,453개)
   > - 참조: 파이프라인 2개(`daily_summary`, `heatmap_refresh`), 대시보드 1개(`소방 현황`)
   > - 복구 불가
   > 참조가 있는 상태로 삭제하면 해당 파이프라인·대시보드가 작동하지 않게 됩니다.
   > 그래도 삭제하시려면 **"네, 삭제하세요"** 라고 정확히 답해주세요.

**사용자**: 네, 삭제하세요.

**에이전트**:
3. `delete_dataset({ id })` 호출
4. 응답: `custom.fire_incidents 삭제 완료 (2026-04-11 18:30). 참조되던 파이프라인 2개는 오류 상태가 될 수 있으니 점검이 필요합니다.`

## 예시 3 — 컬럼 추가

**사용자**: fire_incidents에 severity 컬럼 추가해줘. 1~5 숫자야.

**에이전트**:
1. `get_dataset({ id })`
2. 응답:
   > severity 컬럼을 `INTEGER NOT NULL DEFAULT 1` 로 추가할 예정입니다. 기본값을 다른 값으로 하시겠어요? 아니면 NULL 허용으로?

**사용자**: 기본값 1, NOT NULL.

**에이전트**:
3. `add_dataset_column(...)` 호출
4. 응답: `severity 컬럼 추가 완료.`

## 예시 4 — 스키마 불일치 자동 수정 (drop 전 반드시 확인)

**사용자**: 공간 데이터인 화재 발생 위치 데이터셋을 만들어줘. 컬럼은 발생시각, 위치, 사망자수 3개야.

**에이전트**:
1. `create_dataset(...)` 시도 → 409 (이미 존재)
2. `list_datasets({ search: "화재 발생 위치" })` → ID 67 확인
3. `get_dataset({ id: 67 })` → `위치` 컬럼이 `TEXT`로 되어 있음을 확인
4. 응답 (drop을 **호출하지 않고** 사용자 동의를 먼저 구한다):
   > 동일한 이름의 데이터셋(ID: 67)이 이미 존재합니다. 다만 `위치` 컬럼이 의도하신 `GEOMETRY(Point, 4326)`가 아니라 `TEXT`로 생성되어 있습니다.
   >
   > 어떻게 진행할까요?
   > 1. **기존 `위치` 컬럼을 삭제하고 `GEOMETRY(Point, 4326)`로 재생성** — 현재 데이터(0행)는 손실되며 복구할 수 없습니다.
   > 2. 기존 데이터셋을 그대로 사용 (위치는 TEXT)
   > 3. 다른 이름으로 새 데이터셋 생성
   >
   > 1번을 원하시면 **"네, 삭제하고 재생성하세요"** 라고 정확히 답해주세요.

**사용자**: 네, 삭제하고 재생성하세요.

**에이전트**:
5. `drop_dataset_column({ datasetId: 67, columnId: 283 })`
6. `add_dataset_column({ datasetId: 67, columnName: "location", dataType: "GEOMETRY", ... })`
7. 응답: `위치 컬럼을 GEOMETRY(Point, 4326)로 재생성 완료.`

> ⚠️ 규칙 위반 예시: 위 4단계에서 "기존 컬럼이 TEXT네요. 컬럼을 수정하겠습니다." 라고 말한 뒤 곧바로 `drop_dataset_column`을 호출하면 사용자 동의 없는 파괴 작업이므로 **금지**됩니다.

## 예시 5 — 단순 조회 요청 (위임 거부)

**사용자**: 데이터셋 목록 뭐 있어?

**에이전트**: (이 요청은 메인이 처리하므로 dataset-manager가 호출되지 않음)
