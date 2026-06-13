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

  /**
   * 회귀 테스트: DOCUMENT 데이터셋은 백엔드가 rowCount: null을 반환한다.
   * DatasetInfoTab가 null.toLocaleString()을 호출하면 TypeError로 정보 탭이 크래시하므로,
   * 정보 탭이 정상 렌더되고 행 카운트가 '-'로 표시되는지 검증한다.
   */
  test('rowCount가 null인 DOCUMENT 데이터셋의 정보 탭이 크래시 없이 - 를 표시한다', async ({
    authenticatedPage: page,
  }) => {
    // 페이지 에러(미처리 예외)를 수집 — toLocaleString()이 throw하면 여기에 잡힌다.
    const pageErrors: Error[] = [];
    page.on('pageerror', (err) => pageErrors.push(err));

    await setupDocumentDatasetMocks(page, DATASET_ID, createDocuments(2));

    // 정보 탭(기본 탭)으로 진입
    await page.goto(`/data/datasets/${DATASET_ID}`);

    // 정보 탭이 활성 상태로 렌더링되어야 한다
    await expect(page.getByRole('tab', { name: '정보' })).toHaveAttribute('data-state', 'active');

    // 행 카운트 카드가 '-'를 표시해야 한다 ('행' 라벨 카드 내부)
    const rowCard = page.locator('div').filter({ hasText: /^-행$/ });
    await expect(rowCard).toBeVisible();

    // 렌더 도중 미처리 예외가 없어야 한다 (rowCount.toLocaleString() 크래시 회귀 방지)
    expect(pageErrors).toHaveLength(0);
  });
});
