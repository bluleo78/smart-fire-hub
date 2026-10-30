import type { Page } from '@playwright/test';

import { createRole } from '../../factories/auth.factory';
import { setupAdminAuth, setupRoleDetailMocks, setupRoleListMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/**
 * 역할 상세 — 열어 둔 역할이 다른 곳에서 삭제된 경우 (WD-287)
 * - 서버는 조회·이름 저장·권한 저장·열람 등급 미리보기/저장 모두 404 + 「역할을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.」를 돌려준다.
 * - 화면은 그 한국어 안내를 토스트로 보이고, 더 저장해도 실패할 화면에 남지 않고 역할 목록(/admin/roles)으로 돌아가야 한다.
 * - 예전 영어 원문(「Role not found: 3」)은 보이지 않아야 한다.
 */

/** 서버 RoleNotFoundException.MESSAGE 와 같은 문구. */
const ROLE_GONE_MESSAGE = '역할을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.';

/** GlobalExceptionHandler 가 내는 404 본문(ErrorResponse) 모양. */
const notFoundBody = { status: 404, error: 'Not Found', message: ROLE_GONE_MESSAGE };

/** 역할 정보 카드의 「저장」 — 권한 저장·열람 등급 저장 버튼과 섞이지 않게 카드 안으로 좁힌다. */
async function saveRoleInfo(page: Page) {
  await page
    .locator('[data-slot="card"]')
    .filter({ hasText: '역할 정보' })
    .getByRole('button', { name: '저장', exact: true })
    .click();
}

/** 한국어 안내 토스트가 뜨고, 영어 원문은 없고, 미저장 경고 없이 역할 목록으로 돌아갔는지 확인한다. */
async function expectBackToListWithKoreanNotice(page: Page) {
  // Sonner 는 같은 문구 토스트를 겹쳐 그릴 수 있어 first() 로 본다.
  await expect(page.getByText(ROLE_GONE_MESSAGE).first()).toBeVisible();
  await expect(page.getByText(/Role not found/)).toHaveCount(0);
  await expect(page).toHaveURL(/\/admin\/roles$/);
  // 저장 실패 시점엔 폼이 dirty 라도, 지킬 역할이 사라졌으니 「저장하지 않은 변경사항」 확인 없이 이동해야 한다.
  await expect(page.getByText('저장하지 않은 변경사항')).toHaveCount(0);
}

test.describe('역할 상세 — 삭제된 역할 (WD-287)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSecurityLevelMocks(page);
    // 목록으로 돌아간 뒤 그려질 역할 목록
    await setupRoleListMocks(page);
  });

  test('삭제된 역할을 열면 한국어 안내 후 역할 목록으로 돌아간다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/roles/3', notFoundBody, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/permissions', []);

    await page.goto('/admin/roles/3');

    await expectBackToListWithKoreanNotice(page);
    // 404 는 일반 로드 실패 문구로 뭉뚱그리지 않는다.
    await expect(page.getByText('역할 정보를 불러오는데 실패했습니다.')).toHaveCount(0);
  });

  test('404 본문에 문구가 없어도 같은 한국어 안내로 대신한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/roles/3', {}, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/permissions', []);

    await page.goto('/admin/roles/3');

    await expectBackToListWithKoreanNotice(page);
  });

  test.describe('열어 둔 뒤 삭제된 역할을 저장하면', () => {
    test.beforeEach(async ({ authenticatedPage: page }) => {
      await setupRoleDetailMocks(page, 3, false);
      await mockApi(page, 'GET', '/api/v1/roles/3/clearance', {
        roleId: 3,
        securityLevelId: 2,
        userCount: 1,
        fixed: false,
      });
    });

    test('이름·설명 저장이 404 면 한국어 안내 후 목록으로 돌아간다', async ({ authenticatedPage: page }) => {
      const update = await mockApi(page, 'PUT', '/api/v1/roles/3', notFoundBody, { status: 404, capture: true });
      await page.goto('/admin/roles/3');

      await page.getByLabel('설명').fill('삭제된 역할에 저장');
      await saveRoleInfo(page);
      await update.waitForRequest();

      await expectBackToListWithKoreanNotice(page);
    });

    test('권한 저장이 404 면 한국어 안내 후 목록으로 돌아간다', async ({ authenticatedPage: page }) => {
      const put = await mockApi(page, 'PUT', '/api/v1/roles/3/permissions', notFoundBody, {
        status: 404,
        capture: true,
      });
      await page.goto('/admin/roles/3');

      await page.getByRole('checkbox', { name: /PIPELINE_READ/ }).click();
      await page.getByRole('button', { name: '권한 저장' }).click();
      await put.waitForRequest();

      await expectBackToListWithKoreanNotice(page);
    });

    test('열람 등급 저장이 404 면 한국어 안내 후 목록으로 돌아간다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'POST', '/api/v1/roles/3/clearance/preview', {
        callerLostDatasetCount: 0,
        topLevelRoleCountAfter: 1,
        roleUserCount: 1,
      });
      const put = await mockApi(page, 'PUT', '/api/v1/roles/3/clearance', notFoundBody, {
        status: 404,
        capture: true,
      });
      await page.goto('/admin/roles/3');

      const card = page.getByTestId('role-clearance-card');
      await card.getByRole('radio', { name: /민감/ }).check();
      await card.getByRole('button', { name: '저장' }).click();
      await put.waitForRequest();

      await expectBackToListWithKoreanNotice(page);
    });

    test('열람 등급을 고를 때 미리보기가 404 면 한국어 안내 후 목록으로 돌아간다', async ({
      authenticatedPage: page,
    }) => {
      // 저장 버튼은 미리보기가 성공해야 열리므로, 삭제된 역할은 등급을 고르는 순간의 미리보기에서 처음 드러난다.
      const preview = await mockApi(page, 'POST', '/api/v1/roles/3/clearance/preview', notFoundBody, {
        status: 404,
        capture: true,
      });
      await page.goto('/admin/roles/3');

      await page.getByTestId('role-clearance-card').getByRole('radio', { name: /민감/ }).check();
      await preview.waitForRequest();

      await expectBackToListWithKoreanNotice(page);
    });
  });

  test('목록에서 이미 삭제된 역할을 지우면 한국어 안내 후 목록을 다시 읽어 그 행을 지운다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'DELETE', '/api/v1/roles/3', notFoundBody, { status: 404 });
    await page.goto('/admin/roles');

    await page.getByRole('button', { name: 'EDITOR 역할 삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    // 다른 곳에서 이미 지워진 상태 — 다시 읽으면 EDITOR 가 없다(나중 등록이 우선한다).
    await mockApi(page, 'GET', '/api/v1/roles', [
      createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
      createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자', isSystem: true }),
    ]);
    await dialog.getByRole('button', { name: /삭제|확인/ }).click();

    await expect(page.getByText(ROLE_GONE_MESSAGE).first()).toBeVisible();
    await expect(page.getByText(/Role not found/)).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'EDITOR 역할 삭제' })).toHaveCount(0);
  });
});
