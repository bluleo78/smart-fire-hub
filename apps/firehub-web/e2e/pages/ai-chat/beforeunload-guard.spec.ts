/**
 * AI 채팅 스트리밍 중 beforeunload 가드 E2E 테스트 (WD-56)
 *
 * 결함: 스트리밍 중 새로고침/탭 닫기/하드 네비게이션 시 경고 없이 메시지가
 * 통째로 유실됨. 수정: `useAIChat`에 isStreaming/isUploading 동안 활성화되는
 * `beforeunload` 리스너 추가.
 *
 * 검증 방식:
 * - 실제 브라우저 종료는 Playwright로 트리거할 수 없으므로, page.evaluate로
 *   `beforeunload` Event를 직접 dispatch하여 리스너가 등록되어 있는지
 *   (defaultPrevented === true) 확인한다.
 * - 스트리밍 전/후에 동일하게 dispatch하여 가드가 스트리밍 상태에 따라
 *   켜지고 꺼지는지(리스너 해제) 모두 검증한다.
 *
 * 프로젝트 E2E 컨벤션: API 모킹 기반, 로그인은 auth.fixture의 authenticatedPage 사용.
 */

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** AI 채팅 패널을 연다 (사이드 패널). */
async function openChatPanel(page: Page) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  await page
    .getByPlaceholder('메시지를 입력하세요...')
    .waitFor({ state: 'visible', timeout: 5_000 });
}

/** 현재 beforeunload 리스너가 등록되어 있는지(preventDefault 호출 여부) 확인 */
async function isBeforeUnloadGuarded(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    return event.defaultPrevented;
  });
}

test.describe('AI 채팅 스트리밍 중 beforeunload 가드 (WD-56)', () => {
  test('스트리밍 중에는 beforeunload가 막히고, 완료 후에는 해제된다', async ({
    authenticatedPage: page,
  }) => {
    // 스트리밍 시작 전에는 가드가 없어야 한다
    await openChatPanel(page);
    expect(await isBeforeUnloadGuarded(page)).toBe(false);

    let resolveStream!: () => void;
    const streamBlocked = new Promise<void>((resolve) => {
      resolveStream = resolve;
    });

    // 느린 SSE 스트림 모킹 — init 이벤트 후 대기시켜 isStreaming=true 구간을 확보
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();

        const initEvent = sseEvent({ type: 'init', sessionId: 'beforeunload-session-56' });
        await streamBlocked;

        const lateEvents = [
          sseEvent({ type: 'text', content: '응답 내용입니다.' }),
          sseEvent({ type: 'done', inputTokens: 100 }),
        ];

        return route.fulfill({
          status: 200,
          headers: {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
          },
          body: initEvent + lateEvents.join(''),
        });
      },
    );
    // 세션 생성 호출 모킹 (init 이벤트 수신 시 호출됨)
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
        }
        return route.fallback();
      },
    );

    // 메시지 전송 → 스트리밍 시작
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('beforeunload 테스트 메시지');
    await chatInput.press('Enter');

    // 핵심 검증 1: 스트리밍 중에는 beforeunload가 막혀야 한다 — 가드가 걸릴 때까지 기다린다(WD-257)
    await expect.poll(() => isBeforeUnloadGuarded(page)).toBe(true);

    // 스트림 완료
    resolveStream();

    // 핵심 검증 2: 스트리밍 종료 후에는 가드가 해제되어야 한다 — 풀릴 때까지 기다린다(WD-257)
    await expect.poll(() => isBeforeUnloadGuarded(page)).toBe(false);
  });
});
