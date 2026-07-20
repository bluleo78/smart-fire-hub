import { createCategories, createDatasetDetail } from '../factories/dataset.factory';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

const DATASET_ID = 7;

test.describe('FILE 데이터셋 오브젝트 브라우저', () => {
  test('오브젝트 목록과 썸네일을 보여준다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    // FILE 타입 상세 응답 — DatasetDetailPage 헤더가 항상 호출하는 카테고리/태그 API도 함께 모킹한다.
    const detail = createDatasetDetail({
      id: DATASET_ID,
      name: '장비 학습 데이터',
      storageType: 'FILE',
      originType: 'SOURCE',
      columns: [],
      rowCount: null,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    // 오브젝트 목록 mock
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects`, {
      objects: [{ key: 'equip/robot-01/a.jpg', size: 2048, lastModified: null }],
      nextToken: null,
      hasMore: false,
    });
    // presigned URL mock
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects/url`, {
      url: 'https://example.com/a.jpg',
      expiresInSeconds: 300,
    });

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '오브젝트' }).click();

    await expect(page.getByText('a.jpg · 2KB')).toBeVisible();
    await expect(page.locator('img[alt="a.jpg"]')).toHaveAttribute('src', 'https://example.com/a.jpg');
  });

  test('FILE 데이터셋은 필드/데이터 탭을 숨긴다', async ({ authenticatedPage: page }) => {
    const detail = createDatasetDetail({
      id: DATASET_ID,
      storageType: 'FILE',
      originType: 'SOURCE',
      columns: [],
      rowCount: null,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects`, {
      objects: [],
      nextToken: null,
      hasMore: false,
    });

    await page.goto(`/data/datasets/${DATASET_ID}`);

    await expect(page.getByRole('tab', { name: '오브젝트' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '필드' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: '데이터' })).toHaveCount(0);
  });
});
