# Phase 7-5: 리포트 양식 구조 개선 + 비주얼 빌더 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 리포트 템플릿에 계층 구조, 섹션별 AI 지시(instruction), 정적 콘텐츠를 추가하고 드래그앤드롭 비주얼 빌더 UI를 구축한다.

**Architecture:** JSONB sections 스키마를 확장하여 `children`, `instruction`, `static`, `content` 필드를 추가. AI Agent 프롬프트 빌더를 계층 구조에 맞게 개선. 프론트엔드는 @dnd-kit 기반 트리 빌더 + 속성 편집 패널로 교체.

**Tech Stack:** TypeScript (AI Agent + Frontend), Java/Spring Boot (Backend), @dnd-kit/core + @dnd-kit/sortable (DnD), CodeMirror (JSON 에디터 유지), shadcn/ui (UI 컴포넌트)

**설계 문서:** `docs/superpowers/specs/2026-04-03-phase-7-layer2-design.md` (섹션 2, 3, 8)

**실행 순서:**
```
Layer 1: Task 1~4 (타입 + AI Agent + 백엔드 렌더링) — 순차
Layer 2: Task 5~11 (프론트엔드 빌더) — Task 4 이후
  Task 5 (dnd-kit 설치) → Task 6 (useSectionTree 훅) → Task 7 (TreeItem + Indicator) → Task 8 (TreeBuilder) + Task 9 (PropertyEditor) 병렬 → Task 10 (페이지 통합) → Task 11 (SectionPreview 확장)
Layer 3: Task 12 (통합 검증)
```

---

### Task 1: 프론트엔드 타입 정의 확장

**Files:**
- Modify: `apps/firehub-web/src/api/proactive.ts`
- Modify: `apps/firehub-web/src/lib/template-section-types.ts`

- [ ] **Step 1: SectionType 유니언에 새 타입 추가**

`apps/firehub-web/src/api/proactive.ts`에서 `SectionType`에 `group`과 `divider`를 추가:

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
  | 'recommendation'
  | 'group'
  | 'divider';
```

- [ ] **Step 2: TemplateSection 인터페이스 확장**

같은 파일에서 `TemplateSection` 인터페이스에 새 필드 추가:

```typescript
export interface TemplateSection {
  key: string;
  type: SectionType;
  label: string;
  description?: string;     // UI 가이드용 (AI에 미전달)
  instruction?: string;     // AI에 전달되는 섹션별 지시
  required?: boolean;
  static?: boolean;         // true이면 AI가 채우지 않는 정적 콘텐츠
  content?: string;         // 정적 섹션의 고정 텍스트 (변수 치환 지원)
  children?: TemplateSection[];  // 하위 섹션 (group 타입만)
}
```

- [ ] **Step 3: template-section-types.ts에 group, divider 추가**

`apps/firehub-web/src/lib/template-section-types.ts`의 `SECTION_TYPES` 배열에 추가:

```typescript
  {
    type: 'group',
    icon: '📁',
    label: 'Group',
    description: '섹션 그룹/챕터. 하위 섹션을 묶는 컨테이너.',
    color: 'border-l-violet-500',
    snippet: { key: 'new_group', type: 'group', label: '새 그룹', description: '관련 섹션을 그룹으로 묶습니다' },
  },
  {
    type: 'divider',
    icon: '➖',
    label: 'Divider',
    description: '구분선. 섹션 간 시각적 구분.',
    color: 'border-l-gray-500',
    snippet: { key: 'new_divider', type: 'divider', label: '구분선', description: '' },
  },
```

- [ ] **Step 4: 깊이 검증 유틸 함수 추가**

같은 파일에 계층 깊이 검증 함수 추가:

```typescript
/** Validate that section nesting depth does not exceed maxDepth (default 3). */
export function validateSectionDepth(
  sections: TemplateSection[],
  maxDepth = 3,
  currentDepth = 1,
): boolean {
  for (const section of sections) {
    if (currentDepth > maxDepth) return false;
    if (section.children && section.children.length > 0) {
      if (section.type !== 'group') return false;
      if (!validateSectionDepth(section.children, maxDepth, currentDepth + 1)) return false;
    }
  }
  return true;
}

/** Flatten nested sections into a flat array (for counting, iterating). */
export function flattenSections(sections: TemplateSection[]): TemplateSection[] {
  const result: TemplateSection[] = [];
  for (const section of sections) {
    result.push(section);
    if (section.children) {
      result.push(...flattenSections(section.children));
    }
  }
  return result;
}
```

- [ ] **Step 5: parseTemplateSections 계층 구조 지원 확인**

기존 `parseTemplateSections()`는 `parsed.sections`를 그대로 반환하므로 `children` 중첩이 자연스럽게 지원됨. 변경 불필요 — 확인만.

- [ ] **Step 6: 프론트엔드 타입체크 실행**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS (새 필드는 모두 optional이므로 기존 코드 호환)

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-web/src/api/proactive.ts apps/firehub-web/src/lib/template-section-types.ts
git commit -m "feat(proactive): 템플릿 섹션 타입 확장 (group, divider, instruction, static, children)"
```

---

### Task 2: AI Agent 프롬프트 빌더 개선

**Files:**
- Modify: `apps/firehub-ai-agent/src/routes/proactive.ts`
- Create: `apps/firehub-ai-agent/src/routes/proactive.test.ts`

- [ ] **Step 1: TemplateSection 인터페이스 확장 (AI Agent 측)**

`apps/firehub-ai-agent/src/routes/proactive.ts`의 `TemplateSection` 인터페이스(라인 10-15)를 수정:

```typescript
interface TemplateSection {
  key: string;
  label: string;
  required?: boolean;
  type?: string;
  instruction?: string;
  static?: boolean;
  content?: string;
  children?: TemplateSection[];
}
```

- [ ] **Step 2: buildProactiveSystemPrompt 계층 구조 지원**

`buildProactiveSystemPrompt()` 함수(라인 125-164)를 교체. 섹션 순회를 재귀 함수로 변경하고 `instruction` 포함:

```typescript
function buildSectionPrompt(sections: TemplateSection[], depth = 1): string {
  let prompt = '';
  const headerPrefix = '#'.repeat(depth + 1); // ## for depth 1, ### for depth 2, #### for depth 3

  for (const section of sections) {
    if (section.static) {
      prompt += `${headerPrefix} ${section.label}\n`;
      prompt += '(정적 섹션 — 이 섹션은 생성하지 마세요. 시스템이 자동으로 채웁니다.)\n\n';
      continue;
    }

    if (section.type === 'divider') {
      continue; // divider는 프롬프트에 포함하지 않음
    }

    if (section.type === 'group') {
      prompt += `${headerPrefix} ${section.label}\n`;
      if (section.instruction) {
        prompt += `지시: ${section.instruction}\n`;
      }
      if (section.children && section.children.length > 0) {
        prompt += buildSectionPrompt(section.children, depth + 1);
      }
      prompt += '\n';
      continue;
    }

    // 일반 섹션
    prompt += `${headerPrefix} ${section.label}\n`;
    if (section.required !== false) {
      prompt += '(필수 섹션)\n';
    }
    if (section.instruction) {
      prompt += `지시: ${section.instruction}\n`;
    }
    const typeGuide = getSectionTypeGuide(section.type);
    if (typeGuide) {
      prompt += typeGuide + '\n';
    }
    prompt += '\n';
  }

  return prompt;
}

function buildProactiveSystemPrompt(template?: Template): string {
  let prompt =
    '당신은 프로액티브 AI 분석가입니다. 주어진 컨텍스트와 데이터를 분석하여 인사이트를 제공합니다.\n' +
    '응답은 반드시 한국어로 작성하세요.\n\n' +
    '필요한 데이터가 컨텍스트에 없으면 도구를 사용하여 직접 조회하세요.\n' +
    '데이터셋 데이터 조회: query_dataset_data, 데이터 스키마 조회: get_data_schema,\n' +
    '데이터셋 목록 조회: list_datasets, 데이터셋 상세 조회: get_dataset.\n\n';

  prompt +=
    '## 분석 원칙\n' +
    '- 데이터 나열이 아닌 인사이트 중심으로 서술하세요.\n' +
    '- "왜 이 수치가 변했는가"를 파악하고, 가능한 원인을 제시하세요.\n' +
    '- 컨텍스트에 previousExecutions(이전 실행 결과)가 있으면 비교하여 변화 추이를 언급하세요.\n' +
    '- 변화를 언급할 때는 절대값과 변화율(%)을 함께 제시하세요.\n' +
    '- 확신이 낮으면 "~로 보입니다", "확인이 필요합니다" 등으로 표현하세요.\n' +
    '- 권고사항은 "무엇을 해야 하는가"를 구체적으로 제시하세요.\n\n';

  if (template) {
    if (template.style) {
      prompt += `## 작성 스타일\n${template.style}\n\n`;
    }

    prompt += `출력 형식: ${template.output_format}\n\n`;
    prompt += '다음 섹션 구조에 따라 응답을 작성하세요. 각 섹션은 헤더(##, ###, ####)로 구분합니다:\n\n';
    prompt += buildSectionPrompt(template.sections);
  }

  return prompt;
}
```

- [ ] **Step 3: getSectionTypeGuide에 group, divider 처리 추가**

`getSectionTypeGuide()` 함수(라인 166-193)에 case 추가:

```typescript
    case 'group':
      return null; // group은 buildSectionPrompt에서 처리
    case 'divider':
      return null; // divider는 프롬프트에 미포함
```

- [ ] **Step 4: parseSections 계층 구조 파싱**

`parseSections()` 함수(라인 195-244)를 계층 구조를 지원하도록 교체:

```typescript
function parseSections(text: string, template?: Template): OutputSection[] {
  if (!template) {
    return [
      {
        key: 'content',
        label: '분석 결과',
        content: text.trim(),
      },
    ];
  }

  const sections: OutputSection[] = [];
  // ## / ### / #### 헤더로 분할
  const headerRegex = /^(#{2,4})\s+(.+)$/gm;
  const headers: Array<{ level: number; label: string; start: number }> = [];
  let match;
  while ((match = headerRegex.exec(text)) !== null) {
    headers.push({
      level: match[1].length - 1, // ## → 1, ### → 2, #### → 3
      label: match[2].trim(),
      start: match.index + match[0].length,
    });
  }

  function findContent(label: string): string {
    const headerIdx = headers.findIndex((h) => h.label === label);
    if (headerIdx === -1) return '';
    const start = headers[headerIdx].start;
    const end = headerIdx + 1 < headers.length ? headers[headerIdx + 1].start - (headers[headerIdx + 1].label.length + headers[headerIdx + 1].level + 2) : text.length;
    return text.substring(start, end).trim();
  }

  function processSections(templateSections: TemplateSection[]): OutputSection[] {
    const result: OutputSection[] = [];
    for (const section of templateSections) {
      if (section.static || section.type === 'divider') continue;

      if (section.type === 'group') {
        const childSections = section.children ? processSections(section.children) : [];
        result.push(...childSections);
        continue;
      }

      const content = findContent(section.label);
      if (!content) continue;

      const outputSection: OutputSection = {
        key: section.key,
        label: section.label,
        content,
      };

      if (section.type === 'cards') {
        const jsonMatch = content.match(/```json\s*([\s\S]*?)```/);
        if (jsonMatch) {
          try {
            outputSection.data = JSON.parse(jsonMatch[1].trim());
          } catch {
            // Keep data undefined if parsing fails
          }
        }
      }

      result.push(outputSection);
    }
    return result;
  }

  return processSections(template.sections);
}
```

- [ ] **Step 5: 테스트 파일 작성**

`apps/firehub-ai-agent/src/routes/proactive.test.ts` 생성:

```typescript
import { describe, it, expect } from 'vitest';

// buildSectionPrompt와 parseSections를 테스트하기 위해 export 필요
// proactive.ts에서 해당 함수들을 export 추가

describe('buildSectionPrompt', () => {
  it('should include instruction in section prompt', () => {
    const sections = [
      { key: 'summary', label: '요약', type: 'text', instruction: '핵심 지표를 요약하세요.' },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toContain('지시: 핵심 지표를 요약하세요.');
  });

  it('should skip static sections with note', () => {
    const sections = [
      { key: 'disclaimer', label: '면책조항', type: 'text', static: true, content: '고정 텍스트' },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toContain('정적 섹션');
    expect(result).not.toContain('고정 텍스트');
  });

  it('should handle nested group sections', () => {
    const sections = [
      {
        key: 'ops', label: '운영 현황', type: 'group',
        instruction: '시스템 운영 상태를 분석하세요.',
        children: [
          { key: 'kpi', label: '핵심 지표', type: 'cards', instruction: 'KPI 카드를 표시하세요.' },
        ],
      },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toContain('## 운영 현황');
    expect(result).toContain('### 핵심 지표');
    expect(result).toContain('지시: 시스템 운영 상태를 분석하세요.');
    expect(result).toContain('지시: KPI 카드를 표시하세요.');
  });

  it('should skip divider sections', () => {
    const sections = [
      { key: 'div1', label: '구분선', type: 'divider' },
    ];
    const result = buildSectionPrompt(sections);
    expect(result).toBe('');
  });
});

describe('parseSections', () => {
  it('should parse flat sections from AI response', () => {
    const text = '## 요약\n내용입니다.\n\n## 상세\n상세 내용.';
    const template = {
      sections: [
        { key: 'summary', label: '요약', type: 'text' },
        { key: 'detail', label: '상세', type: 'text' },
      ],
      output_format: 'markdown',
    };
    const result = parseSections(text, template as any);
    expect(result).toHaveLength(2);
    expect(result[0].key).toBe('summary');
    expect(result[0].content).toContain('내용입니다');
  });

  it('should skip static sections in parsing', () => {
    const text = '## 요약\n내용입니다.';
    const template = {
      sections: [
        { key: 'disclaimer', label: '면책조항', type: 'text', static: true },
        { key: 'summary', label: '요약', type: 'text' },
      ],
      output_format: 'markdown',
    };
    const result = parseSections(text, template as any);
    expect(result).toHaveLength(1);
    expect(result[0].key).toBe('summary');
  });

  it('should flatten group children in output', () => {
    const text = '## 운영 현황\n\n### 핵심 지표\nKPI 내용';
    const template = {
      sections: [
        {
          key: 'ops', label: '운영 현황', type: 'group',
          children: [
            { key: 'kpi', label: '핵심 지표', type: 'text' },
          ],
        },
      ],
      output_format: 'markdown',
    };
    const result = parseSections(text, template as any);
    expect(result).toHaveLength(1);
    expect(result[0].key).toBe('kpi');
    expect(result[0].content).toContain('KPI 내용');
  });
});
```

- [ ] **Step 6: 테스트 대상 함수 export 추가**

`proactive.ts`에서 `buildSectionPrompt`와 `parseSections` 함수를 `export`로 변경:

```typescript
export function buildSectionPrompt(sections: TemplateSection[], depth = 1): string {
// ...
export function parseSections(text: string, template?: Template): OutputSection[] {
```

- [ ] **Step 7: 테스트 실행**

Run: `cd apps/firehub-ai-agent && pnpm test -- src/routes/proactive.test.ts`
Expected: PASS (모든 테스트 통과)

- [ ] **Step 8: AI Agent 타입체크**

Run: `cd apps/firehub-ai-agent && pnpm typecheck`
Expected: PASS

- [ ] **Step 9: 커밋**

```bash
git add apps/firehub-ai-agent/src/routes/proactive.ts apps/firehub-ai-agent/src/routes/proactive.test.ts
git commit -m "feat(proactive): AI 프롬프트 빌더 계층 구조 + instruction 지원"
```

---

### Task 3: 백엔드 렌더링 — 계층 구조 + 정적 섹션 + 변수 치환

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ReportRenderUtils.java`
- Modify: `apps/firehub-api/src/main/resources/templates/proactive-report.html`
- Modify: `apps/firehub-api/src/main/resources/templates/proactive-report-pdf.html`
- Modify: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ReportRenderUtilsTest.java` (있으면 수정, 없으면 생성)

- [ ] **Step 1: ReportRenderUtils에 변수 치환 메서드 추가**

`ReportRenderUtils.java`에 정적 콘텐츠 변수 치환 메서드 추가:

```java
public String substituteVariables(String content, Map<String, String> variables) {
    if (content == null || content.isBlank()) return "";
    String result = content;
    for (Map.Entry<String, String> entry : variables.entrySet()) {
        result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
    }
    return result;
}

public Map<String, String> buildVariables(String jobName, String author, String templateName) {
    Map<String, String> vars = new HashMap<>();
    vars.put("date", java.time.LocalDateTime.now().format(
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    vars.put("jobName", jobName != null ? jobName : "");
    vars.put("author", author != null ? author : "");
    vars.put("templateName", templateName != null ? templateName : "");
    return vars;
}
```

- [ ] **Step 2: buildTemplateSections에 정적 섹션 처리 추가**

기존 `buildTemplateSections()` 메서드를 확장하여 정적 섹션과 divider를 처리. 시그니처에 templateSections(원본 구조)와 variables 추가:

```java
public List<Map<String, Object>> buildTemplateSections(
        List<ProactiveResult.Section> aiSections,
        List<Map<String, Object>> templateStructure,
        Map<String, String> variables) {
    List<Map<String, Object>> result = new ArrayList<>();
    processTemplateSections(templateStructure, aiSections, variables, result, 1);
    return result;
}

private void processTemplateSections(
        List<Map<String, Object>> templateSections,
        List<ProactiveResult.Section> aiSections,
        Map<String, String> variables,
        List<Map<String, Object>> result,
        int depth) {
    if (templateSections == null) return;
    for (Map<String, Object> tmplSection : templateSections) {
        String type = (String) tmplSection.get("type");
        String label = (String) tmplSection.get("label");
        String key = (String) tmplSection.get("key");
        Boolean isStatic = (Boolean) tmplSection.get("static");

        if ("divider".equals(type)) {
            Map<String, Object> map = new HashMap<>();
            map.put("type", "divider");
            map.put("label", "");
            map.put("content", "");
            map.put("depth", depth);
            result.add(map);
            continue;
        }

        if (Boolean.TRUE.equals(isStatic)) {
            String content = (String) tmplSection.get("content");
            String rendered = substituteVariables(content, variables);
            Map<String, Object> map = new HashMap<>();
            map.put("label", label);
            map.put("content", markdownToHtml(rendered));
            map.put("static", true);
            map.put("depth", depth);
            result.add(map);
            continue;
        }

        if ("group".equals(type)) {
            Map<String, Object> map = new HashMap<>();
            map.put("label", label);
            map.put("type", "group");
            map.put("content", "");
            map.put("depth", depth);
            result.add(map);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children =
                (List<Map<String, Object>>) tmplSection.get("children");
            if (children != null) {
                processTemplateSections(children, aiSections, variables, result, depth + 1);
            }
            continue;
        }

        // AI 생성 섹션: aiSections에서 매칭
        ProactiveResult.Section aiSection = aiSections.stream()
            .filter(s -> key.equals(s.key()))
            .findFirst()
            .orElse(null);

        Map<String, Object> map = new HashMap<>();
        map.put("label", label != null ? label : "");
        map.put("depth", depth);

        if (aiSection != null) {
            map.put("content", aiSection.content() != null
                ? markdownToHtml(aiSection.content()) : "");
            if (aiSection.data() instanceof Map<?, ?> dataMap) {
                Object cards = dataMap.get("cards");
                if (cards instanceof List<?> cardList) {
                    map.put("cards", cardList);
                }
            }
        } else {
            map.put("content", "");
        }

        result.add(map);
    }
}
```

- [ ] **Step 3: 기존 buildTemplateSections 호출부 하위 호환**

기존 `buildTemplateSections(List<ProactiveResult.Section>)` 시그니처를 유지하여 기존 호출부가 깨지지 않도록:

```java
/** 하위 호환용: 템플릿 구조 없이 AI 섹션만으로 렌더링 */
public List<Map<String, Object>> buildTemplateSections(List<ProactiveResult.Section> sections) {
    List<Map<String, Object>> templateSections = new ArrayList<>();
    for (ProactiveResult.Section section : sections) {
        Map<String, Object> map = new HashMap<>();
        map.put("label", section.label() != null ? section.label() : "");
        map.put("content", section.content() != null ? markdownToHtml(section.content()) : "");
        if (section.data() instanceof Map<?, ?> dataMap) {
            Object cards = dataMap.get("cards");
            if (cards instanceof List<?> cardList) {
                map.put("cards", cardList);
            }
        }
        templateSections.add(map);
    }
    return templateSections;
}
```

- [ ] **Step 4: Thymeleaf 이메일 템플릿에 계층 + divider + static 반영**

`proactive-report.html`의 섹션 루프를 수정하여 depth, divider, static, group을 처리:

```html
<div th:each="section : ${sections}" class="section"
     th:style="'margin-left: ' + (${section['depth'] != null ? (section['depth'] - 1) * 20 : 0}) + 'px'">

  <!-- Divider -->
  <th:block th:if="${section['type'] != null and section['type'] == 'divider'}">
    <hr style="border: none; border-top: 1px solid #e0e0e0; margin: 16px 0;" />
  </th:block>

  <!-- Group header -->
  <th:block th:if="${section['type'] != null and section['type'] == 'group'}">
    <div style="font-size: 18px; font-weight: 700; color: #333; margin: 20px 0 8px 0; border-bottom: 2px solid #e0e0e0; padding-bottom: 4px;"
         th:text="${section['label']}">Group</div>
  </th:block>

  <!-- Regular section (static or AI-generated) -->
  <th:block th:if="${section['type'] == null or (section['type'] != 'divider' and section['type'] != 'group')}">
    <div class="section-label" th:text="${section['label']}">Label</div>

    <!-- Cards -->
    <th:block th:if="${section['cards'] != null and !section['cards'].isEmpty()}">
      <!-- existing cards rendering -->
    </th:block>

    <!-- Chart -->
    <th:block th:if="${section['chartCid'] != null}">
      <img th:src="'cid:' + ${section['chartCid']}" style="max-width: 500px;" alt="Chart" />
    </th:block>

    <!-- Content -->
    <div th:if="${section['content'] != null and !section['content'].isBlank()}"
         th:utext="${section['content']}"></div>
  </th:block>
</div>
```

- [ ] **Step 5: PDF 템플릿에도 동일 변경 적용**

`proactive-report-pdf.html`에도 Step 4와 같은 depth/divider/group/static 처리 적용. Flying Saucer는 flexbox 미지원이므로 `margin-left`로 들여쓰기.

- [ ] **Step 6: 테스트 작성**

`ReportRenderUtilsTest.java`에 변수 치환 + 정적 섹션 테스트 추가:

```java
@Test
void substituteVariables_replacesAllPlaceholders() {
    var vars = Map.of("date", "2026-04-03", "jobName", "일간 요약");
    String result = reportRenderUtils.substituteVariables(
        "리포트: {{jobName}} ({{date}})", vars);
    assertEquals("리포트: 일간 요약 (2026-04-03)", result);
}

@Test
void substituteVariables_handlesNullContent() {
    String result = reportRenderUtils.substituteVariables(null, Map.of());
    assertEquals("", result);
}
```

- [ ] **Step 7: 백엔드 테스트 실행**

Run: `cd apps/firehub-api && ./gradlew test --tests "*.ReportRenderUtilsTest"`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ReportRenderUtils.java \
  apps/firehub-api/src/main/resources/templates/proactive-report.html \
  apps/firehub-api/src/main/resources/templates/proactive-report-pdf.html \
  apps/firehub-api/src/test/
git commit -m "feat(proactive): 렌더링 계층 구조 + 정적 섹션 변수 치환 + divider 지원"
```

---

### Task 4: 빌트인 템플릿 instruction 필드 추가

**Files:**
- Create: `apps/firehub-api/src/main/resources/db/migration/V44__update_builtin_template_instructions.sql`

- [ ] **Step 1: 마이그레이션 SQL 작성**

기존 빌트인 템플릿 3개의 sections JSONB에 instruction 필드를 추가. style은 V43에서 이미 추가됨.

```sql
-- 일간 요약 리포트: instruction 추가
UPDATE report_template
SET sections = '[
    {"key": "summary", "label": "요약", "required": true, "type": "text", "instruction": "오늘 시스템의 전반적인 상태를 3-5문장으로 요약하세요. 가장 주목할 변화를 먼저 언급하세요."},
    {"key": "stats", "label": "통계", "required": false, "type": "cards", "instruction": "파이프라인 총 실행 건수, 성공률, 데이터셋 수, 활성 사용자 수를 카드로 표시하세요."},
    {"key": "details", "label": "상세 내역", "required": false, "type": "list", "instruction": "실패한 파이프라인, 새로 생성된 데이터셋, 주요 변경사항을 나열하세요."},
    {"key": "attention", "label": "주의 항목", "required": false, "type": "list", "instruction": "즉시 조치가 필요한 항목을 심각도 순으로 나열하세요."},
    {"key": "recommendation", "label": "권장 사항", "required": false, "type": "recommendation", "instruction": "데이터를 기반으로 구체적 개선 조치를 제안하세요."}
]'::jsonb
WHERE name = '일간 요약 리포트' AND user_id IS NULL;

-- 실패 분석 리포트: instruction 추가
UPDATE report_template
SET sections = '[
    {"key": "overview", "label": "개요", "required": true, "type": "text", "instruction": "실패 현황을 전체 대비 비율과 함께 요약하세요."},
    {"key": "failures", "label": "실패 목록", "required": false, "type": "list", "instruction": "실패한 파이프라인/작업을 시간순으로 나열하세요. 각 항목에 실패 원인을 한 줄로 추가하세요."},
    {"key": "analysis", "label": "원인 분석", "required": false, "type": "text", "instruction": "공통 패턴이나 근본 원인을 분석하세요. 가능하면 연관된 실패를 그룹핑하세요."},
    {"key": "impact", "label": "영향도", "required": false, "type": "text", "instruction": "실패로 인한 비즈니스 영향과 데이터 파이프라인 연쇄 영향을 평가하세요."},
    {"key": "resolution", "label": "해결 방안", "required": false, "type": "recommendation", "instruction": "각 실패 유형별로 구체적 해결 단계와 재발 방지 조치를 제안하세요."}
]'::jsonb
WHERE name = '실패 분석 리포트' AND user_id IS NULL;

-- 주간 트렌드 리포트: instruction 추가
UPDATE report_template
SET sections = '[
    {"key": "summary", "label": "주간 요약", "required": true, "type": "text", "instruction": "이번 주 가장 주목할 변화 3가지를 요약하세요. 지난주 대비 개선/악화를 명확히 구분하세요."},
    {"key": "comparison", "label": "전주 비교", "required": false, "type": "cards", "instruction": "핵심 지표의 전주 대비 변화를 카드로 표시하세요. 변화율(%)을 description에 포함하세요."},
    {"key": "trends", "label": "트렌드", "required": false, "type": "list", "instruction": "최근 4주간의 추세를 분석하세요. 단기 변동과 중기 트렌드를 구분하세요."},
    {"key": "highlights", "label": "주요 이슈", "required": false, "type": "alert", "instruction": "이번 주 발생한 주요 이슈를 심각도 순으로 나열하세요."},
    {"key": "outlook", "label": "전망", "required": false, "type": "text", "instruction": "다음 주 예상되는 변화와 주의할 점을 서술하세요."}
]'::jsonb
WHERE name = '주간 트렌드 리포트' AND user_id IS NULL;
```

- [ ] **Step 2: baseline-version 업데이트**

`apps/firehub-api/src/main/resources/application.yml`에서 `baseline-version`을 44로 업데이트.

- [ ] **Step 3: 마이그레이션 테스트**

Run: `cd apps/firehub-api && ./gradlew build -x test`
Expected: BUILD SUCCESSFUL (Flyway 마이그레이션 적용)

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-api/src/main/resources/db/migration/V44__update_builtin_template_instructions.sql \
  apps/firehub-api/src/main/resources/application.yml
git commit -m "feat(proactive): 빌트인 템플릿에 instruction 필드 추가 (V44)"
```

---

### Task 5: @dnd-kit 의존성 설치

**Files:**
- Modify: `apps/firehub-web/package.json`

- [ ] **Step 1: @dnd-kit 패키지 설치**

Run: `cd apps/firehub-web && pnpm add @dnd-kit/core @dnd-kit/sortable @dnd-kit/utilities`

- [ ] **Step 2: 빌드 확인**

Run: `cd apps/firehub-web && pnpm build`
Expected: BUILD SUCCESS

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/package.json pnpm-lock.yaml
git commit -m "chore(web): @dnd-kit 의존성 추가"
```

---

### Task 6: useSectionTree 상태 관리 훅

**Files:**
- Create: `apps/firehub-web/src/pages/ai-insights/hooks/useSectionTree.ts`

> **디자이너 권고**: 섹션 트리의 모든 상태 변이(추가/삭제/수정/이동/접기)를 단일 훅으로 관리. 컴포넌트에서는 읽기+콜백만 사용.

- [ ] **Step 1: useSectionTree 훅 구현**

```typescript
// apps/firehub-web/src/pages/ai-insights/hooks/useSectionTree.ts

import { useState, useCallback } from 'react';
import { toast } from 'sonner';
import type { TemplateSection, SectionType } from '@/api/proactive';
import { validateSectionDepth, flattenSections } from '@/lib/template-section-types';

interface UseSectionTreeReturn {
  sections: TemplateSection[];
  setSections: (sections: TemplateSection[]) => void;
  selectedKey: string | null;
  setSelectedKey: (key: string | null) => void;
  selectedSection: TemplateSection | null;
  addSection: (type: SectionType, parentKey?: string) => void;
  removeSection: (key: string) => void;
  updateSection: (key: string, patch: Partial<TemplateSection>) => void;
  moveSection: (activeId: string, overId: string) => void;
  toggleCollapsed: (key: string) => void;
  collapsedKeys: Set<string>;
  flatItems: Array<{ section: TemplateSection; depth: number; parentKey: string | null }>;
}

export function useSectionTree(
  initialSections: TemplateSection[],
): UseSectionTreeReturn {
  const [sections, setSections] = useState<TemplateSection[]>(initialSections);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [collapsedKeys, setCollapsedKeys] = useState<Set<string>>(new Set());

  // 트리를 DFS로 순회하여 플랫 리스트 생성 (dnd-kit용)
  const flatItems = useMemo(() => {
    const result: Array<{ section: TemplateSection; depth: number; parentKey: string | null }> = [];
    function walk(items: TemplateSection[], depth: number, parentKey: string | null) {
      for (const item of items) {
        result.push({ section: item, depth, parentKey });
        if (item.type === 'group' && item.children && !collapsedKeys.has(item.key)) {
          walk(item.children, depth + 1, item.key);
        }
      }
    }
    walk(sections, 0, null);
    return result;
  }, [sections, collapsedKeys]);

  // 선택된 섹션 객체
  const selectedSection = useMemo(() => {
    if (!selectedKey) return null;
    return flattenSections(sections).find(s => s.key === selectedKey) ?? null;
  }, [sections, selectedKey]);

  // 섹션 추가: 고유 key 자동 생성
  const addSection = useCallback((type: SectionType, parentKey?: string) => {
    const allKeys = flattenSections(sections).map(s => s.key);
    let counter = 1;
    let key = `${type}_${counter}`;
    while (allKeys.includes(key)) { counter++; key = `${type}_${counter}`; }

    const newSection: TemplateSection = {
      key,
      type,
      label: type === 'group' ? '새 그룹' : type === 'divider' ? '구분선' : `새 ${type} 섹션`,
      ...(type === 'group' ? { children: [] } : {}),
      ...(type === 'divider' ? { static: true } : {}),
    };

    setSections(prev => {
      if (parentKey) {
        // parentKey의 children에 추가 (깊이 검증)
        return addToParent(prev, parentKey, newSection);
      }
      return [...prev, newSection];
    });
    setSelectedKey(key);
  }, [sections]);

  // 섹션 삭제, 수정, 이동, 접기/펼치기 — 재귀 헬퍼로 구현
  // moveSection: 깊이 제한 초과 시 toast("최대 3단계까지 중첩 가능합니다") + 무시

  return {
    sections, setSections, selectedKey, setSelectedKey, selectedSection,
    addSection, removeSection, updateSection, moveSection,
    toggleCollapsed, collapsedKeys, flatItems,
  };
}
```

- [ ] **Step 2: 타입체크 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/hooks/useSectionTree.ts
git commit -m "feat(web): useSectionTree 훅 — 섹션 트리 상태 관리"
```

---

### Task 7: SectionTreeItem + DragInsertIndicator 컴포넌트

**Files:**
- Create: `apps/firehub-web/src/pages/ai-insights/components/SectionTreeItem.tsx`
- Create: `apps/firehub-web/src/pages/ai-insights/components/DragInsertIndicator.tsx`

> **디자이너 토큰 참조**:
> - Default: `flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted/50 transition-colors group`
> - Selected: `bg-accent border-l-2 border-l-primary`
> - Static: `text-muted-foreground` + `border-l-muted-foreground`
> - Drag handle: `opacity-0 group-hover:opacity-100 transition-opacity cursor-grab active:cursor-grabbing`
> - Indicator: `h-0.5 bg-primary rounded-full`

- [ ] **Step 1: DragInsertIndicator 구현**

```tsx
// apps/firehub-web/src/pages/ai-insights/components/DragInsertIndicator.tsx

interface DragInsertIndicatorProps {
  visible: boolean;
  indent: number; // depth level
}

export function DragInsertIndicator({ visible, indent }: DragInsertIndicatorProps) {
  if (!visible) return null;
  return (
    <div
      className="h-0.5 bg-primary rounded-full absolute left-0 right-0 z-10"
      style={{ marginLeft: indent * 16 }}
    />
  );
}
```

- [ ] **Step 2: SectionTreeItem 구현**

```tsx
// apps/firehub-web/src/pages/ai-insights/components/SectionTreeItem.tsx

import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, ChevronRight, ChevronDown, Trash2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Collapsible, CollapsibleTrigger } from '@/components/ui/collapsible';
import type { TemplateSection } from '@/api/proactive';
import { getSectionTypeDef } from '@/lib/template-section-types';

interface SectionTreeItemProps {
  section: TemplateSection;
  depth: number;
  isSelected: boolean;
  isCollapsed: boolean;
  isDragOverlay?: boolean;
  onSelect: () => void;
  onRemove: () => void;
  onToggleCollapse: () => void;
}

export function SectionTreeItem({
  section, depth, isSelected, isCollapsed, isDragOverlay,
  onSelect, onRemove, onToggleCollapse,
}: SectionTreeItemProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: section.key });

  const def = getSectionTypeDef(section.type);
  const isStatic = section.static || section.type === 'divider';
  const isGroup = section.type === 'group';

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    paddingLeft: depth * 16,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`
        flex items-center gap-2 rounded-md px-2 py-1.5 text-sm transition-colors group relative
        ${isDragging ? 'opacity-40' : ''}
        ${isDragOverlay ? 'bg-muted border border-border shadow-lg rounded-md opacity-90' : ''}
        ${isSelected ? 'bg-accent border-l-2 border-l-primary' : `hover:bg-muted/50 border-l-3 ${isStatic ? 'border-l-muted-foreground' : def?.color ?? 'border-l-gray-500'}`}
        ${isStatic ? 'text-muted-foreground' : ''}
      `}
      onClick={onSelect}
      {...attributes}
    >
      {/* 드래그 핸들 */}
      <span className="opacity-0 group-hover:opacity-100 transition-opacity cursor-grab active:cursor-grabbing"
        {...listeners}>
        <GripVertical className="h-4 w-4 text-muted-foreground" />
      </span>

      {/* 그룹 접기/펼치기 토글 */}
      {isGroup && (
        <CollapsibleTrigger onClick={(e) => { e.stopPropagation(); onToggleCollapse(); }}>
          {isCollapsed
            ? <ChevronRight className="h-3.5 w-3.5 text-muted-foreground transition-transform" />
            : <ChevronDown className="h-3.5 w-3.5 text-muted-foreground transition-transform" />}
        </CollapsibleTrigger>
      )}

      {/* 아이콘 + 라벨 */}
      <span>{def?.icon}</span>
      <span className={`flex-1 truncate ${isGroup ? 'font-medium' : ''}`}>{section.label}</span>

      {/* 뱃지들 */}
      {section.required && <span className="w-1.5 h-1.5 rounded-full bg-amber-500 flex-shrink-0" />}
      {isStatic && <Badge variant="secondary" className="text-[10px] h-5">정적</Badge>}
      <Badge variant="outline" className="text-[10px]">{section.type}</Badge>

      {/* 삭제 */}
      <Button variant="ghost" size="icon" className="h-6 w-6 opacity-0 group-hover:opacity-100"
        onClick={(e) => { e.stopPropagation(); onRemove(); }}>
        <Trash2 className="h-3 w-3" />
      </Button>
    </div>
  );
}
```

- [ ] **Step 3: 타입체크**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/components/SectionTreeItem.tsx \
  apps/firehub-web/src/pages/ai-insights/components/DragInsertIndicator.tsx
git commit -m "feat(web): SectionTreeItem + DragInsertIndicator 컴포넌트"
```

---

### Task 8: SectionTreeBuilder 컴포넌트

**Files:**
- Create: `apps/firehub-web/src/pages/ai-insights/components/SectionTreeBuilder.tsx`

> **디자이너 지침 참조**: 설계 문서 섹션 8.2 (드래그앤드롭 UX), 8.3 (섹션 선택), 8.4 (정적 섹션 시각 구분)

- [ ] **Step 1: SectionTreeItem 컴포넌트 구현**

트리의 개별 행을 렌더링하는 컴포넌트. @dnd-kit/sortable의 `useSortable` 훅 사용.

```tsx
// apps/firehub-web/src/pages/ai-insights/components/SectionTreeBuilder.tsx

import { useState } from 'react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, ChevronRight, ChevronDown, Plus } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { TemplateSection, SectionType } from '@/api/proactive';
import { SECTION_TYPES, getSectionTypeDef } from '@/lib/template-section-types';

interface SectionTreeBuilderProps {
  sections: TemplateSection[];
  selectedKey: string | null;
  onSelect: (key: string) => void;
  onChange: (sections: TemplateSection[]) => void;
}

// 전체 구현은 에이전트가 작성 — 핵심 구조만 명시

export function SectionTreeBuilder({
  sections,
  selectedKey,
  onSelect,
  onChange,
}: SectionTreeBuilderProps) {
  // DnD 센서 설정
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor),
  );

  // 트리 아이템 렌더링 (재귀)
  // 각 아이템: 드래그 핸들 + 아이콘 + 라벨 + 타입 뱃지 + [필수 dot] + [정적 뱃지]
  // group 타입: Collapsible로 접기/펼치기 + children 재귀 렌더링
  // 선택 상태: bg-accent border-l-primary
  // 정적 섹션: border-l-muted-foreground + text-muted-foreground

  // 섹션 추가: DropdownMenu로 타입 선택
  // 그룹 추가: group 타입 직접 생성

  // DragEnd 핸들러: 같은 레벨 내 순서 변경 + 그룹 간 이동 + 깊이 제한 검증

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center justify-between px-4 py-3 border-b">
        <h3 className="text-sm font-semibold">섹션 구조</h3>
        <Badge variant="secondary" className="text-xs">
          {flattenSections(sections).length}개
        </Badge>
      </div>

      <ScrollArea className="flex-1">
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          {/* 재귀 트리 렌더링 */}
        </DndContext>
      </ScrollArea>

      <div className="flex gap-2 p-3 border-t">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="sm" className="flex-1 border border-dashed">
              <Plus className="h-3.5 w-3.5 mr-1" /> 섹션 추가
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            {SECTION_TYPES.filter(t => t.type !== 'group' && t.type !== 'divider').map(t => (
              <DropdownMenuItem key={t.type} onClick={() => addSection(t.type)}>
                {t.icon} {t.label}
              </DropdownMenuItem>
            ))}
            <DropdownMenuItem onClick={() => addSection('divider')}>
              ➖ Divider
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <Button variant="ghost" size="sm" className="flex-1 border border-dashed"
          onClick={() => addSection('group')}>
          <Plus className="h-3.5 w-3.5 mr-1" /> 그룹 추가
        </Button>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 타입체크 + 빌드 확인**

Run: `cd apps/firehub-web && pnpm typecheck && pnpm build`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/components/SectionTreeBuilder.tsx
git commit -m "feat(web): SectionTreeBuilder 드래그앤드롭 트리 컴포넌트"
```

---

### Task 9: SectionPropertyEditor 컴포넌트

**Files:**
- Create: `apps/firehub-web/src/pages/ai-insights/components/SectionPropertyEditor.tsx`

> **디자이너 지침 참조**: 설계 문서 섹션 8.3 (속성 패널), 8.4 (정적 섹션)

- [ ] **Step 1: 속성 편집 패널 구현**

선택된 섹션 타입에 따라 다른 폼을 표시:
- 일반 섹션: Label, Key, Type, Required(Switch), Instruction(Textarea), Description(Input), 타입 가이드
- 정적 섹션: Label, Key, Content(Textarea) + 변수 칩
- 그룹: Label, Key, Instruction
- 구분선: 라벨만

```tsx
// apps/firehub-web/src/pages/ai-insights/components/SectionPropertyEditor.tsx

import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import type { TemplateSection, SectionType } from '@/api/proactive';
import { SECTION_TYPES, getSectionTypeDef } from '@/lib/template-section-types';

interface SectionPropertyEditorProps {
  section: TemplateSection;
  onChange: (updated: TemplateSection) => void;
}

const TEMPLATE_VARIABLES = [
  { key: 'date', label: '실행 일시' },
  { key: 'jobName', label: '작업 이름' },
  { key: 'author', label: '작성자' },
  { key: 'templateName', label: '템플릿 이름' },
  { key: 'period', label: '분석 기간' },
];

export function SectionPropertyEditor({ section, onChange }: SectionPropertyEditorProps) {
  const typeDef = getSectionTypeDef(section.type);
  const isStatic = section.static || section.type === 'divider';
  const isGroup = section.type === 'group';

  // 속성 패널 상단: 선택된 섹션 key
  // key: font-mono text-xs text-muted-foreground

  // 공통 필드: Label, Key (snake_case 검증)
  // Type: Select (SectionType)
  // Required: Switch (aria-label="필수 항목") — 정적/group이면 숨김

  // 조건부 필드:
  // - 일반: Instruction(Textarea rows={4}) + Description(Input) + 타입 가이드(bg-muted/40)
  // - 정적: Content(Textarea) + 변수 칩(클릭 시 커서 위치에 삽입)
  // - 그룹: Instruction만
  // - 구분선: 최소 필드만

  return (
    <Card>
      <CardContent className="pt-6 space-y-6">
        {/* 섹션 key 표시 */}
        <div className="font-mono text-xs text-muted-foreground">key: {section.key}</div>

        {/* Label + Key */}
        {/* Type + Required */}
        {/* 조건부: AI Instruction / Static Content / Type Guide */}
      </CardContent>
    </Card>
  );
}
```

- [ ] **Step 2: 타입체크 확인**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/components/SectionPropertyEditor.tsx
git commit -m "feat(web): SectionPropertyEditor 속성 편집 패널"
```

---

### Task 10: ReportTemplateDetailPage 빌더 통합

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx`

- [ ] **Step 1: 페이지 레이아웃을 빌더 구조로 교체**

기존 TemplateJsonEditor + TemplateSidePanel 레이아웃을 다음으로 교체:
- 좌측 패널: 템플릿 메타(이름, 스타일 지시) + SectionTreeBuilder
- 우측 패널: Tabs (빌더/JSON) — 빌더 탭은 SectionPropertyEditor, JSON 탭은 기존 TemplateJsonEditor

```tsx
// 핵심 구조:
// 1. 헤더: breadcrumb + 템플릿 이름 + 액션 버튼 (취소 ghost, 미리보기 outline, 저장 default)
// 2. 본문: 2-column grid (lg:grid-cols-5)
//    - 좌측 (col-span-2): 메타 입력 + SectionTreeBuilder
//    - 우측 (col-span-3): Tabs [빌더 | JSON]
//       - 빌더: SectionPropertyEditor (선택된 섹션)
//       - JSON: TemplateJsonEditor (기존, 계층 JSON 지원)

// 상태 관리:
// - sections: TemplateSection[] — 트리 구조
// - selectedKey: string | null
// - 빌더 ↔ JSON 동기화: sections → JSON string, JSON string → sections
```

- [ ] **Step 2: 빌더 ↔ JSON 동기화 로직**

```typescript
// sections가 변경되면 JSON string 업데이트
useEffect(() => {
  const json = JSON.stringify({ sections, output_format: 'markdown' }, null, 2);
  setStructureJson(json);
}, [sections]);

// JSON 탭에서 편집 후 빌더 탭으로 전환 시
function handleTabChange(tab: string) {
  if (tab === 'builder' && activeTab === 'json') {
    const parsed = parseTemplateSections(structureJson);
    if (parsed) {
      setSections(parsed);
    }
    // 파싱 실패 시 에러 배너 표시
  }
  setActiveTab(tab);
}
```

- [ ] **Step 3: 뷰 모드 유지**

기존 뷰 모드(편집 아닌 상태)에서는 SectionPreview로 읽기 전용 표시. SectionPreview도 계층 구조를 지원하도록 확장 (Task 9에서 처리).

- [ ] **Step 4: 빌드 + 타입체크**

Run: `cd apps/firehub-web && pnpm typecheck && pnpm build`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx
git commit -m "feat(web): ReportTemplateDetailPage 비주얼 빌더 통합"
```

---

### Task 11: SectionPreview 계층 구조 확장

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/components/SectionPreview.tsx`

- [ ] **Step 1: SectionPreview에 재귀 렌더링 추가**

기존 플랫 리스트를 트리 구조로 변경:
- group 타입: 헤더 + children 재귀
- static 섹션: 변수 치환 미리보기 (`{{date}}` → `2026-04-03 09:00` 등 샘플 값)
- divider: `<Separator />`
- 깊이별 들여쓰기: `pl-4` per level

```tsx
function renderSection(section: TemplateSection, depth = 0) {
  const def = getSectionTypeDef(section.type);
  const indent = depth * 16; // pl-4 per level

  if (section.type === 'divider') {
    return <Separator key={section.key} className="my-2" style={{ marginLeft: indent }} />;
  }

  if (section.type === 'group') {
    return (
      <div key={section.key} style={{ marginLeft: indent }}>
        <div className="flex items-center gap-2 py-1 font-semibold text-sm">
          {def?.icon} {section.label}
        </div>
        {section.children?.map(child => renderSection(child, depth + 1))}
      </div>
    );
  }

  return (
    <div key={section.key}
      className={`flex items-center gap-2 py-1.5 px-2 rounded border-l-3 ${def?.color ?? 'border-l-gray-500'} ${section.static ? 'text-muted-foreground' : ''}`}
      style={{ marginLeft: indent }}>
      <span>{def?.icon}</span>
      <span className="text-sm">{section.label}</span>
      {section.required && <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />}
      {section.static && <Badge variant="secondary" className="text-[10px]">정적</Badge>}
      <Badge variant="outline" className="text-[10px] ml-auto">{section.type}</Badge>
    </div>
  );
}
```

- [ ] **Step 2: 빌드 확인**

Run: `cd apps/firehub-web && pnpm build`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/src/pages/ai-insights/components/SectionPreview.tsx
git commit -m "feat(web): SectionPreview 계층 구조 + 정적 섹션 미리보기"
```

---

### Task 12: 통합 검증

**Files:** (검증만, 코드 변경 없음)

- [ ] **Step 1: 전체 빌드 확인**

Run: `pnpm build`
Expected: 전체 모노레포 빌드 PASS

- [ ] **Step 2: AI Agent 테스트 실행**

Run: `cd apps/firehub-ai-agent && pnpm test`
Expected: 전체 테스트 PASS

- [ ] **Step 3: 백엔드 테스트 실행**

Run: `cd apps/firehub-api && ./gradlew test`
Expected: 전체 테스트 PASS

- [ ] **Step 4: 프론트엔드 타입체크**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: PASS

- [ ] **Step 5: 개발 서버 기동 + Playwright 스크린샷**

Run: `pnpm dev`
- 리포트 템플릿 상세 페이지 접근 → 빌더 UI 표시 확인
- 기존 빌트인 템플릿이 정상 로드되는지 확인
- 편집 모드 진입 → 트리 빌더 + 속성 패널 표시 확인
- Playwright로 스크린샷 촬영 → `snapshots/` 저장

- [ ] **Step 6: 하위 호환 검증**

- 기존 사용자 템플릿(instruction 없음)이 빌더에서 정상 표시되는지 확인
- 기존 스마트 작업 실행 시 기존 프롬프트 동작 확인 (instruction 없으면 생략)
- PDF/이메일 기존 포맷 유지 확인

---

## 파일 변경 요약

| 파일 | 작업 | Task |
|------|------|------|
| `apps/firehub-web/src/api/proactive.ts` | 수정: SectionType + TemplateSection 확장 | 1 |
| `apps/firehub-web/src/lib/template-section-types.ts` | 수정: group, divider 추가 + 유틸 함수 | 1 |
| `apps/firehub-ai-agent/src/routes/proactive.ts` | 수정: 프롬프트 빌더 + 파서 계층 구조 | 2 |
| `apps/firehub-ai-agent/src/routes/proactive.test.ts` | 생성: 프롬프트 빌더 + 파서 테스트 | 2 |
| `apps/firehub-api/.../ReportRenderUtils.java` | 수정: 변수 치환 + 계층 렌더링 | 3 |
| `apps/firehub-api/.../proactive-report.html` | 수정: depth + divider + group | 3 |
| `apps/firehub-api/.../proactive-report-pdf.html` | 수정: 동일 | 3 |
| `apps/firehub-api/.../V44__update_builtin_template_instructions.sql` | 생성: instruction 추가 | 4 |
| `apps/firehub-web/package.json` | 수정: @dnd-kit 추가 | 5 |
| `apps/firehub-web/.../hooks/useSectionTree.ts` | 생성: 트리 상태 관리 훅 | 6 |
| `apps/firehub-web/.../SectionTreeItem.tsx` | 생성: 트리 개별 행 컴포넌트 | 7 |
| `apps/firehub-web/.../DragInsertIndicator.tsx` | 생성: 드래그 삽입 인디케이터 | 7 |
| `apps/firehub-web/.../SectionTreeBuilder.tsx` | 생성: DnD 트리 빌더 | 8 |
| `apps/firehub-web/.../SectionPropertyEditor.tsx` | 생성: 속성 편집 패널 | 9 |
| `apps/firehub-web/.../ReportTemplateDetailPage.tsx` | 수정: 빌더 통합 | 10 |
| `apps/firehub-web/.../SectionPreview.tsx` | 수정: 계층 구조 | 11 |
