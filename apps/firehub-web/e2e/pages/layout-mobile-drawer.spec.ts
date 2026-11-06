import { expect, test } from '../fixtures/auth.fixture';

/**
 * 모바일 메뉴 서랍 키보드·접근성 (WD-508)
 *
 * lg 미만에서 사이드바는 화면 밖으로 밀려나는 서랍이다. 예전에는 CSS 이동만 해서 닫힌 서랍의 링크가 Tab 순서·접근성 트리에 남아
 * 첫 Tab 부터 보이지 않는 곳으로 포커스가 갔고, 열린 서랍은 Esc 로 닫히지 않았으며 대화상자 의미도 없었다.
 */
test.describe('모바일 메뉴 서랍 (WD-508)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/this-page-does-not-exist-wd508');
    await expect(page.getByRole('heading', { name: '404' })).toBeVisible();
  });

  test('닫힌 서랍의 링크는 Tab 으로 가지 않고 접근성 트리에서도 빠진다', async ({ authenticatedPage: page }) => {
    // 닫힌 서랍은 inert — 브라우저가 포커스·접근성 트리에서 뺀다(Playwright 역할 조회는 inert 를 거르지 않아 속성으로 본다)
    await expect(page.locator('aside')).toHaveAttribute('inert', '');
    // 서랍 안 링크에 직접 focus() 를 걸어도 들어가지 않는다
    const focused = await page.evaluate(() => {
      const link = document.querySelector<HTMLElement>('aside a[href]');
      link?.focus();
      return document.activeElement === link;
    });
    expect(focused).toBe(false);

    // 처음 몇 번의 Tab 이 서랍 안으로 들어가지 않는다
    for (let i = 0; i < 5; i += 1) {
      await page.keyboard.press('Tab');
      const inAside = await page.evaluate(() => !!document.activeElement?.closest('aside'));
      expect(inAside).toBe(false);
    }
  });

  test('열린 서랍은 메뉴 대화상자이고 Tab 이 본문으로 새지 않으며, Esc 로 닫히고 포커스가 메뉴 버튼으로 돌아온다', async ({
    authenticatedPage: page,
  }) => {
    const menuButton = page.getByRole('button', { name: '메뉴 열기' });
    await menuButton.click();

    const dialog = page.getByRole('dialog', { name: '메뉴' });
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveAttribute('aria-modal', 'true');
    // 열리면 포커스가 서랍 안으로 들어간다
    await expect.poll(() => page.evaluate(() => !!document.activeElement?.closest('aside'))).toBe(true);

    // 서랍이 열린 동안 Tab 을 여러 번 눌러도 포커스는 서랍 밖(본문)으로 가지 않는다
    for (let i = 0; i < 30; i += 1) {
      await page.keyboard.press('Tab');
      const outside = await page.evaluate(() => {
        const el = document.activeElement;
        return !!el && el !== document.body && !el.closest('aside');
      });
      expect(outside).toBe(false);
    }

    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog', { name: '메뉴' })).toHaveCount(0);
    await expect(page.locator('aside')).toHaveAttribute('inert', '');
    await expect(page.getByRole('button', { name: '메뉴 열기' })).toBeFocused();
  });

  test('바깥(어두운 배경)을 누르면 닫히고 포커스가 메뉴 버튼으로 돌아온다', async ({ authenticatedPage: page }) => {
    await page.getByRole('button', { name: '메뉴 열기' }).click();
    await expect(page.getByRole('dialog', { name: '메뉴' })).toBeVisible();
    // 서랍(폭 240px) 오른쪽의 배경을 누른다
    await page.mouse.click(350, 400);
    await expect(page.getByRole('dialog', { name: '메뉴' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '메뉴 열기' })).toBeFocused();
  });

  test('데스크톱(1280px)에서는 사이드바가 inert·대화상자가 아니고 링크로 갈 수 있다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const aside = page.locator('aside');
    await expect(aside).not.toHaveAttribute('inert', '');
    await expect(aside).not.toHaveAttribute('role', 'dialog');
    await expect(page.getByRole('link', { name: '카테고리' })).toBeVisible();
  });
});
