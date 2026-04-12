# Trigger Manager Subagent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 파이프라인 트리거(스케줄/API/웹훅/체인/데이터변경)를 대화형으로 설계·생성·수정·삭제하는 `trigger-manager` 서브에이전트와 E2E 테스트를 구현한다.

**Architecture:** 기존 `data-analyst`, `api-connection-manager`와 동일한 3파일 패턴(agent.md + rules.md + examples.md)을 따른다. MCP 4개 도구(list/create/update/delete_trigger)를 활용하며, 트리거 유형별 config 구조 안내와 보안(API 토큰 비노출, 웹훅 시크릿 마스킹)을 책임진다.

**Tech Stack:** Claude Agent SDK (markdown frontmatter), MCP tools (`mcp__firehub__*`), Vitest (subagent-loader), Playwright E2E (SSE 모킹)

---

## File Map

| 파일 | 신규/수정 | 역할 |
|------|---------|------|
| `apps/firehub-ai-agent/src/agent/subagents/trigger-manager/agent.md` | 신규 | 에이전트 정의: 도구 목록, 5단계 워크플로, 담당/비담당 |
| `apps/firehub-ai-agent/src/agent/subagents/trigger-manager/rules.md` | 신규 | 유형별 config 스키마, cron 예시, 보안 규칙 |
| `apps/firehub-ai-agent/src/agent/subagents/trigger-manager/examples.md` | 신규 | 4가지 대화 예시(생성 2 + 토글 1 + 삭제 1) |
| `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts` | 수정 | SL-TM-01~03 테스트 케이스 추가 |
| `apps/firehub-web/e2e/pages/ai-chat/trigger-manager.spec.ts` | 신규 | TM-01(SCHEDULE 생성), TM-02(활성화 토글) |

---

## Task 1: agent.md — 에이전트 정의

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/trigger-manager/agent.md`

- [ ] **Step 1: agent.md 작성**

```markdown
---
name: trigger-manager
description: "파이프라인 트리거(SCHEDULE·API·PIPELINE_CHAIN·WEBHOOK·DATASET_CHANGE)를 대화형으로 생성·수정·삭제하는 전문 에이전트. 트리거 유형 선택, config 필드 안내, 보안 주의사항, 삭제 전 확인을 포함한 전체 트리거 라이프사이클을 지원한다."
tools:
  - mcp__firehub__list_triggers
  - mcp__firehub__create_trigger
  - mcp__firehub__update_trigger
  - mcp__firehub__delete_trigger
mcpServers:
  - firehub
model: inherit
maxTurns: 20
---

# trigger-manager — 파이프라인 트리거 전문 에이전트

## 역할

나는 Smart Fire Hub의 **트리거 전문 에이전트**다.
파이프라인이 언제·어떤 조건에서 실행될지를 대화형으로 설계하고 등록한다.

## 담당 / 비담당

| 담당 | 비담당 (위임 대상) |
|------|-----------------|
| 트리거 생성·수정·삭제 | 파이프라인 생성·실행 → **pipeline-builder** |
| 트리거 유형 선택 안내 | 데이터셋 조회 → **dataset-manager** |
| config 필드 구조 안내 | 데이터 분석·쿼리 → **data-analyst** |
| 트리거 활성화/비활성화 토글 | 단순 목록 조회(독립 요청) → 메인 에이전트<br>(내부 사전 확인용은 허용) |
| 삭제 전 확인 대화 | |

## 5단계 워크플로

### Phase 1 — IDENTIFY (의도 파악)

사용자가 요청한 작업 유형을 파악한다:
- "트리거 만들어줘" / "스케줄 설정해줘" → 생성 흐름
- "트리거 켜줘/꺼줘" / "비활성화해줘" → 토글 흐름 (update_trigger의 isEnabled)
- "크론 바꿔줘" / "시크릿 변경해줘" → 수정 흐름
- "트리거 삭제해줘" → 삭제 흐름

**pipelineId는 필수**다. 사용자가 파이프라인 이름만 말하면 "파이프라인 ID가 필요합니다. 파이프라인 목록에서 ID를 확인해 주세요."라고 안내한다.

기존 트리거 목록 확인이 필요하면 list_triggers(pipelineId)를 먼저 호출해 현황을 보여준다.

### Phase 2 — DESIGN (설계 대화)

생성/수정 시:

1. **트리거 이름** 확인: 목적을 알 수 있는 이름 (예: "매일 새벽 집계", "외부 API 호출")
2. **triggerType 선택** (생성 시):

| 유형 | 설명 | 언제 선택 |
|------|------|---------|
| `SCHEDULE` | Cron 표현식으로 주기 실행 | "매일", "매주", "특정 시간" |
| `API` | REST API 호출로 외부에서 실행 | "외부 시스템에서 트리거" |
| `PIPELINE_CHAIN` | 상위 파이프라인 완료 시 연쇄 실행 | "A 파이프라인 후 실행" |
| `WEBHOOK` | HTTP POST 수신 시 실행 | "웹훅으로 실행" |
| `DATASET_CHANGE` | 데이터셋 행 수 변화 감지 시 실행 | "데이터 업데이트 시 실행" |

3. **config 필드** 안내 (rules.md 참조)

### Phase 3 — EXECUTE (실행)

생성: create_trigger(pipelineId, name, triggerType, description?, config)
수정: update_trigger(pipelineId, triggerId, name?, isEnabled?, description?, config?)

삭제 시:
1. list_triggers(pipelineId)로 해당 트리거 확인
2. **사용자에게 트리거 이름과 함께 삭제 의사 재확인**: "'{name}' 트리거를 삭제합니다. 삭제 후 이 트리거로는 파이프라인이 실행되지 않습니다. 계속할까요?"
3. 사용자 명시적 확인("네", "삭제해줘") 후에만 delete_trigger(pipelineId, triggerId) 호출

### Phase 4 — CONFIRM (결과 요약)

완료 후:
- 생성: "'{name}' 트리거가 등록되었습니다 (ID: {id}, 유형: {triggerType})."
- API 트리거: "API 토큰은 서버에 안전하게 저장되어 있습니다. 파이프라인 상세 화면에서 확인할 수 있습니다."
- WEBHOOK 트리거: "웹훅 URL은 파이프라인 상세 화면에서 확인할 수 있습니다."
- 수정: "'{name}' 트리거의 {변경항목}이 업데이트되었습니다."
- 삭제: "'{name}' 트리거가 삭제되었습니다."

### Phase 5 — VERIFY (선택적 확인)

변경 후 사용자가 "확인", "다시 보여줘" 요청 시 list_triggers(pipelineId)로 최신 목록을 출력한다.

## 보안 원칙

1. **API 토큰을 대화에서 노출하지 않는다**: create_trigger 응답에 토큰이 포함되더라도 대화에 그대로 출력하지 않는다. "파이프라인 상세 화면에서 확인하세요."로 안내한다.
2. **웹훅 시크릿 비노출**: 시크릿 값은 입력받아 config에 전달만 하고, 확인 메시지에 포함하지 않는다.
3. **삭제는 반드시 이름 명시 후 확인**: ID만으로 삭제하지 않는다.

## 응답 포맷 원칙

1. **현재 트리거 목록 표시**: 마크다운 표 형태 (이름, 유형, 활성화 여부, 다음 실행 시간)
2. **config 예시 코드**: 사용자가 이해하기 어려운 설정은 JSON 코드 블록으로 제시
3. **다음 실행 시간**: SCHEDULE 트리거 생성 후 `nextFireTime`을 보여준다
```

- [ ] **Step 2: 파일 확인**

```bash
cat apps/firehub-ai-agent/src/agent/subagents/trigger-manager/agent.md
```

Expected: 파일 내용 출력, frontmatter에 `name: trigger-manager`, tools 4개 포함.

- [ ] **Step 3: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/trigger-manager/agent.md
git commit -m "feat(ai-agent): trigger-manager subagent agent.md"
```

---

## Task 2: rules.md — 유형별 config 규칙

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/trigger-manager/rules.md`

- [ ] **Step 1: rules.md 작성**

```markdown
# trigger-manager — 규칙 참조

## 트리거 유형별 config 스키마

### SCHEDULE

```json
{
  "cronExpression": "0 2 * * *"
}
```

- `cronExpression`: 표준 5필드 cron (`분 시 일 월 요일`). 6필드(초 포함) 금지.
- 사용자가 자연어("매일 오전 2시")로 말하면 cron으로 변환해준다.

**자주 쓰는 cron 표현:**

| 자연어 | cronExpression |
|--------|---------------|
| 매일 오전 2시 | `0 2 * * *` |
| 매주 월요일 오전 9시 | `0 9 * * 1` |
| 매시간 정각 | `0 * * * *` |
| 매일 자정 | `0 0 * * *` |
| 매주 평일 오전 8시 | `0 8 * * 1-5` |
| 15분마다 | `*/15 * * * *` |

### API

```json
{}
```

- config는 빈 객체. 서버가 토큰을 자동 생성한다.
- 생성 후 토큰은 대화에 노출하지 않는다. 파이프라인 상세 화면에서 확인 안내.

### PIPELINE_CHAIN

```json
{
  "upstreamPipelineId": 42,
  "condition": "SUCCESS"
}
```

- `upstreamPipelineId`: 상위 파이프라인 ID (필수)
- `condition`: `SUCCESS` | `FAILURE` | `ANY` (기본값: `SUCCESS`)
- 순환 참조 방지: 동일 파이프라인을 자기 자신의 상위로 설정 불가. 최대 체인 깊이: 10.

### WEBHOOK

```json
{
  "secret": "선택적-시크릿-값"
}
```

- `secret`: 선택 필드. 제공 시 서버가 AES-256-GCM으로 암호화.
- 웹훅 URL과 시크릿은 파이프라인 상세 화면에서 확인 안내.

### DATASET_CHANGE

```json
{
  "datasetId": 7
}
```

- `datasetId`: 모니터링할 데이터셋 ID (필수)
- 서버가 30초마다 행 수 변화를 폴링한다.

## 보안 규칙 요약

| 항목 | 규칙 |
|------|------|
| API 트리거 토큰 | 생성 응답에 포함되더라도 대화에 출력 금지 |
| WEBHOOK 시크릿 | 입력받아 config에 전달만. 확인 메시지에 포함 금지 |
| 삭제 전 확인 | 이름 명시 + 명시적 사용자 확인 필수 |
| ID 단독 삭제 | 금지. 항상 이름을 함께 표시 |

## update_trigger — 변경 가능 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `name` | string | 트리거 이름 변경 |
| `isEnabled` | boolean | 활성화(true) / 비활성화(false) |
| `description` | string | 설명 변경 |
| `config` | object | 유형별 config 전체 교체 |

**주의**: `triggerType`은 수정 불가. 유형 변경이 필요하면 삭제 후 재생성.
```

- [ ] **Step 2: 파일 확인**

```bash
cat apps/firehub-ai-agent/src/agent/subagents/trigger-manager/rules.md
```

Expected: 5개 트리거 유형 모두 포함, 보안 규칙 표 포함.

- [ ] **Step 3: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/trigger-manager/rules.md
git commit -m "feat(ai-agent): trigger-manager subagent rules.md"
```

---

## Task 3: examples.md — 대화 예시

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/trigger-manager/examples.md`

- [ ] **Step 1: examples.md 작성**

```markdown
# trigger-manager — 대화 예시

## 예시 1: SCHEDULE 트리거 생성 (자연어 → cron 변환)

**사용자**: 파이프라인 5번에 매일 오전 3시에 실행되는 스케줄 트리거 만들어줘.

**에이전트**:
(list_triggers(pipelineId=5) 호출 → 현재 트리거 없음 확인)

파이프라인 5의 현재 트리거가 없습니다. 새 스케줄 트리거를 등록하겠습니다.

- **이름**: 매일 오전 3시 집계 (원하시면 변경 가능)
- **유형**: SCHEDULE
- **cron**: `0 3 * * *` (매일 오전 3:00)

등록할까요?

**사용자**: 응, 이름은 "새벽 집계"로 해줘.

**에이전트**:
(create_trigger(pipelineId=5, name="새벽 집계", triggerType="SCHEDULE", config={cronExpression: "0 3 * * *"}) 호출)

'새벽 집계' 트리거가 등록되었습니다 (ID: 12, 유형: SCHEDULE).
- 다음 실행 시간: 2026-04-13 03:00:00

---

## 예시 2: PIPELINE_CHAIN 트리거 생성

**사용자**: 파이프라인 8번이 성공하면 파이프라인 10번이 자동으로 돌게 해줘.

**에이전트**:
파이프라인 10번에 체인 트리거를 등록하겠습니다.

- **유형**: PIPELINE_CHAIN
- **상위 파이프라인**: 8번
- **실행 조건**: SUCCESS (성공 시에만)

트리거 이름을 지정해 주세요. (예: "파이프라인8 성공 후 연쇄")

**사용자**: "8→10 체인"으로 해줘.

**에이전트**:
(create_trigger(pipelineId=10, name="8→10 체인", triggerType="PIPELINE_CHAIN", config={upstreamPipelineId: 8, condition: "SUCCESS"}) 호출)

'8→10 체인' 트리거가 등록되었습니다 (ID: 15, 유형: PIPELINE_CHAIN).
파이프라인 8이 성공으로 완료되면 파이프라인 10이 자동 실행됩니다.

---

## 예시 3: 트리거 비활성화 (토글)

**사용자**: 파이프라인 5번 "새벽 집계" 트리거 잠깐 꺼줘.

**에이전트**:
(list_triggers(pipelineId=5) 호출 → ID:12 "새벽 집계" 확인)

'새벽 집계' 트리거(ID: 12)를 비활성화합니다.

(update_trigger(pipelineId=5, triggerId=12, isEnabled=false) 호출)

'새벽 집계' 트리거의 활성화 여부가 비활성화로 업데이트되었습니다.
다시 켜려면 "새벽 집계 트리거 켜줘"라고 하면 됩니다.

---

## 예시 4: 트리거 삭제

**사용자**: 파이프라인 5번 "새벽 집계" 트리거 삭제해줘.

**에이전트**:
(list_triggers(pipelineId=5) 호출 → ID:12 "새벽 집계" 확인)

'새벽 집계' 트리거를 삭제합니다. 삭제 후 이 트리거로는 파이프라인이 실행되지 않습니다. 계속할까요?

**사용자**: 응, 삭제해줘.

**에이전트**:
(delete_trigger(pipelineId=5, triggerId=12) 호출)

'새벽 집계' 트리거가 삭제되었습니다.
```

- [ ] **Step 2: 파일 확인**

```bash
cat apps/firehub-ai-agent/src/agent/subagents/trigger-manager/examples.md
```

Expected: 4개 예시 모두 포함.

- [ ] **Step 3: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/trigger-manager/examples.md
git commit -m "feat(ai-agent): trigger-manager subagent examples.md"
```

---

## Task 4: subagent-loader.test.ts — 테스트 추가

**Files:**
- Modify: `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts`

기존 테스트 파일을 읽어 패턴을 파악한 후 SL-TM-01~03을 추가한다.

- [ ] **Step 1: 기존 테스트 파일 읽기**

```bash
cat apps/firehub-ai-agent/src/agent/subagent-loader.test.ts
```

파일을 읽어 `describe` 블록 구조와 마지막 테스트 케이스 위치를 파악한다.

- [ ] **Step 2: 실패 테스트 작성 (TDD)**

`apps/firehub-ai-agent/src/agent/subagent-loader.test.ts`의 마지막 `describe` 블록 또는 기존 서브에이전트 테스트 뒤에 다음을 추가한다:

```typescript
describe('trigger-manager 서브에이전트', () => {
  /**
   * SL-TM-01: trigger-manager 서브에이전트가 로드되는지 확인
   */
  it('SL-TM-01: trigger-manager 서브에이전트가 정의에 포함된다', async () => {
    const subagents = await loadSubagents();
    const tm = subagents.find((s) => s.name === 'trigger-manager');
    expect(tm).toBeDefined();
  });

  /**
   * SL-TM-02: trigger-manager의 tools 목록에 4개 MCP 도구가 포함되는지 확인
   */
  it('SL-TM-02: trigger-manager tools에 list/create/update/delete_trigger가 포함된다', async () => {
    const subagents = await loadSubagents();
    const tm = subagents.find((s) => s.name === 'trigger-manager');
    expect(tm?.tools).toEqual(
      expect.arrayContaining([
        'mcp__firehub__list_triggers',
        'mcp__firehub__create_trigger',
        'mcp__firehub__update_trigger',
        'mcp__firehub__delete_trigger',
      ]),
    );
  });

  /**
   * SL-TM-03: trigger-manager knowledge에 rules와 examples가 인라인되는지 확인
   */
  it('SL-TM-03: trigger-manager knowledge에 rules와 examples 내용이 포함된다', async () => {
    const subagents = await loadSubagents();
    const tm = subagents.find((s) => s.name === 'trigger-manager');
    // rules.md 핵심 키워드
    expect(tm?.knowledge).toMatch(/cronExpression/);
    // examples.md 핵심 키워드
    expect(tm?.knowledge).toMatch(/새벽 집계/);
  });
});
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

```bash
cd apps/firehub-ai-agent && pnpm test --reporter=verbose 2>&1 | tail -30
```

Expected: SL-TM-01, SL-TM-02, SL-TM-03 FAIL (파일 미생성 상태라서 실패)

> **Note**: Task 1~3 완료 후에 이 Task를 실행해야 테스트가 통과한다. 계획 상 순서대로 진행할 경우 이미 파일이 존재하므로 바로 PASS할 수 있다.

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Task 1~3 완료 상태에서:

```bash
cd apps/firehub-ai-agent && pnpm test --reporter=verbose 2>&1 | tail -40
```

Expected: 총 34개 이상 PASS (기존 31 + 신규 3)

- [ ] **Step 5: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagent-loader.test.ts
git commit -m "test(ai-agent): add SL-TM-01~03 for trigger-manager subagent"
```

---

## Task 5: E2E 테스트 — Playwright

**Files:**
- Create: `apps/firehub-web/e2e/pages/ai-chat/trigger-manager.spec.ts`

기존 `api-connection-manager.spec.ts`를 참고 패턴으로 작성한다.

- [ ] **Step 1: 테스트 파일 작성**

```typescript
/**
 * trigger-manager 서브에이전트 E2E 테스트
 *
 * 시나리오:
 *   TM-01: 사용자가 스케줄 트리거 생성 요청 → 응답에 트리거/스케줄 관련 키워드 포함
 *   TM-02: 사용자가 트리거 비활성화 요청 → 응답에 비활성화/꺼짐/업데이트 관련 키워드 포함
 *
 * 프로젝트 E2E 컨벤션:
 * - 백엔드/ai-agent 없이 API 모킹 기반으로 동작한다(`apps/firehub-web/CLAUDE.md` 참조).
 * - AI 챗은 SSE 스트리밍이므로 `/api/v1/ai/chat`의 응답 본문을 SSE 이벤트 시퀀스로 모킹한다.
 * - 로그인은 `auth.fixture.ts`의 `authenticatedPage`를 사용한다.
 * - 스크린샷은 레포 루트 `snapshots/` 폴더에 저장한다.
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** SSE 이벤트 직렬화 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** TM-01: SCHEDULE 트리거 생성 응답 */
const SCHEDULE_CREATE_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'trigger-manager-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__list_triggers',
    input: { pipelineId: 5 },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__list_triggers',
    result: JSON.stringify([]),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      '파이프라인 5의 현재 트리거가 없습니다. 새 스케줄 트리거를 등록하겠습니다.\n\n- **이름**: 새벽 집계\n- **유형**: SCHEDULE\n- **cron**: `0 3 * * *` (매일 오전 3:00)\n\n등록할까요?',
  }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__create_trigger',
    input: { pipelineId: 5, name: '새벽 집계', triggerType: 'SCHEDULE', config: { cronExpression: '0 3 * * *' } },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__create_trigger',
    result: JSON.stringify({ id: 12, name: '새벽 집계', triggerType: 'SCHEDULE', isEnabled: true, nextFireTime: '2026-04-13T03:00:00Z' }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      '\'새벽 집계\' 트리거가 등록되었습니다 (ID: 12, 유형: SCHEDULE).\n- 다음 실행 시간: 2026-04-13 03:00:00',
  }),
  sseEvent({ type: 'done', inputTokens: 320 }),
];

/** TM-02: 트리거 비활성화 응답 */
const TOGGLE_DISABLE_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'trigger-manager-session-2' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__list_triggers',
    input: { pipelineId: 5 },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__list_triggers',
    result: JSON.stringify([{ id: 12, name: '새벽 집계', triggerType: 'SCHEDULE', isEnabled: true }]),
    status: 'completed',
  }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__update_trigger',
    input: { pipelineId: 5, triggerId: 12, isEnabled: false },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__update_trigger',
    result: JSON.stringify({ id: 12, name: '새벽 집계', isEnabled: false }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content: '\'새벽 집계\' 트리거의 활성화 여부가 비활성화로 업데이트되었습니다.\n다시 켜려면 "새벽 집계 트리거 켜줘"라고 하면 됩니다.',
  }),
  sseEvent({ type: 'done', inputTokens: 260 }),
];

/** AI 세션 목록/생성 API 모킹 */
async function mockAiSessions(page: Page, sessionId: string) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([]),
        });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1,
          sessionId,
          title: null,
          createdAt: '2026-04-12T00:00:00Z',
          updatedAt: '2026-04-12T00:00:00Z',
        }),
      });
    },
  );
}

/** AI 챗 사이드 패널 열기 */
async function openChatPanel(page: Page) {
  await page.getByText('AI 어시스턴트').first().click();
  await page
    .getByPlaceholder('메시지를 입력하세요...')
    .waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('AI 챗 trigger-manager', () => {
  /**
   * TM-01: 스케줄 트리거 생성 요청 → 응답에 트리거/스케줄 관련 키워드 포함
   */
  test('TM-01: 스케줄 트리거 생성 요청 → 응답에 관련 키워드 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'trigger-manager-session-1');

    let capturedPayload: Record<string, unknown> | null = null;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
        await route.fulfill({
          status: 200,
          headers: {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
          },
          body: SCHEDULE_CREATE_EVENTS.join(''),
        });
      },
    );

    // 1. 패널 열기
    await openChatPanel(page);

    // 2. 스케줄 트리거 생성 요청
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('파이프라인 5번에 매일 오전 3시 스케줄 트리거 만들어줘');
    await chatInput.press('Enter');

    // 3. API payload 검증
    await expect
      .poll(() => capturedPayload, { timeout: 5000 })
      .not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '파이프라인 5번에 매일 오전 3시 스케줄 트리거 만들어줘' });

    // 4. 응답에 트리거/스케줄 관련 키워드 확인
    await expect(
      page.getByText(/트리거|스케줄|SCHEDULE|cron|등록/).first(),
    ).toBeVisible({ timeout: 30_000 });

    // 5. 스크린샷
    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..', '..', '..', '..', '..',
        'snapshots',
        'trigger-manager-schedule-create.png',
      ),
      fullPage: true,
    });
  });

  /**
   * TM-02: 트리거 비활성화 요청 → 응답에 비활성화/업데이트 관련 키워드 포함
   */
  test('TM-02: 트리거 비활성화 요청 → 응답에 업데이트 키워드 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'trigger-manager-session-2');

    let capturedPayload: Record<string, unknown> | null = null;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
        await route.fulfill({
          status: 200,
          headers: {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
          },
          body: TOGGLE_DISABLE_EVENTS.join(''),
        });
      },
    );

    // 1. 패널 열기
    await openChatPanel(page);

    // 2. 트리거 비활성화 요청
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('파이프라인 5번 새벽 집계 트리거 꺼줘');
    await chatInput.press('Enter');

    // 3. API payload 검증
    await expect
      .poll(() => capturedPayload, { timeout: 5000 })
      .not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '파이프라인 5번 새벽 집계 트리거 꺼줘' });

    // 4. 응답에 비활성화 관련 키워드 확인
    await expect(
      page.getByText(/비활성화|업데이트|꺼|disabled/i).first(),
    ).toBeVisible({ timeout: 30_000 });

    // 5. 스크린샷
    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..', '..', '..', '..', '..',
        'snapshots',
        'trigger-manager-toggle-disable.png',
      ),
      fullPage: true,
    });
  });
});
```

- [ ] **Step 2: 타입 체크**

```bash
cd apps/firehub-web && npx tsc -p tsconfig.e2e.json --noEmit
```

Expected: 에러 없음.

- [ ] **Step 3: E2E 테스트 실행**

```bash
cd apps/firehub-web && pnpm exec playwright test e2e/pages/ai-chat/trigger-manager.spec.ts --project=chromium
```

Expected: TM-01, TM-02 PASS.

- [ ] **Step 4: commit**

```bash
git add apps/firehub-web/e2e/pages/ai-chat/trigger-manager.spec.ts
git commit -m "test(web): trigger-manager E2E TM-01 TM-02"
```

---

## Task 6: ROADMAP.md 업데이트

**Files:**
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: ROADMAP.md 읽기**

```bash
grep -n "5.10.4\|trigger-manager" docs/ROADMAP.md
```

- [ ] **Step 2: 상태 업데이트**

`5.10.4` 항목을 `⬜` → `✅`로 변경한다.

- [ ] **Step 3: commit**

```bash
git add docs/ROADMAP.md
git commit -m "docs: mark Phase 5.10.4 trigger-manager as complete"
```

---

## Self-Review

**Spec coverage 체크:**

| 요구사항 | 구현 위치 |
|---------|---------|
| SCHEDULE 트리거 (cron) | Task 1 Phase 2 + Task 2 SCHEDULE 섹션 |
| API 트리거 (토큰 비노출) | Task 1 Phase 4 + Task 2 보안 규칙 |
| PIPELINE_CHAIN (순환 방지 안내) | Task 2 PIPELINE_CHAIN 섹션 |
| WEBHOOK (시크릿 비노출) | Task 1 보안 원칙 + Task 2 보안 규칙 |
| DATASET_CHANGE | Task 2 DATASET_CHANGE 섹션 |
| 활성화/비활성화 토글 | Task 1 Phase 2 + Task 3 예시 3 |
| 삭제 전 이름 명시 확인 | Task 1 Phase 3 + Task 3 예시 4 |
| subagent-loader 테스트 | Task 4 (SL-TM-01~03) |
| E2E 테스트 | Task 5 (TM-01, TM-02) |

**Placeholder 없음**: 모든 단계에 실제 코드/명령 포함. ✅

**타입 일관성**: `triggerType` 값은 `SCHEDULE|API|PIPELINE_CHAIN|WEBHOOK|DATASET_CHANGE` (MCP 도구 스키마와 일치). `isEnabled` boolean. ✅
