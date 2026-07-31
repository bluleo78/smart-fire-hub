import type { Page } from '@playwright/test';

import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 차트 색 토큰 해석 회귀 테스트 (#374)
 *
 * 배경: 차트가 색 토큰을 `hsl(var(--X))`로 감싸 쓰고 있었는데, 이 앱 토큰은
 * `oklch()` **완결형**이라 `hsl(oklch(...))`가 무효 CSS가 되어 선언이 통째로 드롭됐다.
 * → 축 눈금 `fill`이 initial `rgb(0,0,0)`(다크 대비 1.06:1), 격자 `stroke`가 initial `none`,
 *   툴팁 배경·보더 소실.
 *
 * 그래서 이 테스트는 "요소가 보이는가"가 아니라 **계산된 색이 의도한 토큰과 일치하는가**를
 * 검증한다. 리터럴 rgb를 하드코딩하지 않고 토큰 자체를 읽어 비교하므로
 * 팔레트가 바뀌어도(#375) 깨지지 않는다.
 */

/** 6개 컬럼/행 — 카테고리+숫자 2컬럼으로 꺾은선/막대 어느 쪽이든 축·격자가 그려지도록 */
const queryResult = createQueryResult({
  columns: ['category', 'amount'],
  rows: [
    { category: 'A', amount: 10 },
    { category: 'B', amount: 20 },
    { category: 'C', amount: 30 },
    { category: 'D', amount: 40 },
    { category: 'E', amount: 50 },
    { category: 'F', amount: 60 },
  ],
  totalRows: 6,
});

/** 쿼리 실행까지 진행해 미리보기 차트를 렌더시킨다 */
async function renderPreviewChart(page: Page) {
  await setupNewChartBuilderMocks(page);
  await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

  await page.goto('/analytics/charts/new');
  await page.getByRole('combobox').click();
  await page.getByRole('option', { name: '저장 쿼리 1' }).click();
  await page.getByRole('button', { name: '쿼리 실행' }).click();

  await expect(page.locator('svg.recharts-surface').first()).toBeVisible();
}

/**
 * 테마 클래스를 교체한다. 차트 색은 순수 CSS 커스텀 프로퍼티로 해석되므로
 * 클래스 교체만으로 충분하다(React 상태 경유 불필요).
 * next-themes가 `light`/`dark`를 함께 쓰므로 둘 다 정리한다.
 */
async function setTheme(page: Page, theme: string, mode: 'light' | 'dark') {
  await page.evaluate(
    ([t, m]) => {
      const r = document.documentElement;
      r.classList.remove('theme-indigo', 'theme-ocean', 'theme-sunset', 'light', 'dark');
      r.classList.add(`theme-${t}`, m);
    },
    [theme, mode] as const
  );
}

const THEMES = ['indigo', 'ocean', 'sunset'] as const;
const MODES = ['light', 'dark'] as const;

test.describe('차트 색 토큰 해석 (#374)', () => {
  for (const theme of THEMES) {
    for (const mode of MODES) {
      test(`${theme}/${mode} — 축 눈금·격자 색이 토큰으로 해석된다`, async ({
        authenticatedPage: page,
      }) => {
        await renderPreviewChart(page);
        await setTheme(page, theme, mode);

        // 테마가 실제로 걸렸는지 먼저 단언한다. 이게 없으면 6개 테스트가 전부
        // 같은 테마를 재측정하고도 통과해(공허한 6조합 커버리지) 함정 A를 놓친다.
        const rootClass = await page.evaluate(() => document.documentElement.className);
        expect(rootClass).toContain(`theme-${theme}`);
        expect(rootClass).toContain(mode);

        const measured = await page.evaluate(() => {
          const root = getComputedStyle(document.documentElement);
          const tick = document.querySelector('.recharts-cartesian-axis-tick-value');
          const grid = document.querySelector('.recharts-cartesian-grid line');
          return {
            tickFill: tick ? getComputedStyle(tick).fill : null,
            gridStroke: grid ? getComputedStyle(grid).stroke : null,
            tokenMutedFg: root.getPropertyValue('--muted-foreground').trim(),
            tokenBorder: root.getPropertyValue('--border').trim(),
          };
        });

        // 축 눈금: initial 검정으로 떨어지지 않고 --muted-foreground 토큰과 일치
        expect(measured.tickFill).not.toBeNull();
        expect(measured.tickFill).not.toBe('rgb(0, 0, 0)');
        expect(measured.tickFill).toBe(measured.tokenMutedFg);

        // 격자선: initial `none`으로 떨어지지 않고 --border 토큰과 일치
        expect(measured.gridStroke).not.toBeNull();
        expect(measured.gridStroke).not.toBe('none');
        // 브라우저는 `oklch(1 0 0 / 10%)`를 `oklch(1 0 0 / 0.1)`로 정규화한다
        expect(normalizeAlpha(measured.gridStroke!)).toBe(normalizeAlpha(measured.tokenBorder));
      });
    }
  }

  test('격자 표시 토글이 격자선을 실제로 켜고 끈다', async ({ authenticatedPage: page }) => {
    await renderPreviewChart(page);

    const gridLines = page.locator('.recharts-cartesian-grid line');
    const toggle = page.locator('#opt-grid');

    // 격자선 `<line>`은 두께가 0인 도형이라 Playwright의 toBeVisible이 항상 hidden으로 본다.
    // 그래서 "존재 개수 + 해석된 stroke"로 가시성을 판정한다.
    const countOn = await gridLines.count();
    expect(countOn).toBeGreaterThan(0);

    // 초기 상태: 켜짐 — stroke가 실제 색으로 해석된다.
    // (#374 이전에는 선은 있지만 stroke가 `none`이라 토글이 무동작처럼 보였다.)
    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    const strokeOn = await gridLines
      .first()
      .evaluate((el) => getComputedStyle(el as Element).stroke);
    expect(strokeOn).not.toBe('none');

    // 끄기 → 격자선이 DOM에서 사라진다
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-checked', 'false');
    await expect(gridLines).toHaveCount(0);

    // 다시 켜기 → 복귀
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    await expect(gridLines).toHaveCount(countOn);
  });

  test('툴팁에 배경·보더가 렌더된다', async ({ authenticatedPage: page }) => {
    await renderPreviewChart(page);

    // 툴팁은 데이터 마크 위에서만 활성화된다 — surface 중앙은 빈 공간일 수 있다.
    await page.locator('.recharts-bar-rectangle, .recharts-dot').first().hover();

    const tooltip = page.locator('.recharts-default-tooltip').first();
    await expect(tooltip).toBeVisible();

    const style = await tooltip.evaluate((el) => {
      const cs = getComputedStyle(el as Element);
      const root = getComputedStyle(document.documentElement);
      return {
        background: cs.backgroundColor,
        borderWidth: cs.borderTopWidth,
        borderStyle: cs.borderTopStyle,
        color: cs.color,
        tokenPopover: root.getPropertyValue('--popover').trim(),
        tokenPopoverFg: root.getPropertyValue('--popover-foreground').trim(),
      };
    });

    // 배경이 투명으로 드롭되지 않고 --popover 토큰으로 해석된다
    expect(style.background).not.toBe('rgba(0, 0, 0, 0)');
    expect(style.background).toBe(style.tokenPopover);
    // 보더 shorthand가 통째로 드롭되지 않았다
    expect(style.borderWidth).toBe('1px');
    expect(style.borderStyle).toBe('solid');
    // 본문 색도 토큰으로 해석된다
    expect(style.color).toBe(style.tokenPopoverFg);
  });
});

/** `oklch(1 0 0 / 10%)` ↔ `oklch(1 0 0 / 0.1)` 표기 차이를 흡수 */
function normalizeAlpha(color: string): string {
  return color.replace(/\/\s*([\d.]+)%/g, (_, p) => `/ ${Number(p) / 100}`).replace(/\s+/g, ' ');
}
