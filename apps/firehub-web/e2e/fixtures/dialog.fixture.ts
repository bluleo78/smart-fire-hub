import type { Locator, Page } from '@playwright/test';
import { expect } from '@playwright/test';

/**
 * 열린 확인 창(alertdialog)의 배경(overlay)을 마우스로 누른다 (WD-943).
 * 창은 화면 가운데에 있어 좌상단 (5, 5) 는 항상 배경 위다. 포털이어도 배경의 click 은 React 트리를 따라
 * 창을 연 행·메뉴 항목으로 번지므로, 그 번짐이 막혔는지 보려는 시험이 공통으로 쓴다.
 * 창이 보이기 전에 누르면 아무것도 누르지 못한 채 시험이 통과할 수 있어 먼저 보이기를 기다린다.
 * @param page - 대상 페이지
 */
export async function clickDialogBackdrop(page: Page): Promise<void> {
  await expect(page.getByRole('alertdialog')).toBeVisible();
  // (5, 5) 가 실제로 배경 위인지 확인한다 — 애니메이션·레이아웃 변화로 다른 요소를 누르면 번짐 시험이 헛통과한다.
  await expect
    .poll(() => page.evaluate(() => document.elementFromPoint(5, 5)?.getAttribute('data-slot') ?? null))
    .toBe('alert-dialog-overlay');
  await page.mouse.click(5, 5);
}

/**
 * 목록 행에서 연 삭제 확인 창의 배경을 눌러도 목록에 남고 삭제 요청이 없는지 확인한다 (WD-943).
 * 배경 click 이 행으로 번졌다면 이동했거나 창이 사라진다 — 창이 남아 「취소」로 닫혀야 click 이 처리된 뒤임을 보인다.
 * @param page - 확인 창이 열린 목록 페이지
 * @param deleteRequests - 창을 열기 전에 건 DELETE 요청 캡처
 */
export async function expectBackdropClickStaysOnList(page: Page, deleteRequests: readonly unknown[]): Promise<void> {
  const dialog = page.getByRole('alertdialog');
  const listUrl = page.url();
  await clickDialogBackdrop(page);
  await expect(dialog).toBeVisible();
  await expect(page).toHaveURL(listUrl);
  await dialog.getByRole('button', { name: '취소' }).click();
  await expect(dialog).toBeHidden();
  await expect(page).toHaveURL(listUrl);
  expect(deleteRequests).toHaveLength(0);
}

/**
 * 대화상자가 열림 애니메이션(zoom-in-95)을 마친 뒤 세로로 뷰포트 안에 있는지 확인한다 (WD-1123).
 * 애니메이션 중에는 95% 로 줄어든 박스라 「뷰포트 안」 단언이 느슨해지므로 먼저 애니메이션이 끝나길 기다린다.
 * 높이는 page.viewportSize() 에서 읽어 시험마다 숫자를 되풀이하지 않는다.
 * @param page - 대상 페이지(뷰포트 크기를 읽는다)
 * @param dialog - 대화상자 locator
 */
export async function expectDialogWithinViewportHeight(page: Page, dialog: Locator): Promise<void> {
  await expect.poll(() => dialog.evaluate((el) => el.getAnimations().length)).toBe(0);
  const height = page.viewportSize()!.height;
  const box = await dialog.boundingBox();
  expect(box, '대화상자가 그려지지 않았다').not.toBeNull();
  expect(box!.y).toBeGreaterThanOrEqual(0);
  expect(box!.y + box!.height).toBeLessThanOrEqual(height);
}
