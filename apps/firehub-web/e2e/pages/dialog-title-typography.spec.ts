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

/**
 * WD-9: 닫기 X(absolute top-4 right-4)가 있는 Dialog 는 헤더 내용 영역의 오른쪽 끝이 X 왼쪽 끝을 넘지 않아야 한다.
 * "헤더 오른쪽 끝 - padding-right ≤ X 왼쪽" 은 제목 길이와 무관하게 성립해야 하는 기하 조건이라 긴 제목 픽스처가 필요 없다.
 */
test.describe('Dialog 헤더와 닫기 버튼 (WD-9)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('헤더 내용 영역이 닫기 X 와 겹치지 않는다', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: '역할 추가' }).click();

    const dialog = page.getByRole('dialog');
    const header = dialog.locator('[data-slot="dialog-header"]');
    const close = dialog.locator('[data-slot="dialog-close"]');
    const headerBox = await header.boundingBox();
    const closeBox = await close.boundingBox();
    const paddingRight = await header.evaluate((el) => parseFloat(getComputedStyle(el).paddingRight));
    if (!headerBox || !closeBox) throw new Error('헤더 또는 닫기 버튼 박스를 얻지 못했다');

    expect(headerBox.x + headerBox.width - paddingRight).toBeLessThanOrEqual(closeBox.x);
  });

  /**
   * 음성 대조: X 가 없는(showCloseButton={false}) 다이얼로그의 헤더에는 여백이 새로 생기면 안 된다.
   * 멤버 추가 결과 화면은 헤더에 개별 padding 없이 X 만 숨기므로, 조건 없이 pr-6 을 박으면 0px → 24px 로 바뀌어 빨개진다.
   * (ReportModal 은 헤더에 px-6 이 이미 있어 24px 로 구분이 안 돼 쓰지 않는다.)
   */
  test('X 가 없는 다이얼로그(멤버 추가 결과 화면)의 헤더에는 우측 여백이 생기지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/roles', [createRole({ id: 1, name: 'USER', isSystem: true })]);
    await mockApi(page, 'GET', '/api/v1/users', createPageResponse([createUser({ id: 1, name: '양동희' })]));
    await mockApi(page, 'POST', '/api/v1/users', { userId: 9, created: true }, { status: 201 });
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const form = page.getByRole('dialog', { name: '멤버 추가' });
    await form.getByLabel('이메일').fill('newbie@acme.io');
    await form.getByLabel('이름').fill('뉴비');
    await form.getByRole('button', { name: '추가' }).click();

    const result = page.getByRole('dialog');
    await expect(result.getByRole('heading', { name: '계정을 만들었습니다' })).toBeVisible();
    await expect(result.locator('[data-slot="dialog-close"]')).toHaveCount(0);
    await expect(result.locator('[data-slot="dialog-header"]')).toHaveCSS('padding-right', '0px');
  });
});
