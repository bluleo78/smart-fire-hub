# Phase 7-0c: 리포트 템플릿 UX 개선 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 리포트 템플릿의 상세 페이지 도입, CodeMirror JSON 에디터, 9가지 섹션 타입 가이드, 빌트인 복제 기능을 구현한다.

**Architecture:** 기존 `ReportTemplatesTab`의 Dialog 기반 CRUD를 제거하고, `ProactiveJobDetailPage` 패턴을 따르는 전용 상세 페이지(`ReportTemplateDetailPage`)를 도입한다. CodeMirror 6 JSON 에디터를 재사용 가능 컴포넌트로 분리하고, 섹션 타입 정의를 별도 모듈로 관리한다.

**Tech Stack:** React 19, TypeScript, CodeMirror 6 (`@codemirror/lang-json`), TanStack Query, React Hook Form + Zod, shadcn/ui

**Spec:** `docs/superpowers/specs/2026-04-02-phase-7-0c-report-template-ux-design.md`

---

## File Structure

### 신규 생성

| 파일 | 역할 |
|------|------|
| `src/lib/template-section-types.ts` | 9가지 섹션 타입 정의 (타입, 아이콘, 색상, JSON 스니펫, 설명) |
| `src/pages/ai-insights/ReportTemplateDetailPage.tsx` | 템플릿 상세/편집 페이지 (읽기/편집 모드 전환) |
| `src/pages/ai-insights/components/TemplateJsonEditor.tsx` | CodeMirror JSON 에디터 + 삽입 툴바 |
| `src/pages/ai-insights/components/TemplateSidePanel.tsx` | 가이드/미리보기 탭 사이드패널 |
| `src/pages/ai-insights/components/SectionPreview.tsx` | 섹션 구조 미리보기 카드 렌더링 |
| `src/lib/validations/report-template.ts` | Zod 스키마 (이름/설명 검증) |

### 수정

| 파일 | 변경 내용 |
|------|-----------|
| `src/api/proactive.ts` | `TemplateSection`, `SectionType` 타입 추가 |
| `src/hooks/queries/useProactiveMessages.ts` | `useProactiveTemplate(id)` 단건 조회 훅 추가 |
| `src/pages/admin/ReportTemplatesTab.tsx` | 카드 클릭 → navigate, Dialog 제거 |
| `src/App.tsx` | 라우트 2개 추가 (`/ai-insights/templates/:id`, `/ai-insights/templates/new`) |

---

### Task 1: 섹션 타입 정의 모듈

**Files:**
- Create: `apps/firehub-web/src/lib/template-section-types.ts`
- Modify: `apps/firehub-web/src/api/proactive.ts`

- [ ] **Step 1: proactive.ts에 타입 추가**

`apps/firehub-web/src/api/proactive.ts` 파일 상단, `ReportTemplate` 인터페이스 아래에 추가:

```typescript
export type SectionType =
  | 'text'
  | 'cards'
  | 'list'
  | 'table'
  | 'comparison'
  | 'alert'
  | 'timeline'
  | 'chart'
  | 'recommendation';

export interface TemplateSection {
  key: string;
  type: SectionType;
  label: string;
  description?: string;
  required?: boolean;
}
```

- [ ] **Step 2: 섹션 타입 정의 파일 생성**

`apps/firehub-web/src/lib/template-section-types.ts` 생성:

```typescript
import type { SectionType } from '../api/proactive';

export interface SectionTypeDefinition {
  type: SectionType;
  icon: string;
  label: string;
  description: string;
  color: string; // Tailwind border color class
  snippet: {
    key: string;
    type: SectionType;
    label: string;
    description: string;
  };
}

export const SECTION_TYPES: SectionTypeDefinition[] = [
  {
    type: 'text',
    icon: '📝',
    label: 'Text',
    description: '마크다운 서술형 텍스트. 요약, 분석, 인사이트 설명.',
    color: 'border-l-blue-500',
    snippet: { key: 'new_text', type: 'text', label: '새 텍스트 섹션', description: '이 섹션에 대한 설명을 입력하세요' },
  },
  {
    type: 'cards',
    icon: '📊',
    label: 'Cards',
    description: '핵심 수치 카드. KPI, 통계 요약, 전주/전월 비교.',
    color: 'border-l-amber-500',
    snippet: { key: 'new_cards', type: 'cards', label: '핵심 지표', description: '주요 KPI 수치를 카드로 표시' },
  },
  {
    type: 'list',
    icon: '📋',
    label: 'List',
    description: '항목 나열. 주요 이슈, 변경사항, 권고사항.',
    color: 'border-l-slate-500',
    snippet: { key: 'new_list', type: 'list', label: '주요 항목', description: '나열할 항목 목록' },
  },
  {
    type: 'table',
    icon: '📑',
    label: 'Table',
    description: '행/열 구조 데이터. 순위표, 비교표, 상세 통계.',
    color: 'border-l-indigo-500',
    snippet: { key: 'new_table', type: 'table', label: '데이터 테이블', description: '표 형식 데이터' },
  },
  {
    type: 'comparison',
    icon: '🔄',
    label: 'Comparison',
    description: '기간 비교. 전주/전월/전년 대비 변화율 표시.',
    color: 'border-l-purple-500',
    snippet: { key: 'new_comparison', type: 'comparison', label: '기간 비교', description: '이전 기간 대비 변화를 비교' },
  },
  {
    type: 'alert',
    icon: '⚠️',
    label: 'Alert',
    description: '경고/알림. 이상치, 임계값 초과, 긴급 권고.',
    color: 'border-l-red-500',
    snippet: { key: 'new_alert', type: 'alert', label: '주요 경고', description: '주의가 필요한 항목' },
  },
  {
    type: 'timeline',
    icon: '🕐',
    label: 'Timeline',
    description: '시간순 이벤트 나열. 사건 경과, 작업 히스토리.',
    color: 'border-l-cyan-500',
    snippet: { key: 'new_timeline', type: 'timeline', label: '타임라인', description: '시간순 이벤트 나열' },
  },
  {
    type: 'chart',
    icon: '📈',
    label: 'Chart',
    description: '차트/그래프 설명. 데이터 시각화 결과를 서술적으로 설명.',
    color: 'border-l-green-500',
    snippet: { key: 'new_chart', type: 'chart', label: '차트 분석', description: '데이터 시각화 결과 설명' },
  },
  {
    type: 'recommendation',
    icon: '💡',
    label: 'Recommendation',
    description: 'AI 권고사항. 분석 결과를 바탕으로 제안하는 조치/개선 사항.',
    color: 'border-l-emerald-500',
    snippet: { key: 'new_recommendation', type: 'recommendation', label: '권고사항', description: 'AI가 제안하는 조치 사항' },
  },
];

export function getSectionTypeDef(type: string): SectionTypeDefinition | undefined {
  return SECTION_TYPES.find((s) => s.type === type);
}
```

- [ ] **Step 3: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS (타입만 추가, 기존 코드 영향 없음)

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/lib/template-section-types.ts apps/firehub-web/src/api/proactive.ts
git commit -m "feat(template): 9가지 섹션 타입 정의 모듈 추가"
```

---

### Task 2: Zod 스키마 + TanStack Query 훅 추가

**Files:**
- Create: `apps/firehub-web/src/lib/validations/report-template.ts`
- Modify: `apps/firehub-web/src/hooks/queries/useProactiveMessages.ts`

- [ ] **Step 1: Zod 스키마 생성**

`apps/firehub-web/src/lib/validations/report-template.ts` 생성:

```typescript
import { z } from 'zod';

export const reportTemplateSchema = z.object({
  name: z.string().min(1, '이름을 입력해주세요.').max(100, '100자 이내로 입력해주세요.'),
  description: z.string().max(500, '500자 이내로 입력해주세요.').optional().or(z.literal('')),
});

export type ReportTemplateFormValues = z.infer<typeof reportTemplateSchema>;
```

- [ ] **Step 2: useProactiveTemplate 단건 조회 훅 추가**

`apps/firehub-web/src/hooks/queries/useProactiveMessages.ts`의 Templates 섹션에 추가:

```typescript
export function useProactiveTemplate(id: number) {
  return useQuery({
    queryKey: KEYS.template(id),
    queryFn: () => proactiveApi.getTemplate(id).then((r) => r.data),
    enabled: !!id,
  });
}
```

- [ ] **Step 3: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/lib/validations/report-template.ts apps/firehub-web/src/hooks/queries/useProactiveMessages.ts
git commit -m "feat(template): Zod 스키마 + 단건 조회 훅 추가"
```

---

### Task 3: CodeMirror JSON 에디터 컴포넌트

**Files:**
- Create: `apps/firehub-web/src/pages/ai-insights/components/TemplateJsonEditor.tsx`

**참조:** `src/pages/data/components/CodeMirrorEditor.tsx` — 동일한 CodeMirror 6 패턴 (useRef + useEffect mount/sync)

- [ ] **Step 1: @codemirror/lang-json 설치**

Run: `cd apps/firehub-web && pnpm add @codemirror/lang-json`

- [ ] **Step 2: TemplateJsonEditor 컴포넌트 생성**

`apps/firehub-web/src/pages/ai-insights/components/TemplateJsonEditor.tsx` 생성:

```typescript
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { json } from '@codemirror/lang-json';
import { linter, type Diagnostic } from '@codemirror/lint';
import { searchKeymap } from '@codemirror/search';
import { EditorState } from '@codemirror/state';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorView, keymap, lineNumbers } from '@codemirror/view';
import { useEffect, useRef } from 'react';

import { Button } from '@/components/ui/button';
import { SECTION_TYPES } from '@/lib/template-section-types';
import type { TemplateSection } from '@/api/proactive';

interface TemplateJsonEditorProps {
  value: string;
  onChange: (value: string) => void;
  readonly?: boolean;
}

function jsonLinter() {
  return linter((view) => {
    const diagnostics: Diagnostic[] = [];
    const doc = view.state.doc.toString();
    if (!doc.trim()) return diagnostics;
    try {
      JSON.parse(doc);
    } catch (e) {
      const message = e instanceof SyntaxError ? e.message : 'Invalid JSON';
      // Try to extract position from error message
      const posMatch = message.match(/position (\d+)/);
      const pos = posMatch ? Number(posMatch[1]) : 0;
      diagnostics.push({
        from: Math.min(pos, doc.length),
        to: Math.min(pos + 1, doc.length),
        severity: 'error',
        message,
      });
    }
    return diagnostics;
  });
}

export function TemplateJsonEditor({ value, onChange, readonly = false }: TemplateJsonEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    if (!containerRef.current) return;

    const extensions = [
      json(),
      oneDark,
      lineNumbers(),
      history(),
      keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap]),
      EditorView.theme({
        '&': {
          fontSize: '13px',
          border: '1px solid hsl(var(--border))',
          borderRadius: '6px',
        },
        '.cm-scroller': {
          overflow: 'auto',
          minHeight: '200px',
          maxHeight: '500px',
        },
      }),
    ];

    if (readonly) {
      extensions.push(EditorView.editable.of(false), EditorState.readOnly.of(true));
    } else {
      extensions.push(
        jsonLinter(),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            onChangeRef.current(update.state.doc.toString());
          }
        }),
      );
    }

    const state = EditorState.create({ doc: value, extensions });
    const view = new EditorView({ state, parent: containerRef.current });
    viewRef.current = view;

    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readonly]);

  // Sync external value changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const currentDoc = view.state.doc.toString();
    if (currentDoc !== value) {
      view.dispatch({
        changes: { from: 0, to: currentDoc.length, insert: value },
      });
    }
  }, [value]);

  const handleInsertSection = (snippet: TemplateSection) => {
    const view = viewRef.current;
    if (!view || readonly) return;

    const doc = view.state.doc.toString();
    try {
      const parsed = JSON.parse(doc);
      const sections = Array.isArray(parsed.sections) ? parsed.sections : [];

      // Generate unique key if snippet key already exists
      let key = snippet.key;
      let counter = 1;
      while (sections.some((s: TemplateSection) => s.key === key)) {
        key = `${snippet.key}_${counter++}`;
      }

      sections.push({ ...snippet, key });
      const newDoc = JSON.stringify({ ...parsed, sections }, null, 2);
      onChange(newDoc);
    } catch {
      // If JSON is invalid, can't insert — user should fix JSON first
    }
  };

  return (
    <div className="flex flex-col">
      {/* Insert toolbar — only in edit mode */}
      {!readonly && (
        <div className="flex items-center gap-1.5 p-2 bg-muted/50 border border-b-0 border-border rounded-t-md flex-wrap">
          <span className="text-xs text-muted-foreground mr-1">섹션 추가:</span>
          {SECTION_TYPES.map((st) => (
            <Button
              key={st.type}
              variant="outline"
              size="sm"
              className="h-6 px-2 text-xs gap-1"
              onClick={() => handleInsertSection(st.snippet as TemplateSection)}
            >
              <span>{st.icon}</span>
              <span>{st.label}</span>
            </Button>
          ))}
        </div>
      )}
      <div ref={containerRef} className={!readonly ? '[&_.cm-editor]:rounded-t-none' : ''} />
    </div>
  );
}
```

- [ ] **Step 3: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/components/TemplateJsonEditor.tsx apps/firehub-web/package.json apps/firehub-web/pnpm-lock.yaml
git commit -m "feat(template): CodeMirror JSON 에디터 + 삽입 툴바 컴포넌트"
```

---

### Task 4: 사이드패널 (가이드/미리보기) 컴포넌트

**Files:**
- Create: `apps/firehub-web/src/pages/ai-insights/components/SectionPreview.tsx`
- Create: `apps/firehub-web/src/pages/ai-insights/components/TemplateSidePanel.tsx`

- [ ] **Step 1: SectionPreview 컴포넌트 생성**

`apps/firehub-web/src/pages/ai-insights/components/SectionPreview.tsx` 생성:

```typescript
import { Badge } from '@/components/ui/badge';
import type { TemplateSection } from '@/api/proactive';
import { getSectionTypeDef } from '@/lib/template-section-types';

interface SectionPreviewProps {
  sections: TemplateSection[];
}

export function SectionPreview({ sections }: SectionPreviewProps) {
  if (sections.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-muted-foreground text-sm">
        <p>섹션이 없습니다.</p>
        <p className="text-xs mt-1">왼쪽 에디터에서 섹션을 추가해보세요.</p>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {sections.map((section, index) => {
        const def = getSectionTypeDef(section.type);
        return (
          <div
            key={section.key || index}
            className={`p-3 bg-muted/30 rounded-md border-l-3 ${def?.color ?? 'border-l-muted-foreground'}`}
          >
            <div className="flex items-center justify-between">
              <div>
                <div className="text-sm font-medium">{section.label}</div>
                <div className="text-xs text-muted-foreground mt-0.5">
                  {def ? `${def.icon} ${def.type}` : section.type ?? 'unknown'}
                </div>
              </div>
              {section.required && (
                <Badge variant="secondary" className="text-[10px] h-5">필수</Badge>
              )}
            </div>
            {section.description && (
              <p className="text-xs text-muted-foreground mt-1">{section.description}</p>
            )}
          </div>
        );
      })}
      <div className="text-center text-xs text-muted-foreground pt-2">
        {sections.length}개 섹션
      </div>
    </div>
  );
}
```

- [ ] **Step 2: TemplateSidePanel 컴포넌트 생성**

`apps/firehub-web/src/pages/ai-insights/components/TemplateSidePanel.tsx` 생성:

```typescript
import { useState } from 'react';

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import type { TemplateSection } from '@/api/proactive';
import { SECTION_TYPES } from '@/lib/template-section-types';

import { SectionPreview } from './SectionPreview';

interface TemplateSidePanelProps {
  jsonValue: string;
}

function GuideTab() {
  const [expandedType, setExpandedType] = useState<string | null>(null);

  return (
    <div className="space-y-2">
      {SECTION_TYPES.map((st) => (
        <div
          key={st.type}
          className="p-3 bg-muted/30 rounded-md border border-border cursor-pointer hover:bg-muted/50 transition-colors"
          onClick={() => setExpandedType(expandedType === st.type ? null : st.type)}
        >
          <div className="flex items-center justify-between">
            <div className="font-medium text-sm">
              {st.icon} {st.label}
            </div>
          </div>
          <p className="text-xs text-muted-foreground mt-1">{st.description}</p>
          {expandedType === st.type && (
            <pre className="mt-2 p-2 bg-muted rounded text-xs font-mono overflow-x-auto">
              {JSON.stringify(st.snippet, null, 2)}
            </pre>
          )}
        </div>
      ))}
    </div>
  );
}

function parseSections(jsonValue: string): TemplateSection[] | null {
  try {
    const parsed = JSON.parse(jsonValue);
    if (Array.isArray(parsed?.sections)) {
      return parsed.sections as TemplateSection[];
    }
    return [];
  } catch {
    return null;
  }
}

export function TemplateSidePanel({ jsonValue }: TemplateSidePanelProps) {
  const sections = parseSections(jsonValue);

  return (
    <Tabs defaultValue="guide" className="h-full flex flex-col">
      <TabsList className="w-full grid grid-cols-2 shrink-0">
        <TabsTrigger value="guide">가이드</TabsTrigger>
        <TabsTrigger value="preview">미리보기</TabsTrigger>
      </TabsList>
      <TabsContent value="guide" className="flex-1 overflow-auto mt-4">
        <GuideTab />
      </TabsContent>
      <TabsContent value="preview" className="flex-1 overflow-auto mt-4">
        {sections === null ? (
          <div className="flex flex-col items-center justify-center py-12 text-muted-foreground text-sm">
            <p>JSON 형식이 올바르지 않습니다.</p>
            <p className="text-xs mt-1">에디터에서 수정해주세요.</p>
          </div>
        ) : (
          <SectionPreview sections={sections} />
        )}
      </TabsContent>
    </Tabs>
  );
}
```

- [ ] **Step 3: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/components/SectionPreview.tsx apps/firehub-web/src/pages/ai-insights/components/TemplateSidePanel.tsx
git commit -m "feat(template): 사이드패널 (가이드/미리보기) + 섹션 미리보기 컴포넌트"
```

---

### Task 5: 템플릿 상세/편집 페이지

**Files:**
- Create: `apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx`

**참조 패턴:** `ProactiveJobDetailPage.tsx` — 헤더(뒤로가기 + 제목 + 배지 + 액션) + isEditing 상태 + 읽기/편집 모드 전환

- [ ] **Step 1: ReportTemplateDetailPage 생성**

`apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx` 생성:

```typescript
import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft, Copy, Pencil, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import type { TemplateSection } from '@/api/proactive';
import {
  useCreateProactiveTemplate,
  useDeleteProactiveTemplate,
  useProactiveTemplate,
  useUpdateProactiveTemplate,
} from '@/hooks/queries/useProactiveMessages';
import { handleApiError } from '@/lib/api-error';
import { type ReportTemplateFormValues, reportTemplateSchema } from '@/lib/validations/report-template';

import { SectionPreview } from './components/SectionPreview';
import { TemplateJsonEditor } from './components/TemplateJsonEditor';
import { TemplateSidePanel } from './components/TemplateSidePanel';

const DEFAULT_STRUCTURE = JSON.stringify({ sections: [] }, null, 2);

function parseSections(json: string): TemplateSection[] {
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed?.sections) ? parsed.sections : [];
  } catch {
    return [];
  }
}

export default function ReportTemplateDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isNew = !id || id === 'new';
  const templateId = isNew ? 0 : Number(id);

  const { data: template, isLoading } = useProactiveTemplate(templateId);
  const createMutation = useCreateProactiveTemplate();
  const updateMutation = useUpdateProactiveTemplate();
  const deleteMutation = useDeleteProactiveTemplate();

  const [isEditing, setIsEditing] = useState(isNew);
  const [structureJson, setStructureJson] = useState(DEFAULT_STRUCTURE);
  const [jsonInitialized, setJsonInitialized] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);

  const form = useForm<ReportTemplateFormValues>({
    resolver: zodResolver(reportTemplateSchema),
    values: template ? { name: template.name, description: template.description ?? '' } : { name: '', description: '' },
  });

  // Sync template structure to JSON editor when loaded
  if (template && !jsonInitialized) {
    setStructureJson(JSON.stringify(template.structure, null, 2));
    setJsonInitialized(true);
  }

  const handleSave = form.handleSubmit((values) => {
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(structureJson);
    } catch {
      toast.error('JSON 형식이 올바르지 않습니다.');
      return;
    }

    const payload = {
      name: values.name,
      description: values.description || undefined,
      structure: parsed,
    };

    if (isNew) {
      createMutation.mutate(payload, {
        onSuccess: (created) => {
          toast.success('템플릿이 생성되었습니다.');
          navigate(`/ai-insights/templates/${created.id}`);
        },
        onError: (err) => handleApiError(err, '템플릿 생성에 실패했습니다.'),
      });
    } else {
      updateMutation.mutate(
        { id: templateId, data: payload },
        {
          onSuccess: () => {
            toast.success('템플릿이 수정되었습니다.');
            setIsEditing(false);
          },
          onError: (err) => handleApiError(err, '템플릿 수정에 실패했습니다.'),
        },
      );
    }
  });

  const handleDelete = () => {
    deleteMutation.mutate(templateId, {
      onSuccess: () => {
        toast.success('템플릿이 삭제되었습니다.');
        navigate('/ai-insights/templates');
      },
      onError: (err) => handleApiError(err, '템플릿 삭제에 실패했습니다.'),
    });
    setDeleteDialogOpen(false);
  };

  const handleClone = () => {
    if (!template) return;
    createMutation.mutate(
      {
        name: `${template.name} (사본)`,
        description: template.description ?? undefined,
        structure: template.structure,
      },
      {
        onSuccess: (created) => {
          toast.success(`"${created.name}" 템플릿이 복제되었습니다.`);
          navigate(`/ai-insights/templates/${created.id}`);
        },
        onError: (err) => handleApiError(err, '템플릿 복제에 실패했습니다.'),
      },
    );
  };

  const handleCancelEdit = () => {
    if (template) {
      form.reset({ name: template.name, description: template.description ?? '' });
      setStructureJson(JSON.stringify(template.structure, null, 2));
    }
    setIsEditing(false);
  };

  const isSaving = createMutation.isPending || updateMutation.isPending;

  if (!isNew && isLoading) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-64 bg-muted animate-pulse rounded" />
        <div className="h-96 bg-muted animate-pulse rounded" />
      </div>
    );
  }

  const isBuiltin = template?.builtin ?? false;
  const sections = parseSections(structureJson);

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <header className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => navigate('/ai-insights/templates')}
            aria-label="목록으로"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">
              {isNew ? '새 템플릿' : (template?.name ?? '-')}
            </h1>
            {!isNew && template && (
              <Badge variant={isBuiltin ? 'secondary' : 'default'} className="mt-1">
                {isBuiltin ? '기본' : '커스텀'}
              </Badge>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2">
          {!isNew && !isEditing && (
            <>
              <Button variant="outline" size="sm" onClick={handleClone} disabled={createMutation.isPending}>
                <Copy className="h-3.5 w-3.5 mr-1" />
                복제
              </Button>
              {!isBuiltin && (
                <>
                  <Button variant="outline" size="sm" onClick={() => setIsEditing(true)}>
                    <Pencil className="h-3.5 w-3.5 mr-1" />
                    편집
                  </Button>
                  <Button
                    variant="destructive"
                    size="sm"
                    onClick={() => setDeleteDialogOpen(true)}
                    disabled={deleteMutation.isPending}
                  >
                    <Trash2 className="h-3.5 w-3.5 mr-1" />
                    삭제
                  </Button>
                </>
              )}
            </>
          )}
          {isEditing && (
            <>
              {!isNew && (
                <Button variant="outline" size="sm" onClick={handleCancelEdit}>
                  취소
                </Button>
              )}
              <Button size="sm" onClick={handleSave} disabled={isSaving}>
                {isSaving ? '저장 중...' : isNew ? '생성' : '저장'}
              </Button>
            </>
          )}
        </div>
      </header>

      {/* 메타 정보 (읽기 모드) */}
      {!isNew && !isEditing && template && (
        <div className="text-sm text-muted-foreground flex gap-4">
          {template.description && <span>{template.description}</span>}
          <span>생성: {new Date(template.createdAt).toLocaleDateString('ko-KR')}</span>
          <span>수정: {new Date(template.updatedAt).toLocaleDateString('ko-KR')}</span>
        </div>
      )}

      {/* 이름/설명 편집 (편집 모드) */}
      {isEditing && (
        <Card>
          <CardContent className="pt-6">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="tpl-name">이름</Label>
                <Input
                  id="tpl-name"
                  {...form.register('name')}
                  placeholder="리포트 템플릿 이름"
                />
                {form.formState.errors.name && (
                  <p className="text-sm text-destructive">{form.formState.errors.name.message}</p>
                )}
              </div>
              <div className="space-y-2">
                <Label htmlFor="tpl-desc">설명 (선택)</Label>
                <Input
                  id="tpl-desc"
                  {...form.register('description')}
                  placeholder="템플릿 설명을 입력하세요"
                />
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* 본문: 에디터 + 사이드패널 */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
        {/* JSON 에디터 (좌측) */}
        <div className="lg:col-span-3">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">섹션 구조</CardTitle>
            </CardHeader>
            <CardContent>
              <TemplateJsonEditor
                value={structureJson}
                onChange={setStructureJson}
                readonly={!isEditing}
              />
            </CardContent>
          </Card>
        </div>

        {/* 사이드패널 (우측) */}
        <div className="lg:col-span-2">
          <Card className="h-full">
            <CardContent className="pt-6 h-full">
              {isEditing ? (
                <TemplateSidePanel jsonValue={structureJson} />
              ) : (
                <div>
                  <h3 className="text-sm font-medium mb-4">섹션 구조 미리보기</h3>
                  <SectionPreview sections={sections} />
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      </div>

      {/* 삭제 확인 다이얼로그 */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>템플릿 삭제</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">이 템플릿을 삭제하시겠습니까? 되돌릴 수 없습니다.</p>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteDialogOpen(false)}>취소</Button>
            <Button variant="destructive" onClick={handleDelete} disabled={deleteMutation.isPending}>
              삭제
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
```

- [ ] **Step 2: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx
git commit -m "feat(template): 템플릿 상세/편집 페이지 (읽기/편집 모드 전환)"
```

---

### Task 6: 라우트 등록 + 목록 페이지 리팩터링

**Files:**
- Modify: `apps/firehub-web/src/App.tsx`
- Modify: `apps/firehub-web/src/pages/admin/ReportTemplatesTab.tsx`

- [ ] **Step 1: App.tsx에 라우트 추가**

`apps/firehub-web/src/App.tsx`에서:

1. lazy import 추가 (ProactiveJobDetailPage 선언 아래):

```typescript
const ReportTemplateDetailPage = lazy(() => import('./pages/ai-insights/ReportTemplateDetailPage'));
```

2. `/ai-insights/templates` 라우트 아래에 2개 추가:

```typescript
<Route path="/ai-insights/templates/new" element={<ReportTemplateDetailPage />} />
<Route path="/ai-insights/templates/:id" element={<ReportTemplateDetailPage />} />
```

주의: `/ai-insights/templates/new`는 `/ai-insights/templates/:id`보다 위에 위치해야 한다.

- [ ] **Step 2: ReportTemplatesTab.tsx 리팩터링**

기존 Dialog 기반 CRUD를 제거하고, 카드 클릭 시 상세 페이지로 이동하도록 변경.

`apps/firehub-web/src/pages/admin/ReportTemplatesTab.tsx` 전체를 다음으로 교체:

```typescript
import { FileText, Plus } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card';
import { useProactiveTemplates } from '../../hooks/queries/useProactiveMessages';

export default function ReportTemplatesTab() {
  const { data: templates = [], isLoading } = useProactiveTemplates();
  const navigate = useNavigate();

  const builtins = templates.filter((t) => t.builtin);
  const customs = templates.filter((t) => !t.builtin);

  const sectionCount = (structure: Record<string, unknown>) =>
    Array.isArray((structure as { sections?: unknown[] })?.sections)
      ? (structure as { sections: unknown[] }).sections.length
      : 0;

  if (isLoading) {
    return <div className="py-8 text-center text-muted-foreground text-sm">불러오는 중...</div>;
  }

  return (
    <div className="space-y-8">
      {/* Built-in templates */}
      <div className="space-y-4">
        <div>
          <h3 className="text-base font-semibold">기본 템플릿</h3>
          <p className="text-sm text-muted-foreground mt-1">시스템에서 제공하는 기본 리포트 템플릿입니다.</p>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {builtins.map((t) => (
            <Card
              key={t.id}
              className="bg-muted/20 border-dashed cursor-pointer hover:bg-muted/30 transition-colors"
              onClick={() => navigate(`/ai-insights/templates/${t.id}`)}
            >
              <CardHeader className="pb-2">
                <div className="flex items-start justify-between gap-2">
                  <CardTitle className="text-sm font-medium">{t.name}</CardTitle>
                  <Badge variant="secondary" className="shrink-0 text-xs">기본</Badge>
                </div>
                {t.description && (
                  <CardDescription className="text-xs">{t.description}</CardDescription>
                )}
              </CardHeader>
              <CardContent>
                <p className="text-xs text-muted-foreground">섹션 {sectionCount(t.structure)}개</p>
              </CardContent>
            </Card>
          ))}
        </div>
      </div>

      {/* Custom templates */}
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-base font-semibold">커스텀 템플릿</h3>
            <p className="text-sm text-muted-foreground mt-1">직접 만든 리포트 템플릿입니다.</p>
          </div>
          <Button size="sm" onClick={() => navigate('/ai-insights/templates/new')}>
            <Plus className="h-4 w-4" />
            템플릿 추가
          </Button>
        </div>

        {customs.length === 0 ? (
          <div className="rounded-lg border border-dashed flex flex-col items-center justify-center py-12 gap-3 text-center">
            <FileText className="h-10 w-10 text-muted-foreground" />
            <div>
              <p className="text-sm font-medium">커스텀 템플릿 없음</p>
              <p className="text-xs text-muted-foreground mt-1">
                나만의 리포트 구조를 만들어 스마트 작업에 사용하세요.
              </p>
            </div>
            <Button size="sm" variant="outline" onClick={() => navigate('/ai-insights/templates/new')}>
              <Plus className="h-4 w-4" />
              첫 템플릿 만들기
            </Button>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {customs.map((t) => (
              <Card
                key={t.id}
                className="card-hover cursor-pointer"
                onClick={() => navigate(`/ai-insights/templates/${t.id}`)}
              >
                <CardHeader className="pb-2">
                  <div className="flex items-start justify-between gap-2">
                    <CardTitle className="text-sm font-medium">{t.name}</CardTitle>
                  </div>
                  {t.description && (
                    <CardDescription className="text-xs">{t.description}</CardDescription>
                  )}
                </CardHeader>
                <CardContent>
                  <p className="text-xs text-muted-foreground">섹션 {sectionCount(t.structure)}개</p>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 3: 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 4: 린트 확인**

Run: `cd apps/firehub-web && pnpm lint`
Expected: PASS (미사용 import 제거 확인)

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/src/App.tsx apps/firehub-web/src/pages/admin/ReportTemplatesTab.tsx
git commit -m "feat(template): 라우트 등록 + 목록 카드 클릭 → 상세 페이지 이동"
```

---

### Task 7: 통합 빌드 + Playwright 검증

**Files:** None (검증만)

- [ ] **Step 1: 전체 빌드**

Run: `cd apps/firehub-web && pnpm build`
Expected: PASS

- [ ] **Step 2: 전체 린트**

Run: `cd apps/firehub-web && pnpm lint`
Expected: PASS

- [ ] **Step 3: dev 서버 시작 + Playwright 스크린샷**

1. `pnpm dev` 로 dev 서버 시작
2. Playwright로 주요 화면 스크린샷:
   - 템플릿 목록 페이지 (`/ai-insights/templates`)
   - 빌트인 템플릿 상세 (읽기 모드)
   - 새 템플릿 생성 (편집 모드, `/ai-insights/templates/new`)
   - 커스텀 템플릿 편집 모드 (편집 버튼 클릭 후)
3. 스크린샷을 `snapshots/` 폴더에 저장

- [ ] **Step 4: 기능 검증 체크리스트**

다음 항목을 Playwright 또는 수동으로 확인:
- 목록에서 카드 클릭 → 상세 페이지 이동
- 읽기 모드: JSON 구문 강조 + 섹션 미리보기
- 편집 버튼 → 편집 모드, 취소 → 읽기 모드 복귀
- 편집 모드: 이름/설명 수정 + JSON 편집 + 저장
- 삽입 버튼 → 스니펫 에디터에 삽입
- 가이드 탭 + 미리보기 탭 전환
- 빌트인 템플릿 복제 → 사본 생성 + 상세 이동
- "템플릿 추가" → `/templates/new` → 편집 모드 → 저장 → 리다이렉트
- 빌트인에 편집/삭제 없음
- 다크/라이트 테마 정상

- [ ] **Step 5: 최종 커밋 (검증 통과 후)**

검증 중 발견된 수정 사항이 있다면 커밋. 없다면 스킵.
