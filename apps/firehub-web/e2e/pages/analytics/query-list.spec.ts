import {
  createDeleteImpact,
  createQueryResult,
  createSavedQuery,
  createSavedQueryListItem,
} from '../../factories/analytics.factory';
import { mockDeleteImpact, openFirstRowDeleteDialog, setupQueryListMocks } from '../../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 쿼리 목록 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 목록 페이지 UI를 검증한다.
 */
test.describe('쿼리 목록 페이지', () => {
  test('쿼리 목록이 올바르게 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    // 5개 쿼리 목록을 모킹한 후 목록 페이지 접근
    await setupQueryListMocks(page, 5);
    await page.goto('/analytics/queries');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '저장된 쿼리' })).toBeVisible();

    // 테이블 헤더 컬럼 확인
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '데이터셋' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '수정일' })).toBeVisible();

    // 헤더 행(1) + 데이터 행(5) = 총 6행 확인
    const rows = page.getByRole('row');
    await expect(rows).toHaveCount(6);

    // 첫 번째 쿼리 이름과 마지막 쿼리 이름 확인
    await expect(page.getByText('저장 쿼리 1')).toBeVisible();
    await expect(page.getByText('저장 쿼리 5')).toBeVisible();

    // 팩토리 데이터의 datasetName '테스트 데이터셋'이 첫 번째 행에 표시되는지 확인
    // createSavedQueryList는 모두 datasetName: '테스트 데이터셋'을 가짐
    const firstDataRow = page.getByRole('row').nth(1);
    await expect(firstDataRow.getByText('테스트 데이터셋')).toBeVisible();
  });

  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 빈 페이지 응답으로 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);

    await page.goto('/analytics/queries');

    // 빈 상태 메시지 확인
    await expect(page.getByText('저장된 쿼리가 없습니다.')).toBeVisible();
  });

  test('새 쿼리 버튼 클릭 시 쿼리 에디터 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    // 쿼리 에디터(/analytics/queries/new)에서 필요한 API 모킹
    await setupQueryListMocks(page, 3);
    await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', { tables: [] });

    await page.goto('/analytics/queries');

    // "새 쿼리" 버튼 클릭
    await page.getByRole('button', { name: '새 쿼리' }).click();

    // /analytics/queries/new 페이지로 이동 확인
    await expect(page).toHaveURL('/analytics/queries/new');
  });

  test('탭 전환 — "공유됨" 탭이 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupQueryListMocks(page, 2);
    await page.goto('/analytics/queries');

    // "내 쿼리" / "공유됨" 탭 존재 확인
    await expect(page.getByRole('tab', { name: '내 쿼리' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '공유됨' })).toBeVisible();

    // "공유됨" 탭 클릭 시 API 재요청 여부를 캡처로 검증
    // capture 등록은 클릭 이전에 미리 설정해야 요청을 놓치지 않는다
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse([]),
      { capture: true },
    );

    // "공유됨" 탭 클릭
    await page.getByRole('tab', { name: '공유됨' }).click();

    // 탭 전환 후 GET /api/v1/analytics/queries 재호출 확인
    const req = await capture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/analytics/queries');
  });

  test('「내 쿼리」 탭은 mineOnly=true 만, 「공유됨」 탭은 sharedOnly=true 만 API 에 전달된다 (WD-328)', async ({
    authenticatedPage: page,
  }) => {
    // 「내 쿼리」 탭이 필터 없이 부르면 서버가 남의 공유 쿼리까지 섞어 주므로 mineOnly=true 를 실어야 한다
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);
    const tabCalls: { mineOnly: string | null; sharedOnly: string | null }[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries',
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

    await page.goto('/analytics/queries');
    await expect(page.getByRole('heading', { name: '저장된 쿼리' })).toBeVisible();

    // 기본(「내 쿼리」) 탭: mineOnly=true, sharedOnly 없음
    await expect.poll(() => tabCalls.length).toBeGreaterThan(0);
    expect(tabCalls[0]).toEqual({ mineOnly: 'true', sharedOnly: null });

    // 「공유됨」 탭: sharedOnly=true, mineOnly 없음 — 내 공유 쿼리도 포함되는 기존 동작 유지
    const before = tabCalls.length;
    await page.getByRole('tab', { name: '공유됨' }).click();
    await expect.poll(() => tabCalls.length).toBeGreaterThan(before);
    expect(tabCalls[tabCalls.length - 1]).toEqual({ mineOnly: null, sharedOnly: 'true' });
  });

  test('삭제 버튼 클릭 시 확인 후 DELETE API가 호출된다', async ({ authenticatedPage: page }) => {
    await setupQueryListMocks(page, 2);
    await page.goto('/analytics/queries');

    // DELETE 요청 캡처 등록 (클릭 전에 등록해야 요청을 놓치지 않는다)
    const capture = await mockApi(
      page,
      'DELETE',
      '/api/v1/analytics/queries/1',
      {},
      { capture: true },
    );

    // 첫 번째 행의 삭제 버튼 클릭 (aria-label="삭제")
    const deleteButtons = page.getByRole('button', { name: '삭제' });
    await deleteButtons.first().click();

    // 삭제 확인 다이얼로그가 열리는지 확인
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // alertdialog 내부의 확인(삭제) 버튼 클릭
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    // DELETE /api/v1/analytics/queries/1 가 실제로 호출되었는지 검증
    const req = await capture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/analytics/queries/1');
  });

  test('마지막 페이지의 유일한 항목을 삭제하면 이전 페이지로 이동해 남은 항목을 보여준다 (#549)', async ({ authenticatedPage: page }) => {
    // 총 11건 → size=10 기준 2페이지. 삭제 후 프론트가 page를 보정하지 않으면
    // 서버에 더 이상 존재하지 않는 page=1을 그대로 요청해 "결과 없음"으로 오표시된다.
    const firstPageItems = Array.from({ length: 10 }, (_, i) =>
      createSavedQueryListItem({ id: i + 1, name: `테스트 쿼리 ${i + 1}` }),
    );
    const lastItem = createSavedQueryListItem({ id: 11, name: '테스트 쿼리 11' });
    let deleted = false;

    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries',
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
    await mockApi(page, 'DELETE', '/api/v1/analytics/queries/11', {});

    await page.goto('/analytics/queries');
    await expect(page.getByText('테스트 쿼리 1', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: '2 페이지' }).click();
    await expect(page.getByText('테스트 쿼리 11')).toBeVisible();

    await page.getByRole('button', { name: '삭제' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    deleted = true;
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    await expect(page.getByText('저장된 쿼리가 없습니다.')).not.toBeVisible();
    await expect(page.getByText('테스트 쿼리 1', { exact: true })).toBeVisible();
    await expect(page.getByText('테스트 쿼리 10', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '2 페이지' })).not.toBeVisible();
  });

  test('공유 쿼리에 공유 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    // isShared: true 쿼리 1개 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse([
        createSavedQueryListItem({ id: 1, name: '공유 쿼리', isShared: true }),
      ]),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);

    await page.goto('/analytics/queries');

    // "공유" 뱃지 확인
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: /^공유$/ })).toBeVisible();
  });

  test('검색 입력 시 API가 search 파라미터와 함께 재호출된다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryListMocks(page, 3);
    await page.goto('/analytics/queries');
    await expect(page.getByRole('heading', { name: '저장된 쿼리' })).toBeVisible();

    // 검색 결과 API 캡처 (search param 포함)
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse([createSavedQueryListItem({ id: 1, name: '화재 분석 쿼리' })]),
      { capture: true },
    );

    // 검색창에 텍스트 입력
    await page.getByPlaceholder(/검색|search/i).fill('화재');

    // API 재호출 확인 (검색 파라미터 포함)
    const req = await capture.waitForRequest();
    expect(req.url.searchParams.get('search')).toBe('화재');
  });

  test('폴더 필터 선택 시 API가 folder 파라미터와 함께 재호출된다', async ({
    authenticatedPage: page,
  }) => {
    // 폴더 목록이 있는 설정
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse([createSavedQueryListItem({ id: 1, folder: '분석' })]),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', ['분석', '보고서']);

    await page.goto('/analytics/queries');
    await expect(page.getByRole('heading', { name: '저장된 쿼리' })).toBeVisible();

    // 폴더 필터 드롭다운 캡처
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse([]),
      { capture: true },
    );

    // 폴더 선택 드롭다운 클릭
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '분석' }).click();

    // API 재호출 확인 (folder 파라미터 포함)
    const req = await capture.waitForRequest();
    expect(req.url.searchParams.get('folder')).toBe('분석');
  });

  test('폴더 목록 요청도 탭 범위를 싣는다 — 「내 쿼리」는 mineOnly=true, 「공유됨」은 sharedOnly=true (WD-328)', async ({
    authenticatedPage: page,
  }) => {
    // 폴더 드롭다운이 목록과 다른 범위면 「내 쿼리」에서 남의 공유 쿼리에만 있는 폴더를 골라 늘 빈 결과가 된다
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse([]));
    const folderCalls: { mineOnly: string | null; sharedOnly: string | null }[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/folders',
      (route) => {
        const url = new URL(route.request().url());
        folderCalls.push({
          mineOnly: url.searchParams.get('mineOnly'),
          sharedOnly: url.searchParams.get('sharedOnly'),
        });
        return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      },
    );

    await page.goto('/analytics/queries');
    await expect(page.getByRole('heading', { name: '저장된 쿼리' })).toBeVisible();

    await expect.poll(() => folderCalls.length).toBeGreaterThan(0);
    expect(folderCalls[0]).toEqual({ mineOnly: 'true', sharedOnly: null });

    const before = folderCalls.length;
    await page.getByRole('tab', { name: '공유됨' }).click();
    await expect.poll(() => folderCalls.length).toBeGreaterThan(before);
    expect(folderCalls[folderCalls.length - 1]).toEqual({ mineOnly: null, sharedOnly: 'true' });
  });

  test('탭을 바꾸면 고른 폴더 필터가 풀린다 — 다른 탭 목록이 조용히 비지 않게 (WD-328)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse([createSavedQueryListItem({ id: 1, folder: '분석' })]),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', ['분석', '보고서']);

    await page.goto('/analytics/queries');
    await expect(page.getByRole('heading', { name: '저장된 쿼리' })).toBeVisible();

    // 「내 쿼리」 탭에서 폴더 선택
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '분석' }).click();
    await expect(page.getByRole('combobox')).toHaveText('분석');

    // 탭 전환 시 목록 요청에 folder 가 빠지고 드롭다운도 「전체 폴더」로 돌아온다
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse([]),
      { capture: true },
    );
    await page.getByRole('tab', { name: '공유됨' }).click();
    const req = await capture.waitForRequest();
    expect(req.url.searchParams.get('sharedOnly')).toBe('true');
    expect(req.url.searchParams.get('folder')).toBeNull();
    await expect(page.getByRole('combobox')).toHaveText('전체 폴더');
  });

  test('쿼리 이름 클릭 시 에디터 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupQueryListMocks(page, 2);
    await mockApi(page, 'GET', '/api/v1/analytics/queries/1', createSavedQueryListItem({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', { tables: [] });

    await page.goto('/analytics/queries');
    await expect(page.getByText('저장 쿼리 1')).toBeVisible();

    // 쿼리 이름 클릭 → 에디터 페이지로 이동
    await page.getByText('저장 쿼리 1').click();

    await expect(page).toHaveURL(/\/analytics\/queries\/1/);
  });

  test('200자 이상 긴 쿼리 이름이 셀 영역을 벗어나지 않고 truncate된다', async ({
    authenticatedPage: page,
  }) => {
    // 200자 이상 이름을 가진 쿼리를 포함한 목록 모킹
    const longName = 'a'.repeat(250);
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse([
        createSavedQueryListItem({ id: 1, name: '정상 쿼리' }),
        createSavedQueryListItem({ id: 2, name: longName }),
      ]),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);

    await page.goto('/analytics/queries');
    await expect(page.getByRole('heading', { name: '저장된 쿼리' })).toBeVisible();

    const table = page.getByRole('table');
    await expect(table).toBeVisible();

    // 긴 이름이 포함된 행의 이름 span 요소가 max-w-[300px] 제한으로 truncate되는지 확인한다.
    // span.truncate가 없으면 텍스트가 무한으로 확장되어 다른 컬럼이 밀려난다.
    const longNameRow = page.getByRole('row').nth(2);
    const longNameSpan = longNameRow.locator('span.truncate').first();
    const spanWidth = await longNameSpan.evaluate((el) => el.getBoundingClientRect().width);

    // max-w-[300px]가 적용된 span이 실제로 300px 이하임을 검증 (픽셀 허용 오차 5px)
    expect(spanWidth).toBeLessThanOrEqual(305);

    // "수정일" 헤더 컬럼이 여전히 뷰포트 내에 있는지 확인 (레이아웃 깨짐 회귀 방지)
    await expect(page.getByRole('columnheader', { name: '수정일' })).toBeVisible();
  });

  test('행의 "실행" 버튼 클릭 시 편집기로 이동해 결과가 재실행 없이 즉시 표시된다 (#569)', async ({
    authenticatedPage: page,
  }) => {
    // 회귀 시나리오: 목록에서 실행 버튼을 누르면 executeSavedQuery API 호출로 결과를 받아오지만
    // 그 결과를 버리고 편집기로 이동만 했었다 (편집기는 결과 패널 없이 SQL만 표시).
    // 수정 후에는 navigate(..., { state: { executionResult } })로 결과를 넘겨
    // 편집기가 재실행 없이 즉시 결과 패널을 렌더링해야 한다.
    await setupQueryListMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/analytics/queries/1', createSavedQuery({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', { tables: [] });

    const executionResult = createQueryResult({
      columns: ['region', 'count'],
      rows: [{ region: '서울', count: 42 }],
      executionTimeMs: 7,
      totalRows: 1,
    });

    // POST executeSavedQuery 호출 횟수를 추적 — 편집기 진입 후 재실행되면 안 되므로 1회만 호출되어야 한다.
    let executeCallCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/1/execute',
      (route) => {
        executeCallCount += 1;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(executionResult),
        });
      },
    );

    await page.goto('/analytics/queries');
    await expect(page.getByText('저장 쿼리 1')).toBeVisible();

    // 행에 hover 해야 아이콘 버튼이 나타난다 (opacity-0 group-hover:opacity-100)
    const row = page.getByRole('row').filter({ hasText: '저장 쿼리 1' });
    await row.hover();
    await row.getByLabel('실행').click();

    // 실행 완료 토스트 + 편집기로 이동 확인
    await expect(page.getByText('쿼리 "저장 쿼리 1" 실행 완료')).toBeVisible();
    await expect(page).toHaveURL('/analytics/queries/1');

    // 결과 패널이 재실행 버튼 클릭 없이 바로 표시되는지 확인 (행수/소요시간/데이터 그리드)
    await expect(page.getByText('결과')).toBeVisible();
    await expect(page.getByText('1행')).toBeVisible();
    await expect(page.getByText('7ms')).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'region' })).toBeVisible();
    await expect(page.getByText('서울')).toBeVisible();

    // 목록에서 executeSavedQuery가 정확히 1회만 호출되었는지 (편집기가 자동 재실행하지 않음을 검증)
    expect(executeCallCount).toBe(1);
  });
  test('볼 수 없는 연결 데이터셋은 이름 대신 "열람 권한 없음"으로 표시한다(오류 아님)', async ({
    authenticatedPage: page,
  }) => {
    // 서버(Task 3 C1)는 조회자가 볼 수 없는 연결 데이터셋의 이름만 null 로 주고 datasetId 는 유지한다.
    // datasetId 도 null 인 행(연결 없음)은 "-" 이다 — 두 상태가 구분돼야 한다.
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse([
        createSavedQueryListItem({ id: 1, name: '숨김 연결 쿼리', datasetId: 42, datasetName: null }),
        createSavedQueryListItem({ id: 2, name: '연결 없는 쿼리', datasetId: null, datasetName: null }),
        createSavedQueryListItem({ id: 3, name: '공개 연결 쿼리', datasetId: 7, datasetName: '출동 기록' }),
      ]),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);

    await page.goto('/analytics/queries');

    const hiddenRow = page.getByRole('row').filter({ hasText: '숨김 연결 쿼리' });
    const restricted = hiddenRow.getByTestId('query-dataset-restricted');
    await expect(restricted).toHaveText('열람 권한 없음');
    // 권한 부족은 오류가 아니다 — 오류색 대신 muted, 아이콘은 보조 기술에 숨긴다
    await expect(restricted).toHaveClass(/text-muted-foreground/);
    await expect(restricted).not.toHaveClass(/destructive/);
    await expect(restricted.locator('svg')).toHaveAttribute('aria-hidden', 'true');
    await expect(hiddenRow.getByTestId('query-dataset-none')).toHaveCount(0);

    const noLinkRow = page.getByRole('row').filter({ hasText: '연결 없는 쿼리' });
    // WD-90: 내부 용어 "cross-dataset" 대신 폴더·차트 빈 칸과 같은 "-" 를 보인다
    await expect(noLinkRow.getByTestId('query-dataset-none')).toHaveText('-');
    await expect(noLinkRow.getByTestId('query-dataset-restricted')).toHaveCount(0);

    const visibleRow = page.getByRole('row').filter({ hasText: '공개 연결 쿼리' });
    await expect(visibleRow.getByText('출동 기록')).toBeVisible();
    await expect(visibleRow.getByTestId('query-dataset-restricted')).toHaveCount(0);

    // 토스트·경고 없음
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
    await page.screenshot({ path: 'test-results/tc/security-level-name-masking/query-list-restricted.png' });
  });
});

/**
 * WD-86: 저장 쿼리를 지우면 그 쿼리의 차트와 차트를 쓰는 대시보드 위젯이 DB CASCADE 로 함께 사라진다.
 * 확인 창이 열릴 때 영향 범위를 조회해 빨간 상자로 보여 주고, 연결이 없으면 지금과 같은 일반 확인 창이어야 한다.
 */
test.describe('쿼리 삭제 — 함께 삭제되는 항목 안내 (WD-86)', () => {
  test('연결된 차트·위젯이 있으면 빨간 상자에 차트·위젯·대시보드를 보이고, 확인하면 DELETE 를 호출한다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryListMocks(page, 2);
    await mockDeleteImpact(
      page,
      createDeleteImpact({
        charts: [
          { id: 10, name: '지역별 매출' },
          { id: 11, name: '월 추이' },
        ],
        hiddenChartCount: 1,
        widgetCount: 3,
        dashboards: [
          { id: 20, name: '영업 현황' },
          { id: 21, name: '경영 요약' },
        ],
      }),
    );
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/analytics/queries/1', {}, { capture: true });
    await page.goto('/analytics/queries');

    // 창을 열 때 해당 쿼리의 영향 범위를 조회한다(목록 진입만으로는 조회하지 않는다).
    const impactRequest = page.waitForRequest((r) => r.url().includes('/delete-impact'));
    const dialog = await openFirstRowDeleteDialog(page);
    expect(new URL((await impactRequest).url()).pathname).toBe('/api/v1/analytics/queries/1/delete-impact');

    const box = dialog.getByTestId('delete-impact');
    await expect(box).toBeVisible();
    await expect(box.getByText('함께 삭제되는 항목', { exact: true })).toBeVisible();
    // 볼 수 있는 이름은 나열하고, 볼 수 없는 차트는 개수만 보인다.
    await expect(box.getByRole('listitem').nth(0)).toHaveText(
      '이 쿼리로 만든 차트 3개 (지역별 매출, 월 추이, 볼 수 없는 차트 1개)',
    );
    await expect(box.getByRole('listitem').nth(1)).toHaveText(
      '그 차트를 쓰는 대시보드 위젯 3개 (대시보드 2곳: 영업 현황, 경영 요약)',
    );

    await dialog.getByRole('button', { name: '삭제' }).click();
    const req = await deleteCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/analytics/queries/1');
  });

  test('연결된 것이 없으면 안내 상자 없이 일반 확인 창을 보인다', async ({ authenticatedPage: page }) => {
    // setupQueryListMocks 가 기본값("연결 없음") 영향 범위를 모킹한다.
    await setupQueryListMocks(page, 2);
    await page.goto('/analytics/queries');

    const dialog = await openFirstRowDeleteDialog(page);
    await expect(dialog.getByText(/정말 삭제하시겠습니까\? 이 작업은 되돌릴 수 없습니다\./)).toBeVisible();
    // 조회가 끝나 삭제 버튼이 켜진 뒤에도 안내 상자·로딩 문구가 없어야 한다(로딩 상태와 구분).
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeEnabled();
    await expect(dialog.getByTestId('delete-impact')).toHaveCount(0);
    await expect(dialog.getByText('함께 삭제되는 항목을 확인하는 중…')).toHaveCount(0);
  });

  test('영향 범위를 확인하는 동안에는 삭제 버튼이 꺼져 있다', async ({ authenticatedPage: page }) => {
    await setupQueryListMocks(page, 2);
    // 응답을 붙잡아 두어 로딩 상태를 관찰한다.
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/1/delete-impact',
      async (route) => {
        await gate;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createDeleteImpact({ widgetCount: 1, dashboards: [{ id: 20, name: '영업 현황' }] })),
        });
      },
    );
    await page.goto('/analytics/queries');

    const dialog = await openFirstRowDeleteDialog(page);
    await expect(dialog.getByText('함께 삭제되는 항목을 확인하는 중…')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();

    release();
    await expect(dialog.getByTestId('delete-impact')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeEnabled();
  });

  test('창을 닫았다 다시 열면 이전 결과를 쓰지 않고 다시 확인하는 동안 삭제 버튼이 꺼져 있다', async ({
    authenticatedPage: page,
  }) => {
    // 처음에는 "연결 없음" — 다시 열 때 그 사이 생긴 연결을 확인하기 전에 옛 결과로 삭제가 켜지면 안 된다.
    await setupQueryListMocks(page, 2);
    await page.goto('/analytics/queries');
    const dialog = await openFirstRowDeleteDialog(page);
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeEnabled();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);

    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/1/delete-impact',
      async (route) => {
        await gate;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            createDeleteImpact({ charts: [{ id: 10, name: '새 차트' }], widgetCount: 0 }),
          ),
        });
      },
    );
    await openFirstRowDeleteDialog(page);
    await expect(dialog.getByText('함께 삭제되는 항목을 확인하는 중…')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();

    release();
    await expect(dialog.getByTestId('delete-impact')).toContainText('이 쿼리로 만든 차트 1개 (새 차트)');
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeEnabled();
  });

  test('영향 범위 조회가 실패하면 함께 삭제될 수 있음을 알리고 삭제는 그대로 할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryListMocks(page, 2);
    await mockDeleteImpact(page, createDeleteImpact(), 500);
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/analytics/queries/1', {}, { capture: true });
    await page.goto('/analytics/queries');

    const dialog = await openFirstRowDeleteDialog(page);
    const box = dialog.getByTestId('delete-impact');
    // 실패를 "연결 없음"(상자 없음)으로 보이게 하지 않는다.
    await expect(box.getByText('함께 삭제되는 항목을 불러오지 못했습니다')).toBeVisible();
    await expect(box).toContainText('이 쿼리로 만든 차트와 그 차트를 쓰는 대시보드 위젯이 있다면 함께 삭제됩니다.');

    await dialog.getByRole('button', { name: '삭제' }).click();
    const req = await deleteCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/analytics/queries/1');
  });
  test('남의 공유 쿼리라 영향 조회가 404 면 본인 것만 지울 수 있다고 알리고 삭제 버튼을 끈다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryListMocks(page, 2);
    await mockDeleteImpact(page, createDeleteImpact(), 404);
    let deleteCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/1',
      (route) => {
        if (route.request().method() !== 'DELETE') return route.fallback();
        deleteCalled = true;
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
      },
    );
    await page.goto('/analytics/queries');

    const dialog = await openFirstRowDeleteDialog(page);
    await expect(dialog.getByTestId('delete-not-owner')).toHaveText('본인이 만든 항목만 삭제할 수 있습니다.');
    // 서버 장애용 빨간 "함께 삭제될 수 있음" 경고와 구분된다.
    await expect(dialog.getByTestId('delete-impact')).toHaveCount(0);
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();
    expect(deleteCalled).toBe(false);
  });

  test('영향 조회가 10초 안에 끝나지 않으면 조회 실패로 보고 경고와 함께 삭제를 허용한다', async ({
    authenticatedPage: page,
  }) => {
    // 시간 제한은 axios timeout(XHR 네이티브 타이머)이라 Playwright 가짜 시계로 앞당길 수 없다 — 실제 10초를 기다린다.
    test.setTimeout(40_000);
    await setupQueryListMocks(page, 2);
    // 응답하지 않는 영향 조회.
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/1/delete-impact',
      () => new Promise<void>(() => {}),
    );
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/analytics/queries/1', {}, { capture: true });
    await page.goto('/analytics/queries');

    const dialog = await openFirstRowDeleteDialog(page);
    await expect(dialog.getByText('함께 삭제되는 항목을 확인하는 중…')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();

    // 시간 제한 전에는 여전히 확인 중이다.
    await page.waitForTimeout(8_000);
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();
    await expect(dialog.getByTestId('delete-impact').getByText('함께 삭제되는 항목을 불러오지 못했습니다')).toBeVisible({
      timeout: 10_000,
    });
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeEnabled();

    await dialog.getByRole('button', { name: '삭제' }).click();
    const req = await deleteCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/analytics/queries/1');
  });
});
