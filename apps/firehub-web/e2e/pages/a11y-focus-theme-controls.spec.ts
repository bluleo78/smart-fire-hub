import { expect, test } from '../fixtures/auth.fixture';

/**
 * 접근성 회귀 가드 — 포커스 링 불투명도(#368)와 테마 모드 버튼의 이름·선택 상태(#369).
 *
 * - #368 (WCAG SC 1.4.11): 포커스 링에 알파 수식자(`ring-ring/50`)가 붙어 있어
 *   실효 대비가 2.0:1까지 떨어졌다. 대비 수치를 E2E에서 계산하기보다,
 *   "링 색에 알파가 다시 들어오면 실패"하는 형태가 회귀를 정확히 잡는다.
 *   (토큰 값 자체의 대비는 src/styles/design-tokens.contrast.test.ts가 담당)
 * - #369 (WCAG SC 4.1.2): 사용자 메뉴의 테마 모드 버튼 3개에 접근 가능한 이름이 없고
 *   현재 선택된 모드도 노출되지 않았다.
 */

/**
 * 색 문자열이 반투명한지 판정한다.
 * - 계산된 색: `rgba(r,g,b,a)` / `oklab(l a b / a)`
 * - 커스텀 프로퍼티 원문: Tailwind v4의 알파 수식자는 `color-mix(in oklab, <색> 50%, transparent)`로
 *   남으므로(`--tw-ring-color`는 `@property syntax:'*'`라 해석되지 않는다) 이 형태도 잡아야 한다.
 */
function isTranslucent(color: string): boolean {
  if (/color-mix\(.*transparent/.test(color)) return true;
  const m = color.match(/rgba?\([^)]*?,\s*([\d.]+)\s*\)/) ?? color.match(/\/\s*([\d.]+)\s*\)/);
  return m ? Number(m[1]) < 1 : false;
}

/**
 * 포커스된 상태에서 Tailwind가 실제로 해석한 링 색·두께를 읽는다.
 * `--tw-ring-color`는 `focus-visible:ring-*` 규칙이 걸릴 때만 세팅되므로,
 * 이 값이 곧 "화면에 그려질 링의 색"이다. 알파 수식자가 다시 붙으면 여기에 알파가 나타난다.
 */
async function focusRing(page: import('@playwright/test').Page, selector: string) {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const cs = getComputedStyle(el);
    return {
      focusVisible: el.matches(':focus-visible'),
      color: cs.getPropertyValue('--tw-ring-color').trim(),
      shadow: cs.getPropertyValue('--tw-ring-shadow').trim(),
    };
  }, selector);
}

test.describe('포커스 링 불투명도 (#368)', () => {
  test('포커스 링 색이 반투명하지 않다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets');

    // 텍스트 입력은 마우스 포커스에도 :focus-visible이 걸리므로 결정적으로 링 규칙이 적용된다.
    const search = page.getByPlaceholder('데이터셋 검색...');
    await expect(search).toBeVisible();
    await search.click();

    const ring = await focusRing(page, 'input[placeholder="데이터셋 검색..."]');
    expect(ring, '검색 입력을 찾지 못했다').not.toBeNull();
    expect(ring!.focusVisible, ':focus-visible이 걸리지 않아 링 규칙이 적용되지 않았다').toBe(true);
    // 링이 실제로 두께를 갖는지(=규칙이 살아 있는지) 먼저 확인한다 — 공허한 통과 방지.
    expect(ring!.shadow, `링 두께가 0이다: ${ring!.shadow}`).toMatch(/[1-9][\d.]*px/);
    expect(
      isTranslucent(ring!.color),
      `포커스 링 색에 알파가 남아 있다: ${ring!.color}`
    ).toBe(false);
  });

  test('전역 outline 유틸에 알파 수식자가 없다', async ({ authenticatedPage: page }) => {
    await page.goto('/data/datasets');
    // `* { outline-color: ... }` 전역 기본값 — 알파가 붙어 있으면 `oklab(... / 0.5)` 형태로 계산된다.
    const outline = await page.evaluate(() => getComputedStyle(document.body).outlineColor);
    expect(isTranslucent(outline), `전역 outline 색에 알파가 붙어 있다: ${outline}`).toBe(false);
  });
});

test.describe('테마 모드 버튼 접근성 (#369)', () => {
  /** 사용자 메뉴(좌하단 사용자 카드)를 연다. */
  async function openUserMenu(page: import('@playwright/test').Page) {
    await page.goto('/data/datasets');
    await page.getByRole('button', { name: /테스트 사용자/ }).first().click();
    await expect(page.getByRole('group', { name: '테마 모드' })).toBeVisible();
  }

  test('세 버튼에 접근 가능한 이름이 있다', async ({ authenticatedPage: page }) => {
    await openUserMenu(page);

    for (const name of ['라이트 모드', '다크 모드', '시스템 설정 따르기']) {
      await expect(page.getByRole('button', { name, exact: true })).toBeVisible();
    }

    // 이름 없는 버튼이 메뉴에 남아 있지 않은지 함께 확인한다.
    const unnamed = await page.evaluate(
      () =>
        [...document.querySelectorAll('[role=menu] button')].filter(
          (b) =>
            !(b.getAttribute('aria-label') || b.getAttribute('title') || b.textContent || '').trim()
        ).length
    );
    expect(unnamed).toBe(0);
  });

  test('현재 선택된 모드가 aria-pressed로 노출되고 클릭 시 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await openUserMenu(page);

    const light = page.getByRole('button', { name: '라이트 모드', exact: true });
    const dark = page.getByRole('button', { name: '다크 모드', exact: true });
    const system = page.getByRole('button', { name: '시스템 설정 따르기', exact: true });

    // 정확히 하나만 선택 상태여야 한다.
    const pressedCount = async () =>
      (await Promise.all([light, dark, system].map((b) => b.getAttribute('aria-pressed')))).filter(
        (v) => v === 'true'
      ).length;
    expect(await pressedCount()).toBe(1);

    await dark.click();
    await expect(dark).toHaveAttribute('aria-pressed', 'true');
    await expect(light).toHaveAttribute('aria-pressed', 'false');
    await expect(system).toHaveAttribute('aria-pressed', 'false');

    // resolvedTheme이 아니라 '설정'을 반영해야 하므로, 시스템 선택 시 시스템만 눌린 상태가 된다.
    await system.click();
    await expect(system).toHaveAttribute('aria-pressed', 'true');
    await expect(dark).toHaveAttribute('aria-pressed', 'false');
    await expect(light).toHaveAttribute('aria-pressed', 'false');
  });
});
