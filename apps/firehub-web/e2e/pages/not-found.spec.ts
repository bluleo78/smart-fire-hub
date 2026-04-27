import { expect, test } from '../fixtures/auth.fixture';

/**
 * 404 페이지 E2E 테스트 (이슈 #81)
 * - 잘못된 URL 접근 시 AppLayout(사이드바·헤더) 안에서 404 콘텐츠가 렌더되는지 검증
 * - 홈 버튼·이전 페이지 버튼 동작 검증
 * - 미인증 사용자는 ProtectedRoute에 의해 /login으로 리다이렉트되는지 검증
 */

test.describe('404 페이지', () => {
  /**
   * 테스트 1: 인증 사용자 — 사이드바·헤더가 함께 렌더된다
   * 이슈 #81의 핵심 회귀: 404가 단독 화면이 아닌 AppLayout 내부에서 표시되어야 한다
   */
  test('인증 사용자가 잘못된 URL 접근 시 사이드바·헤더와 함께 404 콘텐츠가 렌더된다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/this-page-does-not-exist-12345');

    // 404 콘텐츠 노출
    await expect(page.getByRole('heading', { name: '404' })).toBeVisible();
    await expect(page.getByText('페이지를 찾을 수 없습니다')).toBeVisible();

    // AppLayout 사이드바가 함께 렌더되어 다른 메뉴로 이동 가능 (이슈 #81 수정의 핵심)
    // 사이드바 첫 항목 '홈' 링크 (AppLayout > nav)
    await expect(page.getByRole('navigation').getByRole('link', { name: '홈' })).toBeVisible();
    // 사이드바 '데이터' 그룹 토글 버튼
    await expect(page.getByRole('button', { name: '데이터' })).toBeVisible();

    // 액션 버튼 두 개가 존재
    await expect(page.getByRole('button', { name: /홈으로 가기/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /이전 페이지로/ })).toBeVisible();
  });

  /**
   * 테스트 2: '홈으로 가기' 버튼 클릭 → '/'로 이동
   */
  test('홈으로 가기 버튼 클릭 시 홈으로 이동한다', async ({ authenticatedPage: page }) => {
    await page.goto('/this-page-does-not-exist-77777');
    await expect(page.getByRole('heading', { name: '404' })).toBeVisible();

    await page.getByRole('button', { name: /홈으로 가기/ }).click();
    await expect(page).toHaveURL('/');
  });

  /**
   * 테스트 3: '이전 페이지로' 버튼 클릭 → history.back
   */
  test('이전 페이지로 버튼 클릭 시 직전 페이지로 복귀한다', async ({ authenticatedPage: page }) => {
    // 먼저 홈을 방문하여 history 항목을 만든다
    await page.goto('/');
    await page.goto('/this-page-does-not-exist-55555');
    await expect(page.getByRole('heading', { name: '404' })).toBeVisible();

    await page.getByRole('button', { name: /이전 페이지로/ }).click();
    await expect(page).toHaveURL('/');
  });

  /**
   * 테스트 4: 미인증 사용자가 잘못된 URL 접근 시 /login으로 리다이렉트된다 (기존 동작 유지)
   */
  test('미인증 사용자가 잘못된 URL 접근 시 로그인 페이지로 리다이렉트된다', async ({ page }) => {
    await page.goto('/this-page-does-not-exist-99999');
    await expect(page).toHaveURL(/\/login/);
  });
});
