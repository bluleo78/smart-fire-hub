/**
 * 차트 접근 이름·값 대체 표 E2E (WD-826)
 *
 * 결함: 차트(SVG)가 스크린리더에 이름도 값도 전달하지 못했다. 이제 차트는 figure(aria-label)로 감싸고
 * 시각 차트(그림 영역)는 aria-hidden, 같은 값을 sr-only 표로 제공한다. 레이아웃(높이)은 그대로여야 한다.
 * 숨긴 그림 안에는 Tab 정지점이 없어야 하고(recharts accessibilityLayer=false — svg 에 tabindex·role=application 없음),
 * 뷰의 안내문은 숨기지 않는다. 뷰가 그림 대신 안내만 그리면 figure 는 차트 이름·값 표를 내지 않는다.
 */

import type { Locator, Page } from '@playwright/test';

import type { ChartConfig, ChartType } from '../../../src/types/analytics';
import {
  createChart,
  createDashboard,
  createQueryResult,
  createSavedQueryList,
  createWidget,
} from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { CHART_SURFACE } from '../../fixtures/chart-selectors';

const RESULT = createQueryResult({
  columns: ['region', 'amount'],
  rows: [
    { region: '서울', amount: 10 },
    { region: '부산', amount: 20 },
  ],
  totalRows: 2,
});

/** 숨긴 영역 안에서 Tab 정지점이 될 수 있는 요소(WD-826 — axe aria-hidden-focus 와 같은 기준) */
const TABBABLE = '[tabindex]:not([tabindex="-1"]), button, a[href], input, select, textarea';

/** 위젯이 없는 차트 목록 응답(대시보드 편집기가 차트 선택 목록을 부를 때) — 이 spec 은 항상 빈 목록이면 충분하다 */
async function mockEmptyChartList(page: Page) {
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([], { size: 20 }));
}

/** 위젯 하나짜리 대시보드(id 1)와 그 차트(id 1)의 데이터·상세·빈 차트 목록을 모킹한다 */
async function mockSingleChartDashboard(
  page: Page,
  { widget, chart, queryResult }: {
    widget: ReturnType<typeof createWidget>;
    chart: ReturnType<typeof createChart>;
    queryResult: ReturnType<typeof createQueryResult>;
  },
) {
  await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets: [widget] }));
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', { chart, queryResult });
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chart);
  await mockEmptyChartList(page);
}

/**
 * 숨긴 그림의 recharts svg 에 조작 요소가 남지 않았는지 본다(WD-826) — role=application·tabindex 가 없어야 한다.
 * accessibilityLayer=false 면 recharts 가 svg 에 tabindex 를 아예 붙이지 않아 클릭으로도 포커스가 들어가지 않는다.
 */
async function expectInertSurface(figure: Locator) {
  const surface = figure.locator(CHART_SURFACE).first();
  await expect(surface).toBeAttached();
  expect(await surface.evaluate((el) => !!el.closest('[aria-hidden="true"]'))).toBe(true);
  await expect(surface).not.toHaveAttribute('tabindex', /.*/);
  await expect(figure.locator('svg[role="application"]')).toHaveCount(0);
  await expect(figure.locator('[aria-hidden="true"]').locator(TABBABLE)).toHaveCount(0);
}

/** figure 안 표에 값이 있고, 시각 차트는 aria-hidden 조상 아래에 있으며 그 안에 Tab 정지점이 없다 */
async function expectFigureContent(page: Page, name: string) {
  const figure = page.getByRole('figure', { name });
  await expect(figure).toBeVisible();
  const table = figure.getByRole('table');
  await expect(table.getByRole('cell', { name: '10' })).toBeAttached();
  await expect(table.getByRole('cell', { name: '20' })).toBeAttached();
  await expect(table.getByRole('cell', { name: '서울' })).toBeAttached();
  await expectInertSurface(figure);
  return figure;
}

test.describe('차트 접근 이름·값 대체 표 (WD-826)', () => {
  test('차트 편집 미리보기가 이름 있는 figure 와 값 표를 갖고 레이아웃이 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', RESULT);
    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByText('2개 컬럼, 2개 행 로드됨')).toBeVisible();
    // 실행하면 차트 종류가 자동 추천되므로 막대로 고정한다. 저장 전 새 차트는 이름이 없어 축 정보 자동 이름을 쓴다.
    await page.getByRole('button', { name: '막대 차트', exact: true }).click();

    const figure = await expectFigureContent(page, '막대 차트: region별 amount');
    const box = await figure.boundingBox();
    expect(box?.height ?? 0).toBeGreaterThan(100);
  });

  test('기존 차트를 열면 저장된 이름이 figure 이름에 들어간다', async ({ authenticatedPage: page }) => {
    const chart = createChart({
      id: 1,
      name: '지역별 금액',
      chartType: 'BAR',
      config: { xAxis: 'region', yAxis: ['amount'] },
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chart);
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse(createSavedQueryList(3)));
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', RESULT);
    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '쿼리 실행' }).click();

    await expectFigureContent(page, '「지역별 금액」 막대 차트');
  });

  test('대시보드 막대 위젯이 차트 이름의 figure 와 값 표를 갖는다', async ({ authenticatedPage: page }) => {
    const widget = createWidget({ id: 1, chartId: 1, chartName: '월별 매출', chartType: 'BAR', width: 6, height: 4 });
    const chart = createChart({
      id: 1,
      name: '월별 매출',
      chartType: 'BAR',
      config: { xAxis: 'region', yAxis: ['amount'] },
    });
    await mockSingleChartDashboard(page, { widget, chart, queryResult: RESULT });
    await page.goto('/analytics/dashboards/1');

    await expectFigureContent(page, '「월별 매출」 막대 차트');
  });

  test('폭포 누적이 무한대로 넘치면 넘침 안내만 보이고 Infinity·NaN 은 어디에도 없다 (WD-844)', async ({
    authenticatedPage: page,
  }) => {
    const widget = createWidget({ id: 1, chartId: 1, chartName: '증감 폭포', chartType: 'WATERFALL', width: 6, height: 4 });
    const chart = createChart({
      id: 1,
      name: '증감 폭포',
      chartType: 'WATERFALL',
      config: { xAxis: 'region', yAxis: ['amount'] },
    });
    const huge = createQueryResult({
      columns: ['region', 'amount'],
      rows: [
        { region: '서울', amount: 1e308 },
        { region: '부산', amount: 1e308 },
        { region: '대구', amount: -5 },
      ],
      totalRows: 3,
    });
    await mockSingleChartDashboard(page, { widget, chart, queryResult: huge });
    await page.goto('/analytics/dashboards/1');

    await expect(page.getByTestId('waterfall-overflow-notice')).toHaveText('값의 누적이 너무 커서 폭포 차트로 그릴 수 없습니다.');
    await expect(page.getByText('Infinity')).toHaveCount(0);
    await expect(page.getByText('NaN')).toHaveCount(0);
  });

  test('폭포 누적은 유한해도 Y축 범위가 넘치면 넘침 안내만 보이고 Infinity·NaN 은 없다 (WD-844)', async ({
    authenticatedPage: page,
  }) => {
    const widget = createWidget({ id: 1, chartId: 1, chartName: '증감 폭포', chartType: 'WATERFALL', width: 6, height: 4 });
    const chart = createChart({
      id: 1,
      name: '증감 폭포',
      chartType: 'WATERFALL',
      config: { xAxis: 'region', yAxis: ['amount'] },
    });
    const wide = createQueryResult({
      columns: ['region', 'amount'],
      rows: [
        { region: '서울', amount: -1e308 },
        { region: '부산', amount: 1e308 },
        { region: '대구', amount: 1e308 },
      ],
      totalRows: 3,
    });
    await mockSingleChartDashboard(page, { widget, chart, queryResult: wide });
    await page.goto('/analytics/dashboards/1');

    await expect(page.getByTestId('waterfall-overflow-notice')).toBeVisible();
    await expect(page.getByText(/Infinity|NaN/)).toHaveCount(0);
  });

  test('파이 음수 제외 안내는 figure 안에서 숨기지 않아 스크린리더가 읽는다', async ({ authenticatedPage: page }) => {
    const widget = createWidget({ id: 1, chartId: 1, chartName: '지역 비중', chartType: 'PIE', width: 6, height: 4 });
    const chart = createChart({
      id: 1,
      name: '지역 비중',
      chartType: 'PIE',
      config: { xAxis: 'region', yAxis: ['amount'] },
    });
    const negative = createQueryResult({
      columns: ['region', 'amount'],
      rows: [
        { region: '서울', amount: 10 },
        { region: '부산', amount: 20 },
        { region: '인천', amount: -5 },
      ],
      totalRows: 3,
    });
    await mockSingleChartDashboard(page, { widget, chart, queryResult: negative });
    await page.goto('/analytics/dashboards/1');

    const figure = page.getByRole('figure', { name: '「지역 비중」 파이 차트' });
    await expect(figure).toBeVisible();
    // 안내문은 figure 안에 있고 aria-hidden 조상이 없다 — 보조기술 트리에도 문장으로 노출된다
    const notice = figure.getByTestId('pie-negative-notice');
    await expect(notice).toBeVisible();
    expect(await notice.evaluate((el) => !!el.closest('[aria-hidden="true"]'))).toBe(false);
    await expect(figure).toContainText('음수 값 1개는 파이 차트에 표시되지 않습니다');
    // 파이 그림은 숨겨지고 svg 에 role=application·Tab 정지점이 없다
    await expectInertSurface(figure);
  });

  test('그림을 그리는 모든 차트 종류에서 숨긴 그림 안에 Tab 정지점이 없다', async ({ authenticatedPage: page }) => {
    // WD-826: recharts 는 svg(tabindex=0)·Pie 조각 묶음(rootTabIndex=0)에 Tab 정지점을 만든다 — 차트 종류마다 막았는지 본다
    const MULTI = createQueryResult({
      columns: ['region', 'cat', 'amount'],
      rows: [
        { region: '서울', cat: 'A', amount: 10 },
        { region: '부산', cat: 'B', amount: 20 },
        { region: '대구', cat: 'A', amount: 15 },
      ],
      totalRows: 3,
    });
    const XY: ChartConfig = { xAxis: 'region', yAxis: ['amount'] };
    // 박스 플롯·캔들스틱은 직접 그리는 svg 라 각자 필요한 통계·시가/고가/저가/종가 컬럼이 있는 결과를 쓴다
    const BOX = createQueryResult({
      columns: ['region', 'min', 'q1', 'median', 'q3', 'max'],
      rows: [
        { region: '서울', min: 1, q1: 2, median: 3, q3: 4, max: 5 },
        { region: '부산', min: 2, q1: 3, median: 4, q3: 5, max: 6 },
      ],
      totalRows: 2,
    });
    const OHLC = createQueryResult({
      columns: ['day', 'o', 'h', 'l', 'c'],
      rows: [
        { day: '월', o: 10, h: 15, l: 8, c: 12 },
        { day: '화', o: 12, h: 14, l: 9, c: 10 },
      ],
      totalRows: 2,
    });
    const cases: { type: ChartType; config: ChartConfig; result?: typeof MULTI }[] = [
      { type: 'LINE', config: XY },
      { type: 'AREA', config: XY },
      { type: 'DONUT', config: XY },
      { type: 'SCATTER', config: { xAxis: 'amount', yAxis: ['amount'] } },
      { type: 'RADAR', config: XY },
      { type: 'HISTOGRAM', config: { xAxis: 'amount', yAxis: ['amount'] } },
      { type: 'WATERFALL', config: XY },
      { type: 'FUNNEL', config: XY },
      { type: 'TREEMAP', config: XY },
      { type: 'GAUGE', config: XY },
      { type: 'HEATMAP', config: { xAxis: 'region', yAxis: ['cat'], valueColumn: 'amount' } },
      { type: 'BOXPLOT', config: { xAxis: 'region', yAxis: [] }, result: BOX },
      {
        type: 'CANDLESTICK',
        config: { xAxis: 'day', yAxis: [], open: 'o', high: 'h', low: 'l', close: 'c' },
        result: OHLC,
      },
    ];
    const widgets = cases.map((c, i) =>
      createWidget({
        id: i + 1,
        chartId: i + 1,
        chartName: `차트 ${i + 1}`,
        chartType: c.type,
        positionX: (i % 2) * 6,
        positionY: Math.floor(i / 2) * 4,
        width: 6,
        height: 4,
      }),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets }));
    for (const [i, c] of cases.entries()) {
      const chart = createChart({ id: i + 1, name: `차트 ${i + 1}`, chartType: c.type, config: c.config });
      await mockApi(page, 'GET', `/api/v1/analytics/charts/${i + 1}/data`, { chart, queryResult: c.result ?? MULTI });
      await mockApi(page, 'GET', `/api/v1/analytics/charts/${i + 1}`, chart);
    }
    await mockEmptyChartList(page);
    await page.goto('/analytics/dashboards/1');

    const figures = page.getByTestId('chart-figure');
    await expect(figures).toHaveCount(cases.length);
    for (let i = 0; i < cases.length; i++) {
      const figure = figures.nth(i);
      await figure.scrollIntoViewIfNeeded();
      // 그림 영역이 실제로 그려졌고(숨김 대상이 있음) 그 안에 Tab 으로 닿는 요소가 없다
      const drawing = figure.locator('[aria-hidden="true"]').first();
      await expect(drawing.locator('svg').first()).toBeAttached();
      await expect(figure.locator('[aria-hidden="true"]').locator(TABBABLE), cases[i].type).toHaveCount(0);
      // 그림이 있으니 이름 있는 figure 이고, recharts svg 에는 role=application 이 없다
      await expect(figure, cases[i].type).toHaveAttribute('aria-label', /.+/);
      await expect(figure.locator('svg[role="application"]'), cases[i].type).toHaveCount(0);
    }
  });

  test('뷰가 그림 대신 안내만 그리면 차트 이름·값 표를 내지 않고 안내문만 읽힌다', async ({
    authenticatedPage: page,
  }) => {
    // 게이지 값 컬럼이 문자면 게이지 대신 「숫자 값 컬럼을 선택하세요.」만 보인다 — 스크린리더도 같은 정보만 받아야 한다
    const widget = createWidget({ id: 1, chartId: 1, chartName: '지역 게이지', chartType: 'GAUGE', width: 6, height: 4 });
    const chart = createChart({ id: 1, name: '지역 게이지', chartType: 'GAUGE', config: { xAxis: 'region', yAxis: ['region'] } });
    await mockSingleChartDashboard(page, { widget, chart, queryResult: RESULT });
    await page.goto('/analytics/dashboards/1');

    const container = page.getByTestId('chart-figure');
    await expect(container.getByTestId('chart-non-numeric-value')).toBeVisible();
    await expect(container).not.toHaveAttribute('aria-label', /.*/);
    await expect(page.getByRole('figure', { name: /지역 게이지/ })).toHaveCount(0);
    await expect(container.locator('table')).toHaveCount(0);
  });

  test('히스토그램 값 표는 원본 값이 아니라 차트가 그리는 구간별 빈도를 준다', async ({ authenticatedPage: page }) => {
    const widget = createWidget({ id: 1, chartId: 1, chartName: '금액 분포', chartType: 'HISTOGRAM', width: 6, height: 4 });
    const chart = createChart({
      id: 1,
      name: '금액 분포',
      chartType: 'HISTOGRAM',
      config: { xAxis: 'amount', yAxis: ['amount'], bins: 2 },
    });
    await mockSingleChartDashboard(page, { widget, chart, queryResult: RESULT });
    await page.goto('/analytics/dashboards/1');

    // 값 10·20 을 2구간으로 나누면 10.0(1개)·15.0(1개) — 표 머리글은 구간·빈도
    const table = page.getByRole('figure', { name: '「금액 분포」 히스토그램' }).getByRole('table');
    await expect(table.getByRole('columnheader')).toHaveText(['구간', '빈도']);
    await expect(table.getByRole('row')).toHaveCount(3);
    await expect(table.getByRole('row').nth(1).getByRole('cell')).toHaveText(['10.0', '1']);
    await expect(table.getByRole('row').nth(2).getByRole('cell')).toHaveText(['15.0', '1']);
  });

});

test.describe('차트 값 표가 그림과 같은 값을 준다 (WD-831)', () => {
  test('파이 값 표는 그리지 않은 음수 행을 빼고, 숫자 문자열(DB NUMERIC)도 값으로 세어 비율을 준다', async ({
    authenticatedPage: page,
  }) => {
    const widget = createWidget({ id: 1, chartId: 1, chartName: '지역 비중', chartType: 'PIE', width: 6, height: 4 });
    const chart = createChart({ id: 1, name: '지역 비중', chartType: 'PIE', config: { xAxis: 'region', yAxis: ['amount'] } });
    const mixed = createQueryResult({
      columns: ['region', 'amount'],
      // 서울 은 서버가 DECIMAL 을 문자열로 보낸 경우 — 그림과 표 모두 30 으로 세야 한다
      rows: [
        { region: '서울', amount: '30' },
        { region: '부산', amount: 10 },
        { region: '인천', amount: -5 },
      ],
      totalRows: 3,
    });
    await mockSingleChartDashboard(page, { widget, chart, queryResult: mixed });
    await page.goto('/analytics/dashboards/1');

    const table = page.getByRole('figure', { name: '「지역 비중」 파이 차트' }).getByRole('table');
    await expect(table.getByRole('columnheader')).toHaveText(['region', 'amount', '비율']);
    // 음수 행(인천)은 그림에 없으므로 표에도 없다 — 머리글 + 2행
    await expect(table.getByRole('row')).toHaveCount(3);
    await expect(table.getByRole('row').nth(1).getByRole('cell')).toHaveText(['서울', '30', '75.0%']);
    await expect(table.getByRole('row').nth(2).getByRole('cell')).toHaveText(['부산', '10', '25.0%']);
    await expect(table.getByRole('cell', { name: '인천' })).toHaveCount(0);
  });

  test('산점도 값 표는 숫자로 읽히지 않아 그리지 않은 점을 뺀다', async ({ authenticatedPage: page }) => {
    const widget = createWidget({ id: 1, chartId: 1, chartName: '키와 몸무게', chartType: 'SCATTER', width: 6, height: 4 });
    const chart = createChart({ id: 1, name: '키와 몸무게', chartType: 'SCATTER', config: { xAxis: 'h', yAxis: ['w'] } });
    const points = createQueryResult({
      columns: ['h', 'w'],
      rows: [
        { h: 170, w: 65 },
        { h: '측정 안 함', w: 70 },
        { h: 180, w: 0.5 },
      ],
      totalRows: 3,
    });
    await mockSingleChartDashboard(page, { widget, chart, queryResult: points });
    await page.goto('/analytics/dashboards/1');

    const table = page.getByRole('figure', { name: '「키와 몸무게」 산점도' }).getByRole('table');
    await expect(table.getByRole('row')).toHaveCount(3);
    await expect(table.getByRole('row').nth(1).getByRole('cell')).toHaveText(['170', '65']);
    await expect(table.getByRole('row').nth(2).getByRole('cell')).toHaveText(['180', '0.5']);
    await expect(table.getByRole('cell', { name: '측정 안 함' })).toHaveCount(0);
  });
});
