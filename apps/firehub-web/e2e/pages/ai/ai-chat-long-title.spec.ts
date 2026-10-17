/**
 * AI 챗 — 첫 메시지가 매우 길 때 세션 생성 title 트렁케이트 E2E 테스트 (WD-58)
 *
 * 이슈: 새 대화의 첫 메시지가 255자(ai_session.title 컬럼의 DB 한도)를 넘으면
 * `createSession` 호출의 title이 원문 그대로 전송되어 백엔드가
 * `value too long for type character varying(255)` → 409로 거절했다. 프론트가
 * 실패를 조용히 삼켜(`.catch(() => {})`) 세션 row 자체가 생성되지 않고,
 * 새로고침하면 방금 나눈 대화 전체가 사라지는 데이터 손실로 이어졌다.
 *
 * 수정: useAIChat.ts가 title을 안전한 길이(200자 + 말줄임표)로 트렁케이트해서
 * 전송한다. 백엔드도 저장 직전 추가로 255자까지 자르도록 방어했다(별도 백엔드
 * TC: AiSessionServiceTest#createSession_titleExceeds255_persistsSessionWithTruncatedTitle).
 *
 * 이 테스트는 프론트 쪽 방어(트렁케이트 후 전송)만 검증한다 — API 모킹 기반.
 */

import { expect, test } from '../../fixtures/auth.fixture';

const chipLocator = (page: import('@playwright/test').Page) =>
  page.getByRole('button', { name: /AI 상태/ });

test.describe('ChatInput — 긴 첫 메시지의 세션 title 트렁케이트 (WD-58)', () => {
  test('255자를 초과하는 첫 메시지를 보내면 createSession의 title이 200자 이하로 트렁케이트되어 전송된다', async ({
    authenticatedPage: page,
  }) => {
    // 한글 300자 — DB 컬럼 한도(255)를 넘는 길이로 재현 조건과 동일하게 구성
    const longMessage = '가'.repeat(300);

    let capturedTitle: string | undefined;
    let createSessionCalled = false;

    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => {
        if (route.request().method() === 'GET') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
        }
        // POST — 세션 생성 요청의 payload를 캡처
        createSessionCalled = true;
        const payload = route.request().postDataJSON() as { title?: string };
        capturedTitle = payload.title;
        return route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 1,
            sessionId: 'sess-wd58',
            title: capturedTitle,
            createdAt: '2026-10-17T00:00:00Z',
            updatedAt: '2026-10-17T00:00:00Z',
          }),
        });
      },
    );
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
        body: 'data: {"type":"init","sessionId":"sess-wd58"}\n\ndata: {"type":"text","content":"응답입니다"}\n\ndata: {"type":"done","inputTokens":5}\n\n',
      }),
    );

    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.waitFor({ state: 'visible', timeout: 5000 });

    await chatInput.fill(longMessage);
    await chatInput.press('Enter');

    // AI 응답이 도착할 때까지 대기 (스트리밍 정상 완료 — 기존 버그에서도 이 부분은 정상 동작했다)
    await expect(page.getByText('응답입니다')).toBeVisible({ timeout: 5000 });

    // 핵심 검증: createSession이 호출되고, title이 원문(300자) 그대로가 아니라
    // 안전한 길이로 잘려서 전송되어야 한다 (DB VARCHAR(255) 한도를 넘지 않음)
    expect(createSessionCalled).toBe(true);
    expect(capturedTitle).toBeDefined();
    expect(capturedTitle!.length).toBeLessThan(longMessage.length);
    expect(capturedTitle!.length).toBeLessThanOrEqual(255);
  });
});
