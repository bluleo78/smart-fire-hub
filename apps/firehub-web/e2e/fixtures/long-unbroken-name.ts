import { expect, type Locator, type Page } from '@playwright/test';

/**
 * 띄어쓰기 없는 긴 이름의 가로 넘침 검증 도구 (WD-656)
 * - 말줄임 검증은 `table-layout.ts` 의 expectLongValueTruncated / expectValueNotTruncated 를 쓴다.
 * - TODO: WD-648 의 `long-name-layout.ts` 가 병합되면 하나로 합친다.
 */

/** 띄어쓰기 없는 라틴 문자 100자 — 줄을 바꿀 곳이 없어 넘침이 가장 잘 드러난다 */
export const LONG_UNBROKEN_NAME = `LongName_${'abcdefghij'.repeat(10)}`.slice(0, 100);

/** 좁은 노트북 폭 — 사이드바가 펼쳐진 채로 본문 폭이 가장 빠듯한 데스크톱 폭 */
export const NARROW_DESKTOP_VIEWPORT = { width: 1024, height: 768 } as const;

/**
 * 페이지가 가로로 넘치지 않는다.
 * - 문서와 실제 스크롤 컨테이너인 <main>(overflow-auto) 둘 다 본다 — main 이 넘쳐도 문서는 그대로라 문서만 보면 놓친다.
 * - ReactFlow 캔버스가 있는 화면은 배치가 자리 잡는 동안 main 을 잠깐 넓혀 플레이크가 나므로(a11y-reflow-motion #402)
 *   `checkMain: false` 로 문서만 보고, 머리글 같은 좁은 범위는 expectNoOverflowWithin 으로 따로 본다.
 */
export async function expectNoHorizontalOverflow(page: Page, { checkMain = true } = {}) {
  const m = await page.evaluate(() => {
    const main = document.getElementById('main-content');
    return {
      docScroll: document.documentElement.scrollWidth,
      viewport: window.innerWidth,
      mainOverflow: main ? main.scrollWidth - main.clientWidth : 0,
    };
  });
  expect(m.docScroll, '문서 가로 넘침').toBeLessThanOrEqual(m.viewport);
  if (checkMain) expect(m.mainOverflow, '본문(main) 가로 넘침(px)').toBeLessThanOrEqual(1);
}

/** 컨테이너 자체가 가로로 넘치지 않고 오른쪽 끝이 뷰포트 안이다 */
export async function expectNoOverflowWithin(container: Locator, label: string) {
  const m = await container.evaluate((el) => ({
    overflow: el.scrollWidth - el.clientWidth,
    right: el.getBoundingClientRect().right,
    viewport: window.innerWidth,
  }));
  expect(m.overflow, `${label} 가로 넘침(px)`).toBeLessThanOrEqual(1);
  expect(m.right, `${label} 오른쪽 끝이 뷰포트 밖`).toBeLessThanOrEqual(m.viewport);
}

/**
 * 요소의 가로 범위가 컨테이너 안에 있고, 내용도 요소 상자 밖으로 넘치지 않는다 (1px 오차 허용).
 * @param el - 검사할 요소
 * @param container - 요소를 담아야 하는 컨테이너(카드·머리글·nav 등)
 * @param label - 실패 메시지에 쓸 이름
 * @param content - 내용 넘침도 볼지(입력칸처럼 안에서 스크롤되는 요소는 false)
 */
export async function expectInsideHorizontally(el: Locator, container: Locator, label: string, { content = true } = {}) {
  const box = await el.boundingBox();
  const outer = await container.boundingBox();
  expect(box, `${label} 위치`).not.toBeNull();
  expect(outer, `${label} 컨테이너 위치`).not.toBeNull();
  expect(box!.x, `${label} 왼쪽 끝이 컨테이너 밖`).toBeGreaterThanOrEqual(outer!.x - 1);
  expect(box!.x + box!.width, `${label} 오른쪽 끝이 컨테이너 밖`).toBeLessThanOrEqual(outer!.x + outer!.width + 1);
  if (!content) return;
  const clipped = await el.evaluate((node) => node.scrollWidth - node.clientWidth);
  expect(clipped, `${label} 내용이 상자 밖으로 넘침(px)`).toBeLessThanOrEqual(1);
}

/**
 * 잘린 값은 hover 했을 때 전체 이름 title 이 붙는다 — TruncatedTitle 용 (WD-663)
 * - TruncatedTitle 은 잘림을 늘 관찰하지 않고 pointerenter·focus 때만 재서 title 을 단다. 그래서 hover 전에는 title 이 없다.
 * - 항상 title 을 두는 TruncatedText(표 칸)는 table-layout.ts 의 expectLongValueTruncated 를 쓴다.
 * @param el - 말줄임되는 글자 요소
 * @param fullValue - 잘리기 전 전체 값
 */
export async function expectTruncatedWithHoverTitle(el: Locator, fullValue: string) {
  await expect(el).toBeVisible();
  // 제품 판정(lib/overflow-title.ts)과 같은 1px 여유 — 반올림 1px 차이는 잘림이 아니다(WD-669)
  const clipped = await el.evaluate((node) => node.scrollWidth - 1 > node.clientWidth);
  expect(clipped, '긴 값이 말줄임되어야 한다').toBe(true);
  // 포인터가 이미 요소 위에 있으면 hover() 가 pointerenter 를 다시 일으키지 않는다 — 먼저 바깥으로 뺀다
  await el.page().mouse.move(0, 0);
  await el.hover();
  await expect(el).toHaveAttribute('title', fullValue);
}

/**
 * 잘리지 않은 값은 hover 해도 title 이 없다 — TruncatedTitle 용 (WD-663)
 * - hover 없이 title 이 없음만 보면, 판정이 hover 때 일어나므로 늘 통과해 아무것도 검증하지 못한다.
 * @param el - 잘리지 않아야 하는 글자 요소
 */
export async function expectNoTitleOnHover(el: Locator) {
  await expect(el).toBeVisible();
  // 제품 판정(lib/overflow-title.ts)과 같은 1px 여유 — 반올림 1px 차이는 잘림이 아니다(WD-669)
  const clipped = await el.evaluate((node) => node.scrollWidth - 1 > node.clientWidth);
  expect(clipped, '평범한 길이의 값은 잘리지 않아야 한다').toBe(false);
  await el.page().mouse.move(0, 0);
  await el.hover();
  await expect(el, '잘리지 않은 값에는 툴팁이 없다').not.toHaveAttribute('title');
}
