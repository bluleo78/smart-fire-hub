import { expect, test } from '../fixtures/auth.fixture';

/**
 * 모바일 상단 줄 회귀 가드 (WD-397)
 *
 * 좁은 화면(lg 미만)에서 메뉴 버튼·AI 상태 칩·알림 종은 position:fixed 로 떠 있다. 예전에는 그 자리에 배경도 흐름상 자리도 없어서
 * 본문을 스크롤하면 글자·버튼이 이 아이콘들 밑을 지나며 겹쳤다. 이제 흐름 안의 상단 줄(h-12, 불투명 배경)이 그 높이를 차지하고
 * 본문(<main>)은 그 줄 아래에서 시작·스크롤된다.
 */
test.describe('모바일 상단 줄 (WD-397)', () => {
  test('390px 에서 본문은 상단 줄 아래에서 시작하고, 스크롤해도 고정 아이콘 밑으로 지나가지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/this-page-does-not-exist-wd397');
    await expect(page.getByRole('heading', { name: '404' })).toBeVisible();

    const bar = page.getByTestId('mobile-topbar');
    await expect(bar).toBeVisible();
    // 불투명 배경 — 투명(rgba(0,0,0,0))이면 겹침이 그대로 비친다
    const bg = await bar.evaluate((el) => getComputedStyle(el).backgroundColor);
    expect(bg).not.toBe('rgba(0, 0, 0, 0)');
    expect(bg).not.toBe('transparent');

    // 메뉴 버튼·AI 상태 칩은 상단 줄 안에 들어 있다(줄 아래로 삐져나와 본문과 겹치지 않는다)
    const barBox = (await bar.boundingBox())!;
    const menuBox = (await page.getByRole('button', { name: '메뉴 열기' }).boundingBox())!;
    expect(menuBox.y + menuBox.height).toBeLessThanOrEqual(barBox.y + barBox.height + 0.5);

    // 본문은 상단 줄 바로 아래에서 시작한다 — 본문을 길게 만들어 스크롤해도 main 의 윗변은 그대로다
    const mainTopBefore = await page.evaluate(() => document.querySelector('main')!.getBoundingClientRect().top);
    expect(mainTopBefore).toBeGreaterThanOrEqual(barBox.y + barBox.height - 0.5);
    await page.evaluate(() => {
      const main = document.querySelector('main')!;
      const spacer = document.createElement('div');
      spacer.style.height = '2000px';
      main.appendChild(spacer);
      main.scrollTop = 120;
    });
    expect(await page.evaluate(() => document.querySelector('main')!.scrollTop)).toBeGreaterThan(0);
    const mainTopAfter = await page.evaluate(() => document.querySelector('main')!.getBoundingClientRect().top);
    expect(mainTopAfter).toBeGreaterThanOrEqual(barBox.y + barBox.height - 0.5);
  });

  test('데스크톱(1280px)에서는 상단 줄을 그리지 않는다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/this-page-does-not-exist-wd397');
    await expect(page.getByRole('heading', { name: '404' })).toBeVisible();
    await expect(page.getByTestId('mobile-topbar')).toBeHidden();
  });
});
