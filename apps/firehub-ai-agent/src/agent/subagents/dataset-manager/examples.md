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

## 예시 4 — 단순 조회 요청 (위임 거부)

**사용자**: 데이터셋 목록 뭐 있어?

**에이전트**: (이 요청은 메인이 처리하므로 dataset-manager가 호출되지 않음)
