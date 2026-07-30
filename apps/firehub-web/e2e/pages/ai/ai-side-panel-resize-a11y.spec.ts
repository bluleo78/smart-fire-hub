/**
 * AISidePanel 리사이즈 핸들 접근성 E2E 테스트 (이슈 #339)
 *
 * 결함: 좌측 리사이즈 핸들이 `onMouseDown`만 가진 순수 `<div>`라 role·tabIndex·aria-label이
 * 전무했다. 키보드 사용자는 폭을 조절할 수 없고 스크린리더는 존재조차 알 수 없었다.
 *
 * 회귀 방지 포인트:
 * 1. separator role + aria-orientation/label/valuenow/min/max가 실제 clamp 범위와 일치한다.
 * 2. Tab으로 도달 가능하고 ←/→/Home/End로 폭이 실제로 변한다(입력→처리→출력).
 * 3. 방향 규약: 오른쪽 고정 패널이라 드래그가 "왼쪽으로 끌면 넓어진다"(startWidth + startX - clientX).
 *    화살표도 이를 따라 ←가 폭 증가 / →가 폭 감소다(#332 NodeDetailDrawer와 동일).
 * 4. 접힌 패널에서는 부모 inert가 이 핸들까지 포커스 순서에서 제거한다(#333 회귀 방지).
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const MIN_WIDTH = 320;
const MAX_WIDTH = 600;
const DEFAULT_WIDTH = 380;
const KEY_STEP = 16;

test.describe('AISidePanel 리사이즈 핸들 접근성 (이슈 #339)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
  });

  test('핸들이 separator로 노출되고 키보드로 폭을 조절할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/', { waitUntil: 'commit' });

    // 패널을 연다 — 데스크탑 분기에서만 리사이즈 핸들이 렌더된다.
    await page.getByRole('button', { name: /AI 상태/ }).focus();
    await page.keyboard.press('Control+k');

    const panel = page.getByTestId('ai-side-panel');
    await expect(panel).not.toHaveAttribute('inert', /.*/);

    const handle = page.getByTestId('ai-side-panel-resize-handle');
    await expect(handle).toHaveAttribute('role', 'separator');
    await expect(handle).toHaveAttribute('aria-orientation', 'vertical');
    await expect(handle).toHaveAttribute('aria-label', 'AI 패널 폭 조절');
    await expect(handle).toHaveAttribute('aria-valuemin', String(MIN_WIDTH));
    await expect(handle).toHaveAttribute('aria-valuemax', String(MAX_WIDTH));
    await expect(handle).toHaveAttribute('aria-valuenow', String(DEFAULT_WIDTH));

    // 접근성 트리에 실제로 이름을 가진 separator로 잡히는지 확인한다.
    await expect(page.getByRole('separator', { name: 'AI 패널 폭 조절' })).toBeVisible();

    // 키보드로 도달 가능해야 한다(수정 전에는 tabIndex가 없어 focus 대상이 아니었다).
    await handle.focus();
    await expect(handle).toBeFocused();

    // 패널에 `transition-[width] 200ms`가 걸려 있어 실제 렌더 폭은 한 박자 늦게 따라온다 — poll로 본다.
    const expectWidth = async (px: number) =>
      expect
        .poll(() => panel.evaluate((el) => Math.round(el.getBoundingClientRect().width)))
        .toBe(px);

    await expectWidth(DEFAULT_WIDTH);

    // ← 는 폭 증가(드래그 방향과 일치).
    await page.keyboard.press('ArrowLeft');
    await expect(handle).toHaveAttribute('aria-valuenow', String(DEFAULT_WIDTH + KEY_STEP));
    await expectWidth(DEFAULT_WIDTH + KEY_STEP);

    // → 는 폭 감소.
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('ArrowRight');
    await expect(handle).toHaveAttribute('aria-valuenow', String(DEFAULT_WIDTH - KEY_STEP));
    await expectWidth(DEFAULT_WIDTH - KEY_STEP);

    // Home/End는 노출한 aria-valuemin/max로 즉시 이동하고 clamp를 넘지 않는다.
    await page.keyboard.press('End');
    await expect(handle).toHaveAttribute('aria-valuenow', String(MAX_WIDTH));
    await page.keyboard.press('ArrowLeft'); // 최대에서 더 넓히려 해도 clamp
    await expect(handle).toHaveAttribute('aria-valuenow', String(MAX_WIDTH));

    await page.keyboard.press('Home');
    await expect(handle).toHaveAttribute('aria-valuenow', String(MIN_WIDTH));
    await page.keyboard.press('ArrowRight'); // 최소에서 더 좁히려 해도 clamp
    await expect(handle).toHaveAttribute('aria-valuenow', String(MIN_WIDTH));
    await expectWidth(MIN_WIDTH);
  });

  test('접힌 패널에서는 새 핸들도 포커스 순서에 남지 않는다 (#333 회귀 방지)', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/', { waitUntil: 'commit' });

    const panel = page.getByTestId('ai-side-panel');
    await expect(panel).toHaveAttribute('inert', /.*/);

    // 프로그래매틱 focus()도 inert 서브트리에서는 no-op이어야 한다.
    const landed = await panel.evaluate((el) => {
      el.querySelector<HTMLElement>('[data-testid="ai-side-panel-resize-handle"]')?.focus();
      return el.contains(document.activeElement);
    });
    expect(landed).toBe(false);
  });
});
