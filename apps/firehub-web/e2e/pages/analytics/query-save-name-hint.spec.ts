/**
 * 쿼리 저장 다이얼로그 이름 칸 안내 E2E (WD-828)
 *
 * 결함: 이름이 비면 「만들기」·「저장」 버튼이 꺼지는데 이유가 화면에 없었다.
 * 이제 이름 칸 아래에 안내(hint)를 보이고 aria-describedby 로 칸에 잇는다.
 * 중복 이름 409(WD-824) 오류가 있으면 안내 대신 오류만 보인다.
 */

import type { Page, Route } from '@playwright/test';

import { setupNewQueryEditorMocks, setupQueryEditorMocks } from '../../fixtures/analytics.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/** 새 쿼리 편집기에서 SQL 을 채우고 저장 다이얼로그를 연다 */
async function openNewSaveDialog(page: Page) {
  await setupNewQueryEditorMocks(page);
  await page.goto('/analytics/queries/new');
  await page.locator('.cm-content').click();
  await page.keyboard.type('SELECT 1');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '쿼리 저장' });
  await expect(dialog).toBeVisible();
  return dialog;
}

test.describe('쿼리 저장 다이얼로그 — 이름 안내 (WD-828)', () => {
  test('새 쿼리 저장 — 이름을 비우면 안내가 보이고 칸에 이어진다', async ({ authenticatedPage: page }) => {
    const dialog = await openNewSaveDialog(page);
    const input = dialog.locator('#query-name');
    const hint = dialog.locator('#query-name-hint');
    const createBtn = dialog.getByRole('button', { name: '만들기' });

    await input.fill('');
    await expect(hint).toHaveText('이름을 입력하면 만들 수 있습니다.');
    await expect(input).toHaveAttribute('aria-describedby', /query-name-hint/);
    await expect(createBtn).toBeDisabled();

    // 공백만 넣어도 같다
    await input.fill('   ');
    await expect(hint).toHaveText('이름을 입력하면 만들 수 있습니다.');
    await expect(createBtn).toBeDisabled();

    // 이름을 넣으면 안내가 사라지고 버튼이 켜진다
    await input.fill('새 이름');
    await expect(hint).toHaveCount(0);
    await expect(input).not.toHaveAttribute('aria-describedby', /query-name-hint/);
    await expect(createBtn).toBeEnabled();
  });

  test('기존 쿼리 수정 — 이름을 비우면 저장 안내', async ({ authenticatedPage: page }) => {
    await setupQueryEditorMocks(page, 1);
    await page.goto('/analytics/queries/1');
    await expect(page.locator('.cm-content')).toContainText('SELECT');

    await page.getByRole('button', { name: '저장', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '쿼리 수정' });
    await dialog.locator('#query-name').fill('');
    await expect(dialog.locator('#query-name-hint')).toHaveText('이름을 입력하면 저장할 수 있습니다.');
  });

  test('중복 이름 409 — 안내 대신 오류만', async ({ authenticatedPage: page }) => {
    const dialog = await openNewSaveDialog(page);
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries',
      (route: Route) => {
        if (route.request().method() === 'GET') return route.fallback();
        return route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            status: 409,
            error: 'Conflict',
            code: 'DUPLICATE_NAME',
            message: '이미 있는 쿼리 이름입니다. 다른 이름을 입력하세요.',
          }),
        });
      },
    );
    const input = dialog.locator('#query-name');
    await input.fill('겹치는 이름');
    await dialog.getByRole('button', { name: '만들기' }).click();

    await expect(dialog.locator('#query-name-error')).toBeVisible();
    await expect(dialog.locator('#query-name-hint')).toHaveCount(0);
    await expect(input).toHaveAttribute('aria-describedby', /query-name-error/);
    await expect(input).toHaveAttribute('aria-invalid', 'true');
  });
});
