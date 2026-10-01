import type { Page, Route } from '@playwright/test';

import type { Dashboard } from '../../../src/types/analytics';
import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 위젯 데이터 조회·새로고침 회귀 테스트 (#778)
 *
 * 과거 프론트는 일괄 `/dashboards/{id}/data` 응답을 실제 계약(`widgetData[].chartData`)과 다른 형태로 읽어
 * 응답을 버리고, 위젯마다 `/charts/{id}/data` 로 같은 저장 쿼리를 한 번 더 실행했다. 또 위젯·헤더 새로고침은
 * 일괄/메타 쿼리만 다시 불러 화면 데이터가 바뀌지 않는데도 신선도 바가 "방금"으로 바뀌었고, 일괄 요청이
 * 500 이면 정상 위젯까지 "새로고침 실패"가 붙었다.
 *
 * 이 스펙은 위젯 단건 쿼리를 응답 순서별로 바꾸는 라우트로 "새로고침 후 실제 표시 데이터가 바뀌는지",
 * "실패가 그 위젯에만 표시되는지", "저장 쿼리가 위젯당 1회만 실행되는지"를 검증한다.
 * 모킹 형태는 dev API 실측 응답(`GET /charts/{id}/data` → `{chart, queryResult}`)과 같다.
 */

const DIV_ZERO = 'ERROR: division by zero\nSQLState: 22012';

const chartA = createChart({ id: 1, name: '위젯 A', chartType: 'TABLE', config: { xAxis: '', yAxis: [] } });
const chartB = createChart({ id: 2, name: '위젯 B', chartType: 'TABLE', config: { xAxis: '', yAxis: [] } });

/** 실패(queryResult.error) 응답 — API 는 HTTP 200 + error 사유로 준다 */
const errorBody = (chart: typeof chartA) => ({
  chart,
  queryResult: createQueryResult({ columns: [], rows: [], totalRows: 0, error: DIV_ZERO }),
});
/** 정상 응답 — TABLE 차트이므로 셀 값이 화면에 그대로 보인다 */
const rowsBody = (chart: typeof chartA, cat: string) => ({
  chart,
  queryResult: createQueryResult({ columns: ['cat', 'v'], rows: [{ cat, v: 1 }], totalRows: 1 }),
});

/** 호출 순서별로 다른 응답을 주는 단건 데이터 라우트 — 호출 횟수도 함께 센다 */
async function routeChartData(
  page: Page,
  chartId: number,
  responses: Array<{ status?: number; body: unknown }>,
) {
  const counter = { count: 0 };
  await page.route(
    (url) => url.pathname === `/api/v1/analytics/charts/${chartId}/data`,
    (route: Route) => {
      const res = responses[Math.min(counter.count, responses.length - 1)];
      counter.count += 1;
      return route.fulfill({
        status: res.status ?? 200,
        contentType: 'application/json',
        body: JSON.stringify(res.body),
      });
    },
  );
  return counter;
}

/** 위젯 2개(A·B) 대시보드 공통 모킹. 일괄 엔드포인트는 호출 횟수만 센다(호출되면 안 된다). */
async function setupDashboard(page: Page, overrides?: Partial<Dashboard>) {
  const dashboard = createDashboard({
    id: 1,
    widgets: [
      createWidget({ id: 11, chartId: 1, chartName: '위젯 A', chartType: 'TABLE', width: 6, height: 4 }),
      createWidget({ id: 12, chartId: 2, chartName: '위젯 B', chartType: 'TABLE', positionX: 6, width: 6, height: 4 }),
    ],
    ...overrides,
  });
  const metaCapture = await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', dashboard, { capture: true });
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chartA);
  await mockApi(page, 'GET', '/api/v1/analytics/charts/2', chartB);
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));
  // 과거 코드가 부르던 일괄 엔드포인트 — 실제 계약 형태로 두되, 호출 횟수를 센다
  const batch = { count: 0 };
  await page.route(
    (url) => url.pathname === '/api/v1/analytics/dashboards/1/data',
    (route: Route) => {
      batch.count += 1;
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          dashboard,
          widgetData: [
            { widgetId: 11, chartData: rowsBody(chartA, 'batch-a') },
            { widgetId: 12, chartData: rowsBody(chartB, 'batch-b') },
          ],
        }),
      });
    },
  );
  return { batch, metaCapture };
}

/** 위젯 카드(제목으로 식별) */
const card = (page: Page, name: string) =>
  page.locator('[data-slot="card"]').filter({ has: page.getByText(name, { exact: true }) });

test.describe('대시보드 위젯 — 데이터 조회·새로고침 (#778)', () => {
  test('페이지 로드 시 일괄 엔드포인트를 부르지 않고 위젯별 저장 쿼리를 1회씩만 실행한다', async ({
    authenticatedPage: page,
  }) => {
    const { batch } = await setupDashboard(page);
    const a = await routeChartData(page, 1, [{ body: rowsBody(chartA, 'a-1') }]);
    const b = await routeChartData(page, 2, [{ body: rowsBody(chartB, 'b-1') }]);

    await page.goto('/analytics/dashboards/1');
    await expect(card(page, '위젯 A').getByText('a-1')).toBeVisible();
    await expect(card(page, '위젯 B').getByText('b-1')).toBeVisible();
    // 늦게 도착하는 중복 요청까지 잡기 위해 잠시 대기 후 횟수 단언
    await page.waitForTimeout(500);

    expect(batch.count).toBe(0);
    expect(a.count).toBe(1);
    expect(b.count).toBe(1);
  });

  test('위젯 새로고침은 그 위젯의 데이터를 실제로 다시 조회해 화면을 바꾼다', async ({ authenticatedPage: page }) => {
    await setupDashboard(page);
    // 1회차: 쿼리 오류 → 2회차: 사용자가 쿼리를 고친 뒤 정상 결과
    const a = await routeChartData(page, 1, [{ body: errorBody(chartA) }, { body: rowsBody(chartA, 'fixed-a') }]);
    const b = await routeChartData(page, 2, [{ body: rowsBody(chartB, 'b-1') }]);

    await page.goto('/analytics/dashboards/1');
    const cardA = card(page, '위젯 A');
    await expect(cardA.getByRole('alert')).toContainText('division by zero');

    await cardA.locator('button[title="새로고침"]').click();

    // 표시 데이터가 실제로 새 결과로 바뀌고 오류 박스가 사라진다
    await expect(cardA.getByText('fixed-a')).toBeVisible();
    await expect(cardA.getByRole('alert')).toHaveCount(0);
    await expect(cardA.getByText('방금')).toBeVisible();
    expect(a.count).toBe(2);
    // 다른 위젯은 다시 조회하지 않는다
    expect(b.count).toBe(1);
  });

  test('헤더 새로고침은 모든 위젯의 데이터를 다시 조회한다', async ({ authenticatedPage: page }) => {
    const { metaCapture } = await setupDashboard(page);
    const a = await routeChartData(page, 1, [{ body: errorBody(chartA) }, { body: rowsBody(chartA, 'fixed-a') }]);
    const b = await routeChartData(page, 2, [{ body: rowsBody(chartB, 'b-1') }, { body: rowsBody(chartB, 'b-2') }]);

    await page.goto('/analytics/dashboards/1');
    await expect(card(page, '위젯 A').getByRole('alert')).toContainText('division by zero');
    await expect(card(page, '위젯 B').getByText('b-1')).toBeVisible();
    const metaBefore = metaCapture.requests.length;

    // 헤더 버튼 — 위젯 카드 밖의 첫 번째 "새로고침"
    await page.locator('button[title="새로고침"]').first().click();

    await expect(card(page, '위젯 A').getByText('fixed-a')).toBeVisible();
    await expect(card(page, '위젯 B').getByText('b-2')).toBeVisible();
    expect(a.count).toBe(2);
    expect(b.count).toBe(2);
    // 대시보드 메타도 함께 갱신한다
    expect(metaCapture.requests.length).toBeGreaterThan(metaBefore);
  });

  test('한 위젯의 데이터 요청이 500 이어도 실패 표시는 그 위젯에만 붙는다', async ({ authenticatedPage: page }) => {
    await setupDashboard(page);
    // 과거 코드 경로(일괄 엔드포인트)는 한 위젯 예외로 전체 500 이 났다 — 같은 조건을 재현
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/dashboards/1/data',
      (route: Route) =>
        route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"batch failure"}' }),
    );
    await routeChartData(page, 1, [{ body: rowsBody(chartA, 'a-1') }]);
    await routeChartData(page, 2, [{ status: 500, body: { message: 'widget b failure' } }]);

    await page.goto('/analytics/dashboards/1');
    const cardA = card(page, '위젯 A');
    const cardB = card(page, '위젯 B');
    await expect(cardA.getByText('a-1')).toBeVisible();
    await expect(cardB.getByText('새로고침 실패')).toBeVisible();

    // 정상 위젯 A 에는 실패 배지가 없다
    await expect(cardA.getByText('새로고침 실패')).toHaveCount(0);
    await expect(page.getByText('새로고침 실패')).toHaveCount(1);
  });

  test('자동 새로고침 주기마다 위젯 데이터를 다시 조회한다(실패 → 정상 전환)', async ({ authenticatedPage: page }) => {
    // API 최소 주기(5초). refetchInterval 지터(±10%)를 감안해 10초 안에 전환을 기대한다
    await setupDashboard(page, { autoRefreshSeconds: 5 });
    const a = await routeChartData(page, 1, [{ body: errorBody(chartA) }, { body: rowsBody(chartA, 'auto-a') }]);
    await routeChartData(page, 2, [{ body: rowsBody(chartB, 'b-1') }]);

    await page.goto('/analytics/dashboards/1');
    const cardA = card(page, '위젯 A');
    await expect(cardA.getByRole('alert')).toContainText('division by zero');

    // 아무것도 누르지 않아도 다음 주기에 정상 결과로 바뀐다
    await expect(cardA.getByText('auto-a')).toBeVisible({ timeout: 10_000 });
    expect(a.count).toBeGreaterThanOrEqual(2);
  });
});
