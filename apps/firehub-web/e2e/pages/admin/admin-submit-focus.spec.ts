import type { Page } from '@playwright/test';

import { createRole } from '../../factories/auth.factory';
import { setupAdminAuth, setupRoleListMocks } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { holdApi, submitAndRetryWhilePending } from '../../fixtures/hold-api';

/**
 * 관리 화면 다이얼로그 제출 중 포커스 유지 E2E (WD-637, WD-572 후속)
 *
 * 결함: 멤버 추가·새 역할 다이얼로그의 제출 버튼이 disabled={isSubmitting} 이라, Enter 로 제출하면
 * 포커스가 있던 버튼이 비활성화되며 다이얼로그 밖(body)으로 떨어졌다(WCAG 2.4.3).
 * 이제 PendingSubmitButton(aria-disabled) + useSubmitGuard 로 포커스를 버튼에 두고, 진행 중 재제출은 무시한다.
 */

/** 포커스가 열린 다이얼로그 안에 있는가 */
async function focusInsideDialog(page: Page) {
  return page.evaluate(() => !!document.querySelector('[role="dialog"]')?.contains(document.activeElement));
}

test.describe('멤버 추가 — 제출 중 포커스 (WD-637)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockApi(page, 'GET', '/api/v1/roles', [createRole({ id: 1, name: 'USER', isSystem: true })]);
    await mockApi(page, 'GET', '/api/v1/users', createPageResponse([]));
  });

  /** 다이얼로그를 열어 필수 칸을 채운다(임시 비밀번호는 열 때 자동 생성) */
  async function openAndFill(page: Page) {
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    await dialog.getByLabel('이메일').fill('newbie@acme.io');
    await dialog.getByLabel('이름').fill('뉴비');
    return dialog;
  }

  test('서버 오류: Enter 로 제출해도 포커스가 「추가」에 남고 요청은 한 번만 간다', async ({ authenticatedPage: page }) => {
    const post = await holdApi(page, 'POST', '/api/v1/users', 500, { status: 500, message: '서버 오류가 발생했습니다.' });
    const dialog = await openAndFill(page);
    const submit = dialog.getByRole('button', { name: '추가', exact: true });

    // 진행 중 라벨은 「추가」 그대로다(스피너만 붙는다)
    await submitAndRetryWhilePending(page, submit, submit);
    post.release();

    await expect(dialog.getByRole('alert')).toHaveText('서버 오류가 발생했습니다.');
    await expect(submit).toBeFocused();
    expect(await focusInsideDialog(page)).toBe(true);
    expect(post.requests.length).toBe(1);
  });

  test('이미 멤버(409): 포커스가 다이얼로그 안의 이메일 칸으로 옮겨진다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/users', { status: 409, message: '이미 이 워크스페이스의 멤버입니다' }, { status: 409 });
    const dialog = await openAndFill(page);

    await dialog.getByRole('button', { name: '추가', exact: true }).focus();
    await page.keyboard.press('Enter');

    await expect(dialog.getByText('이미 이 워크스페이스의 멤버입니다')).toBeVisible();
    await expect(dialog.getByLabel('이메일')).toBeFocused();
    expect(await focusInsideDialog(page)).toBe(true);
  });
});

test.describe('새 역할 — 제출 중 포커스 (WD-637)', () => {
  test('서버 오류: Enter 로 제출해도 포커스가 「만들기」에 남고 요청은 한 번만 간다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupRoleListMocks(page);
    const post = await holdApi(page, 'POST', '/api/v1/roles', 500, { status: 500, message: '역할 서버 오류' });

    await page.goto('/admin/roles');
    await page.getByRole('button', { name: '새 역할' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('역할 이름').fill('FOCUS_ROLE');
    await submitAndRetryWhilePending(
      page,
      dialog.getByRole('button', { name: '만들기' }),
      dialog.getByRole('button', { name: '만드는 중...' }),
    );
    post.release();

    await expect(dialog.getByText('역할 서버 오류')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '만들기' })).toBeFocused();
    expect(await focusInsideDialog(page)).toBe(true);
    expect(post.requests.length).toBe(1);
  });
});
