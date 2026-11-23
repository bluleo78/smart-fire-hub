import type { Locator } from '@playwright/test';

import { expect } from './auth.fixture';

/** 디자인 시스템 최소 클릭 영역(px) — 높이·너비 둘 다 */
const MIN_HIT = 32;

/**
 * 영역 안의 휴지통 버튼(lucide trash 아이콘을 가진 버튼)이 모두 32×32 이상인지 확인한다(WD-1023).
 * 휴지통은 아이콘만 있는 버튼이라 이름 대신 아이콘으로 좁혀 찾는다.
 *
 * @param scope - 대상 휴지통이 들어 있는 줄·패널·대화상자
 * @param expectedCount - 알면 개수를 못박는다 — 한 줄의 휴지통이 사라져도 다른 줄만으로 통과하지 않게
 */
export async function expectTrashHitTarget(scope: Locator, expectedCount?: number) {
  const buttons = scope
    .getByRole('button')
    .filter({ has: scope.page().locator('svg.lucide-trash-2, svg.lucide-trash2') })
    .filter({ visible: true });
  await expect(buttons.first()).toBeVisible();
  if (expectedCount !== undefined) await expect(buttons).toHaveCount(expectedCount);
  const sizes = await buttons.evaluateAll((els) =>
    els.map((el) => {
      const rect = el.getBoundingClientRect();
      return { width: rect.width, height: rect.height };
    }),
  );
  // `first()` 대기와 이 재해석 사이에 DOM 이 바뀌어 빈 배열이 되면 그대로 지나가지 않게 한다(0건 공허 통과 방지)
  expect(sizes.length, '휴지통 측정 결과 0건').toBeGreaterThan(0);
  for (const { width, height } of sizes) {
    expect(height, `휴지통 높이 ${height}px < ${MIN_HIT}px`).toBeGreaterThanOrEqual(MIN_HIT);
    expect(width, `휴지통 너비 ${width}px < ${MIN_HIT}px`).toBeGreaterThanOrEqual(MIN_HIT);
  }
}
