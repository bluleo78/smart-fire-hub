/**
 * AI Chat 스트리밍 중 세션 전환 시 기존 SSE 스트림 취소 E2E 테스트
 *
 * 이슈 #209: startNewSession/loadSession 호출 시 abortControllerRef.current.abort() 미호출
 * 수정 내용: startNewSession·loadSession 진입 시 기존 SSE 스트림을 abort() 처리
 *
 * 프로젝트 E2E 컨벤션:
 * - API 모킹 기반 — 백엔드/ai-agent 없이 동작한다.
 * - SSE 스트리밍은 page.route()로 청크 단위 응답을 시뮬레이션한다.
 * - 로그인은 `auth.fixture.ts`의 `authenticatedPage`를 사용한다.
 */

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/**
 * AI 채팅 채널을 연다 (사이드 패널).
 */
async function openChatPanel(page: Page) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  await page
    .getByPlaceholder('메시지를 입력하세요...')
    .waitFor({ state: 'visible', timeout: 5_000 });
}

/** AI 세션 목록 API 모킹 — 기존 세션 1개 포함 */
async function mockAiSessionsWithOne(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            {
              id: 1,
              sessionId: 'existing-session-209',
              title: '기존 대화',
              createdAt: '2026-05-09T00:00:00Z',
              updatedAt: '2026-05-09T00:00:00Z',
            },
          ]),
        });
      }
      return route.continue();
    },
  );
}

/** 세션 메시지 조회 API 모킹 — 빈 응답 */
async function mockSessionMessages(page: Page) {
  await page.route(
    (url) => url.pathname.startsWith('/api/v1/ai/sessions/') && url.pathname.endsWith('/messages'),
    (route) => {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      });
    },
  );
}

test.describe('AI 채팅 스트리밍 중 세션 전환 abort (#209)', () => {
  /**
   * SS-01: 스트리밍 중 "새 대화" 클릭 시 기존 SSE 스트림이 abort되고
   *        새 세션의 상태가 오염되지 않아야 한다.
   *
   * 검증 방식:
   * - 느린 SSE 스트림(Promise로 중단 제어)을 mock으로 설정
   * - 스트림 진행 중 "새 대화" 버튼 클릭
   * - 이후 SSE 이벤트가 더 이상 처리되지 않는지 확인
   *   → 새 세션(빈 대화)의 메시지 영역에 이전 스트림 메시지가 나타나지 않아야 함
   */
  test('SS-01: 스트리밍 중 새 대화 시작 시 기존 스트림 메시지가 새 세션에 나타나지 않음', async ({
    authenticatedPage: page,
  }) => {
    // SSE 스트림을 인위적으로 지연시키기 위한 Promise resolve 함수
    let resolveStream!: () => void;
    const streamBlocked = new Promise<void>((resolve) => {
      resolveStream = resolve;
    });

    // 1. /api/v1/ai/chat POST 모킹 — 느린 SSE 스트림 (init 이벤트 후 대기)
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();

        // init 이벤트 전송 후 스트림을 블록킹 — 세션 전환 후 나머지 이벤트 전송
        const initEvent = sseEvent({ type: 'init', sessionId: 'stream-session-209' });

        // streamBlocked가 resolve되기 전까지 나머지 이벤트는 전송하지 않음
        await streamBlocked;

        // 스트림 전체 응답 (세션 전환 후 도달해야 하는 이벤트들)
        const lateEvents = [
          sseEvent({ type: 'text', content: '이전 세션의 오염 메시지입니다.' }),
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

    await mockAiSessionsWithOne(page);
    await mockSessionMessages(page);

    // 2. AI 패널 열기
    await openChatPanel(page);

    // 3. 메시지 전송 → 스트리밍 시작 (스트림은 블로킹 상태)
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('테스트 메시지');
    await chatInput.press('Enter');

    // 전송 후 스트리밍 상태가 시작될 때까지 대기 (중지 버튼 또는 loading indicator 등장)
    // 스트리밍 중임을 확인: isStreaming=true 상태에서 "중지" 버튼이 나타남
    await page.waitForTimeout(300);

    // 4. "새 대화" 버튼 클릭 — startNewSession 호출 → abort 발생해야 함
    const newChatButton = page.getByRole('button', { name: /새 대화|새 채팅/ }).first();
    if (await newChatButton.isVisible()) {
      await newChatButton.click();
    } else {
      // "새 대화" 버튼이 다른 형태일 경우 — Plus 아이콘 버튼 시도
      await page.getByTitle('새 대화').click();
    }

    // 5. 스트림 해제 — 이후 이벤트들이 도착 시도 (abort되어 있어야 하므로 처리 안 됨)
    resolveStream();
    await page.waitForTimeout(500);

    // 6. 핵심 검증: 새 세션(빈 대화)에 이전 스트림의 오염 메시지가 나타나지 않아야 한다
    // 메시지 목록 영역에 "이전 세션의 오염 메시지" 텍스트가 없어야 함
    await expect(page.getByText('이전 세션의 오염 메시지입니다.')).not.toBeVisible();

    // 7. 새 세션은 입력창이 활성화되어 있어야 한다
    await expect(page.getByPlaceholder('메시지를 입력하세요...')).toBeEnabled();
  });

  /**
   * SS-02: 스트리밍 중 기존 세션으로 전환 시 기존 SSE 스트림이 abort되고
   *        세션 전환 후 메시지 상태가 오염되지 않아야 한다.
   *
   * 검증 방식:
   * - 느린 SSE 스트림 진행 중 다른 세션 선택
   * - loadSession 호출 → abort 발생
   * - 선택한 세션의 빈 메시지 목록만 표시되고 이전 스트림 메시지는 없어야 함
   */
  test('SS-02: 스트리밍 중 다른 세션 선택 시 기존 스트림 메시지가 새 세션에 나타나지 않음', async ({
    authenticatedPage: page,
  }) => {
    let resolveStream!: () => void;
    const streamBlocked = new Promise<void>((resolve) => {
      resolveStream = resolve;
    });

    // 느린 SSE 스트림 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();

        await streamBlocked;

        const lateEvents = [
          sseEvent({ type: 'text', content: '세션 전환 후 오염 메시지입니다.' }),
          sseEvent({ type: 'done', inputTokens: 100 }),
        ];

        return route.fulfill({
          status: 200,
          headers: {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
          },
          body: sseEvent({ type: 'init', sessionId: 'stream-session-209b' }) + lateEvents.join(''),
        });
      },
    );

    await mockAiSessionsWithOne(page);
    await mockSessionMessages(page);

    // AI 패널 열기
    await openChatPanel(page);

    // 메시지 전송 → 스트리밍 시작
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('두 번째 테스트');
    await chatInput.press('Enter');
    await page.waitForTimeout(300);

    // 기존 세션으로 전환 (드롭다운 → 세션 선택)
    const sessionSwitcher = page.getByRole('button', { name: /대화 선택/ });
    if (await sessionSwitcher.isVisible()) {
      await sessionSwitcher.click();
      await page.getByRole('menuitem', { name: /기존 대화/ }).first().click();
    }

    // 스트림 해제
    resolveStream();
    await page.waitForTimeout(500);

    // 핵심 검증: 선택한 세션에 이전 스트림 오염 메시지가 없어야 함
    await expect(page.getByText('세션 전환 후 오염 메시지입니다.')).not.toBeVisible();
  });
});
