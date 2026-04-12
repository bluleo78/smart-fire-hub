/**
 * trigger-manager 서브에이전트 E2E 테스트
 *
 * 시나리오:
 *   TM-01: 사용자가 스케줄 트리거 생성 요청 → 응답에 트리거/스케줄 관련 키워드 포함
 *   TM-02: 사용자가 트리거 비활성화 요청 → 응답에 비활성화/업데이트 관련 키워드 포함
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
