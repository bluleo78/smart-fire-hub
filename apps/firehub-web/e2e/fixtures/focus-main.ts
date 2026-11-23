import { expect, type Page } from '@playwright/test';

/**
 * 건너뛰기 직후처럼 본문 main#main-content 에 포커스를 둔다 (WD-980).
 *
 * AppLayout 의 「본문으로 건너뛰기」와 같이 main 에 임시 tabindex=-1 을 붙인 뒤 focus() 하고,
 * 실제로 main 에 포커스가 갔는지 단언한다 — 포커스 잃음 판정 시험들이 같은 준비 단계를 공유한다.
 */
export async function focusMain(page: Page) {
  await page.locator('main#main-content').evaluate((el) => {
    el.setAttribute('tabindex', '-1');
    (el as HTMLElement).focus();
  });
  await expect(page.locator('main#main-content')).toBeFocused();
}
