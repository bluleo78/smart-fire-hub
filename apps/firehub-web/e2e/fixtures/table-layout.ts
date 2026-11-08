import { expect, type Locator } from '@playwright/test';

/**
 * 목록 표의 긴 값 말줄임 검증 헬퍼 (WD-559)
 * - 공백 없는 긴 이름·설명이 표 칸을 늘려 마지막 열(작업·상태)을 화면 밖으로 밀어내던 회귀를 막는다.
 * - 데이터셋 목록(WD-517)과 같은 기준: 표 컨테이너가 가로로 넘치지 않고, 긴 값은 잘려 보이며 전체 값은 title 로 남는다.
 * @param valueEl - 긴 값을 그리는 요소(링크·span 등 truncate 가 걸리는 요소)
 * @param fullValue - 잘리기 전 전체 값(title 로 남아야 한다)
 * @param lastCell - 화면 안에 보여야 하는 마지막 열의 요소(작업 버튼·상태 배지)
 */
export async function expectLongValueTruncated(valueEl: Locator, fullValue: string, lastCell: Locator) {
  await expect(valueEl).toBeVisible();

  // 표 컨테이너가 가로로 넘치지 않는다 — 넘치면 오른쪽 열이 가로 스크롤해야만 보인다.
  // 페이지에 표가 여럿일 수 있으므로 값이 들어 있는 표의 컨테이너를 잰다.
  const container = valueEl.locator('xpath=ancestor::*[@data-slot="table-container"]');
  const overflow = await container.evaluate((el) => el.scrollWidth - el.clientWidth);
  expect(overflow, '표 컨테이너 가로 넘침(px)').toBeLessThanOrEqual(1);

  // 마지막 열(작업 버튼·상태)이 뷰포트 안에 보인다
  await expect(lastCell).toBeInViewport();

  // 말줄임 상태: 내용 폭이 보이는 폭보다 크다(잘려 보인다)
  const clipped = await valueEl.evaluate((el) => el.scrollWidth > el.clientWidth);
  expect(clipped, '긴 값이 말줄임되어야 한다').toBe(true);

  // 잘린 전체 값은 title 로 볼 수 있어야 한다
  await expect(valueEl).toHaveAttribute('title', fullValue);
}

/**
 * 평범한 길이의 값은 잘리지 않는다 (WD-559)
 * - 폭 상한이 너무 좁으면 보통 이메일·이름까지 말줄임되어 정보가 가려진다. 데이터셋 목록(WD-517)의 "짧은 이름은 잘리지 않는다"와 같은 기준.
 * @param valueEl - 평범한 값을 그리는 요소
 */
export async function expectValueNotTruncated(valueEl: Locator) {
  await expect(valueEl).toBeVisible();
  const clipped = await valueEl.evaluate((el) => el.scrollWidth > el.clientWidth);
  expect(clipped, '평범한 길이의 값은 잘리지 않아야 한다').toBe(false);
}
