import type { Page } from '@playwright/test';

import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 게이지 한쪽 범위 설정 (WD-848)
 *
 * 최솟값 또는 최댓값만 설정하면 자동 기본값이 설정된 끝을 무시해 최댓값 ≤ 최솟값(역범위)이 되어 바늘이 NaN·반전 위치로 그려졌다.
 * 둘 다 설정한 역범위는 서버 검증과 normalizeChartConfig(WD-413)가 막으므로, 한쪽만 설정한 경우도 같은 철학으로
 * 빠진 끝을 설정된 끝에 맞춰 정상 게이지로 그리는지 본다:
 * (a) min 200·max 없음·값 100 → 200 ~ 240, (b) min 없음·max -10·값 5 → -12 ~ -10.
 */

const BODY = 'dashboard-widget-chart-area';

/** 게이지 위젯 하나짜리 대시보드를 모킹한다 */
async function setup(page: Page, config: Record<string, unknown>, value: number) {
  const widget = createWidget({ id: 1, chartId: 1, chartName: '위젯-GAUGE', chartType: 'GAUGE', width: 6, height: 5 });
  const chart = createChart({ id: 1, name: '위젯-GAUGE', chartType: 'GAUGE', config: config as never });
  const rows = [{ name: 'A', v: value }];
  await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets: [widget] }));
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', {
    chart,
    queryResult: createQueryResult({ columns: ['name', 'v'], rows: rows as never, totalRows: 1 }),
  });
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chart);
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([], { size: 20 }));
}

test.describe('게이지 한쪽 범위 설정 (WD-848)', () => {
  // 한쪽만 설정한 경우 빠진 끝을 설정된 끝에 맞춰 정상 게이지로 그린다. side: 값이 범위 밖일 때 바늘 끝이 중심(x1)의 어느 쪽인지
  const cases = [
    { name: 'min 만 설정해 자동 max 보다 크면 max 를 맞춘다', config: { min: 200 }, value: 100, rangeText: '200 ~ 240', side: 'left' },
    { name: 'max 만 음수로 설정하면 min 을 아래로 맞춘다', config: { max: -10 }, value: 5, rangeText: '-12 ~ -10', side: 'right' },
  ] as const;

  for (const c of cases) {
    test(`${c.name}: 안내 없이 정상 게이지를 그린다`, async ({ authenticatedPage: page }) => {
      await setup(page, { xAxis: 'name', yAxis: ['v'], ...c.config }, c.value);
      await page.goto('/analytics/dashboards/1');
      const body = page.getByTestId(BODY);
      const needle = body.locator('[data-chart-drawing] line');
      await expect(needle).toHaveCount(1);
      await expect(body.getByTestId('chart-gauge-invalid-range')).toHaveCount(0);
      await expect(body.getByText(c.rangeText)).toBeVisible();
      const x1 = Number(await needle.getAttribute('x1'));
      const x2 = Number(await needle.getAttribute('x2'));
      if (c.side === 'left') expect(x2).toBeLessThan(x1);
      else expect(x2).toBeGreaterThan(x1);
    });
  }

  test('대조군: 정상 범위면 안내 없이 바늘 하나를 그린다', async ({ authenticatedPage: page }) => {
    await setup(page, { xAxis: 'name', yAxis: ['v'], min: 0, max: 100 }, 70);
    await page.goto('/analytics/dashboards/1');
    const body = page.getByTestId(BODY);
    await expect(body.locator('[data-chart-drawing] line')).toHaveCount(1);
    await expect(body.getByTestId('chart-gauge-invalid-range')).toHaveCount(0);
  });
});
