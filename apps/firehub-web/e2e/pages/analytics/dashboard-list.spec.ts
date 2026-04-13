import { createDashboard, createDashboardListItem } from '../../factories/analytics.factory';
import { setupDashboardListMocks } from '../../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 목록 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 목록 페이지 UI를 검증한다.
 */
test.describe('대시보드 목록 페이지', () => {
  test('대시보드 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 3개 대시보드 목록을 모킹한 후 목록 페이지 접근
    await setupDashboardListMocks(page, 3);
    await page.goto('/analytics/dashboards');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible();

    // 테이블 헤더 컬럼 확인
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '위젯' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '수정일' })).toBeVisible();

    // 대시보드 행이 3개 렌더링되는지 확인 (fixture에서 생성한 이름 패턴)
    // 셀 내부는 복합 구조이므로 getByText로 확인
    await expect(page.getByText('테스트 대시보드 1')).toBeVisible();
    await expect(page.getByText('테스트 대시보드 3')).toBeVisible();

    // 행 수 확인: 헤더 1개 + 데이터 3개 = 총 4개 행
    await expect(page.getByRole('row')).toHaveCount(4);

    // 첫 번째 데이터 행에 위젯 수 '1개'가 표시되는지 확인
    // DashboardListPage는 widgetCount를 "{widgetCount}개" 형식의 plain text로 렌더링함
    // createDashboardListItem 기본값: widgetCount: 1
    const firstRow = page.getByRole('row').nth(1);
    await expect(firstRow.getByText('1개')).toBeVisible();
  });

  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 빈 페이지 응답으로 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([]));

    await page.goto('/analytics/dashboards');

    // 빈 상태 메시지 확인
    await expect(page.getByText('대시보드가 없습니다.')).toBeVisible();
  });

  test('탭 전환 — "내 대시보드"/"공유됨" 탭이 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupDashboardListMocks(page, 2);
    await page.goto('/analytics/dashboards');

    // 탭 목록 확인
    await expect(page.getByRole('tab', { name: '내 대시보드' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '공유됨' })).toBeVisible();
  });

  test('새 대시보드 버튼 클릭 시 생성 다이얼로그가 열리고 POST payload가 전달된다', async ({ authenticatedPage: page }) => {
    await setupDashboardListMocks(page, 2);
    await page.goto('/analytics/dashboards');

    // POST /api/v1/analytics/dashboards 캡처 모킹
    const newDashboard = createDashboard({ id: 10, name: '테스트 대시보드' });
    const postCapture = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/dashboards',
      newDashboard,
      { capture: true },
    );

    // 생성 후 에디터 이동을 위한 추가 API 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/10', newDashboard);
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/10/data', { dashboardId: 10, widgets: [] });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    // "새 대시보드" 버튼 클릭
    await page.getByRole('button', { name: '새 대시보드' }).click();

    // 생성 다이얼로그가 열리는지 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: '새 대시보드' })).toBeVisible();

    // 이름 입력 필드에 '테스트 대시보드' 입력
    await page.getByLabel('이름 *').fill('테스트 대시보드');

    // "생성" 버튼 클릭
    await page.getByRole('button', { name: '생성' }).click();

    // POST API가 호출되었는지, payload에 name이 포함되었는지 검증
    const captured = await postCapture.waitForRequest();
    expect(captured.payload).toMatchObject({ name: '테스트 대시보드' });
  });

  test('대시보드 생성 다이얼로그에 이름 입력 필드가 있다', async ({ authenticatedPage: page }) => {
    await setupDashboardListMocks(page, 1);
    await page.goto('/analytics/dashboards');

    await page.getByRole('button', { name: '새 대시보드' }).click();

    // 다이얼로그 내 이름 입력 필드 확인
    await expect(page.getByLabel('이름 *')).toBeVisible();
  });

  test('삭제 버튼 클릭 시 확인 다이얼로그가 열리고 DELETE API가 호출된다', async ({ authenticatedPage: page }) => {
    await setupDashboardListMocks(page, 2);
    await page.goto('/analytics/dashboards');

    // DELETE /api/v1/analytics/dashboards/1 캡처 모킹
    const deleteCapture = await mockApi(
      page,
      'DELETE',
      '/api/v1/analytics/dashboards/1',
      {},
      { capture: true },
    );

    // 첫 번째 행의 삭제 버튼 클릭 (aria-label="삭제")
    const deleteButtons = page.getByRole('button', { name: '삭제' });
    await deleteButtons.first().click();

    // 삭제 확인 다이얼로그가 열리는지 확인
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // 다이얼로그의 확인(삭제) 버튼 클릭
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    // DELETE API가 실제로 호출되었는지 검증
    const captured = await deleteCapture.waitForRequest();
    expect(captured).toBeDefined();
  });

  test('공유 대시보드에 공유 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    // isShared: true 대시보드 1개 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards',
      createPageResponse([
        createDashboardListItem({ id: 1, name: '공유 대시보드', isShared: true }),
      ]),
    );

    await page.goto('/analytics/dashboards');

    // "공유" 뱃지 확인
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: /^공유$/ })).toBeVisible();
  });

  test('대시보드 검색 시 search 파라미터가 API 에 전달된다', async ({ authenticatedPage: page }) => {
    const searchCalls: string[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/dashboards',
      (route) => {
        const url = new URL(route.request().url());
        searchCalls.push(url.searchParams.get('search') ?? '');
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            createPageResponse([
              createDashboardListItem({ id: 1, name: '검색된 대시보드' }),
            ]),
          ),
        });
      },
    );

    await page.goto('/analytics/dashboards');
    await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible();

    // 검색어 입력
    await page.getByPlaceholder('대시보드 검색...').fill('검색된');

    // 검색 결과 반영 대기
    await expect(page.getByText('검색된 대시보드')).toBeVisible();

    // search 파라미터가 전달되었는지 확인
    expect(searchCalls.some((s) => s.includes('검색된'))).toBe(true);
  });

  test('자동 갱신 초가 설정된 대시보드에 갱신 배지가 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards',
      createPageResponse([
        createDashboardListItem({ id: 1, name: '자동갱신 대시보드', autoRefreshSeconds: 30 }),
      ]),
    );

    await page.goto('/analytics/dashboards');

    // autoRefreshSeconds=30 → "30초" 배지가 표시된다
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '30초' })).toBeVisible();
  });

  test('공유됨 탭 전환 시 sharedOnly 파라미터가 API 에 전달된다', async ({ authenticatedPage: page }) => {
    const tabCalls: string[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/dashboards',
      (route) => {
        const url = new URL(route.request().url());
        tabCalls.push(url.searchParams.get('sharedOnly') ?? '');
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse([])),
        });
      },
    );

    await page.goto('/analytics/dashboards');
    await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible();

    // "공유됨" 탭 클릭
    await page.getByRole('tab', { name: '공유됨' }).click();

    await page.waitForTimeout(300);
    expect(tabCalls.some((v) => v === 'true')).toBe(true);
  });
});
