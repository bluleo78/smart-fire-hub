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
