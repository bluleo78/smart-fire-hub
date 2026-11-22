import type { Locator, Page } from '@playwright/test';

/**
 * 키보드 초점 이동 도우미 (WD-927)
 * 페이지에서 Tab 만 눌러 대상에 초점이 닿을 때까지 이동한다 — 바로 앞 요소를 가정하지 않아,
 * 대상 앞에 초점을 받는 요소(필터·안내 등)가 늘어도 키보드 경로가 있는 한 시험이 깨지지 않는다.
 * 끝내 닿지 못하면 예외로 실패한다(키보드로 닿을 수 없다는 뜻).
 * @param page - 대상 페이지
 * @param target - 초점이 닿아야 할 요소
 * @param maxTabs - 최대 Tab 횟수(사이드바 메뉴를 지나갈 여유)
 */
export async function tabUntilFocused(page: Page, target: Locator, maxTabs = 60): Promise<void> {
  for (let i = 0; i < maxTabs; i++) {
    await page.keyboard.press('Tab');
    if (await target.evaluate((el) => el === document.activeElement)) return;
  }
  throw new Error(`Tab ${maxTabs}번 안에 대상에 초점이 닿지 않았다`);
}
