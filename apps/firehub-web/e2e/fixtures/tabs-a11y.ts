import { expect, type Locator, type Page } from '@playwright/test';

/** 탭 접근성 확인 도우미(WD-514) — 여러 spec 이 같은 aria-controls ↔ tabpanel 확인을 쓴다. */

/** 선택된 탭의 aria-controls 가 존재하는 role=tabpanel 을 가리키고, 그 패널이 aria-labelledby 로 탭을 되가리키는지 확인하고, 그 패널을 돌려준다. */
export async function expectSelectedTabControlsPanel(page: Page, tab: Locator): Promise<Locator> {
  await expect(tab).toHaveAttribute('aria-selected', 'true');
  const controls = await tab.getAttribute('aria-controls');
  const tabId = await tab.getAttribute('id');
  expect(controls, 'aria-controls 가 있어야 한다').toBeTruthy();
  expect(tabId, '탭 id 가 있어야 한다').toBeTruthy();
  // Radix id 는 useId 문자(«», :)를 포함할 수 있어 CSS #id 대신 속성 선택자로 찾는다
  const panel = page.locator(`[id="${controls}"]`);
  await expect(panel).toHaveCount(1);
  await expect(panel).toHaveAttribute('role', 'tabpanel');
  await expect(panel).toHaveAttribute('aria-labelledby', tabId!);
  return panel;
}
