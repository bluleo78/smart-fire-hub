import type { ChartConfig } from '../../../src/types/analytics';
import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { setupChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 잘못 저장된 차트 config (WD-413)
 *
 * 서버가 config 값을 검사하지 않던 때 `yAxis` 없음·문자열, `xAxis` 숫자 같은 config 가 저장됐다. 차트 편집에서 「쿼리 실행」을 누르면
 * 축 설정 패널이 `config.yAxis.includes` 에서 터져 페이지 전체가 「페이지를 불러오는 중 문제가 발생했습니다」가 됐고, 문자열 yAxis 는
 * 체크박스를 눌러도 `yAxis.filter` 오류로 아무것도 바뀌지 않았다. 이제 받는 즉시 정규화해 축을 다시 골라 저장할 수 있다.
 */

const PAGE_ERROR = '페이지를 불러오는 중 문제가 발생했습니다';

const RESULT = createQueryResult({
  columns: ['region', 'amount', 'total'],
  rows: [
    { region: '서울', amount: 120, total: 300 },
    { region: '부산', amount: 80, total: 200 },
  ],
  totalRows: 2,
});

/** 타입이 틀린 config — 화면 타입(ChartConfig)으로는 만들 수 없어 그대로 실어 보낸다 */
const malformed = (config: Record<string, unknown>) => config as unknown as ChartConfig;

test.describe('잘못 저장된 차트 config — 차트 편집 (WD-413)', () => {
  test('yAxis 가 없는 차트도 「쿼리 실행」 뒤 축 설정이 보이고, Y축을 골라 저장할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupChartBuilderMocks(page);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, config: malformed({ xAxis: 'region' }) }));
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', RESULT);
    const update = await mockApi(page, 'PUT', '/api/v1/analytics/charts/1', createChart({ id: 1 }), { capture: true });

    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByText(/행 로드됨/).first()).toBeVisible();

    await expect(page.getByText(PAGE_ERROR)).toHaveCount(0);
    const amount = page.getByRole('checkbox', { name: 'amount' });
    await expect(amount).not.toBeChecked();
    await amount.click();
    await expect(amount).toBeChecked();
    await expect(page.locator('.recharts-surface').first()).toBeVisible();

    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('dialog').getByRole('button', { name: '수정' }).click();
    const req = await update.waitForRequest();
    expect(req.payload).toMatchObject({ config: { xAxis: 'region', yAxis: ['amount'] } });
  });

  test('yAxis 가 문자열로 저장된 차트는 그 컬럼만 체크된 채 열리고, 다른 컬럼을 더할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupChartBuilderMocks(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts/1',
      createChart({ id: 1, config: malformed({ xAxis: 'region', yAxis: 'amount' }) }),
    );
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', RESULT);

    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByText(/행 로드됨/).first()).toBeVisible();

    await expect(page.getByRole('checkbox', { name: 'amount' })).toBeChecked();
    await expect(page.getByRole('checkbox', { name: 'total' })).not.toBeChecked();
    await page.getByRole('checkbox', { name: 'total' }).click();
    await expect(page.getByRole('checkbox', { name: 'total' })).toBeChecked();
    await expect(page.getByRole('checkbox', { name: 'amount' })).toBeChecked();
    await expect(page.getByText(PAGE_ERROR)).toHaveCount(0);
  });

  test('서버가 잘못된 config 를 400 으로 거절하면 그 사유를 보인다', async ({ authenticatedPage: page }) => {
    await setupChartBuilderMocks(page);
    // 축은 결과에 있는 컬럼으로 — 없으면 화면의 「없는 컬럼」 검사가 저장 전에 막아 서버까지 가지 않는다
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts/1',
      createChart({ id: 1, config: { xAxis: 'region', yAxis: ['amount'] } as ChartConfig }),
    );
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', RESULT);
    await mockApi(
      page,
      'PUT',
      '/api/v1/analytics/charts/1',
      { status: 400, error: 'Bad Request', message: '차트 설정 yAxis 은(는) 문자열 목록(배열)이어야 합니다.' },
      { status: 400 },
    );

    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByText(/행 로드됨/).first()).toBeVisible();
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('dialog').getByRole('button', { name: '수정' }).click();

    await expect(page.getByText('차트 설정 yAxis 은(는) 문자열 목록(배열)이어야 합니다.')).toBeVisible();
  });
});

test.describe('잘못 저장된 차트 config — 대시보드 위젯 (WD-413)', () => {
  test('xAxis 가 숫자·yAxis 가 객체 섞인 배열이어도 위젯이 깨지지 않고 결과 컬럼으로 차트를 그린다', async ({
    authenticatedPage: page,
  }) => {
    const widget = createWidget({ id: 1, chartId: 1, chartName: '깨진 설정', width: 6, height: 4 });
    const chart = createChart({ id: 1, name: '깨진 설정', config: malformed({ xAxis: 123, yAxis: [1, { a: 2 }] }) });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets: [widget] }));
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chart);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', { chart, queryResult: RESULT });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/analytics/dashboards/1');
    const card = page.locator('[data-slot="card"]').filter({ hasText: '깨진 설정' });
    await expect(card.locator('.recharts-surface').first()).toBeVisible();
    await expect(page.getByText(PAGE_ERROR)).toHaveCount(0);
  });
});
