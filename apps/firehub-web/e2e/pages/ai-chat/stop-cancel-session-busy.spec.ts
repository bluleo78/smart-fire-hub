/**
 * AI 채팅 — 응답 중단 시 서버 취소 + 같은 세션 동시 실행 거절 안내 E2E (WD-57)
 *
 * 결함: "응답 중단"이 브라우저 fetch 만 끊어 서버(ai-agent)는 작업을 계속했고, 곧바로 보낸 다음 메시지가
 * 같은 세션에서 겹쳐 실행돼 응답이 섞이거나 대화 기록의 턴이 유실됐다.
 * 수정: 중단 시 POST /api/v1/ai/chat/{sessionId}/cancel 로 서버 실행도 취소하고, 서버가 같은 세션의
 * 이전 응답 처리 중이라 거절하면(session_busy 오류 이벤트) 토스트로 안내하고 그 메시지를 대화에 남기지 않는다.
 *
 * 프로젝트 E2E 컨벤션: API 모킹 기반, 로그인은 auth.fixture 의 authenticatedPage 사용.
 */

import type { Page, Route } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

const SESSION_ID = 'wd57-session';

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

function fulfillSse(route: Route, events: Record<string, unknown>[]) {
  return route.fulfill({
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    body: events.map(sseEvent).join(''),
  });
}

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

async function send(page: Page, text: string) {
  const input = page.getByPlaceholder('메시지를 입력하세요...');
  await input.fill(text);
  await input.press('Enter');
}

test.describe('AI 채팅 응답 중단 → 서버 취소 (WD-57)', () => {
  test('스트리밍 중 "응답 중단"을 누르면 그 세션의 서버 취소 API 를 부르고 부분 응답을 보존한다', async ({
    authenticatedPage: page,
  }) => {
    await mockSessionCreate(page);
    await openChatPanel(page);

    // 1번째 메시지: 세션을 만들고 정상 완료. 2번째 메시지: 응답이 오지 않고 계속 생성 중인 상태로 멈춘다.
    let chatCalls = 0;
    let releaseHang!: () => void;
    const hang = new Promise<void>((r) => {
      releaseHang = r;
    });
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        chatCalls++;
        if (chatCalls === 1) {
          return fulfillSse(route, [
            { type: 'init', sessionId: SESSION_ID },
            { type: 'text', content: '첫 답변입니다.' },
            { type: 'done', inputTokens: 10 },
          ]);
        }
        await hang;
        return route.abort().catch(() => {});
      },
    );

    const cancelRequests: string[] = [];
    await page.route(
      (url) => /^\/api\/v1\/ai\/chat\/[^/]+\/cancel$/.test(url.pathname),
      (route) => {
        cancelRequests.push(`${route.request().method()} ${new URL(route.request().url()).pathname}`);
        return route.fulfill({ status: 204 });
      },
    );

    await send(page, '첫 질문');
    await expect(page.getByText('첫 답변입니다.')).toBeVisible();

    await send(page, '아주 긴 보고서를 써 줘');
    const stopButton = page.getByRole('button', { name: '응답 중단' });
    await expect(stopButton).toBeVisible();
    await stopButton.click();

    // 서버 취소 API 가 현재 세션 id 로 정확히 한 번 호출된다
    await expect.poll(() => cancelRequests).toEqual([`POST /api/v1/ai/chat/${SESSION_ID}/cancel`]);
    // 화면 중단: 중단 버튼이 사라지고, 보낸 메시지는 대화에 남는다
    await expect(stopButton).toBeHidden();
    await expect(page.getByText('아주 긴 보고서를 써 줘')).toBeVisible();

    releaseHang();
  });

  test('서버 취소가 실패해도(500) 화면 중단은 유지되고 바로 다음 메시지를 보낼 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await mockSessionCreate(page);
    await openChatPanel(page);
    let chatCalls = 0;
    let releaseHang!: () => void;
    const hang = new Promise<void>((r) => {
      releaseHang = r;
    });
    const sentBodies: Record<string, unknown>[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        chatCalls++;
        sentBodies.push(route.request().postDataJSON() as Record<string, unknown>);
        if (chatCalls === 2) {
          await hang;
          return route.abort().catch(() => {});
        }
        return fulfillSse(route, [
          { type: 'init', sessionId: SESSION_ID },
          { type: 'text', content: `답변 ${chatCalls}` },
          { type: 'done', inputTokens: 10 },
        ]);
      },
    );
    await page.route(
      (url) => /^\/api\/v1\/ai\/chat\/[^/]+\/cancel$/.test(url.pathname),
      (route) => route.fulfill({ status: 500, contentType: 'application/json', body: '{}' }),
    );

    await send(page, '첫 질문');
    await expect(page.getByText('답변 1')).toBeVisible();
    await send(page, '중단할 질문');
    await page.getByRole('button', { name: '응답 중단' }).click();
    await expect(page.getByRole('button', { name: '응답 중단' })).toBeHidden();

    await send(page, '다음 질문');
    await expect(page.getByText('답변 3')).toBeVisible();
    // 다음 메시지는 같은 세션으로 이어진다
    expect(sentBodies[2]).toMatchObject({ message: '다음 질문', sessionId: SESSION_ID });
    releaseHang();
  });
});

test.describe('AI 채팅 같은 세션 동시 실행 거절 안내 (WD-57)', () => {
  test('서버가 session_busy 로 거절하면 토스트로 안내하고 그 메시지는 대화에 남기지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockSessionCreate(page);
    await openChatPanel(page);
    let chatCalls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => {
        chatCalls++;
        if (chatCalls === 1) {
          return fulfillSse(route, [
            { type: 'init', sessionId: SESSION_ID },
            { type: 'text', content: '첫 답변입니다.' },
            { type: 'done', inputTokens: 10 },
          ]);
        }
        // api 프록시가 ai-agent 의 409 를 옮겨 싣는 형태
        return fulfillSse(route, [
          { type: 'error', code: 'session_busy', message: '이전 응답을 처리 중입니다. 잠시 후 다시 시도해 주세요.' },
        ]);
      },
    );

    await send(page, '첫 질문');
    await expect(page.getByText('첫 답변입니다.')).toBeVisible();

    await send(page, '겹친 메시지 B');
    await expect(page.getByText('이전 응답을 처리 중입니다. 잠시 후 다시 시도해 주세요.')).toBeVisible();
    // 처리되지 않은 메시지는 대화에 남지 않고, 일반 오류 문구("오류: ...")도 붙지 않는다
    await expect(page.getByText('겹친 메시지 B')).toHaveCount(0);
    await expect(page.getByText(/^오류:/)).toHaveCount(0);
    // 입력이 다시 가능하다
    await expect(page.getByRole('button', { name: '응답 중단' })).toBeHidden();
  });
});
