import { expect, type Locator, type Page } from '@playwright/test';

/**
 * 공용 Table 의 보이는 폭에 붙는 행(실패·빈 상태 — table-empty.tsx 의 TableVisibleWidthCell, WD-958·WD-1012)을
 * 재는 E2E 헬퍼. 두 목록 시험이 같은 단언을 쓰도록 한 곳에 둔다.
 */
export type Rect = { left: number; right: number; height: number };
export const rectOf = (l: Locator) =>
  l.evaluate((el): Rect => {
    const r = el.getBoundingClientRect();
    return { left: r.left, right: r.right, height: r.height };
  });
export const lineHeightOf = (l: Locator) => l.evaluate((el) => parseFloat(getComputedStyle(el).lineHeight));

export function expectInside(inner: Rect, box: Rect) {
  expect(inner.right).toBeLessThanOrEqual(box.right + 0.5);
  expect(inner.left).toBeGreaterThanOrEqual(box.left - 0.5);
}

/**
 * 문구·버튼이 보이는 스크롤 페이드에 덮이지 않는다 — 페이드 폭은 화면에서 왼쪽·오른쪽을 따로 잰다(WD-958 리뷰 후속).
 * table.tsx 의 페이드 클래스가 바뀌어도 시험이 따라간다. 375px 에서는 표가 넘쳐 한쪽 이상의 페이드가 늘 보인다(없으면 전제가 깨져 실패).
 */
export async function expectClearOfFade(page: Page, inner: Rect) {
  const left = page.locator('[data-slot="table-fade-left"]');
  const right = page.locator('[data-slot="table-fade-right"]');
  const [l, r] = await Promise.all([
    left.count().then((n) => (n ? rectOf(left) : null)),
    right.count().then((n) => (n ? rectOf(right) : null)),
  ]);
  expect(l ?? r).not.toBeNull();
  if (l) expect(inner.left).toBeGreaterThanOrEqual(l.right - 1); // 1px 여유
  if (r) expect(inner.right).toBeLessThanOrEqual(r.left + 1);
}
