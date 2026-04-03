# Phase 7-6: 목표 기반 리포트 생성 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 비즈니스 질문("매출이 왜 떨어졌는지 분석해줘")에서 출발하여 AI가 데이터 탐색 + 리포트 생성을 수행하고, 그 결과를 챗 위젯으로 표시하거나 스마트 작업으로 저장할 수 있게 한다.

**Architecture:** AI Agent에 `generate_report`, `save_as_smart_job`, `show_report_builder` MCP 도구 3종을 추가. 프론트엔드에 `ReportBuilderWidget` (WidgetShell 기반)을 등록하고, 스마트 작업 생성 화면에 "목표 기반" 모드를 추가하여 AI가 비즈니스 질문으로부터 템플릿을 자동 생성하게 한다.

**Tech Stack:** TypeScript (AI Agent + Frontend), shadcn/ui (RadioGroup, Textarea, Button, Collapsible, ScrollArea, Skeleton), Vitest + nock (테스트)

**설계 문서:** `docs/superpowers/specs/2026-04-03-phase-7-layer2-design.md` (섹션 5, 6.4, 7.2, 7.3, 8.6, 8.7)

**실행 순서:**
```
Task 1 (AI Agent API 클라이언트 확장)
  ↓
Task 2 (generate_report MCP 도구) + Task 3 (save_as_smart_job MCP 도구) — 병렬
  ↓
Task 4 (show_report_builder UI 도구)
  ↓
Task 5 (시스템 프롬프트 업데이트)
  ↓
Task 6 (MCP 도구 테스트)
  ↓
Task 7 (ReportBuilderWidget 프론트엔드)
  ↓
Task 8 (스마트 작업 "목표 기반" 모드 — 프론트엔드)
  ↓
Task 9 (통합 검증)
```

---

### Task 1: AI Agent API 클라이언트 확장

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/api-client/proactive-api.ts`

- [ ] **Step 1: createReportTemplate 스키마 확장**

`apps/firehub-ai-agent/src/mcp/api-client/proactive-api.ts`의 `createReportTemplate` 메서드에 `style` 파라미터를 추가:

```typescript
    async createReportTemplate(data: {
      name: string;
      description?: string;
      style?: string;
      structure: {
        sections: Array<{
          key: string;
          label: string;
          required?: boolean;
          type?: string;
          instruction?: string;
          static?: boolean;
          content?: string;
          children?: unknown[];
        }>;
        output_format: string;
      };
    }): Promise<unknown> {
      const response = await client.post('/proactive/templates', data);
      return response.data;
    },
```

- [ ] **Step 2: createSmartJob 스키마 확장**

같은 파일의 `createSmartJob` 메서드에 `templateStructure` 파라미터를 추가 (인라인 템플릿 생성 + 작업 생성을 한 번에 처리하기 위함):

```typescript
    async createSmartJobWithTemplate(data: {
      name: string;
      prompt: string;
      cronExpression?: string;
      timezone?: string;
      channels?: string[];
      templateName: string;
      templateStructure: {
        sections: Array<{
          key: string;
          label: string;
          required?: boolean;
          type?: string;
          instruction?: string;
          children?: unknown[];
        }>;
        output_format: string;
      };
      templateStyle?: string;
    }): Promise<unknown> {
      // 1. 템플릿 생성
      const template = await client.post('/proactive/templates', {
        name: data.templateName,
        description: `AI 자동 생성 — "${data.prompt}"`,
        style: data.templateStyle,
        structure: data.templateStructure,
      });
      const templateId = (template.data as { id: number }).id;
      // 2. 스마트 작업 생성 (cronExpression이 없으면 비활성 상태로)
      const jobPayload: Record<string, unknown> = {
        name: data.name,
        prompt: data.prompt,
        templateId,
        cronExpression: data.cronExpression ?? '0 9 * * *',
        timezone: data.timezone ?? 'Asia/Seoul',
        config: {
          channels: (data.channels ?? ['CHAT']).map((ch) => ({
            type: ch,
            recipientUserIds: [],
            recipientEmails: [],
          })),
        },
      };
      if (!data.cronExpression) {
        jobPayload.enabled = false;
      }
      const job = await client.post('/proactive/jobs', jobPayload);
      return { template: template.data, job: job.data };
    },
```

- [ ] **Step 3: 타입체크 실행**

Run: `cd apps/firehub-ai-agent && pnpm typecheck`
Expected: PASS

---

### Task 2: generate_report MCP 도구

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/tools/proactive-tools.ts`

- [ ] **Step 1: generate_report 도구 추가**

`apps/firehub-ai-agent/src/mcp/tools/proactive-tools.ts`의 `return [` 배열 마지막(기존 `create_report_template` 뒤)에 추가:

```typescript
    safeTool(
      'generate_report',
      '비즈니스 질문을 기반으로 데이터를 탐색하고 구조화된 리포트 섹션을 생성합니다. 사용자가 분석 요청을 하면 이 도구를 사용하세요. 결과는 챗에 인라인 리포트 위젯으로 표시됩니다.',
      {
        question: z.string().describe('비즈니스 질문 (예: "매출이 왜 떨어졌는지 분석해줘")'),
        datasetIds: z
          .array(z.number())
          .optional()
          .describe('분석 대상 데이터셋 ID 목록. 생략 시 AI가 자동 탐색'),
        templateStructure: z
          .object({
            sections: z.array(
              z.object({
                key: z.string().describe('섹션 키'),
                label: z.string().describe('섹션 레이블'),
                type: z.string().describe('섹션 타입 (text, cards, list, table, chart, recommendation 등)'),
                instruction: z.string().optional().describe('이 섹션에서 분석할 내용'),
                required: z.boolean().optional().describe('필수 여부'),
              }),
            ),
            output_format: z.string().optional().describe('출력 형식 (기본: markdown)'),
          })
          .describe('리포트 구조 (AI가 질문에 맞게 설계)'),
        sectionContents: z
          .record(z.string(), z.string())
          .describe('각 섹션 key에 대한 분석 결과 텍스트 (마크다운)'),
        style: z.string().optional().describe('리포트 작성 스타일 (예: "간결한 경영진 보고 스타일")'),
      },
      async (args: {
        question: string;
        datasetIds?: number[];
        templateStructure: {
          sections: Array<{
            key: string;
            label: string;
            type: string;
            instruction?: string;
            required?: boolean;
          }>;
          output_format?: string;
        };
        sectionContents: Record<string, string>;
        style?: string;
      }) => {
        return jsonResult({
          displayed: true,
          question: args.question,
          datasetIds: args.datasetIds ?? [],
          templateStructure: args.templateStructure,
          sectionContents: args.sectionContents,
          style: args.style,
        });
      },
    ),
```

- [ ] **Step 2: 타입체크 실행**

Run: `cd apps/firehub-ai-agent && pnpm typecheck`
Expected: PASS

---

### Task 3: save_as_smart_job MCP 도구

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/tools/proactive-tools.ts`

- [ ] **Step 1: save_as_smart_job 도구 추가**

같은 파일의 `generate_report` 뒤에 추가:

```typescript
    safeTool(
      'save_as_smart_job',
      '챗에서 생성한 분석을 스마트 작업 + 리포트 양식으로 저장합니다. generate_report로 생성한 리포트를 반복 실행하고 싶을 때 사용합니다.',
      {
        name: z.string().describe('스마트 작업 이름'),
        templateStructure: z
          .object({
            sections: z.array(
              z.object({
                key: z.string().describe('섹션 키'),
                label: z.string().describe('섹션 레이블'),
                type: z.string().describe('섹션 타입'),
                instruction: z.string().optional().describe('섹션별 AI 지시'),
                required: z.boolean().optional().describe('필수 여부'),
              }),
            ),
            output_format: z.string().optional().describe('출력 형식'),
          })
          .describe('리포트 양식 구조'),
        prompt: z.string().describe('AI 분석 프롬프트'),
        style: z.string().optional().describe('리포트 작성 스타일'),
        cronExpression: z
          .string()
          .optional()
          .describe('반복 실행 Cron 표현식 (생략 시 수동 실행 전용)'),
        channels: z
          .array(z.string())
          .optional()
          .describe('전달 채널 (기본: ["CHAT"])'),
      },
      async (args: {
        name: string;
        templateStructure: {
          sections: Array<{
            key: string;
            label: string;
            type: string;
            instruction?: string;
            required?: boolean;
          }>;
          output_format?: string;
        };
        prompt: string;
        style?: string;
        cronExpression?: string;
        channels?: string[];
      }) => {
        const result = await apiClient.createSmartJobWithTemplate({
          name: args.name,
          prompt: args.prompt,
          cronExpression: args.cronExpression,
          channels: args.channels,
          templateName: `${args.name} 양식`,
          templateStructure: {
            sections: args.templateStructure.sections,
            output_format: args.templateStructure.output_format ?? 'markdown',
          },
          templateStyle: args.style,
        });
        return jsonResult(result);
      },
    ),
```

- [ ] **Step 2: 타입체크 실행**

Run: `cd apps/firehub-ai-agent && pnpm typecheck`
Expected: PASS

---

### Task 4: show_report_builder UI 도구

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/tools/ui-tools.ts`

- [ ] **Step 1: show_report_builder 도구 추가**

`apps/firehub-ai-agent/src/mcp/tools/ui-tools.ts`의 기존 도구 배열 마지막에 추가:

```typescript
    // 10. 리포트 빌더 위젯 (Passthrough — AI가 구조 + 콘텐츠 직접 전달)
    safeTool(
      'show_report_builder',
      '채팅에 AI가 생성한 리포트를 인라인 위젯으로 표시합니다. 섹션 구조와 분석 결과를 미리보기로 보여주고, 스마트 작업 저장 및 편집 링크를 제공합니다.',
      {
        question: z.string().describe('원본 비즈니스 질문'),
        templateStructure: z
          .object({
            sections: z.array(
              z.object({
                key: z.string(),
                label: z.string(),
                type: z.string(),
                instruction: z.string().optional(),
                required: z.boolean().optional(),
              }),
            ),
            output_format: z.string().optional(),
          })
          .describe('리포트 양식 구조'),
        sectionContents: z
          .record(z.string(), z.string())
          .describe('섹션별 분석 결과 (key → markdown 텍스트)'),
        style: z.string().optional().describe('리포트 스타일'),
        prompt: z.string().optional().describe('분석 프롬프트'),
        canvas: canvasSchema,
      },
      async (args: {
        question: string;
        templateStructure: {
          sections: Array<{
            key: string;
            label: string;
            type: string;
            instruction?: string;
            required?: boolean;
          }>;
          output_format?: string;
        };
        sectionContents: Record<string, string>;
        style?: string;
        prompt?: string;
      }) => {
        return jsonResult({
          displayed: true,
          question: args.question,
          sectionCount: args.templateStructure.sections.length,
        });
      },
    ),
```

- [ ] **Step 2: 타입체크 실행**

Run: `cd apps/firehub-ai-agent && pnpm typecheck`
Expected: PASS

---

### Task 5: 시스템 프롬프트 업데이트

**Files:**
- Modify: `apps/firehub-ai-agent/src/agent/system-prompt.ts`

- [ ] **Step 1: 목표 기반 리포트 도구 안내 추가**

`apps/firehub-ai-agent/src/agent/system-prompt.ts`의 `[AI 인사이트]` 섹션(기존 `create_report_template` 이후)에 새 도구 설명 추가:

```typescript
- generate_report: 비즈니스 질문 기반 리포트 생성 (질문 → 데이터 탐색 → 섹션별 분석 → 인라인 위젯)
- save_as_smart_job: 챗에서 생성한 분석을 스마트 작업으로 저장 (양식 + 작업 자동 생성)
- show_report_builder: 생성된 리포트를 채팅에 인라인 위젯으로 표시
```

- [ ] **Step 2: 목표 기반 리포트 가이드 추가**

같은 파일에서 `스마트 작업 사용 예시:` 블록 아래에 목표 기반 분석 워크플로우 가이드 추가:

```typescript
목표 기반 리포트 워크플로우:
사용자가 비즈니스 질문을 하면 다음 순서로 처리하세요:
1. list_datasets로 관련 데이터셋 탐색 (datasetIds 미지정 시)
2. query_dataset_data 또는 execute_sql_query로 데이터 수집/분석
3. 분석 결과를 섹션으로 구조화하여 generate_report 호출
4. show_report_builder로 결과를 인라인 위젯으로 표시
5. "이 분석을 스마트 작업으로 저장할까요?" 제안
6. 사용자 승인 시 save_as_smart_job 호출

예시:
- "매출이 왜 떨어졌는지 분석해줘" → 데이터 탐색 → 분석 → generate_report → show_report_builder
- "이 분석을 매주 받고 싶어" → save_as_smart_job (cronExpression: "0 9 * * 1")
```

- [ ] **Step 3: 타입체크 실행**

Run: `cd apps/firehub-ai-agent && pnpm typecheck`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-ai-agent/src/mcp/tools/proactive-tools.ts \
  apps/firehub-ai-agent/src/mcp/tools/ui-tools.ts \
  apps/firehub-ai-agent/src/mcp/api-client/proactive-api.ts \
  apps/firehub-ai-agent/src/agent/system-prompt.ts
git commit -m "feat(proactive): 목표 기반 리포트 MCP 도구 추가 (generate_report, save_as_smart_job, show_report_builder)"
```

---

### Task 6: MCP 도구 테스트

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/tools/ui-tools.test.ts`
- Create: `apps/firehub-ai-agent/src/mcp/tools/proactive-tools.test.ts`

- [ ] **Step 1: proactive-tools.test.ts 작성**

`apps/firehub-ai-agent/src/mcp/tools/proactive-tools.test.ts` 생성:

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import { FireHubApiClient } from '../api-client.js';

function createMockClient(): FireHubApiClient {
  const client = Object.create(FireHubApiClient.prototype);
  const methodNames = Object.getOwnPropertyNames(FireHubApiClient.prototype).filter(
    (name) => name !== 'constructor',
  );
  for (const name of methodNames) {
    client[name] = vi.fn().mockResolvedValue({ mocked: true });
  }
  return client as FireHubApiClient;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function invokeTool(server: any, toolName: string, args: Record<string, unknown> = {}) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const instance = server.instance as any;
  const entry = instance._registeredTools[toolName];
  if (!entry) throw new Error(`Tool ${toolName} not found in registered tools`);
  return entry.handler(args, {});
}

describe('Proactive MCP Tools', () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;
  let mockClient: FireHubApiClient;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    mockClient = createMockClient();
    server = createFireHubMcpServer(mockClient);
  });

  describe('generate_report', () => {
    it('returns displayed: true with question and sections', async () => {
      const result = await invokeTool(server, 'generate_report', {
        question: '매출이 왜 떨어졌는지 분석해줘',
        templateStructure: {
          sections: [
            { key: 'summary', label: '요약', type: 'text', instruction: '핵심 요약' },
            { key: 'analysis', label: '상세 분석', type: 'text' },
          ],
          output_format: 'markdown',
        },
        sectionContents: {
          summary: '매출이 전주 대비 15% 감소했습니다.',
          analysis: '주요 원인은 프로모션 종료입니다.',
        },
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.question).toBe('매출이 왜 떨어졌는지 분석해줘');
      expect(parsed.templateStructure.sections).toHaveLength(2);
      expect(parsed.sectionContents.summary).toContain('15%');
    });

    it('handles optional datasetIds', async () => {
      const result = await invokeTool(server, 'generate_report', {
        question: '파이프라인 현황 분석',
        datasetIds: [1, 5, 12],
        templateStructure: {
          sections: [{ key: 'status', label: '현황', type: 'cards' }],
        },
        sectionContents: { status: 'KPI 카드 내용' },
      });

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.datasetIds).toEqual([1, 5, 12]);
    });
  });

  describe('save_as_smart_job', () => {
    it('calls createSmartJobWithTemplate and returns result', async () => {
      const mockResult = {
        template: { id: 10, name: '매출 분석 양식' },
        job: { id: 20, name: '매출 분석' },
      };
      (mockClient.createSmartJobWithTemplate as ReturnType<typeof vi.fn>).mockResolvedValue(
        mockResult,
      );

      const result = await invokeTool(server, 'save_as_smart_job', {
        name: '매출 분석',
        templateStructure: {
          sections: [
            { key: 'summary', label: '요약', type: 'text' },
          ],
        },
        prompt: '매출 추이를 분석하세요',
        cronExpression: '0 9 * * 1',
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.template.id).toBe(10);
      expect(parsed.job.id).toBe(20);
      expect(mockClient.createSmartJobWithTemplate).toHaveBeenCalledWith(
        expect.objectContaining({
          name: '매출 분석',
          prompt: '매출 추이를 분석하세요',
          cronExpression: '0 9 * * 1',
          templateName: '매출 분석 양식',
        }),
      );
    });

    it('works without cronExpression (manual execution only)', async () => {
      (mockClient.createSmartJobWithTemplate as ReturnType<typeof vi.fn>).mockResolvedValue({
        template: { id: 11 },
        job: { id: 21, enabled: false },
      });

      const result = await invokeTool(server, 'save_as_smart_job', {
        name: '일회성 분석',
        templateStructure: {
          sections: [{ key: 'result', label: '결과', type: 'text' }],
        },
        prompt: '데이터 분석',
      });

      expect(result.isError).toBeFalsy();
      expect(mockClient.createSmartJobWithTemplate).toHaveBeenCalledWith(
        expect.objectContaining({
          cronExpression: undefined,
        }),
      );
    });
  });
});
```

- [ ] **Step 2: ui-tools.test.ts에 show_report_builder 테스트 추가**

`apps/firehub-ai-agent/src/mcp/tools/ui-tools.test.ts`의 마지막 `describe` 블록 뒤에 추가:

```typescript
  // --- show_report_builder ---
  describe('show_report_builder', () => {
    it('returns displayed: true with section count', async () => {
      const result = await invokeTool(server, 'show_report_builder', {
        question: '매출 분석',
        templateStructure: {
          sections: [
            { key: 'summary', label: '요약', type: 'text' },
            { key: 'detail', label: '상세', type: 'table' },
            { key: 'recommend', label: '권고', type: 'recommendation' },
          ],
        },
        sectionContents: {
          summary: '매출 요약 내용',
          detail: '| 항목 | 금액 |\n|--|--|\n| A | 100 |',
          recommend: '마케팅 강화를 권고합니다',
        },
      });

      expect(result.isError).toBeFalsy();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.displayed).toBe(true);
      expect(parsed.sectionCount).toBe(3);
      expect(parsed.question).toBe('매출 분석');
    });
  });
```

- [ ] **Step 3: 테스트 실행**

Run: `cd apps/firehub-ai-agent && pnpm test -- src/mcp/tools/proactive-tools.test.ts src/mcp/tools/ui-tools.test.ts`
Expected: ALL PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-ai-agent/src/mcp/tools/proactive-tools.test.ts \
  apps/firehub-ai-agent/src/mcp/tools/ui-tools.test.ts
git commit -m "test(proactive): generate_report, save_as_smart_job, show_report_builder MCP 도구 테스트"
```

---

### Task 7: ReportBuilderWidget 프론트엔드

**Files:**
- Create: `apps/firehub-web/src/components/ai/widgets/ReportBuilderWidget.tsx`
- Modify: `apps/firehub-web/src/components/ai/widgets/WidgetRegistry.ts`

- [ ] **Step 1: ReportBuilderWidget 컴포넌트 작성**

`apps/firehub-web/src/components/ai/widgets/ReportBuilderWidget.tsx` 생성:

```typescript
import { FileText, Pencil, Save } from 'lucide-react';
import { useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { getSectionTypeDef } from '@/lib/template-section-types';

import { WidgetShell } from './WidgetShell';
import type { WidgetProps } from './types';

interface ReportSection {
  key: string;
  label: string;
  type: string;
  instruction?: string;
  required?: boolean;
}

interface ReportBuilderInput {
  question: string;
  templateStructure: {
    sections: ReportSection[];
    output_format?: string;
  };
  sectionContents: Record<string, string>;
  style?: string;
  prompt?: string;
}

export default function ReportBuilderWidget({
  input,
  onNavigate,
  displayMode,
}: WidgetProps<ReportBuilderInput>) {
  const [showSections, setShowSections] = useState(true);
  const sections = input.templateStructure?.sections ?? [];
  const contents = input.sectionContents ?? {};

  const handleSaveAsJob = () => {
    // Encode the template structure as query params for the smart job creation page
    const params = new URLSearchParams({
      mode: 'goal',
      question: input.question,
      templateStructure: JSON.stringify(input.templateStructure),
      prompt: input.prompt ?? input.question,
      style: input.style ?? '',
    });
    onNavigate?.(`/ai-insights/jobs/new?${params.toString()}`);
  };

  const handleEdit = () => {
    // Navigate to template builder with pre-filled structure
    const params = new URLSearchParams({
      prefill: JSON.stringify(input.templateStructure),
      style: input.style ?? '',
    });
    onNavigate?.(`/ai-insights/templates/new?${params.toString()}`);
  };

  return (
    <WidgetShell
      title="AI 리포트"
      icon="📄"
      subtitle={`${sections.length}개 섹션`}
      displayMode={displayMode}
      actions={
        <div className="flex items-center gap-1">
          <Button variant="outline" size="sm" className="h-6 text-xs gap-1" onClick={handleEdit}>
            <Pencil className="h-3 w-3" />
            편집하기
          </Button>
          <Button variant="outline" size="sm" className="h-6 text-xs gap-1" onClick={handleSaveAsJob}>
            <Save className="h-3 w-3" />
            스마트 작업으로 저장
          </Button>
        </div>
      }
    >
      <div className="p-3 space-y-3">
        {/* 원본 질문 */}
        <div className="text-xs text-muted-foreground">
          <span className="font-medium">질문:</span> {input.question}
        </div>

        {/* 섹션 미리보기 */}
        <ScrollArea className={displayMode === 'fullscreen' ? 'max-h-[380px]' : 'max-h-[180px]'}>
          <div className="space-y-2">
            {sections.map((section) => {
              const def = getSectionTypeDef(section.type);
              const content = contents[section.key];
              return (
                <div
                  key={section.key}
                  className={`p-2.5 bg-muted/30 rounded-md border-l-3 ${def?.color ?? 'border-l-muted-foreground'}`}
                >
                  <div className="flex items-center justify-between mb-1">
                    <div className="flex items-center gap-1.5">
                      <span className="text-xs">{def?.icon ?? '📝'}</span>
                      <span className="text-sm font-medium">{section.label}</span>
                    </div>
                    {section.required && (
                      <Badge variant="secondary" className="text-[10px] h-4">필수</Badge>
                    )}
                  </div>
                  {content && (
                    <p className="text-xs text-muted-foreground line-clamp-3 whitespace-pre-wrap">
                      {content}
                    </p>
                  )}
                </div>
              );
            })}
          </div>
        </ScrollArea>
      </div>
    </WidgetShell>
  );
}
```

- [ ] **Step 2: WidgetRegistry에 show_report_builder 등록**

`apps/firehub-web/src/components/ai/widgets/WidgetRegistry.ts`의 `WIDGET_REGISTRY` 객체에 추가:

```typescript
  show_report_builder: {
    component: lazy(() => import('./ReportBuilderWidget')),
    label: 'AI 리포트',
    icon: '📄',
  },
```

- [ ] **Step 3: 프론트엔드 타입체크 + 빌드 실행**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/components/ai/widgets/ReportBuilderWidget.tsx \
  apps/firehub-web/src/components/ai/widgets/WidgetRegistry.ts
git commit -m "feat(proactive): AI 리포트 인라인 위젯 (ReportBuilderWidget + WidgetRegistry 등록)"
```

---

### Task 8: 스마트 작업 "목표 기반" 모드

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx`
- Modify: `apps/firehub-web/src/api/proactive.ts`
- Modify: `apps/firehub-web/src/lib/validations/proactive-job.ts`

- [ ] **Step 1: proactive API에 generateTemplateFromQuestion 추가**

`apps/firehub-web/src/api/proactive.ts`의 `proactiveApi` 객체에 AI 에이전트 호출 메서드 추가. 기존 AI chat SSE 인프라를 활용하여 단발성 질문을 보내고 응답에서 템플릿 구조를 추출:

```typescript
  // Goal-based template generation via AI agent
  generateTemplateFromQuestion: (question: string) =>
    client.post<{ templateStructure: Record<string, unknown>; prompt: string; style: string }>(
      '/proactive/templates/generate',
      { question },
    ),
```

- [ ] **Step 2: ProactiveJobFormValues 스키마에 goalMode 필드 추가**

`apps/firehub-web/src/lib/validations/proactive-job.ts`에서 스키마 확장:

```typescript
  goalMode: z.enum(['manual', 'goal']).optional().default('manual'),
  businessQuestion: z.string().optional(),
  generatedTemplateStructure: z.any().optional(),
```

- [ ] **Step 3: JobOverviewTab에 모드 전환 RadioGroup 추가**

`apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx`의 편집 모드 JSX 상단(작업명 입력 위)에 모드 전환 UI 추가. import 추가:

```typescript
import { Sparkles } from 'lucide-react';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { Skeleton } from '@/components/ui/skeleton';
import { SectionPreview } from '../components/SectionPreview';
import { proactiveApi, type TemplateSection } from '@/api/proactive';
```

편집 모드의 `return` 문 첫 번째 children으로 모드 전환 RadioGroup 추가:

```tsx
      {/* 모드 전환 */}
      <div className="space-y-2">
        <Label>리포트 구성 방식</Label>
        <RadioGroup
          value={watch('goalMode') ?? 'manual'}
          onValueChange={(v) => setValue('goalMode', v as 'manual' | 'goal')}
          className="flex gap-4"
        >
          <div className="flex items-center gap-2">
            <RadioGroupItem value="manual" id="mode-manual" />
            <Label htmlFor="mode-manual" className="font-normal">직접 설정</Label>
          </div>
          <div className="flex items-center gap-2">
            <RadioGroupItem value="goal" id="mode-goal" />
            <Label htmlFor="mode-goal" className="font-normal">목표 기반</Label>
          </div>
        </RadioGroup>
      </div>
```

- [ ] **Step 4: 목표 기반 모드 UI 추가**

모드 전환 RadioGroup 바로 아래에 목표 기반 전용 UI 블록 추가. `goalMode === 'goal'`일 때만 렌더링:

```tsx
      {watch('goalMode') === 'goal' && (
        <GoalBasedSection form={form} />
      )}
```

같은 파일에 `GoalBasedSection` 컴포넌트를 추가:

```tsx
function GoalBasedSection({ form }: { form: UseFormReturn<ProactiveJobFormValues> }) {
  const { watch, setValue } = form;
  const [isGenerating, setIsGenerating] = useState(false);
  const [generatedSections, setGeneratedSections] = useState<TemplateSection[] | null>(null);
  const [generateError, setGenerateError] = useState<string | null>(null);

  const businessQuestion = watch('businessQuestion') ?? '';

  const handleGenerate = async () => {
    if (!businessQuestion.trim()) return;
    setIsGenerating(true);
    setGenerateError(null);
    try {
      const { data } = await proactiveApi.generateTemplateFromQuestion(businessQuestion);
      const sections = (data.templateStructure as { sections?: TemplateSection[] })?.sections ?? [];
      setGeneratedSections(sections);
      setValue('prompt', data.prompt);
      setValue('generatedTemplateStructure', data.templateStructure);
    } catch {
      setGenerateError('템플릿 생성에 실패했습니다. 다시 시도해주세요.');
    } finally {
      setIsGenerating(false);
    }
  };

  return (
    <div className="space-y-4 rounded-lg border p-4 bg-muted/20">
      <div className="space-y-2">
        <Label htmlFor="business-question">비즈니스 질문</Label>
        <Textarea
          id="business-question"
          rows={3}
          placeholder="예: 주간 매출 추이와 이상 원인을 분석해줘"
          value={businessQuestion}
          onChange={(e) => setValue('businessQuestion', e.target.value)}
        />
      </div>
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={handleGenerate}
        disabled={isGenerating || !businessQuestion.trim()}
        className="gap-1.5"
      >
        <Sparkles className="h-4 w-4" />
        {isGenerating ? '생성 중...' : '템플릿 자동 생성'}
      </Button>

      {isGenerating && (
        <div className="space-y-2">
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-3/4" />
          <Skeleton className="h-8 w-5/6" />
        </div>
      )}

      {generateError && (
        <p className="text-sm text-destructive">{generateError}</p>
      )}

      {generatedSections && !isGenerating && (
        <Collapsible defaultOpen>
          <CollapsibleTrigger className="text-sm font-medium hover:underline">
            생성된 템플릿 구조 ({generatedSections.length}개 섹션)
          </CollapsibleTrigger>
          <CollapsibleContent className="mt-2">
            <SectionPreview sections={generatedSections} />
          </CollapsibleContent>
        </Collapsible>
      )}
    </div>
  );
}
```

- [ ] **Step 5: 목표 기반 모드에서 기존 필드 조건부 렌더링**

기존 "템플릿" Select과 "프롬프트" Textarea를 `watch('goalMode') !== 'goal'` 조건으로 감싸서, 목표 기반 모드에서는 숨김:

```tsx
      {watch('goalMode') !== 'goal' && (
        <>
          {/* 템플릿 */}
          <div className="space-y-2">
            ...기존 템플릿 Select 코드...
          </div>

          {/* 프롬프트 */}
          <div className="space-y-2">
            ...기존 프롬프트 Textarea 코드...
          </div>
        </>
      )}
```

- [ ] **Step 6: useSearchParams로 AI 챗 위젯에서 전달된 파라미터 수신**

`JobOverviewTab` 컴포넌트가 `ProactiveJobDetailPage`에서 URL 쿼리 파라미터를 받을 수 있도록, `ProactiveJobDetailPage.tsx`에서 `searchParams`를 props로 전달하는 구조를 활용. `JobOverviewTab`의 `useEffect`에서 `mode=goal` 쿼리 파라미터가 있으면 자동으로 목표 기반 모드를 설정:

`JobOverviewTab`의 props interface에 `searchParams` 추가:

```typescript
interface JobOverviewTabProps {
  job: ProactiveJob | undefined;
  isNew: boolean;
  isEditing: boolean;
  form: UseFormReturn<ProactiveJobFormValues>;
  templates: ReportTemplate[];
  searchParams?: URLSearchParams;
}
```

컴포넌트 본문에 useEffect 추가:

```typescript
import { useEffect } from 'react';

  useEffect(() => {
    if (!searchParams || !isNew) return;
    const mode = searchParams.get('mode');
    if (mode === 'goal') {
      setValue('goalMode', 'goal');
      const question = searchParams.get('question');
      if (question) setValue('businessQuestion', question);
      const prompt = searchParams.get('prompt');
      if (prompt) setValue('prompt', prompt);
      const structureStr = searchParams.get('templateStructure');
      if (structureStr) {
        try {
          const structure = JSON.parse(structureStr);
          setValue('generatedTemplateStructure', structure);
        } catch {
          // ignore parse error
        }
      }
    }
  }, [searchParams, isNew, setValue]);
```

`ProactiveJobDetailPage.tsx`에서 `JobOverviewTab`에 `searchParams` prop 전달:

```tsx
<JobOverviewTab
  job={job}
  isNew={isNew}
  isEditing={isEditing}
  form={form}
  templates={templates}
  searchParams={searchParams}
/>
```

- [ ] **Step 7: 프론트엔드 타입체크 실행**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx \
  apps/firehub-web/src/pages/ai-insights/ProactiveJobDetailPage.tsx \
  apps/firehub-web/src/api/proactive.ts \
  apps/firehub-web/src/lib/validations/proactive-job.ts
git commit -m "feat(proactive): 스마트 작업 목표 기반 모드 (RadioGroup + AI 템플릿 자동 생성)"
```

---

### Task 9: 통합 검증

**Files:** (검증만, 신규 코드 없음)

- [ ] **Step 1: AI Agent 전체 테스트**

Run: `cd apps/firehub-ai-agent && pnpm test`
Expected: ALL PASS

- [ ] **Step 2: AI Agent 타입체크**

Run: `cd apps/firehub-ai-agent && pnpm typecheck`
Expected: PASS

- [ ] **Step 3: 프론트엔드 빌드**

Run: `cd apps/firehub-web && pnpm build`
Expected: PASS

- [ ] **Step 4: 검증 기준 확인**

설계 문서 섹션 6.4 기준:

- [ ] AI 챗에서 비즈니스 질문 → 리포트 생성 동작
  - `generate_report` 도구가 정상 등록되고 invokeTool로 호출 가능
  - `show_report_builder` 위젯이 WidgetRegistry에 등록됨
  - 위젯이 SectionPreview + 편집하기/스마트 작업 저장 버튼 포함

- [ ] 챗에서 생성한 리포트를 스마트 작업으로 저장 가능
  - `save_as_smart_job` 도구가 `createSmartJobWithTemplate` API 호출
  - 템플릿 + 스마트 작업이 한 번에 생성됨

- [ ] 스마트 작업 "목표 기반" 모드에서 AI 템플릿 자동 생성 동작
  - RadioGroup으로 "직접 설정" / "목표 기반" 전환
  - 비즈니스 질문 입력 → "템플릿 자동 생성" 버튼 → SectionPreview로 결과 표시

- [ ] 자동 생성된 템플릿을 빌더에서 편집 가능
  - ReportBuilderWidget의 "편집하기" 버튼이 빌더 페이지로 딥링크 (prefill 쿼리 파라미터)

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat(proactive): 목표 기반 리포트 생성 Phase 7-6 완료"
```
