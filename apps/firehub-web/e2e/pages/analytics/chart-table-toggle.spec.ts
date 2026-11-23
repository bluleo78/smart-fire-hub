/**
 * 차트 「표로 보기」 토글 E2E (WD-832)
 *
 * 대시보드 위젯 머리의 토글을 켜면 그림 자리에 값 표가 보이고(카드 높이 그대로), 끄면 그림이 돌아온다.
 * 안내만 그리는 차트는 표 모드에서도 안내만 보이고, 표·지도 위젯에는 버튼이 없다.
 */

import type { Page } from '@playwright/test';

import {
  createChart,
  createDashboard,
  createQueryResult,
  createSavedQueryList,
  createWidget,
} from '../../factories/analytics.factory';
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
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([], { size: 20 }));
}

test.describe('차트 「표로 보기」 토글 (WD-832)', () => {
  test('대시보드 막대 위젯 — 키보드로 표로 보기를 켜면 그림 자리에 값 표가, 끄면 그림이 돌아온다', async ({ authenticatedPage: page }) => {
    await mockSingleChartDashboard(page, {
      widget: createWidget({ id: 1, chartId: 1, chartName: '월별 매출', chartType: 'BAR', width: 6, height: 4 }),
      chart: createChart({ id: 1, name: '월별 매출', chartType: 'BAR', config: { xAxis: 'region', yAxis: ['amount'] } }),
      queryResult: RESULT,
    });
    await page.goto('/analytics/dashboards/1');

    const toggle = page.getByRole('button', { name: '표로 보기' });
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    const figure = page.getByRole('figure', { name: '「월별 매출」 막대 차트' });
    await expect(figure.locator(CHART_SURFACE).first()).toBeVisible();
    const area = page.getByTestId('dashboard-widget-chart-area');
    const box0 = await area.boundingBox();

    await toggle.focus();
    await page.keyboard.press('Enter');
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    const table = figure.getByRole('table');
    await expect(table).toHaveCount(1);
    await expect(table).toBeVisible();
    for (const name of ['서울', '10', '부산', '20']) {
      await expect(table.getByRole('cell', { name, exact: true })).toBeVisible();
    }
    await expect(figure.locator(CHART_SURFACE).first()).toBeHidden();
    const box1 = await area.boundingBox();
    expect(Math.abs(box1!.height - box0!.height)).toBeLessThanOrEqual(1);

    await page.keyboard.press('Space');
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await expect(figure.locator(CHART_SURFACE).first()).toBeVisible();
    await expect(figure.getByRole('table')).toBeAttached();
    // sr-only 표는 1px 로 잘려 있어 Playwright 가 「보임」으로 셀 수 있다 — 화면에서 가려진 표인지는 sr-only 클래스로 본다
    await expect(figure.getByRole('table')).toHaveClass(/sr-only/);
    const hidden = await figure
      .locator(CHART_SURFACE)
      .first()
      .evaluate((el) => !!el.closest('[aria-hidden="true"]'));
    expect(hidden).toBe(true);
  });

  test('안내만 그리는 차트는 표 모드에서도 안내만 보인다', async ({ authenticatedPage: page }) => {
    await mockSingleChartDashboard(page, {
      widget: createWidget({ id: 1, chartId: 1, chartName: '게이지', chartType: 'GAUGE', width: 6, height: 4 }),
      chart: createChart({ id: 1, name: '게이지', chartType: 'GAUGE', config: { xAxis: 'region', yAxis: ['region'] } }),
      queryResult: RESULT,
    });
    await page.goto('/analytics/dashboards/1');

    await page.getByRole('button', { name: '표로 보기' }).click();
    await expect(page.getByTestId('chart-non-numeric-value')).toBeVisible();
    await expect(page.getByTestId('chart-figure').locator('table')).toHaveCount(0);
  });

  test('편집 모드에서도 표로 보기 버튼이 있고 위젯 제거 버튼과 겹치지 않는다', async ({ authenticatedPage: page }) => {
    await mockSingleChartDashboard(page, {
      widget: createWidget({ id: 1, chartId: 1, chartName: '월별 매출', chartType: 'BAR', width: 6, height: 4 }),
      chart: createChart({ id: 1, name: '월별 매출', chartType: 'BAR', config: { xAxis: 'region', yAxis: ['amount'] } }),
      queryResult: RESULT,
    });
    await page.goto('/analytics/dashboards/1');
    await page.getByRole('button', { name: '편집' }).click();

    const toggle = page.getByRole('button', { name: '표로 보기' });
    const remove = page.getByTitle('위젯 제거');
    await expect(toggle).toBeVisible();
    await expect(remove).toBeVisible();
    // 편집 모드로 들어갈 때 격자 배치가 움직이는 중에 재면 두 상자 위치가 어긋나므로, 값이 안정될 때까지 다시 잰다
    await expect
      .poll(async () => {
        const a = await toggle.boundingBox();
        const b = await remove.boundingBox();
        if (!a || !b) return true;
        return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
      })
      .toBe(false);
  });

  test('파이 음수 제외 안내는 표 모드에서도 보인다', async ({ authenticatedPage: page }) => {
    // WD-832: 표 모드가 그림만 숨기고 뷰의 안내문은 숨기지 않는다 — chart-a11y-figure.spec.ts 의 파이 음수 시험과 같은 데이터
    await mockSingleChartDashboard(page, {
      widget: createWidget({ id: 1, chartId: 1, chartName: '지역 비중', chartType: 'PIE', width: 6, height: 4 }),
      chart: createChart({ id: 1, name: '지역 비중', chartType: 'PIE', config: { xAxis: 'region', yAxis: ['amount'] } }),
      queryResult: createQueryResult({
        columns: ['region', 'amount'],
        rows: [
          { region: '서울', amount: 10 },
          { region: '부산', amount: 20 },
          { region: '인천', amount: -5 },
        ],
        totalRows: 3,
      }),
    });
    await page.goto('/analytics/dashboards/1');

    const figure = page.getByRole('figure', { name: '「지역 비중」 파이 차트' });
    await expect(figure.locator(CHART_SURFACE).first()).toBeVisible();
    await page.getByRole('button', { name: '표로 보기' }).click();
    await expect(page.getByTestId('pie-negative-notice')).toBeVisible();
    await expect(figure.getByRole('table')).toHaveCount(1);
    await expect(figure.getByRole('table')).toBeVisible();
    await expect(figure.locator(CHART_SURFACE).first()).toBeHidden();
  });

  test('표 위젯에는 표로 보기 버튼이 없다', async ({ authenticatedPage: page }) => {
    await mockSingleChartDashboard(page, {
      widget: createWidget({ id: 1, chartId: 1, chartName: '표', chartType: 'TABLE', width: 6, height: 4 }),
      chart: createChart({ id: 1, name: '표', chartType: 'TABLE', config: { xAxis: 'region', yAxis: ['amount'] } }),
      queryResult: RESULT,
    });
    await page.goto('/analytics/dashboards/1');

    await expect(page.getByTestId('dashboard-widget-chart-area')).toBeVisible();
    await expect(page.getByRole('button', { name: '표로 보기' })).toHaveCount(0);
  });

  /** 차트 빌더(차트 1, 막대)를 열고 쿼리를 실행해 미리보기를 그린다 — 「표로 보기」 시험이 같이 쓴다 */
  async function openBuilderWithPreview(page: Page) {
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts/1',
      createChart({ id: 1, name: '지역별 금액', chartType: 'BAR', config: { xAxis: 'region', yAxis: ['amount'] } }),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse(createSavedQueryList(3)));
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', RESULT);
    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '쿼리 실행' }).click();
  }

  test('차트 빌더 미리보기 — 키보드로 표로 보기를 켜고 끈다', async ({ authenticatedPage: page }) => {
    await openBuilderWithPreview(page);

    const toggle = page.getByRole('button', { name: '표로 보기' });
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    const figure = page.getByRole('figure', { name: '「지역별 금액」 막대 차트' });
    await expect(figure.locator(CHART_SURFACE).first()).toBeVisible();
    const preview = page.getByTestId('chart-builder-preview');
    const box0 = await preview.boundingBox();

    await toggle.focus();
    await page.keyboard.press('Enter');
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    const table = figure.getByRole('table');
    await expect(table).toHaveCount(1);
    await expect(table).toBeVisible();
    for (const name of ['서울', '10']) {
      await expect(table.getByRole('cell', { name, exact: true })).toBeVisible();
    }
    await expect(figure.locator(CHART_SURFACE).first()).toBeHidden();
    const box1 = await preview.boundingBox();
    expect(Math.abs(box1!.height - box0!.height)).toBeLessThanOrEqual(1);

    await page.keyboard.press('Space');
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await expect(figure.locator(CHART_SURFACE).first()).toBeVisible();
    // sr-only 표는 Playwright 가 「보임」으로 셀 수 있어 화면에서 가려진 표인지는 클래스로 본다
    await expect(figure.getByRole('table')).toHaveClass(/sr-only/);
  });

  test('차트 빌더에서 표 종류로 바꾸면 표로 보기 버튼이 사라진다', async ({ authenticatedPage: page }) => {
    await openBuilderWithPreview(page);

    await expect(page.getByRole('button', { name: '표로 보기' })).toHaveCount(1);
    await page.getByRole('button', { name: '테이블', exact: true }).click();
    await expect(page.getByRole('button', { name: '표로 보기' })).toHaveCount(0);
  });

  test('1행 높이 위젯 — 표 모드에서 행이 넘치면 Tab 으로 표 영역에 닿고 키보드로 스크롤된다', async ({ authenticatedPage: page }) => {
    // 행이 위젯 높이를 넘치게 많은 결과 — 표 영역이 카드 안에서 스크롤돼야 한다
    const manyRows = Array.from({ length: 20 }, (_, i) => ({ region: `지역${i + 1}`, amount: i + 1 }));
    await mockSingleChartDashboard(page, {
      widget: createWidget({ id: 1, chartId: 1, chartName: '월별 매출', chartType: 'BAR', width: 6, height: 1 }),
      chart: createChart({ id: 1, name: '월별 매출', chartType: 'BAR', config: { xAxis: 'region', yAxis: ['amount'] } }),
      queryResult: createQueryResult({ columns: ['region', 'amount'], rows: manyRows, totalRows: 20 }),
    });
    await page.goto('/analytics/dashboards/1');

    const toggle = page.getByRole('button', { name: '표로 보기' });
    const area = page.getByTestId('dashboard-widget-chart-area');
    await expect(area).toBeVisible();
    const box0 = await area.boundingBox();

    await toggle.focus();
    await page.keyboard.press('Enter');
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    const region = page.getByRole('region', { name: /월별 매출/ });
    await expect(region).toHaveCount(1);
    const box1 = await area.boundingBox();
    expect(Math.abs(box1!.height - box0!.height)).toBeLessThanOrEqual(1);
    expect(await region.evaluate((el) => el.scrollHeight > el.clientHeight)).toBe(true);

    // 토글 다음 Tab 으로 표 영역에 초점이 닿아야 한다(스크롤 영역에 자동 초점이 없는 브라우저 대비)
    await page.keyboard.press('Tab');
    await expect(region).toBeFocused();
    await page.keyboard.press('End');
    await expect.poll(() => region.evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
  });
});
