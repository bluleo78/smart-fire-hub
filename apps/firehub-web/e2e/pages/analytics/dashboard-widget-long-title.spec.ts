import type { Page } from '@playwright/test';

import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 위젯 — 긴 제목 말줄임 (WD-528)
 * - 위젯 머리글(CardHeader)이 grid 라 열 폭이 글자 폭까지 늘어나, 100자 차트 이름이 줄임표 없이 카드 가장자리에서 잘렸다.
 * - 이제 제목은 카드 폭 안에서 한 줄로 줄어 「…」로 끝나고, 전체 이름은 title 로 남는다.
 */
const LONG_NAME = '가'.repeat(100);

async function setup(page: Page, name: string) {
  const chart = createChart({ id: 1, name, chartType: 'TABLE', config: { xAxis: '', yAxis: [] } });
  const dashboard = createDashboard({
    id: 1,
    widgets: [createWidget({ id: 11, chartId: 1, chartName: name, chartType: 'TABLE', width: 6, height: 4 })],
  });
  await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', dashboard);
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chart);
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', {
    chart,
    queryResult: createQueryResult({ columns: ['cat'], rows: [{ cat: 'a' }], totalRows: 1 }),
  });
}

/** 제목 요소의 폭이 카드 안에 묶이고 내용이 넘쳐(=줄임표로 잘려) 보이는지 잰다 */
async function measure(page: Page, name: string) {
  const title = page.getByRole('heading', { level: 2, name });
  await expect(title).toBeVisible();
  return title.evaluate((el) => {
    const card = el.closest('[data-slot="card"]') as HTMLElement;
    const style = getComputedStyle(el);
    return {
      titleRight: el.getBoundingClientRect().right,
      cardRight: card.getBoundingClientRect().right,
      clipped: el.scrollWidth > el.clientWidth,
      ellipsis: style.textOverflow,
      tooltip: el.getAttribute('title'),
    };
  });
}

for (const [label, width] of [
  ['데스크톱 1440px', 1440],
  ['모바일 390px', 390],
] as const) {
  test(`${label}: 100자 위젯 제목이 카드 안에서 줄임표로 줄고 전체 이름은 title 로 남는다 (WD-528)`, async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await setup(page, LONG_NAME);
    await page.goto('/analytics/dashboards/1');

    const m = await measure(page, LONG_NAME);
    // 제목 오른쪽 끝이 카드 오른쪽 끝을 넘지 않는다(예전에는 글자 폭 2000px 넘게 늘어났다)
    expect(m.titleRight).toBeLessThanOrEqual(m.cardRight);
    // 내용이 넘쳐 줄임표로 잘린 상태다
    expect(m.clipped).toBe(true);
    expect(m.ellipsis).toBe('ellipsis');
    expect(m.tooltip).toBe(LONG_NAME);
  });
}

test('짧은 위젯 제목은 잘리지 않는다 (WD-528)', async ({ authenticatedPage: page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await setup(page, '매출 추이');
  await page.goto('/analytics/dashboards/1');
  const m = await measure(page, '매출 추이');
  expect(m.clipped).toBe(false);
});
