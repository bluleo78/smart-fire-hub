# Phase 7-4/7-5 잔여 작업 완료 설계

## 목표

Phase 7-4 (이상 탐지 + 자동 알림) 70% → 100%, Phase 7-5 (비주얼 리포트 빌더) 85% → 100% 완료.

## 범위

### 7-4 이상 탐지 잔여 작업 (4건)

#### 4-A. 시스템 메트릭 추가 Select 버그 수정

**현상**: 이상 탐지를 활성화하고 "시스템 메트릭 추가" Select를 클릭해도 반응하지 않는다.

**수정 대상**: `apps/firehub-web/src/pages/ai-insights/tabs/JobMonitoringTab.tsx`

**원인 조사 후 수정**: Select 컴포넌트의 이벤트 전파 문제 또는 폼 상태 초기화 문제를 진단하여 수정한다.

#### 4-B. 커스텀 메트릭 추가 (모달 방식)

**UI 구조**: 기존 "시스템 메트릭 추가" Select 옆에 "커스텀 메트릭 추가" Button을 배치한다. 클릭 시 Dialog(모달)가 열린다.

**모달 필드**:
- **메트릭 이름** (필수) — 텍스트 입력. 모니터링 목록에 표시되는 이름.
- **데이터셋 선택** (필수) — Select. `/api/v1/datasets` 목록에서 선택.
- **SQL 쿼리** (필수) — Textarea 또는 CodeMirror. 숫자 1개를 반환하는 SELECT 쿼리. placeholder: `SELECT COUNT(*) FROM {테이블명} WHERE ...`
- **폴링 주기** (필수) — Number input, 기본값 600초, 최소 60초.

**저장 시 동작**: `AnomalyMetricConfig`에 `{ id: uuid, name, source: 'dataset', datasetId, query, pollingInterval }` 추가.

**파일 변경**:
- `JobMonitoringTab.tsx` — "커스텀 메트릭 추가" 버튼 + Dialog 렌더링
- `proactive-job.ts` (Zod) — dataset 메트릭 필드 유효성 (name 필수, query 필수, datasetId 필수)

**백엔드**: 기존 `AnomalyMetricConfig` 구조에 `datasetId`, `query` 필드가 이미 정의되어 있으므로 추가 백엔드 변경 없음. `MetricPollerService`에 dataset 메트릭 수집 로직을 추가한다 (executor를 통한 SQL 실행).

#### 4-C. 이상 탐지 이력 (모니터링 탭 하단)

**위치**: `JobMonitoringTab.tsx`의 읽기 전용/편집 모드 모두에서, 기존 메트릭 설정 아래에 "최근 이상 탐지" 섹션을 추가한다.

**UI 구성**:
- **섹션 헤더**: "최근 이상 탐지" + 전체 건수 Badge
- **테이블 (최근 20건)**:
  - 컬럼: 감지 시간, 메트릭 이름, 현재 값, 평균, 편차(σ), 민감도
  - 빈 상태: "감지된 이상이 없습니다"
- **메트릭 트렌드 미니 차트** (선택적, 향후 확장):
  - 첫 구현에서는 테이블만 제공. 차트는 데이터가 충분히 쌓인 후 확장.

**API**: 새 엔드포인트 `GET /api/v1/proactive/jobs/{jobId}/anomaly-events?limit=20`
- 반환: `AnomalyEventRecord[]` — `{ id, jobId, metricId, metricName, currentValue, mean, stddev, deviation, sensitivity, detectedAt }`

**백엔드**:
- 새 테이블 `anomaly_event` (V46 마이그레이션): `id`, `job_id`, `metric_id`, `metric_name`, `current_value`, `mean`, `stddev`, `deviation`, `sensitivity`, `detected_at`
- `AnomalyEventRepository`: `save()`, `findByJobId(jobId, limit)`
- `ProactiveJobController`에 `GET /anomaly-events` 엔드포인트 추가

**프론트엔드**:
- `proactive.ts`에 `AnomalyEventRecord` 타입 + `getAnomalyEvents(jobId)` API 함수 추가
- `useProactiveMessages.ts`에 `useAnomalyEvents(jobId)` TanStack Query 훅 추가
- `JobMonitoringTab.tsx`에 이력 테이블 섹션 추가

#### 4-D. AnomalyEvent → 알림 전달 연결

**현재 상태**: `MetricPollerService`가 `AnomalyEvent`를 `ApplicationEventPublisher`로 발행 → `ProactiveJobService.onAnomalyDetected()`가 수신하여 작업을 실행. 하지만 이상 탐지 자체에 대한 알림(이상 감지됨)이 없고, 이벤트가 DB에 저장되지 않는다.

**변경**:
1. `ProactiveJobService.onAnomalyDetected()`에서 작업 실행 전에:
   - `anomaly_event` 테이블에 이벤트 저장
   - `ProactiveContextCollector.addAnomalyContext()` 호출하여 작업 실행 시 이상 탐지 컨텍스트를 AI에 전달
2. SSE 알림: `NotificationService`를 통해 "이상 탐지" 타입의 실시간 알림을 사용자에게 전송 (기존 `SseEmitterRegistry` 인프라 활용)

**파일 변경**:
- `ProactiveJobService.java` — 이벤트 저장 + 컨텍스트 연결 + SSE 알림
- `AnomalyEventRepository.java` (신규) — anomaly_event CRUD
- `MetricPollerService.java` — dataset 메트릭 수집 로직 추가 (executor 연동)

---

### 7-5 비주얼 빌더 잔여 작업 (2건)

#### 5-A. 자유 정렬 + 그룹 간 이동

**현재 상태**: `useSectionTree.ts`의 `FlatItem`에 `parentKey`가 이미 있다. `moveSectionInTree()`는 `overId` 앞에 삽입하는 로직이지만, 같은 레벨에서만 동작하고 그룹 간 이동이 불완전하다.

**변경 사항**:

1. **`moveSectionInTree()` 개선** (`useSectionTree.ts`):
   - `activeId`의 현재 parentKey와 `overId`의 parentKey를 비교
   - 같은 부모: 부모의 children 배열 내에서 순서 변경
   - 다른 부모: activeId를 원래 위치에서 제거 → overId의 부모 그룹의 children에 overId 앞에 삽입
   - 루트로 이동: overId가 루트 레벨이면 루트 배열에 삽입
   - 그룹 위에 드롭: overId가 group 타입이고 activeId가 group이 아니면 해당 그룹의 children 마지막에 추가

2. **깊이 검증**: 이동 후 `validateSectionDepth()` 호출 (기존 로직 유지). 깊이 초과 시 toast 에러 + 이동 취소.

3. **DnD 시각적 피드백 개선** (`SectionTreeItem.tsx`):
   - 드래그 중인 아이템의 드롭 위치를 시각적으로 표시 (border-top 인디케이터)
   - 그룹 위에 호버 시 그룹 하이라이트 (자식으로 삽입될 것임을 표시)

**파일 변경**:
- `useSectionTree.ts` — `moveSectionInTree()` 로직 개선
- `SectionTreeBuilder.tsx` — DnD 드롭 인디케이터 (선택적)
- `SectionTreeItem.tsx` — 드래그 오버 시각적 피드백 (선택적)

#### 5-B. 구조 + 가이드 미리보기 (프론트엔드만)

**현재 상태**: `SectionPreview.tsx`는 섹션 구조를 트리 형태로 보여주지만, 실제 리포트 레이아웃과는 다르다.

**변경**: `SectionPreview`를 개선하여 각 섹션 타입별 리포트 레이아웃 프리뷰를 보여준다.

**타입별 미리보기 렌더링**:

| 섹션 타입 | 미리보기 렌더링 |
|----------|---------------|
| `text` | 블록 형태 + instruction 텍스트를 이탤릭 가이드로 표시 |
| `cards` | 2~3개 KPI 카드 플레이스홀더 (--값, 지표명) |
| `list` | 3줄 불릿 포인트 플레이스홀더 |
| `table` | 3x3 미니 테이블 스켈레톤 |
| `comparison` | 좌우 비교 카드 (이전/현재 기간) |
| `alert` | 경고 배너 스타일 플레이스홀더 |
| `timeline` | 3개 타임라인 포인트 |
| `chart` | 차트 영역 플레이스홀더 (📈 아이콘) |
| `recommendation` | 💡 추천 카드 플레이스홀더 |
| `group` | 제목 + 자식 재귀 렌더링 |
| `divider` | `<Separator>` |

각 섹션에 `instruction`이 있으면 하단에 이탤릭으로 "AI 지시: {instruction}" 표시.
`static` 섹션은 `content`를 변수 치환하여 실제 텍스트 렌더링.

**사이드패널 통합**: `TemplateSidePanel.tsx`의 "미리보기" 탭에서 이 개선된 `SectionPreview`를 사용. 빌더 탭에서 섹션을 편집하면 미리보기가 실시간으로 반영된다 (이미 sections prop으로 연결되어 있으므로 추가 작업 없음).

**파일 변경**:
- `SectionPreview.tsx` — 타입별 렌더링 로직 전면 개선
- 추가 파일 없음 (기존 컴포넌트 확장)

---

## 실행 순서

```
7-4 (BE+FE)                          7-5 (FE)
━━━━━━━━━━━━                        ━━━━━━━━━
4-D: 백엔드 (DB + 알림 연결)          5-A: 그룹 간 자유 정렬
  ↓                                  5-B: 구조 미리보기
4-C: 백엔드 API + 프론트 이력 UI
  ↓
4-A: 시스템 메트릭 버그 수정
4-B: 커스텀 메트릭 모달
```

7-4와 7-5는 완전히 독립적이므로 **병렬 진행** 가능.

## 완료 기준

- [ ] `pnpm build` 성공 (타입 체크 포함)
- [ ] `pnpm lint` 통과
- [ ] 기존 E2E 테스트 통과 (`pnpm test:e2e`)
- [ ] 신규 E2E 테스트 추가: 커스텀 메트릭 모달, 이상 탐지 이력 테이블, 그룹 내 정렬, 미리보기 렌더링
- [ ] 백엔드 테스트 통과: `AnomalyEventRepository`, `MetricPollerService` dataset 메트릭 수집
- [ ] 시스템 메트릭 추가 Select가 정상 동작 (수동 검증)

## 제약사항

- AI 생성 미리보기는 이번 범위에서 제외 (향후 확장)
- 메트릭 트렌드 차트는 이번 범위에서 제외 (테이블만 제공)
- 기존 테스트 인프라(factories, fixtures, api-mock capture) 활용
