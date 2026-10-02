import type { Locator } from '@playwright/test';

import { createRole, createUser, createUserDetail } from '../factories/auth.factory';
import { setupAdminAuth, setupRoleListMocks } from '../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * #788: 다이얼로그 제목은 디자인 시스템 heading-card(text-xl leading-7 = 20px/28px)다.
 * 개별 className 이 없는 다이얼로그(역할 생성·역할 삭제 확인)도 기본값만으로 20px 여야 한다 — 그래야 기본 클래스를
 * 고쳤다는 증거가 된다. WD-2 다이얼로그(멤버 추가·멤버십 정지)는 개별 className 을 지운 뒤에도 20px 여야 한다.
 */
async function expectHeadingCard(title: Locator) {
  await expect(title).toHaveCSS('font-size', '20px');
  await expect(title).toHaveCSS('line-height', '28px');
  await expect(title).toHaveCSS('font-weight', '600');
}

test.describe('다이얼로그 제목 타이포그래피 (#788)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('일반 Dialog(역할 생성) 제목이 20px/28px', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: '역할 추가' }).click();
    await expectHeadingCard(page.getByRole('dialog').locator('[data-slot="dialog-title"]'));
  });

  test('DeleteConfirmDialog(AlertDialog 기본 크기) 제목이 20px/28px', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: 'EDITOR 역할 삭제' }).click();
    await expectHeadingCard(page.getByRole('alertdialog').locator('[data-slot="alert-dialog-title"]'));
  });

  test('size="sm" AlertDialog(멤버십 정지) 제목이 개별 className 없이 20px/28px', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/roles', [createRole({ id: 1, name: 'USER', isSystem: true })]);
    await mockApi(page, 'GET', '/api/v1/users/5', createUserDetail({ id: 5, name: '김지수', username: 'jisu@acme.io', email: 'jisu@acme.io' }));
    await page.goto('/admin/users/5');
    await page.getByRole('switch', { name: '이 워크스페이스에서 활성' }).click();
    await expectHeadingCard(page.getByRole('alertdialog').locator('[data-slot="alert-dialog-title"]'));
  });

  test('멤버 추가 Dialog 제목이 개별 className 없이 20px/28px', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/roles', [createRole({ id: 1, name: 'USER', isSystem: true })]);
    await mockApi(page, 'GET', '/api/v1/users', createPageResponse([createUser({ id: 1, name: '양동희' })]));
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    await expectHeadingCard(page.getByRole('dialog', { name: '멤버 추가' }).locator('[data-slot="dialog-title"]'));
  });
});
