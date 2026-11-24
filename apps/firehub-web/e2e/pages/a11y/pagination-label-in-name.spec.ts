/**
 * 페이지 사이즈 콤보박스 접근 이름 회귀 E2E (WD-1066, WCAG 2.5.3 Label in Name)
 *
 * 보이는 라벨 「페이지당」과 같은 이름으로 콤보박스를 찾을 수 있어야 음성 명령·스크린리더가
 * 사용자가 보는 라벨 그대로를 불러도 맞는 컨트롤을 잡는다.
 * 감사 로그 화면의 인증·모킹은 audit-logs-pagination.spec.ts 와 같은 패턴을 따른다.
 */
import { createAuditLog } from '../../factories/admin.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('페이지 사이즈 콤보박스 접근 이름 (WD-1066)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('페이지 사이즈 콤보박스의 접근 가능한 이름은 보이는 라벨 「페이지당」이다 (WD-1066)', async ({
    authenticatedPage: page,
  }) => {
    // 390건 / 20건 페이지 기준으로 모킹해 페이지네이션(사이즈 선택기 포함)이 뜨게 한다
    const logs = Array.from({ length: 20 }, (_, i) => createAuditLog({ id: i + 1 }));
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse(logs, { totalElements: 390, totalPages: 20 }),
    );
    await page.goto('/admin/audit-logs');

    // 보이는 라벨과 접근 이름이 한 세트여야 WCAG 2.5.3 전제가 고정된다
    await expect(page.getByText('페이지당', { exact: true })).toBeVisible();
    // 접근 가능한 이름이 보이는 라벨 「페이지당」과 정확히 일치해야 한다(WD-1066, 리뷰 후속 — exact 로 부분 일치까지 통과하지 못하게 고정)
    await expect(page.getByRole('combobox', { name: '페이지당', exact: true })).toBeVisible();
  });
});
