/**
 * AI 채팅 요청이 4xx/5xx 로 거절될 때 서버 오류 문구 표시 E2E 테스트 (WD-550)
 *
 * 결함: streamAIChat 이 non-OK 응답을 `Error('HTTP 400')` 로 바꾸며 본문을 버렸고, 채팅 UI 는 어떤 실패든
 * 「네트워크 연결에 실패했습니다」로 표시해 "메시지는 50,000자 이하로 입력하세요." 같은 검증 문구가 사용자에게 닿지 않았다.
 * 수정: ErrorResponse 본문을 읽어 필드 오류 → message 순으로 고르고, 채팅 UI 가 그 문구를 오류 말풍선에 보여 준다.
 *
 * 프로젝트 E2E 컨벤션: API 모킹 기반, 로그인은 auth.fixture 의 authenticatedPage 사용.
 */

import type { Page } from '@playwright/test';

import { mockApi } from '../../fixtures/api-mock';
import { expect, jwtFor, MOCK_TOKEN_RESPONSE, test } from '../../fixtures/auth.fixture';

/** AI 채팅 패널을 연다 (사이드 패널). */
async function openChatPanel(page: Page) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  await page
    .getByPlaceholder('메시지를 입력하세요...')
    .waitFor({ state: 'visible', timeout: 5_000 });
}

/** POST /api/v1/ai/chat 을 지정한 상태·본문으로 거절한다 */
async function mockChatRejected(page: Page, status: number, body: string, contentType = 'application/json') {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      return route.fulfill({ status, contentType, body });
    },
  );
}

/** 메시지를 입력해 전송한다 */
async function sendMessage(page: Page, text: string) {
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.fill(text);
  await chatInput.press('Enter');
}

test.describe('AI 채팅 요청 거절 시 서버 오류 문구 표시 (WD-550)', () => {
  test('400 검증 실패 — 필드 오류 문구(errors.message)를 그대로 보여 준다', async ({
    authenticatedPage: page,
  }) => {
    await mockChatRejected(
      page,
      400,
      JSON.stringify({
        status: 400,
        error: 'Bad Request',
        message: 'Validation failed',
        errors: { message: '메시지는 50,000자 이하로 입력하세요.' },
        timestamp: '2026-11-08T00:00:00Z',
        path: '/api/v1/ai/chat',
      }),
    );
    await openChatPanel(page);
    await sendMessage(page, '아주 긴 메시지');

    await expect(page.getByText('오류: 메시지는 50,000자 이하로 입력하세요.')).toBeVisible({ timeout: 5_000 });
    // 네트워크 실패 문구로 덮이지 않아야 한다
    await expect(page.getByText('네트워크 연결에 실패했습니다')).toHaveCount(0);
    // 보낸 메시지는 사라지지 않고 대화에 남는다
    await expect(page.getByText('아주 긴 메시지')).toBeVisible();
  });

  test('본문이 JSON 이 아닌 5xx — 상태 코드를 담은 일반 문구로 폴백한다', async ({
    authenticatedPage: page,
  }) => {
    await mockChatRejected(page, 502, '<html>Bad Gateway</html>', 'text/html');
    await openChatPanel(page);
    await sendMessage(page, '안녕');

    await expect(page.getByText('오류: 요청을 처리하지 못했습니다 (HTTP 502)')).toBeVisible({ timeout: 5_000 });
  });

  // WD-1142: 스트림을 여는 요청의 갱신이 「다른 사용자 로그인」으로 끝나면 네트워크 실패가 아니라 바뀜 문구를 남긴다.
  test('채팅 스트림 중 다른 사용자 로그인이 감지되면 네트워크 실패가 아니라 바뀜 문구를 남긴다 (WD-1142)', async ({
    authenticatedPage: page,
  }) => {
    // 이 탭의 사용자는 1 — 부팅 refresh 가 sub 1 토큰을 준다(client.ts 가 사용자를 알아보려면 JWT 모양이어야 한다)
    await mockApi(page, 'POST', '/api/v1/auth/refresh', { ...MOCK_TOKEN_RESPONSE, accessToken: jwtFor('1') });
    // 떠나기 확인(스트리밍 중 beforeunload)을 취소해 화면 재열기가 막힌 탭을 남긴다
    page.on('dialog', (dialog) => void dialog.dismiss());
    await mockChatRejected(page, 401, JSON.stringify({ status: 401, message: 'expired' }));
    await openChatPanel(page);
    // 공유 refresh 쿠키가 이제 다른 사용자(2)의 것이다 — 401 뒤 갱신이 sub 2 토큰을 돌려준다
    await page.route('**/api/v1/auth/refresh', (route) =>
      route.fulfill({ json: { ...MOCK_TOKEN_RESPONSE, accessToken: jwtFor('2') } }),
    );
    await sendMessage(page, '안녕');

    await expect(page.getByText('오류: 다른 사용자로 로그인되어 화면을 다시 엽니다.')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText('네트워크 연결에 실패했습니다')).toHaveCount(0);
  });
});
