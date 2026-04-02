# Phase 7-0b: 스마트 작업 UX 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 스마트 작업의 사용성을 개선한다 — 작업 복제, Cron 프리셋 확대, 타임존 셀렉터, 다음 실행 시간 표시, 상세 에러 메시지.

**Architecture:** Frontend-only 변경. 기존 createJob API를 활용한 복제, cron-parser 라이브러리로 다음 실행 시간 계산, 프론트엔드 패턴 매칭으로 에러 분류. 백엔드 변경 없음.

**Tech Stack:** React 19, TypeScript, TanStack Query, React Hook Form, Zod, shadcn/ui, cron-parser, Tailwind CSS v4

---

## File Structure

| 파일 | 역할 | 변경 유형 |
|------|------|----------|
| `apps/firehub-web/src/lib/timezone-data.ts` | 타임존 목록 상수 | 신규 |
| `apps/firehub-web/src/lib/error-classifier.ts` | 에러 분류 유틸리티 | 신규 |
| `apps/firehub-web/src/lib/next-run.ts` | 다음 실행 시간 계산 유틸리티 | 신규 |
| `apps/firehub-web/src/lib/cron-label.ts` | 신규 프리셋 라벨 추가 | 수정 |
| `apps/firehub-web/src/hooks/queries/useProactiveMessages.ts` | `useCloneProactiveJob` 훅 추가 | 수정 |
| `apps/firehub-web/src/pages/ai-insights/ProactiveJobListPage.tsx` | 복제 버튼, 다음 실행 컬럼 추가 | 수정 |
| `apps/firehub-web/src/pages/ai-insights/ProactiveJobDetailPage.tsx` | 복제 버튼 + 핸들러 추가 | 수정 |
| `apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx` | 프리셋 확대, 타임존 셀렉터, 다음 실행 인포박스 | 수정 |
| `apps/firehub-web/src/pages/ai-insights/tabs/JobExecutionsTab.tsx` | 에러 분류 카드 표시 | 수정 |

---

## Task 1: 유틸리티 모듈 생성 (timezone-data, error-classifier, next-run)

**Files:**
- Create: `apps/firehub-web/src/lib/timezone-data.ts`
- Create: `apps/firehub-web/src/lib/error-classifier.ts`
- Create: `apps/firehub-web/src/lib/next-run.ts`

- [ ] **Step 1: 타임존 데이터 상수 파일 생성**

```ts
// apps/firehub-web/src/lib/timezone-data.ts

export interface TimezoneOption {
  value: string;
  label: string;
  abbr: string;
  offset: string;
}

export const TIMEZONE_OPTIONS: TimezoneOption[] = [
  { value: 'Asia/Seoul', label: 'Asia/Seoul', abbr: 'KST', offset: 'UTC+9' },
  { value: 'Asia/Tokyo', label: 'Asia/Tokyo', abbr: 'JST', offset: 'UTC+9' },
  { value: 'Asia/Shanghai', label: 'Asia/Shanghai', abbr: 'CST', offset: 'UTC+8' },
  { value: 'Asia/Singapore', label: 'Asia/Singapore', abbr: 'SGT', offset: 'UTC+8' },
  { value: 'America/New_York', label: 'America/New_York', abbr: 'EST', offset: 'UTC-5' },
  { value: 'America/Chicago', label: 'America/Chicago', abbr: 'CST', offset: 'UTC-6' },
  { value: 'America/Denver', label: 'America/Denver', abbr: 'MST', offset: 'UTC-7' },
  { value: 'America/Los_Angeles', label: 'America/Los_Angeles', abbr: 'PST', offset: 'UTC-8' },
  { value: 'Europe/London', label: 'Europe/London', abbr: 'GMT', offset: 'UTC+0' },
  { value: 'Europe/Paris', label: 'Europe/Paris', abbr: 'CET', offset: 'UTC+1' },
  { value: 'Europe/Berlin', label: 'Europe/Berlin', abbr: 'CET', offset: 'UTC+1' },
  { value: 'Australia/Sydney', label: 'Australia/Sydney', abbr: 'AEST', offset: 'UTC+10' },
  { value: 'Pacific/Auckland', label: 'Pacific/Auckland', abbr: 'NZST', offset: 'UTC+12' },
  { value: 'UTC', label: 'UTC', abbr: 'UTC', offset: 'UTC+0' },
];
```

- [ ] **Step 2: 에러 분류 유틸리티 생성**

```ts
// apps/firehub-web/src/lib/error-classifier.ts

export interface ClassifiedError {
  type: 'ai' | 'data' | 'channel' | 'unknown';
  icon: string;
  label: string;
  guide: string;
}

const ERROR_PATTERNS: { type: ClassifiedError['type']; keywords: string[]; icon: string; label: string; guide: string }[] = [
  {
    type: 'ai',
    keywords: ['rate limit', 'token', 'claude', 'api', 'overloaded', 'anthropic', 'model'],
    icon: '🔴',
    label: 'AI 모델 오류',
    guide: '잠시 후 수동 실행을 시도하거나, 스케줄 간격을 늘려보세요.',
  },
  {
    type: 'data',
    keywords: ['connection', 'timeout', 'database', 'query', 'sql', 'datasource'],
    icon: '🟠',
    label: '데이터 접근 실패',
    guide: '데이터 연결 상태를 확인해주세요.',
  },
  {
    type: 'channel',
    keywords: ['email', 'smtp', 'delivery', 'channel', 'mail'],
    icon: '🟡',
    label: '채널 전달 실패',
    guide: '채널 설정(이메일/SMTP)을 확인해주세요.',
  },
];

export function classifyError(errorMessage: string | null | undefined): ClassifiedError {
  if (!errorMessage) {
    return { type: 'unknown', icon: '⚪', label: '알 수 없는 오류', guide: '관리자에게 문의해주세요.' };
  }

  const lower = errorMessage.toLowerCase();

  for (const pattern of ERROR_PATTERNS) {
    if (pattern.keywords.some((kw) => lower.includes(kw))) {
      return {
        type: pattern.type,
        icon: pattern.icon,
        label: pattern.label,
        guide: pattern.guide,
      };
    }
  }

  return { type: 'unknown', icon: '⚪', label: '기타 오류', guide: '관리자에게 문의해주세요.' };
}
```

- [ ] **Step 3: 다음 실행 시간 계산 유틸리티 생성**

```ts
// apps/firehub-web/src/lib/next-run.ts

import { parseExpression } from 'cron-parser';

/**
 * cron 표현식과 타임존으로 다음 실행 시간을 계산한다.
 * 실패 시 null 반환.
 */
export function getNextRunDate(cronExpression: string, timezone: string): Date | null {
  try {
    const interval = parseExpression(cronExpression, { tz: timezone });
    return interval.next().toDate();
  } catch {
    return null;
  }
}

/**
 * 다음 실행 시간을 사람이 읽기 쉬운 형식으로 포맷한다.
 * 편집 폼용: "2026-04-03 (목) 09:00 KST"
 */
export function formatNextRun(date: Date, timezone: string): string {
  const formatted = date.toLocaleDateString('ko-KR', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    weekday: 'short',
  });
  const time = date.toLocaleTimeString('ko-KR', {
    timeZone: timezone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
  return `${formatted} ${time}`;
}

/**
 * 목록 페이지용 간결한 포맷.
 * 24시간 이내 → "내일 09:00", 그 외 → "4월 3일 09:00"
 */
export function formatNextRunShort(date: Date, timezone: string): string {
  const now = new Date();
  const diffMs = date.getTime() - now.getTime();
  const diffHours = diffMs / (1000 * 60 * 60);

  const time = date.toLocaleTimeString('ko-KR', {
    timeZone: timezone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });

  if (diffHours <= 24) {
    // 오늘 내에 실행: "오늘 09:00" 또는 "내일 09:00"
    const todayInTz = new Date().toLocaleDateString('ko-KR', { timeZone: timezone });
    const dateInTz = date.toLocaleDateString('ko-KR', { timeZone: timezone });
    return todayInTz === dateInTz ? `오늘 ${time}` : `내일 ${time}`;
  }

  const formatted = date.toLocaleDateString('ko-KR', {
    timeZone: timezone,
    month: 'long',
    day: 'numeric',
  });
  return `${formatted} ${time}`;
}
```

- [ ] **Step 4: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: 타입 에러 없음

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/src/lib/timezone-data.ts apps/firehub-web/src/lib/error-classifier.ts apps/firehub-web/src/lib/next-run.ts
git commit -m "feat(proactive): 유틸리티 모듈 추가 (timezone, error-classifier, next-run)"
```

---

## Task 2: Cron 프리셋 확대 + 라벨 업데이트

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx:22-28`
- Modify: `apps/firehub-web/src/lib/cron-label.ts:1-14`

- [ ] **Step 1: cron-label.ts에 매주 금요일 라벨 추가**

`apps/firehub-web/src/lib/cron-label.ts` — LABELS 맵에 추가:

```ts
const LABELS: Record<string, string> = {
  '0 9 * * *': '매일 오전 9시',
  '0 8 * * *': '매일 오전 8시',
  '0 7 * * *': '매일 오전 7시',
  '0 6 * * *': '매일 오전 6시',
  '0 18 * * *': '매일 오후 6시',
  '0 9 * * 1': '매주 월요일 오전 9시',
  '0 9 * * 5': '매주 금요일 오전 9시',
  '0 9 * * 1-5': '평일 오전 9시',
  '0 * * * *': '매시간',
  '*/30 * * * *': '30분마다',
  '0 0 * * *': '매일 자정',
  '0 0 1 * *': '매월 1일 자정',
  '0 9 1 * *': '매월 1일 오전 9시',
};
```

변경 사항: `'0 9 * * 5': '매주 금요일 오전 9시'` 한 줄 추가만. 나머지는 이미 존재.

- [ ] **Step 2: JobOverviewTab.tsx의 CRON_PRESETS 배열 확대**

`apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx:22-28` — 기존 CRON_PRESETS를 다음으로 교체:

```ts
const CRON_PRESETS = [
  { label: '매시간', value: '0 * * * *' },
  { label: '매 30분', value: '*/30 * * * *' },
  { label: '매일 오전 8시', value: '0 8 * * *' },
  { label: '매일 오전 9시', value: '0 9 * * *' },
  { label: '매일 오후 6시', value: '0 18 * * *' },
  { label: '매주 월요일 오전 9시', value: '0 9 * * 1' },
  { label: '매주 금요일 오전 9시', value: '0 9 * * 5' },
  { label: '매월 1일 오전 9시', value: '0 9 1 * *' },
  { label: '직접 입력', value: '__custom__' },
];
```

- [ ] **Step 3: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: 타입 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/lib/cron-label.ts apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx
git commit -m "feat(proactive): Cron 프리셋 확대 (매 30분, 매일 오후 6시, 매주 금요일, 매월 1일)"
```

---

## Task 3: 타임존 셀렉터 (텍스트 input → Select 드롭다운)

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx:237-245`

- [ ] **Step 1: import 추가**

`JobOverviewTab.tsx` 상단 import에 추가:

```ts
import { TIMEZONE_OPTIONS } from '@/lib/timezone-data';
```

기존 import에 이미 `Select, SelectContent, SelectItem, SelectTrigger, SelectValue`가 있으므로 추가 import 불필요.

- [ ] **Step 2: 타임존 텍스트 input을 Select로 교체**

`JobOverviewTab.tsx` — 편집 모드의 타임존 섹션 (기존 237-245행)을 교체:

기존:
```tsx
{/* 타임존 */}
<div className="space-y-2">
  <Label htmlFor="job-timezone">타임존</Label>
  <Input
    id="job-timezone"
    placeholder="Asia/Seoul"
    {...register('timezone')}
  />
</div>
```

교체:
```tsx
{/* 타임존 */}
<div className="space-y-2">
  <Label htmlFor="job-timezone">타임존</Label>
  <Select
    value={watch('timezone')}
    onValueChange={(v) => setValue('timezone', v)}
  >
    <SelectTrigger id="job-timezone">
      <SelectValue placeholder="타임존 선택" />
    </SelectTrigger>
    <SelectContent>
      {TIMEZONE_OPTIONS.map((tz) => (
        <SelectItem key={tz.value} value={tz.value}>
          {tz.label} ({tz.abbr}, {tz.offset})
        </SelectItem>
      ))}
    </SelectContent>
  </Select>
</div>
```

- [ ] **Step 3: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: 타입 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx
git commit -m "feat(proactive): 타임존 셀렉터 드롭다운으로 교체"
```

---

## Task 4: 다음 실행 시간 표시 (편집 폼 + 읽기 전용 + 목록)

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx`
- Modify: `apps/firehub-web/src/pages/ai-insights/ProactiveJobListPage.tsx`

- [ ] **Step 1: 편집 폼에 다음 실행 인포박스 추가**

`JobOverviewTab.tsx` — import 추가:

```ts
import { getNextRunDate, formatNextRun } from '@/lib/next-run';
import { TIMEZONE_OPTIONS } from '@/lib/timezone-data';
```

편집 모드 타임존 셀렉터 `</div>` 직후 (교체한 타임존 섹션 바로 다음)에 인포박스 추가:

```tsx
{/* 다음 실행 시간 */}
{(() => {
  const tz = watch('timezone');
  const cron = watch('cronExpression');
  if (!cron || cron === '__custom__') return null;
  const nextDate = getNextRunDate(cron, tz);
  if (!nextDate) return null;
  const tzOption = TIMEZONE_OPTIONS.find((t) => t.value === tz);
  return (
    <div className="flex items-center gap-2 rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-800 dark:border-blue-800 dark:bg-blue-950/50 dark:text-blue-300">
      <span>📅</span>
      <span>다음 실행: <strong>{formatNextRun(nextDate, tz)}{tzOption ? ` ${tzOption.abbr}` : ''}</strong></span>
    </div>
  );
})()}
```

- [ ] **Step 2: 읽기 전용 뷰의 실행 주기 카드에 다음 실행 시간 개선**

읽기 전용 뷰에는 이미 `job.nextExecuteAt`을 표시하는 코드가 있음 (90-92행):
```tsx
{job.nextExecuteAt && (
  <ReadonlyCard label="다음 실행" value={formatDate(job.nextExecuteAt)} />
)}
```

이것은 이미 동작하므로 변경 불필요.

- [ ] **Step 3: 목록 페이지에 다음 실행 컬럼 추가**

`ProactiveJobListPage.tsx` — import 추가:

```ts
import { formatNextRunShort } from '@/lib/next-run';
```

TableHeader에 "다음 실행" 컬럼 추가 (기존 134행 `<TableHead>마지막 실행</TableHead>` 다음):

```tsx
<TableHead>다음 실행</TableHead>
```

TableBody의 각 행에 해당 셀 추가 (기존 160행 마지막 실행 TableCell 다음):

```tsx
<TableCell className="text-sm text-muted-foreground">
  {job.enabled && job.nextExecuteAt
    ? formatNextRunShort(new Date(job.nextExecuteAt), job.timezone)
    : '-'}
</TableCell>
```

컬럼 수가 6 → 7로 증가하므로 다음도 수정:
- `TableSkeletonRows columns={6}` → `columns={7}` (143행)
- `TableEmptyRow colSpan={6}` → `colSpan={7}` (189행)

- [ ] **Step 4: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: 타입 에러 없음

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx apps/firehub-web/src/pages/ai-insights/ProactiveJobListPage.tsx
git commit -m "feat(proactive): 다음 실행 시간 표시 (목록 컬럼 + 편집 폼 인포박스)"
```

---

## Task 5: 작업 복제 기능

**Files:**
- Modify: `apps/firehub-web/src/hooks/queries/useProactiveMessages.ts`
- Modify: `apps/firehub-web/src/pages/ai-insights/ProactiveJobDetailPage.tsx`
- Modify: `apps/firehub-web/src/pages/ai-insights/ProactiveJobListPage.tsx`

- [ ] **Step 1: useCloneProactiveJob 훅 추가**

`apps/firehub-web/src/hooks/queries/useProactiveMessages.ts` — Jobs 섹션 마지막 (`useExecuteProactiveJob` 훅 다음, 82행 이후)에 추가:

```ts
export function useCloneProactiveJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (job: {
      name: string;
      prompt: string;
      templateId?: number | null;
      cronExpression: string;
      timezone?: string;
      config?: Record<string, unknown>;
    }) =>
      proactiveApi.createJob({
        name: `${job.name} (복사본)`,
        prompt: job.prompt,
        templateId: job.templateId,
        cronExpression: job.cronExpression,
        timezone: job.timezone,
        config: job.config,
      }).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: KEYS.jobs });
    },
  });
}
```

- [ ] **Step 2: 상세 페이지에 복제 버튼 추가**

`ProactiveJobDetailPage.tsx` — import에 `useCloneProactiveJob` 추가:

```ts
import {
  useCloneProactiveJob,
  useCreateProactiveJob,
  useDeleteProactiveJob,
  useExecuteProactiveJob,
  useProactiveJob,
  useProactiveTemplates,
  useUpdateProactiveJob,
} from '@/hooks/queries/useProactiveMessages';
```

import에 `Copy` 아이콘 추가:

```ts
import { ArrowLeft, Copy } from 'lucide-react';
```

컴포넌트 내부에 mutation 추가 (90행 `executeMutation` 다음):

```ts
const cloneMutation = useCloneProactiveJob();
```

복제 핸들러 추가 (`handleExecute` 함수 다음):

```ts
const handleClone = () => {
  if (!job) return;
  cloneMutation.mutate(
    {
      name: job.name,
      prompt: job.prompt,
      templateId: job.templateId,
      cronExpression: job.cronExpression,
      timezone: job.timezone,
      config: job.config,
    },
    {
      onSuccess: (created) => {
        toast.success(`"${created.name}" 작업이 복제되었습니다.`);
        navigate(`/ai-insights/jobs/${created.id}?tab=overview`);
        setIsEditing(true);
      },
      onError: (err) => handleApiError(err, '작업 복제에 실패했습니다.'),
    },
  );
};
```

헤더 버튼 영역 — "지금 실행" 버튼 앞에 복제 버튼 추가 (198행, `<>` 바로 다음):

```tsx
<Button
  variant="outline"
  size="sm"
  onClick={handleClone}
  disabled={cloneMutation.isPending}
>
  <Copy className="h-3.5 w-3.5 mr-1" />
  복제
</Button>
```

- [ ] **Step 3: 목록 페이지에 복제 버튼 추가**

`ProactiveJobListPage.tsx` — import에 추가:

```ts
import { Copy, Play, Plus, Zap } from 'lucide-react';
import { useCloneProactiveJob } from '@/hooks/queries/useProactiveMessages';
```

(기존 `useExecuteProactiveJob` import 다음에 `useCloneProactiveJob` 추가)

컴포넌트 내부에 mutation 추가:

```ts
const cloneMutation = useCloneProactiveJob();
```

복제 핸들러 추가 (`handleExecute` 다음):

```ts
const handleClone = (e: React.MouseEvent, job: ProactiveJob) => {
  e.stopPropagation();
  cloneMutation.mutate(
    {
      name: job.name,
      prompt: job.prompt,
      templateId: job.templateId,
      cronExpression: job.cronExpression,
      timezone: job.timezone,
      config: job.config,
    },
    {
      onSuccess: (created) => {
        toast.success(`"${created.name}" 작업이 복제되었습니다.`);
        navigate(`/ai-insights/jobs/${created.id}?tab=overview`);
      },
      onError: () => toast.error('작업 복제에 실패했습니다.'),
    },
  );
};
```

각 행의 실행 버튼 앞에 복제 버튼 추가 (174행 `<TableCell>` 내부, Play 버튼 앞):

```tsx
<TableCell>
  <div className="flex items-center gap-1">
    <Button
      size="icon"
      variant="ghost"
      className="h-7 w-7"
      aria-label="복제"
      onClick={(e) => handleClone(e, job)}
      disabled={cloneMutation.isPending}
    >
      <Copy className="h-3.5 w-3.5" />
    </Button>
    <Button
      size="icon"
      variant="ghost"
      className="h-7 w-7"
      aria-label="지금 실행"
      onClick={(e) => handleExecute(e, job)}
      disabled={executeMutation.isPending}
    >
      <Play className="h-3.5 w-3.5" />
    </Button>
  </div>
</TableCell>
```

마지막 `<TableHead className="w-[60px]" />`를 `<TableHead className="w-[80px]" />`로 변경하여 두 버튼 수용.

- [ ] **Step 4: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: 타입 에러 없음

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/src/hooks/queries/useProactiveMessages.ts apps/firehub-web/src/pages/ai-insights/ProactiveJobDetailPage.tsx apps/firehub-web/src/pages/ai-insights/ProactiveJobListPage.tsx
git commit -m "feat(proactive): 작업 복제 기능 (목록/상세 페이지)"
```

---

## Task 6: 상세 에러 메시지 (에러 분류 카드)

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/tabs/JobExecutionsTab.tsx:34-51`

- [ ] **Step 1: import 추가**

`JobExecutionsTab.tsx` 상단에 추가:

```ts
import { classifyError } from '@/lib/error-classifier';
```

- [ ] **Step 2: ExecutionResultView의 FAILED 분기 교체**

`JobExecutionsTab.tsx:44-50` — 기존 FAILED 분기:

```tsx
if (execution.status === 'FAILED') {
  return (
    <div className="p-4">
      <p className="text-sm font-medium text-destructive mb-2">실행 실패</p>
      <p className="text-sm text-muted-foreground">{execution.errorMessage ?? '알 수 없는 오류가 발생했습니다.'}</p>
    </div>
  );
}
```

교체:

```tsx
if (execution.status === 'FAILED') {
  const classified = classifyError(execution.errorMessage);
  return (
    <div className="p-4">
      <div className="rounded-lg border border-destructive/50 p-4 space-y-2">
        <div className="flex items-center gap-2">
          <span>{classified.icon}</span>
          <span className="text-sm font-semibold text-destructive">{classified.label}</span>
        </div>
        <p className="text-sm text-muted-foreground">
          {execution.errorMessage ?? '알 수 없는 오류가 발생했습니다.'}
        </p>
        <p className="text-xs text-muted-foreground/70 border-t pt-2">
          💡 {classified.guide}
        </p>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: 타입 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/tabs/JobExecutionsTab.tsx
git commit -m "feat(proactive): 상세 에러 메시지 (에러 유형 분류 + 해결 가이드)"
```

---

## Task 7: 최종 빌드 검증 + Playwright 스크린샷

**Files:** (변경 없음, 검증만)

- [ ] **Step 1: 전체 빌드**

Run: `cd apps/firehub-web && pnpm build`
Expected: 빌드 성공 (exit code 0)

- [ ] **Step 2: 전체 린트**

Run: `cd apps/firehub-web && pnpm lint`
Expected: 에러 없음

- [ ] **Step 3: Playwright로 스마트 작업 목록 페이지 스크린샷**

1. `pnpm dev` 실행
2. Playwright로 `/ai-insights/jobs` 접속 → 스크린샷
3. 복제 버튼, 다음 실행 컬럼 확인

- [ ] **Step 4: Playwright로 스마트 작업 편집 폼 스크린샷**

1. 기존 작업 편집 모드 진입
2. Cron 프리셋 드롭다운 확인 (9개 프리셋)
3. 타임존 셀렉터 드롭다운 확인
4. 다음 실행 시간 인포박스 확인

- [ ] **Step 5: 커밋 (스크린샷)**

```bash
git add snapshots/
git commit -m "test(proactive): Phase 7-0b 스크린샷 검증"
```
