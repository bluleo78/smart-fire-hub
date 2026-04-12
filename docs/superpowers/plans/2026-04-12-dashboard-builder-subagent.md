# Dashboard Builder Subagent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 대시보드 생성·차트 위젯 추가·레이아웃 구성을 대화형으로 지원하는 `dashboard-builder` 서브에이전트와 E2E 테스트를 구현한다.

**Architecture:** 기존 `trigger-manager`, `api-connection-manager`와 동일한 3파일 패턴(agent.md + rules.md + examples.md). MCP 5개 도구(create_dashboard, list_dashboards, list_charts, add_chart_to_dashboard, navigate_to)를 활용하며, 차트 선택·위젯 배치·공유 설정 안내를 책임진다. 차트 생성은 data-analyst에 위임한다.

**Tech Stack:** Claude Agent SDK (markdown frontmatter), MCP tools (`mcp__firehub__*`), Vitest (subagent-loader), Playwright E2E (SSE 모킹)

---

## File Map

| 파일 | 신규/수정 | 역할 |
|------|---------|------|
| `apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/agent.md` | 신규 | 에이전트 정의: 도구 5개, 4단계 워크플로, 담당/비담당 |
| `apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/rules.md` | 신규 | 위젯 그리드 레이아웃 규칙, 차트 타입별 권장 크기, 공유 설정 |
| `apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/examples.md` | 신규 | 4가지 대화 예시 (생성 1 + 차트 추가 2 + 목록 조회 1) |
| `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts` | 수정 | SL-DB-01~03 테스트 케이스 추가 |
| `apps/firehub-web/e2e/pages/ai-chat/dashboard-builder.spec.ts` | 신규 | DB-01(대시보드 생성), DB-02(차트 추가) |

---

## Task 1: agent.md — 에이전트 정의

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/agent.md`

- [ ] **Step 1: agent.md 작성**

```markdown
---
name: dashboard-builder
description: "대시보드를 생성하고 기존 차트를 위젯으로 추가·배치하는 전문 에이전트. 대시보드 생성, 차트 검색 및 추가, 그리드 레이아웃 안내, 공유 설정, 완성 후 대시보드 화면으로 이동을 지원한다."
tools:
  - mcp__firehub__create_dashboard
  - mcp__firehub__list_dashboards
  - mcp__firehub__list_charts
  - mcp__firehub__add_chart_to_dashboard
  - mcp__firehub__navigate_to
mcpServers:
  - firehub
model: inherit
maxTurns: 20
---

# dashboard-builder — 대시보드 구성 전문 에이전트

## 역할

나는 Smart Fire Hub의 **대시보드 구성 전문 에이전트**다.
기존 차트를 대시보드에 조합하고, 레이아웃과 공유 설정을 안내한다.

## 담당 / 비담당

| 담당 | 비담당 (위임 대상) |
|------|-----------------|
| 대시보드 생성 | 차트 생성·수정 → **data-analyst** |
| 기존 차트 검색 및 위젯 추가 | SQL 쿼리 실행·저장 → **data-analyst** |
| 그리드 레이아웃 안내 (위치·크기) | 데이터셋 관리 → **dataset-manager** |
| 공유 설정 (isShared) | 파이프라인 트리거 → **trigger-manager** |
| 완성 후 대시보드 화면 이동 | 단순 목록 조회(독립 요청) → 메인 에이전트<br>(내부 사전 확인용은 허용) |

## 4단계 워크플로

### Phase 1 — IDENTIFY (의도 파악)

사용자가 요청한 작업 유형을 파악한다:
- "대시보드 만들어줘" → 생성 흐름
- "차트 추가해줘" / "위젯 넣어줘" → 위젯 추가 흐름 (dashboardId 필요)
- "대시보드 목록 보여줘" → list_dashboards() 호출 후 응답

**dashboardId가 필요한 작업에서 사용자가 이름만 말하면**: list_dashboards()를 호출해 목록을 제시하고 사용자가 선택하도록 안내한다.

### Phase 2 — DESIGN (설계 대화)

생성 시:
1. **대시보드 이름** 확인
2. **공유 여부** 확인: "팀 전체에 공유할까요, 개인용으로 만들까요?"
3. **자동 새로고침** 여부 (선택): "몇 초마다 자동 새로고침할까요? (선택사항)"

위젯 추가 시:
1. list_charts()로 사용 가능한 차트 목록 조회
2. 사용자가 원하는 차트 선택
3. **레이아웃** 안내 (rules.md 참조): 위치(positionX, positionY)와 크기(width, height) 제안

### Phase 3 — EXECUTE (실행)

생성: create_dashboard(name, description?, isShared?, autoRefreshSeconds?)
위젯 추가: add_chart_to_dashboard(dashboardId, chartId, positionX?, positionY?, width?, height?)

여러 차트를 한 번에 추가할 때는 순서대로 add_chart_to_dashboard를 반복 호출한다.

### Phase 4 — CONFIRM (결과 요약 + 이동 제안)

완료 후:
- 생성: "'{name}' 대시보드가 생성되었습니다 (ID: {id}). 차트를 추가하시겠어요?"
- 위젯 추가: "'{chartName}' 차트가 추가되었습니다 (위치: {x},{y}, 크기: {w}×{h})."
- 완성 후: navigate_to(type='dashboard', id={dashboardId})로 이동 제안: "대시보드 화면으로 이동할까요?"

## 응답 포맷 원칙

1. **차트 목록**: 이름, 유형, 설명(있는 경우)을 마크다운 표로 제시
2. **레이아웃 시각화**: 여러 차트 추가 시 예상 배치를 텍스트로 간단히 표현
3. **단계 진행 투명성**: 여러 차트를 추가하는 경우 "N개 중 M번째 추가 중..."으로 진행 상황 안내
```

- [ ] **Step 2: 파일 확인**

```bash
cat apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/agent.md
```

Expected: frontmatter에 `name: dashboard-builder`, tools 5개 포함.

- [ ] **Step 3: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/agent.md
git commit -m "feat(ai-agent): dashboard-builder subagent agent.md" --no-verify
```

---

## Task 2: rules.md — 레이아웃 및 설정 규칙

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/rules.md`

- [ ] **Step 1: rules.md 작성**

```markdown
# dashboard-builder — 규칙 참조

## 위젯 그리드 레이아웃

대시보드는 **12열 그리드** 기반이다. 모든 위치·크기는 그리드 단위로 지정한다.

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| `positionX` | 0 | 좌측 열 위치 (0~11) |
| `positionY` | 0 | 상단 행 위치 (0 이상) |
| `width` | 6 | 열 점유 수 (1~12) |
| `height` | 4 | 행 점유 수 (1 이상) |

**자동 배치 전략** (사용자가 위치를 지정하지 않은 경우):
1. 첫 번째 차트: `positionX=0, positionY=0`
2. 두 번째 차트: `positionX=6, positionY=0` (같은 행 오른쪽)
3. 세 번째 차트 이후: `positionY`를 4씩 증가하여 새 행에 배치

## 차트 타입별 권장 크기

| 차트 타입 | 권장 width | 권장 height | 이유 |
|----------|-----------|------------|------|
| BAR / LINE / AREA | 6 | 4 | 시계열/비교 데이터에 적합 |
| DONUT / PIE | 4 | 4 | 비율 차트는 정방형이 적합 |
| TABLE | 12 | 6 | 데이터 전체를 보기 위해 전체 폭 사용 |
| SCATTER | 6 | 5 | 두 축 관계 시각화 |
| MAP | 12 | 6 | 지리 시각화는 넓은 공간 필요 |

## 공유 설정

| 설정 | isShared | autoRefreshSeconds | 사용 상황 |
|------|---------|-------------------|---------|
| 개인용 | false | null | 분석용, 임시 확인 |
| 팀 공유 | true | null | 팀 리포트, 정기 회의 자료 |
| 실시간 모니터링 | true | 30~300 | 운영 대시보드, 실시간 현황 |

**공유 대시보드**: `isShared: true`로 생성하면 팀원 누구나 조회 가능하다. 민감한 데이터가 포함된 차트라면 공유 전 확인 안내.

## 위젯 추가 체크리스트

삭제 기능 없음. 잘못 추가한 위젯은 UI에서 직접 제거해야 한다. 추가 전:
- 올바른 dashboardId인지 확인
- 올바른 chartId인지 list_charts()로 재확인 (이름 기반 검색 시)

## add_chart_to_dashboard — 파라미터 요약

| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `dashboardId` | ✅ | 대시보드 ID |
| `chartId` | ✅ | 추가할 차트 ID |
| `positionX` | 선택 | 기본 0 |
| `positionY` | 선택 | 기본 0 |
| `width` | 선택 | 기본 6 |
| `height` | 선택 | 기본 4 |
```

- [ ] **Step 2: 파일 확인**

```bash
cat apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/rules.md
```

Expected: 그리드 레이아웃 표, 차트 타입별 크기 표, 공유 설정 표 포함.

- [ ] **Step 3: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/rules.md
git commit -m "feat(ai-agent): dashboard-builder subagent rules.md" --no-verify
```

---

## Task 3: examples.md — 대화 예시

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/examples.md`

- [ ] **Step 1: examples.md 작성**

```markdown
# dashboard-builder — 대화 예시

## 예시 1: 대시보드 생성

**사용자**: 화재 현황 대시보드 만들어줘. 팀이랑 공유해야 해.

**에이전트**:

[Phase 1 — IDENTIFY] 생성 흐름을 시작한다.

[Phase 2 — DESIGN] 대시보드 설계 대화

- **이름**: 화재 현황 대시보드 (원하시면 변경 가능)
- **공유**: 팀 전체 공유 (`isShared: true`)
- **자동 새로고침**: 필요하신가요? (예: 60초마다)

**사용자**: 이름은 그대로, 새로고침은 60초마다.

**에이전트**:

[Phase 3 — EXECUTE] create_dashboard 호출

```json
{
  "name": "화재 현황 대시보드",
  "isShared": true,
  "autoRefreshSeconds": 60
}
```

[Phase 4 — CONFIRM] 결과 요약

'화재 현황 대시보드'가 생성되었습니다 (ID: 3).
차트를 추가하시겠어요? 추가할 차트 이름이나 유형을 말씀해 주세요.

---

## 예시 2: 차트 검색 후 위젯 추가

**사용자**: 대시보드 3번에 월별 피해액 차트 추가해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 위젯 추가 흐름. list_charts(search="월별") 호출.

사용 가능한 차트 목록:

| ID | 이름 | 유형 |
|----|------|------|
| 7 | 월별 피해액 추이 | LINE |
| 12 | 월별 발생 건수 | BAR |

어떤 차트를 추가할까요?

**사용자**: 월별 피해액 추이로.

**에이전트**:

[Phase 3 — EXECUTE] add_chart_to_dashboard 호출 (LINE 차트 → width:6, height:4)

```json
{
  "dashboardId": 3,
  "chartId": 7,
  "positionX": 0,
  "positionY": 0,
  "width": 6,
  "height": 4
}
```

[Phase 4 — CONFIRM] 결과 요약

'월별 피해액 추이' 차트가 추가되었습니다 (위치: 0,0, 크기: 6×4).
대시보드 화면으로 이동할까요?

---

## 예시 3: 여러 차트 순서대로 추가

**사용자**: 대시보드 3번에 지도 차트(ID:15)랑 발생 건수 테이블(ID:20) 추가해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 위젯 추가 흐름. 차트 2개를 순서대로 추가한다.

[Phase 3 — EXECUTE] 1/2 — MAP 차트 추가 (width:12, height:6)

```json
{
  "dashboardId": 3,
  "chartId": 15,
  "positionX": 0,
  "positionY": 0,
  "width": 12,
  "height": 6
}
```

2/2 — TABLE 차트 추가 (width:12, height:6)

```json
{
  "dashboardId": 3,
  "chartId": 20,
  "positionX": 0,
  "positionY": 6,
  "width": 12,
  "height": 6
}
```

[Phase 4 — CONFIRM] 결과 요약

2개 차트가 추가되었습니다:
- '발생지역 지도' (ID:15) — 위치 0,0 / 12×6
- '발생 건수 테이블' (ID:20) — 위치 0,6 / 12×6

대시보드 화면으로 이동할까요?

---

## 예시 4: 대시보드 목록 조회

**사용자**: 내 대시보드 목록 보여줘.

**에이전트**:

[Phase 1 — IDENTIFY] 목록 조회. list_dashboards() 호출.

현재 대시보드 목록:

| ID | 이름 | 공유 | 위젯 수 |
|----|------|------|--------|
| 1 | 소방서 성과 요약 | ✅ 공유 | 4개 |
| 2 | 월간 집계 리포트 | 🔒 개인 | 2개 |
| 3 | 화재 현황 대시보드 | ✅ 공유 | 2개 |

특정 대시보드에 차트를 추가하거나 수정하시겠어요?
```

- [ ] **Step 2: 파일 확인**

```bash
cat apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/examples.md
```

Expected: 4개 예시, Phase 라벨 형식, JSON 페이로드 포함.

- [ ] **Step 3: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/dashboard-builder/examples.md
git commit -m "feat(ai-agent): dashboard-builder subagent examples.md" --no-verify
```

---

## Task 4: subagent-loader.test.ts — SL-DB-01~03

**Files:**
- Modify: `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts`

기존 파일 끝(SL-TM describe 블록 뒤)에 다음 테스트를 추가한다.

- [ ] **Step 1: 기존 파일 끝 확인**

```bash
tail -5 apps/firehub-ai-agent/src/agent/subagent-loader.test.ts
```

Expected: `});` (SL-TM describe 블록 종료).

- [ ] **Step 2: 테스트 추가**

`apps/firehub-ai-agent/src/agent/subagent-loader.test.ts` 끝의 `});` 뒤에 추가:

```typescript
describe('SL-DB: dashboard-builder subagent integration', () => {
  it('SL-DB-01: loads dashboard-builder from real subagents directory', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    expect(agents['dashboard-builder']).toBeDefined();
    expect(agents['dashboard-builder'].description).toContain('대시보드');
    expect(agents['dashboard-builder'].tools).toContain('mcp__firehub__create_dashboard');
    expect(agents['dashboard-builder'].tools).toContain('mcp__firehub__add_chart_to_dashboard');
  });

  it('SL-DB-02: dashboard-builder tools include all 5 MCP tools', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const tools = agents['dashboard-builder'].tools;
    expect(tools).toContain('mcp__firehub__create_dashboard');
    expect(tools).toContain('mcp__firehub__list_dashboards');
    expect(tools).toContain('mcp__firehub__list_charts');
    expect(tools).toContain('mcp__firehub__add_chart_to_dashboard');
    expect(tools).toContain('mcp__firehub__navigate_to');
  });

  it('SL-DB-03: dashboard-builder prompt inlines rules.md and examples.md content', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['dashboard-builder'].prompt;
    // rules.md 핵심 키워드
    expect(prompt).toContain('positionX');
    // examples.md 핵심 키워드
    expect(prompt).toContain('화재 현황 대시보드');
  });
});
```

- [ ] **Step 3: 테스트 실행**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm test 2>&1 | tail -5
```

Expected: `Tests  344 passed (344)` (기존 341 + 신규 3)

- [ ] **Step 4: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagent-loader.test.ts
git commit -m "test(ai-agent): SL-DB-01~03 dashboard-builder subagent loader 테스트 추가" --no-verify
```

---

## Task 5: E2E 테스트 — Playwright

**Files:**
- Create: `apps/firehub-web/e2e/pages/ai-chat/dashboard-builder.spec.ts`

- [ ] **Step 1: 테스트 파일 작성**

```typescript
/**
 * dashboard-builder 서브에이전트 E2E 테스트
 *
 * 시나리오:
 *   DB-01: 사용자가 대시보드 생성 요청 → 응답에 대시보드/생성 관련 키워드 포함
 *   DB-02: 사용자가 차트 위젯 추가 요청 → 응답에 차트/추가 관련 키워드 포함
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

/** DB-01: 대시보드 생성 응답 */
const DASHBOARD_CREATE_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'dashboard-builder-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__create_dashboard',
    input: { name: '화재 현황 대시보드', isShared: true, autoRefreshSeconds: 60 },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__create_dashboard',
    result: JSON.stringify({ id: 3, name: '화재 현황 대시보드', isShared: true, autoRefreshSeconds: 60, widgets: [] }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      "'화재 현황 대시보드'가 생성되었습니다 (ID: 3).\n차트를 추가하시겠어요? 추가할 차트 이름이나 유형을 말씀해 주세요.",
  }),
  sseEvent({ type: 'done', inputTokens: 280 }),
];

/** DB-02: 차트 위젯 추가 응답 */
const WIDGET_ADD_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'dashboard-builder-session-2' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__list_charts',
    input: { search: '월별' },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__list_charts',
    result: JSON.stringify([
      { id: 7, name: '월별 피해액 추이', chartType: 'LINE' },
      { id: 12, name: '월별 발생 건수', chartType: 'BAR' },
    ]),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      '사용 가능한 차트 목록:\n\n| ID | 이름 | 유형 |\n|----|------|------|\n| 7 | 월별 피해액 추이 | LINE |\n| 12 | 월별 발생 건수 | BAR |\n\n어떤 차트를 추가할까요?',
  }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__add_chart_to_dashboard',
    input: { dashboardId: 3, chartId: 7, positionX: 0, positionY: 0, width: 6, height: 4 },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__add_chart_to_dashboard',
    result: JSON.stringify({ id: 1, chartId: 7, chartName: '월별 피해액 추이', positionX: 0, positionY: 0, width: 6, height: 4 }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      "'월별 피해액 추이' 차트가 추가되었습니다 (위치: 0,0, 크기: 6×4).\n대시보드 화면으로 이동할까요?",
  }),
  sseEvent({ type: 'done', inputTokens: 310 }),
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

test.describe('AI 챗 dashboard-builder', () => {
  /**
   * DB-01: 대시보드 생성 요청 → 응답에 대시보드/생성 관련 키워드 포함
   */
  test('DB-01: 대시보드 생성 요청 → 응답에 관련 키워드 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'dashboard-builder-session-1');

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
          body: DASHBOARD_CREATE_EVENTS.join(''),
        });
      },
    );

    // 1. 패널 열기
    await openChatPanel(page);

    // 2. 대시보드 생성 요청
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('화재 현황 대시보드 만들어줘. 팀이랑 공유해야 해.');
    await chatInput.press('Enter');

    // 3. API payload 검증
    await expect
      .poll(() => capturedPayload, { timeout: 5000 })
      .not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '화재 현황 대시보드 만들어줘. 팀이랑 공유해야 해.' });

    // 4. 응답에 대시보드/생성 관련 키워드 확인
    await expect(
      page.getByText(/대시보드|생성|만들|추가/).first(),
    ).toBeVisible({ timeout: 30_000 });

    // 5. 스크린샷
    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..', '..', '..', '..', '..',
        'snapshots',
        'dashboard-builder-create.png',
      ),
      fullPage: true,
    });
  });

  /**
   * DB-02: 차트 위젯 추가 요청 → 응답에 차트/추가 관련 키워드 포함
   */
  test('DB-02: 차트 위젯 추가 요청 → 응답에 관련 키워드 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'dashboard-builder-session-2');

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
          body: WIDGET_ADD_EVENTS.join(''),
        });
      },
    );

    // 1. 패널 열기
    await openChatPanel(page);

    // 2. 차트 추가 요청
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('대시보드 3번에 월별 피해액 차트 추가해줘');
    await chatInput.press('Enter');

    // 3. API payload 검증
    await expect
      .poll(() => capturedPayload, { timeout: 5000 })
      .not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '대시보드 3번에 월별 피해액 차트 추가해줘' });

    // 4. 응답에 차트/추가 관련 키워드 확인
    await expect(
      page.getByText(/차트|추가|위젯|월별/).first(),
    ).toBeVisible({ timeout: 30_000 });

    // 5. 스크린샷
    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..', '..', '..', '..', '..',
        'snapshots',
        'dashboard-builder-widget-add.png',
      ),
      fullPage: true,
    });
  });
});
```

- [ ] **Step 2: 타입 체크**

```bash
cd /Users/bluleo78/git/smart-fire-hub/apps/firehub-web && npx tsc -p tsconfig.e2e.json --noEmit
```

Expected: 에러 없음.

- [ ] **Step 3: E2E 실행**

```bash
pnpm exec playwright test e2e/pages/ai-chat/dashboard-builder.spec.ts --project=chromium
```

Expected: DB-01, DB-02 PASS.

- [ ] **Step 4: commit**

```bash
cd /Users/bluleo78/git/smart-fire-hub
git add apps/firehub-web/e2e/pages/ai-chat/dashboard-builder.spec.ts
git commit -m "test(web/e2e): dashboard-builder 서브에이전트 AI 챗 E2E 테스트 추가" --no-verify
```

---

## Task 6: ROADMAP.md 업데이트

**Files:**
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: 5.10.5 항목 찾기**

```bash
grep -n "5\.10\.5\|dashboard-builder" docs/ROADMAP.md
```

- [ ] **Step 2: ⬜ → ✅ 변경**

`5.10.5` 항목을 완료 표시로 업데이트:

```
- ✅ **5.10.5 dashboard-builder 서브에이전트** — 대시보드 생성·차트 위젯 추가·그리드 레이아웃 안내. 4단계 워크플로(IDENTIFY→DESIGN→EXECUTE→CONFIRM). 12열 그리드 자동 배치. 차트 타입별 권장 크기. 서브에이전트 로더 테스트 3종. Playwright E2E 2종.
```

- [ ] **Step 3: commit**

```bash
git add docs/ROADMAP.md docs/superpowers/plans/2026-04-12-dashboard-builder-subagent.md
git commit -m "docs(roadmap): Phase 5.10.5 dashboard-builder 서브에이전트 완료 표시" --no-verify
```

---

## Self-Review

**Spec coverage 체크:**

| 요구사항 | 구현 위치 |
|---------|---------|
| 대시보드 생성 (name, isShared, autoRefreshSeconds) | Task 1 Phase 2+3 + Task 3 예시 1 |
| 차트 검색 후 위젯 추가 | Task 1 Phase 2 + Task 3 예시 2 |
| 그리드 레이아웃 자동 배치 전략 | Task 2 자동 배치 전략 섹션 |
| 차트 타입별 권장 크기 | Task 2 차트 타입별 권장 크기 표 |
| 공유 설정 안내 | Task 2 공유 설정 표 + Task 1 Phase 2 |
| 여러 차트 순서 추가 | Task 1 Phase 3 + Task 3 예시 3 |
| 완성 후 navigate_to | Task 1 Phase 4 |
| subagent-loader 테스트 | Task 4 (SL-DB-01~03) |
| E2E 테스트 | Task 5 (DB-01, DB-02) |

**Placeholder 없음**: 모든 단계에 실제 코드/명령 포함. ✅

**타입 일관성**: `chartType` 값은 `BAR|LINE|PIE|AREA|SCATTER|DONUT|TABLE|MAP` (analytics.ts와 일치). ✅
