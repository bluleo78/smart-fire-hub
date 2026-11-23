import type { Locator, Page } from '@playwright/test';

/**
 * 키보드 초점 이동 도우미 (WD-927)
 * 페이지에서 Tab 만 눌러 대상에 초점이 닿을 때까지 이동한다 — 바로 앞 요소를 가정하지 않아,
 * 대상 앞에 초점을 받는 요소(필터·안내 등)가 늘어도 키보드 경로가 있는 한 시험이 깨지지 않는다.
 * 끝내 닿지 못하면 예외로 실패한다(키보드로 닿을 수 없다는 뜻).
 * @param page - 대상 페이지
 * @param target - 초점이 닿아야 할 요소
 * @param maxTabs - 최대 키 입력 횟수(사이드바 메뉴를 지나갈 여유)
 * @param key - 초점을 옮길 키(기본 'Tab', 거슬러 가려면 'Shift+Tab')
 */
export async function tabUntilFocused(page: Page, target: Locator, maxTabs = 60, key = 'Tab'): Promise<void> {
  for (let i = 0; i < maxTabs; i++) {
    await page.keyboard.press(key);
    if (await target.evaluate((el) => el === document.activeElement)) return;
  }
  throw new Error(`${key} ${maxTabs}번 안에 대상에 초점이 닿지 않았다`);
}

/**
 * 초점 표시에 관련된 계산 스타일을 읽는다 (WD-949)
 * 고대비 초점 시험이 outline·offset·box-shadow 와 :focus-visible 여부를 한 번에 단언하도록 모은다.
 * @param el - 스타일을 읽을 요소
 */
export async function readFocusStyle(el: Locator) {
  return el.evaluate((node) => {
    const cs = getComputedStyle(node);
    return {
      focusVisible: node.matches(':focus-visible'),
      outlineStyle: cs.outlineStyle,
      outlineWidth: cs.outlineWidth,
      outlineOffset: cs.outlineOffset,
      outlineColor: cs.outlineColor,
      boxShadow: cs.boxShadow,
    };
  });
}
