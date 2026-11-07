/**
 * AISidePanel 열림 시 입력창 포커스 E2E 테스트 (WD-579)
 *
 * 결함: Ctrl/Cmd+K 로 AI 사이드 패널을 열어도 포커스가 <body> 에 머물러,
 * 키보드 사용자가 바로 질문을 입력하지 못하고 Tab 을 여러 번 눌러 입력창을 찾아야 했다.
 *
 * 회귀 방지 포인트:
 * 1. 단축키·AI 상태 칩 어느 쪽으로 열어도 채팅 입력창(textarea)에 포커스가 간다(데스크탑·모바일).
 * 2. 닫았다가 다시 열어도 입력창 포커스가 반복된다(열림 전환마다 동작).
 * 3. 패널은 이름 있는 랜드마크(complementary, 「AI 어시스턴트」)로 노출된다.
 * 4. 칩으로 연 뒤 닫기 버튼으로 닫으면 포커스가 칩으로 복귀한다(#333 동작 유지).
 * 5. 전체 화면 모드로 열어도 입력창에 포커스가 간다.
 *
 * 스트리밍 중(입력창 disabled) 열기·포커스 빼앗지 않기 가드는 ChatInput 단위 테스트
 * (src/components/ai/ChatInput.test.tsx)에서 검증한다.
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** AIStatusChip — 패널을 여는 트리거(패널 바깥) */
const chipLocator = (page: import('@playwright/test').Page) =>
  page.getByRole('button', { name: /AI 상태/ });

test.describe('AISidePanel — 열림 시 입력창 포커스 (WD-579)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
  });

  test('아무것도 누르지 않고 Control+K 로 열면 입력창에 포커스가 가고, 다시 열어도 같다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/', { waitUntil: 'commit' });
    const panel = page.getByTestId('ai-side-panel');
    await expect(panel).toHaveAttribute('inert', /.*/);

    // 시작 시점 포커스는 body — 결함 재현 조건(opener 없음).
    expect(await page.evaluate(() => document.activeElement === document.body)).toBe(true);

    await page.keyboard.press('Control+k');
    await expect(panel).not.toHaveAttribute('inert', /.*/);

    // 패널은 이름 있는 complementary 랜드마크로 노출된다.
    const landmark = page.getByRole('complementary', { name: 'AI 어시스턴트' });
    await expect(landmark).toBeVisible();

    const input = landmark.getByPlaceholder('메시지를 입력하세요...');
    await expect(input).toBeFocused();
    // 포커스만이 아니라 바로 타이핑이 입력창에 들어가는지까지 확인한다(ASCII — 조합 입력 회피).
    await page.keyboard.type('hello');
    await expect(input).toHaveValue('hello');

    // 닫았다가 다시 열어도 입력창 포커스가 반복된다.
    await page.keyboard.press('Control+k');
    await expect(panel).toHaveAttribute('inert', /.*/);
    await page.keyboard.press('Control+k');
    await expect(panel).not.toHaveAttribute('inert', /.*/);
    await expect(input).toBeFocused();
  });

  test('AI 상태 칩으로 열면 입력창 포커스, 닫기 버튼으로 닫으면 칩으로 복귀한다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/', { waitUntil: 'commit' });
    const panel = page.getByTestId('ai-side-panel');
    const chip = chipLocator(page);

    await chip.click();
    await expect(panel).not.toHaveAttribute('inert', /.*/);
    await expect(panel.getByPlaceholder('메시지를 입력하세요...')).toBeFocused();

    // 닫기 버튼으로 닫는다 — 포커스가 패널 안에 있으므로 여는 트리거(칩)로 되돌아가야 한다.
    await panel.getByRole('button', { name: 'AI 어시스턴트 닫기' }).click();
    await expect(panel).toHaveAttribute('inert', /.*/);
    await expect(chip).toBeFocused();
  });

  test('전체 화면 모드가 저장된 상태에서 Control+K 로 열면 입력창에 포커스가 간다', async ({
    authenticatedPage: page,
  }) => {
    // 전체 화면 모드는 열리면서 AIFullScreen(lazy)과 그 안의 ChatInput 이 새로 마운트된다 —
    // 마운트 시점에 이미 focusRequest>0 인 경로를 검증한다.
    await page.addInitScript(() => localStorage.setItem('ai-mode', 'fullscreen'));
    await page.goto('/', { waitUntil: 'commit' });
    await expect(chipLocator(page)).toBeVisible();
    expect(await page.evaluate(() => document.activeElement === document.body)).toBe(true);

    await page.keyboard.press('Control+k');

    const input = page.getByPlaceholder('메시지를 입력하세요...');
    await expect(input).toBeVisible();
    await expect(input).toBeFocused();
    await page.keyboard.type('full');
    await expect(input).toHaveValue('full');
  });

  test('모바일 뷰포트(390x844)에서 열어도 입력창에 포커스가 간다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/', { waitUntil: 'commit' });
    // 모바일 분기는 닫힘에서 패널을 렌더하지 않는다 — 칩이 보일 때까지 기다려 앱 준비를 확인.
    await expect(chipLocator(page)).toBeVisible();

    await page.keyboard.press('Control+k');

    const landmark = page.getByRole('complementary', { name: 'AI 어시스턴트' });
    await expect(landmark).toBeVisible();
    await expect(landmark.getByPlaceholder('메시지를 입력하세요...')).toBeFocused();
  });
});
