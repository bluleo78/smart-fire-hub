import { createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 목록 — 호버 '내보내기' 버튼이 ExportDialog 를 연다 (#95) E2E 테스트.
 *
 * 이전에는 navigate 로 상세 페이지로만 이동했으나, 라벨/아이콘과 동작 일치를 위해
 * ExportDialog 를 직접 트리거하도록 수정.
 */
test.describe('데이터셋 목록 — 호버 내보내기 액션', () => {
  const datasets = [
    createDataset({ id: 1, name: '고객 데이터셋', datasetType: 'SOURCE', status: 'CERTIFIED' }),
  ];

  async function setupCommon(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(datasets));
    // ExportDialog 가 estimate API 를 호출
    await mockApi(page, 'GET', '/api/v1/datasets/1/export/estimate', {
      rowCount: 100,
      async: false,
      hasGeometryColumn: false,
      columns: [
        { columnName: 'id', displayName: 'ID', isGeometry: false },
        { columnName: 'name', displayName: '이름', isGeometry: false },
      ],
    });
  }

  test('호버 내보내기 버튼 클릭 시 ExportDialog 가 열린다 (#95)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // 호버 액션의 '내보내기' 버튼 — opacity-0 group-hover 라 force click
    const exportBtn = page.getByRole('button', { name: '내보내기' }).first();
    await exportBtn.click({ force: true });

    // 상세 페이지로 navigate 하지 않고 목록 URL 유지
    await expect(page).toHaveURL(/\/data\/datasets($|\?)/);

    // ExportDialog 의 타이틀 노출
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('데이터 내보내기')).toBeVisible();
  });
});
