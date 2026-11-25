import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';
import { expectDialogWithinViewportHeight } from '../../fixtures/dialog.fixture';

/**
 * WD-1123: 필드 추가(ColumnDialog) 대화상자 높이가 보이는 뷰포트(dvh) 안에 머문다.
 * - 호출처가 덮어쓴 max-h-[90vh] 는 모바일 주소창에 가려진 높이까지 포함해 대화상자가 잘렸다.
 */
test.describe('필드 추가 대화상자 높이 — 보이는 뷰포트 기준 (WD-1123)', () => {
  test('낮은 뷰포트에서 필드 추가 대화상자가 뷰포트 안에 머문다 (WD-1123)', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 375, height: 500 });
    await grantDatasetPermissions(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets/5',
      createDatasetDetail({
        id: 5,
        rowCount: 0,
        columns: [createColumn({ id: 1, columnName: 'title', displayName: '제목', dataType: 'TEXT', columnOrder: 0 })],
      }),
    );
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/5/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/5/stats', []);

    await page.goto('/data/datasets/5');
    await page.getByRole('tab', { name: '필드' }).click();
    await page.getByRole('button', { name: '필드 추가' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    // 호출처가 vh 로 덮어쓰지 않는다 — 데스크톱 브라우저는 vh==dvh 라 좌표만으로는 수정 전후를 못 가른다.
    await expect(dialog).not.toHaveClass(/90vh/);
    await expectDialogWithinViewportHeight(page, dialog);
  });
});
