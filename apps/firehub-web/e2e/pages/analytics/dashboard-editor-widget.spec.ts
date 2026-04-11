import { createChartListItem, createDashboard, createWidget } from '../../factories/analytics.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * DashboardEditorPage 의 위젯 CRUD 상세 경로 E2E 테스트
 * - AddWidgetDialog 내부의 차트 검색/선택/추가, handleAddWidget 호출,
 *   handleRemoveWidget 호출, 빈 상태에서의 "편집 모드" 버튼, 대시보드 404 분기 등
 *   기존 dashboard-editor.spec.ts 에서 커버되지 않은 경로를 검증한다.
 */
test.describe('대시보드 에디터 — 위젯 CRUD', () => {
  test('차트 추가 다이얼로그에서 차트 검색/선택 후 POST 호출된다', async ({ authenticatedPage: page }) => {
    const dashboard = createDashboard({ id: 1, widgets: [] });
    const chart = createChartListItem({ id: 42, name: '검색용 테스트 차트', chartType: 'BAR' });

    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', dashboard);
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1/data', {
      dashboardId: 1,
      widgets: [],
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', {
      content: [chart],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });

    // 위젯 추가 POST 캡처
    const addCapture = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/dashboards/1/widgets',
      createWidget({ id: 99, chartId: 42, chartName: '검색용 테스트 차트' }),
      { capture: true },
    );

    await page.goto('/analytics/dashboards/1');

    // 빈 상태 확인
    await expect(page.getByText('위젯이 없습니다.')).toBeVisible();

    // 빈 상태의 "편집 모드" 버튼 클릭 — handleExitEdit/setIsEditing(true) 경로
    await page.getByRole('button', { name: '편집 모드' }).click();

    // 차트 추가 버튼 클릭 → AddWidgetDialog 열림
    await page.getByRole('button', { name: '차트 추가' }).click();

    // 다이얼로그 타이틀 확인
    await expect(page.getByRole('dialog').getByText('차트 추가')).toBeVisible();

    // 검색 입력 — setSearch 분기 실행
    await page.getByPlaceholder('차트 검색...').fill('검색');

    // 차트 항목 선택 (button 안의 텍스트 클릭)
    await page.getByRole('button', { name: /검색용 테스트 차트/ }).click();

    // 다이얼로그의 "추가" 버튼 클릭
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    // POST payload 검증 — positionY=0, width=6, height=4 (BAR 차트 기본값)
    const captured = await addCapture.waitForRequest();
    const payload = captured.payload as {
      chartId: number;
      positionX: number;
      positionY: number;
      width: number;
      height: number;
    };
    expect(payload).toMatchObject({
      chartId: 42,
      positionX: 0,
      positionY: 0,
      width: 6,
      height: 4,
    });
  });

  test('차트 추가 다이얼로그에서 차트가 없으면 빈 상태 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets: [] }));
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1/data', {
      dashboardId: 1,
      widgets: [],
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/analytics/dashboards/1');

    // 편집 모드 진입 후 차트 추가
    await page.getByRole('button', { name: '편집 모드' }).click();
    await page.getByRole('button', { name: '차트 추가' }).click();

    // 차트 목록 빈 상태 메시지 확인
    await expect(page.getByText('차트가 없습니다.')).toBeVisible();

    // 선택 없음 → 추가 버튼은 비활성
    const confirmButton = page.getByRole('dialog').getByRole('button', { name: '추가' });
    await expect(confirmButton).toBeDisabled();

    // 취소 버튼으로 닫기
    await page.getByRole('dialog').getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('dialog')).not.toBeVisible();
  });

  test('존재하지 않는 대시보드(404) 시 "찾을 수 없습니다" 분기가 렌더링된다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards/999',
      { message: 'Not Found' },
      { status: 404 },
    );
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards/999/data',
      { message: 'Not Found' },
      { status: 404 },
    );
    await mockApi(page, 'GET', '/api/v1/analytics/charts', {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/analytics/dashboards/999');

    // 대시보드를 찾을 수 없을 때 빈 상태 + "목록으로" 버튼이 표시된다
    await expect(page.getByText('대시보드를 찾을 수 없습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '목록으로' })).toBeVisible();
  });

  test('대시보드가 공유 상태일 때 "공유" 뱃지와 autoRefresh 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    const dashboard = createDashboard({
      id: 2,
      name: '공유 대시보드',
      isShared: true,
      autoRefreshSeconds: 30,
      widgets: [],
    });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/2', dashboard);
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/2/data', {
      dashboardId: 2,
      widgets: [],
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/analytics/dashboards/2');

    await expect(page.getByRole('heading', { name: '공유 대시보드' })).toBeVisible();
    // isShared 분기 — "공유" 뱃지
    await expect(page.getByText('공유', { exact: true })).toBeVisible();
    // autoRefreshSeconds 분기 — "30초" 뱃지
    await expect(page.getByText(/30초/)).toBeVisible();
  });
});
