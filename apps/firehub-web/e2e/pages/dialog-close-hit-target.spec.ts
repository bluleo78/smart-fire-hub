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
});
