/**
 * admin-manager 서브에이전트 E2E 테스트
 *
 * 시나리오:
 *   AM-01: 사용자 목록 조회 요청 → 응답에 사용자/목록 관련 키워드 포함
 *   AM-02: 사용자 역할 변경 요청 → 응답에 역할/변경 관련 키워드 포함
 *
 * 프로젝트 E2E 컨벤션:
 * - 백엔드/ai-agent 없이 API 모킹 기반으로 동작한다.
 * - AI 챗은 SSE 스트리밍이므로 `/api/v1/ai/chat` 응답을 SSE 이벤트 시퀀스로 모킹한다.
 * - 로그인은 auth.fixture.ts의 authenticatedPage를 사용한다.
 * - 스크린샷은 레포 루트 snapshots/ 폴더에 저장한다.
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

/** AM-01: 사용자 목록 조회 응답 */
const USER_LIST_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'admin-manager-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__list_users',
    input: {},
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__list_users',
    result: JSON.stringify({
      content: [
        { id: 1, username: 'admin', email: 'admin@example.com', name: '홍길동', isActive: true, createdAt: '2026-01-01' },
        { id: 2, username: 'user1', email: 'user1@example.com', name: '김철수', isActive: true, createdAt: '2026-01-02' },
      ],
      totalElements: 2,
      totalPages: 1,
      number: 0,
      size: 20,
    }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content: '현재 등록된 사용자 목록:\n\n| ID | 이름 | 이메일 | 상태 |\n|----|------|--------|------|\n| 1 | 홍길동 | admin@example.com | 활성 |\n| 2 | 김철수 | user1@example.com | 활성 |\n\n총 2명.',
  }),
  sseEvent({ type: 'done', inputTokens: 250 }),
];

/** AM-02: 역할 변경 응답 */
const ROLE_CHANGE_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'admin-manager-session-2' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__list_users',
    input: { search: '김철수' },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__list_users',
    result: JSON.stringify({
      content: [{ id: 2, username: 'user1', email: 'user1@example.com', name: '김철수', isActive: true, createdAt: '2026-01-02' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    }),
    status: 'completed',
  }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__set_user_roles',
    input: { userId: 2, roleIds: [1] },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__set_user_roles',
    result: JSON.stringify({ success: true, userId: 2, roleIds: [1] }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content: "'김철수' 사용자의 역할이 USER → ADMIN으로 변경되었습니다.",
  }),
  sseEvent({ type: 'done', inputTokens: 300 }),
];

/** AI 세션 목록/생성 API 모킹 */
async function mockAiSessions(page: Page, sessionId: string) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ id: 1, sessionId, title: null, createdAt: '2026-04-12T00:00:00Z', updatedAt: '2026-04-12T00:00:00Z' }),
      });
    },
  );
}

/** AI 챗 사이드 패널 열기 */
async function openChatPanel(page: Page) {
  // AI 패널은 모든 인증 페이지에서 접근 가능 — 홈으로 이동 후 패널 열기
  await page.goto("/", { waitUntil: "commit" });
  await page.getByText('AI 어시스턴트').first().click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('AI 챗 admin-manager', () => {
  /**
   * AM-01: 사용자 목록 조회 요청 → 응답에 사용자/목록 관련 키워드 포함
   */
  test('AM-01: 사용자 목록 조회 요청 → 응답에 관련 키워드 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'admin-manager-session-1');

    let capturedPayload: Record<string, unknown> | null = null;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' },
          body: USER_LIST_EVENTS.join(''),
        });
      },
    );

    await openChatPanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('현재 등록된 사용자 목록 보여줘');
    await chatInput.press('Enter');

    await expect.poll(() => capturedPayload, { timeout: 5000 }).not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '현재 등록된 사용자 목록 보여줘' });

    // 응답에 사용자 이름 표시 확인
    await expect(page.getByText(/총 2명|홍길동/).first()).toBeVisible({ timeout: 30_000 });

    await page.screenshot({
      path: path.resolve(__dirname, '..', '..', '..', '..', '..', 'snapshots', 'admin-manager-user-list.png'),
      fullPage: true,
    });
  });

  /**
   * AM-02: 사용자 역할 변경 요청 → 응답에 역할/변경 관련 키워드 포함
   */
  test('AM-02: 사용자 역할 변경 요청 → 응답에 역할 변경 확인', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'admin-manager-session-2');

    let capturedPayload: Record<string, unknown> | null = null;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' },
          body: ROLE_CHANGE_EVENTS.join(''),
        });
      },
    );

    await openChatPanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('김철수를 ADMIN으로 바꿔줘');
    await chatInput.press('Enter');

    await expect.poll(() => capturedPayload, { timeout: 5000 }).not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '김철수를 ADMIN으로 바꿔줘' });

    // 응답에 역할 변경 결과 확인
    await expect(page.getByText(/ADMIN으로 변경|USER.*ADMIN/).first()).toBeVisible({ timeout: 30_000 });

    await page.screenshot({
      path: path.resolve(__dirname, '..', '..', '..', '..', '..', 'snapshots', 'admin-manager-role-change.png'),
      fullPage: true,
    });
  });
});
