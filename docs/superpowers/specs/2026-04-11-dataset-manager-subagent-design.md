# dataset-manager 서브에이전트 설계

- **작성일**: 2026-04-11
- **작성자**: Claude (브레인스토밍 세션)
- **상태**: 설계 승인 완료, 구현 플랜 작성 대기
- **대상 애플리케이션**: `apps/firehub-ai-agent`, `apps/firehub-api`, `apps/firehub-web`

## 1. 배경 및 목표

AI 챗으로 Smart Fire Hub 데이터 플랫폼 전체를 제어하는 로드맵의 **1단계**. 현재 ai-agent에는 `pipeline-builder`, `smart-job-manager`, `template-builder`, `report-writer` 4개의 서브에이전트만 정의되어 있어, 데이터셋 도메인은 메인 오케스트레이터가 직접 MCP 도구를 호출하는 구조다. 결과적으로 다음 문제가 발생한다.

- 메인 컨텍스트가 너무 많은 도메인 지식과 도구를 들고 있어, 복잡한 데이터셋 설계·임포트 대화에서 응답 품질이 낮다.
- Smart Fire Hub의 핵심 가치인 GIS(PostGIS) 특성을 자연어 대화에서 활용하지 못한다.
- 도메인별 전문화·튜닝·확장이 어려워 후속 에이전트(data-analyst, api-connection-manager 등) 추가 때 구조적 부담이 커진다.

**목표**: 데이터셋 도메인의 **상태 변경 및 대화형 설계 작업**을 전담하는 `dataset-manager` 서브에이전트를 신설한다. 단순 조회는 메인이 유지하고, 생성·수정·삭제·CSV 임포트·GIS 감지를 전담한다.

## 2. 추진 방식 및 로드맵 위치

### 추진 방식
- **우선순위 1개 MVP** 방식. dataset-manager를 먼저 구현하고, 실사용 피드백을 받아 후속 서브에이전트를 순차적으로 추가한다.
- 본 스펙은 dataset-manager 구현 범위만 다룬다. 후속 에이전트(data-analyst, api-connection-manager, trigger-manager, dashboard-builder, admin-manager, audit-analyst)는 별도 스펙으로 분리한다.

### 로드맵
`docs/ROADMAP.md`에 신규 Phase **"AI 챗 데이터 플랫폼 전면 제어"** 를 추가한다. Phase 번호는 현재 구조(5.9 / Phase 6 등) 확인 후 구현 플랜에서 확정한다.

예상 체크리스트:
- `dataset-manager` 서브에이전트 (본 스펙)
- `data-analyst` 서브에이전트
- `api-connection-manager` 서브에이전트
- `trigger-manager` 서브에이전트 또는 pipeline-builder 확장
- `dashboard-builder` 서브에이전트
- `admin-manager` / `audit-analyst` 서브에이전트 (권한 게이팅 포함)

## 3. 아키텍처

### 코드 위치
```
apps/firehub-ai-agent/src/agent/subagents/dataset-manager/
├── agent.md       # name, description, 시스템 프롬프트(역할·위임 정책·게이팅 규칙·GIS 가이드)
├── rules.md       # 파괴 작업 체크리스트, 타입 매핑 규칙
└── examples.md    # 대화 샘플 (설계, 임포트, GIS 감지, 삭제 확인)
```

기존 `pipeline-builder`가 `agent.md` + `rules.md` + `examples.md` + `step-types.md`를 쓰는 패턴을 따른다. `subagent-loader.ts`가 디렉터리를 자동 로딩하므로 별도 등록 코드는 필요 없다.

### 위임 정책
`agent.md`의 description 필드에 명시한다.

> "데이터셋 생성·수정·삭제·컬럼 변경·CSV/XLSX 임포트를 대화형으로 수행하는 전문 에이전트. 단순 조회(목록, 상세, 스키마 확인)는 위임하지 마세요. 공간 데이터 감지 시 GEOMETRY 컬럼과 SRID 4326을 자동 제안합니다."

- **위임 대상**: 상태 변경, 대화형 설계, CSV/XLSX 임포트
- **위임 제외(메인이 직접 처리)**: `list_datasets`, `get_dataset`, 즐겨찾기 토글, 최근 사용 조회 등 단건 조회

## 4. 기능 범위

### 4.1 포함 작업

| # | 작업 | MCP 도구 | 도구 상태 |
|---|------|---------|----------|
| 1 | 데이터셋 생성 (스키마 설계 대화 포함) | `create_dataset` | 기존 |
| 2 | 데이터셋 메타 수정 (이름·설명·카테고리·태그) | `update_dataset` | 기존 |
| 3 | 데이터셋 삭제 | `delete_dataset` | **신규** |
| 4 | 컬럼 추가 | `add_dataset_column` | **신규** (또는 기존 `update_dataset` 확장 확인 후 결정) |
| 5 | 컬럼 제거 | `drop_dataset_column` | **신규** (동일) |
| 6 | 카테고리 CRUD, 태그 부여 | `category-tools` 기존 도구 | 기존 |
| 7 | CSV/XLSX 임포트 (미리보기·매핑·검증·적재·상태) | `preview_csv`, `propose_schema_from_csv`, `start_import`, `import_status` | 일부 신규 |
| 8 | 데이터 미리보기/통계 | `preview_dataset_rows`, `dataset_stats` | 기존(`data-tools`) / 일부 신규 |
| 9 | 참조 관계 조회 (삭제 전 영향 범위 확인) | `get_dataset_references` | **신규** |

### 4.2 제외 (메인이 직접 처리)
- `list_datasets`, `get_dataset`
- 즐겨찾기 토글, 최근 사용 조회
- 행 단위 편집(INSERT/UPDATE/DELETE) — 향후 `data-analyst` 영역

## 5. 파괴 작업 안전장치

### 5.1 2단계 방어
**1단계 — 권한 기반 도구 노출**
- MCP 서버가 세션 사용자 권한을 기반으로 파괴 도구를 **동적으로 노출/차단**
- `dataset:delete` 권한 없으면 `delete_dataset`, `drop_dataset_column`, REPLACE 임포트 도구가 에이전트 도구 목록에 아예 포함되지 않음
- 기존 MCP 도구 등록 함수에 `userPermissions` 주입 (`proactive-tools` 등 기존 패턴 재활용 또는 확장)

**2단계 — 평문 확인 게이팅 (시스템 프롬프트)**
`agent.md` 및 `rules.md`에 강제 규칙 명시.

```
## 파괴 작업 체크리스트 (반드시 준수)
다음 작업 전에는 반드시 사용자의 명시적 평문 확인을 받아야 한다:
1. 데이터셋 삭제
2. 컬럼 삭제
3. REPLACE 전략 임포트 (기존 행 전부 덮어쓰기)

## 확인 요구 형식
- 삭제 대상을 이름과 핵심 속성으로 명시
  (예: "custom.fire_incidents (행 12,453개, 3개 파이프라인에서 참조)")
- 복구 불가 명시
- 사용자가 "네, 삭제하세요" 류의 명시적 평문을 답할 때만 실행
- "삭제해줘"만으로는 실행하지 말고 한 번 더 확인
- 실행 직후 결과 요약 리포트
```

### 5.2 참조 검증
삭제 전 영향 범위 확인을 위해 `get_dataset_references` 도구를 신설한다. 해당 도구는 지정 데이터셋을 참조하는 **파이프라인·대시보드·스마트잡**의 개수와 이름 목록을 반환한다. 참조가 존재하면 에이전트는 반드시 사용자에게 명시하고 재확인을 받는다.

### 5.3 감사 로그
- 백엔드 `audit` 모듈이 이미 DELETE 작업을 자동 기록하는지 구현 플랜 단계에서 확인한다.
- 누락 시 `DatasetController` 등 해당 컨트롤러에 감사 로그 등록을 추가한다.

## 6. CSV/XLSX 임포트 워크플로 (챗 주도)

### 6.1 전제
- 기존 채팅 인프라의 파일 첨부(`fileIds`) 흐름을 재사용한다.
- 백엔드 `dataimport` 모듈이 파싱·검증·Jobrunr 적재를 처리한다.
- 파일 크기 상한 및 파싱 정책은 백엔드 기존 제한을 따른다.

### 6.2 대화 흐름
1. **감지**: 사용자 메시지에 파일 첨부 + "데이터셋 만들어줘" 류 발화 → 임포트 의도로 판정.
2. **미리보기**: `preview_csv`로 첫 50~100행 및 컬럼 타입 추론 결과를 수신한다.
3. **대상 결정**:
   - **신규 데이터셋**: 이름·카테고리·컬럼 타입 대화 설계. GIS 자동 감지 시 `GEOMETRY(Point, 4326)` 및 GiST 인덱스 제안.
   - **기존 데이터셋 매핑**: 후보 데이터셋 제안 및 컬럼 매핑 (이름·타입 유사도 기반).
4. **매핑 제안**: 타입 추론 + GIS 감지 결과를 요약하여 사용자 확인 요청.
5. **검증 미리보기**: 상위 N행을 매핑된 스키마로 파싱해 에러 건수와 샘플을 제시한다.
6. **적재 전략 선택**: `APPEND`(기본) 또는 `REPLACE`. `REPLACE`는 5절 파괴 작업 게이팅 적용.
7. **적재 시작**: `start_import` 호출 후 `jobId` 반환.
8. **진행 상태**: 에이전트는 "적재 시작됨, jobId=...`" 를 사용자에게 안내하고 종료한다. 이후 완료/에러는 기존 UI 알림 채널을 통해 전달된다. 사용자가 재질문하면 `import_status`로 즉시 조회한다.

### 6.3 GIS 자동 감지 규칙 (`rules.md`에 명시)
- 컬럼명 패턴: `lat`, `latitude`, `lng`, `lon`, `longitude`, `x`, `y`, `geom`, `geometry`, `location`
- 데이터 포맷 패턴: WKT 문자열 (`POINT(...)`, `POLYGON(...)`), GeoJSON 문자열
- 감지 시 추천: 단일 `GEOMETRY(Point, 4326)` 컬럼 + GiST 인덱스
- 사용자 거부 시 일반 `NUMERIC(9,6)` 등 일반 컬럼으로 대체

### 6.4 상태 관리
- 멀티턴 대화 상태는 Claude SDK 대화 히스토리에 의존한다. 별도 세션 스토어를 두지 않는다.
- 토큰 압박 완화를 위해 시스템 프롬프트에 **"최신 제안 스키마를 간결한 JSON 요약 형태로 유지"** 지시를 포함한다.

### 6.5 신규 MCP 도구 후보

| 도구 | 역할 | 백엔드 대응 |
|------|------|-----------|
| `preview_csv` | 파일 파싱, 첫 N행 + 컬럼 타입 추론 | 기존 `dataimport` 미리보기 API 확인. 없으면 신설 |
| `propose_schema_from_csv` | 타입·GIS 추론 결과 반환 | 에이전트 측 순수 로직 가능. 백엔드 API 선택 |
| `start_import` | 적재 시작 | 기존 `POST /api/v1/imports` 활용 |
| `import_status` | 적재 진행 상태 조회 | 기존 job 상태 API |

구현 플랜 단계에서 실제 백엔드 엔드포인트 존재 여부를 조사해 매핑을 확정한다.

## 7. 신규 MCP 도구 총정리 (구현 플랜에서 API 검증 필요)

| 도구 | 목적 | 권한 | 백엔드 |
|------|------|------|--------|
| `delete_dataset` | 데이터셋 삭제 | `dataset:delete` | 기존 `DELETE` API 확인됨 |
| `add_dataset_column` | 컬럼 추가 | `dataset:update` | 기존 여부 확인 |
| `drop_dataset_column` | 컬럼 제거 | `dataset:delete` 또는 전용 권한 | 기존 여부 확인 |
| `get_dataset_references` | 참조 관계 조회 | `dataset:read` | 신규 작성 가능성 큼 |
| `preview_csv` | CSV 미리보기 | `dataset:create` 또는 `dataimport:create` | 확인 필요 |
| `propose_schema_from_csv` | 스키마·GIS 추론 | `dataset:read` | 에이전트 측 로직 가능 |
| `start_import` | 임포트 시작 | `dataimport:create` | 확인 필요 |
| `import_status` | 진행 상태 | `dataimport:read` | 확인 필요 |

## 8. 테스트 전략

### 8.1 ai-agent 단위 테스트 (Vitest)
- `subagent-loader.test.ts`에 `dataset-manager` 디렉터리 로딩 케이스 추가
- `dataset-tools.test.ts` 신설: 신규 MCP 도구 각각의 input/output 스키마 검증
- 권한 없는 세션에서 파괴 도구가 **등록되지 않음**을 확인하는 케이스 추가

### 8.2 firehub-api 통합 테스트 (JUnit, IntegrationTestBase)
- `DatasetService.delete()`: 정상, 참조 존재, 권한 부족
- 컬럼 추가·삭제 DDL 경로: 정상, 존재하지 않는 컬럼 404
- 참조 조회 서비스: 파이프라인·대시보드·스마트잡 카운트 정확성
- 임포트 관련 신규 API가 있으면 각각 테스트

### 8.3 E2E (Playwright)
- **신규 데이터셋 생성 + GIS 감지**: 로그인 → 챗 패널 → 위경도 포함 CSV 첨부 → "지역별 화재 데이터셋 만들어줘" → GEOMETRY 제안 확인 → 확인 → 적재 시작 응답 확인. 스크린샷 저장.
- **삭제 확인 흐름**: 로그인 → "X 데이터셋 삭제해줘" → 참조·확인 프롬프트 확인 → "네, 삭제하세요" → 완료 응답 확인.
- 스크린샷은 `snapshots/` 하위에 저장한다.

## 9. 수용 기준

- [ ] `dataset-manager` 서브에이전트가 로더에 등록되고 메인이 상태 변경 요청을 위임한다
- [ ] 단순 조회 요청은 메인이 직접 처리해 위임이 발생하지 않는다
- [ ] 신규 MCP 도구 전부 타입 정의 및 단위 테스트를 포함한다
- [ ] 권한이 없는 사용자 세션에서 파괴 도구가 에이전트 도구 목록에 노출되지 않는다
- [ ] 파괴 작업 시 사용자의 평문 확인 없이 실행되지 않는다
- [ ] lat/lng 계열 컬럼 자동 감지 시 GEOMETRY 컬럼을 제안한다
- [ ] CSV 임포트 워크플로가 챗 내에서 끝까지 완주한다 (신규 데이터셋 생성, 매핑, 적재, 완료 안내)
- [ ] 삭제 시 참조 관계가 사용자에게 고지된다
- [ ] 감사 로그에 삭제 이벤트가 기록된다
- [ ] `docs/ROADMAP.md`에 신규 Phase 및 dataset-manager 항목이 추가되고 본 작업 완료 시 ✅ 표시된다
- [ ] 모든 단위·통합·E2E 테스트가 통과한다

## 10. 비범위

- `data-analyst`, `api-connection-manager`, `trigger-manager`, `dashboard-builder`, `admin-manager`, `audit-analyst` 등 후속 서브에이전트
- 행 단위 데이터 편집 (INSERT/UPDATE/DELETE)
- 수백만 행 이상 대용량 특수 최적화
- 서브에이전트 공통 프레임워크 리팩터링 (현재 패턴 재사용으로 충분)

## 11. 오픈 이슈 (구현 플랜 단계에서 해결)

1. 임포트 관련 백엔드 엔드포인트(미리보기·적재·상태) 실제 존재 여부 및 경로
2. `get_dataset_references`용 백엔드 쿼리 구현 위치 (DatasetService vs 신규 Service)
3. `update_dataset`가 컬럼 추가·삭제까지 이미 다루는지, 아니면 별도 도구 필요 여부
4. 신규 로드맵 Phase 번호 (5.10 / 9 / 기타)
5. MCP 권한 주입 기존 패턴 확인 및 확장 범위
