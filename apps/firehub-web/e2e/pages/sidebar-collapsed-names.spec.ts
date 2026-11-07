import type { Page } from '@playwright/test';

import { setupAdminAuth } from '../fixtures/admin.fixture';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * 접힌 사이드바의 접근 가능한 이름 (WD-533, WCAG 4.1.2 Name, Role, Value)
 *
 * 수정 전 결함: 사이드바를 접으면 메뉴 링크가 아이콘만 남고 라벨 텍스트가 없어 이름이 빈 문자열이었고,
 * 「메뉴 펼치기」 버튼도 로고 이미지만 있어 이름이 없었다. 이름은 툴팁에만 있었는데 툴팁은 열리기 전엔 DOM 에 없다.
 */

/** 접힌 상태로 시작하도록 localStorage 를 미리 심고 홈을 연다 */
async function openCollapsed(page: Page) {
  await page.addInitScript(() => localStorage.setItem('sidebar-collapsed', 'true'));
  await page.goto('/');
  await expect(page.getByRole('button', { name: '메뉴 펼치기' })).toBeVisible();
}

test.describe('접힌 사이드바 접근 가능한 이름 (WD-533)', () => {
  test('접힌 상태: 사이드바 링크 전부가 이름을 갖고, 같은 이름의 링크가 중복되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 관리자 메뉴까지 보여 링크 수를 최대로 만든다
    await setupAdminAuth(page);
    await openCollapsed(page);

    const links = page.locator('aside nav a');
    const count = await links.count();
    expect(count).toBeGreaterThanOrEqual(10);

    // 모든 링크가 비지 않은 aria-label 을 갖는다(이름 계산 결과가 비면 스크린리더가 「링크」로만 읽는다)
    const labels = await links.evaluateAll((els) => els.map((el) => el.getAttribute('aria-label') ?? ''));
    for (const label of labels) {
      expect(label.trim()).not.toBe('');
    }

    // 툴팁 텍스트와 이중으로 이름이 잡히지 않는다 — 이름으로 찾으면 정확히 1개
    await expect(page.getByRole('link', { name: '데이터셋', exact: true })).toHaveCount(1);
    await expect(page.getByRole('link', { name: '홈', exact: true })).toHaveCount(1);
  });

  test('접힌 상태: 「메뉴 펼치기」 버튼이 이름으로 찾아지고 누르면 펼쳐진다', async ({
    authenticatedPage: page,
  }) => {
    await openCollapsed(page);

    await page.getByRole('button', { name: '메뉴 펼치기' }).click();
    await expect(page.getByRole('button', { name: '사이드바 접기' })).toBeVisible();
    await expect(page.getByRole('button', { name: '메뉴 펼치기' })).toHaveCount(0);
  });

  test('접힌 상태: TenantSwitcher·UserNav 트리거도 이름을 갖는다', async ({ authenticatedPage: page }) => {
    await openCollapsed(page);

    // 사이드바 하단 버튼(접기/펼치기·메뉴 제외) 전부가 비지 않은 이름을 갖는다
    const footerButtons = page.locator('aside button');
    const names = await footerButtons.evaluateAll((els) =>
      els.map((el) => (el.getAttribute('aria-label') ?? el.textContent ?? '').trim())
    );
    expect(names.length).toBeGreaterThan(1);
    for (const name of names) {
      expect(name).not.toBe('');
    }
  });

  test('펼친 상태: 링크 이름은 보이는 텍스트 그대로이고 aria-label 을 따로 달지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.addInitScript(() => localStorage.setItem('sidebar-collapsed', 'false'));
    await page.goto('/');
    await expect(page.getByRole('button', { name: '사이드바 접기' })).toBeVisible();

    await expect(page.getByRole('link', { name: '홈', exact: true })).toHaveCount(1);
    await expect(page.getByRole('link', { name: '데이터셋', exact: true })).toHaveCount(1);
    const withLabel = await page.locator('aside nav a[aria-label]').count();
    expect(withLabel).toBe(0);
  });

  test('접힌 상태: Tab 키보드 포커스로 링크에 닿으면 툴팁이 열린다', async ({ authenticatedPage: page }) => {
    await openCollapsed(page);

    // 건너뛰기 링크 → 펼치기 버튼 → 첫 메뉴 링크 순으로 Tab. 첫 메뉴 링크가 포커스될 때까지 이동한다.
    const first = page.locator('aside nav a').first();
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press('Tab');
      if (await first.evaluate((el) => el === document.activeElement)) break;
    }
    await expect(first).toBeFocused();

    const label = await first.getAttribute('aria-label');
    // 직전에 지나온 「메뉴 펼치기」 툴팁이 함께 남아 있을 수 있어 이름으로 좁힌다
    await expect(page.getByRole('tooltip', { name: label!, exact: true })).toHaveCount(1);
  });
});
