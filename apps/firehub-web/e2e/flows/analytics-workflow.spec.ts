import { createDashboard } from '../factories/analytics.factory';
import {
  setupDashboardListMocks,
  setupNewQueryEditorMocks,
  setupQueryListMocks,
} from '../fixtures/analytics.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * 분석 도메인 플로우 E2E 테스트
 * - 쿼리/대시보드 주요 사용자 플로우를 검증한다.
 * - 각 플로우는 여러 페이지를 걸쳐 진행된다.
 */
test.describe('분석 플로우', () => {
  test('쿼리 목록 → 새 쿼리 버튼 → 에디터 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    // 쿼리 목록 페이지 모킹
    await setupQueryListMocks(page, 3);
    // 에디터 페이지로 이동 시 필요한 스키마/폴더 API 모킹
    await setupNewQueryEditorMocks(page);

    // 쿼리 목록 페이지 접근
    await page.goto('/analytics/queries');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '저장된 쿼리' })).toBeVisible();

    // "새 쿼리" 버튼 클릭
    await page.getByRole('button', { name: '새 쿼리' }).click();

    // 에디터 페이지로 이동했는지 확인
    await expect(page).toHaveURL('/analytics/queries/new');

    // 에디터 페이지 UI 요소 확인
    await expect(page.getByText('새 쿼리')).toBeVisible();
    // 에디터 툴바의 "저장" 버튼 확인 (실행 버튼은 size="sm"이므로 저장 버튼으로 구분)
    await expect(page.getByRole('button', { name: '저장' })).toBeVisible();
  });

  test('대시보드 목록 → 새 대시보드 버튼 → 생성 다이얼로그 → 생성 후 에디터 이동', async ({ authenticatedPage: page }) => {
    // 대시보드 목록 모킹
    await setupDashboardListMocks(page, 2);

    // 대시보드 생성 API 응답 모킹 (POST /api/v1/analytics/dashboards)
    const newDashboard = createDashboard({ id: 99, name: '새 테스트 대시보드' });
    await mockApi(page, 'POST', '/api/v1/analytics/dashboards', newDashboard);

    // 생성 후 에디터 페이지 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/99', newDashboard);
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/99/data', {
      dashboardId: 99,
      widgets: [],
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });

    // 대시보드 목록 페이지 접근
    await page.goto('/analytics/dashboards');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible();

    // "새 대시보드" 버튼 클릭
    await page.getByRole('button', { name: '새 대시보드' }).click();

    // 생성 다이얼로그가 열리는지 확인
    await expect(page.getByRole('dialog')).toBeVisible();

    // 이름 입력
    await page.getByLabel('이름 *').fill('새 테스트 대시보드');

    // "생성" 버튼 클릭
    await page.getByRole('button', { name: '생성' }).click();

    // 대시보드 에디터 페이지로 이동했는지 확인
    await expect(page).toHaveURL('/analytics/dashboards/99');
  });
});
