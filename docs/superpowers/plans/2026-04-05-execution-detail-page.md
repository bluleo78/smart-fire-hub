# 실행 상세 페이지 + 리포트 모달 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실행 이력 클릭 시 전용 상세 페이지로 이동하고, AI 챗/알림에서는 리포트 모달로 표시하며, 외부 링크는 기존 ReportViewerPage를 유지한다.

**Architecture:** 백엔드에 단건 실행 조회 API 1개 추가. 프론트엔드에 실행 상세 페이지, 리포트 모달, ReportIframe 공통 컴포넌트를 신규 생성하고, 기존 JobExecutionsTab을 테이블 전용으로 간소화한다.

**Tech Stack:** Spring Boot (jOOQ), React 19, TanStack Query, shadcn/ui Dialog, iframe srcdoc

**설계 스펙:** `docs/superpowers/specs/2026-04-05-execution-detail-page-design.md`

---

## 파일 구조

### 신규 생성
| 파일 | 역할 |
|------|------|
| `apps/firehub-web/src/pages/ai-insights/ExecutionDetailPage.tsx` | 실행 상세 페이지 (카드형 메타 + 요약 + 리포트) |
| `apps/firehub-web/src/components/ai/ReportIframe.tsx` | iframe srcdoc 공통 컴포넌트 |
| `apps/firehub-web/src/components/ai/ReportModal.tsx` | 리포트 모달 (shadcn Dialog) |

### 수정
| 파일 | 변경 내용 |
|------|----------|
| `apps/firehub-api/.../controller/ProactiveJobController.java` | 단건 실행 조회 엔드포인트 추가 |
| `apps/firehub-api/.../service/ProactiveJobServiceTest.java` | 단건 조회 테스트 추가 |
| `apps/firehub-web/src/api/proactive.ts` | `getExecution()` API 함수 추가 |
| `apps/firehub-web/src/hooks/queries/useProactiveMessages.ts` | `useExecution()` 쿼리 훅 추가 |
| `apps/firehub-web/src/App.tsx` | 실행 상세 페이지 라우트 추가 |
| `apps/firehub-web/src/pages/ai-insights/tabs/JobExecutionsTab.tsx` | 하단 상세 영역 제거, 행 클릭 → navigate |
| `apps/firehub-web/src/components/ai/ProactiveMessage.tsx` | Link → 리포트 모달 트리거 |
| `apps/firehub-web/src/components/ai/AINotificationPanel.tsx` | Link → 리포트 모달 트리거 |
| `apps/firehub-web/src/pages/ai-insights/ReportViewerPage.tsx` | ReportIframe 공통 컴포넌트로 리팩터링 |

---

## Task 1: 백엔드 — 단건 실행 조회 API

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/controller/ProactiveJobController.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ProactiveJobServiceTest.java`

- [ ] **Step 1: 통합 테스트 작성**

`ProactiveJobServiceTest.java` 파일 끝에 추가:

```java
/** 단건 실행 조회 — 정상 케이스 */
@Test
void getExecution_returns_single_execution() {
  // Job 생성
  ProactiveJobResponse job =
      proactiveJobService.createJob(buildCreateRequest("단건조회테스트"), testUserId);

  // 실행 레코드 직접 삽입
  Long execId =
      dsl.insertInto(PROACTIVE_JOB_EXECUTION)
          .set(PROACTIVE_JOB_EXECUTION.JOB_ID, job.id())
          .set(PROACTIVE_JOB_EXECUTION.STATUS, "COMPLETED")
          .set(PROACTIVE_JOB_EXECUTION.STARTED_AT, java.time.LocalDateTime.now().minusMinutes(5))
          .set(PROACTIVE_JOB_EXECUTION.COMPLETED_AT, java.time.LocalDateTime.now())
          .returning(PROACTIVE_JOB_EXECUTION.ID)
          .fetchOne()
          .getId();

  // 단건 조회
  var result = proactiveJobService.getExecution(execId);

  assertThat(result).isNotNull();
  assertThat(result.id()).isEqualTo(execId);
  assertThat(result.jobId()).isEqualTo(job.id());
  assertThat(result.status()).isEqualTo("COMPLETED");
}

/** 단건 실행 조회 — 존재하지 않는 ID */
@Test
void getExecution_throws_when_not_found() {
  assertThatThrownBy(() -> proactiveJobService.getExecution(999999L))
      .isInstanceOf(ProactiveJobException.class)
      .hasMessageContaining("999999");
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd apps/firehub-api && ./gradlew test --tests "*.ProactiveJobServiceTest.getExecution_*" -i
```

Expected: PASS (getExecution 메서드는 이미 존재). 만약 통과하면 Step 3은 컨트롤러 엔드포인트만 추가.

- [ ] **Step 3: 컨트롤러에 단건 조회 엔드포인트 추가**

`ProactiveJobController.java`에서 `getExecutions` 메서드 바로 아래에 추가:

```java
/**
 * 단건 실행 조회 — 실행 상세 페이지에서 사용.
 * jobId와 executionId의 소속 관계를 검증한 뒤 반환한다.
 */
@GetMapping("/{jobId}/executions/{executionId}")
@RequirePermission("proactive:read")
public ResponseEntity<ProactiveJobExecutionResponse> getExecution(
    @PathVariable Long jobId,
    @PathVariable Long executionId,
    Authentication authentication) {
  Long userId = (Long) authentication.getPrincipal();
  // Job 소유권 검증
  proactiveJobService.getJob(jobId, userId);
  // 실행 조회 + jobId 소속 검증
  ProactiveJobExecutionResponse execution = proactiveJobService.getExecution(executionId);
  if (!jobId.equals(execution.jobId())) {
    return ResponseEntity.notFound().build();
  }
  return ResponseEntity.ok(execution);
}
```

- [ ] **Step 4: 백엔드 빌드 + 테스트 통과 확인**

```bash
cd apps/firehub-api && ./gradlew build
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-api/
git commit -m "feat(api): 단건 실행 조회 API 추가 — GET /proactive/jobs/{id}/executions/{id}"
```

---

## Task 2: 프론트엔드 — API 함수 + 쿼리 훅 추가

**Files:**
- Modify: `apps/firehub-web/src/api/proactive.ts`
- Modify: `apps/firehub-web/src/hooks/queries/useProactiveMessages.ts`

- [ ] **Step 1: proactiveApi에 getExecution 함수 추가**

`apps/firehub-web/src/api/proactive.ts`에서 `getJobExecutions` 바로 아래에 추가:

```typescript
  /** 단건 실행 조회 — 실행 상세 페이지용 */
  getExecution: (jobId: number, executionId: number) =>
    client.get<ProactiveJobExecution>(`/proactive/jobs/${jobId}/executions/${executionId}`),
```

- [ ] **Step 2: useExecution 쿼리 훅 추가**

`apps/firehub-web/src/hooks/queries/useProactiveMessages.ts`에서 `useJobExecutions` 아래에 추가:

```typescript
/**
 * 단건 실행 조회 훅 — 실행 상세 페이지에서 사용.
 * RUNNING 상태일 때 5초 간격으로 자동 폴링하여 완료를 감지한다.
 */
export function useExecution(jobId: number, executionId: number) {
  const query = useQuery({
    queryKey: [...KEYS.executions(jobId), executionId],
    queryFn: () => proactiveApi.getExecution(jobId, executionId).then((r) => r.data),
    enabled: !!jobId && !!executionId,
  });

  // RUNNING 상태이면 5초 간격 폴링
  const isRunning = query.data?.status === 'RUNNING';
  return useQuery({
    queryKey: [...KEYS.executions(jobId), executionId],
    queryFn: () => proactiveApi.getExecution(jobId, executionId).then((r) => r.data),
    enabled: !!jobId && !!executionId,
    refetchInterval: isRunning ? 5000 : false,
  });
}
```

참고: `useExecution` 내부에서 `isRunning` 상태에 따라 `refetchInterval`을 동적으로 조절해야 하므로, `useState`와 `useEffect`를 사용하는 패턴으로 구현한다. 기존 `JobExecutionsTab`의 패턴 참조:

```typescript
export function useExecution(jobId: number, executionId: number) {
  const [refetchInterval, setRefetchInterval] = useState<number | false>(false);

  const result = useQuery({
    queryKey: [...KEYS.executions(jobId), executionId],
    queryFn: () => proactiveApi.getExecution(jobId, executionId).then((r) => r.data),
    enabled: !!jobId && !!executionId,
    refetchInterval,
  });

  useEffect(() => {
    setRefetchInterval(result.data?.status === 'RUNNING' ? 5000 : false);
  }, [result.data?.status]);

  return result;
}
```

`useState`와 `useEffect`를 import에 추가할 것.

- [ ] **Step 3: 타입체크 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/api/proactive.ts apps/firehub-web/src/hooks/queries/useProactiveMessages.ts
git commit -m "feat(web): 단건 실행 조회 API 함수 + useExecution 쿼리 훅 추가"
```

---

## Task 3: 프론트엔드 — ReportIframe 공통 컴포넌트

**Files:**
- Create: `apps/firehub-web/src/components/ai/ReportIframe.tsx`
- Modify: `apps/firehub-web/src/pages/ai-insights/ReportViewerPage.tsx`

- [ ] **Step 1: ReportIframe 컴포넌트 생성**

`apps/firehub-web/src/components/ai/ReportIframe.tsx`:

```tsx
/**
 * ReportIframe — HTML 리포트를 안전하게 렌더링하는 공통 iframe 컴포넌트.
 *
 * 세 곳에서 재사용: 실행 상세 페이지, 리포트 모달, ReportViewerPage.
 * sandbox="allow-same-origin"으로 스크립트 실행을 차단하되 인쇄 접근은 허용한다.
 */
import { forwardRef } from 'react';

import { cn } from '@/lib/utils';

interface ReportIframeProps {
  /** 렌더링할 HTML 문자열 */
  html: string;
  /** 추가 CSS 클래스 */
  className?: string;
}

const ReportIframe = forwardRef<HTMLIFrameElement, ReportIframeProps>(
  ({ html, className }, ref) => {
    return (
      <iframe
        ref={ref}
        srcDoc={html}
        sandbox="allow-same-origin"
        title="리포트"
        className={cn('w-full h-full border-0', className)}
      />
    );
  },
);

ReportIframe.displayName = 'ReportIframe';

export default ReportIframe;
```

- [ ] **Step 2: ReportViewerPage에서 ReportIframe 사용하도록 리팩터링**

`apps/firehub-web/src/pages/ai-insights/ReportViewerPage.tsx`에서 기존 `<iframe>` 태그를 `ReportIframe`으로 교체:

import 추가:
```tsx
import ReportIframe from '@/components/ai/ReportIframe';
```

기존 iframe 블록 (약 line 143~157):
```tsx
          <iframe
            ref={iframeRef}
            srcDoc={rawHtml}
            sandbox="allow-same-origin"
            title="리포트"
            className="w-full h-full border-0"
          />
```

교체:
```tsx
          <ReportIframe ref={iframeRef} html={rawHtml} />
```

- [ ] **Step 3: 타입체크 + 빌드 확인**

```bash
cd apps/firehub-web && pnpm typecheck && pnpm build
```

Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/components/ai/ReportIframe.tsx apps/firehub-web/src/pages/ai-insights/ReportViewerPage.tsx
git commit -m "refactor(web): ReportIframe 공통 컴포넌트 추출 — ReportViewerPage 리팩터링"
```

---

## Task 4: 프론트엔드 — 실행 상세 페이지

**Files:**
- Create: `apps/firehub-web/src/pages/ai-insights/ExecutionDetailPage.tsx`
- Modify: `apps/firehub-web/src/App.tsx`

- [ ] **Step 1: ExecutionDetailPage 컴포넌트 생성**

`apps/firehub-web/src/pages/ai-insights/ExecutionDetailPage.tsx`:

```tsx
/**
 * ExecutionDetailPage — 실행 상세 페이지.
 *
 * URL: /ai-insights/jobs/:jobId/executions/:executionId
 * 카드형 메타정보(상태, 시간, 채널) + 요약 + 리포트 인라인 표시.
 * 상태별 분기: COMPLETED(요약+리포트), FAILED(에러 카드), RUNNING(로딩+폴링).
 */
import { ArrowLeft, FileDown, Loader2, Printer } from 'lucide-react';
import { useCallback, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { useNavigate, useParams } from 'react-router-dom';
import remarkGfm from 'remark-gfm';
import { toast } from 'sonner';

import { proactiveApi } from '@/api/proactive';
import ReportIframe from '@/components/ai/ReportIframe';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { useExecution } from '@/hooks/queries/useProactiveMessages';
import { downloadBlob } from '@/lib/download';
import { classifyError } from '@/lib/error-classifier';
import { formatDate, getStatusBadgeVariant, getStatusLabel, timeAgo } from '@/lib/formatters';
import { getSections } from '@/lib/proactive-utils';
import { useQuery } from '@tanstack/react-query';

const REMARK_PLUGINS = [remarkGfm];
const PROSE_CLASSES =
  'prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-headings:my-2 prose-ul:my-1 prose-ol:my-1 prose-li:my-0 text-sm leading-relaxed';

/** 실행 시간과 완료 시간 사이 소요 시간을 계산한다 */
function calcDuration(startedAt: string, completedAt: string): string {
  const ms = new Date(completedAt).getTime() - new Date(startedAt).getTime();
  if (ms < 1000) return `${ms}ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}초`;
  return `${Math.floor(s / 60)}분 ${s % 60}초`;
}

export default function ExecutionDetailPage() {
  const { jobId, executionId } = useParams<{ jobId: string; executionId: string }>();
  const navigate = useNavigate();
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const [downloading, setDownloading] = useState(false);

  const jobIdNum = Number(jobId);
  const executionIdNum = Number(executionId);

  // 실행 데이터 조회 (RUNNING이면 5초 폴링)
  const { data: execution, isLoading } = useExecution(jobIdNum, executionIdNum);

  // HTML 리포트 조회 — COMPLETED + result가 있을 때만
  const { data: htmlResponse } = useQuery({
    queryKey: ['execution-html', jobIdNum, executionIdNum],
    queryFn: () => proactiveApi.getExecutionHtml(jobIdNum, executionIdNum),
    enabled: execution?.status === 'COMPLETED' && execution?.result != null,
  });
  const rawHtml = htmlResponse?.data ?? null;

  /** PDF 다운로드 핸들러 */
  const handleDownloadPdf = useCallback(async () => {
    setDownloading(true);
    try {
      const response = await proactiveApi.downloadExecutionPdf(jobIdNum, executionIdNum);
      downloadBlob(`report-${executionIdNum}.pdf`, response.data as Blob);
    } catch {
      toast.error('PDF 다운로드에 실패했습니다.');
    } finally {
      setDownloading(false);
    }
  }, [jobIdNum, executionIdNum]);

  /** iframe 내부 문서를 인쇄한다 */
  const handlePrint = useCallback(() => {
    const iframe = iframeRef.current;
    if (iframe?.contentWindow) {
      iframe.contentWindow.print();
    } else {
      window.print();
    }
  }, []);

  // 로딩 상태
  if (isLoading || !execution) {
    return (
      <div className="flex items-center justify-center h-[60vh] gap-2 text-muted-foreground">
        <Loader2 className="h-5 w-5 animate-spin" />
        <span className="text-sm">실행 정보를 불러오는 중...</span>
      </div>
    );
  }

  // 요약 텍스트와 htmlContent 추출
  const summary = execution.result?.summary as string | undefined;
  const hasHtml = !!rawHtml;
  const sections = execution.result ? getSections(execution.result) : [];

  return (
    <div className="flex flex-col h-full">
      {/* 고정 헤더 — 뒤로가기 + 실행 번호 */}
      <div className="flex items-center gap-3 px-6 py-3 border-b bg-background">
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)} className="gap-1.5">
          <ArrowLeft className="h-4 w-4" />
          뒤로
        </Button>
        <div className="h-5 w-px bg-border" />
        <h1 className="text-sm font-semibold text-foreground">실행 #{execution.id}</h1>
      </div>

      {/* 스크롤 영역 */}
      <div className="flex-1 overflow-auto px-6 py-4 space-y-4">
        {/* 메타정보 카드 4칸 그리드 */}
        <div className="grid grid-cols-4 gap-3">
          <div className="rounded-lg border bg-card p-3">
            <p className="text-xs text-muted-foreground mb-1">상태</p>
            <Badge variant={getStatusBadgeVariant(execution.status)}>
              {getStatusLabel(execution.status)}
            </Badge>
          </div>
          <div className="rounded-lg border bg-card p-3">
            <p className="text-xs text-muted-foreground mb-1">실행 시간</p>
            <p className="text-sm font-medium">
              {formatDate(execution.startedAt)}
              <span className="text-muted-foreground ml-1 text-xs">
                ({timeAgo(execution.startedAt)})
              </span>
            </p>
          </div>
          <div className="rounded-lg border bg-card p-3">
            <p className="text-xs text-muted-foreground mb-1">소요 시간</p>
            <p className="text-sm font-medium">
              {execution.status === 'RUNNING'
                ? '진행 중...'
                : execution.completedAt
                  ? calcDuration(execution.startedAt, execution.completedAt)
                  : '-'}
            </p>
          </div>
          <div className="rounded-lg border bg-card p-3">
            <p className="text-xs text-muted-foreground mb-1">전달 채널</p>
            <div className="flex gap-1 flex-wrap">
              {execution.deliveredChannels?.length > 0 ? (
                execution.deliveredChannels.map((ch) => (
                  <Badge key={ch} variant="outline" className="text-xs">
                    {ch}
                  </Badge>
                ))
              ) : (
                <span className="text-sm text-muted-foreground">—</span>
              )}
            </div>
          </div>
        </div>

        {/* 상태별 결과 영역 */}
        {execution.status === 'RUNNING' && (
          <div className="flex flex-col items-center justify-center py-16 gap-3">
            <Loader2 className="h-6 w-6 animate-spin text-primary" />
            <span className="text-sm text-muted-foreground">리포트를 생성하고 있습니다...</span>
            <span className="text-xs text-muted-foreground/70">5초마다 자동 갱신</span>
          </div>
        )}

        {execution.status === 'FAILED' && (
          <div className="rounded-lg border border-destructive/50 p-4 space-y-2">
            {(() => {
              const classified = classifyError(execution.errorMessage);
              return (
                <>
                  <div className="flex items-center gap-2">
                    <span>{classified.icon}</span>
                    <span className="text-sm font-semibold text-destructive">
                      {classified.label}
                    </span>
                  </div>
                  <p className="text-sm text-muted-foreground">
                    {execution.errorMessage ?? '알 수 없는 오류가 발생했습니다.'}
                  </p>
                  <p className="text-xs text-muted-foreground/70 border-t pt-2">
                    💡 {classified.guide}
                  </p>
                </>
              );
            })()}
          </div>
        )}

        {execution.status === 'COMPLETED' && (
          <>
            {/* 요약 섹션 */}
            {summary && (
              <div className="rounded-lg border bg-card p-4">
                <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
                  요약
                </p>
                <div className={PROSE_CLASSES}>
                  <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>{summary}</ReactMarkdown>
                </div>
              </div>
            )}

            {/* 리포트 섹션 — HTML이 있으면 iframe, 없으면 마크다운 */}
            {hasHtml ? (
              <div className="rounded-lg border overflow-hidden">
                {/* 리포트 헤더 — 타이틀 + 액션 버튼 */}
                <div className="flex items-center justify-between px-4 py-2.5 bg-muted/50 border-b">
                  <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                    리포트
                  </p>
                  <div className="flex gap-2">
                    <Button variant="outline" size="sm" onClick={handlePrint} className="gap-1.5 h-7 text-xs">
                      <Printer className="h-3 w-3" />
                      인쇄
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={handleDownloadPdf}
                      disabled={downloading}
                      className="gap-1.5 h-7 text-xs"
                    >
                      {downloading ? (
                        <Loader2 className="h-3 w-3 animate-spin" />
                      ) : (
                        <FileDown className="h-3 w-3" />
                      )}
                      PDF 다운로드
                    </Button>
                  </div>
                </div>
                {/* 리포트 본문 — 라이트 배경 iframe */}
                <div className="bg-white" style={{ minHeight: '500px' }}>
                  <ReportIframe ref={iframeRef} html={rawHtml} />
                </div>
              </div>
            ) : sections.length > 0 ? (
              <div className="space-y-4">
                <div className="flex justify-end">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={handleDownloadPdf}
                    disabled={downloading}
                    className="gap-1.5"
                  >
                    {downloading ? (
                      <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <FileDown className="h-3.5 w-3.5" />
                    )}
                    PDF
                  </Button>
                </div>
                {sections.map((section) => (
                  <div key={section.key}>
                    {sections.length > 1 && (
                      <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
                        {section.label}
                      </p>
                    )}
                    <div className={PROSE_CLASSES}>
                      <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>
                        {section.content}
                      </ReactMarkdown>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="text-sm text-muted-foreground text-center py-8">
                결과 내용이 없습니다.
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: App.tsx에 라우트 추가**

`apps/firehub-web/src/App.tsx`에서 lazy import 추가 (기존 ReportViewerPage import 근처):

```tsx
const ExecutionDetailPage = lazy(() => import('./pages/ai-insights/ExecutionDetailPage'));
```

라우트 추가 (ReportViewerPage 라우트 바로 위):

```tsx
<Route path="/ai-insights/jobs/:jobId/executions/:executionId" element={<ExecutionDetailPage />} />
```

**주의**: 이 라우트가 `/ai-insights/jobs/:jobId/executions/:executionId/report`보다 위에 있어야 한다. React Router v7은 패턴 매칭 시 더 구체적인 경로를 우선하므로 `/report`가 있는 경로는 별도 매칭된다. 하지만 명시적으로 report 라우트를 아래에 두면 더 안전하다.

- [ ] **Step 3: 타입체크 + 빌드 확인**

```bash
cd apps/firehub-web && pnpm typecheck && pnpm build
```

Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/ExecutionDetailPage.tsx apps/firehub-web/src/App.tsx
git commit -m "feat(web): 실행 상세 페이지 — 카드형 메타 + 요약 + 리포트 인라인"
```

---

## Task 5: 프론트엔드 — 리포트 모달 컴포넌트

**Files:**
- Create: `apps/firehub-web/src/components/ai/ReportModal.tsx`

shadcn Dialog가 설치되어 있는지 확인 후, 없으면 `npx shadcn@latest add dialog` 실행.

- [ ] **Step 1: ReportModal 컴포넌트 생성**

`apps/firehub-web/src/components/ai/ReportModal.tsx`:

```tsx
/**
 * ReportModal — AI 챗/알림에서 리포트를 빠르게 확인하는 모달.
 *
 * shadcn Dialog 기반. 현재 화면 위에 오버레이로 표시하여
 * 페이지 이동 없이 리포트를 확인할 수 있다.
 * 하단에 "실행 상세 보기" 링크로 상세 페이지 진입 가능.
 */
import { ExternalLink, FileDown, Loader2, Printer } from 'lucide-react';
import { useCallback, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { Link, useNavigate } from 'react-router-dom';
import remarkGfm from 'remark-gfm';
import { toast } from 'sonner';

import { proactiveApi } from '@/api/proactive';
import ReportIframe from '@/components/ai/ReportIframe';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { downloadBlob } from '@/lib/download';
import { getStatusBadgeVariant, getStatusLabel, timeAgo } from '@/lib/formatters';
import { getSections } from '@/lib/proactive-utils';
import { useQuery } from '@tanstack/react-query';

const REMARK_PLUGINS = [remarkGfm];
const PROSE_CLASSES =
  'prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-headings:my-2 text-sm leading-relaxed';

interface ReportModalProps {
  /** 모달 열림 여부 */
  open: boolean;
  /** 모달 닫기 콜백 */
  onClose: () => void;
  /** Job ID */
  jobId: number;
  /** Execution ID */
  executionId: number;
}

export default function ReportModal({ open, onClose, jobId, executionId }: ReportModalProps) {
  const navigate = useNavigate();
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const [downloading, setDownloading] = useState(false);

  // HTML 리포트 조회 — 모달이 열릴 때만
  const { data: htmlResponse, isLoading } = useQuery({
    queryKey: ['execution-html', jobId, executionId],
    queryFn: () => proactiveApi.getExecutionHtml(jobId, executionId),
    enabled: open && !!jobId && !!executionId,
  });
  const rawHtml = htmlResponse?.data ?? null;

  // 실행 데이터 조회 (상태 뱃지 등 메타정보용)
  const { data: execution } = useQuery({
    queryKey: ['proactive', 'executions', jobId, executionId],
    queryFn: () => proactiveApi.getExecution(jobId, executionId).then((r) => r.data),
    enabled: open && !!jobId && !!executionId,
  });

  /** PDF 다운로드 핸들러 */
  const handleDownloadPdf = useCallback(async () => {
    setDownloading(true);
    try {
      const response = await proactiveApi.downloadExecutionPdf(jobId, executionId);
      downloadBlob(`report-${executionId}.pdf`, response.data as Blob);
    } catch {
      toast.error('PDF 다운로드에 실패했습니다.');
    } finally {
      setDownloading(false);
    }
  }, [jobId, executionId]);

  /** iframe 내부 문서를 인쇄한다 */
  const handlePrint = useCallback(() => {
    const iframe = iframeRef.current;
    if (iframe?.contentWindow) {
      iframe.contentWindow.print();
    } else {
      window.print();
    }
  }, []);

  /** "실행 상세 보기" — 모달 닫고 상세 페이지로 이동 */
  const handleGoToDetail = useCallback(() => {
    onClose();
    navigate(`/ai-insights/jobs/${jobId}/executions/${executionId}`);
  }, [onClose, navigate, jobId, executionId]);

  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-4xl h-[80vh] flex flex-col p-0 gap-0">
        {/* 헤더 — 메타 정보 압축 + 액션 버튼 */}
        <DialogHeader className="flex flex-row items-center justify-between px-4 py-3 border-b space-y-0">
          <div className="flex items-center gap-2">
            <DialogTitle className="text-sm font-semibold">리포트 #{executionId}</DialogTitle>
            {execution && (
              <>
                <Badge variant={getStatusBadgeVariant(execution.status)} className="text-xs">
                  {getStatusLabel(execution.status)}
                </Badge>
                <span className="text-xs text-muted-foreground">
                  {timeAgo(execution.startedAt)}
                </span>
              </>
            )}
          </div>
          <div className="flex items-center gap-2">
            {rawHtml && (
              <Button variant="outline" size="sm" onClick={handlePrint} className="gap-1.5 h-7 text-xs">
                <Printer className="h-3 w-3" />
                인쇄
              </Button>
            )}
            <Button
              variant="outline"
              size="sm"
              onClick={handleDownloadPdf}
              disabled={downloading}
              className="gap-1.5 h-7 text-xs"
            >
              {downloading ? (
                <Loader2 className="h-3 w-3 animate-spin" />
              ) : (
                <FileDown className="h-3 w-3" />
              )}
              PDF
            </Button>
            <Button variant="outline" size="sm" asChild className="gap-1.5 h-7 text-xs">
              <Link
                to={`/ai-insights/jobs/${jobId}/executions/${executionId}/report`}
                target="_blank"
              >
                <ExternalLink className="h-3 w-3" />
                새 탭
              </Link>
            </Button>
          </div>
        </DialogHeader>

        {/* 본문 — 리포트 영역 */}
        <div className="flex-1 overflow-hidden">
          {isLoading && (
            <div className="flex items-center justify-center h-full gap-2 text-muted-foreground">
              <Loader2 className="h-5 w-5 animate-spin" />
              <span className="text-sm">리포트를 불러오는 중...</span>
            </div>
          )}

          {!isLoading && rawHtml && (
            <div className="h-full bg-white">
              <ReportIframe ref={iframeRef} html={rawHtml} />
            </div>
          )}

          {!isLoading && !rawHtml && execution?.result && (
            <div className="p-4 overflow-auto h-full">
              <div className={PROSE_CLASSES}>
                {getSections(execution.result).map((section) => (
                  <div key={section.key} className="mb-4">
                    <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
                      {section.label}
                    </p>
                    <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>
                      {section.content}
                    </ReactMarkdown>
                  </div>
                ))}
              </div>
            </div>
          )}

          {!isLoading && !rawHtml && !execution?.result && (
            <div className="flex items-center justify-center h-full text-sm text-muted-foreground">
              리포트가 없습니다.
            </div>
          )}
        </div>

        {/* 하단 — 실행 상세 보기 링크 */}
        <div className="px-4 py-2.5 border-t bg-muted/30">
          <button
            onClick={handleGoToDetail}
            className="text-xs text-primary hover:underline"
          >
            실행 상세 보기 →
          </button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 2: 타입체크 확인**

```bash
cd apps/firehub-web && pnpm typecheck
```

Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/ai/ReportModal.tsx
git commit -m "feat(web): 리포트 모달 컴포넌트 — AI 챗/알림용 오버레이"
```

---

## Task 6: 프론트엔드 — JobExecutionsTab 간소화

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/tabs/JobExecutionsTab.tsx`

- [ ] **Step 1: JobExecutionsTab 리팩터링**

변경 사항:
1. `ExecutionResultView` 함수 컴포넌트 전체 **삭제** (line 42~183)
2. `selectedId` 상태 **삭제**
3. `useNavigate` import 추가, `navigate` 호출로 행 클릭 처리
4. 하단 결과 뷰 영역 **삭제** — 테이블만 남김
5. 테이블이 전체 높이를 사용하도록 레이아웃 변경
6. 불필요한 import 제거 (`ExternalLink`, `FileDown`, `Link`, `ReactMarkdown`, `remarkGfm`, `downloadBlob`, `classifyError`, `getSections`, `cn`, `REMARK_PLUGINS`, `PROSE_CLASSES`, `calcDuration`)

리팩터링된 전체 파일:

```tsx
/**
 * JobExecutionsTab — 실행 이력 목록 탭.
 *
 * 행 클릭 시 실행 상세 페이지(/ai-insights/jobs/:jobId/executions/:id)로 이동한다.
 * RUNNING 상태의 실행이 있으면 5초 간격으로 자동 폴링한다.
 */
import { Loader2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useJobExecutions } from '@/hooks/queries/useProactiveMessages';
import { formatDate, getStatusBadgeVariant, getStatusLabel, timeAgo } from '@/lib/formatters';

/** 실행 시간과 완료 시간 사이 소요 시간을 계산한다 */
function calcDuration(startedAt: string, completedAt: string): string {
  const ms = new Date(completedAt).getTime() - new Date(startedAt).getTime();
  if (ms < 1000) return `${ms}ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}초`;
  return `${Math.floor(s / 60)}분 ${s % 60}초`;
}

interface JobExecutionsTabProps {
  jobId: number;
}

export default function JobExecutionsTab({ jobId }: JobExecutionsTabProps) {
  const navigate = useNavigate();
  const [limit, setLimit] = useState(20);

  const [refetchInterval, setRefetchInterval] = useState<number | false>(false);
  const { data: executions = [], isLoading } = useJobExecutions(
    jobId,
    { limit, offset: 0 },
    { refetchInterval },
  );

  const hasRunning = executions.some((e) => e.status === 'RUNNING');
  useEffect(() => {
    setRefetchInterval(hasRunning ? 5000 : false);
  }, [hasRunning]);

  return (
    <div className="flex flex-col" style={{ height: 'calc(100vh - 280px)' }}>
      {/* 실행 목록 테이블 — 전체 높이 사용 */}
      <div className="flex-1 overflow-auto border rounded-md">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-[45%]">실행 시간</TableHead>
              <TableHead className="w-[12%] text-center">상태</TableHead>
              <TableHead className="w-[18%] text-center">소요 시간</TableHead>
              <TableHead className="w-[25%] text-center">전달 채널</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={4} rows={5} />
            ) : executions.length > 0 ? (
              executions.map((exec) => (
                <TableRow
                  key={exec.id}
                  className="cursor-pointer hover:bg-muted/50"
                  onClick={() => navigate(`/ai-insights/jobs/${jobId}/executions/${exec.id}`)}
                >
                  <TableCell className="text-sm">
                    {formatDate(exec.startedAt)} ({timeAgo(exec.startedAt)})
                  </TableCell>
                  <TableCell className="text-center">
                    <Badge variant={getStatusBadgeVariant(exec.status)}>
                      {getStatusLabel(exec.status)}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-sm text-center">
                    {exec.completedAt ? calcDuration(exec.startedAt, exec.completedAt) : (
                      exec.status === 'RUNNING' ? (
                        <Loader2 className="h-3.5 w-3.5 animate-spin inline" />
                      ) : '-'
                    )}
                  </TableCell>
                  <TableCell>
                    <div className="flex gap-1 flex-wrap justify-center">
                      {exec.deliveredChannels?.map((ch) => (
                        <Badge key={ch} variant="outline" className="text-xs">
                          {ch}
                        </Badge>
                      ))}
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={4} message="실행 이력이 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>

      {executions.length >= limit && (
        <div className="py-2 flex justify-center">
          <Button variant="ghost" size="sm" onClick={() => setLimit((l) => l + 20)}>
            더 보기
          </Button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: 타입체크 + 빌드 확인**

```bash
cd apps/firehub-web && pnpm typecheck && pnpm build
```

Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/tabs/JobExecutionsTab.tsx
git commit -m "refactor(web): JobExecutionsTab 간소화 — 행 클릭 시 상세 페이지 이동"
```

---

## Task 7: 프론트엔드 — ProactiveMessage + AINotificationPanel 모달 연동

**Files:**
- Modify: `apps/firehub-web/src/components/ai/ProactiveMessage.tsx`
- Modify: `apps/firehub-web/src/components/ai/AINotificationPanel.tsx`

이 두 컴포넌트에서 "리포트 보기" `<Link>`를 모달 트리거로 변경한다. `ReportModal`의 상태 관리를 위해 `useState`를 사용한다.

- [ ] **Step 1: ProactiveMessage.tsx 수정**

import 변경:
- 제거: `Link` (from react-router-dom) — 다른 곳에서 사용하지 않는다면
- 추가: `useState` (from react), `ReportModal` (from @/components/ai/ReportModal)

기존 "리포트 보기" 링크 블록 (약 line 87~100):
```tsx
{jobId && executionId && (
  <Button
    variant="default"
    size="sm"
    className="h-7 text-xs"
    asChild
    onClick={(e) => e.stopPropagation()}
  >
    <Link to={`/ai-insights/jobs/${jobId}/executions/${executionId}/report`}>
      <ExternalLink className="h-3 w-3 mr-1" />
      리포트 보기
    </Link>
  </Button>
)}
```

교체:
```tsx
{jobId && executionId && (
  <>
    <Button
      variant="default"
      size="sm"
      className="h-7 text-xs"
      onClick={(e) => {
        e.stopPropagation();
        setReportModalOpen(true);
      }}
    >
      <ExternalLink className="h-3 w-3 mr-1" />
      리포트 보기
    </Button>
    <ReportModal
      open={reportModalOpen}
      onClose={() => setReportModalOpen(false)}
      jobId={Number(jobId)}
      executionId={Number(executionId)}
    />
  </>
)}
```

컴포넌트 함수 내부에 state 추가:
```tsx
const [reportModalOpen, setReportModalOpen] = useState(false);
```

`asChild` prop 제거됨에 유의. `Link`를 더 이상 사용하지 않으면 import에서도 제거.

- [ ] **Step 2: AINotificationPanel.tsx 수정**

import 추가: `useState`, `ReportModal`

기존 "리포트 보기" 링크 블록 (약 line 194~206):
```tsx
{jobId && executionId && (
  <Link
    to={`/ai-insights/jobs/${jobId}/executions/${executionId}/report`}
    onClick={onClose}
    className="flex items-center justify-center gap-1.5 w-full rounded-lg py-2 ..."
    style={{ ... }}
  >
    <ExternalLink className="h-3.5 w-3.5" />
    리포트 보기
  </Link>
)}
```

교체:
```tsx
{jobId && executionId && (
  <>
    <button
      onClick={() => setReportModalOpen(true)}
      className="flex items-center justify-center gap-1.5 w-full rounded-lg py-2 text-xs font-medium transition-colors hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/50"
      style={{
        background: 'var(--primary)',
        color: 'var(--primary-foreground)',
      }}
    >
      <ExternalLink className="h-3.5 w-3.5" />
      리포트 보기
    </button>
    <ReportModal
      open={reportModalOpen}
      onClose={() => {
        setReportModalOpen(false);
        onClose();
      }}
      jobId={Number(jobId)}
      executionId={Number(executionId)}
    />
  </>
)}
```

DetailView 함수 내부에 state 추가:
```tsx
const [reportModalOpen, setReportModalOpen] = useState(false);
```

`Link` import가 더 이상 사용되지 않으면 제거. `onClose`는 모달 닫을 때 알림 패널도 함께 닫도록 체이닝.

- [ ] **Step 3: 타입체크 + 빌드 확인**

```bash
cd apps/firehub-web && pnpm typecheck && pnpm build
```

Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/components/ai/ProactiveMessage.tsx apps/firehub-web/src/components/ai/AINotificationPanel.tsx
git commit -m "feat(web): AI 챗/알림 리포트 보기 → 리포트 모달로 전환"
```

---

## Task 8: 통합 검증

- [ ] **Step 1: 전체 빌드 + 타입체크**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm build && pnpm typecheck
```

Expected: 전체 통과

- [ ] **Step 2: 백엔드 테스트**

```bash
cd apps/firehub-api && ./gradlew test
```

Expected: 전체 통과

- [ ] **Step 3: dev 서버 실행 + UI 검증**

```bash
pnpm dev
```

수동 검증 체크리스트:
1. 스마트 작업 상세 → 실행 이력 탭 → 행 클릭 → 실행 상세 페이지 이동 확인
2. 상세 페이지 메타 카드 4칸 표시 확인
3. 성공 실행: 요약 + 리포트 인라인 표시 확인
4. 실패 실행: 에러 분류 카드 표시 확인
5. "뒤로" 버튼 → 실행 이력 목록 복귀 확인
6. AI 챗 "리포트 보기" → 리포트 모달 표시 확인
7. 모달 닫기 (ESC / ✕ / 오버레이 클릭) 확인
8. 모달 "실행 상세 보기" → 상세 페이지 이동 확인
9. 모달 "새 탭" → ReportViewerPage 열림 확인
10. PDF 다운로드 (상세 페이지 + 모달) 확인
11. `/ai-insights/jobs/{id}/executions/{id}/report` 직접 접근 → ReportViewerPage 정상 확인

- [ ] **Step 4: Playwright 스크린샷**

주요 화면 스크린샷을 `snapshots/` 에 저장:
- 실행 상세 페이지 (성공 상태)
- 실행 상세 페이지 (실패 상태)
- 리포트 모달
- 간소화된 실행 이력 테이블

- [ ] **Step 5: 최종 커밋 (있다면)**

검증 중 발견된 수정 사항이 있으면 커밋.
