import { createDocuments } from '../../factories/document.factory';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDocumentDatasetMocks } from '../../fixtures/document.fixture';

const DATASET_ID = 1;

test.describe('문서 데이터셋 상세', () => {
  test('DOCUMENT 데이터셋은 문서 탭을 보이고 필드/데이터 탭을 숨긴다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupDocumentDatasetMocks(page, DATASET_ID, createDocuments(2));

    await page.goto(`/data/datasets/${DATASET_ID}?tab=documents`);

    await expect(page.getByRole('tab', { name: '문서' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '필드' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: '데이터' })).toHaveCount(0);
    await expect(page.getByText('document_1.pdf')).toBeVisible();
  });
});
