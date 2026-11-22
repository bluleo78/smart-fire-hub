import type { Locator, Page } from '@playwright/test';

import { createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';
import { tabUntilFocused } from '../../fixtures/keyboard-focus';

/**
 * 데이터셋 목록 이름 링크 고대비(forced-colors) 초점 표시 (WD-944)
 * - TruncatedLink 의 초점 표시는 box-shadow ring 인데, 고대비 모드에서는 box-shadow 가 그려지지 않는다.
 *   outline-none(= outline-style: none)이면 키보드 초점이 보이지 않으므로 outline-hidden
 *   (보통 모드는 outline 없음, 고대비 모드에서만 투명 2px outline 을 두어 시스템 색으로 그려짐)이어야 한다.
 */
test.describe('데이터셋 목록 이름 링크 고대비 초점 표시 (WD-944)', () => {
  /** 목록을 열고 검색 칸에서 Tab 으로 첫 행 이름 링크까지 키보드 초점을 옮긴다(키보드 초점이어야 :focus-visible) */
  async function focusFirstNameLink(page: Page) {
    await page.setViewportSize({ width: 1280, height: 720 });
    await setupDatasetMocks(page);
    const datasets = createDatasets(3);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(datasets));
    await page.goto('/data/datasets');

    const firstLink = page.getByRole('link', { name: datasets[0].name });
    await expect(firstLink).toBeVisible();
    await page.getByPlaceholder('데이터셋 검색...').click();
    // 검색 칸 뒤 필터 컨트롤 수에 매이지 않도록 공용 도우미로 닿을 때까지 Tab (못 닿으면 도우미가 실패시킨다)
    await tabUntilFocused(page, firstLink);
    return firstLink;
  }

  /** 초점 표시에 관련된 계산 스타일을 한 번에 읽는다 */
  async function readFocusStyle(link: Locator) {
    return link.evaluate((el) => {
      const cs = getComputedStyle(el);
      return {
        focusVisible: el.matches(':focus-visible'),
        outlineStyle: cs.outlineStyle,
        outlineWidth: cs.outlineWidth,
        ringShadow: cs.getPropertyValue('--tw-ring-shadow').trim(),
      };
    });
  }

  test('고대비 모드에서 Tab 초점이 간 이름 링크에 outline 이 그려진다', async ({ authenticatedPage: page }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    const style = await readFocusStyle(await focusFirstNameLink(page));

    expect(style.focusVisible, 'Tab 초점이라 :focus-visible 이어야 한다').toBe(true);
    expect(style.outlineStyle, '고대비에서 outline-style 이 none 이면 초점이 안 보인다').not.toBe('none');
    expect(parseFloat(style.outlineWidth)).toBeGreaterThan(0);
  });

  test('보통 모드에서는 ring 이 그대로이고 outline 은 보이지 않는다', async ({ authenticatedPage: page }) => {
    const style = await readFocusStyle(await focusFirstNameLink(page));

    expect(style.ringShadow, '보통 모드에서는 ring 두께가 있어야 한다').toMatch(/[1-9][\d.]*px/);
    // outline-hidden 은 보통 모드에서 outline-style: none 이라 ring 과 이중 테두리가 생기지 않는다
    expect(style.outlineStyle, '보통 모드에서 outline 이 그려지면 ring 과 이중 테두리가 된다').toBe('none');
  });
});
