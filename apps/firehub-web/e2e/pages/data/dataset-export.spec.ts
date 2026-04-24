import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * ExportDialog E2E 테스트
 *
 * 내보내기 다이얼로그의 성공/실패 흐름을 검증한다.
 * - 500 에러 시 에러 토스트 표시 (issue #8 회귀 방지)
 * - 성공 시 다이얼로그 닫힘
 */
test.describe('데이터셋 상세 — 내보내기 다이얼로그', () => {
  const datasetDetail = createDatasetDetail({
    id: 1,
    rowCount: 5,
    columns: [
      createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
      createColumn({ id: 2, columnName: 'name', displayName: '이름', dataType: 'TEXT', isPrimaryKey: false, columnOrder: 1 }),
    ],
  });

  const estimateResponse = {
    rowCount: 5,
    async: false,
    hasGeometryColumn: false,
    columns: [
      { columnName: 'id', displayName: 'ID', isGeometry: false },
      { columnName: 'name', displayName: '이름', isGeometry: false },
    ],
  };

  async function setupMocks(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/datasets/1', datasetDetail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/export/estimate', estimateResponse);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          columns: datasetDetail.columns,
          rows: [
            { id: 1, name: 'Alice' },
            { id: 2, name: 'Bob' },
          ],
          page: 0,
          size: 50,
          totalElements: 2,
          totalPages: 1,
        }),
      }),
    );
  }

  test('내보내기 500 에러 시 에러 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await setupMocks(page);

    // 내보내기 POST → 500 에러
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/export',
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ message: 'cannot execute INSERT in a read-only transaction' }),
          });
        }
        return route.continue();
      },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    // 내보내기 버튼 클릭
    await page.getByRole('button', { name: '내보내기' }).click();

    // 다이얼로그 열림 확인
    await expect(page.getByRole('dialog').getByRole('heading', { name: '데이터 내보내기' })).toBeVisible();

    // "내보내기" 실행
    await page.getByRole('dialog').getByRole('button', { name: '내보내기' }).click();

    // 에러 토스트 표시 확인
    await expect(page.getByText(/내보내기에 실패했습니다/)).toBeVisible({ timeout: 5000 });

    // 다이얼로그는 여전히 열려있어야 한다
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  test('내보내기 성공 시 다이얼로그가 닫힌다', async ({ authenticatedPage: page }) => {
    await setupMocks(page);

    // 내보내기 POST → 성공 (CSV blob)
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/export',
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({
            status: 200,
            contentType: 'text/csv',
            body: 'id,name\n1,Alice\n2,Bob',
          });
        }
        return route.continue();
      },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    await page.getByRole('button', { name: '내보내기' }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '데이터 내보내기' })).toBeVisible();

    await page.getByRole('dialog').getByRole('button', { name: '내보내기' }).click();

    // 성공 토스트 + 다이얼로그 닫힘
    await expect(page.getByText(/파일이 다운로드되었습니다/)).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 3000 });
  });
});
