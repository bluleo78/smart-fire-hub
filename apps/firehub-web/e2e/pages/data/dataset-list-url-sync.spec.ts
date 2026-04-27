import { createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 목록 — URL 쿼리 파라미터 동기화 (#94) E2E 테스트.
 *
 * 검색·필터·정렬 상태가 URL 쿼리에 반영되어 새로고침/공유 시 복원되어야 한다.
 */
test.describe('데이터셋 목록 — URL 동기화', () => {
  const datasets = [
    createDataset({ id: 1, name: '고객 데이터셋', datasetType: 'SOURCE', status: 'CERTIFIED' }),
    createDataset({ id: 2, name: '주문 데이터셋', datasetType: 'DERIVED', status: 'NONE' }),
  ];

  async function setupCommon(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(datasets));
  }

  test('검색어 입력 시 URL 에 q 파라미터가 추가되고 새로고침 시 복원된다 (#94)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    await page.getByPlaceholder('데이터셋 검색...').fill('고객');

    // URL 에 q=고객 이 반영되는지 검증
    await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('고객');

    // 새로고침 후에도 검색어가 입력란에 복원되는지 검증
    await page.reload();
    await expect(page.getByPlaceholder('데이터셋 검색...')).toHaveValue('고객');
  });

  test('상태 필터 URL 직접 진입 시 SELECT 가 해당 값으로 복원된다 (#94)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);

    await page.goto('/data/datasets?status=CERTIFIED');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const statusCombobox = page.getByRole('combobox').filter({ hasText: /전체 상태|인증됨|사용 중단|상태 없음/ }).first();
    await expect(statusCombobox).toContainText('인증됨');
  });

  test('즐겨찾기 토글 시 URL 에 favorite=true 가 추가되고 해제 시 제거된다 (#94)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    await page.getByRole('button', { name: '즐겨찾기', exact: true }).click();
    await expect.poll(() => new URL(page.url()).searchParams.get('favorite')).toBe('true');

    await page.getByRole('button', { name: '즐겨찾기', exact: true }).click();
    await expect.poll(() => new URL(page.url()).searchParams.get('favorite')).toBeNull();
  });
});
