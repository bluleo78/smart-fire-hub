import { expect, type Locator } from '@playwright/test';

/**
 * 공백 없는 긴 이름의 줄바꿈·넘침 검증 헬퍼 (WD-632, WD-648)
 * - long-name-overflow.spec.ts(확인 대화상자·알림·작업 개요)와 dialog-long-name-overflow.spec.ts(일반 대화상자)가 함께 쓴다.
 */

/**
 * 요소 안에서 가로로 넘친 내용이 없다 — 긴 낱말이 요소 폭 안에서 줄바꿈되었다.
 * 블록 요소의 오른쪽 끝은 내용이 넘쳐도 부모 폭에 머물러 끝 위치만 보면 넘침을 놓치므로, 요소 자체의 scrollWidth 로 잰다.
 * @param target - 긴 이름을 그리는 글자 요소 또는 대화상자
 */
export async function expectWrapped(target: Locator) {
  const { scrollWidth, clientWidth } = await target.evaluate((el) => ({
    scrollWidth: el.scrollWidth,
    clientWidth: el.clientWidth,
  }));
  expect(clientWidth).toBeGreaterThan(0);
  expect(scrollWidth).toBeLessThanOrEqual(clientWidth);
}

/**
 * 대화상자가 넘치지 않고 뷰포트 가로 안에 있으며, 확인(하단) 버튼이 화면 안에 다 보인다.
 * @param dialog - 대화상자(role=dialog / alertdialog)
 * @param confirmButton - 화면 밖으로 밀리면 안 되는 하단 버튼(삭제·복제 등)
 */
export async function expectDialogContained(dialog: Locator, confirmButton: Locator) {
  await expectWrapped(dialog);
  const box = await dialog.boundingBox();
  const viewportWidth = dialog.page().viewportSize()?.width ?? 0;
  expect(box).not.toBeNull();
  expect(viewportWidth).toBeGreaterThan(0);
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewportWidth);
  await expect(confirmButton).toBeInViewport({ ratio: 1 });
}

/**
 * 요소의 글자가 한 줄로 그려졌다 — 글자 범위(Range)의 줄 상자들이 모두 같은 줄(윗변)에 있다.
 * 요소 상자 높이로 재지 않는 이유: flex 항목은 늘어나(stretch) 옆 항목 높이를 따라가므로 한 줄이어도 높아진다.
 * @param target - 끊기면 안 되는 짧은 식별자(컬럼명·데이터 타입·이메일 등)를 그리는 요소
 */
export async function expectSingleLine(target: Locator) {
  const lineTops = await target.evaluate((el) => {
    const range = document.createRange();
    range.selectNodeContents(el);
    const tops = Array.from(range.getClientRects())
      .filter((r) => r.width > 0)
      .map((r) => Math.round(r.top));
    return Array.from(new Set(tops));
  });
  expect(lineTops).toHaveLength(1);
}

/**
 * 안쪽 요소의 가로 범위가 바깥 요소 안에 있다 — 긴 이름이 칸·패널·창 밖으로 뻗지 않았다.
 * @param inner - 긴 이름을 그리는 요소
 * @param outer - inner 를 담아야 하는 요소(대화상자·패널 등)
 */
export async function expectWithin(inner: Locator, outer: Locator) {
  const innerBox = await inner.boundingBox();
  const outerBox = await outer.boundingBox();
  expect(innerBox, '안쪽 요소가 화면에 그려지지 않았다').not.toBeNull();
  expect(outerBox, '바깥 요소가 화면에 그려지지 않았다').not.toBeNull();
  expect(innerBox!.x, '안쪽 요소 왼쪽 끝이 바깥 요소 밖').toBeGreaterThanOrEqual(outerBox!.x);
  expect(innerBox!.x + innerBox!.width, '안쪽 요소 오른쪽 끝이 바깥 요소 밖').toBeLessThanOrEqual(
    outerBox!.x + outerBox!.width,
  );
}
