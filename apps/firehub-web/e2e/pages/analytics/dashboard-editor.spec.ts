import { createDashboard } from '../../factories/analytics.factory';
import { setupDashboardEditorMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 에디터 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 에디터 페이지 UI를 검증한다.
 */
test.describe('대시보드 에디터 페이지', () => {
  test('대시보드 로드 시 이름이 툴바에 표시된다', async ({ authenticatedPage: page }) => {
    // 대시보드 ID=1 관련 API 모킹
    await setupDashboardEditorMocks(page, 1);

    await page.goto('/analytics/dashboards/1');

    // 대시보드 이름이 툴바에 표시되는지 확인 (팩토리 기본값: '테스트 대시보드')
    await expect(page.getByRole('heading', { name: '테스트 대시보드' })).toBeVisible();
  });

  test('위젯이 없는 대시보드에서 빈 상태 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    // 위젯이 없는 대시보드 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards/1',
      createDashboard({ id: 1, widgets: [] }),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1/data', {
      dashboardId: 1,
      widgets: [],
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    await page.goto('/analytics/dashboards/1');

    // 위젯 없음 메시지 확인
    await expect(page.getByText('위젯이 없습니다.')).toBeVisible();
  });

  test('편집 버튼이 툴바에 존재한다', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 1);

    await page.goto('/analytics/dashboards/1');

    // "편집" 버튼 확인
    await expect(page.getByRole('button', { name: '편집' })).toBeVisible();
  });

  test('편집 모드 진입 시 "완료" 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 1);

    await page.goto('/analytics/dashboards/1');

    // 편집 버튼 클릭하여 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // 편집 모드에서는 "완료" 버튼과 "차트 추가" 버튼이 표시된다
    await expect(page.getByRole('button', { name: '완료' })).toBeVisible();
    await expect(page.getByRole('button', { name: '차트 추가' })).toBeVisible();
  });

  test('새로고침 버튼이 툴바에 존재한다', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 1);

    await page.goto('/analytics/dashboards/1');

    // 새로고침 버튼 확인 (툴바에 2개 이상 존재할 수 있으므로 first() 사용)
    await expect(page.locator('button[title="새로고침"]').first()).toBeVisible();
  });
});
