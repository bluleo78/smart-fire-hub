import { createAuditLog } from '../../factories/admin.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 감사 로그 페이지의 강화된 페이지네이션 검증 (이슈 #65)
 * - 페이지 번호 버튼이 노출되는지
 * - 처음/마지막 점프 버튼이 동작하는지
 * - 페이지 사이즈 selector 가 API 파라미터에 반영되는지
 * - 첫 페이지에서 처음/이전 비활성, 마지막에서 다음/마지막 비활성
 */
test.describe('감사 로그 페이지네이션 - 강화 (이슈 #65)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('페이지 번호 버튼이 렌더링되고 클릭 시 page 파라미터가 전달된다', async ({
    authenticatedPage: page,
  }) => {
    // 390건 / 20건/page = 20 페이지
    const logs = Array.from({ length: 20 }, (_, i) => createAuditLog({ id: i + 1 }));
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse(logs, { totalElements: 390, totalPages: 20 }),
      { capture: true },
    );
    await page.goto('/admin/audit-logs');

    // 페이지네이션 nav 노출 확인
    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    await expect(nav).toBeVisible();

    // 1 페이지 버튼 (현재) 와 마지막 20 페이지 버튼 둘 다 노출
    await expect(nav.getByRole('button', { name: '1 페이지' })).toBeVisible();
    await expect(nav.getByRole('button', { name: '20 페이지' })).toBeVisible();

    // 마지막 페이지 점프 버튼 클릭
    await nav.getByRole('button', { name: '마지막 페이지' }).click();

    // API 가 page=19 로 호출됐는지 검증
    await page.waitForTimeout(200);
    const req = capture.lastRequest();
    expect(req?.searchParams.get('page')).toBe('19');
  });

  test('페이지 사이즈 selector 변경 시 size 파라미터가 변경되고 page 가 0 으로 리셋된다', async ({
    authenticatedPage: page,
  }) => {
    const logs = Array.from({ length: 20 }, (_, i) => createAuditLog({ id: i + 1 }));
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse(logs, { totalElements: 390, totalPages: 20 }),
      { capture: true },
    );
    await page.goto('/admin/audit-logs');

    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    await expect(nav).toBeVisible();

    // 먼저 마지막 페이지로 점프 (page=19)
    await nav.getByRole('button', { name: '마지막 페이지' }).click();
    await page.waitForTimeout(150);
    expect(capture.lastRequest()?.searchParams.get('page')).toBe('19');

    // 페이지 사이즈를 50으로 변경
    const sizeSelector = page.getByRole('combobox', { name: '페이지 사이즈' });
    await sizeSelector.click();
    await page.getByRole('option', { name: '50' }).click();

    await page.waitForTimeout(200);
    const req = capture.lastRequest();
    expect(req?.searchParams.get('size')).toBe('50');
    // page 가 0 으로 리셋됐는지 확인
    expect(req?.searchParams.get('page')).toBe('0');
  });

  test('첫 페이지에서 처음/이전 버튼이 비활성화된다', async ({ authenticatedPage: page }) => {
    const logs = Array.from({ length: 20 }, (_, i) => createAuditLog({ id: i + 1 }));
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse(logs, { totalElements: 390, totalPages: 20, page: 0 }),
    );
    await page.goto('/admin/audit-logs');

    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    await expect(nav.getByRole('button', { name: '처음 페이지' })).toBeDisabled();
    await expect(nav.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
    // 다음 / 마지막 은 활성
    await expect(nav.getByRole('button', { name: '다음 페이지' })).toBeEnabled();
    await expect(nav.getByRole('button', { name: '마지막 페이지' })).toBeEnabled();
  });
});
