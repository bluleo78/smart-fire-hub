/**
 * AISidePanel 접힘 상태 접근성 E2E 테스트 (이슈 #333)
 *
 * 결함: 데스크탑 사이드 패널이 닫힌 상태에서 `w-0 overflow-hidden`으로만 숨겨져
 * 내부 컨트롤 9개가 탭 순서에 그대로 남았다. AISidePanel은 AppLayout에 상주하므로
 * **모든 라우트**에 보이지 않는 탭 스톱이 생겼다(#327 TypeFilterPanel과 동일한 결함 패턴).
 *
 * 회귀 방지 포인트:
 * 1. 닫히면 `inert`가 붙고 열리면 사라진다(aria-hidden 중복 지정 금지 — inert가 함의).
 * 2. 닫힌 패널 내부로는 프로그래매틱 포커스도 Tab도 들어가지 않는다.
 * 3. 패널 안에 포커스를 둔 채 닫으면 포커스가 <body>로 유실되지 않고 여는 트리거로 복귀한다
 *    (#328과 같은 포커스 유실 결함을 inert 도입으로 새로 만들지 않기 위함).
 *
 * 모바일(lg 미만) 분기는 닫힘 상태에서 `null`을 반환해 포커스 대상이 아예 없으므로 대상 외다.
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** AIStatusChip — 패널을 여는 트리거(패널 바깥) */
const chipLocator = (page: import('@playwright/test').Page) =>
  page.getByRole('button', { name: /AI 상태/ });

test.describe('AISidePanel — 접힌 패널 inert (이슈 #333)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
  });

  test('닫힌 패널은 inert로 포커스 순서에서 제거되고, 열면 다시 조작 가능해진다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/', { waitUntil: 'commit' });

    const panel = page.getByTestId('ai-side-panel');
    await expect(panel).toBeAttached();

    // 기본(닫힘) 상태 — 폭 0이지만 자식 컨트롤은 DOM에 살아 있다. inert로 차단되어야 한다.
    await expect(panel).toHaveAttribute('inert', /.*/);
    // aria-hidden은 붙이지 않는다(inert가 접근성 트리 제거를 함의 — 중복 지정은 경고 대상).
    await expect(panel).not.toHaveAttribute('aria-hidden', /.*/);
    expect(await panel.evaluate((el) => el.getBoundingClientRect().width)).toBe(0);

    // 내부 컨트롤이 실제로 남아 있는지 먼저 확인 — 0개면 이 테스트가 아무것도 검증하지 못한다.
    const innerFocusables = await panel.evaluate(
      (el) =>
        el.querySelectorAll('button,a[href],input,select,textarea,[tabindex]:not([tabindex="-1"])').length,
    );
    expect(innerFocusables).toBeGreaterThan(0);

    // 프로그래매틱 focus()도 inert 요소에서는 no-op이어야 한다.
    const focusLanded = await panel.evaluate((el) => {
      el.querySelector<HTMLElement>('textarea, button')?.focus();
      return el.contains(document.activeElement);
    });
    expect(focusLanded).toBe(false);

    // Tab 순서: 패널 직전(문서 순서)의 마지막 컨트롤에서 Tab을 눌러도 패널 안으로 들어가지 않는다.
    await panel.evaluate((el) => {
      const sel = 'button:not([disabled]),a[href],input:not([disabled]),select,textarea,[tabindex]:not([tabindex="-1"])';
      const preceding = [...document.querySelectorAll<HTMLElement>(sel)].filter(
        (e) => !el.contains(e) && !!(el.compareDocumentPosition(e) & Node.DOCUMENT_POSITION_PRECEDING),
      );
      preceding[preceding.length - 1]?.focus();
    });
    await page.keyboard.press('Tab');
    expect(
      await page.evaluate(() => !!document.activeElement?.closest('[data-testid="ai-side-panel"]')),
    ).toBe(false);

    // 열면 inert가 사라지고 내부 입력창에 키보드로 값을 넣을 수 있다.
    await chipLocator(page).click();
    await expect(panel).not.toHaveAttribute('inert', /.*/);
    const input = panel.getByPlaceholder('메시지를 입력하세요...');
    await input.focus();
    // ASCII로 입력한다 — 한글은 조합 입력이라 keyboard.type의 순서 보장이 깨진다(검증 대상 아님).
    await page.keyboard.type('keyboard ok');
    await expect(input).toHaveValue('keyboard ok');
  });

  test('패널 안에 포커스를 둔 채 닫으면 포커스가 여는 트리거로 복귀한다(body 유실 방지)', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/', { waitUntil: 'commit' });

    const panel = page.getByTestId('ai-side-panel');
    const chip = chipLocator(page);

    // 칩에 포커스를 둔 상태에서 단축키로 연다 — 이때의 포커스 위치가 복귀 지점으로 기억된다.
    // (칩 클릭은 호버 드롭다운을 함께 열어 포커스 주체가 모호해지므로 쓰지 않는다.)
    await chip.focus();
    await expect(chip).toBeFocused();
    await page.keyboard.press('Control+k');
    await expect(panel).not.toHaveAttribute('inert', /.*/);

    // 패널 내부 입력창에 포커스를 둔 뒤 단축키로 닫는다(닫기 버튼과 동일 경로 — closeAI).
    const input = panel.getByPlaceholder('메시지를 입력하세요...');
    await input.focus();
    await expect(input).toBeFocused();
    await page.keyboard.press('Control+k');

    await expect(panel).toHaveAttribute('inert', /.*/);
    // inert는 내부 포커스를 <body>로 떨어뜨린다 — 복귀 보정이 없으면 여기서 body가 된다.
    await expect(chip).toBeFocused();
  });
});
