# Phase 6-1: AI Chat Generative UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 챗에 인터랙티브 위젯(데이터셋 미리보기, 리치 테이블)을 렌더링하고, 딥링크 이동과 쿼리 캐시 자동 갱신을 지원한다.

**Architecture:** WidgetRegistry 패턴으로 MCP 도구명 → React 컴포넌트를 매핑. 데이터셋은 Reference 패턴(FE fetch), 테이블은 Passthrough 패턴(AI가 데이터 전달). MessageBubble의 하드코딩을 Registry 룩업으로 교체.

**Tech Stack:** React 19, TanStack Query, Zod v4, Claude Agent SDK MCP, Express SSE

**Spec:** `docs/superpowers/specs/2026-03-28-generative-ui-phase6-1-design.md`

---

## File Structure

### New Files (Frontend)

| File | Responsibility |
|------|---------------|
| `apps/firehub-web/src/components/ai/widgets/types.ts` | WidgetProps, DisplayMode 공통 타입 |
| `apps/firehub-web/src/components/ai/widgets/WidgetRegistry.ts` | 도구명 → React 컴포넌트 매핑 테이블 |
| `apps/firehub-web/src/components/ai/widgets/WidgetShell.tsx` | 공통 카드 래퍼 (헤더/액션/딥링크/반응형) |
| `apps/firehub-web/src/components/ai/widgets/WidgetErrorBoundary.tsx` | 위젯 에러 폴백 UI |
| `apps/firehub-web/src/components/ai/widgets/WidgetSkeleton.tsx` | 로딩 스켈레톤 |
| `apps/firehub-web/src/components/ai/widgets/InlineChartWidgetAdapter.tsx` | 기존 차트 → 새 WidgetProps 어댑터 |
| `apps/firehub-web/src/components/ai/widgets/InlineDatasetWidget.tsx` | 데이터셋 미리보기 위젯 |
| `apps/firehub-web/src/components/ai/widgets/InlineTableWidget.tsx` | 리치 테이블 위젯 |
| `apps/firehub-web/src/components/ai/widgets/NavigateToWidget.tsx` | 딥링크 이동 카드 |
| `apps/firehub-web/src/components/ai/widgets/invalidationMap.ts` | 도구명 → TanStack Query 키 매핑 |

### Modified Files (Frontend)

| File | Changes |
|------|---------|
| `apps/firehub-web/src/components/ai/MessageBubble.tsx` | `renderToolCall()` → WidgetRegistry 룩업으로 교체 |
| `apps/firehub-web/src/hooks/queries/useAIChat.ts` | `tool_result` 핸들러에 invalidation 로직 추가 |
| `apps/firehub-web/src/types/ai.ts` | TOOL_LABELS 에 새 도구 추가 (MessageBubble에서 이동 불필요, TOOL_LABELS는 MessageBubble에 있음) |

### New Files (AI Agent)

| File | Responsibility |
|------|---------------|
| `apps/firehub-ai-agent/src/mcp/tools/ui-tools.ts` | show_dataset_preview, show_table, navigate_to MCP 도구 |
| `apps/firehub-ai-agent/src/mcp/tools/ui-tools.test.ts` | UI 도구 테스트 |

### Modified Files (AI Agent)

| File | Changes |
|------|---------|
| `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts` | ui-tools 등록 |
| `apps/firehub-ai-agent/src/agent/system-prompt.ts` | 새 도구 사용 가이드 추가 |

---

## Task 1: 위젯 공통 타입 + WidgetShell

**Files:**
- Create: `apps/firehub-web/src/components/ai/widgets/types.ts`
- Create: `apps/firehub-web/src/components/ai/widgets/WidgetShell.tsx`
- Create: `apps/firehub-web/src/components/ai/widgets/WidgetErrorBoundary.tsx`
- Create: `apps/firehub-web/src/components/ai/widgets/WidgetSkeleton.tsx`

- [ ] **Step 1: 공통 타입 파일 생성**

```typescript
// apps/firehub-web/src/components/ai/widgets/types.ts
import type { AIMode } from '../../../types/ai';

export interface WidgetProps<T = Record<string, unknown>> {
  input: T;
  onNavigate?: (path: string) => void;
  displayMode: AIMode;
}

export interface WidgetShellProps {
  title: string;
  icon: string;
  subtitle?: string;
  actions?: React.ReactNode;
  navigateTo?: string;
  onNavigate?: (path: string) => void;
  displayMode: AIMode;
  children: React.ReactNode;
}
```

- [ ] **Step 2: WidgetShell 컴포넌트 생성**

```typescript
// apps/firehub-web/src/components/ai/widgets/WidgetShell.tsx
import { ExternalLink } from 'lucide-react';
import { cn } from '../../../lib/utils';
import type { WidgetShellProps } from './types';

const MAX_HEIGHT: Record<string, string> = {
  side: 'max-h-[250px]',
  floating: 'max-h-[250px]',
  fullscreen: 'max-h-[450px]',
};

export function WidgetShell({
  title, icon, subtitle, actions, navigateTo, onNavigate, displayMode, children,
}: WidgetShellProps) {
  return (
    <div className="my-1 overflow-hidden rounded-lg border border-border">
      {/* Header */}
      <div className="flex items-center justify-between gap-2 border-b border-border bg-muted/50 px-3 py-1.5">
        <div className="flex items-center gap-2 min-w-0">
          <span className="shrink-0">{icon}</span>
          <span className="truncate font-medium text-sm">{title}</span>
          {subtitle && <span className="shrink-0 text-xs text-muted-foreground">{subtitle}</span>}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {actions}
          {navigateTo && onNavigate && (
            <button
              onClick={() => onNavigate(navigateTo)}
              className="flex items-center gap-1 text-xs text-primary hover:underline"
            >
              상세 보기
              <ExternalLink className="h-3 w-3" />
            </button>
          )}
        </div>
      </div>
      {/* Body */}
      <div className={cn('overflow-auto', MAX_HEIGHT[displayMode] || MAX_HEIGHT.side)}>
        {children}
      </div>
    </div>
  );
}
```

- [ ] **Step 3: WidgetErrorBoundary 생성**

```typescript
// apps/firehub-web/src/components/ai/widgets/WidgetErrorBoundary.tsx
import type { ReactNode } from 'react';
import { Component } from 'react';

interface State { hasError: boolean }

export class WidgetErrorBoundary extends Component<{ children: ReactNode }, State> {
  constructor(props: { children: ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }
  static getDerivedStateFromError(): State { return { hasError: true }; }
  render() {
    if (this.state.hasError) {
      return (
        <div className="my-1 flex h-20 items-center justify-center rounded-lg border border-border bg-muted text-sm text-muted-foreground">
          위젯을 표시할 수 없습니다.
        </div>
      );
    }
    return this.props.children;
  }
}
```

- [ ] **Step 4: WidgetSkeleton 생성**

```typescript
// apps/firehub-web/src/components/ai/widgets/WidgetSkeleton.tsx
export function WidgetSkeleton({ label }: { label: string }) {
  return (
    <div className="my-1 flex h-20 items-center justify-center rounded-lg border border-border bg-muted">
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <div className="h-4 w-4 animate-spin rounded-full border-2 border-muted-foreground border-t-transparent" />
        {label} 로딩 중...
      </div>
    </div>
  );
}
```

- [ ] **Step 5: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS (새 파일은 아직 import 안 됨, 독립 컴파일 확인)

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-web/src/components/ai/widgets/
git commit -m "feat(web): 위젯 공통 인프라 — types, WidgetShell, ErrorBoundary, Skeleton"
```

---

## Task 2: WidgetRegistry + InlineChartWidgetAdapter + MessageBubble 리팩터링

**Files:**
- Create: `apps/firehub-web/src/components/ai/widgets/WidgetRegistry.ts`
- Create: `apps/firehub-web/src/components/ai/widgets/InlineChartWidgetAdapter.tsx`
- Modify: `apps/firehub-web/src/components/ai/MessageBubble.tsx:236-251`

- [ ] **Step 1: InlineChartWidgetAdapter 생성**

기존 `InlineChartWidget`은 flat props를 받으므로 WidgetProps → flat props 변환 어댑터:

```typescript
// apps/firehub-web/src/components/ai/widgets/InlineChartWidgetAdapter.tsx
import type { ChartConfig, ChartType } from '../../../types/analytics';
import { InlineChartWidget } from '../InlineChartWidget';
import type { WidgetProps } from './types';

interface ShowChartInput {
  sql: string;
  chartType: ChartType;
  config: ChartConfig;
  columns: string[];
  rows: Record<string, unknown>[];
}

export default function InlineChartWidgetAdapter({ input }: WidgetProps<ShowChartInput>) {
  return (
    <InlineChartWidget
      sql={String(input.sql || '')}
      chartType={input.chartType}
      config={input.config}
      columns={input.columns || []}
      rows={input.rows || []}
    />
  );
}
```

- [ ] **Step 2: WidgetRegistry 생성**

```typescript
// apps/firehub-web/src/components/ai/widgets/WidgetRegistry.ts
import { lazy } from 'react';
import type { ComponentType } from 'react';
import type { WidgetProps } from './types';

interface WidgetEntry {
  component: React.LazyExoticComponent<ComponentType<WidgetProps<any>>>;
  label: string;
  icon: string;
}

const WIDGET_REGISTRY: Record<string, WidgetEntry> = {
  show_chart: {
    component: lazy(() => import('./InlineChartWidgetAdapter')),
    label: '차트 표시',
    icon: '📊',
  },
  show_dataset_preview: {
    component: lazy(() => import('./InlineDatasetWidget')),
    label: '데이터셋 미리보기',
    icon: '📦',
  },
  show_table: {
    component: lazy(() => import('./InlineTableWidget')),
    label: '테이블 표시',
    icon: '📋',
  },
  navigate_to: {
    component: lazy(() => import('./NavigateToWidget')),
    label: '페이지 이동',
    icon: '🔗',
  },
};

export function getWidget(toolName: string): WidgetEntry | undefined {
  const cleanName = toolName.replace(/^mcp__firehub__/, '');
  return WIDGET_REGISTRY[cleanName];
}
```

- [ ] **Step 3: MessageBubble 리팩터링**

`apps/firehub-web/src/components/ai/MessageBubble.tsx`를 수정한다.

**import 추가** (파일 상단, 기존 import 영역):

```typescript
import { Suspense } from 'react';
import { useNavigate } from 'react-router-dom';
import { getWidget } from './widgets/WidgetRegistry';
import { WidgetErrorBoundary } from './widgets/WidgetErrorBoundary';
import { WidgetSkeleton } from './widgets/WidgetSkeleton';
import { useAI } from './AIProvider';
```

**기존 import 제거**:
- `import type { ChartConfig, ChartType } from '../../types/analytics';` — 더 이상 직접 사용 안 함
- `import { InlineChartWidget } from './InlineChartWidget';` — Adapter를 통해 lazy import

**`renderToolCall` 함수 교체** (line 236–251):

기존:
```typescript
function renderToolCall(tc: AIToolCall, index: number) {
  const cleanName = tc.name.replace(/^mcp__firehub__/, '');
  if (cleanName === 'show_chart' && tc.input) {
    return (
      <InlineChartWidget
        key={`tool-${index}`}
        sql={String(tc.input.sql || '')}
        chartType={tc.input.chartType as ChartType}
        config={tc.input.config as ChartConfig}
        columns={(tc.input.columns as string[]) || []}
        rows={(tc.input.rows as Record<string, unknown>[]) || []}
      />
    );
  }
  return <ToolCallDisplay key={`tool-${index}`} toolCall={tc} />;
}
```

변경:
```typescript
function RenderToolCall({ tc, index }: { tc: AIToolCall; index: number }) {
  const widget = getWidget(tc.name);
  const navigate = useNavigate();
  const { mode, setMode } = useAI();

  const handleNavigate = (path: string) => {
    if (mode === 'fullscreen') setMode('side');
    navigate(path);
  };

  if (widget && tc.input) {
    const WidgetComponent = widget.component;
    return (
      <Suspense fallback={<WidgetSkeleton label={widget.label} />}>
        <WidgetErrorBoundary>
          <WidgetComponent
            input={tc.input}
            onNavigate={handleNavigate}
            displayMode={mode}
          />
        </WidgetErrorBoundary>
      </Suspense>
    );
  }
  return <ToolCallDisplay toolCall={tc} />;
}
```

**Note**: `renderToolCall` 함수를 `RenderToolCall` 컴포넌트로 변경한다 (hooks 사용을 위해). 호출부도 업데이트:

`AssistantContent` 함수 내 2곳 수정:

기존 (line 271):
```typescript
return (
  <div key={`block-${idx}`} className={cn(idx > 0 && 'mt-1')}>
    {renderToolCall(tc, block.toolCallIndex)}
  </div>
);
```

변경:
```typescript
return (
  <div key={`block-${idx}`} className={cn(idx > 0 && 'mt-1')}>
    <RenderToolCall tc={tc} index={block.toolCallIndex} />
  </div>
);
```

기존 (line 286):
```typescript
{message.toolCalls!.map((tc, i) => renderToolCall(tc, i))}
```

변경:
```typescript
{message.toolCalls!.map((tc, i) => (
  <RenderToolCall key={`tool-${i}`} tc={tc} index={i} />
))}
```

- [ ] **Step 4: 빌드 + 타입체크 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS (InlineDatasetWidget, InlineTableWidget, NavigateToWidget는 아직 없지만 lazy import이므로 빌드 시점에는 문제 없음)

- [ ] **Step 5: show_chart 기존 동작 확인**

기존 AI 채팅에서 차트가 정상 렌더링되는지 수동 확인. Registry를 통한 lazy loading → InlineChartWidgetAdapter → InlineChartWidget 경로가 동작해야 함.

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-web/src/components/ai/widgets/WidgetRegistry.ts \
  apps/firehub-web/src/components/ai/widgets/InlineChartWidgetAdapter.tsx \
  apps/firehub-web/src/components/ai/MessageBubble.tsx
git commit -m "refactor(web): MessageBubble → WidgetRegistry 룩업으로 교체 + ChartAdapter"
```

---

## Task 3: 데이터셋 미리보기 — MCP 도구 (AI Agent)

**Files:**
- Create: `apps/firehub-ai-agent/src/mcp/tools/ui-tools.ts`
- Create: `apps/firehub-ai-agent/src/mcp/tools/ui-tools.test.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts`

- [ ] **Step 1: ui-tools.ts 생성**

```typescript
// apps/firehub-ai-agent/src/mcp/tools/ui-tools.ts
import { z } from 'zod/v4';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerUiTools(
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    // 1. 데이터셋 미리보기 위젯 (프론트엔드에서 직접 API fetch)
    safeTool(
      'show_dataset_preview',
      '채팅에 데이터셋 미리보기 카드를 표시합니다. 데이터셋의 메타정보와 샘플 데이터를 인터랙티브 카드로 보여줍니다.',
      {
        datasetId: z.number().describe('표시할 데이터셋 ID'),
      },
      async (args: { datasetId: number }) => {
        return jsonResult({ displayed: true, datasetId: args.datasetId });
      },
    ),

    // 2. 리치 테이블 위젯 (Passthrough — AI가 데이터 직접 전달)
    safeTool(
      'show_table',
      '채팅에 인터랙티브 테이블을 표시합니다. 정렬, 필터, 페이지네이션, CSV 내보내기를 지원합니다. execute_analytics_query 결과를 테이블로 보여줄 때 사용합니다.',
      {
        title: z.string().optional().describe('테이블 제목 (선택)'),
        sql: z.string().describe('테이블 데이터를 조회한 SQL 쿼리 (참조용)'),
        columns: z.array(z.string()).describe('컬럼 목록'),
        rows: z.array(z.record(z.string(), z.unknown())).max(2000, '최대 2000행까지 지원합니다').describe('데이터 행 배열'),
        totalRows: z.number().optional().describe('전체 행 수 (표시용)'),
      },
      async (args: {
        title?: string;
        sql: string;
        columns: string[];
        rows: Record<string, unknown>[];
        totalRows?: number;
      }) => {
        return jsonResult({
          displayed: true,
          rowCount: args.rows.length,
          totalRows: args.totalRows ?? args.rows.length,
        });
      },
    ),

    // 3. 딥링크 이동 (프론트엔드가 자동으로 페이지 이동)
    safeTool(
      'navigate_to',
      '메인 UI의 특정 페이지로 이동합니다. 데이터셋, 파이프라인, 대시보드를 생성하거나 수정한 후 해당 페이지로 자동 이동할 때 사용합니다.',
      {
        type: z.enum(['dataset', 'pipeline', 'dashboard']).describe('이동할 리소스 타입'),
        id: z.number().describe('리소스 ID'),
        label: z.string().describe('표시할 리소스 이름'),
      },
      async (args: { type: string; id: number; label: string }) => {
        return jsonResult({ navigated: true, type: args.type, id: args.id });
      },
    ),
  ];
}
```

- [ ] **Step 2: firehub-mcp-server.ts에 등록**

`apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts`를 수정:

import 추가:
```typescript
import { registerUiTools } from './tools/ui-tools.js';
```

기존 도구 등록 배열에 추가 (다른 `register*Tools` 호출과 같은 패턴):
```typescript
...registerUiTools(safeTool, jsonResult),
```

- [ ] **Step 3: ui-tools.test.ts 생성**

```typescript
// apps/firehub-ai-agent/src/mcp/tools/ui-tools.test.ts
import { describe, it, expect, beforeAll } from 'vitest';
import { Client } from '@anthropic-ai/claude-agent-sdk/resources/mcp.js';
import { createMcpServer } from '../firehub-mcp-server.js';

// invokeTool 헬퍼 — analytics-tools.test.ts와 동일 패턴
async function invokeTool(server: any, name: string, args: Record<string, unknown>) {
  const result = await server.callTool({ name, arguments: args });
  const textContent = result.content.find((c: any) => c.type === 'text');
  return { ...result, parsed: textContent ? JSON.parse(textContent.text) : null };
}

describe('ui-tools', () => {
  let server: any;

  beforeAll(() => {
    // apiClient는 UI 도구에서 사용하지 않으므로 null 가능
    server = createMcpServer(null as any, 'test-token', 1);
  });

  describe('show_dataset_preview', () => {
    it('should return displayed: true with datasetId', async () => {
      const result = await invokeTool(server, 'show_dataset_preview', { datasetId: 42 });
      expect(result.parsed).toEqual({ displayed: true, datasetId: 42 });
    });
  });

  describe('show_table', () => {
    it('should return displayed: true with row count', async () => {
      const result = await invokeTool(server, 'show_table', {
        sql: 'SELECT * FROM test',
        columns: ['name', 'value'],
        rows: [{ name: 'a', value: 1 }, { name: 'b', value: 2 }],
      });
      expect(result.parsed).toEqual({ displayed: true, rowCount: 2, totalRows: 2 });
    });

    it('should use totalRows when provided', async () => {
      const result = await invokeTool(server, 'show_table', {
        sql: 'SELECT * FROM test LIMIT 2',
        columns: ['name'],
        rows: [{ name: 'a' }, { name: 'b' }],
        totalRows: 1000,
      });
      expect(result.parsed.totalRows).toBe(1000);
    });
  });

  describe('navigate_to', () => {
    it('should return navigated: true', async () => {
      const result = await invokeTool(server, 'navigate_to', {
        type: 'dataset',
        id: 42,
        label: '소방용수_현황',
      });
      expect(result.parsed).toEqual({ navigated: true, type: 'dataset', id: 42 });
    });
  });

  it('should register all UI tools', async () => {
    const tools = await server.listTools();
    const toolNames = tools.tools.map((t: any) => t.name);
    expect(toolNames).toContain('show_dataset_preview');
    expect(toolNames).toContain('show_table');
    expect(toolNames).toContain('navigate_to');
  });
});
```

- [ ] **Step 4: 테스트 실행**

Run: `cd apps/firehub-ai-agent && pnpm test -- src/mcp/tools/ui-tools.test.ts`
Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-ai-agent/src/mcp/tools/ui-tools.ts \
  apps/firehub-ai-agent/src/mcp/tools/ui-tools.test.ts \
  apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts
git commit -m "feat(ai-agent): show_dataset_preview, show_table, navigate_to MCP 도구 추가"
```

---

## Task 4: InlineDatasetWidget (프론트엔드)

**Files:**
- Create: `apps/firehub-web/src/components/ai/widgets/InlineDatasetWidget.tsx`

- [ ] **Step 1: InlineDatasetWidget 구현**

```typescript
// apps/firehub-web/src/components/ai/widgets/InlineDatasetWidget.tsx
import { useQuery } from '@tanstack/react-query';
import { datasetsApi } from '../../../api/datasets';
import { WidgetShell } from './WidgetShell';
import type { WidgetProps } from './types';

interface ShowDatasetPreviewInput {
  datasetId: number;
}

const MAX_PREVIEW_ROWS = 5;
const MAX_COLUMNS: Record<string, number> = {
  side: 4,
  floating: 4,
  fullscreen: 10,
};

export default function InlineDatasetWidget({
  input,
  onNavigate,
  displayMode,
}: WidgetProps<ShowDatasetPreviewInput>) {
  const { datasetId } = input;

  const { data: dataset, isLoading: metaLoading } = useQuery({
    queryKey: ['datasets', datasetId],
    queryFn: () => datasetsApi.getDatasetById(datasetId).then(r => r.data),
    staleTime: 30_000,
  });

  const { data: previewData, isLoading: dataLoading } = useQuery({
    queryKey: ['datasets', datasetId, 'data', { limit: MAX_PREVIEW_ROWS }],
    queryFn: () =>
      datasetsApi.getDatasetData(datasetId, { page: 0, size: MAX_PREVIEW_ROWS }).then(r => r.data),
    staleTime: 30_000,
    enabled: !!dataset,
  });

  if (metaLoading || dataLoading) {
    return (
      <div className="my-1 flex h-20 items-center justify-center rounded-lg border border-border bg-muted text-sm text-muted-foreground">
        <div className="flex items-center gap-2">
          <div className="h-4 w-4 animate-spin rounded-full border-2 border-muted-foreground border-t-transparent" />
          데이터셋 로딩 중...
        </div>
      </div>
    );
  }

  if (!dataset) {
    return (
      <div className="my-1 flex h-16 items-center justify-center rounded-lg border border-border bg-muted text-sm text-muted-foreground">
        데이터셋을 찾을 수 없습니다 (ID: {datasetId})
      </div>
    );
  }

  const columns = dataset.columns || [];
  const maxCols = MAX_COLUMNS[displayMode] || 4;
  const visibleColumns = columns.slice(0, maxCols);
  const hiddenColCount = Math.max(0, columns.length - maxCols);
  const rows = previewData?.content || [];
  const totalRows = dataset.rowCount ?? 0;

  return (
    <WidgetShell
      title={dataset.name}
      icon="📦"
      subtitle={dataset.datasetType}
      navigateTo={`/datasets/${datasetId}`}
      onNavigate={onNavigate}
      displayMode={displayMode}
    >
      {/* Meta row */}
      <div className="flex gap-4 border-b border-border px-3 py-1.5 text-xs text-muted-foreground">
        <span>📐 {columns.length}개 컬럼</span>
        <span>📏 {totalRows.toLocaleString()}행</span>
        {dataset.updatedAt && (
          <span>📅 {new Date(dataset.updatedAt).toLocaleDateString('ko-KR')} 수정</span>
        )}
      </div>

      {/* Sample table */}
      {rows.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-xs">
            <thead>
              <tr className="bg-muted/50">
                {visibleColumns.map(col => (
                  <th key={col.name} className="px-2 py-1.5 text-left font-medium text-muted-foreground">
                    {col.name}
                  </th>
                ))}
                {hiddenColCount > 0 && (
                  <th className="px-2 py-1.5 text-left font-medium text-muted-foreground">
                    +{hiddenColCount}
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {rows.map((row: Record<string, unknown>, i: number) => (
                <tr key={i} className="border-t border-border">
                  {visibleColumns.map(col => (
                    <td key={col.name} className="max-w-[150px] truncate px-2 py-1">
                      {row[col.name] != null ? String(row[col.name]) : ''}
                    </td>
                  ))}
                  {hiddenColCount > 0 && <td className="px-2 py-1 text-muted-foreground">…</td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Footer */}
      <div className="border-t border-border px-3 py-1 text-center text-xs text-muted-foreground">
        {rows.length} / {totalRows.toLocaleString()}건 미리보기
      </div>
    </WidgetShell>
  );
}
```

**Note**: `datasetsApi.getDatasetById`와 `datasetsApi.getDatasetData`의 실제 응답 shape에 맞게 `dataset.columns`, `dataset.rowCount`, `previewData.content` 등을 참조한다. 기존 API 응답 구조 (`DatasetDetailResponse`의 `columns: { name, dataType, ... }[]`, `getDatasetData` 반환의 `content: Record<string, unknown>[]`)와 일치.

- [ ] **Step 2: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/ai/widgets/InlineDatasetWidget.tsx
git commit -m "feat(web): InlineDatasetWidget — 데이터셋 미리보기 위젯"
```

---

## Task 5: InlineTableWidget (프론트엔드)

**Files:**
- Create: `apps/firehub-web/src/components/ai/widgets/InlineTableWidget.tsx`

- [ ] **Step 1: InlineTableWidget 구현**

```typescript
// apps/firehub-web/src/components/ai/widgets/InlineTableWidget.tsx
import { useMemo, useState } from 'react';
import { ChevronDown, ChevronUp, Download, Code2 } from 'lucide-react';
import SyntaxHighlighter from 'react-syntax-highlighter/dist/esm/prism-light';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { downloadBlob } from '../../../lib/download';
import { WidgetShell } from './WidgetShell';
import type { WidgetProps } from './types';

const codeStyle = oneDark as Record<string, React.CSSProperties>;

interface ShowTableInput {
  title?: string;
  sql: string;
  columns: string[];
  rows: Record<string, unknown>[];
  totalRows?: number;
}

type SortDir = 'asc' | 'desc' | null;
const PAGE_SIZE = 50;

export default function InlineTableWidget({
  input,
  displayMode,
}: WidgetProps<ShowTableInput>) {
  const { title, sql, columns, rows, totalRows } = input;
  const total = totalRows ?? rows.length;

  const [sortCol, setSortCol] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<SortDir>(null);
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [page, setPage] = useState(0);
  const [showSql, setShowSql] = useState(false);

  // Filter
  const filtered = useMemo(() => {
    return rows.filter(row =>
      Object.entries(filters).every(([col, val]) => {
        if (!val) return true;
        const cell = row[col];
        return cell != null && String(cell).toLowerCase().includes(val.toLowerCase());
      }),
    );
  }, [rows, filters]);

  // Sort
  const sorted = useMemo(() => {
    if (!sortCol || !sortDir) return filtered;
    return [...filtered].sort((a, b) => {
      const av = a[sortCol], bv = b[sortCol];
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
      return sortDir === 'asc' ? cmp : -cmp;
    });
  }, [filtered, sortCol, sortDir]);

  // Paginate
  const pageCount = Math.ceil(sorted.length / PAGE_SIZE);
  const paged = sorted.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

  function toggleSort(col: string) {
    if (sortCol === col) {
      setSortDir(d => (d === 'asc' ? 'desc' : d === 'desc' ? null : 'asc'));
      if (sortDir === 'desc') setSortCol(null);
    } else {
      setSortCol(col);
      setSortDir('asc');
    }
    setPage(0);
  }

  function handleFilter(col: string, value: string) {
    setFilters(prev => ({ ...prev, [col]: value }));
    setPage(0);
  }

  function exportCsv() {
    const header = columns.join(',');
    const body = sorted.map(row =>
      columns.map(c => {
        const v = row[c];
        const s = v != null ? String(v) : '';
        return s.includes(',') || s.includes('"') || s.includes('\n')
          ? `"${s.replace(/"/g, '""')}"` : s;
      }).join(','),
    ).join('\n');
    const csv = '\uFEFF' + header + '\n' + body;
    downloadBlob(
      `${title || 'query-result'}.csv`,
      new Blob([csv], { type: 'text/csv;charset=utf-8' }),
    );
  }

  const SortIcon = ({ col }: { col: string }) => {
    if (sortCol !== col) return <ChevronDown className="h-3 w-3 opacity-30" />;
    return sortDir === 'asc'
      ? <ChevronUp className="h-3 w-3 text-primary" />
      : <ChevronDown className="h-3 w-3 text-primary" />;
  };

  return (
    <WidgetShell
      title={title || '쿼리 결과'}
      icon="📋"
      subtitle={`${total.toLocaleString()}건`}
      displayMode={displayMode}
      actions={
        <div className="flex items-center gap-1">
          <button onClick={() => setShowSql(v => !v)} className="p-1 text-muted-foreground hover:text-foreground" title="SQL 보기">
            <Code2 className="h-3.5 w-3.5" />
          </button>
          <button onClick={exportCsv} className="p-1 text-muted-foreground hover:text-foreground" title="CSV 내보내기">
            <Download className="h-3.5 w-3.5" />
          </button>
        </div>
      }
    >
      {/* SQL collapse */}
      {showSql && (
        <div className="border-b border-border">
          <SyntaxHighlighter style={codeStyle} language="sql" PreTag="div" customStyle={{ margin: 0, padding: '8px 12px', fontSize: '11px' }}>
            {sql}
          </SyntaxHighlighter>
        </div>
      )}

      {/* Filter row */}
      <div className="flex border-b border-border bg-muted/30">
        {columns.map(col => (
          <input
            key={col}
            className="min-w-[80px] flex-1 border-r border-border bg-transparent px-2 py-1 text-xs placeholder:text-muted-foreground/50 focus:outline-none"
            placeholder={`${col}...`}
            value={filters[col] || ''}
            onChange={e => handleFilter(col, e.target.value)}
          />
        ))}
      </div>

      {/* Table */}
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-xs">
          <thead>
            <tr className="bg-muted/50">
              {columns.map(col => (
                <th
                  key={col}
                  className="cursor-pointer select-none whitespace-nowrap px-2 py-1.5 text-left font-medium text-muted-foreground hover:text-foreground"
                  onClick={() => toggleSort(col)}
                >
                  <span className="flex items-center gap-1">
                    {col}
                    <SortIcon col={col} />
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {paged.map((row, i) => (
              <tr key={i} className="border-t border-border hover:bg-muted/30">
                {columns.map(col => (
                  <td key={col} className="max-w-[200px] truncate px-2 py-1">
                    {row[col] != null ? String(row[col]) : ''}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Pagination footer */}
      <div className="flex items-center justify-between border-t border-border px-3 py-1 text-xs text-muted-foreground">
        <span>
          {sorted.length === 0
            ? '결과 없음'
            : `${page * PAGE_SIZE + 1}–${Math.min((page + 1) * PAGE_SIZE, sorted.length)} / ${sorted.length.toLocaleString()}`}
          {sorted.length !== rows.length && ` (필터: ${rows.length}건 중)`}
        </span>
        {pageCount > 1 && (
          <div className="flex gap-1">
            <button
              className="rounded px-2 py-0.5 border border-border disabled:opacity-30 hover:bg-muted"
              disabled={page === 0}
              onClick={() => setPage(p => p - 1)}
            >
              ← 이전
            </button>
            <button
              className="rounded px-2 py-0.5 border border-border disabled:opacity-30 hover:bg-muted"
              disabled={page >= pageCount - 1}
              onClick={() => setPage(p => p + 1)}
            >
              다음 →
            </button>
          </div>
        )}
      </div>
    </WidgetShell>
  );
}
```

- [ ] **Step 2: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/ai/widgets/InlineTableWidget.tsx
git commit -m "feat(web): InlineTableWidget — 리치 테이블 위젯 (정렬/필터/페이지네이션/CSV)"
```

---

## Task 6: NavigateToWidget + 딥링크

**Files:**
- Create: `apps/firehub-web/src/components/ai/widgets/NavigateToWidget.tsx`

- [ ] **Step 1: NavigateToWidget 구현**

```typescript
// apps/firehub-web/src/components/ai/widgets/NavigateToWidget.tsx
import { useEffect, useRef } from 'react';
import { ExternalLink } from 'lucide-react';
import type { WidgetProps } from './types';

interface NavigateToInput {
  type: 'dataset' | 'pipeline' | 'dashboard';
  id: number;
  label: string;
}

const ROUTE_MAP: Record<string, (id: number) => string> = {
  dataset: (id) => `/datasets/${id}`,
  pipeline: (id) => `/pipelines/${id}`,
  dashboard: (id) => `/analytics/dashboards/${id}`,
};

const TYPE_LABELS: Record<string, string> = {
  dataset: '데이터셋',
  pipeline: '파이프라인',
  dashboard: '대시보드',
};

const TYPE_ICONS: Record<string, string> = {
  dataset: '📦',
  pipeline: '⚙️',
  dashboard: '📊',
};

export default function NavigateToWidget({ input, onNavigate }: WidgetProps<NavigateToInput>) {
  const { type, id, label } = input;
  const path = ROUTE_MAP[type]?.(id);
  const navigated = useRef(false);

  // Auto-navigate on mount (once)
  useEffect(() => {
    if (path && onNavigate && !navigated.current) {
      navigated.current = true;
      onNavigate(path);
    }
  }, [path, onNavigate]);

  if (!path) return null;

  return (
    <div className="my-1 flex items-center gap-2 rounded-lg border border-border bg-muted/50 px-3 py-2 text-sm">
      <span>{TYPE_ICONS[type] || '🔗'}</span>
      <span className="text-muted-foreground">
        {TYPE_LABELS[type] || type}
      </span>
      <button
        onClick={() => onNavigate?.(path)}
        className="flex items-center gap-1 font-medium text-primary hover:underline"
      >
        {label}
        <ExternalLink className="h-3 w-3" />
      </button>
      <span className="text-xs text-muted-foreground">으로 이동했습니다</span>
    </div>
  );
}
```

- [ ] **Step 2: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/components/ai/widgets/NavigateToWidget.tsx
git commit -m "feat(web): NavigateToWidget — 딥링크 자동 이동 카드"
```

---

## Task 7: 쿼리 캐시 자동 갱신

**Files:**
- Create: `apps/firehub-web/src/components/ai/widgets/invalidationMap.ts`
- Modify: `apps/firehub-web/src/hooks/queries/useAIChat.ts:211-220`

- [ ] **Step 1: invalidationMap 생성**

```typescript
// apps/firehub-web/src/components/ai/widgets/invalidationMap.ts

// MCP 도구 실행 후 invalidate할 TanStack Query 키 매핑
// 키: mcp__firehub__ 접두사 제거한 도구 이름
// 값: invalidateQueries에 전달할 queryKey 배열들
const TOOL_INVALIDATION_MAP: Record<string, string[][]> = {
  create_dataset: [['datasets']],
  update_dataset: [['datasets']],
  delete_dataset: [['datasets']],
  truncate_dataset: [['datasets']],
  add_row: [['datasets']],
  add_rows: [['datasets']],
  update_row: [['datasets']],
  delete_rows: [['datasets']],
  replace_dataset_data: [['datasets']],
  create_pipeline: [['pipelines']],
  update_pipeline: [['pipelines']],
  delete_pipeline: [['pipelines']],
  execute_pipeline: [['pipelines']],
  create_trigger: [['pipelines']],
  update_trigger: [['pipelines']],
  delete_trigger: [['pipelines']],
  create_chart: [['charts'], ['dashboards']],
  create_dashboard: [['dashboards']],
  add_chart_to_dashboard: [['dashboards']],
  create_category: [['categories']],
  update_category: [['categories']],
  create_api_connection: [['api-connections']],
  update_api_connection: [['api-connections']],
  delete_api_connection: [['api-connections']],
};

export function getInvalidationKeys(toolName: string): string[][] {
  const cleanName = toolName.replace(/^mcp__firehub__/, '');
  return TOOL_INVALIDATION_MAP[cleanName] || [];
}
```

- [ ] **Step 2: useAIChat의 tool_result 핸들러 수정**

`apps/firehub-web/src/hooks/queries/useAIChat.ts`의 `tool_result` case (line 211-220)를 수정:

import 추가:
```typescript
import { getInvalidationKeys } from '../../components/ai/widgets/invalidationMap';
```

기존:
```typescript
case 'tool_result':
  if (streamingContentRef.current) {
    const toolCalls = [...(streamingContentRef.current.toolCalls || [])];
    if (toolCalls.length > 0) {
      toolCalls[toolCalls.length - 1].result = event.result;
    }
    streamingContentRef.current = { ...streamingContentRef.current, toolCalls };
    setStreamingMessage({ ...streamingContentRef.current });
  }
  break;
```

변경:
```typescript
case 'tool_result':
  if (streamingContentRef.current) {
    const toolCalls = [...(streamingContentRef.current.toolCalls || [])];
    if (toolCalls.length > 0) {
      const lastTool = toolCalls[toolCalls.length - 1];
      lastTool.result = event.result;

      // Auto-invalidate TanStack Query cache
      const keys = getInvalidationKeys(lastTool.name);
      for (const key of keys) {
        queryClient.invalidateQueries({ queryKey: key });
      }
    }
    streamingContentRef.current = { ...streamingContentRef.current, toolCalls };
    setStreamingMessage({ ...streamingContentRef.current });
  }
  break;
```

- [ ] **Step 3: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/components/ai/widgets/invalidationMap.ts \
  apps/firehub-web/src/hooks/queries/useAIChat.ts
git commit -m "feat(web): 쿼리 캐시 자동 갱신 — AI 도구 실행 후 TanStack Query invalidation"
```

---

## Task 8: 시스템 프롬프트 + TOOL_LABELS 업데이트

**Files:**
- Modify: `apps/firehub-ai-agent/src/agent/system-prompt.ts`
- Modify: `apps/firehub-web/src/components/ai/MessageBubble.tsx` (TOOL_LABELS)

- [ ] **Step 1: system-prompt.ts에 새 도구 가이드 추가**

`apps/firehub-ai-agent/src/agent/system-prompt.ts`의 `[분석]` 섹션 마지막(show_chart 설명 뒤)에 추가:

```
- show_dataset_preview: 채팅에 데이터셋 미리보기 카드를 표시합니다. datasetId를 전달하면 프론트엔드가 메타정보와 샘플 데이터를 카드로 보여줍니다. 데이터셋 정보를 텍스트로 나열하는 대신 이 도구를 사용하세요.
- show_table: 채팅에 인터랙티브 테이블을 표시합니다. execute_analytics_query 결과를 테이블로 보여줄 때 사용합니다. 정렬/필터/페이지네이션/CSV 내보내기를 지원합니다. columns와 rows는 조회 결과를 그대로 전달하세요.
- navigate_to: 메인 UI의 특정 페이지로 이동합니다. 데이터셋/파이프라인/대시보드를 생성하거나 수정한 후 해당 페이지로 자동 이동할 때 사용합니다.
```

`[Text-to-SQL 자동 실행]` 섹션의 5번 항목 뒤에 추가:

```
5-1. 결과가 원본 데이터 확인 목적이면 show_table을 호출하여 테이블로 표시합니다
5-2. 사용자가 특정 데이터셋에 대해 물어보면 show_dataset_preview를 호출하여 미리보기 카드를 표시합니다
5-3. 리소스를 생성/수정한 후 navigate_to를 호출하여 해당 페이지로 이동합니다
```

도구 사용 구분 규칙 추가 (차트 추천 가이드라인 뒤):

```
[위젯 사용 구분]
- 데이터를 시각화(차트)로 보여줄 때: show_chart
- 데이터를 원본 테이블로 보여줄 때: show_table
- 데이터셋 정보(메타+샘플)를 보여줄 때: show_dataset_preview
- 리소스 생성/수정 후 해당 페이지로 이동: navigate_to
- 텍스트로 나열하는 것보다 위젯 도구를 우선 사용하세요
```

- [ ] **Step 2: MessageBubble.tsx TOOL_LABELS 추가**

`apps/firehub-web/src/components/ai/MessageBubble.tsx`의 `TOOL_LABELS` 객체 (line 28-91)에서 `show_chart` 항목 아래에 추가:

```typescript
show_dataset_preview: { label: '데이터셋 미리보기', icon: '📦' },
show_table: { label: '테이블 표시', icon: '📋' },
navigate_to: { label: '페이지 이동', icon: '🔗' },
```

- [ ] **Step 3: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck && cd ../../apps/firehub-ai-agent && pnpm typecheck`
Expected: 둘 다 PASS

- [ ] **Step 4: AI Agent 전체 테스트**

Run: `cd apps/firehub-ai-agent && pnpm test`
Expected: 전체 PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/system-prompt.ts \
  apps/firehub-web/src/components/ai/MessageBubble.tsx
git commit -m "feat(ai-agent,web): 시스템 프롬프트 + TOOL_LABELS — Generative UI 도구 가이드"
```

---

## Task 9: 통합 테스트 + E2E 검증

**Files:** (수정 없음 — 검증만)

- [ ] **Step 1: Frontend 전체 빌드**

Run: `cd apps/firehub-web && pnpm build`
Expected: PASS (0 errors)

- [ ] **Step 2: AI Agent 전체 테스트**

Run: `cd apps/firehub-ai-agent && pnpm test`
Expected: 전체 PASS (기존 + ui-tools 테스트)

- [ ] **Step 3: 로컬 환경 E2E 수동 검증**

`pnpm dev`로 전체 서버 시작 후:

1. AI 채팅에서 "소방용수 데이터셋 보여줘" 입력
   - Expected: `show_dataset_preview` 호출 → InlineDatasetWidget 카드 렌더링 (메타 + 샘플 5행)
2. "상세 보기 →" 클릭
   - Expected: 메인 UI가 `/datasets/{id}`로 이동, 사이드 패널 챗 유지
3. "소방용수 데이터 전부 조회해줘" 입력
   - Expected: `execute_analytics_query` → `show_table` 호출 → InlineTableWidget 렌더링
4. 테이블에서 정렬/필터/페이지네이션/CSV 내보내기 테스트
5. "소방용수 데이터셋에 새 데이터 추가해줘" → AI가 `add_rows` 실행
   - Expected: 메인 UI 데이터셋 목록이 자동 갱신됨
6. 기존 "차트로 보여줘" 요청 → show_chart 정상 동작 확인 (레그레션 없음)
7. 사이드/플로팅/전체화면 3모드에서 위젯 크기 확인

- [ ] **Step 4: 최종 커밋 (필요 시 수정사항 반영)**

검증 중 발견된 이슈가 있으면 수정 후 커밋.

---

## Summary

| Task | 설명 | 범위 |
|------|------|------|
| 1 | 위젯 공통 인프라 (types, Shell, ErrorBoundary, Skeleton) | Frontend |
| 2 | WidgetRegistry + ChartAdapter + MessageBubble 리팩터링 | Frontend |
| 3 | MCP 도구 3종 (show_dataset_preview, show_table, navigate_to) | AI Agent |
| 4 | InlineDatasetWidget (데이터셋 미리보기) | Frontend |
| 5 | InlineTableWidget (리치 테이블) | Frontend |
| 6 | NavigateToWidget (딥링크 자동 이동) | Frontend |
| 7 | 쿼리 캐시 자동 갱신 (invalidationMap) | Frontend |
| 8 | 시스템 프롬프트 + TOOL_LABELS 업데이트 | AI Agent + Frontend |
| 9 | 통합 테스트 + E2E 검증 | 전체 |
