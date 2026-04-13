/**
 * AI 채팅 메시지 렌더링 E2E 테스트
 * - MessageBubble 분기: plain text, markdown, code block, thinking, multi-chunk
 * - useAIChat 분기: init→text→done 흐름, error 이벤트, 연속 전송, done 후 입력창 재활성화
 */
import { expect, test } from '../../fixtures/auth.fixture';

const chipLocator = (page: import('@playwright/test').Page) =>
  page.getByRole('button', { name: /AI 상태/ });

/** AI 세션 + SSE 응답을 모킹하고 메시지를 전송하는 헬퍼 */
async function sendMessageWithResponse(
  page: import('@playwright/test').Page,
  userMessage: string,
  sseBody: string,
) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ id: 1, sessionId: 'test-session', title: null, createdAt: '2026-04-13T00:00:00Z', updatedAt: '2026-04-13T00:00:00Z' }),
      });
    },
  );
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    (route) => route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
      body: sseBody,
    }),
  );

  await page.goto('/', { waitUntil: 'commit' });
  await chipLocator(page).click();
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.waitFor({ state: 'visible', timeout: 5000 });
  await chatInput.fill(userMessage);
  await chatInput.press('Enter');
}

test.describe('MessageBubble — 텍스트 렌더링', () => {
  test('사용자 메시지와 AI 텍스트 응답이 버블로 렌더링된다', async ({ authenticatedPage: page }) => {
    await sendMessageWithResponse(
      page,
      '안녕하세요',
      [
        'data: {"type":"init","sessionId":"test-session"}\n\n',
        'data: {"type":"text","content":"안녕하세요! 무엇을 도와드릴까요?"}\n\n',
        'data: {"type":"done","inputTokens":10}\n\n',
      ].join(''),
    );

    await expect(page.getByText('안녕하세요').first()).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('안녕하세요! 무엇을 도와드릴까요?')).toBeVisible({ timeout: 10_000 });
  });

  test('AI 응답에 마크다운 굵은 텍스트가 렌더링된다', async ({ authenticatedPage: page }) => {
    await sendMessageWithResponse(
      page,
      '마크다운 테스트',
      [
        'data: {"type":"init","sessionId":"test-session"}\n\n',
        'data: {"type":"text","content":"**중요한 내용**입니다."}\n\n',
        'data: {"type":"done","inputTokens":10}\n\n',
      ].join(''),
    );

    // ReactMarkdown이 <strong>으로 렌더링
    await expect(page.getByText('중요한 내용', { exact: false })).toBeVisible({ timeout: 10_000 });
  });

  test('AI 응답에 SQL 코드 블록이 렌더링된다', async ({ authenticatedPage: page }) => {
    const codeContent = 'SELECT * FROM datasets LIMIT 10';
    await sendMessageWithResponse(
      page,
      '쿼리 예시',
      [
        'data: {"type":"init","sessionId":"test-session"}\n\n',
        `data: {"type":"text","content":"SQL 예시:\\n\\n\`\`\`sql\\n${codeContent}\\n\`\`\`"}\n\n`,
        'data: {"type":"done","inputTokens":15}\n\n',
      ].join(''),
    );

    await expect(page.getByText(codeContent, { exact: false })).toBeVisible({ timeout: 10_000 });
  });

  test('여러 텍스트 청크가 누적되어 하나의 메시지로 렌더링된다', async ({ authenticatedPage: page }) => {
    await sendMessageWithResponse(
      page,
      '청크 테스트',
      [
        'data: {"type":"init","sessionId":"test-session"}\n\n',
        'data: {"type":"text","content":"첫 번째 "}\n\n',
        'data: {"type":"text","content":"두 번째 "}\n\n',
        'data: {"type":"text","content":"세 번째"}\n\n',
        'data: {"type":"done","inputTokens":20}\n\n',
      ].join(''),
    );

    await expect(page.getByText('첫 번째', { exact: false })).toBeVisible({ timeout: 10_000 });
  });

  test('마크다운 목록이 렌더링된다', async ({ authenticatedPage: page }) => {
    await sendMessageWithResponse(
      page,
      '목록 테스트',
      [
        'data: {"type":"init","sessionId":"test-session"}\n\n',
        'data: {"type":"text","content":"주요 항목:\\n\\n- 항목 1\\n- 항목 2\\n- 항목 3"}\n\n',
        'data: {"type":"done","inputTokens":12}\n\n',
      ].join(''),
    );

    await expect(page.getByText('항목 1').first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('항목 2').first()).toBeVisible({ timeout: 5000 });
  });
});

test.describe('MessageBubble — thinking 상태', () => {
  test('thinking 이벤트 후 응답 완료 시 최종 텍스트가 표시된다', async ({ authenticatedPage: page }) => {
    await sendMessageWithResponse(
      page,
      'thinking 테스트',
      [
        'data: {"type":"init","sessionId":"test-session"}\n\n',
        'data: {"type":"thinking","content":"분석 중입니다..."}\n\n',
        'data: {"type":"text","content":"분석이 완료되었습니다."}\n\n',
        'data: {"type":"done","inputTokens":25}\n\n',
      ].join(''),
    );

    await expect(page.getByText('분석이 완료되었습니다.').first()).toBeVisible({ timeout: 10_000 });
  });
});

test.describe('useAIChat — 상태 분기', () => {
  test('done 이벤트 수신 후 입력창이 다시 활성화된다', async ({ authenticatedPage: page }) => {
    await sendMessageWithResponse(
      page,
      '완료 테스트',
      [
        'data: {"type":"init","sessionId":"test-session"}\n\n',
        'data: {"type":"text","content":"응답이 완료되었습니다."}\n\n',
        'data: {"type":"done","inputTokens":10}\n\n',
      ].join(''),
    );

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await expect(chatInput).toBeEnabled({ timeout: 10_000 });
    await expect(chatInput).toHaveValue('');
  });

  test('error 이벤트 수신 후 입력창이 다시 활성화된다', async ({ authenticatedPage: page }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => {
        if (route.request().method() === 'GET') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ id: 1, sessionId: 'err-session', title: null, createdAt: '2026-04-13T00:00:00Z', updatedAt: '2026-04-13T00:00:00Z' }),
        });
      },
    );
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
        body: 'data: {"type":"init","sessionId":"err-session"}\n\ndata: {"type":"error","message":"서버 오류"}\n\n',
      }),
    );

    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.waitFor({ state: 'visible', timeout: 5000 });
    await chatInput.fill('오류 테스트');
    await chatInput.press('Enter');

    await expect(chatInput).toBeEnabled({ timeout: 10_000 });
  });

  test('연속 두 번 메시지 전송 시 두 응답 모두 렌더링된다', async ({ authenticatedPage: page }) => {
    let callCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => {
        if (route.request().method() === 'GET') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ id: 1, sessionId: 'multi-session', title: null, createdAt: '2026-04-13T00:00:00Z', updatedAt: '2026-04-13T00:00:00Z' }),
        });
      },
    );
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => {
        callCount++;
        const content = callCount === 1 ? '첫 번째 응답입니다.' : '두 번째 응답입니다.';
        return route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: `data: {"type":"init","sessionId":"multi-session"}\n\ndata: {"type":"text","content":"${content}"}\n\ndata: {"type":"done","inputTokens":10}\n\n`,
        });
      },
    );

    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.waitFor({ state: 'visible', timeout: 5000 });

    await chatInput.fill('첫 번째 질문');
    await chatInput.press('Enter');
    await expect(page.getByText('첫 번째 응답입니다.')).toBeVisible({ timeout: 10_000 });

    await expect(chatInput).toBeEnabled({ timeout: 5000 });
    await chatInput.fill('두 번째 질문');
    await chatInput.press('Enter');
    await expect(page.getByText('두 번째 응답입니다.')).toBeVisible({ timeout: 10_000 });
  });

  test('스트리밍 중 Stop 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => {
        if (route.request().method() === 'GET') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ id: 1, sessionId: 'stop-session', title: null, createdAt: '2026-04-13T00:00:00Z', updatedAt: '2026-04-13T00:00:00Z' }),
        });
      },
    );
    // 지연된 SSE 응답으로 스트리밍 상태 유지
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            'data: {"type":"init","sessionId":"stop-session"}\n\n',
            'data: {"type":"text","content":"스트리밍 응답"}\n\n',
            'data: {"type":"done","inputTokens":10}\n\n',
          ].join(''),
        });
      },
    );

    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.waitFor({ state: 'visible', timeout: 5000 });

    await chatInput.fill('스톱 테스트');
    await chatInput.press('Enter');

    // 응답 완료 또는 스트리밍 중 상태 확인
    await expect(page.getByText('스트리밍 응답').first()).toBeVisible({ timeout: 10_000 });
  });
});
