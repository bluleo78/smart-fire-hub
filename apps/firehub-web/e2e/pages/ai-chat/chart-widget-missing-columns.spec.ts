/**
 * AI 챗 인라인 차트(show_chart) — 차트 컬럼 누락 안내 E2E (WD-88)
 *
 * AI 가 조립한 차트 설정이 결과에 없는 컬럼을 가리키면(예: 결과는 area/sum 인데 축은 region/total),
 * 과거에는 빈 격자와 범례만 그렸다. 이제 차트 대신 안내와 없는 컬럼 이름을 보여 준다.
 * SSE/세션/패널 헬퍼는 table-widget-export.spec.ts 패턴을 따른다.
 */

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** show_chart tool_use 를 담은 assistant 응답 SSE 이벤트 — config 만 바꿔 정상/누락 두 경우를 만든다. */
function showChartEvents(config: { xAxis: string; yAxis: string[] }, columns: string[] = ['area', 'sum']): string[] {
  return [
    sseEvent({ type: 'init', sessionId: 'chart-widget-session-1' }),
    sseEvent({
      type: 'tool_use',
      toolName: 'mcp__firehub__show_chart',
      input: {
        title: '지역별 합계',
        sql: 'SELECT area, sum FROM t',
        chartType: 'BAR',
        config,
        columns,
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
}

/** /api/v1/ai/chat SSE 모킹 — 어떤 POST든 주어진 이벤트를 돌려준다. */
async function mockChat(page: Page, events: string[]) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      await route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' },
        body: events.join(''),
      });
    },
  );
}

/** AI 세션 목록/생성 API 모킹 (table-widget-export.spec.ts 패턴). */
async function mockAiSessions(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1,
          sessionId: 'chart-widget-session-1',
          title: null,
          createdAt: '2026-10-20T00:00:00Z',
          updatedAt: '2026-10-20T00:00:00Z',
        }),
      });
    },
  );
}

/** 챗 패널을 열고 메시지를 보내 인라인 차트 위젯 헤더가 그려질 때까지 기다린다. */
async function showChart(page: Page) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.waitFor({ state: 'visible', timeout: 5000 });
  await chatInput.fill('지역별 합계 차트로 보여줘');
  await chatInput.press('Enter');
  await expect(page.getByTestId('inline-chart-title')).toHaveText('지역별 합계', { timeout: 10_000 });
}

test.describe('AI 챗 인라인 차트 — 차트 컬럼 누락 안내 (WD-88)', () => {
  test('설정 컬럼이 결과에 없으면 차트 대신 안내와 없는 컬럼 이름을 보여 준다', async ({ authenticatedPage: page }) => {
    await mockChat(page, showChartEvents({ xAxis: 'region', yAxis: ['total'] }));
    await mockAiSessions(page);
    await showChart(page);

    const panel = page.getByTestId('ai-side-panel');
    const notice = panel.getByTestId('chart-missing-columns');
    await expect(notice).toBeVisible();
    await expect(notice).toContainText('차트에 쓰는 컬럼이 쿼리 결과에 없습니다');
    // AI 챗에는 저장된 쿼리·차트 편집이 없으므로 해결 문구는 AI 에게 다시 요청하라는 문구다
    await expect(notice).toContainText('차트 설정의 컬럼 이름이 결과와 맞지 않습니다. AI에게 차트를 다시 그려 달라고 요청하세요.');
    await expect(notice).not.toContainText('차트 편집에서 축을 다시 고르세요');
    await expect(notice.locator('code')).toHaveText(['region', 'total']);
    await expect(panel.locator('.recharts-surface')).toHaveCount(0);
    // 깨진 설정이 저장되지 않게 「차트로 저장」을 숨긴다
    await expect(panel.getByTestId('inline-chart-sql-toggle')).toBeVisible();
    await expect(panel.getByRole('button', { name: '차트로 저장' })).toHaveCount(0);
  });

  test('columns 가 표시 라벨이어도 row 키에 설정 컬럼이 있으면 예전처럼 차트를 그린다', async ({
    authenticatedPage: page,
  }) => {
    // LLM 이 columns 를 표시 라벨로 채운 경우 — 뷰는 row 키(area/sum)로 값을 읽으므로 정상이다
    await mockChat(page, showChartEvents({ xAxis: 'area', yAxis: ['sum'] }, ['지역', '합계']));
    await mockAiSessions(page);
    await showChart(page);

    const panel = page.getByTestId('ai-side-panel');
    await expect(panel.locator('.recharts-surface').first()).toBeVisible();
    await expect(panel.getByTestId('chart-missing-columns')).toHaveCount(0);
    await expect(panel.getByRole('button', { name: '차트로 저장' })).toBeVisible();
  });

  test('설정 컬럼이 결과에 모두 있으면 안내 없이 차트를 그린다', async ({ authenticatedPage: page }) => {
    await mockChat(page, showChartEvents({ xAxis: 'area', yAxis: ['sum'] }));
    await mockAiSessions(page);
    await showChart(page);

    const panel = page.getByTestId('ai-side-panel');
    await expect(panel.locator('.recharts-surface').first()).toBeVisible();
    await expect(panel.getByTestId('chart-missing-columns')).toHaveCount(0);
    await expect(panel.getByRole('button', { name: '차트로 저장' })).toBeVisible();
  });
});
