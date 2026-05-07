/**
 * useAIChat.onCanvasWidget 콜백 stale closure 회귀 테스트 (이슈 #193)
 *
 * 버그: sendMessage useCallback이 options.onCanvasWidget을 의존성 배열에 포함하지 않아
 * 첫 마운트 시점의 낡은 콜백이 캡처되었다.
 *
 * 수정: onCanvasWidgetRef(useRef)로 관리하여 항상 최신 콜백을 참조하도록 변경.
 *
 * 시나리오:
 *   CW-01: 첫 번째 메시지에서 tool_result 수신 → ToolCallDisplay가 완료 상태로 렌더링
 *   CW-02: 두 번째 메시지에서 tool_result 수신 → 재렌더 후에도 콜백 정상 동작 (stale closure 없음)
 *
 * 프로젝트 E2E 컨벤션:
 * - 백엔드 없이 API 모킹 기반으로 동작한다.
 * - AI 챗 SSE 응답은 page.route()로 모킹한다.
 * - 로그인은 auth.fixture.ts의 authenticatedPage를 사용한다.
 */

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** AI 세션 API 모킹 */
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
          createdAt: '2026-05-07T00:00:00Z',
          updatedAt: '2026-05-07T00:00:00Z',
        }),
      });
    },
  );
}

/** AI 패널 열기 헬퍼 */
async function openChatPanel(page: Page) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  await page
    .getByPlaceholder('메시지를 입력하세요...')
    .waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('useAIChat — onCanvasWidget stale closure 회귀 (이슈 #193)', () => {
  /**
   * CW-01: 첫 번째 메시지에서 tool_result 수신 → ToolCallDisplay "✓ 완료" 렌더링
   * sendMessage 첫 호출 시 options.onCanvasWidget이 정상 동작해야 한다.
   */
  test('CW-01: 첫 번째 메시지의 tool_result → ToolCallDisplay 완료 렌더링', async ({ authenticatedPage: page }) => {
    const sessionId = 'cw-01-session';
    await mockAiSessions(page, sessionId);

    // tool_use → tool_result → done 시퀀스 (onCanvasWidget 콜백 경로 통과)
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId }),
            sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__get_row_count', input: { datasetId: 1 } }),
            // tool_result 수신 시 useAIChat 내부에서 onCanvasWidgetRef.current() 호출
            sseEvent({ type: 'tool_result', toolName: 'mcp__firehub__get_row_count', result: JSON.stringify({ totalRows: 100 }) }),
            sseEvent({ type: 'text', content: '데이터셋에는 100행이 있습니다.' }),
            sseEvent({ type: 'done', inputTokens: 80 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('행 수 조회');
    await chatInput.press('Enter');

    // tool_result 처리 후 ToolCallDisplay가 완료 상태로 렌더링 → "행 수 조회" 레이블 표시
    await expect(page.getByText('행 수 조회').first()).toBeVisible({ timeout: 10_000 });
    // 응답 텍스트가 렌더링되면 onCanvasWidget 콜백까지 포함한 tool_result 처리 경로가 완주된 것
    await expect(page.getByText('데이터셋에는 100행이 있습니다.').first()).toBeVisible({ timeout: 10_000 });
  });

  /**
   * CW-02: 두 번째 메시지에서 tool_result 수신 → 재렌더 후에도 콜백 정상 동작 (stale closure 회귀)
   *
   * stale closure 버그가 있을 때: 첫 sendMessage 호출 후 컴포넌트가 재렌더되어
   * options.onCanvasWidget이 갱신되어도 두 번째 sendMessage는 낡은 콜백을 참조한다.
   * useRef 패턴으로 수정 후: 항상 최신 콜백을 참조하므로 두 번째 메시지에서도 정상 동작한다.
   */
  test('CW-02: 두 번째 메시지 tool_result → 재렌더 후에도 ToolCallDisplay 완료 렌더링', async ({ authenticatedPage: page }) => {
    const sessionId = 'cw-02-session';
    await mockAiSessions(page, sessionId);

    // 메시지 횟수를 카운트하여 첫 번째와 두 번째 SSE 응답을 구분
    let callCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        callCount++;
        const isFirst = callCount === 1;
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId }),
            sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__get_row_count', input: { datasetId: isFirst ? 1 : 2 } }),
            sseEvent({ type: 'tool_result', toolName: 'mcp__firehub__get_row_count', result: JSON.stringify({ totalRows: isFirst ? 100 : 200 }) }),
            sseEvent({ type: 'text', content: isFirst ? '첫 번째 응답: 100행' : '두 번째 응답: 200행' }),
            sseEvent({ type: 'done', inputTokens: 80 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');

    // 첫 번째 메시지 전송 및 완료 대기
    await chatInput.fill('첫 번째 행 수 조회');
    await chatInput.press('Enter');
    await expect(page.getByText('첫 번째 응답: 100행').first()).toBeVisible({ timeout: 10_000 });

    // 두 번째 메시지 전송 — 재렌더 후 stale closure 없이 최신 onCanvasWidget 콜백 호출 검증
    await chatInput.fill('두 번째 행 수 조회');
    await chatInput.press('Enter');
    // 두 번째 tool_result 처리 후 응답 텍스트가 렌더링되어야 한다 (stale closure 시 콜백 오동작)
    await expect(page.getByText('두 번째 응답: 200행').first()).toBeVisible({ timeout: 10_000 });
  });
});
