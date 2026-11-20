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
      await expect(page.getByText(name, { exact: true })).toBeVisible();

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

  test('음수가 1000행 이상이면 안내 개수에 천 단위 쉼표를 쓴다(WD-841)', async ({ authenticatedPage: page }) => {
    const rows: Record<string, unknown>[] = Array.from({ length: 1000 }, (_, i) => ({ city: `음수${i}`, delta: -1 }));
    rows.push({ city: '서울', delta: 5 });
    const name = await setupPieMocks(page, 'PIE', rows);
    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText(name, { exact: true })).toBeVisible();
    await expect(page.getByTestId('pie-negative-notice')).toContainText('음수 값 1,000개');
  });

  test('음수가 없으면 안내 없이 모든 행을 그린다', async ({ authenticatedPage: page }) => {
    const rows = [
      { city: '부산', delta: 25 },
      { city: '인천', delta: 75 },
    ];
    const name = await setupPieMocks(page, 'PIE', rows);
    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText(name, { exact: true })).toBeVisible();

    await expect(page.locator('.recharts-pie-label-text', { hasText: '인천 (75%)' })).toBeVisible();
    await expect(page.locator('.recharts-pie-label-text', { hasText: '부산 (25%)' })).toBeVisible();
    await expect(page.getByTestId('pie-negative-notice')).toHaveCount(0);
    // WD-835: 결측도 없으면 결측 안내도 없다
    await expect(page.getByTestId('pie-missing-notice')).toHaveCount(0);
  });

  // WD-835: 값이 비었거나 숫자가 아닌 행은 0 이 아니라 「없음」이다 — 조각에서 빼고 뺀 개수를 안내한다
  for (const [chartType, label] of [
    ['PIE', '파이'],
    ['DONUT', '도넛'],
  ] as const) {
    test(`${label} 차트는 결측 행을 빼고 뺀 개수를 안내한다 (WD-835)`, async ({ authenticatedPage: page }) => {
      const rows = [
        { city: '서울', delta: 30 },
        { city: '부산', delta: null },
        { city: '대구', delta: '측정 안 함' },
        { city: '인천', delta: 10 },
        { city: '광주', delta: -5 },
      ];
      const name = await setupPieMocks(page, chartType, rows);
      await page.goto('/analytics/dashboards/1');
      await expect(page.getByText(name, { exact: true })).toBeVisible();

      await expect(page.getByTestId('pie-missing-notice')).toHaveText(
        `값이 없거나 숫자가 아닌 항목 2개는 ${label} 차트에 표시되지 않습니다.`,
      );
      // 음수 1개 안내도 함께 보인다
      await expect(page.getByTestId('pie-negative-notice')).toBeVisible();

      // 결측 행은 범례에서도 빠진다
      const legend = page.locator('.recharts-legend-wrapper');
      await expect(legend.getByText('서울')).toBeVisible();
      await expect(legend.getByText('인천')).toBeVisible();
      await expect(legend.getByText('부산')).toHaveCount(0);
      await expect(legend.getByText('대구')).toHaveCount(0);
    });
  }
});

/**
 * WD-838: 그릴 값이 없는 파이·도넛, 텍스트 값 컬럼, 많은 항목 안내 문구.
 * 값이 전부 null 이면 빈 차트 대신 빈 안내를, 값 컬럼이 문자뿐이면 「숫자 값 컬럼을 선택하세요.」를 보인다.
 */
test.describe('파이·도넛 차트 — 빈 안내·텍스트 컬럼 (WD-838)', () => {
  for (const [chartType, label] of [
    ['PIE', '파이'],
    ['DONUT', '도넛'],
  ] as const) {
    test(`${label} 차트는 값이 전부 null 이면 빈 안내를 보이고 조각을 그리지 않는다`, async ({ authenticatedPage: page }) => {
      const rows = [
        { city: '서울', delta: null },
        { city: '부산', delta: null },
        { city: '대구', delta: null },
      ];
      const name = await setupPieMocks(page, chartType, rows);
      await page.goto('/analytics/dashboards/1');
      await expect(page.getByText(name, { exact: true })).toBeVisible();

      await expect(page.getByTestId('pie-empty-notice')).toHaveText(`${label} 차트로 그릴 값이 없습니다.`);
      // null 은 「숫자 아닌 값」이 아니므로 텍스트 컬럼 안내가 아니다. 결측 안내 줄은 그대로 보인다.
      await expect(page.getByTestId('chart-non-numeric-value')).toHaveCount(0);
      await expect(page.getByTestId('pie-missing-notice')).toBeVisible();
      await expect(page.locator('.recharts-pie-sector')).toHaveCount(0);
    });
  }

  test('값이 전부 0 이면 조각이 없어 빈 안내를 보인다', async ({ authenticatedPage: page }) => {
    const rows = [
      { city: '서울', delta: 0 },
      { city: '부산', delta: '0' },
    ];
    const name = await setupPieMocks(page, 'PIE', rows);
    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText(name, { exact: true })).toBeVisible();

    await expect(page.getByTestId('pie-empty-notice')).toHaveText('파이 차트로 그릴 값이 없습니다.');
    await expect(page.locator('.recharts-pie-sector')).toHaveCount(0);
  });

  for (const [chartType, label] of [
    ['PIE', '파이'],
    ['DONUT', '도넛'],
  ] as const) {
    test(`${label} 차트는 값의 합이 무한대로 넘치면 넘침 안내를 보이고 조각을 그리지 않는다 (WD-843)`, async ({ authenticatedPage: page }) => {
      const rows = [
        { city: '서울', delta: 1e308 },
        { city: '부산', delta: 1e308 },
      ];
      const name = await setupPieMocks(page, chartType, rows);
      await page.goto('/analytics/dashboards/1');
      await expect(page.getByText(name, { exact: true })).toBeVisible();

      await expect(page.getByTestId('pie-empty-notice')).toHaveText(`값의 합이 너무 커서 ${label} 차트로 그릴 수 없습니다.`);
      await expect(page.locator('.recharts-pie-sector')).toHaveCount(0);
    });
  }

  test('값 컬럼이 전부 문자열이면 숫자 값 컬럼 안내를 보인다', async ({ authenticatedPage: page }) => {
    const rows = [
      { city: '서울', delta: '서울' },
      { city: '부산', delta: '부산' },
    ];
    const name = await setupPieMocks(page, 'PIE', rows);
    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText(name, { exact: true })).toBeVisible();

    await expect(page.getByTestId('chart-non-numeric-value')).toHaveText('숫자 값 컬럼을 선택하세요.');
    await expect(page.getByTestId('pie-empty-notice')).toHaveCount(0);
    await expect(page.locator('.recharts-pie-sector')).toHaveCount(0);
  });

  test('항목이 51개인 도넛은 도넛 차트로 읽기 어렵다고 안내한다', async ({ authenticatedPage: page }) => {
    const rows = Array.from({ length: 51 }, (_, i) => ({ city: `도시${i + 1}`, delta: i + 1 }));
    const name = await setupPieMocks(page, 'DONUT', rows);
    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText(name, { exact: true })).toBeVisible();

    await expect(page.getByTestId('pie-many-categories-notice')).toContainText('도넛 차트로 읽기 어렵습니다');
  });

  test('값이 전부 0 인 항목 51개 파이는 빈 안내만 보이고 많은 항목 안내는 없다', async ({ authenticatedPage: page }) => {
    const rows = Array.from({ length: 51 }, (_, i) => ({ city: `도시${i + 1}`, delta: 0 }));
    const name = await setupPieMocks(page, 'PIE', rows);
    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText(name, { exact: true })).toBeVisible();

    await expect(page.getByTestId('pie-empty-notice')).toBeVisible();
    await expect(page.getByTestId('pie-many-categories-notice')).toHaveCount(0);
  });

  test('값 컬럼이 공백 문자열뿐이면 텍스트 컬럼 안내가 아니라 빈 안내를 보인다', async ({ authenticatedPage: page }) => {
    const rows = [
      { city: '서울', delta: ' ' },
      { city: '부산', delta: '  ' },
    ];
    const name = await setupPieMocks(page, 'PIE', rows);
    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText(name, { exact: true })).toBeVisible();

    await expect(page.getByTestId('pie-empty-notice')).toBeVisible();
    await expect(page.getByTestId('chart-non-numeric-value')).toHaveCount(0);
  });
});
