import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * WD-913 — SelectTrigger className 높이(size 무시)
 *
 * 무엇: 화면에 실제로 그려진 SelectTrigger 의 높이(getBoundingClientRect)를 재는 측정 회귀 시험이다.
 * 왜:   공용 select.tsx 는 size 생략 시 `data-[size=default]:h-9` 를 속성 선택자로 붙이고,
 *       속성 선택자는 호출부 className 의 h-7·h-8 보다 특이도 가 높아 높이를 무시한다
 *       (tailwind-merge 도 지우지 못한다) — 그래서 수정 전 36px 로 그려졌다.
 *       높이는 `size="sm"`(32px)으로만 정하며, 이 시험은 그 32px 이 실제로 칠해지는지 화면에서 재고,
 *       src/lib/select-trigger-height-guard.test.ts 의 정적 스캔과 짝을 이뤄 되풀이를 막는다.
 */

/**
 * 32px 허용 오차(±0.5px) — 브라우저가 렌더링한 높이는 서브픽셀이라 31.95 처럼 소수로 나올 수 있다.
 * 왜:   정확히 `=== 32` 로 비교하면 애니메이션·서브픽셀 반올림만으로 흔들려(주석에도 31.95 가 나온다)
 *       올바른 화면에서도 실패한다 — 리뷰 후속(앞 PR #1173)에서 ±0.5 로 완화했다.
 */
const HEIGHT_TOLERANCE = 0.5;

/** 그려진 높이가 32px 과 허용 오차 안에 있는지 본다. */
function is32(height: number): boolean {
  return Math.abs(height - 32) <= HEIGHT_TOLERANCE;
}

/**
 * 선택 상자의 높이들을 재어 32px 다는 것을 단언한다.
 * @param heights - getBoundingClientRect().height 배열 — 최소 1개는 있어야 한다(빈 배열로 통과하는 공허한 단언 방지).
 */
function expectAll32(heights: number[]) {
  expect(heights.length).toBeGreaterThanOrEqual(1);
  expect(
    heights.every(is32),
    `SelectTrigger 높이가 전부 32px 가 아니다: ${JSON.stringify(heights)}`
  ).toBe(true);
}

test('데이터셋 목록의 페이지 사이즈 선택 상자가 32px 다 (WD-913)', async ({ authenticatedPage: page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await setupAdminAuth(page);
  await page.goto('/data/datasets');

  const pageSizeSelect = page.getByRole('combobox', { name: '페이지 사이즈' });
  // 목록 응답이 도착해야 페이지네이션이 그려진다 — 존재를 먼저 기다려 evaluateAll 이 빈 배열로 통과하지 않게 한다.
  await expect(pageSizeSelect).toHaveCount(1);

  const heights = await pageSizeSelect.evaluateAll((els) =>
    els.map((el) => el.getBoundingClientRect().height)
  );
  expectAll32(heights);
});

test('헤더 사용자 메뉴의 테마 선택 상자가 32px 다 (WD-913)', async ({ authenticatedPage: page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await setupAdminAuth(page);
  await page.goto('/data/datasets');

  // 사용자 메뉴를 열고 — 테마 모드 그룹이 보일 때 드롭다운이 뜬 것이다.
  await page.getByRole('button', { name: /admin@example\.com/ }).click();
  await expect(page.getByRole('group', { name: '테마 모드' })).toBeVisible();

  // 드롭다운 안의 테마 선택 상자 — 초기 내용이 Indigo 라는 것으로 페이지 사이즈 콤보박스와 구분한다.
  const themeSelect = page.getByRole('combobox').filter({ hasText: 'Indigo' });
  await expect(themeSelect).toHaveCount(1);

  const readHeights = () =>
    themeSelect.evaluateAll((els) => els.map((el) => el.getBoundingClientRect().height));

  // 드롭다운은 열릴 때 scale 애니메이션이라 도중에는 사각형이 잠깐 32px 보다 작다(예: 31.95) —
  // 애니메이션이 끝나 허용 오차 안에 들어올 때까지 기다린 뒤 확정 단언한다.
  // (수정 전에는 애니메이션이 끝나도 36 이라 이 대기가 타임아웃으로 실패한다.)
  // 임계값도 위 허용 오차를 쓴다 — 정확 일치(=== 32) 기다림은 서브픽셀 차이로 타임아웃이 난다(리뷰 후속).
  try {
    await expect
      .poll(async () => {
        const settled = await readHeights();
        return settled.length >= 1 && settled.every(is32);
      })
      .toBe(true);
  } catch {
    // 기다림이 끝나도 못 맞으면 확정 단언이 재실측한 높이 배열을 실패 메시지에 남긴다 —
    // 기다림 실패만 뜨면 Expected: true 뿐이라 원인 추정을 다시 재보게 된다(리뷰 후속 지적).
    expectAll32(await readHeights());
  }

  expectAll32(await readHeights());
});