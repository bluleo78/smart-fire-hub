# Phase 7-3: 리포트 내러티브 강화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 리포트 품질을 "데이터 나열"에서 "인사이트 중심 내러티브"로 향상시킨다.

**Architecture:** 시스템 프롬프트 강화 + 이전 실행 결과 컨텍스트 전달 + 템플릿 style 필드 추가의 3가지 축으로 개선한다.

**Tech Stack:** Spring Boot (Backend context collector), Node.js/TypeScript (AI Agent prompt), React (Frontend template editor)

**Spec:** `docs/superpowers/specs/2026-04-02-phase-7-3-narrative-enhancement-design.md`

---

## File Structure

### 수정
| 파일 | 역할 |
|------|------|
| `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveContextCollector.java` | 이전 실행 결과 3건 수집 |
| `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java` | collectContext 호출 시 jobId 전달 |
| `apps/firehub-ai-agent/src/routes/proactive.ts` | 시스템 프롬프트 강화 + Template.style |
| `apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx` | 스타일 입력란 추가 |

### 신규 생성
| 파일 | 역할 |
|------|------|
| `apps/firehub-api/src/main/resources/db/migration/V31__add_template_style.sql` | 빌트인 템플릿 style 추가 |
| `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ProactiveContextCollectorTest.java` | 컨텍스트 수집 테스트 (이전 실행 포함) |

---

## Task 1: 이전 실행 결과 컨텍스트 수집

ProactiveContextCollector에 이전 실행 결과 3건을 수집하는 로직을 추가한다.

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveContextCollector.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java`
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ProactiveContextCollectorTest.java`

### Step 1: ProactiveContextCollector 테스트 작성

`apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ProactiveContextCollectorTest.java` 생성:

```java
package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.smartfirehub.IntegrationTestBase;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockitoBean;

class ProactiveContextCollectorTest extends IntegrationTestBase {

  @Autowired private ProactiveContextCollector contextCollector;
  @MockitoBean private ProactiveJobExecutionRepository executionRepository;

  @Test
  void collectContext_includes_previousExecutions_when_jobId_provided() {
    var execution = new ProactiveJobExecutionResponse(
        1L, 10L, "COMPLETED", LocalDateTime.now().minusDays(1),
        LocalDateTime.now().minusDays(1), null,
        Map.of("title", "Test", "sections", List.of(
            Map.of("key", "s1", "label", "요약", "content", "테스트 내용"))),
        LocalDateTime.now().minusDays(1));
    when(executionRepository.findByJobId(anyLong(), anyInt(), anyInt()))
        .thenReturn(List.of(execution));

    String context = contextCollector.collectContext(Map.of(), 10L);

    assertThat(context).contains("previousExecutions");
    assertThat(context).contains("테스트 내용");
  }

  @Test
  void collectContext_works_without_jobId() {
    String context = contextCollector.collectContext(Map.of(), null);
    assertThat(context).doesNotContain("previousExecutions");
  }

  @Test
  void collectContext_truncates_long_section_content() {
    String longContent = "A".repeat(3000);
    var execution = new ProactiveJobExecutionResponse(
        1L, 10L, "COMPLETED", LocalDateTime.now(), LocalDateTime.now(), null,
        Map.of("sections", List.of(Map.of("key", "s1", "label", "L", "content", longContent))),
        LocalDateTime.now());
    when(executionRepository.findByJobId(anyLong(), anyInt(), anyInt()))
        .thenReturn(List.of(execution));

    String context = contextCollector.collectContext(Map.of(), 10L);

    // Content should be truncated to 2000 chars
    assertThat(context).doesNotContain("A".repeat(2500));
  }
}
```

### Step 2: 테스트 실패 확인

```bash
cd apps/firehub-api && ./gradlew test --tests "*.ProactiveContextCollectorTest"
```

Expected: FAIL — `collectContext` 시그니처가 다름.

### Step 3: ProactiveContextCollector 수정

`collectContext` 시그니처 변경: `collectContext(Map<String, Object> config, Long jobId)`

이전 실행 결과 수집 로직 추가 (기존 4개 병렬 호출 블록 뒤에):

```java
// 의존성 추가
private final ProactiveJobExecutionRepository executionRepository;

// collectContext 메서드 내, applyTargetFilter 호출 전에 추가:
if (jobId != null) {
  try {
    List<ProactiveJobExecutionResponse> recentExecutions =
        executionRepository.findByJobId(jobId, 3, 0).stream()
            .filter(e -> "COMPLETED".equals(e.status()) && e.result() != null)
            .toList();
    if (!recentExecutions.isEmpty()) {
      List<Map<String, Object>> prevExecs = recentExecutions.stream()
          .map(exec -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("completedAt", exec.completedAt() != null
                ? exec.completedAt().toString() : "");
            // sections에서 content만 추출 (2000자 제한)
            Object sectionsObj = exec.result().get("sections");
            if (sectionsObj instanceof List<?> sectionsList) {
              List<Map<String, String>> summarySections = sectionsList.stream()
                  .filter(s -> s instanceof Map)
                  .map(s -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sec = (Map<String, Object>) s;
                    String content = sec.get("content") instanceof String c ? c : "";
                    if (content.length() > 2000) {
                      content = content.substring(0, 2000) + "...[truncated]";
                    }
                    return Map.of(
                        "key", String.valueOf(sec.getOrDefault("key", "")),
                        "label", String.valueOf(sec.getOrDefault("label", "")),
                        "content", content);
                  })
                  .toList();
              entry.put("sections", summarySections);
            }
            return entry;
          })
          .toList();
      context.put("previousExecutions", prevExecs);
    }
  } catch (Exception e) {
    log.warn("Failed to collect previous executions for job {}", jobId, e);
  }
}
```

### Step 4: ProactiveJobService 수정

`executeJob()` 메서드에서 `contextCollector.collectContext(job.config())` 호출을 `contextCollector.collectContext(job.config(), jobId)`로 변경.

```java
// 변경 전:
String context = contextCollector.collectContext(job.config());

// 변경 후:
String context = contextCollector.collectContext(job.config(), jobId);
```

### Step 5: 테스트 통과 확인

```bash
cd apps/firehub-api && ./gradlew test --tests "*.ProactiveContextCollectorTest"
```

Expected: PASS

### Step 6: 커밋

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveContextCollector.java \
  apps/firehub-api/src/main/java/com/smartfirehub/proactive/service/ProactiveJobService.java \
  apps/firehub-api/src/test/java/com/smartfirehub/proactive/service/ProactiveContextCollectorTest.java
git commit -m "feat(proactive): add previous execution results to context (last 3)"
```

---

## Task 2: 빌트인 템플릿 style 추가 (DB 마이그레이션)

빌트인 템플릿 3개의 structure JSON에 `style` 필드를 추가한다.

**Files:**
- Create: `apps/firehub-api/src/main/resources/db/migration/V31__add_template_style.sql`

### Step 1: 마이그레이션 작성

`apps/firehub-api/src/main/resources/db/migration/V31__add_template_style.sql` 생성:

```sql
-- 빌트인 템플릿에 style 필드 추가
UPDATE report_template
SET sections = jsonb_set(
    sections,
    '{style}',
    '"간결한 경영진 보고 스타일. 핵심 변화를 먼저 서술하고, 수치는 맥락과 함께 제시. 이전 실행과 비교하여 변화 추이를 언급."'::jsonb
)
WHERE name = '일간 요약 리포트' AND user_id IS NULL;

UPDATE report_template
SET sections = jsonb_set(
    sections,
    '{style}',
    '"기술 분석 스타일. 실패 현상 → 근본 원인 → 영향도 → 해결 방안 순서로 논리적 서술. 재발 방지 관점의 권고사항 포함."'::jsonb
)
WHERE name = '실패 분석 리포트' AND user_id IS NULL;

UPDATE report_template
SET sections = jsonb_set(
    sections,
    '{style}',
    '"트렌드 분석 스타일. 이번 주와 지난주를 비교하여 변화율 중심 서술. 단기(1주) 변화와 중기(4주) 추세를 구분. 수치에는 반드시 변화율(%) 병기."'::jsonb
)
WHERE name = '주간 트렌드 리포트' AND user_id IS NULL;
```

**주의:** `sections` 컬럼은 JSONB 배열(sections 목록)이 아니라, 실제로는 `structure` 컬럼일 수 있다. 마이그레이션 작성 전 테이블 스키마를 확인할 것. `report_template` 테이블의 컬럼이 `structure`이면 위 SQL의 `sections`를 `structure`로 교체.

또한, `style`은 structure JSON의 최상위 필드이므로, 현재 structure가 `{"sections": [...]}` 형태라면 `jsonb_set(structure, '{style}', ...)`이 올바른 경로.

### Step 2: application.yml baseline-version 업데이트

```yaml
spring:
  flyway:
    baseline-version: 31
```

### Step 3: 빌드 확인

```bash
cd apps/firehub-api && ./gradlew build -x test
```

Expected: BUILD SUCCESS

### Step 4: 커밋

```bash
git add apps/firehub-api/src/main/resources/db/migration/V31__add_template_style.sql \
  apps/firehub-api/src/main/resources/application.yml
git commit -m "feat(proactive): add style field to built-in report templates"
```

---

## Task 3: 시스템 프롬프트 강화 (AI Agent)

`buildProactiveSystemPrompt()`를 강화하여 내러티브 가이드, style 반영, 섹션 타입별 가이드를 추가한다.

**Files:**
- Modify: `apps/firehub-ai-agent/src/routes/proactive.ts`

### Step 1: Template 인터페이스 확장

```typescript
interface Template {
  sections: TemplateSection[];
  output_format: string;
  style?: string;  // 추가
}
```

### Step 2: buildProactiveSystemPrompt 강화

기존 함수를 다음과 같이 교체:

```typescript
function buildProactiveSystemPrompt(template?: Template): string {
  let prompt =
    '당신은 프로액티브 AI 분석가입니다. 주어진 컨텍스트와 데이터를 분석하여 인사이트를 제공합니다.\n' +
    '응답은 반드시 한국어로 작성하세요.\n\n' +
    '필요한 데이터가 컨텍스트에 없으면 도구를 사용하여 직접 조회하세요.\n' +
    '데이터셋 데이터 조회: query_dataset_data, 데이터 스키마 조회: get_data_schema,\n' +
    '데이터셋 목록 조회: list_datasets, 데이터셋 상세 조회: get_dataset.\n\n';

  // 기본 내러티브 가이드 (항상 포함)
  prompt +=
    '## 분석 원칙\n' +
    '- 데이터 나열이 아닌 인사이트 중심으로 서술하세요.\n' +
    '- "왜 이 수치가 변했는가"를 파악하고, 가능한 원인을 제시하세요.\n' +
    '- 컨텍스트에 previousExecutions(이전 실행 결과)가 있으면 비교하여 변화 추이를 언급하세요.\n' +
    '- 변화를 언급할 때는 절대값과 변화율(%)을 함께 제시하세요.\n' +
    '- 확신이 낮으면 "~로 보입니다", "확인이 필요합니다" 등으로 표현하세요.\n' +
    '- 권고사항은 "무엇을 해야 하는가"를 구체적으로 제시하세요.\n\n';

  if (template) {
    // 템플릿 스타일 (있으면)
    if (template.style) {
      prompt += `## 작성 스타일\n${template.style}\n\n`;
    }

    prompt += `출력 형식: ${template.output_format}\n\n`;
    prompt += '다음 섹션 구조에 따라 응답을 작성하세요. 각 섹션은 ## 헤더로 구분합니다:\n\n';

    for (const section of template.sections) {
      prompt += `## ${section.label}\n`;
      if (section.required !== false) {
        prompt += '(필수 섹션)\n';
      }

      // 섹션 타입별 가이드
      const typeGuide = getSectionTypeGuide(section.type);
      if (typeGuide) {
        prompt += typeGuide + '\n';
      }

      prompt += '\n';
    }
  }

  return prompt;
}

function getSectionTypeGuide(type?: string): string | null {
  switch (type) {
    case 'text':
      return '마크다운 서술. 핵심 발견(key finding)을 먼저 쓰고 근거를 뒤에 배치하세요.';
    case 'cards':
      return (
        '카드 형식으로 출력합니다. 텍스트 설명 후 반드시 다음과 같이 JSON 코드 블록을 포함하세요:\n' +
        '```json\n[{"title": "...", "value": "...", "description": "..."}]\n```\n' +
        '가능하면 이전 값 대비 변화를 description에 포함하세요.'
      );
    case 'list':
      return '중요도/심각도 순으로 정렬하세요. 각 항목에 맥락(왜 중요한지) 한 줄을 추가하세요.';
    case 'table':
      return '마크다운 테이블 형식. 비교 항목이 있으면 변화율 컬럼을 추가하세요.';
    case 'comparison':
      return '"이번 기간 vs 이전 기간: +N% (절대값)" 패턴으로 기간 비교를 작성하세요.';
    case 'alert':
      return '심각도 순(CRITICAL → WARNING → INFO)으로 정렬. 각 알림에 권장 조치를 포함하세요.';
    case 'timeline':
      return '시간순으로 나열. 각 이벤트에 영향도 설명을 한 줄 추가하세요.';
    case 'chart':
      return '차트/그래프에 대한 해석을 서술하세요. 추세, 이상값, 패턴을 자연어로 설명하세요.';
    case 'recommendation':
      return '구체적 액션 + 기대 효과 + 우선순위를 기술하세요. 실행 가능한 단계로 작성하세요.';
    default:
      return null;
  }
}
```

### Step 3: AI Agent 테스트

```bash
cd apps/firehub-ai-agent && pnpm test
```

Expected: PASS

### Step 4: 커밋

```bash
git add apps/firehub-ai-agent/src/routes/proactive.ts
git commit -m "feat(proactive): enhance system prompt with narrative guide and section type guides"
```

---

## Task 4: 프론트엔드 — 템플릿 스타일 입력란

ReportTemplateDetailPage에 스타일 입력란을 추가한다.

**Files:**
- Modify: `apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx`

### Step 1: structureJson에서 style 추출/주입

현재 `structureJson`은 `JSON.stringify(template.structure, null, 2)`로 관리된다. `style`은 structure 최상위 필드이므로:

- 읽기 모드: `template.structure.style`이 있으면 메타 정보에 표시
- 편집 모드: 이름/설명 아래에 "스타일" textarea 추가
- 저장 시: structureJson 파싱 → style 필드 설정 → 다시 직렬화

`ReportTemplateDetailPage.tsx`에서:

1. `styleText` 상태 추가:
```typescript
const [styleText, setStyleText] = useState('');
```

2. template 로드 시 style 동기화 (기존 useEffect 내):
```typescript
useEffect(() => {
  if (template) {
    setStructureJson(JSON.stringify(template.structure, null, 2));
    const style = (template.structure as Record<string, unknown>)?.style;
    setStyleText(typeof style === 'string' ? style : '');
  }
}, [template]);
```

3. handleSave에서 style을 structure에 반영:
```typescript
const handleSave = form.handleSubmit((values) => {
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(structureJson);
  } catch {
    toast.error('JSON 형식이 올바르지 않습니다.');
    return;
  }

  // style 반영
  if (styleText.trim()) {
    parsed.style = styleText.trim();
  } else {
    delete parsed.style;
  }

  const payload = { name: values.name, description: values.description || undefined, structure: parsed };
  // ... 기존 create/update 로직
});
```

4. 편집 모드 UI — 이름/설명 카드 내, 설명 필드 아래에 추가:
```tsx
<div className="col-span-2 space-y-2">
  <Label htmlFor="tpl-style">작성 스타일 (선택)</Label>
  <Textarea
    id="tpl-style"
    value={styleText}
    onChange={(e) => setStyleText(e.target.value)}
    placeholder="AI가 리포트를 작성할 때의 스타일을 기술하세요 (예: 경영진 보고서 스타일, 기술 분석 스타일 등)"
    rows={2}
  />
</div>
```

5. 읽기 모드 — 메타 정보에 스타일 표시 (description 옆):
```tsx
{!isNew && !isEditing && template && (
  <div className="text-sm text-muted-foreground flex flex-col gap-1">
    <div className="flex gap-4">
      {template.description && <span>{template.description}</span>}
      <span>생성: {new Date(template.createdAt).toLocaleDateString('ko-KR')}</span>
      <span>수정: {new Date(template.updatedAt).toLocaleDateString('ko-KR')}</span>
    </div>
    {(template.structure as Record<string, unknown>)?.style && (
      <div className="text-xs text-muted-foreground/70">
        스타일: {String((template.structure as Record<string, unknown>).style)}
      </div>
    )}
  </div>
)}
```

6. Textarea import 추가:
```typescript
import { Textarea } from '@/components/ui/textarea';
```

### Step 2: 타입체크 + 빌드

```bash
cd apps/firehub-web && pnpm typecheck && pnpm build
```

Expected: 0 errors, BUILD SUCCESS

### Step 3: 커밋

```bash
git add apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx
git commit -m "feat(proactive): add style input to template detail page"
```

---

## Task 5: 전체 빌드 검증

**Files:** 없음 (검증만)

### Step 1: 백엔드 전체 빌드 + 테스트

```bash
cd apps/firehub-api && ./gradlew build
```

Expected: BUILD SUCCESS

### Step 2: AI Agent 테스트

```bash
cd apps/firehub-ai-agent && pnpm test
```

Expected: PASS

### Step 3: 프론트엔드 타입체크 + 빌드 + 린트

```bash
cd apps/firehub-web && pnpm typecheck && pnpm build && pnpm lint
```

Expected: 모두 성공

### Step 4: Spotless

```bash
cd apps/firehub-api && ./gradlew spotlessApply
```
