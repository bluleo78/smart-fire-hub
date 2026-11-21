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
    await expect(page.getByText('월별 매출 비중(파이)', { exact: true })).toBeVisible();

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
    await expect(page.getByText('월별 매출 비중(파이)', { exact: true })).toBeVisible();

    // 크래시 시 뜨는 WidgetErrorBoundary fallback이 나타나지 않아야 한다
    await expect(page.getByText('위젯 오류')).not.toBeVisible();

    // 파이 자체는 여전히 그려져야 한다 (반경 0 이하로 사라지지 않음)
    await expect(page.locator('path.recharts-sector').first()).toBeVisible();
  });
});

/**
 * 카테고리 1000개 파이 대시보드 목 설정 — WD-307 시험과 WD-852 툴팁 시험이 같은 데이터를 쓴다.
 * 만든 행을 돌려줘 시험이 기대값을 같은 데이터로 계산하게 한다.
 */
async function setupManyPieDashboard(page: import('@playwright/test').Page) {
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
  return rows;
}

/**
 * WD-307: 카테고리가 수백 개면 범례가 위젯(카드) 위로 넘쳐 제목을 덮고 파이를 가렸다.
 * 범례는 위젯 안 상한 높이에서 스크롤되고, 파이는 범례 위에 남으며, 많은 항목 안내가 뜨는지 본다.
 */
test.describe('대시보드 파이 위젯 — 카테고리 1000개 (WD-307)', () => {
  test('범례가 위젯 밖으로 넘치지 않고 스크롤되며, 파이가 보이고 많은 항목 안내가 뜬다', async ({
    authenticatedPage: page,
  }) => {
    // 1000조각 파이 + 범례 1000개의 첫 렌더가 무거워 호스트 부하에서 수십 초 걸린다.
    // 측정(제목 표시까지): 감속 없음 2.7초 / CPU 4배 9.6초 / CPU 8배 24.7초.
    // 느린 단계는 아래 45초 개별 제한이 맡으므로 시험 전체는 그 위에 여유만 둔 60초로 —
    // test.slow()(90초)면 뒤 단계가 멈출 때 시도마다 90초를 쓴다.
    test.setTimeout(60_000);
    const rows = await setupManyPieDashboard(page);

    await page.goto('/analytics/dashboards/1');
    const title = page.getByText('많은 항목 파이').first();
    // 위젯 제목은 1000조각 그리기가 끝나야 보인다 — 45초는 위 최악 측정의 약 1.8배라 큰 렌더 회귀는 여전히 실패한다.
    await expect(title).toBeVisible({ timeout: 45_000 });
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
    // WD-852: 범례가 큰 순 50개 + 「기타」로 줄어 이제 넘치지 않을 수도 있다 — 스크롤 여부 대신
    // 「범례 높이가 상한(위젯 높이의 40%) 이하」를 본다. 넘치지 않아도 상한 안이면 위젯 밖으로 나가지 않는다.
    // 상한은 컴포넌트가 쓰는 측정 높이(.recharts-responsive-container) 기준이므로 같은 요소로 잰다.
    const chartHeight = (await page.locator('.recharts-responsive-container').first().boundingBox())!.height;
    expect(legendMetrics.clientHeight).toBeLessThanOrEqual(chartHeight * 0.4 + 1);

    // WD-852: 범례 항목을 앱과 같은 규칙으로 계산한다 — 0.5° 이상(v*360 >= 0.5*합) 큰 조각 중 상위 50개,
    // 2개 이상 묶였으면 회색 「기타 (n개)」(n = 묶인 조각 수), 큰 조각이 잘렸으면 마지막에 「외 k개」.
    const total = rows.reduce((sum, r) => sum + r.v, 0);
    const bigCount = rows.filter((r) => r.v * 360 >= 0.5 * total).length;
    const smallCount = rows.length - bigCount;
    const shownBig = Math.min(bigCount, 50);
    const legendItems = legend.locator('li');
    await expect(legendItems).toHaveCount(shownBig + (smallCount >= 2 ? 1 : 0) + (bigCount > 50 ? 1 : 0));
    if (smallCount >= 2) {
      await expect(legendItems.nth(shownBig)).toHaveText(`기타 (${smallCount.toLocaleString('ko-KR')}개)`);
    }
    if (bigCount > 50) {
      await expect(legendItems.last()).toHaveText(`외 ${(bigCount - 50).toLocaleString('ko-KR')}개`);
    }

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
    // WD-852: 0.5° 미만 작은 조각은 「기타」 한 조각으로 묶여 1000개보다 적게 그려진다.
    expect(sectors.length).toBeLessThan(1000);
    expect(sectors.length).toBeGreaterThan(1);
    await expect(page.locator('[data-testid="pie-other-slice"]')).toHaveCount(1);
    expect(Math.max(...sectors.map((b) => b.bottom))).toBeLessThanOrEqual(legendMetrics.top + 1);

    // WD-852 리뷰 후속: 많은 항목이면 테두리·여백이 꺼지므로, 그린 순서로 맞닿는 큰 조각(원을 돌아 마지막 → 첫 조각 포함)은
    // 색이 달라야 한 조각처럼 보이지 않는다. 회색 기타는 1° 도 안 되어 사이에 있어도 경계가 되지 못하므로 빼고 본다.
    const bigFills = await page
      .locator('path.recharts-sector:not([data-testid="pie-other-slice"])')
      .evaluateAll((els) => els.map((el) => el.getAttribute('fill')));
    expect(bigFills.length).toBeGreaterThan(1);
    bigFills.forEach((fill, i) => expect(fill).not.toBe(bigFills[(i + 1) % bigFills.length]));
    // 범례 스와치 색은 같은 이름 조각의 색과 같다 — 첫 범례 항목(가장 큰 조각)으로 확인
    const firstSwatch = await legendItems.first().locator('path').getAttribute('fill');
    expect(bigFills).toContain(firstSwatch);

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

  test('기타 조각에 마우스를 올리면 개수와 합계가 툴팁에 뜬다 (WD-852)', async ({ authenticatedPage: page }) => {
    test.setTimeout(60_000);
    const rows = await setupManyPieDashboard(page);

    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText('많은 항목 파이').first()).toBeVisible({ timeout: 45_000 });

    // 기대값은 같은 데이터로 계산한다 — 0.5° 미만(v*360 < 0.5*합) 조각의 개수와 합.
    const total = rows.reduce((sum, r) => sum + r.v, 0);
    const small = rows.filter((r) => r.v * 360 < 0.5 * total);
    const smallSum = small.reduce((sum, r) => sum + r.v, 0);

    await page.locator('[data-testid="pie-other-slice"]').hover();
    const tooltip = page.locator('.recharts-tooltip-wrapper');
    const otherName = `기타 (${small.length.toLocaleString('ko-KR')}개)`;
    await expect(tooltip).toContainText(otherName);
    // 툴팁 formatter 는 값을 그대로(천 단위 구분 없이) 보인다 — 값 요소 전체가 합계와 정확히 같아야 한다(부분 일치 방지).
    await expect(tooltip.locator('.recharts-tooltip-item-value')).toHaveText(String(smallSum));
    // 툴팁의 기타 개수는 범례의 회색 기타 항목 개수와 같다.
    await expect(page.locator('.recharts-legend-wrapper li').filter({ hasText: otherName })).toHaveCount(1);
  });
});
