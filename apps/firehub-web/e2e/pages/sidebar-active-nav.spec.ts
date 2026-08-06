import { setupAdminAuth, setupSettingsMocks } from '../fixtures/admin.fixture';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * 사이드바 활성 메뉴 노출 회귀 가드.
 *
 * 배경: nav 항목이 뷰포트를 넘어 항상 일부가 잘려 있는데(실측 856px 콘텐츠 / 614px 가시)
 * 잘린 쪽 메뉴로 진입해도 nav가 스크롤되지 않았다. `/admin/settings`에서 활성 항목 "설정"이
 * top=872px, nav 하단이 670px — 현재 어느 메뉴에 있는지 화면에 단서가 전혀 없었다.
 *
 * 뷰포트를 낮춰 nav가 확실히 잘리도록 만든 뒤 검증한다.
 */

const SHORT_VIEWPORT = { width: 1280, height: 500 };

test.describe('사이드바 활성 메뉴', () => {
  test('활성 링크에 aria-current="page"가 붙는다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets');
    const active = page.locator('aside nav [aria-current="page"]');
    await expect(active).toHaveText('데이터셋');
  });

  test('잘린 영역의 메뉴로 진입하면 nav가 활성 항목까지 스크롤된다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupSettingsMocks(page);
    await page.setViewportSize(SHORT_VIEWPORT);
    await page.goto('/admin/settings');

    const nav = page.locator('aside nav');
    await expect(nav).toBeVisible();

    // nav가 실제로 잘려 있어야 이 테스트가 의미를 갖는다
    await expect
      .poll(() => nav.evaluate((el) => el.scrollHeight - el.clientHeight))
      .toBeGreaterThan(0);

    const active = nav.locator('[aria-current="page"]');
    await expect(active).toHaveText('설정');

    // 활성 항목이 nav의 가시 영역 안에 있는지 — 좌표로 직접 확인한다
    await expect
      .poll(async () =>
        active.evaluate((el) => {
          const navEl = el.closest('nav');
          if (!navEl) return false;
          const a = el.getBoundingClientRect();
          const n = navEl.getBoundingClientRect();
          return a.top >= n.top - 1 && a.bottom <= n.bottom + 1;
        })
      )
      .toBe(true);
  });

  test('이미 보이는 메뉴를 누르면 nav를 불필요하게 움직이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // block:'nearest'의 계약 — 'center'로 바꾸면 위쪽 메뉴를 눌러도 nav가 덜컥 움직인다.
    await page.setViewportSize(SHORT_VIEWPORT);
    await page.goto('/data/datasets');
    const nav = page.locator('aside nav');
    await expect(nav).toBeVisible();

    await expect.poll(() => nav.evaluate((el) => el.scrollTop)).toBe(0);

    await page.getByRole('link', { name: '카테고리' }).click();
    await expect(page).toHaveURL(/\/data\/categories/);

    // '카테고리'는 상단에 있어 이미 보인다 — nav는 그대로여야 한다
    await expect.poll(() => nav.evaluate((el) => el.scrollTop)).toBe(0);
  });
});
