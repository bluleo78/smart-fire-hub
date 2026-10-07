import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 목업 s4 ① — 권한 부족은 오류가 아니다: 차트 제목 유지, 토스트·재시도·신선도 바 없음, 원본 데이터셋 이름 비노출.
 * 서버는 위젯 단건 /charts/{id}/data 에 200 + denied:true + 빈 결과를 준다(Task 16).
 */
test.describe('대시보드 위젯 — 열람 권한 없음', () => {
  test('denied 위젯은 잠금 상태, 이웃 위젯은 정상', async ({ authenticatedPage: page }) => {
    const denied = createWidget({ id: 1, chartId: 1, chartName: '월별 인건비 추이', width: 6, height: 4 });
    const ok = createWidget({ id: 2, chartId: 2, chartName: '출동 건수', positionX: 6, width: 6, height: 4 });
    const deniedChart = createChart({ id: 1, name: '월별 인건비 추이', config: { xAxis: 'm', yAxis: ['v'] } });
    const okChart = createChart({ id: 2, name: '출동 건수', config: { xAxis: 'm', yAxis: ['v'] } });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets: [denied, ok] }));
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', deniedChart);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/2', okChart);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', {
      chart: deniedChart,
      queryResult: createQueryResult({ columns: [], rows: [], totalRows: 0, error: null }),
      denied: true,
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts/2/data', {
      chart: okChart,
      queryResult: createQueryResult({ columns: ['m', 'v'], rows: [{ m: '1월', v: 3 }], totalRows: 1 }),
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    await page.goto('/analytics/dashboards/1');
    const deniedCard = page.locator('[data-slot="card"]').filter({ hasText: '월별 인건비 추이' });
    const state = deniedCard.getByTestId('widget-denied');
    await expect(state).toBeVisible();
    await expect(state).toContainText('열람 권한 없음');
    await expect(state).toContainText('이 위젯의 원본 데이터를 볼 수 있는 권한이 없습니다.');
    // 오류·재시도·토스트·신선도 바 없음
    await expect(deniedCard.getByRole('alert')).toHaveCount(0);
    await expect(deniedCard.getByRole('button', { name: /새로고침|다시 시도/ })).toHaveCount(0);
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
    await expect(deniedCard.getByText('데이터를 불러올 수 없습니다.')).toHaveCount(0);
    // 이웃 위젯은 정상 렌더
    const okCard = page.locator('[data-slot="card"]').filter({ hasText: '출동 건수' });
    await expect(okCard.getByTestId('widget-denied')).toHaveCount(0);
    // 위 '새로고침 버튼 없음' 단언이 공허하지 않도록 — 정상 위젯에는 신선도 바의 새로고침 버튼이 있다
    await expect(okCard.getByRole('button', { name: '새로고침' })).toHaveCount(1);
  });
});
