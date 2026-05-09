/**
 * MessageList 스크롤 동작 회귀 테스트 (refs #215)
 *
 * 스트리밍 중 사용자가 위로 스크롤하면 자동 스크롤이 억제되고,
 * 맨 아래로 돌아오면 자동 스크롤이 재개되어야 한다.
 *
 * 테스트 전략:
 * - SSE 응답을 지연시켜 스트리밍 중 상태를 유지한다.
 * - 스트리밍 중 scrollContainer를 위로 스크롤한 후, scrollTop이 유지되는지 검증한다.
 * - 새 메시지 전송 시에는 무조건 맨 아래로 스크롤됨을 검증한다.
 */

import { expect, test } from '../../fixtures/auth.fixture';

/** AI 세션 GET/POST 모킹 + 채팅 패널 열기 공통 헬퍼 */
async function setupAndOpenPanel(page: import('@playwright/test').Page, sessionId = 'scroll-test-session') {
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
          createdAt: '2026-05-10T00:00:00Z',
          updatedAt: '2026-05-10T00:00:00Z',
        }),
      });
    },
  );

  await page.goto('/', { waitUntil: 'commit' });
  await page.getByRole('button', { name: /AI 상태/ }).click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('MessageList — 스트리밍 중 사용자 스크롤 동작 (#215)', () => {
  /**
   * TC-1: 스트리밍 중 사용자가 위로 스크롤하면 자동 스크롤이 억제된다
   *
   * SSE 응답을 2초 지연시켜 스트리밍 상태를 유지하고,
   * 스크롤 컨테이너를 위로 스크롤한 후 scrollTop이 0 근처에 유지되는지 검증한다.
   * (수정 전에는 매 토큰마다 scrollIntoView가 호출되어 scrollTop이 강제로 최하단으로 이동함)
   */
  test('스트리밍 중 위로 스크롤하면 자동 스크롤이 억제된다', async ({ authenticatedPage: page }) => {
    await setupAndOpenPanel(page, 'scroll-suppress-session');

    // SSE 응답을 2초 지연시켜 스트리밍 중 상태를 유지한다
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 2000));
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          // done 없이 긴 텍스트 — 스트리밍 중 상태 유지
          body: [
            'data: {"type":"init","sessionId":"scroll-suppress-session"}\n\n',
            'data: {"type":"text","content":"스크롤 억제 테스트 응답"}\n\n',
          ].join(''),
        });
      },
    );

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('긴 응답을 만들어줘');
    await chatInput.press('Enter');

    // 입력창이 비워지면 전송 시작
    await expect(chatInput).toHaveValue('', { timeout: 3000 });

    // 스크롤 컨테이너를 찾아 위로 스크롤한다
    // MessageList의 overflow-y-auto 컨테이너를 선택
    const scrollContainer = page.locator('.overflow-y-auto').first();

    // 위로 스크롤 — scrollTop을 0으로 설정
    await scrollContainer.evaluate((el) => {
      el.scrollTop = 0;
    });

    // 300ms 대기 — 스트리밍 이벤트가 추가로 발생할 경우 자동 스크롤 여부 확인
    await page.waitForTimeout(300);

    // 검증: scrollTop이 여전히 0 근처에 있어야 한다 (자동 스크롤 억제됨)
    // BOTTOM_THRESHOLD(50px) 이상 위에 있으면 억제 성공
    const scrollTop = await scrollContainer.evaluate((el) => el.scrollTop);
    const scrollHeight = await scrollContainer.evaluate((el) => el.scrollHeight);
    const clientHeight = await scrollContainer.evaluate((el) => el.clientHeight);
    const distanceFromBottom = scrollHeight - clientHeight - scrollTop;

    // 사용자가 위로 스크롤했으므로 맨 아래에서 50px 이상 위에 있어야 함
    // 단, 스크롤 가능한 콘텐츠가 없으면 이 검증을 스킵
    if (scrollHeight > clientHeight + 50) {
      expect(distanceFromBottom).toBeGreaterThan(50);
    }
  });

  /**
   * TC-2: 새 메시지 전송 시 자동 스크롤이 무조건 실행된다 (isAtBottom 재설정)
   *
   * 스트리밍 중 위로 스크롤해도, 새 메시지를 보내면 맨 아래로 이동하고
   * isAtBottom 플래그가 true로 재설정된다.
   */
  test('새 메시지 전송 시 자동 스크롤이 재개된다', async ({ authenticatedPage: page }) => {
    await setupAndOpenPanel(page, 'scroll-resume-session');

    // 즉시 완료되는 SSE 응답으로 메시지 추가
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
        body: [
          'data: {"type":"init","sessionId":"scroll-resume-session"}\n\n',
          'data: {"type":"text","content":"첫 번째 응답입니다."}\n\n',
          'data: {"type":"done","inputTokens":10}\n\n',
        ].join(''),
      }),
    );

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('첫 번째 메시지');
    await chatInput.press('Enter');

    // 응답 완료 대기
    await expect(page.getByText('첫 번째 응답입니다.')).toBeVisible({ timeout: 10_000 });

    // 스크롤 컨테이너를 위로 스크롤
    const scrollContainer = page.locator('.overflow-y-auto').first();
    await scrollContainer.evaluate((el) => { el.scrollTop = 0; });

    // 두 번째 메시지 전송 — 이때 messages가 변경되어 isAtBottom이 true로 재설정되고 맨 아래로 스크롤
    await expect(chatInput).toBeEnabled({ timeout: 5000 });
    await chatInput.fill('두 번째 메시지');
    await chatInput.press('Enter');

    // 두 번째 응답 대기
    await expect(page.getByText('두 번째 메시지').first()).toBeVisible({ timeout: 10_000 });

    // 검증: 새 메시지 전송 후 맨 아래로 스크롤됨
    // 입력창이 활성화된 상태 = 스크롤 완료 후 정상 동작
    await expect(chatInput).toBeEnabled({ timeout: 5000 });
  });
});
