/**
 * AI 챗 인라인 차트(show_chart) — 「차트로 저장」 config 정규화·서버 사유 표시 E2E (WD-413)
 *
 * AI 가 조립한 config 는 타입이 어긋날 수 있다(showLegend: 'yes', bins: 0 등). 서버가 이제 config 타입을 검사하므로
 * 저장 전에 화면과 같은 모양으로 맞춰 보내고, 그래도 거절되면 서버가 준 사유를 토스트로 보인다.
 * SSE/세션/패널 헬퍼는 chart-widget-missing-columns.spec.ts 패턴을 따른다.
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

/** 챗 SSE·세션·쿼리 저장 API 를 모킹하고, 패널을 열어 차트를 그린 뒤 저장 대화상자에 이름까지 넣는다. */
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
  await page.route(
    (url) => url.pathname === '/api/v1/analytics/queries',
    (route) =>
      route.request().method() !== 'POST'
        ? route.fallback()
        : route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: 77 }) }),
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

test.describe('AI 챗 인라인 차트 — 저장 config 정규화 (WD-413)', () => {
  test('타입이 어긋난 키는 버리고 화면과 같은 모양의 config 로 저장한다', async ({ authenticatedPage: page }) => {
    const dialog = await openSaveDialog(page);
    const chartRequest = page.waitForRequest(
      (req) => new URL(req.url()).pathname === '/api/v1/analytics/charts' && req.method() === 'POST',
    );
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/charts',
      (route) =>
        route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ id: 9, chartType: 'BAR', config: { xAxis: 'area', yAxis: ['sum'] } }),
        }),
    );

    await dialog.getByRole('button', { name: '저장', exact: true }).click();

    const body = (await chartRequest).postDataJSON();
    expect(body.savedQueryId).toBe(77);
    expect(body.config).toEqual({ xAxis: 'area', yAxis: ['sum'] });
    await expect(page.getByText('차트가 저장되었습니다.')).toBeVisible();
  });

  test('서버가 config 를 400 으로 거절하면 그 사유를 보인다', async ({ authenticatedPage: page }) => {
    const dialog = await openSaveDialog(page);
    const reason = '차트 설정 yAxis 은(는) 문자열 목록(배열)이어야 합니다.';
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/charts',
      (route) =>
        route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ status: 400, error: 'Bad Request', message: reason }),
        }),
    );

    await dialog.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByText(reason)).toBeVisible();
    // 실패하면 대화상자를 닫지 않아 다시 시도할 수 있다
    await expect(dialog).toBeVisible();
  });
});
