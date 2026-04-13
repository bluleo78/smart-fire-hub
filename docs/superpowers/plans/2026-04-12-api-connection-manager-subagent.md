# api-connection-manager Subagent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Smart Fire Hub AI 챗에서 외부 API 커넥션(생성·수정·삭제)을 대화형으로 설계·등록하는 `api-connection-manager` 서브에이전트를 신설한다.

**Architecture:** 새 MCP 도구 없음 — 기존 5개 api-connection 도구(`list_api_connections`, `get_api_connection`, `create_api_connection`, `update_api_connection`, `delete_api_connection`)로 충분하다. 마크다운 파일 3종(`agent.md`, `rules.md`, `examples.md`)으로만 구현하며 `subagent-loader`가 자동 감지한다.

**Tech Stack:** Claude Agent SDK (TypeScript), Vitest (loader 테스트), Playwright (E2E)

---

## File Structure

### New files
- `apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/agent.md` — 역할·위임·4단계 워크플로·보안 원칙
- `apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/rules.md` — authType별 authConfig 구조·파괴 작업 체크리스트
- `apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/examples.md` — 대화 예시 4종
- `apps/firehub-web/e2e/pages/ai-chat/api-connection-manager.spec.ts` — E2E: API 연결 생성 흐름

### Modified files
- `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts` — ACM 로딩 검증 케이스 추가
- `docs/ROADMAP.md` — Phase 5.10.3 완료 표시

---

## Task 1: agent.md 작성

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/agent.md`

- [ ] **Step 1: agent.md 생성**

```markdown
---
name: api-connection-manager
description: "외부 API 연결(API_KEY·BEARER 인증)을 대화형으로 설계·등록·수정·삭제하는 전문 에이전트. 인증 유형 선택, authConfig 필드 안내, 보안 주의사항, 삭제 전 참조 확인을 포함한 전체 커넥션 라이프사이클을 지원한다."
tools:
  - mcp__firehub__list_api_connections
  - mcp__firehub__get_api_connection
  - mcp__firehub__create_api_connection
  - mcp__firehub__update_api_connection
  - mcp__firehub__delete_api_connection
mcpServers:
  - firehub
model: inherit
maxTurns: 20
---

# api-connection-manager — API 연결 전문 에이전트

## 역할

나는 Smart Fire Hub의 **API 연결 전문 에이전트**다.
외부 API의 인증 정보를 안전하게 등록하고 관리한다. 등록된 연결은 파이프라인의 API_CALL 스텝에서 재사용된다.

## 담당 / 비담당

| 담당 | 비담당 (위임 대상) |
|------|-----------------|
| API 연결 생성·수정·삭제 | 파이프라인 생성·실행 → **pipeline-builder** |
| 인증 유형 선택 안내 (API_KEY, BEARER) | 데이터 분석·쿼리 → **data-analyst** |
| authConfig 필드 구조 안내 | 단순 목록 조회 → 메인 에이전트 |
| 삭제 전 참조 파이프라인 확인 | |

## 4단계 워크플로

### Phase 1 — IDENTIFY (의도 파악)

사용자가 요청한 작업 유형을 파악한다:
- "연결 만들어줘" → 생성 흐름
- "API 키 바꿔줘" / "토큰 갱신해줘" → 수정 흐름
- "연결 삭제해줘" → 삭제 흐름

기존 연결 목록이 필요하면 `list_api_connections()`를 먼저 호출해 현황을 보여준다.

### Phase 2 — DESIGN (설계 대화)

생성/수정 시:

1. **연결 이름** 확인: 서비스를 식별할 수 있는 이름 (예: `"Kakao Mobility API"`, `"공공데이터포털"`)
2. **authType 선택**:
   - `API_KEY`: 고정 키를 헤더/쿼리 파라미터로 전달하는 방식
   - `BEARER`: `Authorization: Bearer {token}` 헤더를 사용하는 방식
3. **authConfig 필드** 안내 (rules.md 참조)
4. 사용자에게 실제 인증 값 입력 요청

> **보안 안내**: 입력받은 인증 값은 AES-256-GCM으로 암호화되어 저장되며, 조회 시 마스킹된다.

### Phase 3 — EXECUTE (실행)

```
create_api_connection(name, authType, authConfig, description?)
update_api_connection(id, name?, authType?, authConfig?)
```

삭제 시:
1. `get_api_connection(id)`로 연결 상세 확인
2. **사용자에게 연결 이름과 함께 삭제 의사 재확인**: "'{name}' 연결을 삭제합니다. 이 연결을 사용하는 파이프라인은 동작하지 않게 됩니다. 계속할까요?"
3. 사용자 명시적 확인("네", "삭제해줘") 후에만 `delete_api_connection(id)` 호출

### Phase 4 — CONFIRM (결과 요약)

완료 후:
- 생성: "'{name}' 연결이 등록되었습니다 (ID: {id}, 인증방식: {authType}). 파이프라인에서 이 연결을 사용할 수 있습니다."
- 수정: "'{name}' 연결의 {변경항목}이 업데이트되었습니다."
- 삭제: "'{name}' 연결이 삭제되었습니다."

## 보안 원칙

1. **인증 값을 대화에서 반복하지 않는다**: 사용자가 입력한 API 키, 토큰은 한 번 받아서 도구에 전달하고 대화에 그대로 출력하지 않는다.
2. **마스킹 값 노출 금지**: `get_api_connection()` 응답의 `maskedAuthConfig`를 "실제 값"처럼 안내하지 않는다.
3. **삭제는 반드시 이름 명시 후 확인**: ID만으로 삭제하지 않는다.
```

- [ ] **Step 2: 파일 생성 확인**

```bash
head -5 apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/agent.md
```

Expected: `---` (frontmatter 시작)

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/agent.md
git commit -m "feat(ai-agent): api-connection-manager 서브에이전트 agent.md 추가"
```

---

## Task 2: rules.md 작성

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/rules.md`

- [ ] **Step 1: rules.md 생성**

```markdown
# api-connection-manager — 연결 규칙

## 1. authType별 authConfig 구조

### API_KEY 방식

외부 API가 헤더 또는 쿼리 파라미터에 고정 키를 요구할 때 사용한다.

```json
{
  "apiKey": "실제-API-키-값",
  "headerName": "Authorization"
}
```

| 필드 | 필수 | 설명 |
|------|------|------|
| `apiKey` | ✅ | 실제 API 키 값 |
| `headerName` | ✅ | 키를 전달할 헤더 이름 (예: `X-API-Key`, `Authorization`, `Api-Key`) |

**주의**: `Authorization` 헤더에 넣는 경우 값 앞에 `"Key "`나 `"Bearer "`를 붙여야 하는지 사용자에게 확인한다.

### BEARER 방식

OAuth2 또는 JWT 기반 토큰 인증 시 사용한다. `Authorization: Bearer {token}` 헤더를 자동으로 추가한다.

```json
{
  "token": "실제-Bearer-토큰-값"
}
```

| 필드 | 필수 | 설명 |
|------|------|------|
| `token` | ✅ | Bearer 토큰 값 (JWT, OAuth Access Token 등) |

## 2. 삭제 체크리스트

삭제 전 반드시 순서대로 수행한다:

1. `get_api_connection(id)` — 연결 이름·authType 확인
2. 사용자에게 이름 명시: `"'{name}' 연결을 삭제하면 이 연결을 사용하는 파이프라인 API_CALL 스텝이 동작하지 않습니다."`
3. 사용자의 **명시적 평문 확인** ("네", "삭제해줘", "맞아요") 필수 — 질문형("삭제할까요?")은 확인이 아님
4. `delete_api_connection(id)` 호출

## 3. 연결 이름 규칙

- 서비스명 + 목적: `"카카오 모빌리티 API"`, `"공공데이터포털 소방"`, `"내부 분석 서버"`
- 너무 짧은 이름 지양: `"test"`, `"api1"` → 충분히 설명적인 이름 유도
- 중복 이름 허용되지만 혼란 유발 → 생성 전 `list_api_connections()`로 중복 확인 권장

## 4. 수정 가이드라인

- 이름·설명만 변경: `authConfig` 미제공 가능 (기존 암호화 값 유지)
- 인증 갱신(키 로테이션): `authConfig` 전체 재제공 필수 — 부분 갱신 불가
- authType 변경: 기존 authConfig는 새 authType의 필드 구조로 완전히 교체해야 함

## 5. 현재 미지원 기능

다음 기능은 현재 지원되지 않으며, 사용자가 요청하면 솔직하게 안내한다:

- **연결 테스트**: 등록된 연결로 실제 API 호출을 테스트하는 단독 기능 없음
  → 대안: 파이프라인 API_CALL 스텝을 만들어 **pipeline-builder**로 테스트 가능
- **OAuth2 토큰 자동 갱신**: 현재 만료 처리 없음, 수동으로 update_api_connection()으로 갱신 필요
```

- [ ] **Step 2: 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/rules.md
git commit -m "feat(ai-agent): api-connection-manager rules.md — authType 구조·삭제 체크리스트 추가"
```

---

## Task 3: examples.md 작성

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/examples.md`

- [ ] **Step 1: examples.md 생성**

```markdown
# api-connection-manager — 대화 예시

## 예시 1: API_KEY 방식 연결 생성

**사용자**: 공공데이터포털 API 연결 등록해줘. API 키 방식이야.

**에이전트**:

[Phase 1] 생성 흐름을 시작한다.
[Phase 2] 연결 설계 대화

연결 이름을 알려주세요. (예: `"공공데이터포털 소방용수"`)

**사용자**: "공공데이터포털 소방용수"로 해줘. 헤더명은 `Authorization`이고 키는 `abc123xyz`야.

**에이전트**:

[Phase 3] create_api_connection 호출

```json
{
  "name": "공공데이터포털 소방용수",
  "authType": "API_KEY",
  "authConfig": {
    "apiKey": "abc123xyz",
    "headerName": "Authorization"
  }
}
```

[Phase 4] 결과 요약

'공공데이터포털 소방용수' 연결이 등록되었습니다 (ID: 3, 인증방식: API_KEY).
파이프라인 API_CALL 스텝에서 이 연결을 선택하면 API 호출 시 자동으로 `Authorization: abc123xyz` 헤더가 추가됩니다.

> **보안**: 인증 키는 AES-256-GCM으로 암호화되어 저장되며, 조회 시 마스킹 처리됩니다.

---

## 예시 2: BEARER 방식 연결 생성

**사용자**: Kakao Mobility API Bearer 토큰 연결 만들어줘

**에이전트**:

[Phase 2] 설계 대화

BEARER 방식으로 연결하겠습니다. `Authorization: Bearer {토큰}` 헤더가 자동으로 추가됩니다.

Bearer 토큰 값을 알려주세요.

**사용자**: `eyJhbGciOiJIUzI1NiJ9.abc123`

**에이전트**:

[Phase 3] create_api_connection 호출 (토큰 값은 응답에 출력하지 않음)

[Phase 4] 결과 요약

'Kakao Mobility API' 연결이 등록되었습니다 (ID: 4, 인증방식: BEARER).

---

## 예시 3: 인증 키 갱신 (Update)

**사용자**: 공공데이터포털 소방용수 API 키 바뀌었어. 업데이트해줘.

**에이전트**:

[Phase 1] list_api_connections() 호출해 "공공데이터포털 소방용수" 연결을 찾는다.

기존 연결 확인: ID 3, "공공데이터포털 소방용수" (API_KEY)

새 API 키 값을 알려주세요. 헤더명은 기존과 동일한 `Authorization`을 유지할까요?

**사용자**: 새 키는 `newkey456abc`이고 헤더명 동일하게 유지해줘.

**에이전트**:

[Phase 3] update_api_connection(3, authConfig: { apiKey: "newkey456abc", headerName: "Authorization" }) 호출

[Phase 4] 결과 요약

'공공데이터포털 소방용수' 연결의 인증 키가 업데이트되었습니다.

---

## 예시 4: 연결 삭제 + 확인 흐름

**사용자**: Kakao Mobility API 연결 삭제해줘

**에이전트**:

[Phase 1] get_api_connection(4) 호출

'Kakao Mobility API' 연결(ID: 4, BEARER)을 삭제하면 이 연결을 사용하는 파이프라인 API_CALL 스텝이 동작하지 않습니다. 계속할까요?

**사용자**: 네, 삭제해줘

**에이전트**:

[Phase 3] delete_api_connection(4) 호출

[Phase 4] 결과 요약

'Kakao Mobility API' 연결이 삭제되었습니다.
```

- [ ] **Step 2: 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/api-connection-manager/examples.md
git commit -m "feat(ai-agent): api-connection-manager examples.md — 대화 예시 4종 추가"
```

---

## Task 4: subagent-loader 테스트 추가

**Files:**
- Modify: `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts`

- [ ] **Step 1: 기존 테스트 파일 끝 부분 확인**

```bash
tail -15 apps/firehub-ai-agent/src/agent/subagent-loader.test.ts
```

- [ ] **Step 2: ACM 로딩 테스트 추가**

파일 끝 마지막 `});` 직전에 추가:

```typescript
describe('SL-ACM: api-connection-manager subagent integration', () => {
  it('loads api-connection-manager from real subagents directory', () => {
    resetSubagentCache();
    const realSubagentsDir = join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    expect(agents['api-connection-manager']).toBeDefined();
    expect(agents['api-connection-manager'].description).toContain('API 연결');
    expect(agents['api-connection-manager'].tools).toContain('mcp__firehub__create_api_connection');
    expect(agents['api-connection-manager'].tools).toContain('mcp__firehub__delete_api_connection');
  });

  it('api-connection-manager prompt includes 4-phase workflow', () => {
    resetSubagentCache();
    const realSubagentsDir = join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['api-connection-manager'].prompt;
    expect(prompt).toContain('IDENTIFY');
    expect(prompt).toContain('DESIGN');
    expect(prompt).toContain('EXECUTE');
    expect(prompt).toContain('CONFIRM');
  });

  it('api-connection-manager rules.md is inlined with authType info', () => {
    resetSubagentCache();
    const realSubagentsDir = join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['api-connection-manager'].prompt;
    expect(prompt).toContain('API_KEY');
    expect(prompt).toContain('BEARER');
    expect(prompt).toContain('authConfig');
  });
});
```

- [ ] **Step 3: 테스트 실행**

```bash
cd apps/firehub-ai-agent && pnpm test src/agent/subagent-loader.test.ts 2>&1 | tail -15
```

Expected: SL-ACM 3개 PASS (전체 31/31)

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/subagent-loader.test.ts
git commit -m "test(ai-agent): api-connection-manager 서브에이전트 로딩 검증 케이스 3종 추가"
```

---

## Task 5: Playwright E2E 테스트 추가

**Files:**
- Create: `apps/firehub-web/e2e/pages/ai-chat/api-connection-manager.spec.ts`

- [ ] **Step 1: 기존 AI 챗 E2E 패턴 확인**

```bash
head -30 apps/firehub-web/e2e/pages/ai-chat/data-analyst.spec.ts
```

- [ ] **Step 2: api-connection-manager.spec.ts 생성**

`data-analyst.spec.ts`의 패턴을 그대로 따른다 (sseEvent 헬퍼, mockAiSessions, openChatPanel, authenticatedPage fixture, SSE route 모킹).

```typescript
/**
 * api-connection-manager 서브에이전트 E2E 테스트
 * API 연결 생성/삭제 흐름의 AI 채팅 인터랙션을 검증한다.
 */
import { expect, test } from '../../fixtures/auth.fixture';

// SSE 이벤트 생성 헬퍼 (기존 패턴 동일)
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** ACM-01: API 연결 생성 요청에 대한 SSE 응답 이벤트 */
const CREATE_CONNECTION_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'acm-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__create_api_connection',
    input: {
      name: '공공데이터포털',
      authType: 'API_KEY',
      authConfig: { apiKey: '***', headerName: 'Authorization' },
    },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__create_api_connection',
    result: JSON.stringify({ id: 5, name: '공공데이터포털', authType: 'API_KEY' }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      "'공공데이터포털' 연결이 등록되었습니다 (ID: 5, 인증방식: API_KEY).\n파이프라인 API_CALL 스텝에서 이 연결을 사용할 수 있습니다.",
  }),
  sseEvent({ type: 'done' }),
];

/** ACM-02: 연결 삭제 요청에 대한 SSE 응답 이벤트 */
const DELETE_CONNECTION_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'acm-session-2' }),
  sseEvent({
    type: 'text',
    content:
      "'공공데이터포털' 연결(ID: 5)을 삭제하면 이 연결을 사용하는 파이프라인이 동작하지 않습니다. 계속할까요?",
  }),
  sseEvent({ type: 'done' }),
];

test.describe('AI Chat — api-connection-manager 서브에이전트', () => {
  test('ACM-01: API 연결 생성 요청 시 연결 ID와 등록 완료 메시지가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    let capturedPayload: Record<string, unknown> | null = null;

    // AI 채팅 API 모킹
    await page.route(
      (url) => url.pathname.includes('/agent/chat'),
      async (route) => {
        if (route.request().method() === 'POST') {
          capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
          await route.fulfill({
            status: 200,
            headers: { 'Content-Type': 'text/event-stream' },
            body: CREATE_CONNECTION_EVENTS.join(''),
          });
        } else {
          await route.continue();
        }
      }
    );

    await page.goto('/');

    // AI 챗 패널 열기
    const chatToggle = page.locator('[data-testid="ai-chat-toggle"]');
    if (await chatToggle.isVisible()) {
      await chatToggle.click();
    }
    await page.waitForSelector('[data-testid="ai-chat-input"]', { timeout: 10000 });

    // 메시지 전송
    await page.fill('[data-testid="ai-chat-input"]', '공공데이터포털 API 키 연결 등록해줘');
    await page.click('[data-testid="ai-chat-send"]');

    // payload 검증
    await expect.poll(() => capturedPayload, { timeout: 5000 }).not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: expect.stringContaining('공공데이터포털') });

    // 응답 메시지 검증
    await expect(
      page.getByText(/연결이 등록되었습니다|API_KEY|ID: 5/).first()
    ).toBeVisible({ timeout: 30000 });
  });

  test('ACM-02: 삭제 요청 시 확인 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    let capturedPayload: Record<string, unknown> | null = null;

    await page.route(
      (url) => url.pathname.includes('/agent/chat'),
      async (route) => {
        if (route.request().method() === 'POST') {
          capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
          await route.fulfill({
            status: 200,
            headers: { 'Content-Type': 'text/event-stream' },
            body: DELETE_CONNECTION_EVENTS.join(''),
          });
        } else {
          await route.continue();
        }
      }
    );

    await page.goto('/');

    const chatToggle = page.locator('[data-testid="ai-chat-toggle"]');
    if (await chatToggle.isVisible()) {
      await chatToggle.click();
    }
    await page.waitForSelector('[data-testid="ai-chat-input"]', { timeout: 10000 });

    await page.fill('[data-testid="ai-chat-input"]', '공공데이터포털 연결 삭제해줘');
    await page.click('[data-testid="ai-chat-send"]');

    // payload 검증
    await expect.poll(() => capturedPayload, { timeout: 5000 }).not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: expect.stringContaining('삭제') });

    // 삭제 확인 안내 메시지 검증
    await expect(
      page.getByText(/계속할까요|삭제하면|동작하지 않습니다/).first()
    ).toBeVisible({ timeout: 30000 });
  });
});
```

- [ ] **Step 3: TypeScript 타입 체크**

```bash
cd apps/firehub-web && npx tsc -p tsconfig.e2e.json --noEmit 2>&1 | tail -5
```

Expected: 오류 없음

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/e2e/pages/ai-chat/api-connection-manager.spec.ts
git commit -m "test(web/e2e): api-connection-manager 서브에이전트 AI 챗 E2E 테스트 추가"
```

---

## Task 6: ROADMAP.md 업데이트

**Files:**
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: Phase 5.10.3 완료 표시**

`docs/ROADMAP.md`에서 다음 줄을 찾아 교체:

변경 전:
```
- ⬜ 5.10.3 api-connection-manager 서브에이전트 — 외부 API 커넥션 설계/등록/테스트
```

변경 후:
```
- ✅ **5.10.3 api-connection-manager 서브에이전트** — API_KEY·BEARER 인증 방식 연결 생성·수정·삭제. 4단계 워크플로(IDENTIFY→DESIGN→EXECUTE→CONFIRM). 삭제 확인 체크리스트. 인증 값 보안 원칙. 서브에이전트 로더 테스트 3종. Playwright E2E 2종.
```

- [ ] **Step 2: 진행률 업데이트**

변경 전:
```
| [Phase 5.10](#phase-510-ai-챗-데이터-플랫폼-전면-제어) | **진행 중** | 2/7 |
```

변경 후:
```
| [Phase 5.10](#phase-510-ai-챗-데이터-플랫폼-전면-제어) | **진행 중** | 3/7 |
```

- [ ] **Step 3: 커밋**

```bash
git add docs/ROADMAP.md
git commit -m "docs(roadmap): Phase 5.10.3 api-connection-manager 서브에이전트 완료 표시"
```

---

## Self-Review

### Spec Coverage

| 요구사항 | 담당 Task |
|---------|---------|
| 외부 API 커넥션 설계 | Task 1 (Phase 2 DESIGN), Task 2 (authType 구조) |
| 등록 (API_KEY, BEARER) | Task 1 (Phase 3 EXECUTE), Task 3 (예시 1, 2) |
| 수정 (키 갱신) | Task 1 (Phase 3), Task 2 (수정 가이드라인), Task 3 (예시 3) |
| 삭제 + 확인 | Task 1 (Phase 3 삭제 흐름), Task 2 (삭제 체크리스트), Task 3 (예시 4) |
| 현재 미지원 기능 안내 | Task 2 (섹션 5 미지원 기능) |
| 보안 원칙 | Task 1 (보안 원칙 섹션) |
| 서브에이전트 로더 검증 | Task 4 |
| E2E 검증 | Task 5 (생성 + 삭제 확인 2종) |

### Placeholder Scan

없음 — 모든 JSON, 코드 블록을 실제 내용으로 작성했다.

### Type Consistency

- `create_api_connection` — api-connection-tools.ts의 실제 도구명과 일치
- `update_api_connection` — 동일
- `delete_api_connection` — 동일
- `authConfig` 필드 구조(`apiKey`, `headerName`, `token`) — rules.md와 examples.md 전체 일치
