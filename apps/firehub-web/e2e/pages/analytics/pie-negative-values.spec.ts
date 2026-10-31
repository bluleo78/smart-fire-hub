import type { ChartType } from '../../../src/types/analytics';
import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 파이·도넛 차트 음수 값 처리 회귀 테스트 (WD-303)
 *
 * 값 컬럼에 음수가 섞이면 recharts 가 음수를 포함한 합계로 비율을 계산해 「인천 (135%)」처럼 100% 를 넘는 라벨을 내고
 * 음수 조각을 다른 조각 위에 겹쳐 그렸다. 음수 행은 그리기·비율에서 빼고 「음수 값 N개는 … 표시되지 않습니다」로 안내해야 한다.
 */

/** 이슈 재현 데이터 — delta = -5.5, 3.2, 0, 12.75, -1 (양수 합계 15.95 → 인천 80%, 부산 20%) */
const ROWS = [
  { city: '서울', delta: -5.5 },
  { city: '부산', delta: 3.2 },
  { city: '대구', delta: 0 },
  { city: '인천', delta: 12.75 },
  { city: '서울2', delta: '-1' }, // NUMERIC 은 문자열로 올 수 있다 — 문자열 음수도 음수로 본다
];

async function setupPieMocks(page: import('@playwright/test').Page, chartType: ChartType, rows: Record<string, unknown>[]) {
  const name = `도시별 증감(${chartType})`;
  const widget = createWidget({ id: 1, chartId: 1, chartName: name, chartType, width: 6, height: 6 });
  const dashboard = createDashboard({ id: 1, widgets: [widget] });
  const chart = createChart({
    id: 1,
    name,
    chartType,
    config: { xAxis: 'city', yAxis: ['delta'], showLegend: true },
  });
  await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', dashboard);
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', {
    chart,
    queryResult: createQueryResult({ columns: ['city', 'delta'], rows, totalRows: rows.length }),
  });
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chart);
  await mockApi(page, 'GET', '/api/v1/analytics/charts', {
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  });
  return name;
}

/** 조각 위 라벨의 퍼센트 숫자만 모은다 — 「이름 (N%)」 */
async function labelPercents(page: import('@playwright/test').Page) {
  const texts = await page.locator('.recharts-pie-label-text').allTextContents();
  return texts
    .map((t) => /\((\d+)%\)/.exec(t)?.[1])
    .filter((v): v is string => v !== undefined)
    .map(Number);
}

test.describe('파이·도넛 차트 — 음수 값 (WD-303)', () => {
  for (const [chartType, label] of [
    ['PIE', '파이'],
    ['DONUT', '도넛'],
  ] as const) {
    test(`${label} 차트는 음수 행을 빼고 비율을 계산하며, 뺀 개수를 안내한다`, async ({ authenticatedPage: page }) => {
      const name = await setupPieMocks(page, chartType, ROWS);
      await page.goto('/analytics/dashboards/1');
      await expect(page.getByText(name)).toBeVisible();

      // 음수 2개(서울 -5.5, 서울2 "-1")를 뺐다는 안내
      await expect(page.getByTestId('pie-negative-notice')).toHaveText(
        `음수 값 2개는 ${label} 차트에 표시되지 않습니다. 음수까지 보려면 막대 차트를 쓰세요.`,
      );

      // 비율은 양수 합계(15.95) 기준 — 인천 80%, 부산 20%. 100% 를 넘는 라벨은 없다.
      await expect(page.locator('.recharts-pie-label-text', { hasText: '인천 (80%)' })).toBeVisible();
      await expect(page.locator('.recharts-pie-label-text', { hasText: '부산 (20%)' })).toBeVisible();
      const percents = await labelPercents(page);
      expect(percents.length).toBeGreaterThan(0);
      expect(Math.max(...percents)).toBeLessThanOrEqual(100);

      // 음수 행은 범례에서도 빠진다(그리지 않은 조각을 범례에 남기지 않음)
      const legend = page.locator('.recharts-legend-wrapper');
      await expect(legend.getByText('인천')).toBeVisible();
      await expect(legend.getByText('서울', { exact: true })).toHaveCount(0);
      await expect(legend.getByText('서울2')).toHaveCount(0);
    });
  }

  test('음수가 없으면 안내 없이 모든 행을 그린다', async ({ authenticatedPage: page }) => {
    const rows = [
      { city: '부산', delta: 25 },
      { city: '인천', delta: 75 },
    ];
    const name = await setupPieMocks(page, 'PIE', rows);
    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText(name)).toBeVisible();

    await expect(page.locator('.recharts-pie-label-text', { hasText: '인천 (75%)' })).toBeVisible();
    await expect(page.locator('.recharts-pie-label-text', { hasText: '부산 (25%)' })).toBeVisible();
    await expect(page.getByTestId('pie-negative-notice')).toHaveCount(0);
  });
});
