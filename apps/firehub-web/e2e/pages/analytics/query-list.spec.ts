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

    // 쿼리 행이 5개 렌더링되는지 확인 (팩토리에서 생성한 이름 패턴)
    // 셀 내부는 복합 구조이므로 getByText로 확인
    await expect(page.getByText('저장 쿼리 1')).toBeVisible();
    await expect(page.getByText('저장 쿼리 5')).toBeVisible();
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

    // "공유됨" 탭 클릭 후 API 재요청 (빈 목록)
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse([]));
    await page.getByRole('tab', { name: '공유됨' }).click();
  });

  test('삭제 버튼 클릭 시 확인 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    await setupQueryListMocks(page, 2);
    await page.goto('/analytics/queries');

    // 첫 번째 행의 삭제 버튼 클릭 (aria-label="삭제")
    const deleteButtons = page.getByRole('button', { name: '삭제' });
    await deleteButtons.first().click();

    // 삭제 확인 다이얼로그가 열리는지 확인
    await expect(page.getByRole('alertdialog')).toBeVisible();
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
});
