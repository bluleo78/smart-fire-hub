import {
  createChart,
  createDashboardListItem,
  createSavedQueryList,
} from '../../factories/analytics.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 이슈 #97: 차트→대시보드 워크플로우 — 차트에서 "대시보드에 추가" 단축 액션 부재
 *
 * 차트 빌더 페이지 헤더에 "대시보드에 추가" 버튼이 있고, 클릭 시 다이얼로그에서
 * 대시보드를 선택하면 위젯으로 추가되어 대시보드 상세로 이동하는지 검증한다.
 */
test.describe('차트 — 대시보드에 추가 단축 액션 (#97)', () => {
  test('차트 빌더 헤더의 "대시보드에 추가" → 다이얼로그 → 위젯 추가 → 대시보드 이동', async ({
    authenticatedPage: page,
  }) => {
    // 기존 차트 빌더 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, chartType: 'BAR' }));
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse(createSavedQueryList(2))
    );
    // 대시보드 목록 — 다이얼로그에서 표시할 항목
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards',
      createPageResponse([
        createDashboardListItem({ id: 10, name: '영업 대시보드' }),
        createDashboardListItem({ id: 11, name: '재무 대시보드' }),
      ])
    );

    // 위젯 추가 API 호출 캡처
    let capturedPayload: { chartId: number; positionX: number; positionY: number; width: number; height: number } | null = null;
    await page.route('**/api/v1/analytics/dashboards/10/widgets', (route) => {
      if (route.request().method() === 'POST') {
        capturedPayload = route.request().postDataJSON();
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 999,
            chartId: 1,
            chartName: '테스트 차트',
            chartType: 'BAR',
            positionX: 0,
            positionY: 9999,
            width: 6,
            height: 4,
          }),
        });
      }
      return route.continue();
    });

    // 대시보드 상세 — 추가 후 navigate되는 페이지의 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/10', {
      id: 10,
      name: '영업 대시보드',
      description: null,
      isShared: false,
      autoRefreshSeconds: null,
      widgets: [],
      createdByName: '테스트',
      createdBy: 1,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-01T00:00:00Z',
    });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/10/data', {
      dashboardId: 10,
      widgets: [],
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));

    await page.goto('/analytics/charts/1');

    // 헤더에 "대시보드에 추가" 버튼이 표시되어야 한다 (저장된 차트 이므로 chartId 존재)
    const addButton = page.getByRole('button', { name: '대시보드에 추가' });
    await expect(addButton).toBeVisible();
    await addButton.click();

    // 다이얼로그가 열리고 대시보드 목록이 표시되어야 한다
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('영업 대시보드')).toBeVisible();
    await expect(page.getByText('재무 대시보드')).toBeVisible();

    // 대시보드 선택 후 추가 버튼 클릭
    await page.getByText('영업 대시보드').click();
    await page.getByRole('button', { name: '추가' }).click();

    // 위젯 추가 API가 올바른 payload로 호출되어야 한다
    await expect.poll(() => capturedPayload).not.toBeNull();
    expect(capturedPayload).toMatchObject({
      chartId: 1,
      width: 6,
      height: 4,
    });

    // 대시보드 상세 페이지로 이동
    await expect(page).toHaveURL(/\/analytics\/dashboards\/10$/, { timeout: 5000 });
  });
});
