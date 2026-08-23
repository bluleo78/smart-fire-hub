import { expect, test } from '@playwright/test';

/**
 * 스캐폴딩 스모크 — Vite 기동 + React 마운트 + Tailwind 토큰 적용까지가 살아 있는지 본다.
 * 이 테스트가 없으면 Task 1 은 "검증할 것이 없는 설정 파일 더미"가 된다.
 */
test('앱이 기동하고 운영자 콘솔 제목을 렌더한다', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '운영자 콘솔' })).toBeVisible();
  await expect(page).toHaveTitle('운영자 콘솔 · Smart Fire Hub');
});
