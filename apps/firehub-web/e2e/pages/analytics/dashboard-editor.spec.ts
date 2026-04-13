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

    // 위젯이 렌더링되는지 확인
    // createDashboard 기본값: widgets[0].chartName = '테스트 차트'
    // setupDashboardEditorMocks의 /data 응답에서 chartName: '테스트 차트'가 포함됨
    await expect(page.getByText('테스트 차트')).toBeVisible();
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

  test('차트 추가 버튼 클릭 시 차트 추가 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    // 차트 목록에 1개 차트를 포함하도록 모킹 재설정
    await setupDashboardEditorMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/analytics/charts', {
      content: [{ id: 1, name: '테스트 차트 1', description: '', savedQueryId: 1, savedQueryName: '쿼리 1', chartType: 'BAR', isShared: false, createdByName: '사용자', createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });

    await page.goto('/analytics/dashboards/1');

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.getByRole('button', { name: '차트 추가' })).toBeVisible();

    // 차트 추가 버튼 클릭 → 다이얼로그 오픈
    await page.getByRole('button', { name: '차트 추가' }).click();

    // "차트 추가" 다이얼로그 제목 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: '차트 추가' })).toBeVisible();

    // 차트 목록에 '테스트 차트 1' 이 렌더링되는지 확인
    await expect(page.getByText('테스트 차트 1')).toBeVisible();
  });

  test('대시보드를 찾을 수 없을 때 오류 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    // 존재하지 않는 대시보드 ID 요청 — 404 응답 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/999', {}, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/999/data', {}, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    await page.goto('/analytics/dashboards/999');

    // 대시보드 없음 메시지 확인
    await expect(page.getByText('대시보드를 찾을 수 없습니다.')).toBeVisible({ timeout: 5000 });

    // "목록으로" 버튼 확인
    await expect(page.getByRole('button', { name: '목록으로' })).toBeVisible();
  });

  test('"완료" 버튼 클릭으로 편집 모드를 종료한다', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 1);

    await page.goto('/analytics/dashboards/1');

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.getByRole('button', { name: '완료' })).toBeVisible();

    // 완료 버튼 클릭으로 편집 모드 종료
    await page.getByRole('button', { name: '완료' }).click();

    // 편집 모드 종료 후 다시 '편집' 버튼이 표시된다
    await expect(page.getByRole('button', { name: '편집' })).toBeVisible();
    await expect(page.getByRole('button', { name: '완료' })).not.toBeVisible();
  });

  test('편집 모드에서 위젯 제거 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 1);

    await page.goto('/analytics/dashboards/1');

    // 대시보드와 위젯이 렌더링될 때까지 대기
    await expect(page.getByRole('heading', { name: '테스트 대시보드' })).toBeVisible();
    await expect(page.getByText('테스트 차트')).toBeVisible();

    // 뷰 모드에서는 위젯 제거 버튼이 없어야 한다
    await expect(page.locator('button[title="위젯 제거"]')).not.toBeVisible();

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // 편집 모드에서는 위젯 제거 버튼(title="위젯 제거")이 표시된다
    await expect(page.locator('button[title="위젯 제거"]').first()).toBeVisible({ timeout: 5000 });
  });
});
