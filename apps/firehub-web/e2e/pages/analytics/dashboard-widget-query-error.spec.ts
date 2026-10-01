import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 위젯 — 저장 쿼리 실패 사유 표시 회귀 테스트 (#764)
 *
 * 저장 쿼리가 실패하면 API는 HTTP 200 + `queryResult.error`(예: division by zero,
 * 결과 32MB 초과 안내)로 사유를 돌려준다. 과거 위젯은 이 사유를 버리고 빈 rows를
 * ChartRenderer에 넘겨 "데이터가 없습니다." 만 표시했다 — 실패와 정상 0행을 구분할 수 없었다.
 *
 * 대시보드 위젯은 차트 단건 `/charts/{id}/data`(실제 ChartDataResponse 형태 `{chart, queryResult}`)로
 * 표시 데이터를 가져온다(#778 — 일괄 `/dashboards/{id}/data` 는 호출하지 않는다).
 */

const DIV_ZERO_ERROR = 'ERROR: division by zero\nSQLState: 22012';

async function setupMocks(page: import('@playwright/test').Page) {
  const errWidget = createWidget({ id: 1, chartId: 1, chartName: '실패 쿼리 차트', width: 6, height: 4 });
  const emptyWidget = createWidget({
    id: 2,
    chartId: 2,
    chartName: '빈 결과 차트',
    positionX: 6,
    width: 6,
    height: 4,
  });
  const dashboard = createDashboard({ id: 1, widgets: [errWidget, emptyWidget] });
  const errChart = createChart({ id: 1, name: '실패 쿼리 차트', config: { xAxis: 'cat', yAxis: ['v'] } });
  const emptyChart = createChart({ id: 2, name: '빈 결과 차트', config: { xAxis: 'cat', yAxis: ['v'] } });

  await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', dashboard);
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1', errChart);
  await mockApi(page, 'GET', '/api/v1/analytics/charts/2', emptyChart);
  // 단건 경로 — 실패 차트는 200 + queryResult.error
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', {
    chart: errChart,
    queryResult: createQueryResult({ columns: [], rows: [], totalRows: 0, error: DIV_ZERO_ERROR }),
  });
  // 단건 경로 — 정상 0행
  await mockApi(page, 'GET', '/api/v1/analytics/charts/2/data', {
    chart: emptyChart,
    queryResult: createQueryResult({ columns: ['cat', 'v'], rows: [], totalRows: 0 }),
  });
  await mockApi(page, 'GET', '/api/v1/analytics/charts', {
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  });
}

test.describe('대시보드 위젯 — 쿼리 실패 사유 표시 (#764)', () => {
  test('저장 쿼리가 실패한 위젯은 사유를 보여주고, 정상 0행 위젯은 "데이터가 없습니다."를 보여준다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);
    await page.goto('/analytics/dashboards/1');

    const errCard = page.locator('[data-slot="card"]').filter({ hasText: '실패 쿼리 차트' });
    const emptyCard = page.locator('[data-slot="card"]').filter({ hasText: '빈 결과 차트' });

    // 실패 위젯: 오류 상태 + 서버가 준 사유 원문이 보여야 한다
    const errState = errCard.getByRole('alert');
    await expect(errState).toBeVisible();
    await expect(errState).toContainText('쿼리 오류');
    await expect(errState).toContainText('ERROR: division by zero');
    await expect(errState).toContainText('SQLState: 22012');
    // 실패를 빈 결과로 오인시키는 문구는 없어야 한다
    await expect(errCard.getByText('데이터가 없습니다.')).toHaveCount(0);

    // 정상 0행 위젯: 기존 빈 결과 안내 유지, 오류 표시 없음
    await expect(emptyCard.getByText('데이터가 없습니다.')).toBeVisible();
    await expect(emptyCard.getByRole('alert')).toHaveCount(0);
    await expect(emptyCard.getByText('쿼리 오류')).toHaveCount(0);
  });
});
