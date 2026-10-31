import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 파이/도넛 위젯 — 라벨·범례 겹침 회귀 테스트 (#642)
 *
 * PieChartView가 outerRadius 계산 시 범례(Legend) 공간을 확보하지 않아
 * 기본 위젯 크기(4행)에서 카테고리가 많은 파이 차트의 조각 라벨과 범례가
 * 겹쳐 보이던 문제를 재현·검증한다. 스크린샷 비교 대신 DOM 겹침 여부로
 * 판정한다(실제 파이 sector 바운딩 박스가 범례 wrapper 위쪽에 위치하는지).
 */

/** 카테고리 12개짜리 파이 차트 쿼리 결과 — 이슈 본문의 "월별 매출 비중" 재현 */
const PIE_COLUMNS = ['month', 'amount'];
const PIE_ROWS = Array.from({ length: 12 }, (_, i) => ({
  month: `2025-${String(i + 1).padStart(2, '0')}`,
  amount: (i + 1) * 100,
}));

async function setupPieDashboardMocks(page: import('@playwright/test').Page, widgetHeight: number) {
  const widget = createWidget({
    id: 1,
    chartId: 1,
    chartName: '월별 매출 비중(파이)',
    chartType: 'PIE',
    width: 6,
    height: widgetHeight,
  });
  const dashboard = createDashboard({ id: 1, widgets: [widget] });
  const chart = createChart({
    id: 1,
    name: '월별 매출 비중(파이)',
    chartType: 'PIE',
    config: { xAxis: 'month', yAxis: ['amount'], showLegend: true },
  });

  await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', dashboard);
  // 위젯 표시 데이터는 단건 `/charts/{id}/data`(실제 ChartDataResponse 형태)로 온다(#778)
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', {
    chart,
    queryResult: createQueryResult({
      columns: PIE_COLUMNS,
      rows: PIE_ROWS,
      totalRows: PIE_ROWS.length,
    }),
  });
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chart);
  await mockApi(page, 'GET', '/api/v1/analytics/charts', {
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  });
}

test.describe('대시보드 파이 위젯 — 라벨/범례 겹침 (#642)', () => {
  test('카테고리 12개 파이 차트가 기본 크기(4행)에서 슬라이스 라벨을 생략하고 범례와 겹치지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupPieDashboardMocks(page, 4);

    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText('월별 매출 비중(파이)')).toBeVisible();

    // 위젯 오류 경계(WidgetErrorBoundary) fallback이 뜨지 않아야 한다 — 렌더 크래시 없음
    await expect(page.getByText('위젯 오류')).not.toBeVisible();

    const pieSector = page.locator('path.recharts-sector').first();
    await expect(pieSector).toBeVisible();
    // Pie 진입 애니메이션(recharts 기본 animationDuration=1500ms)이 끝날 때까지 대기 —
    // 애니메이션 도중 바운딩 박스를 읽으면 최종 반경보다 작게 측정되어 겹침 판정이 왜곡된다.
    await expect(page.locator('path.recharts-sector')).toHaveCount(12);
    await page.waitForTimeout(1700);

    // 카테고리 수(12) > 임계값(8)이므로 조각 위 퍼센트 라벨은 렌더되지 않아야 한다 (겹침 방지)
    await expect(page.locator('.recharts-pie-label-text')).toHaveCount(0);

    // 범례는 여전히 렌더된다
    const legend = page.locator('.recharts-legend-wrapper');
    await expect(legend).toBeVisible();
    await expect(legend.getByText('2025-01')).toBeVisible();

    // DOM 겹침 검증: 파이 sector들의 바운딩 박스 하단이 범례 바운딩 박스 상단보다 위(또는 같음)여야 한다.
    const sectorBoxes = await page.locator('path.recharts-sector').evaluateAll((els) =>
      els.map((el) => el.getBoundingClientRect()).map((r) => ({ top: r.top, bottom: r.bottom })),
    );
    const pieBottom = Math.max(...sectorBoxes.map((b) => b.bottom));

    const legendBox = await legend.evaluate((el) => el.getBoundingClientRect());

    expect(pieBottom).toBeLessThanOrEqual(legendBox.top + 1); // 1px 오차 허용
  });

  test('위젯을 매우 작은 크기(1행)로 줄여도 크래시 없이 렌더된다', async ({ authenticatedPage: page }) => {
    // 위젯 크기가 극단적으로 작아져도(outerRadius 하한 24px 보장) 예외 없이 렌더되는지 확인한다.
    await setupPieDashboardMocks(page, 1);

    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText('월별 매출 비중(파이)')).toBeVisible();

    // 크래시 시 뜨는 WidgetErrorBoundary fallback이 나타나지 않아야 한다
    await expect(page.getByText('위젯 오류')).not.toBeVisible();

    // 파이 자체는 여전히 그려져야 한다 (반경 0 이하로 사라지지 않음)
    await expect(page.locator('path.recharts-sector').first()).toBeVisible();
  });
});

/**
 * WD-307: 카테고리가 수백 개면 범례가 위젯(카드) 위로 넘쳐 제목을 덮고 파이를 가렸다.
 * 범례는 위젯 안 상한 높이에서 스크롤되고, 파이는 범례 위에 남으며, 많은 항목 안내가 뜨는지 본다.
 */
test.describe('대시보드 파이 위젯 — 카테고리 1000개 (WD-307)', () => {
  test('범례가 위젯 밖으로 넘치지 않고 스크롤되며, 파이가 보이고 많은 항목 안내가 뜬다', async ({
    authenticatedPage: page,
  }) => {
    const rows = Array.from({ length: 1000 }, (_, i) => ({ label: `항목 ${i + 1}`, v: ((i + 1) * 7) % 13 + 1 }));
    const widget = createWidget({ id: 1, chartId: 1, chartName: '많은 항목 파이', chartType: 'PIE', width: 6, height: 4 });
    const chart = createChart({
      id: 1,
      name: '많은 항목 파이',
      chartType: 'PIE',
      config: { xAxis: 'label', yAxis: ['v'], showLegend: true },
    });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets: [widget] }));
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', {
      chart,
      queryResult: createQueryResult({ columns: ['label', 'v'], rows, totalRows: rows.length }),
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chart);
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    await page.goto('/analytics/dashboards/1');
    const title = page.getByText('많은 항목 파이').first();
    await expect(title).toBeVisible();
    await expect(page.getByText('위젯 오류')).not.toBeVisible();

    // 많은 항목 안내
    await expect(page.getByTestId('pie-many-categories-notice')).toContainText('항목이 1,000개라');

    // 범례는 상한 높이 안에서 스크롤된다(내용이 상자보다 길다)
    const legend = page.locator('.recharts-legend-wrapper');
    await expect(legend).toBeVisible();
    const legendMetrics = await legend.evaluate((el) => ({
      top: el.getBoundingClientRect().top,
      bottom: el.getBoundingClientRect().bottom,
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
    }));
    expect(legendMetrics.scrollHeight).toBeGreaterThan(legendMetrics.clientHeight);

    // 범례가 위젯 제목 위로 넘치지 않는다 — 이전에는 범례가 카드 위쪽 경계를 넘어 제목을 덮었다.
    const titleBox = (await title.boundingBox())!;
    expect(legendMetrics.top).toBeGreaterThanOrEqual(titleBox.y + titleBox.height);

    // 파이는 그려져 있고 범례 위에 있다 — 많은 항목이면 진입 애니메이션을 끄므로 기다리지 않는다.
    const sectors = await page.locator('path.recharts-sector').evaluateAll((els) =>
      els.map((el) => {
        const r = el.getBoundingClientRect();
        return { top: r.top, bottom: r.bottom };
      }),
    );
    expect(sectors.length).toBe(1000);
    expect(Math.max(...sectors.map((b) => b.bottom))).toBeLessThanOrEqual(legendMetrics.top + 1);

    // 안내는 한 줄이라 파이를 덮지 않는다
    const noticeBox = (await page.getByTestId('pie-many-categories-notice').boundingBox())!;
    expect(noticeBox.height).toBeLessThan(24);
    expect(noticeBox.y + noticeBox.height).toBeLessThanOrEqual(Math.min(...sectors.map((b) => b.top)) + 1);

    // 범례 아래쪽도 위젯 칸 안에 있다 — 위·아래 모두 넘치지 않는다.
    const gridItem = page.locator('.react-grid-item').filter({ has: title });
    if ((await gridItem.count()) > 0) {
      const itemBox = (await gridItem.first().boundingBox())!;
      expect(legendMetrics.bottom).toBeLessThanOrEqual(itemBox.y + itemBox.height + 1);
    }
  });
});
