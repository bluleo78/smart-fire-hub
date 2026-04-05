# 실행 상세 페이지 + 리포트 모달 설계

> **날짜**: 2026-04-05
> **Phase**: 7 (AI 리포트 고도화) — UX 개선
> **범위**: Frontend (firehub-web) + Backend (firehub-api, 단건 조회 API 1개)

---

## 배경

현재 스마트 작업의 실행 이력은 `JobExecutionsTab`에서 상하 분할(테이블 220px + 하단 상세)로 표시된다. 이 방식은:
- 테이블이 220px로 압축되어 목록 탐색이 불편
- 리포트가 하단 영역에 갇혀 좁음
- 파이프라인(사이드 패널) 등 다른 실행 이력 패턴과 비일관

리포트 뷰어로의 진입점이 3곳(실행 이력 탭, AI 챗 메시지, 알림 패널)이지만 모두 동일하게 `ReportViewerPage`로 이동하여, 진입 맥락에 맞는 최적화된 경험을 제공하지 못한다.

## 설계 목표

1. 실행 이력 클릭 시 **전용 상세 페이지**로 이동하여 메타정보 + 요약 + 리포트를 한 화면에서 제공
2. AI 챗/알림에서는 **리포트 모달**로 현재 화면을 유지하며 빠르게 확인
3. 이메일/외부 링크에서는 기존 **ReportViewerPage** 유지 (리포트만 전체 화면)
4. 리포트 렌더링 로직(iframe + sandbox) 공통 재사용

---

## 진입 경로별 뷰

| 진입점 | 목적지 | 보여주는 것 | 다음 동선 |
|--------|--------|------------|----------|
| 실행 이력 테이블 행 클릭 | **실행 상세 페이지** (신규) | 메타 카드 + 요약 + 리포트 인라인 | "뒤로" → 실행 이력 목록 |
| AI 챗 / 알림 패널 "리포트 보기" | **리포트 모달** (신규) | 리포트만 (오버레이) | 닫기 → 원래 화면, "실행 상세 보기" → 실행 상세 페이지 |
| 이메일 / 외부 URL | **ReportViewerPage** (기존 유지) | 리포트만 (전체 화면, 로그인 필요) | "작업 상세 보기" → 스마트 작업 상세 페이지 |

---

## 1. 실행 상세 페이지 (신규)

### 라우트

```
/ai-insights/jobs/:jobId/executions/:executionId
```

`App.tsx`에 `ProtectedRoute` 하위로 추가. lazy-load.

### 레이아웃 — 카드형

```
┌─────────────────────────────────────────────────────┐
│ ← 뒤로  │  실행 #1234                               │  ← 고정 헤더
├─────────────────────────────────────────────────────┤
│  ┌──────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐  │
│  │ 상태 │ │ 실행시간  │ │ 소요시간  │ │ 전달채널   │  │  ← 메타 카드 (4칸 그리드)
│  └──────┘ └──────────┘ └──────────┘ └───────────┘  │
│                                                     │
│  ┌─ 요약 ──────────────────────────────────────┐    │
│  │ 마크다운 요약 텍스트                          │    │  ← 요약 섹션 (다크 배경)
│  └─────────────────────────────────────────────┘    │
│                                                     │
│  ┌─ 리포트 ──────────────────── [🖨 인쇄] [⬇ PDF] ┐ │
│  │                                                 │ │  ← 리포트 섹션 (라이트 배경 iframe)
│  │  HTML 리포트 (iframe srcdoc, sandbox)           │ │
│  │                                                 │ │
│  └─────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

### 상태별 결과 영역

| 상태 | 메타 카드 | 결과 영역 |
|------|----------|----------|
| **COMPLETED + htmlContent** | 상태=완료(green) | 요약 섹션 + 리포트 섹션 (iframe) + 인쇄/PDF 버튼 |
| **COMPLETED + htmlContent 없음** | 상태=완료(green) | 마크다운 섹션 (기존 sections 렌더링) + PDF 버튼 |
| **FAILED** | 상태=실패(red) | 에러 분류 카드 (아이콘 + 에러 메시지 + 가이드) |
| **RUNNING** | 상태=실행 중(blue), 소요시간="진행 중..." | 로딩 스피너 + "리포트를 생성하고 있습니다..." + 5초 자동 폴링 |

### 데이터 조회

- 단건 실행 조회 API 필요: `GET /api/v1/proactive/jobs/{jobId}/executions/{executionId}`
  - 기존 목록 API(`GET /api/v1/proactive/jobs/{jobId}/executions`)에서 단건 필터링도 가능하나, 전용 단건 API가 깔끔
  - 응답: 기존 `ProactiveJobExecution` DTO 그대로
- HTML 리포트: 기존 `GET /api/v1/proactive/jobs/{jobId}/executions/{executionId}/html` 재사용
- RUNNING 상태일 때 `refetchInterval: 5000`으로 폴링

### 컴포넌트 구조

```
ExecutionDetailPage.tsx (신규)
├── 헤더 (뒤로 + 실행 번호)
├── ExecutionMetaCards (신규) — 4칸 그리드 메타정보
├── ExecutionResultSection (기존 ExecutionResultView 리팩터링)
│   ├── 성공: SummarySection + ReportSection
│   ├── 실패: ErrorClassificationCard
│   └── 실행 중: LoadingState
└── ReportIframe (공통 컴포넌트, 신규)
    └── iframe srcdoc + sandbox="allow-same-origin"
```

---

## 2. 리포트 모달 (신규)

### 트리거

- `ProactiveMessage` (AI 챗 카드) — "리포트 보기" 클릭
- `AINotificationPanel` (알림 패널) — 리포트 링크 클릭

### 구현

shadcn `Dialog` 컴포넌트 기반. 최대 너비 `max-w-4xl`, 높이 `80vh`.

```
┌─────────────────────────────────────────────┐
│  리포트 #1234  ✓완료  14:30 · 2분 34초       │
│                    [🖨 인쇄] [⬇ PDF] [↗ 새탭] [✕] │
├─────────────────────────────────────────────┤
│                                             │
│  HTML 리포트 (iframe srcdoc, sandbox)        │
│  (라이트 배경)                               │
│                                             │
├─────────────────────────────────────────────┤
│  [실행 상세 보기 →]                           │  ← 하단 링크
└─────────────────────────────────────────────┘
```

### 기능

- **헤더**: 실행 번호 + 상태 뱃지 + 시간 정보 + 액션 버튼
- **액션**: 인쇄, PDF 다운로드, 새 탭에서 열기(→ ReportViewerPage), 닫기
- **하단 링크**: "실행 상세 보기" → 모달 닫기 후 실행 상세 페이지로 navigate
- **닫기**: ESC 키 또는 오버레이 클릭 또는 ✕ 버튼
- **HTML 없는 경우**: 마크다운 렌더링으로 폴백

### 컴포넌트

```
ReportModal.tsx (신규)
├── Dialog (shadcn)
├── 헤더 (메타 정보 압축 + 액션 버튼)
├── ReportIframe (공통 컴포넌트 재사용)
└── 하단 링크 (실행 상세 페이지)
```

---

## 3. 기존 변경 사항

### JobExecutionsTab.tsx 변경

- 하단 상세 영역(`ExecutionResultView`) **제거**
- `selectedId` 상태 **제거**
- 행 클릭 시 `navigate(`/ai-insights/jobs/${jobId}/executions/${exec.id}`)` 호출
- 테이블이 전체 높이를 사용하도록 레이아웃 변경 (상하 분할 → 단일 테이블)

### ProactiveMessage.tsx 변경

- "리포트 보기" `<Link>` → 리포트 모달 트리거 (onClick으로 변경)

### AINotificationPanel.tsx 변경

- 리포트 링크 → 리포트 모달 트리거 (onClick으로 변경)

### ReportViewerPage.tsx

- **변경 없음**. 외부 링크 / 모달의 "새 탭" 버튼용으로 유지.
- 기존 라우트 `/ai-insights/jobs/:jobId/executions/:executionId/report` 유지.

### App.tsx 라우트 추가

```tsx
<Route path="/ai-insights/jobs/:jobId/executions/:executionId" element={<ExecutionDetailPage />} />
```

---

## 4. 공통 컴포넌트: ReportIframe

세 곳(실행 상세 페이지, 리포트 모달, ReportViewerPage)에서 재사용.

```tsx
interface ReportIframeProps {
  html: string;
  className?: string;
  onPrint?: () => void;  // iframe ref를 통한 인쇄 콜백
}
```

- `iframe srcdoc={html}` + `sandbox="allow-same-origin"` (XSS 방지 + 인쇄 허용)
- `ReportViewerPage`에서도 이 컴포넌트로 리팩터링하여 중복 제거

---

## 5. 백엔드 변경

### 단건 실행 조회 API (신규)

```
GET /api/v1/proactive/jobs/{jobId}/executions/{executionId}
```

- 응답: `ProactiveJobExecution` (기존 DTO)
- 인증: JWT Bearer
- 에러: 404 (존재하지 않는 실행), 403 (권한 없음)

기존 `ProactiveJobController`에 엔드포인트 추가. `ProactiveJobService`에서 단건 조회 메서드 추가.

---

## 6. 검증 기준

| # | 검증 항목 | 방법 |
|---|----------|------|
| 1 | 실행 이력 행 클릭 → 실행 상세 페이지 이동 | Playwright E2E |
| 2 | 상세 페이지 메타 카드 4칸 정상 표시 | 스크린샷 |
| 3 | 성공 실행: 요약 + 리포트 인라인 표시 | 스크린샷 |
| 4 | 실패 실행: 에러 분류 카드 표시 | 스크린샷 |
| 5 | 실행 중: 로딩 상태 + 자동 폴링 | 수동 검증 |
| 6 | AI 챗 "리포트 보기" → 리포트 모달 표시 | Playwright E2E |
| 7 | 모달 닫기 (ESC / 오버레이 / ✕) 정상 동작 | Playwright E2E |
| 8 | 모달 "실행 상세 보기" → 상세 페이지 이동 | Playwright E2E |
| 9 | 이메일 링크 → ReportViewerPage 정상 표시 | 수동 검증 |
| 10 | PDF 다운로드 (상세 페이지 + 모달) | 수동 검증 |
| 11 | 단건 실행 조회 API 정상 응답 | 백엔드 통합 테스트 |
| 12 | 빌드 + 타입체크 통과 | `pnpm build && pnpm typecheck` |

---

## 목업

Visual Companion 목업 파일: `.superpowers/brainstorm/98909-1775374846/content/`
- `execution-detail-layout.html` — 레이아웃 A/B 비교 (A 카드형 선택)
- `execution-detail-states.html` — 상태별 결과 영역
- `execution-detail-inline-report.html` — 요약 + 리포트 인라인
- `report-modal.html` — 리포트 모달 + 진입 경로 정리
