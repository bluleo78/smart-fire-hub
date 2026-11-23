/**
 * 빠른 입력 미전송 글 보존 E2E (WD-1029)
 *
 * AI 상태 칩 빠른 입력은 보내는 순간 패널을 열고 사라지므로, 보내지 못했을 때(sendMessage → false)
 *    글을 입력창에 남겨야 한다. 재현 경로: 응답 중단 취소(cancel)가 가는 중에 빠른 입력으로 보내고,
 *    취소가 끝나기 전에 다른 대화를 열면 sendMessage 가 false 로 끝난다.
 */

import type { Page } from '@playwright/test';

import { createMessage } from '../../factories/ai-insight.factory';
import { createAISession } from '../../factories/ai-session.factory';
import { expect, test } from '../../fixtures/auth.fixture';

const SESSIONS = [
  createAISession({ id: 1, sessionId: 'session-1', title: '이전 대화 1' }),
  createAISession({ id: 2, sessionId: 'session-2', title: '이전 대화 2' }),
];

/** 세션 목록을 모킹한다. */
async function mockSessions(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) =>
      route.request().method() === 'GET'
        ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SESSIONS) })
        : route.continue(),
  );
}

/** 대화 선택 목록에서 제목으로 대화를 연다. */
async function openSession(page: Page, title: string) {
  await page.getByRole('button', { name: /대화 선택|이전 대화/ }).first().click();
  await page.getByRole('dialog', { name: '대화 선택' }).getByRole('button', { name: title, exact: true }).click();
}

test.describe('AI 빠른 입력 미전송 글 보존 (WD-1029)', () => {
  test('빠른 입력이 보내지지 못하면 그 글이 패널 입력창에 남는다', async ({ authenticatedPage: page }) => {
    await mockSessions(page);
    const chatBodies: unknown[] = [];
    let releaseChat: () => void = () => {};
    const chatGate = new Promise<void>((r) => { releaseChat = r; });
    let releaseCancel: () => void = () => {};
    const cancelGate = new Promise<void>((r) => { releaseCancel = r; });
    let cancelRequests = 0;

    for (const id of ['session-1', 'session-2']) {
      await page.route(
        (url) => url.pathname === `/api/v1/ai/sessions/${id}/messages`,
        (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
      );
    }
    // 첫 응답은 붙잡아 둬 스트리밍 상태를 유지한다(중단되면 연결이 끊겨 fulfill 이 실패해도 무시)
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        chatBodies.push(route.request().postDataJSON());
        await chatGate;
        await route.fulfill({
          status: 200,
          headers: { 'content-type': 'text/event-stream' },
          body: 'data: {"type":"done","inputTokens":1,"outputTokens":1}\n\n',
        }).catch(() => {});
      },
    );
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat/session-1/cancel',
      async (route) => {
        cancelRequests += 1;
        await cancelGate;
        await route.fulfill({ status: 200, body: '' });
      },
    );

    await page.goto('/', { waitUntil: 'commit' });
    await page.getByRole('button', { name: /AI 상태/ }).click();
    const input = page.getByPlaceholder('메시지를 입력하세요...');
    await input.waitFor({ state: 'visible', timeout: 5000 });
    await openSession(page, '이전 대화 1');
    await input.fill('첫 질문');
    await input.press('Enter');
    await expect.poll(() => chatBodies.length).toBe(1);

    // 응답을 중단하면 서버 취소가 가는 중(붙잡힘)이다
    await page.getByRole('button', { name: '응답 중단' }).click();
    await expect.poll(() => cancelRequests).toBe(1);

    // 패널을 닫고 빠른 입력으로 보낸다 — 취소를 기다리며 패널이 열린다
    await page.keyboard.press('Control+k');
    await page.mouse.move(0, 0);
    await page.getByRole('button', { name: /AI 상태/ }).hover();
    const quick = page.getByPlaceholder('AI에게 질문하기...');
    await quick.waitFor({ state: 'visible', timeout: 5000 });
    await quick.fill('빠른 질문');
    await quick.press('Enter');

    // 취소가 끝나기 전에 다른 대화를 연다 → 빠른 질문은 보내지지 못한다
    const panelInput = page.getByPlaceholder('메시지를 입력하세요...');
    await panelInput.waitFor({ state: 'visible', timeout: 5000 });
    await openSession(page, '이전 대화 2');
    releaseCancel();

    await expect(page.getByText('대화가 바뀌어 보내지 않았습니다. 다시 보내 주세요.')).toBeVisible();
    await expect(panelInput).toHaveValue('빠른 질문');
    expect(chatBodies).toHaveLength(1);
    releaseChat();
  });

  test('이력 조회가 404 로 실패하면 보관한 AI 에게 물어보기 질문을 보내지 않고 알린다', async ({
    authenticatedPage: page,
  }) => {
    await mockSessions(page);
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/messages',
      (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([createMessage({ title: '일일 리포트' })]) }),
    );
    let release: () => void = () => {};
    const gate = new Promise<void>((r) => { release = r; });
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions/session-1/messages',
      async (route) => {
        await gate;
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ message: '대화를 찾을 수 없습니다.' }) });
      },
    );
    const chatBodies: unknown[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => {
        chatBodies.push(route.request().postDataJSON());
        return route.fulfill({ status: 200, headers: { 'content-type': 'text/event-stream' }, body: 'data: {"type":"done","inputTokens":1,"outputTokens":1}\n\n' });
      },
    );

    await page.goto('/', { waitUntil: 'commit' });
    await page.getByRole('button', { name: /AI 상태/ }).click();
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });
    await openSession(page, '이전 대화 1');

    await page.getByRole('button', { name: 'AI 인사이트 알림' }).click();
    await page.getByText('일일 리포트').first().click();
    await page.getByRole('button', { name: 'AI에게 물어보기' }).click();
    await expect(page.getByRole('button', { name: 'AI에게 물어보기' })).toHaveCount(0);
    expect(chatBodies).toHaveLength(0);

    release();
    await expect(page.getByText('대화를 불러오지 못해 「AI 에게 물어보기」 질문을 보내지 않았습니다.')).toBeVisible();
    // 안내가 뜬 뒤(이력 응답 처리 뒤)에도 chat 요청이 없어야 한다
    expect(chatBodies).toHaveLength(0);
  });
});
