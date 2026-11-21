import type { Locator } from '@playwright/test';

import { setupAdminAuth, setupAuditLogMocks, setupRoleListMocks } from '../fixtures/admin.fixture';
import { expect, test } from '../fixtures/auth.fixture';

/** 열림 애니메이션(zoom-in-95)이 끝나기 전에는 박스가 5% 작게 측정되므로 애니메이션 종료를 기다린다. */
async function settle(dialog: Locator) {
  await dialog.evaluate((el) => Promise.all(el.getAnimations().map((a) => a.finished)));
}

/**
 * 열린 다이얼로그의 오른쪽 위 닫기 X 가 32×32 이상인지 보고 그 버튼을 돌려준다.
 * X 는 사용자가 듣는 이름(sr-only 「닫기」)으로 찾는다 — 시험하는 두 창에는 다른 「닫기」 버튼이 없다.
 */
async function expectHitTarget(dialog: Locator) {
  await expect(dialog).toBeVisible();
  await settle(dialog);
  const close = dialog.getByRole('button', { name: '닫기', exact: true });
  const box = await close.boundingBox();
  if (!box) throw new Error('닫기 버튼 박스를 얻지 못했다');
  expect(box.width).toBeGreaterThanOrEqual(32);
  expect(box.height).toBeGreaterThanOrEqual(32);
  return close;
}

/**
 * WD-901: 공용 Dialog 오른쪽 위 닫기 X 는 아이콘 상자 그대로 16×16 이라 최소 클릭 대상(32×32)에 못 미쳤다.
 * 클릭 영역만 32×32 로 넓히고, 아이콘(16px)과 보이는 위치는 그대로여야 한다.
 */
test.describe('Dialog 닫기 X 클릭 영역 (WD-901)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('감사 로그 상세의 닫기 X 가 32×32 이상이고 누르면 닫힌다 (WD-901)', async ({ authenticatedPage: page }) => {
    await setupAuditLogMocks(page, 1);
    await page.goto('/admin/audit-logs');
    await page.getByRole('row').nth(1).click();

    const dialog = page.getByRole('dialog');
    const close = await expectHitTarget(dialog);
    const svgBox = await close.locator('svg').boundingBox();
    const dialogBox = await dialog.boundingBox();
    if (!svgBox || !dialogBox) throw new Error('아이콘·다이얼로그 박스를 얻지 못했다');

    // 아이콘은 16×16 그대로
    expect(Math.abs(svgBox.width - 16)).toBeLessThanOrEqual(0.5);
    expect(Math.abs(svgBox.height - 16)).toBeLessThanOrEqual(0.5);
    // 아이콘은 다이얼로그 테두리 안쪽에서 오른쪽·위 16px(전과 같은 자리) — 테두리 두께를 빼고 잰다
    const border = await dialog.evaluate((el) => {
      const cs = getComputedStyle(el);
      return { top: parseFloat(cs.borderTopWidth), right: parseFloat(cs.borderRightWidth) };
    });
    expect(dialogBox.x + dialogBox.width - border.right - (svgBox.x + svgBox.width)).toBeCloseTo(16, 0);
    expect(svgBox.y - dialogBox.y - border.top).toBeCloseTo(16, 0);

    await close.click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('새 역할 다이얼로그의 닫기 X 도 32×32 이상 — 공용 적용 (WD-901)', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: '새 역할' }).click();

    await expectHitTarget(page.getByRole('dialog'));
  });
  /**
   * WD-901 리뷰 후속: 감사 로그 상세처럼 다른 포커스 대상이 없는 창은 Radix 가 열 때 X 에 첫 포커스를 준다.
   * 링이 focus 로 그려지면 마우스로 열어도 32px 상자 둘레에 늘 링이 보이므로, 키보드 포커스(focus-visible)일 때만 그려야 한다.
   */
  test('마우스로 열면 닫기 X 에 포커스 링이 없고, Tab 으로 오면 링이 보인다 (WD-901)', async ({ authenticatedPage: page }) => {
    await setupAuditLogMocks(page, 1);
    await page.goto('/admin/audit-logs');
    await page.getByRole('row').nth(1).getByRole('button', { name: /상세 보기$/ }).click();

    const dialog = page.getByRole('dialog');
    const close = await expectHitTarget(dialog);
    // 링은 box-shadow 로 그려진다 — 투명(rgba(0, 0, 0, 0))이 아닌 그림자 층이 하나라도 있으면 링이 보이는 것
    const ringVisible = () =>
      close.evaluate((el) =>
        getComputedStyle(el)
          .boxShadow.split(/,(?![^(]*\))/)
          .some((layer) => layer.trim() !== 'none' && !layer.trim().startsWith('rgba(0, 0, 0, 0)')),
      );

    // 일시 「상세 보기」 버튼을 마우스로 눌러 연 직후 — 링(box-shadow)이 없어야 한다.
    // (행 빈 곳처럼 포커스를 받지 않는 곳을 누르면 앞선 포커스가 없어 Chromium 이 스크립트 포커스를 focus-visible 로 본다 — 그 경로는 이 시험 밖)
    expect(await ringVisible()).toBe(false);

    // Tab 으로 X 까지 온다(창 안 포커스는 갇혀 돈다) — 그러면 링이 보여야 한다
    for (let i = 0; i < 10; i++) {
      await page.keyboard.press('Tab');
      if (await close.evaluate((el) => el === document.activeElement)) break;
    }
    await expect(close).toBeFocused();
    expect(await ringVisible()).toBe(true);
  });
});
