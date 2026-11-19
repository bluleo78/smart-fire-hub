/**
 * AI 챗 인라인 차트 「차트로 저장」 — 이름 중복(409 DUPLICATE_NAME) 보상·재시도 E2E (WD-824)
 *
 * 저장은 쿼리 `AI 생성: <차트 이름>` 을 먼저 만들고 차트를 만든다.
 * - 차트 생성이 409 로 실패하면 방금 만든 쿼리를 지우고(고아 방지), 서버 문구를 이름 칸 아래 오류로 보인다.
 * - 쿼리 이름이 겹치면(예전 고아 등) 「AI 생성: 이름 (2)」 처럼 번호를 붙여 다시 시도한다.
 */

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** 타입이 어긋난 키가 섞인 config 로 BAR 차트를 그리는 assistant 응답 SSE 이벤트 */
const SHOW_CHART_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'chart-save-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__show_chart',
    input: {
      title: '지역별 합계',
      sql: 'SELECT area, sum FROM t',
      chartType: 'BAR',
      config: { xAxis: 'area', yAxis: ['sum'], showLegend: 'yes', bins: 0 },
      columns: ['area', 'sum'],
      rows: [
        { area: '서울', sum: 120 },
        { area: '부산', sum: 80 },
      ],
    },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__show_chart',
    result: JSON.stringify({ displayed: true }),
    status: 'completed',
  }),
  sseEvent({ type: 'done', inputTokens: 120 }),
];

/** 챗 SSE·세션 API 를 모킹하고, 패널을 열어 차트를 그린 뒤 저장 대화상자에 이름까지 넣는다. 쿼리·차트 API 는 각 테스트가 모킹한다. */
async function openSaveDialog(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    (route) =>
      route.request().method() !== 'POST'
        ? route.fallback()
        : route.fulfill({ status: 200, headers: { 'Content-Type': 'text/event-stream' }, body: SHOW_CHART_EVENTS.join('') }),
  );
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) =>
      route.request().method() === 'GET'
        ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
        : route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              id: 1,
              sessionId: 'chart-save-session-1',
              title: null,
              createdAt: '2026-10-20T00:00:00Z',
              updatedAt: '2026-10-20T00:00:00Z',
            }),
          }),
  );
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.waitFor({ state: 'visible', timeout: 5000 });
  await chatInput.fill('지역별 합계 차트로 보여줘');
  await chatInput.press('Enter');
  await expect(page.getByTestId('inline-chart-title')).toHaveText('지역별 합계', { timeout: 10_000 });

  await page.getByTestId('ai-side-panel').getByRole('button', { name: '차트로 저장' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel(/차트 이름/).fill('지역별 합계');
  return dialog;
}


const CHART_MSG = '이미 있는 차트 이름입니다. 다른 이름을 입력하세요.';
const dupBody = (message: string) => JSON.stringify({ status: 409, error: 'Conflict', code: 'DUPLICATE_NAME', message });

test.describe('AI 챗 인라인 차트 — 저장 이름 중복 (WD-824)', () => {
  test('차트 이름이 겹쳐 409 면 방금 만든 쿼리를 지우고 이름 칸 아래에 사유를 보인다', async ({ authenticatedPage: page }) => {
    const dialog = await openSaveDialog(page);
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries',
      (route) =>
        route.request().method() !== 'POST'
          ? route.fallback()
          : route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 77 }) }),
    );
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/charts',
      (route) => route.fulfill({ status: 409, contentType: 'application/json', body: dupBody(CHART_MSG) }),
    );
    const deleteRequest = page.waitForRequest(
      (req) => new URL(req.url()).pathname === '/api/v1/analytics/queries/77' && req.method() === 'DELETE',
    );
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/77',
      (route) => route.fulfill({ status: 204 }),
    );

    await dialog.getByRole('button', { name: '저장', exact: true }).click();

    await deleteRequest;
    await expect(dialog.getByText(CHART_MSG)).toBeVisible();
    // 토스트로 같은 문구가 또 뜨지 않고, 대화상자는 닫히지 않는다
    await expect(page.getByText(CHART_MSG)).toHaveCount(1);
    await expect(dialog).toBeVisible();
  });

  test('쿼리 이름이 겹치면 번호를 붙여 다시 시도해 저장한다', async ({ authenticatedPage: page }) => {
    const dialog = await openSaveDialog(page);
    const queryNames: string[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries',
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        queryNames.push((route.request().postDataJSON() as { name: string }).name);
        return queryNames.length === 1
          ? route.fulfill({ status: 409, contentType: 'application/json', body: dupBody('이미 있는 쿼리 이름입니다.') })
          : route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 78 }) });
      },
    );
    const chartRequest = page.waitForRequest(
      (req) => new URL(req.url()).pathname === '/api/v1/analytics/charts' && req.method() === 'POST',
    );
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/charts',
      (route) => route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 9 }) }),
    );

    await dialog.getByRole('button', { name: '저장', exact: true }).click();

    const body = (await chartRequest).postDataJSON();
    expect(queryNames).toEqual(['AI 생성: 지역별 합계', 'AI 생성: 지역별 합계 (2)']);
    expect(body.savedQueryId).toBe(78);
    expect(body.name).toBe('지역별 합계');
    await expect(page.getByText('차트가 저장되었습니다.')).toBeVisible();
  });

  test('차트 요청이 응답 없이 끊기면 방금 만든 쿼리를 지우지 않는다(서버가 차트를 이미 만들었을 수 있다)', async ({ authenticatedPage: page }) => {
    const dialog = await openSaveDialog(page);
    const deletes: string[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries',
      (route) =>
        route.request().method() !== 'POST'
          ? route.fallback()
          : route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 79 }) }),
    );
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/79',
      (route) => {
        if (route.request().method() === 'DELETE') deletes.push(route.request().url());
        return route.fulfill({ status: 204 });
      },
    );
    const chartAttempt = page.waitForRequest(
      (req) => new URL(req.url()).pathname === '/api/v1/analytics/charts' && req.method() === 'POST',
    );
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/charts',
      (route) => route.abort('connectionreset'),
    );

    await dialog.getByRole('button', { name: '저장', exact: true }).click();

    await chartAttempt;
    await expect(dialog.getByRole('button', { name: '저장', exact: true })).toBeEnabled();
    expect(deletes).toHaveLength(0);
  });

  test('쿼리 생성 단계에서 이름 409 가 끝까지 이어지면 차트 이름 칸이 아니라 토스트로 알린다', async ({ authenticatedPage: page }) => {
    const dialog = await openSaveDialog(page);
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries',
      (route) =>
        route.request().method() !== 'POST'
          ? route.fallback()
          : route.fulfill({ status: 409, contentType: 'application/json', body: dupBody('이미 있는 쿼리 이름입니다.') }),
    );

    await dialog.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByText('이미 있는 쿼리 이름입니다.')).toBeVisible();
    await expect(dialog.locator('#chart-name-error')).toHaveCount(0);
  });
});
