import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 차트 데이터 잘림 안내 회귀 테스트 (WD-320)
 *
 * 서버는 차트 데이터를 1000행까지만 실행하고 넘치면 `truncated: true` 를 준다. 과거 차트 빌더·대시보드 위젯은
 * 받은 행 수만 보여 「1000개 행 로드됨」「1000행 기준」이 전부인 것처럼 읽혔다. 쿼리 편집기의 「(상위 N행)」처럼
 * 잘렸을 때만 「상위 N행」 안내를 보여야 한다. 행 수는 빠르게 그리려고 작게 둔다 — 안내는 받은 행 수를 그대로 쓴다.
 */

const ROWS = [
  { label: '항목 1', v: 7 },
  { label: '항목 2', v: 1 },
  { label: '항목 3', v: 8 },
];

test.describe('차트 데이터 잘림 안내 (WD-320)', () => {
  test('차트 빌더 — 저장 쿼리 결과가 잘리면 「상위 N행」으로 안내하고, 잘리지 않으면 안내하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    // 첫 실행은 잘린 결과, 두 번째 실행은 전부인 결과를 돌려준다(같은 쿼리를 다시 실행해 안내가 사라지는지 본다)
    let call = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/1/execute',
      (route) => {
        call += 1;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            createQueryResult({ columns: ['label', 'v'], rows: ROWS, totalRows: 3, truncated: call === 1 }),
          ),
        });
      },
    );

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    const runBtn = page.getByRole('button', { name: '쿼리 실행' });

    // 1) 잘린 결과 — 로드 요약·미리보기 기준 모두 「상위 3행」
    await runBtn.click();
    const summary = page.getByText(/^2개 컬럼, .*행 로드됨/);
    const basis = page.getByText(/^(상위 )?3행 기준$/);
    await expect(summary).toHaveText('2개 컬럼, 상위 3개 행 로드됨 (행 상한 — 결과가 더 있을 수 있음)');
    await expect(basis).toHaveText('상위 3행 기준');

    // 2) 다시 실행해 결과가 전부면 잘림 안내가 사라진다(이전 상태가 남지 않음)
    await runBtn.click();
    // (첫 실행의 「상위 3행까지 로드됨」 토스트가 남아 있을 수 있어, 화면 전체가 아니라 두 표시만 본다)
    await expect(summary).toHaveText('2개 컬럼, 3개 행 로드됨');
    await expect(basis).toHaveText('3행 기준');
    expect(call).toBe(2);
  });

  test('대시보드 위젯 — 차트 데이터가 잘린 위젯에만 「상위 N행만 표시」를 보인다', async ({
    authenticatedPage: page,
  }) => {
    const truncWidget = createWidget({ id: 1, chartId: 1, chartName: '잘린 표', width: 6, height: 4 });
    const fullWidget = createWidget({
      id: 2,
      chartId: 2,
      chartName: '전부인 표',
      positionX: 6,
      width: 6,
      height: 4,
    });
    const truncChart = createChart({ id: 1, name: '잘린 표', chartType: 'TABLE', config: { xAxis: 'label', yAxis: ['v'] } });
    const fullChart = createChart({ id: 2, name: '전부인 표', chartType: 'TABLE', config: { xAxis: 'label', yAxis: ['v'] } });

    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets: [truncWidget, fullWidget] }));
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', truncChart);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/2', fullChart);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', {
      chart: truncChart,
      queryResult: createQueryResult({ columns: ['label', 'v'], rows: ROWS, totalRows: 3, truncated: true }),
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts/2/data', {
      chart: fullChart,
      queryResult: createQueryResult({ columns: ['label', 'v'], rows: ROWS, totalRows: 3, truncated: false }),
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    await page.goto('/analytics/dashboards/1');

    const truncCard = page.locator('[data-slot="card"]').filter({ hasText: '잘린 표' });
    const fullCard = page.locator('[data-slot="card"]').filter({ hasText: '전부인 표' });

    // 두 위젯 모두 받은 행을 그린다
    await expect(truncCard.getByRole('cell', { name: '항목 3', exact: true })).toBeVisible();
    await expect(fullCard.getByRole('cell', { name: '항목 3', exact: true })).toBeVisible();
    // 잘린 위젯에만 안내가 붙는다
    await expect(truncCard.getByText('상위 3행까지만 표시 — 결과가 더 있을 수 있습니다')).toBeVisible();
    await expect(fullCard.getByText(/상위 \d+행까지만 표시/)).toHaveCount(0);
  });
});
