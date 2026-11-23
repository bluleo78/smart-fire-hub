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
  await expectFadesSettled(page); // 측정 전에 늘 페이드가 안정되게 한다 (WD-1017)
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

/**
 * 표가 실제로 넘치는 만큼의 페이드가 뜰 때까지 기다린다 — useHorizontalOverflow 갱신 전에 페이드 개수를 읽어
 * 겹침 검사가 「아직 없어서 건너뜀」으로 헛통과하지 않게 한다 (WD-1017).
 * 넘침 전제는 expectClearOfFade 가 페이드 존재로 확인한다(데스크톱처럼 안 넘치면 0개로 안정). 왼쪽은 scrollLeft>0, 오른쪽은 끝까지 안 밀렸을 때 보인다.
 */
export async function expectFadesSettled(page: Page) {
  const box = page.locator('[data-slot="table-container"]');
  const st = await box.evaluate((el) => ({
    left: el.scrollLeft > 0.5,
    right: el.scrollLeft + el.clientWidth < el.scrollWidth - 1,
  }));
  await expect(page.locator('[data-slot="table-fade-left"]')).toHaveCount(st.left ? 1 : 0);
  await expect(page.locator('[data-slot="table-fade-right"]')).toHaveCount(st.right ? 1 : 0);
}

/**
 * 보이는 폭 내용 상자(table-visible-content)의 안쪽 여백이 양쪽 모두 40px 과 보이는 페이드의 실제 폭 중 큰 값 이상이다 (WD-1017).
 * 여백은 내용 상자의 padding 으로 둔다는 구현을 전제로 한다.
 * 페이드 유무와 무관하게 computed padding 을 직접 읽어, 페이드 겹침 검사가 놓치는 px-10 → pl-0 같은 한쪽 여백 소실을 잡는다.
 */
export async function expectContentInset(page: Page) {
  await expectFadesSettled(page); // 측정 전에 늘 페이드가 안정되게 한다
  let min = 40;
  for (const side of ['left', 'right']) {
    const fade = page.locator(`[data-slot="table-fade-${side}"]`);
    if (await fade.count()) min = Math.max(min, await fade.evaluate((el) => el.getBoundingClientRect().width));
  }
  const pad = await page.locator('[data-slot="table-visible-content"]').evaluate((el) => {
    const cs = getComputedStyle(el);
    return { left: parseFloat(cs.paddingLeft), right: parseFloat(cs.paddingRight) };
  });
  expect(pad.left).toBeGreaterThanOrEqual(min);
  expect(pad.right).toBeGreaterThanOrEqual(min);
}
