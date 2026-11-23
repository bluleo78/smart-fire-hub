import { expect, type Locator, type Page } from '@playwright/test';

/**
 * inert 확인 도우미(WD-606, WD-649) — 모달(모바일 서랍·AI 오버레이)이 열린 동안 뒤 화면이 막혔는지, 오버레이 안 입력창이 막히지 않았는지 여러
 * spec 이 같은 기준으로 본다.
 *
 * Playwright 의 역할 조회·toBeFocused 는 inert 를 거르지 않으므로, 요소 자신이나 조상에 inert 속성이 있는지 DOM 으로 직접 확인한다.
 */
export async function isUnderInert(target: Locator): Promise<boolean> {
  return target.evaluate((el) => !!el.closest('[inert]'));
}

/**
 * 오프라인 쪽 이동 안내가 흐림·inert 밖에서 노출되는지 확인한다(WD-982 code-review) —
 * 표가 aria-busy 가 아니고, 안내(role=status)가 보이며, 그 안내가 inert 조상 아래에 있지 않아 스크린리더에 들린다.
 */
export async function expectOfflineNoticeExposed(page: Page, tableName: string) {
  await expect(page.getByRole('table', { name: tableName })).not.toHaveAttribute('aria-busy', 'true');
  const notice = page.getByRole('status').filter({ hasText: '인터넷 연결이 없어' });
  await expect(notice).toBeVisible();
  expect(await isUnderInert(notice)).toBe(false);
}
