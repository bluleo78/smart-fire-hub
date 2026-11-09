/**
 * AI 채팅 요청 한도·토큰 만료 처리 E2E 테스트 (WD-631)
 *
 * 1) 메시지 길이 상한: 서버(ChatRequest)는 50,000자를 넘으면 400 으로 거절한다. 예전에는 웹이 상한을 몰라 보내고 나서야
 *    서버 문구를 봤다 → 이제 입력창 아래에 현재 길이를 담은 안내를 보이고 전송을 막는다(글은 자르지 않는다).
 * 2) 액세스 토큰 만료: 채팅은 fetch 로 직접 보내 axios 인터셉터의 401 → refresh → 재시도를 받지 못해, 토큰이 만료되면
 *    채팅이 「HTTP 401」 오류로 끝났다 → 스트림이 열리기 전 401 이면 같은 갱신 경로로 갱신하고 한 번만 다시 보낸다.
 *
 * 프로젝트 E2E 컨벤션: API 모킹 기반, 로그인은 auth.fixture 의 authenticatedPage 사용.
 */

import type { Page, Route } from '@playwright/test';

import { MOCK_TOKEN_RESPONSE } from '../../fixtures/auth.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

const SESSION_ID = 'wd631-session';
/** 갱신 후 받는 새 액세스 토큰 — 재시도 요청이 이 토큰을 싣는지 본다 */
const REFRESHED_TOKEN = 'wd631-refreshed-access-token';

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** 200 SSE 응답으로 이벤트 목록을 돌려준다 */
function fulfillSse(route: Route, events: Record<string, unknown>[]) {
  return route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    body: events.map(sseEvent).join(''),
  });
}

/** 401 응답 — api 의 인증 실패 본문 형태 */
function fulfill401(route: Route) {
  return route.fulfill({
    status: 401,
    contentType: 'application/json',
    body: JSON.stringify({ status: 401, error: 'Unauthorized', message: '인증이 필요합니다.' }),
  });
}

/** AI 채팅 패널을 연다 (사이드 패널) */
async function openChatPanel(page: Page) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5_000 });
}

/** 세션 생성(POST /ai/sessions)은 init 수신 시 호출된다 — 성공으로 모킹 */
async function mockSessionCreate(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) =>
      route.request().method() === 'POST'
        ? route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
        : route.fallback(),
  );
}

/**
 * 채팅 패널이 열린 뒤(부팅 refresh 이후)의 토큰 갱신 호출을 센다 — 새 토큰을 돌려준다.
 * 부팅 refresh 는 fixture 의 기본 모킹이 처리하므로, 이 함수는 패널을 연 다음에 부른다(나중 등록이 이긴다).
 */
async function countRefreshes(page: Page): Promise<{ count: number }> {
  const counter = { count: 0 };
  await page.route(
    (url) => url.pathname === '/api/v1/auth/refresh',
    (route) => {
      counter.count++;
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ...MOCK_TOKEN_RESPONSE, accessToken: REFRESHED_TOKEN }),
      });
    },
  );
  return counter;
}

test.describe('AI 채팅 메시지 길이 상한 (WD-631)', () => {
  test('50,000자를 넘기면 전송이 막히고 현재 길이 안내가 보이며, 줄이면 다시 보낼 수 있다', async ({
    authenticatedPage: page,
  }) => {
    let chatCalls = 0;
    let captured: Record<string, unknown> | null = null;
    await mockSessionCreate(page);
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => {
        chatCalls++;
        captured = route.request().postDataJSON() as Record<string, unknown>;
        return fulfillSse(route, [
          { type: 'init', sessionId: SESSION_ID },
          { type: 'text', content: '받았습니다.' },
          { type: 'done', inputTokens: 10 },
        ]);
      },
    );
    await openChatPanel(page);

    const input = page.getByRole('textbox', { name: '메시지 입력' });
    const sendButton = page.getByRole('button', { name: '메시지 전송' });
    const longText = 'a'.repeat(50_001);
    await input.fill(longText);

    // 전송 버튼 비활성 + 서버 문구와 같은 표현의 안내(현재 길이 포함)
    await expect(sendButton).toBeDisabled();
    const hint = page.getByText('메시지는 50,000자 이하로 입력하세요. (현재 50,001자)');
    await expect(hint).toBeVisible();
    // 안내가 입력창의 설명으로 이어져 있다(스크린리더)
    const hintId = await hint.getAttribute('id');
    await expect(input).toHaveAttribute('aria-describedby', hintId!);

    // Enter 로도 보내지 않고, 글은 자르지 않고 그대로 둔다
    await input.press('Enter');
    await expect(input).toHaveValue(longText);
    expect(chatCalls).toBe(0);

    // 한 글자 줄이면(정확히 50,000자) 안내가 사라지고 보낼 수 있다 — 보낸 본문은 자르지 않은 원문 그대로
    await input.fill('b'.repeat(50_000));
    await expect(hint).toHaveCount(0);
    await expect(page.getByText(/자 이하로 입력하세요/)).toHaveCount(0);
    await expect(sendButton).toBeEnabled();
    await sendButton.click();

    await expect(page.getByText('받았습니다.')).toBeVisible({ timeout: 5_000 });
    expect(chatCalls).toBe(1);
    expect((captured!.message as string).length).toBe(50_000);
  });
});

test.describe('AI 채팅 액세스 토큰 만료 시 갱신 후 재시도 (WD-631)', () => {
  test('스트림 열기 전 401 → 토큰 갱신 1회 → 새 토큰으로 같은 요청을 한 번 다시 보내 응답을 받는다', async ({
    authenticatedPage: page,
  }) => {
    await mockSessionCreate(page);
    await openChatPanel(page);
    const refreshes = await countRefreshes(page);

    const calls: { authorization: string | undefined; body: Record<string, unknown> }[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => {
        calls.push({
          authorization: route.request().headers()['authorization'],
          body: route.request().postDataJSON() as Record<string, unknown>,
        });
        // 첫 요청은 만료된 토큰으로 간주해 401, 재시도는 정상 스트림
        if (calls.length === 1) return fulfill401(route);
        return fulfillSse(route, [
          { type: 'init', sessionId: SESSION_ID },
          { type: 'text', content: '갱신 후 답변입니다.' },
          { type: 'done', inputTokens: 10 },
        ]);
      },
    );

    const input = page.getByRole('textbox', { name: '메시지 입력' });
    await input.fill('토큰 만료 뒤 질문');
    await input.press('Enter');

    await expect(page.getByText('갱신 후 답변입니다.')).toBeVisible({ timeout: 5_000 });
    // 오류 말풍선(HTTP 401·인증 문구)이 남지 않는다
    await expect(page.getByText(/오류:/)).toHaveCount(0);

    expect(refreshes.count).toBe(1);
    expect(calls).toHaveLength(2);
    // 첫 요청은 기존 토큰, 재시도는 갱신된 토큰 — 본문은 같다
    expect(calls[0].authorization).toBe(`Bearer ${MOCK_TOKEN_RESPONSE.accessToken}`);
    expect(calls[1].authorization).toBe(`Bearer ${REFRESHED_TOKEN}`);
    expect(calls[1].body).toEqual(calls[0].body);
    expect(calls[1].body.message).toBe('토큰 만료 뒤 질문');
  });

  test('갱신 후 재시도도 401 이면 더 재시도하지 않고 오류로 끝낸다(한 번만 재시도)', async ({
    authenticatedPage: page,
  }) => {
    await mockSessionCreate(page);
    await openChatPanel(page);
    const refreshes = await countRefreshes(page);

    let chatCalls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => {
        chatCalls++;
        return fulfill401(route);
      },
    );

    const input = page.getByRole('textbox', { name: '메시지 입력' });
    await input.fill('계속 거절되는 질문');
    await input.press('Enter');

    // 두 번째 401 은 서버 문구 대신 로그인 만료 안내로 끝난다
    await expect(page.getByText('오류: 로그인이 만료되었습니다. 다시 로그인해 주세요.')).toBeVisible({ timeout: 5_000 });
    // 오류가 보인 뒤 잠시 기다려도 추가 요청이 없다
    await page.waitForTimeout(300);
    expect(chatCalls).toBe(2);
    expect(refreshes.count).toBe(1);
  });
});
