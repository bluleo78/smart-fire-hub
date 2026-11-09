/**
 * 모바일 AI 오버레이 — 서랍과의 겹침·Esc 닫기 E2E (WD-649, WD-606 후속)
 *
 * 결함:
 * 1. 모바일(1024px 미만)에서 메뉴 서랍을 연 채 Ctrl/Cmd+K 로 AI 오버레이를 열면, 서랍이 본문 열을 inert 로 막고
 *    오버레이가 서랍(aside)을 inert 로 막아 본문 열 안에 있는 오버레이 입력창까지 inert 가 되어 입력할 수 없었다.
 * 2. 화면을 덮는 모바일 오버레이(role=dialog·aria-modal)가 Esc 로 닫히지 않았다.
 *
 * 회귀 방지 포인트:
 * - 서랍 → Ctrl+K: 서랍이 닫히고 오버레이 입력창에 포커스·타이핑이 되며, 입력창의 어떤 조상도 inert 가 아니다.
 * - Esc 로 오버레이가 닫히고 포커스가 연 트리거(AI 칩)로 돌아온다. 서랍에서 열었으면 「메뉴 열기」 버튼으로 돌아온다.
 * - IME 조합 중 Esc·하위 팝오버(대화 선택 메뉴)가 먼저 쓴 Esc 로는 오버레이를 닫지 않는다.
 * - 데스크탑 사이드 패널은 Esc 로 닫히지 않는다(기존 동작 그대로).
 */

import type { Page } from '@playwright/test';

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { isUnderInert } from '../../fixtures/inert-a11y';

/** AIStatusChip — 패널을 여는 트리거(패널 바깥) */
const chipLocator = (page: Page) => page.getByRole('button', { name: /AI 상태/ });
/** 모바일 오버레이(모달 대화상자, WD-606) */
const overlayLocator = (page: Page) => page.getByRole('dialog', { name: 'AI 어시스턴트' });
/** 오버레이 안 채팅 입력창 */
const overlayInput = (page: Page) => overlayLocator(page).getByPlaceholder('메시지를 입력하세요...');

/** 대화 선택 메뉴(하위 팝오버)를 띄우기 위한 세션 목록 — 있으면 SessionSwitcher 가 드롭다운을 그린다 */
const MOCK_SESSIONS = [
  {
    id: 649,
    sessionId: 'session-wd649',
    title: 'WD-649 테스트 대화',
    createdAt: '2026-11-08T00:00:00Z',
    updatedAt: '2026-11-08T01:00:00Z',
  },
];

test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockApi(page, 'GET', '/api/v1/ai/sessions', MOCK_SESSIONS);
});

/** 모바일 390px — 메뉴 서랍이 열린 채 단축키로 여는 경로(서랍을 거치므로 열기 경로가 다르다). */
test.describe('모바일 AI 오버레이 — 서랍 겹침 (WD-649)', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('메뉴 서랍을 연 채 Ctrl+K 로 열면 서랍이 닫히고 오버레이 입력창에 입력할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/', { waitUntil: 'commit' });
    await expect(chipLocator(page)).toBeVisible();

    // 서랍을 연다 — 포커스가 서랍 안 첫 항목으로 들어간다(WD-508).
    await page.getByRole('button', { name: '메뉴 열기' }).click();
    await expect(page.getByRole('dialog', { name: '메뉴' })).toBeVisible();
    await expect.poll(() => page.evaluate(() => !!document.activeElement?.closest('aside'))).toBe(true);

    await page.keyboard.press('Control+k');

    // 한 번에 모달 하나 — 서랍은 닫히고 AI 오버레이만 열린다.
    const overlay = overlayLocator(page);
    await expect(overlay).toBeVisible();
    await expect(page.getByRole('dialog', { name: '메뉴' })).toHaveCount(0);

    // 입력창에 포커스가 가고, 입력창의 어떤 조상도 inert 가 아니며, 실제 타이핑이 들어간다.
    const input = overlayInput(page);
    await expect(input).toBeFocused();
    expect(await isUnderInert(input)).toBe(false);
    await page.keyboard.type('hello');
    await expect(input).toHaveValue('hello');

    // Esc 로 닫으면 연 트리거(서랍 안 항목)는 닫힌 서랍(inert) 안이라, 서랍을 여는 「메뉴 열기」 버튼으로 포커스가 돌아온다.
    await page.keyboard.press('Escape');
    await expect(overlay).toHaveCount(0);
    const menuButton = page.getByRole('button', { name: '메뉴 열기' });
    await expect(menuButton).toBeFocused();
    // 뒤 화면이 다시 열린다 — 본문·메뉴 버튼은 inert 가 아니고, 닫힌 서랍만 inert 다.
    expect(await isUnderInert(page.locator('main#main-content'))).toBe(false);
    expect(await isUnderInert(menuButton)).toBe(false);
    await expect(page.locator('aside')).toHaveAttribute('inert', '');
  });
});

/** 모바일 390px — AI 칩으로 연 오버레이의 Esc 동작. 각 테스트는 칩으로 연 상태(입력창 포커스)에서 시작한다. */
test.describe('모바일 AI 오버레이 — Esc 닫기 (WD-649)', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    await expect(overlayInput(page)).toBeFocused();
  });

  test('Esc 로 닫히고 포커스가 칩으로 돌아온다', async ({ authenticatedPage: page }) => {
    await page.keyboard.press('Escape');
    await expect(overlayLocator(page)).toHaveCount(0);
    await expect(chipLocator(page)).toBeFocused();
    expect(await isUnderInert(page.locator('main#main-content'))).toBe(false);
  });

  test('IME 조합 중 Esc 로는 닫지 않는다', async ({ authenticatedPage: page }) => {
    const input = overlayInput(page);
    // 실제 IME 조합은 헤드리스에서 재현할 수 없어, 브라우저가 조합 중에 보내는 형태(isComposing=true, keyCode 229)의
    // Esc keydown 을 입력창에서 직접 발생시킨다.
    await input.evaluate((el) => {
      el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', isComposing: true, bubbles: true, cancelable: true }));
      el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 229, bubbles: true, cancelable: true }));
    });
    await expect(overlayLocator(page)).toBeVisible();
    await expect(input).toBeFocused();

    // 조합이 끝난 뒤의 일반 Esc 는 닫는다 — 위 무시가 리스너 부재 때문이 아님을 확인한다.
    await page.keyboard.press('Escape');
    await expect(overlayLocator(page)).toHaveCount(0);
  });

  test('대화 선택 메뉴가 열려 있으면 Esc 는 메뉴만 닫고 오버레이는 남는다', async ({ authenticatedPage: page }) => {
    const overlay = overlayLocator(page);
    // 하위 팝오버(Radix 드롭다운) — 세션 목록이 있을 때 그려지는 대화 선택 메뉴를 연다.
    await overlay.getByRole('button', { name: '대화 선택' }).click();
    const menu = page.getByRole('menu');
    await expect(menu).toBeVisible();
    await expect(menu.getByText('WD-649 테스트 대화')).toBeVisible();

    // 첫 Esc 는 메뉴가 소비(preventDefault)한다 — 메뉴만 닫히고 오버레이는 그대로.
    await page.keyboard.press('Escape');
    await expect(menu).toHaveCount(0);
    await expect(overlay).toBeVisible();

    // 메뉴가 닫힌 뒤의 Esc 는 오버레이를 닫는다.
    await page.keyboard.press('Escape');
    await expect(overlay).toHaveCount(0);
  });
});

test.describe('데스크탑 AI 사이드 패널 — Esc 동작 유지 (WD-649)', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('데스크탑 사이드 패널은 Esc 로 닫히지 않는다', async ({ authenticatedPage: page }) => {
    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();

    const panel = page.getByTestId('ai-side-panel');
    await expect(panel).not.toHaveAttribute('inert', /.*/);
    const input = panel.getByPlaceholder('메시지를 입력하세요...');
    await expect(input).toBeFocused();
    // 데스크탑은 대화상자가 아닌 보조 랜드마크다.
    await expect(page.getByRole('complementary', { name: 'AI 어시스턴트' })).toBeVisible();
    await expect(overlayLocator(page)).toHaveCount(0);

    await page.keyboard.press('Escape');
    await expect(panel).not.toHaveAttribute('inert', /.*/);
    await expect(input).toBeVisible();
    await expect(input).toBeFocused();
  });
});
