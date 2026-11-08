import { expect, type Locator } from '@playwright/test';

/**
 * 긴 값 말줄임을 검증할 뷰포트 폭 (WD-634)
 * - 1280px 만 보면 좁은 노트북 폭(1024px, 사이드바 펼침)에서 표가 넘쳐 작업 열이 밀리는 회귀를 놓친다. 두 폭을 모두 본다.
 */
export const TABLE_LAYOUT_WIDTHS = [1280, 1024] as const;

/**
 * 표가 컨테이너 안에 가로로 들어맞고 마지막 열이 가로로 다 보이는지 검증한다 (WD-634)
 * - 표 컨테이너가 가로로 넘치지 않고(≤ 1px), 마지막 열 요소의 오른쪽 끝이 컨테이너·뷰포트 오른쪽 끝 안이다.
 * - 세로 위치는 보지 않는다 — 행이 접힌 영역 아래에 있어도 가로 배치만 따지도록 먼저 세로로 스크롤해 둔다.
 * @param lastCell - 가로로 다 보여야 하는 마지막 열의 요소(작업 버튼·상태 배지·마지막 칸)
 */
export async function expectTableFits(lastCell: Locator) {
  await lastCell.scrollIntoViewIfNeeded();
  const m = await lastCell.evaluate((el) => {
    // 페이지에 표가 여럿일 수 있으므로 이 요소가 들어 있는 표의 컨테이너를 잰다
    const container = el.closest('[data-slot="table-container"]') as HTMLElement;
    return {
      overflow: container.scrollWidth - container.clientWidth,
      containerRight: container.getBoundingClientRect().right,
      lastRight: el.getBoundingClientRect().right,
      viewport: window.innerWidth,
    };
  });
  expect(m.overflow, '표 컨테이너 가로 넘침(px)').toBeLessThanOrEqual(1);
  expect(m.lastRight, '마지막 열 오른쪽 끝이 컨테이너 밖').toBeLessThanOrEqual(m.containerRight + 1);
  expect(m.lastRight, '마지막 열 오른쪽 끝이 뷰포트 밖').toBeLessThanOrEqual(m.viewport);
}

/**
 * 긴 값은 말줄임되고 전체 값은 title 로 남는다 (WD-559)
 * @param valueEl - 긴 값을 그리는 요소(링크·span 등 truncate 가 걸리는 요소)
 * @param fullValue - 잘리기 전 전체 값
 */
export async function expectLongValueTruncated(valueEl: Locator, fullValue: string) {
  await expect(valueEl).toBeVisible();
  const clipped = await valueEl.evaluate((el) => el.scrollWidth > el.clientWidth);
  expect(clipped, '긴 값이 말줄임되어야 한다').toBe(true);
  await expect(valueEl).toHaveAttribute('title', fullValue);
}

/**
 * 평범한 길이의 값은 잘리지 않는다 (WD-559)
 * - 폭 상한이 너무 좁으면 보통 이메일·이름까지 말줄임되어 정보가 가려진다.
 * @param valueEl - 평범한 값을 그리는 요소
 */
export async function expectValueNotTruncated(valueEl: Locator) {
  await expect(valueEl).toBeVisible();
  const clipped = await valueEl.evaluate((el) => el.scrollWidth > el.clientWidth);
  expect(clipped, '평범한 길이의 값은 잘리지 않아야 한다').toBe(false);
}
