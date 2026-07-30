/**
 * AI 채팅 아이콘 버튼 접근 가능한 이름 E2E 테스트 (이슈 #341)
 *
 * 결함: 닫기·전송·중단 3개 아이콘 전용 버튼에 aria-label·title·시각적 텍스트가 전부 없어
 * 스크린리더가 "버튼"으로만 읽었다. 같은 줄의 `사이드 패널`·`파일 첨부`는 title을 갖고 있었다.
 *
 * 회귀 방지 포인트:
 * 1. 세 버튼이 각각 고유한 접근 가능한 이름으로 role 조회에 잡힌다.
 * 2. 패널 안에 "이름 없는 버튼"이 0개다 — 개별 이름 검사만으로는 누락 재발을 못 잡는다.
 * 3. 이름이 붙은 뒤에도 원래 동작(닫기)이 그대로다.
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('AI 채팅 아이콘 버튼 접근 가능한 이름 (이슈 #341)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
  });

  test('닫기·전송 버튼에 이름이 있고, 패널에 이름 없는 버튼이 남지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/', { waitUntil: 'commit' });

    const chip = page.getByRole('button', { name: /AI 상태/ });
    await chip.focus();
    await page.keyboard.press('Control+k');

    const panel = page.getByTestId('ai-side-panel');
    await expect(panel).not.toHaveAttribute('inert', /.*/);

    await expect(panel.getByRole('button', { name: 'AI 어시스턴트 닫기' })).toBeVisible();
    await expect(panel.getByRole('button', { name: '메시지 전송' })).toBeVisible();

    // 개별 검사만으로는 새로 추가되는 아이콘 버튼의 누락을 못 잡는다 — 전수로 고정한다.
    const unnamed = await panel.evaluate((el) =>
      [...el.querySelectorAll('button')].filter(
        (b) =>
          !b.textContent?.trim() && !b.getAttribute('aria-label') && !b.getAttribute('title'),
      ).length,
    );
    expect(unnamed).toBe(0);

    // 이름만 붙이고 동작을 바꾸지 않았는지 확인한다.
    await panel.getByRole('button', { name: 'AI 어시스턴트 닫기' }).click();
    await expect(panel).toHaveAttribute('inert', /.*/);
  });

  test('스트리밍 중 교체되는 중단 버튼에도 이름이 있다', async ({ authenticatedPage: page }) => {
    // 세션 생성(POST)까지 모킹해야 채팅 전송이 진행된다(ai-stop-streaming.spec.ts와 동일 패턴).
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body:
            route.request().method() === 'GET'
              ? JSON.stringify([])
              : JSON.stringify({
                  id: 1,
                  sessionId: 'test-session-341',
                  title: null,
                  createdAt: '2026-04-24T00:00:00Z',
                  updatedAt: '2026-04-24T00:00:00Z',
                }),
        }),
    );

    // 응답을 지연시켜 isStreaming=true 구간(전송→중단 토글)을 고정한다.
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 3000));
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: 'data: {"type":"done"}\n\n',
        });
      },
    );

    await page.goto('/', { waitUntil: 'commit' });
    await page.getByRole('button', { name: /AI 상태/ }).click();

    const panel = page.getByTestId('ai-side-panel');
    const input = panel.getByPlaceholder('메시지를 입력하세요...');
    await input.fill('hello');
    await panel.getByRole('button', { name: '메시지 전송' }).click();

    // 전송 버튼이 중단 버튼으로 교체되며, 새 버튼도 이름을 가져야 한다.
    await expect(panel.getByRole('button', { name: '응답 중단' })).toBeVisible();
  });
});
