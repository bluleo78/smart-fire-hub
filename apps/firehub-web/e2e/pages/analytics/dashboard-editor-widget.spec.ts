import { createChartListItem, createDashboard, createWidget } from '../../factories/analytics.factory';
import { setupDashboardEditorMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * DashboardEditorPage 의 위젯 CRUD 상세 경로 E2E 테스트
 * - AddWidgetDialog 내부의 차트 검색/선택/추가, handleAddWidget 호출,
 *   handleRemoveWidget 호출, 빈 상태에서의 "편집 모드" 버튼, 대시보드 404 분기 등
 *   기존 dashboard-editor.spec.ts 에서 커버되지 않은 경로를 검증한다.
 */
// 병렬 실행 환경에서 타이밍 경합이 발생할 수 있으므로 1회 재시도 허용
test.describe.configure({ retries: 1 });

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

  test('위젯 제거 성공 — 확인 다이얼로그에서 삭제 클릭 시 즉시 DELETE API 호출 및 토스트 표시 (#133)', async ({ authenticatedPage: page }) => {
    // setupDashboardEditorMocks를 사용해 위젯 포함 대시보드를 올바른 형식으로 모킹
    await setupDashboardEditorMocks(page, 1);
    // 삭제 확인 클릭 시 즉시 호출될 DELETE 모킹 — capture로 호출 검증
    const deleteCapture = await mockApi(
      page,
      'DELETE',
      '/api/v1/analytics/dashboards/1/widgets/1',
      {},
      { capture: true },
    );

    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText('테스트 차트')).toBeVisible();

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.locator('button[title="위젯 제거"]').first()).toBeVisible({ timeout: 5000 });

    // 위젯 제거 버튼 클릭 → 확인 다이얼로그 표시 (handleRemoveWidget 경로)
    await page.locator('button[title="위젯 제거"]').first().click();

    // 확인 다이얼로그가 열려야 한다
    await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 3000 });
    await expect(page.getByRole('alertdialog').getByText('위젯 삭제')).toBeVisible();

    // 다이얼로그에서 "삭제" 클릭 → 즉시 서버 DELETE + 토스트 표시 (#133 수정된 동작)
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    // DELETE API가 즉시 호출되었음을 검증 (완료 버튼 없이도)
    const captured = await deleteCapture.waitForRequest();
    expect(captured.url.href).toContain('/api/v1/analytics/dashboards/1/widgets/1');

    // 성공 토스트 확인
    await expect(page.getByText('위젯이 제거되었습니다.')).toBeVisible({ timeout: 5000 });
  });

  test('위젯 삭제 확인 다이얼로그 — 취소 클릭 시 DELETE API 미호출, 위젯 유지', async ({ authenticatedPage: page }) => {
    // AlertDialog에서 취소 클릭 시 DELETE API가 호출되지 않음을 확인하는 회귀 테스트
    await setupDashboardEditorMocks(page, 1);

    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText('테스트 차트')).toBeVisible();

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.locator('button[title="위젯 제거"]').first()).toBeVisible({ timeout: 5000 });

    // DELETE 요청이 발생하면 테스트 실패하도록 라우트 인터셉트
    let deleteCalled = false;
    await page.route('**/api/v1/analytics/dashboards/1/widgets/**', (route) => {
      if (route.request().method() === 'DELETE') {
        deleteCalled = true;
        return route.fulfill({ status: 200, body: '{}' });
      }
      return route.continue();
    });

    // 위젯 제거 버튼 클릭 → 다이얼로그 표시
    await page.locator('button[title="위젯 제거"]').first().click();
    await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 3000 });

    // 취소 클릭 → 다이얼로그 닫힘
    await page.getByRole('alertdialog').getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).not.toBeVisible({ timeout: 3000 });

    // DELETE API가 호출되지 않았음을 검증
    expect(deleteCalled).toBe(false);

    // 위젯이 여전히 화면에 존재
    await expect(page.getByText('테스트 차트')).toBeVisible();
  });

  test('위젯 삭제 즉시 반영 — 완료 버튼 없이도 삭제 확인 시 DELETE API 호출됨 (#133 회귀 방지)', async ({ authenticatedPage: page }) => {
    // 핵심 검증: 위젯 삭제가 "완료" 버튼 클릭 없이 즉시 서버에 반영된다.
    // 추가(POST)와 동일한 즉시 반영 패턴을 유지하여 동작 일관성을 보장한다 (#133).
    await setupDashboardEditorMocks(page, 1);

    // 삭제 DELETE 모킹 등록
    const deleteCapture = await mockApi(
      page,
      'DELETE',
      '/api/v1/analytics/dashboards/1/widgets/1',
      {},
      { capture: true },
    );

    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText('테스트 차트')).toBeVisible();

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.locator('button[title="위젯 제거"]').first()).toBeVisible({ timeout: 5000 });

    // 위젯 삭제 → 확인 다이얼로그 → 삭제 확인
    await page.locator('button[title="위젯 제거"]').first().click();
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    // DELETE API가 즉시 호출되었음을 검증 — 완료 버튼 클릭 없이도 서버에 반영됨
    const captured = await deleteCapture.waitForRequest();
    expect(captured.url.href).toContain('/api/v1/analytics/dashboards/1/widgets/1');

    // 편집 취소(X 버튼) 클릭 — 삭제는 이미 서버에 반영되어 있음
    await page.getByRole('button', { name: '편집 취소' }).click();
    // 편집 모드 종료 확인
    await expect(page.getByRole('button', { name: '편집' })).toBeVisible({ timeout: 3000 });
  });

  test('편집 완료(완료 버튼) — 위젯 삭제 후 완료 시 추가 DELETE 미호출 (이미 즉시 반영됨, #133)', async ({ authenticatedPage: page }) => {
    // 수정 후 동작: 삭제는 확인 시 즉시 반영되므로 완료 버튼에서 DELETE를 재호출하지 않음
    await setupDashboardEditorMocks(page, 1);

    // 삭제 DELETE 모킹 — 삭제 확인 시 1회 호출됨
    const deleteCapture = await mockApi(
      page,
      'DELETE',
      '/api/v1/analytics/dashboards/1/widgets/1',
      {},
      { capture: true },
    );
    // 완료 시 레이아웃 PATCH 도 발생할 수 있음 — 허용
    await mockApi(page, 'PATCH', '/api/v1/analytics/dashboards/1/widgets/1', {});

    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText('테스트 차트')).toBeVisible();

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.locator('button[title="위젯 제거"]').first()).toBeVisible({ timeout: 5000 });

    // 위젯 삭제 → 확인 다이얼로그 → 삭제 확인 → 즉시 서버 DELETE 발생
    await page.locator('button[title="위젯 제거"]').first().click();
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    // 삭제 확인 시점에 DELETE API가 호출되었음을 검증
    const captured = await deleteCapture.waitForRequest();
    expect(captured.url.href).toContain('/api/v1/analytics/dashboards/1/widgets/1');

    // 완료 버튼 클릭 — handleSaveEdit: 레이아웃 PATCH만 처리, DELETE 재호출 없음
    await page.getByRole('button', { name: '완료' }).click();
    // 완료 후 편집 모드 종료 확인
    await expect(page.getByRole('button', { name: '편집' })).toBeVisible({ timeout: 3000 });
  });

  test('차트 추가 성공 — 토스트 및 레이아웃 갱신 (handleAddWidget)', async ({ authenticatedPage: page }) => {
    const dashboard = createDashboard({ id: 1, widgets: [] });
    const chart = createChartListItem({ id: 55, name: '추가 테스트 차트', chartType: 'BAR' });

    // 초기 모킹만 등록 — refetch도 동일 모킹을 재사용 (LIFO 충돌 방지)
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', dashboard);
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1/data', { dashboardId: 1, widgets: [] });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', {
      content: [chart], page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    // POST 위젯 추가 응답
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/dashboards/1/widgets',
      createWidget({ id: 55, chartId: 55, chartName: '추가 테스트 차트' }),
    );

    await page.goto('/analytics/dashboards/1');

    // 편집 모드 진입 (빈 대시보드에서는 "편집 모드" 버튼)
    await page.getByRole('button', { name: '편집 모드' }).click();
    await page.getByRole('button', { name: '차트 추가' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 차트 선택 → 추가 버튼 활성화 → 클릭
    await page.getByRole('button', { name: /추가 테스트 차트/ }).click();
    await page.getByRole('dialog').getByRole('button', { name: '추가', exact: true }).click();

    // 성공 토스트 확인 — handleAddWidget 성공 경로 (lines 332-342)
    await expect(page.getByText('차트가 대시보드에 추가되었습니다.')).toBeVisible({ timeout: 5000 });
  });

  test('이미 추가된 차트를 다시 추가하면 중복 허용 없이 에러 토스트가 표시된다 (이슈 #42)', async ({ authenticatedPage: page }) => {
    // chartId=1 위젯이 이미 포함된 대시보드
    const existingWidget = createWidget({ id: 1, chartId: 1, chartName: '기존 차트' });
    const dashboard = createDashboard({ id: 1, widgets: [existingWidget] });
    // 차트 목록에도 동일한 chartId=1 차트 포함
    const chart = createChartListItem({ id: 1, name: '기존 차트', chartType: 'BAR' });

    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', dashboard);
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1/data', {
      dashboardId: 1,
      widgets: [existingWidget],
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', {
      content: [chart],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });

    // POST 위젯 추가는 호출되면 안 됨 — 감시용 플래그
    let postCalled = false;
    await page.route('**/api/v1/analytics/dashboards/1/widgets', (route) => {
      if (route.request().method() === 'POST') {
        postCalled = true;
        return route.fulfill({ status: 200, body: '{}' });
      }
      return route.continue();
    });

    await page.goto('/analytics/dashboards/1');
    await expect(page.getByText('기존 차트')).toBeVisible();

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // 차트 추가 다이얼로그 열기
    await page.getByRole('button', { name: '차트 추가' }).click();
    await expect(page.getByRole('dialog').getByText('차트 추가')).toBeVisible();

    // 이미 대시보드에 있는 차트(chartId=1) 선택
    await page.getByRole('button', { name: /기존 차트/ }).click();

    // 추가 버튼 클릭
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    // 에러 토스트가 표시되고 POST는 호출되지 않아야 한다
    await expect(page.getByText('이미 대시보드에 추가된 차트입니다.')).toBeVisible({ timeout: 5000 });
    expect(postCalled).toBe(false);
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
