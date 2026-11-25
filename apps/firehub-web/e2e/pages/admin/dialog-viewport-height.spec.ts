import { createRole, createUser } from '../../factories/auth.factory';
import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';
import { expectDialogWithinViewportHeight } from '../../fixtures/dialog.fixture';

/**
 * WD-1123: 대화상자 높이가 보이는 뷰포트(dvh) 안에 머문다.
 * - 호출처가 덮어쓴 max-h-[90vh] 는 모바일 주소창에 가려진 높이까지 포함해 대화상자가 잘렸다.
 * - 안쪽 영역이 스크롤하는 대화상자는 바깥도 overflow-y-auto 라 스크롤 창이 이중으로 생겼다.
 */
test.describe('대화상자 높이 — 보이는 뷰포트 기준 (WD-1123)', () => {
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

  test('낮은 뷰포트에서 멤버 추가 대화상자는 바깥이 아니라 안쪽 영역만 스크롤한다 (WD-1123)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await mockApi(page, 'GET', '/api/v1/roles', [
      createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
      createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자', isSystem: true }),
    ]);
    await mockApi(
      page,
      'GET',
      '/api/v1/users',
      createPageResponse([createUser({ id: 1, name: '양동희', username: 'owner@acme.io', email: 'owner@acme.io' })]),
    );
    await page.setViewportSize({ width: 375, height: 420 });

    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    await expect(dialog).toBeVisible();

    await expectDialogWithinViewportHeight(page, dialog);

    // 바깥(대화상자)은 스크롤 컨테이너가 아니고(overflow-y: hidden), 안쪽 영역이 스크롤한다.
    // scrollHeight 로는 재지 않는다 — 체크박스의 숨은 <input>(absolute)이 안쪽 스크롤 영역의 클립을 벗어나 값을 부풀린다.
    await expect(dialog).toHaveCSS('overflow-y', 'hidden');
    const inner = await dialog
      .getByTestId('add-member-dialog-scroll')
      .evaluate((el) => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight }));
    expect(inner.scrollHeight).toBeGreaterThan(inner.clientHeight);
  });
});
