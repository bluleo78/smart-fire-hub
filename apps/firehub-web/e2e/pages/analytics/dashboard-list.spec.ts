import { createDashboard, createDashboardListItem } from '../../factories/analytics.factory';
import { openFirstRowDeleteDialog, setupDashboardListMocks } from '../../fixtures/analytics.fixture';
import { createPageResponse, mockApi, mockDeleteOnceThenNotFound } from '../../fixtures/api-mock';
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
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    // "새 대시보드" 버튼 클릭
    await page.getByRole('button', { name: '새 대시보드' }).click();

    // 생성 다이얼로그가 열리는지 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: '새 대시보드' })).toBeVisible();

    // 이름 입력 필드에 '테스트 대시보드' 입력
    await page.getByLabel('이름 *').fill('테스트 대시보드');

    // "생성" 버튼 클릭
    await page.getByRole('button', { name: '만들기' }).click();

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
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    await page.getByRole('button', { name: '새 대시보드' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByLabel('이름 *').fill('더블클릭 대시보드');

    // "생성" 버튼을 빠르게 두 번 클릭 (더블클릭 시뮬레이션)
    const createButton = page.getByRole('dialog').getByRole('button', { name: '만들기' });
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

  test('확인 창의 「삭제」를 더블클릭해도 DELETE 는 한 번만 나가고 오류 토스트가 뜨지 않는다 (WD-507)', async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardListMocks(page, 2);
    // 첫 DELETE 204, 두 번째부터 404 — 결함이 있으면 성공·「찾을 수 없습니다」 토스트가 함께 뜬다.
    const del = await mockDeleteOnceThenNotFound(page, '/api/v1/analytics/dashboards/1', '대시보드를 찾을 수 없습니다.');
    await page.goto('/analytics/dashboards');

    const dialog = await openFirstRowDeleteDialog(page);
    const confirm = dialog.getByRole('button', { name: '삭제' });
    // 영향 범위 조회 등으로 잠시 꺼져 있을 수 있어 켜진 뒤 누른다.
    await expect(confirm).toBeEnabled();
    await confirm.dblclick();

    await expect(page.getByText(/대시보드 "테스트 대시보드 1".*삭제되었습니다/)).toBeVisible();
    // 두 번째 요청이 늦게 나가는 경우까지 잡도록 잠시 기다린 뒤 횟수를 본다.
    await page.waitForTimeout(300);
    expect(del.requests).toEqual(['/api/v1/analytics/dashboards/1']);
    await expect(page.getByText('찾을 수 없습니다')).toHaveCount(0);
  });

  test('취소로 닫았다 다시 연 확인 창에서도 삭제할 수 있다 — 한 번만 확인 가드는 창을 열 때마다 초기화된다 (WD-507)', async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardListMocks(page, 2);
    const del = await mockDeleteOnceThenNotFound(page, '/api/v1/analytics/dashboards/1', '대시보드를 찾을 수 없습니다.');
    await page.goto('/analytics/dashboards');

    let dialog = await openFirstRowDeleteDialog(page);
    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(dialog).toBeHidden();

    dialog = await openFirstRowDeleteDialog(page);
    const confirm = dialog.getByRole('button', { name: '삭제' });
    await expect(confirm).toBeEnabled();
    await confirm.click();

    await expect(page.getByText(/대시보드 "테스트 대시보드 1".*삭제되었습니다/)).toBeVisible();
    expect(del.requests).toEqual(['/api/v1/analytics/dashboards/1']);
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

    // search 파라미터가 전달되었는지 확인 — 목록 요청은 300ms 디바운스 뒤에 나가므로 도착을 기다린다 (WD-513)
    await expect.poll(() => searchCalls.some((s) => s.includes('검색된'))).toBe(true);
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

  test('자동 새로고침에 소수점 값을 입력하면 저장이 거부되고 칸 아래 오류가 표시된다 (#568, WD-876)', async ({
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

    // 칸 아래 오류(role=alert)가 표시되고 다이얼로그는 닫히지 않으며, 저장 API는 호출되지 않아야 한다
    await expect(page.locator('#edit-dash-refresh-error')).toHaveText('자동 새로고침은 5 이상의 정수(초)로 입력해 주세요.');
    await expect(page.getByRole('dialog', { name: '대시보드 설정' })).toBeVisible();
    expect(putCapture.requests.length).toBe(0);
  });

  test('자동 새로고침이 24시간(86400초)을 넘으면 저장을 막고, 86400초는 그대로 저장한다 (WD-327)', async ({
    authenticatedPage: page,
  }) => {
    // 상한이 없을 때 2500000초 같은 값이 저장되면 브라우저 타이머가 넘쳐 위젯 데이터를 쉬지 않고 다시 요청했다.
    const dashboard = createDashboardListItem({ id: 1, name: '상한 대시보드', autoRefreshSeconds: null });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([dashboard]));
    const putCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/analytics/dashboards/1',
      { ...dashboard, autoRefreshSeconds: 86400 },
      { capture: true },
    );

    await page.goto('/analytics/dashboards');
    await page.getByRole('row', { name: /상한 대시보드/ }).getByLabel('설정').click();
    const dialog = page.getByRole('dialog', { name: '대시보드 설정' });
    await expect(dialog).toBeVisible();

    // 상한 + 1 → 안내가 뜨고 창은 그대로, 저장 요청은 가지 않는다
    await page.getByLabel('자동 새로고침 (초)').fill('86401');
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByText('자동 새로고침은 86400초(24시간) 이하로 입력해 주세요.')).toBeVisible();
    await expect(dialog).toBeVisible();
    expect(putCapture.requests.length).toBe(0);

    // 상한 값 그대로 → 저장 요청에 86400 이 실린다
    await page.getByLabel('자동 새로고침 (초)').fill('86400');
    await page.getByRole('button', { name: '저장' }).click();
    const req = await putCapture.waitForRequest();
    expect(req.payload).toMatchObject({ autoRefreshSeconds: 86400 });
  });

  test('「내 대시보드」 탭은 mineOnly=true 만, 「공유됨」 탭은 sharedOnly=true 만 API 에 전달된다 (WD-328)', async ({
    authenticatedPage: page,
  }) => {
    // 「내 대시보드」 탭이 필터 없이 부르면 서버가 남의 공유 대시보드까지 섞어 주므로 mineOnly=true 를 실어야 한다
    const tabCalls: { mineOnly: string | null; sharedOnly: string | null }[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/dashboards',
      (route) => {
        const url = new URL(route.request().url());
        tabCalls.push({
          mineOnly: url.searchParams.get('mineOnly'),
          sharedOnly: url.searchParams.get('sharedOnly'),
        });
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse([])),
        });
      },
    );

    await page.goto('/analytics/dashboards');
    await expect(page.getByRole('heading', { name: '대시보드' })).toBeVisible();

    // 기본(「내 대시보드」) 탭: mineOnly=true, sharedOnly 없음
    await expect.poll(() => tabCalls.length).toBeGreaterThan(0);
    expect(tabCalls[0]).toEqual({ mineOnly: 'true', sharedOnly: null });

    // 「공유됨」 탭: sharedOnly=true, mineOnly 없음 — 내 공유 대시보드도 포함되는 기존 동작 유지
    const before = tabCalls.length;
    await page.getByRole('tab', { name: '공유됨' }).click();
    await expect.poll(() => tabCalls.length).toBeGreaterThan(before);
    expect(tabCalls[tabCalls.length - 1]).toEqual({ mineOnly: null, sharedOnly: 'true' });
  });

  test('설명을 지우고 저장하면 빈 문자열로 전송된다 (WD-197)', async ({ authenticatedPage: page }) => {
    // 이전에는 지운 설명을 undefined 로 보내 필드가 빠졌고, 서버가 "변경 없음"으로 보고 옛 설명을 남겼다.
    const dashboard = createDashboardListItem({ id: 1, name: '설명 대시보드', description: '지울 설명' });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([dashboard]));
    const putCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/analytics/dashboards/1',
      { ...dashboard, description: null },
      { capture: true },
    );

    await page.goto('/analytics/dashboards');
    await page.getByRole('row', { name: /설명 대시보드/ }).getByLabel('설정').click();
    await expect(page.getByRole('dialog', { name: '대시보드 설정' })).toBeVisible();
    const descInput = page.getByLabel('설명');
    await expect(descInput).toHaveValue('지울 설명');
    await descInput.fill('');
    await page.getByRole('button', { name: '저장' }).click();

    const req = await putCapture.waitForRequest();
    expect(req.payload).toMatchObject({ description: '' });
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

  test('대시보드 설명 입력란은 서버 상한(2,000자)까지만 입력받는다 (WD-425)', async ({ authenticatedPage: page }) => {
    // 서버가 2,000자를 넘는 설명을 400 으로 거부하므로, 생성·설정 다이얼로그 모두 같은 길이에서 입력을 막아야 한다.
    const dashboard = createDashboardListItem({ id: 1, name: '상한 확인 대시보드', description: '설명' });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([dashboard]));
    await page.goto('/analytics/dashboards');

    // 1) 새 대시보드 다이얼로그 — 입력란의 maxlength 가 서버 상한과 같다
    await page.getByRole('button', { name: '새 대시보드' }).click();
    const createDialog = page.getByRole('dialog', { name: '새 대시보드' });
    await expect(createDialog.getByLabel('설명')).toHaveAttribute('maxlength', '2000');
    await createDialog.getByRole('button', { name: '취소' }).click();
    await expect(createDialog).not.toBeVisible();

    // 2) 설정 다이얼로그 — 같은 상한
    await page.getByRole('row', { name: /상한 확인 대시보드/ }).getByLabel('설정').click();
    const editDialog = page.getByRole('dialog', { name: '대시보드 설정' });
    await expect(editDialog.getByLabel('설명')).toHaveAttribute('maxlength', '2000');
  });
});

/**
 * WD-876 — 자동 새로고침 검증 오류를 토스트 대신 입력 칸 아래에 보이고, 허용 범위를 칸에서 안내한다.
 * 토스트는 몇 초 뒤 사라져 무엇이 틀렸는지 다시 볼 수 없고 칸과 이어지지 않았다.
 */
test.describe('자동 새로고침 인라인 오류 (WD-876)', () => {
  const HINT = '5~86400초(24시간) 사이의 정수. 비워 두면 수동 새로고침';
  const ERR_MIN = '자동 새로고침은 5 이상의 정수(초)로 입력해 주세요.';

  /** 생성 다이얼로그를 열고 POST 캡처를 건 상태를 준비한다 */
  async function openCreateDialog(page: import('@playwright/test').Page) {
    await setupDashboardListMocks(page, 1);
    await page.goto('/analytics/dashboards');
    const newDashboard = createDashboard({ id: 10, name: '운영 현황' });
    const post = await mockApi(page, 'POST', '/api/v1/analytics/dashboards', newDashboard, { capture: true });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/10', newDashboard);
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    await page.getByRole('button', { name: '새 대시보드' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    return { dialog, post };
  }

  /**
   * 칸에 포커스가 **닿는 그 순간**의 aria-describedby·aria-invalid 를 기록한다(WD-876 리뷰 후속 반려).
   * 오류에 role=alert 가 없으므로(announceError={false}) 처음 실패한 오류는 포커스 순간 칸에 연결돼 있어야만 읽힌다 —
   * 포커스를 오류가 그려지기 전에 옮기면 그 순간 연결이 비어 있어 최종 DOM 만 보는 단언으로는 잡히지 않는다.
   */
  async function recordFocusMoments(input: import('@playwright/test').Locator) {
    await input.evaluate((el) => {
      const log: { describedBy: string; invalid: string | null }[] = [];
      (window as unknown as { __focusLog: typeof log }).__focusLog = log;
      el.addEventListener('focus', () =>
        log.push({ describedBy: el.getAttribute('aria-describedby') ?? '', invalid: el.getAttribute('aria-invalid') }),
      );
    });
  }
  /** 기록된 마지막 포커스 순간 */
  async function lastFocusMoment(page: import('@playwright/test').Page) {
    return page.evaluate(() => {
      const log = (window as unknown as { __focusLog: { describedBy: string; invalid: string | null }[] }).__focusLog;
      return log[log.length - 1];
    });
  }

  test('처음 실패한 오류도 읽힌다 — 포커스가 닿는 순간 칸에 오류가 이미 연결돼 있다(만들기·설정)', async ({
    authenticatedPage: page,
  }) => {
    const { dialog } = await openCreateDialog(page);
    await dialog.getByLabel('이름 *').fill('운영 현황');
    const input = dialog.getByLabel('자동 새로고침 (초)');
    await input.fill('3');
    await recordFocusMoments(input);
    await dialog.getByRole('button', { name: '만들기' }).click();
    await expect(input).toBeFocused();
    const created = await lastFocusMoment(page);
    expect(created.describedBy.split(' ')).toContain('dash-refresh-error');
    expect(created.invalid).toBe('true');

    // 설정 창 — 같은 규칙
    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(dialog).not.toBeVisible();
    await page.getByRole('row', { name: /대시보드/ }).first().getByLabel('설정').click();
    const settings = page.getByRole('dialog', { name: '대시보드 설정' });
    await expect(settings).toBeVisible();
    const editInput = settings.getByLabel('자동 새로고침 (초)');
    await editInput.fill('5.5');
    await recordFocusMoments(editInput);
    await settings.getByRole('button', { name: '저장' }).click();
    await expect(editInput).toBeFocused();
    const edited = await lastFocusMoment(page);
    expect(edited.describedBy.split(' ')).toContain('edit-dash-refresh-error');
    expect(edited.invalid).toBe('true');
  });

  test('새 대시보드 — 범위를 벗어난 값은 칸 아래 오류로 알리고, 고치면 사라진다', async ({ authenticatedPage: page }) => {
    const { dialog, post } = await openCreateDialog(page);
    const input = dialog.getByLabel('자동 새로고침 (초)');
    await expect(input).toHaveAccessibleDescription(HINT);

    await dialog.getByLabel('이름 *').fill('운영 현황');
    await input.fill('3');
    await dialog.getByRole('button', { name: '만들기' }).click();

    await expect(dialog.locator('#dash-refresh-error')).toHaveText(ERR_MIN);
    // 오류는 칸 포커스로 aria-describedby 가 읽는다 — role=alert 까지 두면 두 번 낭독된다(WD-876 리뷰 후속)
    await expect(dialog.locator('#dash-refresh-error')).not.toHaveAttribute('role', 'alert');
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    // 오류와 범위 안내를 둘 다 읽는다 — 공용 FormField 규칙대로 오류가 먼저
    await expect(input).toHaveAccessibleDescription(`${ERR_MIN} ${HINT}`);
    // 포커스가 틀린 칸으로 간다 — 같은 값으로 다시 눌러도 어디가 틀렸는지 알 수 있다
    await expect(input).toBeFocused();
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
    expect(post.requests.length).toBe(0);

    // 값을 고치면 오류가 사라지고, 올바른 값으로는 POST 가 간다
    await input.fill('10');
    await expect(dialog.locator('#dash-refresh-error')).toHaveCount(0);
    await expect(input).not.toHaveAttribute('aria-invalid', 'true');
    await dialog.getByRole('button', { name: '만들기' }).click();
    expect((await post.waitForRequest()).payload).toMatchObject({ name: '운영 현황', autoRefreshSeconds: 10 });
  });

  test('새 대시보드 — 24시간을 넘는 값도 칸 아래에 알린다', async ({ authenticatedPage: page }) => {
    const { dialog, post } = await openCreateDialog(page);
    await dialog.getByLabel('이름 *').fill('운영 현황');
    await dialog.getByLabel('자동 새로고침 (초)').fill('86401');
    await dialog.getByRole('button', { name: '만들기' }).click();

    await expect(dialog.locator('#dash-refresh-error')).toHaveText('자동 새로고침은 86400초(24시간) 이하로 입력해 주세요.');
    expect(post.requests.length).toBe(0);

    // 다른 칸으로 옮겼다가 같은 잘못된 값으로 다시 만들어도 포커스가 다시 그 칸으로 온다
    await dialog.getByLabel('이름 *').focus();
    await dialog.getByRole('button', { name: '만들기' }).click();
    await expect(dialog.getByLabel('자동 새로고침 (초)')).toBeFocused();
    expect(post.requests.length).toBe(0);
  });

  test('대시보드 설정 — 칸 아래 오류는 창을 닫았다 다시 열면 사라진다', async ({ authenticatedPage: page }) => {
    const dashboard = createDashboardListItem({ id: 1, name: '재열기 대시보드', autoRefreshSeconds: null });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([dashboard]));
    await page.goto('/analytics/dashboards');

    const openSettings = async () => {
      await page.getByRole('row', { name: /재열기 대시보드/ }).getByLabel('설정').click();
      const dialog = page.getByRole('dialog', { name: '대시보드 설정' });
      await expect(dialog).toBeVisible();
      return dialog;
    };

    let dialog = await openSettings();
    const input = dialog.getByLabel('자동 새로고침 (초)');
    await input.fill('5.5');
    await dialog.getByRole('button', { name: '저장' }).click();
    await expect(dialog.locator('#edit-dash-refresh-error')).toHaveText(ERR_MIN);
    await expect(input).toHaveAttribute('aria-invalid', 'true');

    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(dialog).not.toBeVisible();

    dialog = await openSettings();
    await expect(dialog.locator('#edit-dash-refresh-error')).toHaveCount(0);
  });
});
