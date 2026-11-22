import { createChartListItem, createDeleteImpact } from '../../factories/analytics.factory';
import { mockDeleteImpact, openFirstRowDeleteDialog, setupChartBuilderMocks, setupChartListMocks } from '../../fixtures/analytics.fixture';
import { createPageResponse, mockApi, mockDeleteOnceThenNotFound } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { clickDialogBackdrop, expectBackdropClickStaysOnList } from '../../fixtures/dialog.fixture';
import { expectWithin } from '../../fixtures/long-name-layout';
import { expectTableErrorAlert } from '../../fixtures/table-error-alert';
import { expectFocusRingRoom, expectTableFits, TABLE_LAYOUT_WIDTHS } from '../../fixtures/table-layout';

/**
 * 차트 목록 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 목록 페이지 UI를 검증한다.
 */
test.describe('차트 목록 페이지', () => {
  test('차트 목록이 올바르게 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    // 3개 차트 목록을 모킹한 후 목록 페이지 접근
    await setupChartListMocks(page, 3);
    await page.goto('/analytics/charts');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '차트' })).toBeVisible();

    // 테이블 헤더 컬럼 확인
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '타입' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '쿼리' })).toBeVisible();

    // 차트 행이 3개 렌더링되는지 확인 (fixture에서 생성한 이름 패턴)
    // 셀 내부는 복합 구조이므로 getByText로 확인
    await expect(page.getByText('테스트 차트 1')).toBeVisible();
    await expect(page.getByText('테스트 차트 3')).toBeVisible();

    // 행 수 확인: 헤더 1개 + 데이터 3개 = 총 4개 행
    await expect(page.getByRole('row')).toHaveCount(4);

    // 첫 번째 데이터 행에 BAR 타입 뱃지 '막대'가 표시되는지 확인
    // setupChartListMocks는 기본적으로 chartType: 'BAR'로 생성하므로 '막대' 뱃지 검증
    const firstRow = page.getByRole('row').nth(1);
    await expect(firstRow.locator('[data-slot="badge"]').filter({ hasText: '막대' })).toBeVisible();

    // 첫 번째 데이터 행에 쿼리 이름 '테스트 쿼리'가 표시되는지 확인
    await expect(firstRow.getByText('테스트 쿼리')).toBeVisible();
  });

  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 빈 페이지 응답으로 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));

    await page.goto('/analytics/charts');

    // 빈 상태 메시지 확인
    await expect(page.getByText('차트가 없습니다.')).toBeVisible();
  });

  test('탭 — "내 차트"/"공유됨" 탭이 표시된다', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 2);
    await page.goto('/analytics/charts');

    // 탭 목록 확인
    await expect(page.getByRole('tab', { name: '내 차트' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '공유됨' })).toBeVisible();
  });

  test('차트 타입 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    // 막대(BAR) 차트 1개 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts',
      createPageResponse([
        createChartListItem({ id: 1, name: '막대 차트', chartType: 'BAR' }),
      ]),
    );

    await page.goto('/analytics/charts');

    // "막대" 뱃지 확인 (CHART_TYPE_LABELS 기준)
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '막대' })).toBeVisible();
  });

  test('상속 이름·모르는 차트 타입은 함수가 아니라 타입 키 그대로 뱃지에 쓴다 (WD-831)', async ({ authenticatedPage: page }) => {
    // 서버가 돌려준 chartType 이 'constructor'·'SANKEY' 처럼 표에 없는 값이어도 목록이 깨지지 않아야 한다
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts',
      createPageResponse([
        createChartListItem({ id: 1, name: '상속 이름 차트', chartType: 'constructor' as unknown as 'BAR' }),
        createChartListItem({ id: 2, name: '모르는 타입 차트', chartType: 'SANKEY' as unknown as 'BAR' }),
      ]),
    );

    await page.goto('/analytics/charts');

    const firstRow = page.getByRole('row', { name: /상속 이름 차트/ });
    await expect(firstRow.locator('[data-slot="badge"]')).toHaveText('constructor');
    const secondRow = page.getByRole('row', { name: /모르는 타입 차트/ });
    await expect(secondRow.locator('[data-slot="badge"]')).toHaveText('SANKEY');
    // Object.prototype.constructor 함수 소스가 화면에 새어 나오지 않는다
    await expect(page.getByText('[native code]')).toHaveCount(0);
  });

  test('삭제 버튼 클릭 시 확인 다이얼로그가 열리고 DELETE API가 호출된다', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 2);
    await page.goto('/analytics/charts');

    // DELETE /api/v1/analytics/charts/1 API 캡처 모킹
    const deleteCapture = await mockApi(
      page,
      'DELETE',
      '/api/v1/analytics/charts/1',
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

  test('삭제 확인 창의 배경을 눌러도 상세로 이동하지 않는다 (WD-943)', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 2);
    await page.goto('/analytics/charts');
    // 요청 캡처는 누르기 전에 건다 — 늦게 걸면 번진 요청을 놓쳐 통과할 수 있다(WD-943).
    const { requests: deleteRequests } = await mockApi(page, 'DELETE', '/api/v1/analytics/charts/1', {}, { capture: true });

    await openFirstRowDeleteDialog(page);
    await expectBackdropClickStaysOnList(page, deleteRequests);
  });

  test('삭제 확인 창의 배경을 누른 뒤 Escape 로 닫으면 삭제 버튼으로 초점이 돌아온다 (WD-943)', async ({
    authenticatedPage: page,
  }) => {
    await setupChartListMocks(page, 2);
    await page.goto('/analytics/charts');

    const trigger = page.getByRole('button', { name: '삭제' }).first();
    const dialog = await openFirstRowDeleteDialog(page);
    const listUrl = page.url();
    await clickDialogBackdrop(page);
    await expect(dialog).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    await expect(trigger).toBeFocused();
    await expect(page).toHaveURL(listUrl);
  });

  test('확인 창의 「삭제」를 더블클릭해도 DELETE 는 한 번만 나가고 오류 토스트가 뜨지 않는다 (WD-507)', async ({
    authenticatedPage: page,
  }) => {
    await setupChartListMocks(page, 2);
    // 첫 DELETE 204, 두 번째부터 404 — 결함이 있으면 성공·「찾을 수 없습니다」 토스트가 함께 뜬다.
    const del = await mockDeleteOnceThenNotFound(page, '/api/v1/analytics/charts/1', '차트를 찾을 수 없습니다.');
    await page.goto('/analytics/charts');

    const dialog = await openFirstRowDeleteDialog(page);
    const confirm = dialog.getByRole('button', { name: '삭제' });
    // 영향 범위 조회 등으로 잠시 꺼져 있을 수 있어 켜진 뒤 누른다.
    await expect(confirm).toBeEnabled();
    await confirm.dblclick();

    await expect(page.getByText(/차트 "테스트 차트 1".*삭제되었습니다/)).toBeVisible();
    // 두 번째 요청이 늦게 나가는 경우까지 잡도록 잠시 기다린 뒤 횟수를 본다.
    await page.waitForTimeout(300);
    expect(del.requests).toEqual(['/api/v1/analytics/charts/1']);
    await expect(page.getByText('찾을 수 없습니다')).toHaveCount(0);
  });

  test('취소로 닫았다 다시 연 확인 창에서도 삭제할 수 있다 — 한 번만 확인 가드는 창을 열 때마다 초기화된다 (WD-507)', async ({
    authenticatedPage: page,
  }) => {
    await setupChartListMocks(page, 2);
    const del = await mockDeleteOnceThenNotFound(page, '/api/v1/analytics/charts/1', '차트를 찾을 수 없습니다.');
    await page.goto('/analytics/charts');

    let dialog = await openFirstRowDeleteDialog(page);
    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(dialog).toBeHidden();

    dialog = await openFirstRowDeleteDialog(page);
    const confirm = dialog.getByRole('button', { name: '삭제' });
    await expect(confirm).toBeEnabled();
    await confirm.click();

    await expect(page.getByText(/차트 "테스트 차트 1".*삭제되었습니다/)).toBeVisible();
    expect(del.requests).toEqual(['/api/v1/analytics/charts/1']);
  });

  test('마지막 페이지의 유일한 항목을 삭제하면 이전 페이지로 이동해 남은 항목을 보여준다 (#549)', async ({ authenticatedPage: page }) => {
    // 총 11건 → size=10 기준 2페이지. 삭제 후 프론트가 page를 보정하지 않으면
    // 서버에 더 이상 존재하지 않는 page=1을 그대로 요청해 "결과 없음"으로 오표시된다.
    const firstPageItems = Array.from({ length: 10 }, (_, i) =>
      createChartListItem({ id: i + 1, name: `테스트 차트 ${i + 1}` }),
    );
    const lastItem = createChartListItem({ id: 11, name: '테스트 차트 11' });
    let deleted = false;

    await page.route(
      (url) => url.pathname === '/api/v1/analytics/charts',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const requestedPage = new URL(route.request().url()).searchParams.get('page') ?? '0';

        if (!deleted) {
          const body =
            requestedPage === '1'
              ? createPageResponse([lastItem], { page: 1, totalElements: 11, totalPages: 2 })
              : createPageResponse(firstPageItems, { page: 0, totalElements: 11, totalPages: 2 });
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
        }

        const body =
          requestedPage === '1'
            ? createPageResponse([], { page: 1, totalElements: 10, totalPages: 1 })
            : createPageResponse(firstPageItems, { page: 0, totalElements: 10, totalPages: 1 });
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      },
    );
    await mockDeleteImpact(page);
    await mockApi(page, 'DELETE', '/api/v1/analytics/charts/11', {});

    await page.goto('/analytics/charts');
    await expect(page.getByText('테스트 차트 1', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: '2 페이지' }).click();
    await expect(page.getByText('테스트 차트 11')).toBeVisible();

    await page.getByRole('button', { name: '삭제' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    deleted = true;
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    await expect(page.getByText('차트가 없습니다.')).not.toBeVisible();
    await expect(page.getByText('테스트 차트 1', { exact: true })).toBeVisible();
    await expect(page.getByText('테스트 차트 10', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '2 페이지' })).not.toBeVisible();
  });

  test('차트 검색 시 search 파라미터가 API 에 전달된다', async ({ authenticatedPage: page }) => {
    // 검색어 포함 호출 캡처를 위해 route 직접 등록
    const searchCalls: string[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/charts',
      (route) => {
        const url = new URL(route.request().url());
        searchCalls.push(url.searchParams.get('search') ?? '');
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            createPageResponse([
              createChartListItem({ id: 1, name: '검색된 차트' }),
            ]),
          ),
        });
      },
    );

    await page.goto('/analytics/charts');
    await expect(page.getByRole('heading', { name: '차트' })).toBeVisible();

    // 검색어 입력
    await page.getByPlaceholder('차트 검색...').fill('검색된');

    // 검색 결과 반영 대기
    await expect(page.getByText('검색된 차트')).toBeVisible();

    // search 파라미터가 전달되었는지 확인 — 목록 요청은 300ms 디바운스 뒤에 나가므로 도착을 기다린다 (WD-513)
    await expect.poll(() => searchCalls.some((s) => s.includes('검색된'))).toBe(true);
  });

  test('「내 차트」 탭은 mineOnly=true 만, 「공유됨」 탭은 sharedOnly=true 만 API 에 전달된다 (WD-328)', async ({
    authenticatedPage: page,
  }) => {
    // 「내 차트」 탭이 필터 없이 부르면 서버가 남의 공유 차트까지 섞어 주므로 mineOnly=true 를 실어야 한다
    const tabCalls: { mineOnly: string | null; sharedOnly: string | null }[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/charts',
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

    await page.goto('/analytics/charts');
    await expect(page.getByRole('heading', { name: '차트' })).toBeVisible();

    // 기본(「내 차트」) 탭: mineOnly=true, sharedOnly 없음
    await expect.poll(() => tabCalls.length).toBeGreaterThan(0);
    expect(tabCalls[0]).toEqual({ mineOnly: 'true', sharedOnly: null });

    // 「공유됨」 탭: sharedOnly=true, mineOnly 없음 — 내 공유 차트도 포함되는 기존 동작 유지
    const before = tabCalls.length;
    await page.getByRole('tab', { name: '공유됨' }).click();
    await expect.poll(() => tabCalls.length).toBeGreaterThan(before);
    expect(tabCalls[tabCalls.length - 1]).toEqual({ mineOnly: null, sharedOnly: 'true' });
  });

  test('새 차트 버튼 클릭 시 /analytics/charts/new 로 이동한다', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 1);
    // 새 차트 빌더 페이지 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);

    await page.goto('/analytics/charts');
    await expect(page.getByRole('heading', { name: '차트' })).toBeVisible();

    await page.getByRole('button', { name: '새 차트' }).click();

    await expect(page).toHaveURL('/analytics/charts/new');
  });

  test('남이 공유한 차트도 Tab·Enter 로 상세에 간다 (WD-933)', async ({ authenticatedPage: page }) => {
    // 작성자가 내가 아닌 공유 차트 — 편집 버튼이 없어 키보드로 상세에 갈 길이 이름 링크뿐이다
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts',
      createPageResponse([createChartListItem({ id: 7, name: '남의 공유 차트', createdBy: 999, isShared: true })]),
    );
    await setupChartBuilderMocks(page, 7);

    await page.goto('/analytics/charts');
    await page.getByRole('tab', { name: '공유됨' }).click();

    const link = page.getByRole('table').getByRole('link', { name: '남의 공유 차트', exact: true });
    await expect(link).toHaveAttribute('href', '/analytics/charts/7');

    // 행은 키보드 대상이 아니다 — 이름 링크가 유일한 경로
    const row = page.getByRole('row').filter({ hasText: '남의 공유 차트' });
    await expect(row).not.toHaveAttribute('role');
    await expect(row).not.toHaveAttribute('tabindex');
    // 이 시험의 전제 — 남의 공유 항목 행에는 편집 버튼이 없다(aria-label="편집", 작성자 행에서만 그려진다)
    await expect(row.getByRole('button', { name: '편집', exact: true })).toHaveCount(0);

    // 검색 칸에서 Tab 한 번으로 링크에 닿는다
    await page.getByPlaceholder('차트 검색...').focus();
    await page.keyboard.press('Tab');
    await expect(link).toBeFocused();

    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/analytics\/charts\/7$/);
  });

  test('이름 링크 클릭은 한 번만 이동한다 (WD-933)', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 2);
    await setupChartBuilderMocks(page, 1);

    await page.goto('/analytics/charts');
    await page.getByRole('table').getByRole('link', { name: '테스트 차트 1', exact: true }).click();
    await expect(page).toHaveURL(/\/analytics\/charts\/1$/);

    // 링크 클릭이 행 onClick 으로 번져 두 번 push 되면 한 번 뒤로로는 목록에 못 온다
    await page.goBack();
    await expect(page).toHaveURL(/\/analytics\/charts$/);

    // 링크 밖 칸(타입)을 누르면 행 경로로 상세에 간다
    await page.getByRole('row').nth(1).getByRole('cell').nth(1).click();
    await expect(page).toHaveURL(/\/analytics\/charts\/1$/);
  });

  test('이름 링크 포커스 링이 잘리지 않는다 (WD-933)', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 1);
    await page.goto('/analytics/charts');

    const link = page.getByRole('table').getByRole('link', { name: '테스트 차트 1', exact: true });
    await expectFocusRingRoom(link);
  });

  test('이름이 매우 긴 차트도 테이블 레이아웃이 깨지지 않는다 (#563)', async ({ authenticatedPage: page }) => {
    // 회귀 방지: 이름 링크에 truncate가 없으면 긴 이름이 테이블 전체 폭을
    // 뷰포트 밖으로 밀어내 타입/쿼리/작업 버튼 컬럼이 가려진다.
    const longName = '가'.repeat(500);
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts',
      createPageResponse([createChartListItem({ id: 99, name: longName })]),
    );

    await page.goto('/analytics/charts');
    const dataRow = page.getByRole('row').nth(1);
    await expect(dataRow).toBeVisible();

    // 이름은 상세 링크(TruncatedLink, WD-933)이고 잘려 보이되 title 속성으로 전체 이름을 노출해야 한다
    const nameLink = dataRow.getByRole('link', { name: longName, exact: true });
    await expect(nameLink).toHaveAttribute('title', longName);
    await expect(nameLink).toHaveClass(/truncate/);

    // 테이블 전체 폭이 뷰포트를 벗어나지 않아야 하며,
    // 같은 행의 "삭제" 작업 버튼(가장 우측 컬럼)이 화면 밖으로 밀려나지 않고 보여야 한다
    const table = page.getByRole('table');
    const viewportWidth = page.viewportSize()?.width ?? 1280;
    const tableBox = await table.boundingBox();
    expect(tableBox?.width ?? 0).toBeLessThanOrEqual(viewportWidth);
    await expect(dataRow.getByLabel('삭제')).toBeVisible();
  });

  test('목록 조회가 실패하면 빈 목록 대신 실패 안내를 보인다 (WD-978)', async ({ authenticatedPage: page }) => {
    // 목록 조회만 500 — 「차트가 없습니다.」 는 실패 안내로 대체되어야 한다
    await setupChartListMocks(page, 0);
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { message: '서버 오류' }, { status: 500 });
    await page.goto('/analytics/charts');

    await expectTableErrorAlert(page);
    await expect(page.getByText('차트가 없습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '새 차트 만들기' })).toHaveCount(0);
  });
});

/** WD-86: 차트를 지우면 그 차트를 쓰는 대시보드 위젯이 DB CASCADE 로 함께 사라진다 — 확인 창이 이를 알려야 한다. */
test.describe('차트 삭제 — 함께 삭제되는 항목 안내 (WD-86)', () => {
  test('이 차트를 쓰는 대시보드 위젯이 있으면 빨간 상자에 위젯·대시보드를 보이고, 확인하면 DELETE 를 호출한다', async ({
    authenticatedPage: page,
  }) => {
    await setupChartListMocks(page, 2);
    await mockDeleteImpact(
      page,
      createDeleteImpact({ widgetCount: 2, dashboards: [{ id: 20, name: '영업 현황' }], hiddenDashboardCount: 1 }),
    );
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/analytics/charts/1', {}, { capture: true });
    await page.goto('/analytics/charts');

    const impactRequest = page.waitForRequest((r) => r.url().includes('/delete-impact'));
    const dialog = await openFirstRowDeleteDialog(page);
    expect(new URL((await impactRequest).url()).pathname).toBe('/api/v1/analytics/charts/1/delete-impact');

    const box = dialog.getByTestId('delete-impact');
    await expect(box.getByText('함께 삭제되는 항목', { exact: true })).toBeVisible();
    // 차트 삭제에는 "이 쿼리로 만든 차트" 줄이 없다. 볼 수 없는 대시보드는 개수만.
    await expect(box.getByRole('listitem')).toHaveCount(1);
    await expect(box.getByRole('listitem')).toHaveText(
      '이 차트를 쓰는 대시보드 위젯 2개 (대시보드 2곳: 영업 현황, 볼 수 없는 대시보드 1곳)',
    );

    await dialog.getByRole('button', { name: '삭제' }).click();
    const req = await deleteCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/analytics/charts/1');
  });

  test('쓰는 대시보드가 없으면 안내 상자 없이 일반 확인 창을 보인다', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 2);
    await page.goto('/analytics/charts');

    const dialog = await openFirstRowDeleteDialog(page);
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeEnabled();
    await expect(dialog.getByTestId('delete-impact')).toHaveCount(0);
  });

  test('영향 범위 조회가 실패하면 위젯이 함께 삭제될 수 있음을 알리고 삭제는 그대로 할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupChartListMocks(page, 2);
    await mockDeleteImpact(page, createDeleteImpact(), 500);
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/analytics/charts/1', {}, { capture: true });
    await page.goto('/analytics/charts');

    const dialog = await openFirstRowDeleteDialog(page);
    const box = dialog.getByTestId('delete-impact');
    await expect(box.getByText('함께 삭제되는 항목을 불러오지 못했습니다')).toBeVisible();
    await expect(box).toContainText('이 차트를 쓰는 대시보드 위젯이 있다면 함께 삭제됩니다.');

    await dialog.getByRole('button', { name: '삭제' }).click();
    const req = await deleteCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/analytics/charts/1');
  });
  test('남의 공유 차트라 영향 조회가 404 면 쿼리와 같은 안내로 삭제 버튼을 끈다', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 2);
    await mockDeleteImpact(page, createDeleteImpact(), 404);
    await page.goto('/analytics/charts');

    const dialog = await openFirstRowDeleteDialog(page);
    await expect(dialog.getByTestId('delete-not-owner')).toHaveText('본인이 만든 항목만 삭제할 수 있습니다.');
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();
  });
});

/**
 * WD-890 — 차트 목록의 탭·행 작업 버튼을 최소 32px 클릭 영역으로 키운다.
 * 28px 버튼·29px 탭은 터치·마우스로 누르기 빠듯해 WD-877(대시보드 목록)과 같은 기준으로 맞춘다.
 */
test.describe('WD-890 클릭 영역 32px', () => {
  test('탭 세 개가 모두 32px 이상이다', async ({ authenticatedPage: page }) => {
    // 관리자 권한이어야 조건부 「워크스페이스 전체」 탭까지 세 개가 보인다
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['analytics:read', 'analytics:write', 'analytics:admin']);
    await setupChartListMocks(page, 2);
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/analytics/charts');

    const tabs = page.getByRole('tab');
    await expect(tabs).toHaveCount(3);
    for (const tab of await tabs.all()) {
      const box = await tab.boundingBox();
      const label = await tab.textContent();
      expect(box, `${label} 탭 영역`).not.toBeNull();
      expect(box!.height, `${label} 탭 높이`).toBeGreaterThanOrEqual(32);
    }
  });

  test('행 작업 버튼(편집·삭제)이 32×32 이상이고 아이콘은 14px 이다', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 2);
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/analytics/charts');

    const firstRow = page.getByRole('row').nth(1);
    await firstRow.hover();
    for (const name of ['편집', '삭제']) {
      const box = await firstRow.getByRole('button', { name, exact: true }).boundingBox();
      expect(box, `${name} 버튼 영역`).not.toBeNull();
      expect(box!.width, `${name} 너비`).toBeGreaterThanOrEqual(32);
      expect(box!.height, `${name} 높이`).toBeGreaterThanOrEqual(32);
    }
    const icon = await firstRow.getByRole('button', { name: '편집', exact: true }).locator('svg').boundingBox();
    expect(icon, '편집 아이콘 영역').not.toBeNull();
    expect(icon!.width).toBeCloseTo(14, 0);
  });

  for (const width of [768, ...TABLE_LAYOUT_WIDTHS]) {
    test(`${width}px 폭에서 가장 넓은 행(60자 이름·공유 배지·긴 설명·긴 쿼리 이름)이 있어도 표가 넘치지 않고 작업 열이 버튼을 담는다`, async ({
      authenticatedPage: page,
    }) => {
      // 표준 목록 모킹 뒤 목록 응답만 가장 넓어지기 쉬운 행으로 덮어쓴다
      await setupChartListMocks(page, 2);
      await mockApi(
        page,
        'GET',
        '/api/v1/analytics/charts',
        createPageResponse([
          createChartListItem({
            id: 1,
            name: 'ㄱ'.repeat(60),
            isShared: true,
            description: '관할 구역·출동 유형·장비 종류별로 나눠 주간 추이와 전년 대비를 한 화면에 모아 보는 긴 설명',
            savedQueryName: '본부별 화재 출동 현황과 장비 가동률을 함께 보는 운영 쿼리 2026년 하반기',
          }),
          createChartListItem({ id: 2, name: '일반 차트 2' }),
        ]),
      );
      await page.setViewportSize({ width, height: 900 });
      await page.goto('/analytics/charts');

      const table = page.getByRole('table', { name: '차트 목록' });
      // 로딩 골격 행이 아니라 실제 행이 그려진 뒤에 잰다
      await expect(table.getByRole('row', { name: /일반 차트 2/ })).toBeVisible();
      // 작업 열 머리 폭이 버튼 2개(32×2 + 간격 4 = 68px)보다 좁지 않다
      const actionHead = table.getByRole('columnheader', { name: '작업' });
      // WD-890: 작업 열 머리 폭만으로는 버튼이 칸을 넘는지 모른다 — 가장 넓은 행의 마지막 버튼(삭제)이 자기 칸 안에 들어가는지 직접 잰다.
      // 버튼은 opacity-0 이어도 자리는 차지하므로 호버 없이 잰다.
      const lastButton = table.getByRole('row', { name: /ㄱㄱㄱ/ }).getByRole('button', { name: '삭제', exact: true });
      // 표 컨테이너가 넘치지 않고 마지막 버튼이 화면 안에 다 보인다(공용 측정 도우미)
      await expectTableFits(lastButton);
      await expectWithin(lastButton, lastButton.locator('xpath=ancestor::td[1]'));
      // 레이아웃이 자리 잡은 뒤 작업 열 머리 폭을 잰다
      const headBox = await actionHead.boundingBox();
      expect(headBox, '작업 열 머리 영역').not.toBeNull();
      expect(headBox!.width, '작업 열 머리 폭').toBeGreaterThanOrEqual(68);
    });
  }
});
