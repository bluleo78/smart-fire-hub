/**
 * audit-analyst 서브에이전트 E2E 테스트
 *
 * 시나리오:
 *   AA-01: 실패 이벤트 조회 요청 → 응답에 실패/FAILURE 관련 키워드 포함
 *   AA-02: 사용자 활동 조회 요청 → 응답에 사용자명/활동 관련 키워드 포함
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

/** AA-01: 실패 이벤트 조회 응답 */
const FAILURE_LOG_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'audit-analyst-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__list_audit_logs',
    input: { result: 'FAILURE', size: 20 },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__list_audit_logs',
    result: JSON.stringify({
      content: [
        {
          id: 1,
          userId: 2,
          username: 'hong',
          actionType: 'LOGIN',
          resource: 'user',
          resourceId: null,
          description: '비밀번호 불일치',
          actionTime: '2026-04-12T11:05:00',
          ipAddress: '192.168.1.1',
          userAgent: null,
          result: 'FAILURE',
          errorMessage: '비밀번호가 올바르지 않습니다.',
          metadata: null,
        },
        {
          id: 2,
          userId: 3,
          username: 'kim',
          actionType: 'DELETE',
          resource: 'dataset',
          resourceId: '42',
          description: '데이터셋 삭제 권한 없음',
          actionTime: '2026-04-12T14:23:00',
          ipAddress: '192.168.1.2',
          userAgent: null,
          result: 'FAILURE',
          errorMessage: '권한이 없습니다.',
          metadata: null,
        },
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
    content:
      '최근 실패 이벤트 (최근 20건 기준):\n\n- hong: 로그인 실패 (비밀번호 불일치)\n- kim: 데이터셋 삭제 권한 없음\n\n총 2건의 실패 이벤트가 확인되었습니다.',
  }),
  sseEvent({ type: 'done', inputTokens: 260 }),
];

/** AA-02: 사용자 활동 조회 응답 */
const USER_ACTIVITY_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'audit-analyst-session-2' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__list_audit_logs',
    input: { search: '홍길동', size: 30 },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__list_audit_logs',
    result: JSON.stringify({
      content: [
        {
          id: 5,
          userId: 2,
          username: '홍길동',
          actionType: 'CREATE',
          resource: 'dataset',
          resourceId: '50',
          description: "데이터셋 '화재통계_2026Q1' 생성",
          actionTime: '2026-04-12T14:10:00',
          ipAddress: '192.168.1.1',
          userAgent: null,
          result: 'SUCCESS',
          errorMessage: null,
          metadata: null,
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 30,
    }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      "홍길동 최근 활동 (30건 기준):\n\n- 2026-04-12 14:10 | CREATE | dataset | SUCCESS — 데이터셋 '화재통계_2026Q1' 생성\n\n총 1건. 모든 활동이 정상적으로 수행되었습니다.",
  }),
  sseEvent({ type: 'done', inputTokens: 280 }),
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
  // AI 패널은 모든 인증 페이지에서 접근 가능 — 홈으로 이동 후 패널 열기
  await page.goto("/", { waitUntil: "commit" });
  await page.getByText('AI 어시스턴트').first().click();
  await page
    .getByPlaceholder('메시지를 입력하세요...')
    .waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('AI 챗 audit-analyst', () => {
  /**
   * AA-01: 실패 이벤트 조회 요청 → 응답에 실패 건수 포함
   */
  test('AA-01: 실패 이벤트 조회 요청 → 응답에 실패 이벤트 요약 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'audit-analyst-session-1');

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
          body: FAILURE_LOG_EVENTS.join(''),
        });
      },
    );

    await openChatPanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('최근에 실패한 작업이 있는지 확인해줘');
    await chatInput.press('Enter');

    await expect.poll(() => capturedPayload, { timeout: 5000 }).not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '최근에 실패한 작업이 있는지 확인해줘' });

    // 응답에 실패 건수 확인 (총 2건)
    await expect(page.getByText(/총 2건|실패 이벤트/).first()).toBeVisible({ timeout: 30_000 });

    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..', '..', '..', '..', '..',
        'snapshots',
        'audit-analyst-failure-logs.png',
      ),
      fullPage: true,
    });
  });

  /**
   * AA-02: 사용자 활동 조회 요청 → 응답에 사용자 이름 + 활동 포함
   */
  test('AA-02: 사용자 활동 조회 요청 → 응답에 활동 내역 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'audit-analyst-session-2');

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
          body: USER_ACTIVITY_EVENTS.join(''),
        });
      },
    );

    await openChatPanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('홍길동 최근 활동 내역 보여줘');
    await chatInput.press('Enter');

    await expect.poll(() => capturedPayload, { timeout: 5000 }).not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '홍길동 최근 활동 내역 보여줘' });

    // 응답에 홍길동 활동 내역 포함 확인 (데이터셋 생성 내역)
    await expect(page.getByText(/화재통계_2026Q1|홍길동.*CREATE/).first()).toBeVisible({ timeout: 30_000 });

    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..', '..', '..', '..', '..',
        'snapshots',
        'audit-analyst-user-activity.png',
      ),
      fullPage: true,
    });
  });
});
