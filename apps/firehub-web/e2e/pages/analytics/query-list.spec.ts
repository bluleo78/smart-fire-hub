import { createSavedQueryListItem } from '../../factories/analytics.factory';
import { setupQueryListMocks } from '../../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 쿼리 목록 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 목록 페이지 UI를 검증한다.
 */
test.describe('쿼리 목록 페이지', () => {
  test('쿼리 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
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
});
