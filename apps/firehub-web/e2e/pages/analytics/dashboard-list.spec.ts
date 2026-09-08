import { createDashboard, createDashboardListItem } from '../../factories/analytics.factory';
import { setupDashboardListMocks } from '../../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 목록 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 목록 페이지 UI를 검증한다.
 */
test.describe('대시보드 목록 페이지', () => {
  test('대시보드 목록이 올바르게 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
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
    // exact:true 필수 — 부분일치면 수정일 상대시간("N개월 전")의 "…1개…"와도 매칭돼 strict 위반이 난다.
    const firstRow = page.getByRole('row').nth(1);
    await expect(firstRow.getByText('1개', { exact: true })).toBeVisible();
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

  test('새 대시보드 버튼 클릭 시 생성 다이얼로그가 열리고 POST payload가 전달된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
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

  test('생성 버튼을 빠르게 두 번 클릭해도 POST가 한 번만 호출된다 (#546)', async ({ authenticatedPage: page }) => {
    await setupDashboardListMocks(page, 1);
    await page.goto('/analytics/dashboards');

    // 응답을 약간 지연시켜 isPending 리렌더가 반영되기 전에
    // 두 번째 클릭이 도착할 수 있는 레이스 윈도우를 넓힌다.
    let postCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/dashboards' && url.search === '',
      async (route) => {
        if (route.request().method() !== 'POST') return route.continue();
        postCount += 1;
        await new Promise((resolve) => setTimeout(resolve, 200));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createDashboard({ id: 10, name: '더블클릭 대시보드' })),
        });
      },
    );
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/10', createDashboard({ id: 10, name: '더블클릭 대시보드' }));
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/10/data', { dashboardId: 10, widgets: [] });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    await page.getByRole('button', { name: '새 대시보드' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByLabel('이름 *').fill('더블클릭 대시보드');

    // "생성" 버튼을 빠르게 두 번 클릭 (더블클릭 시뮬레이션)
    const createButton = page.getByRole('dialog').getByRole('button', { name: '생성' });
    await createButton.dblclick();

    // 다이얼로그가 닫힐 때까지(=요청 완료) 대기 후 POST 호출 횟수 검증
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 5000 });
    expect(postCount).toBe(1);
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

  test('마지막 페이지의 유일한 항목을 삭제하면 이전 페이지로 이동해 남은 항목을 보여준다 (#549)', async ({ authenticatedPage: page }) => {
    // 총 11건 → size=10 기준 2페이지. 1페이지(page=0)에 10건, 2페이지(page=1)에 1건.
    // 삭제 성공 후 목록 쿼리가 무효화되어 같은 page(=1)로 재조회되는데, 서버에는 더 이상
    // 해당 페이지 데이터가 없다(totalPages: 1) — 프론트가 page를 보정하지 않으면
    // "결과 없음" 빈 상태로 오표시된다.
    const firstPageItems = Array.from({ length: 10 }, (_, i) =>
      createDashboardListItem({ id: i + 1, name: `테스트 대시보드 ${i + 1}` }),
    );
    const lastItem = createDashboardListItem({ id: 11, name: '테스트 대시보드 11' });
    let deleted = false;

    await page.route(
      (url) => url.pathname === '/api/v1/analytics/dashboards',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const requestedPage = new URL(route.request().url()).searchParams.get('page') ?? '0';

        if (!deleted) {
          // 삭제 전: page=0 → 10건, page=1 → 1건 (totalPages: 2)
          const body =
            requestedPage === '1'
              ? createPageResponse([lastItem], { page: 1, totalElements: 11, totalPages: 2 })
              : createPageResponse(firstPageItems, { page: 0, totalElements: 11, totalPages: 2 });
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
        }

        // 삭제 후: 서버 기준 이제 10건뿐 (totalPages: 1) — page=1 요청 시 빈 배열 반환
        const body =
          requestedPage === '1'
            ? createPageResponse([], { page: 1, totalElements: 10, totalPages: 1 })
            : createPageResponse(firstPageItems, { page: 0, totalElements: 10, totalPages: 1 });
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      },
    );
    await mockApi(page, 'DELETE', '/api/v1/analytics/dashboards/11', {});

    await page.goto('/analytics/dashboards');
    await expect(page.getByText('테스트 대시보드 1', { exact: true })).toBeVisible();

    // 2 페이지로 이동 → 유일한 항목(테스트 대시보드 11) 확인
    await page.getByRole('button', { name: '2 페이지' }).click();
    await expect(page.getByText('테스트 대시보드 11')).toBeVisible();

    // 삭제 실행
    await page.getByRole('button', { name: '삭제' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    deleted = true;
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    // "결과 없음" 빈 상태가 아니라, 1페이지로 자동 이동해 남은 10건이 다시 보여야 한다
    await expect(page.getByText('대시보드가 없습니다.')).not.toBeVisible();
    await expect(page.getByText('테스트 대시보드 1', { exact: true })).toBeVisible();
    await expect(page.getByText('테스트 대시보드 10', { exact: true })).toBeVisible();
    // 삭제 후 totalPages: 1이 되어 페이지네이션 네비게이션 자체가 사라진다(SimplePagination 규칙)
    await expect(page.getByRole('button', { name: '2 페이지' })).not.toBeVisible();
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

  test('자동 새로고침 값을 지우고 저장하면 clearAutoRefresh 플래그와 함께 전송된다 (#568)', async ({
    authenticatedPage: page,
  }) => {
    // 이전에는 autoRefreshSeconds: null만 보내 백엔드가 "미제공"으로 오인,
    // 값이 그대로 남던 회귀(#568)를 검증한다. 프론트는 clearAutoRefresh: true를 함께 보내야 한다.
    const dashboard = createDashboardListItem({ id: 1, name: '갱신 대시보드', autoRefreshSeconds: 30 });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([dashboard]));
    const putCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/analytics/dashboards/1',
      { ...dashboard, autoRefreshSeconds: null },
      { capture: true },
    );

    await page.goto('/analytics/dashboards');
    await page.getByRole('row', { name: /갱신 대시보드/ }).getByLabel('설정').click();
    await expect(page.getByRole('dialog', { name: '대시보드 설정' })).toBeVisible();

    const refreshInput = page.getByLabel('자동 새로고침 (초)');
    await expect(refreshInput).toHaveValue('30');
    await refreshInput.fill('');
    await page.getByRole('button', { name: '저장' }).click();

    await expect(page.getByRole('dialog', { name: '대시보드 설정' })).not.toBeVisible();
    const req = await putCapture.waitForRequest();
    expect(req.payload).toMatchObject({ autoRefreshSeconds: null, clearAutoRefresh: true });
  });

  test('자동 새로고침에 소수점 값을 입력하면 저장이 거부되고 에러 토스트가 표시된다 (#568)', async ({
    authenticatedPage: page,
  }) => {
    // 이전에는 parseInt(autoRefresh, 10)가 "5.5"를 소리 없이 "5"로 잘라 저장했다.
    // 이제는 정수가 아니면 저장을 막고 에러를 알려야 한다.
    const dashboard = createDashboardListItem({ id: 1, name: '소수점 대시보드', autoRefreshSeconds: null });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([dashboard]));
    const putCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/analytics/dashboards/1',
      { ...dashboard, autoRefreshSeconds: 5 },
      { capture: true },
    );

    await page.goto('/analytics/dashboards');
    await page.getByRole('row', { name: /소수점 대시보드/ }).getByLabel('설정').click();
    await expect(page.getByRole('dialog', { name: '대시보드 설정' })).toBeVisible();

    await page.getByLabel('자동 새로고침 (초)').fill('5.5');
    await page.getByRole('button', { name: '저장' }).click();

    // 에러 토스트가 표시되고 다이얼로그는 닫히지 않으며, 저장 API는 호출되지 않아야 한다
    await expect(page.getByText('자동 새로고침은 5 이상의 정수(초)로 입력해 주세요.')).toBeVisible();
    await expect(page.getByRole('dialog', { name: '대시보드 설정' })).toBeVisible();
    expect(putCapture.requests.length).toBe(0);
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

  test('설정 다이얼로그를 취소 후 다시 열면 저장된 값이 표시된다 (#553)', async ({ authenticatedPage: page }) => {
    // 부모(DashboardListPage)가 "설정" 클릭 시 목록에 이미 있던 동일 참조의
    // dashboard 객체를 다시 넘기므로, EditDashboardDialog가 이를 "변경 없음"으로
    // 오인해 취소 직전 미저장 입력값을 리셋하지 않던 회귀(#553)를 검증한다.
    const dashboard = createDashboardListItem({ id: 1, name: '원본 이름', description: '원본 설명' });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([dashboard]));
    // 취소 흐름에서는 저장 API가 절대 호출되지 않아야 하므로 호출 여부를 감시한다.
    const putCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/analytics/dashboards/1',
      { ...dashboard },
      { capture: true },
    );

    await page.goto('/analytics/dashboards');
    await expect(page.getByText('원본 이름')).toBeVisible();

    // 1) 설정 다이얼로그를 열고 이름/설명을 변경한 뒤 취소
    await page.getByRole('row', { name: /원본 이름/ }).getByLabel('설정').click();
    await expect(page.getByRole('dialog', { name: '대시보드 설정' })).toBeVisible();
    const nameInput = page.getByLabel('이름 *');
    const descInput = page.getByLabel('설명');
    await expect(nameInput).toHaveValue('원본 이름');
    await expect(descInput).toHaveValue('원본 설명');

    await nameInput.fill('DIRTY_UNSAVED_NAME');
    await descInput.fill('DIRTY_UNSAVED_DESC');
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('dialog', { name: '대시보드 설정' })).not.toBeVisible();

    // 2) 같은 대시보드의 설정을 다시 열면 원본 값이 그대로 보여야 한다 (미저장 값이 남으면 회귀)
    await page.getByRole('row', { name: /원본 이름/ }).getByLabel('설정').click();
    await expect(page.getByRole('dialog', { name: '대시보드 설정' })).toBeVisible();
    await expect(page.getByLabel('이름 *')).toHaveValue('원본 이름');
    await expect(page.getByLabel('설명')).toHaveValue('원본 설명');

    // 취소만 반복했으므로 저장 API는 한 번도 호출되지 않아야 한다
    expect(putCapture.requests.length).toBe(0);
  });

  test('이름이 매우 긴 대시보드도 테이블 레이아웃이 깨지지 않는다 (#563)', async ({ authenticatedPage: page }) => {
    // 회귀 방지: 이름 span에 truncate가 없으면 긴 이름이 테이블 전체 폭을
    // 뷰포트 밖으로 밀어내 상태/생성자/작업 버튼 컬럼이 가려진다.
    const longName = '가'.repeat(500);
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards',
      createPageResponse([createDashboardListItem({ id: 99, name: longName })]),
    );

    await page.goto('/analytics/dashboards');
    const dataRow = page.getByRole('row').nth(1);
    await expect(dataRow).toBeVisible();

    // 이름 span은 잘려 보이되 title 속성으로 전체 이름을 노출해야 한다
    const nameSpan = dataRow.locator('span[title]').first();
    await expect(nameSpan).toHaveAttribute('title', longName);
    await expect(nameSpan).toHaveClass(/truncate/);

    // 테이블 전체 폭이 뷰포트를 벗어나지 않아야 하며,
    // 같은 행의 "삭제" 작업 버튼(가장 우측 컬럼)이 화면 밖으로 밀려나지 않고 보여야 한다
    const table = page.getByRole('table');
    const viewportWidth = page.viewportSize()?.width ?? 1280;
    const tableBox = await table.boundingBox();
    expect(tableBox?.width ?? 0).toBeLessThanOrEqual(viewportWidth);
    await expect(dataRow.getByLabel('삭제')).toBeVisible();
  });
});
