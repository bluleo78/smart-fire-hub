import type { Page } from '@playwright/test';

/**
 * 브라우저 두 프레임(requestAnimationFrame 두 번)이 지나길 기다린다
 * 고정 시간 대기 대신, 렌더 직후 한 프레임 뒤에 도는 복원 코드·퇴장 애니메이션 뒤 포커스 복귀까지 덮을 때 쓴다.
 * @param page - 대상 페이지
 */
export async function waitTwoFrames(page: Page): Promise<void> {
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
}
