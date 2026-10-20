/**
 * AI 챗 인라인 차트(show_chart) — 숫자 문자열 값 E2E (WD-104)
 *
 * WD-101·WD-104 이후 데이터셋 행 조회·SQL 탭 응답의 소수는 값과 상관없이 문자열("1.5")로 나간다. LLM 이 그 값을
 * show_chart.rows 에 그대로 옮기면 차트가 숫자 대신 문자열을 받는다. 위젯은 값 축 칸의 숫자 문자열만 Number 로 바꿔
 * 막대 높이·선 위치·파이 조각이 숫자 값과 같게 그려야 하고, 카테고리 축 값("007" 같은 코드)은 그대로 둬야 한다.
 * SSE/세션/패널 헬퍼는 chart-widget-missing-columns.spec.ts 패턴을 따른다.
 */

import type { Locator, Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** 값 칸(sum)이 숫자 문자열이고 카테고리 칸(code)이 숫자처럼 생긴 문자열인 행 — 비율 120:80 = 1.5 */
const STRING_ROWS = [
  { code: '007', sum: '120.5' },
  { code: '2024', sum: '80' },
];

/** show_chart tool_use 를 담은 assistant 응답 SSE 이벤트 */
function showChartEvents(chartType: 'BAR' | 'LINE' | 'PIE'): string[] {
  return [
    sseEvent({ type: 'init', sessionId: 'chart-numeric-session-1' }),
    sseEvent({
      type: 'tool_use',
      toolName: 'mcp__firehub__show_chart',
      input: {
        title: '코드별 합계',
        sql: 'SELECT code, sum FROM t',
        chartType,
        config: { xAxis: 'code', yAxis: ['sum'] },
        columns: ['code', 'sum'],
        rows: STRING_ROWS,
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

/** AI 세션 목록/생성 API 모킹 */
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
          sessionId: 'chart-numeric-session-1',
          title: null,
          createdAt: '2026-10-20T00:00:00Z',
          updatedAt: '2026-10-20T00:00:00Z',
        }),
      });
    },
  );
}

/** 챗 패널을 열고 메시지를 보내 인라인 차트가 그려질 때까지 기다린 뒤 패널을 돌려준다. */
async function showChart(page: Page, chartType: 'BAR' | 'LINE' | 'PIE'): Promise<Locator> {
  await mockChat(page, showChartEvents(chartType));
  await mockAiSessions(page);
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.waitFor({ state: 'visible', timeout: 5000 });
  await chatInput.fill('코드별 합계 차트로 보여줘');
  await chatInput.press('Enter');
  await expect(page.getByTestId('inline-chart-title')).toHaveText('코드별 합계', { timeout: 10_000 });
  const panel = page.getByTestId('ai-side-panel');
  await expect(panel.locator('.recharts-surface').first()).toBeVisible();
  return panel;
}

test.describe('AI 챗 인라인 차트 — 숫자 문자열 값 (WD-104)', () => {
  test('막대 차트: 숫자 문자열 값으로 막대 높이가 값 비율대로 그려지고 코드 라벨은 그대로다', async ({
    authenticatedPage: page,
  }) => {
    const panel = await showChart(page, 'BAR');
    const bars = panel.locator('.recharts-bar-rectangle path');
    await expect(bars).toHaveCount(2);
    // 120.5 : 80 — 숫자로 읽혔다면 높이 비율이 값 비율과 같다. recharts 가 막대를 0 에서 키우는 애니메이션을 하므로
    // 한 번 재지 않고 비율이 맞을 때까지 기다린다(애니메이션 중에는 boundingBox 가 null 일 수 있다).
    await expect
      .poll(async () => {
        const [first, second] = await Promise.all([bars.nth(0).boundingBox(), bars.nth(1).boundingBox()]);
        if (!first || !second || second.height === 0) return null;
        return Math.round((first.height / second.height) * 100) / 100;
      })
      .toBeCloseTo(120.5 / 80, 1);
    // 카테고리 축 값은 바꾸지 않는다 — "007" 이 7 로 바뀌면 안 된다
    // 첫 .recharts-surface 는 범례 아이콘이라 차트 본체(role=application)에서 축 라벨을 찾는다
    const surface = panel.getByRole('application');
    await expect(surface.getByText('007', { exact: true })).toBeVisible();
    await expect(surface.getByText('2024', { exact: true })).toBeVisible();
  });

  test('선 차트: 숫자 문자열 값으로 점 위치가 값에 맞게 그려진다', async ({ authenticatedPage: page }) => {
    const panel = await showChart(page, 'LINE');
    const dots = panel.locator('.recharts-line-dots circle');
    await expect(dots).toHaveCount(2);
    const cy = await dots.evaluateAll((els) => els.map((el) => Number(el.getAttribute('cy'))));
    // 큰 값(120.5)이 더 위(cy 작음)에 있어야 한다
    expect(cy.every((v) => Number.isFinite(v))).toBe(true);
    expect(cy[0]).toBeLessThan(cy[1]);
  });

  test('파이 차트: 숫자 문자열 값으로도 조각 두 개가 그려진다', async ({ authenticatedPage: page }) => {
    const panel = await showChart(page, 'PIE');
    const sectors = panel.locator('.recharts-pie-sector path');
    await expect(sectors).toHaveCount(2);
    // 정규화 전에는 recharts Pie 가 문자열 값을 0 으로 보아 조각이 하나도 그려지지 않았다(WD-104 실측) — 두 조각 모두 크기가 있어야 한다
    // 펼침 애니메이션 중에는 boundingBox 가 null 일 수 있어 두 조각 모두 크기가 생길 때까지 기다린다
    await expect
      .poll(async () => {
        const boxes = await Promise.all([sectors.nth(0).boundingBox(), sectors.nth(1).boundingBox()]);
        return boxes.every((box) => box !== null && box.width * box.height > 0);
      })
      .toBe(true);
  });
});
