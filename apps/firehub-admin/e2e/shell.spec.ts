import { expect, loginAs, test } from './fixtures/auth.fixture';

test.describe('운영자 콘솔 셸', () => {
  test('상단 바에 평면 칩과 목적지 2개, 계정 메뉴가 있다', async ({ authenticatedPage: page }) => {
    await page.goto('/tenants');

    await expect(page.getByText('Smart Fire Hub')).toBeVisible();
    await expect(page.getByText('운영자 콘솔')).toBeVisible();

    const nav = page.getByRole('navigation', { name: '주요 메뉴' });
    await expect(nav.getByRole('link', { name: '테넌트' })).toBeVisible();
    await expect(nav.getByRole('link', { name: '플랫폼 설정' })).toBeVisible();
    // 목적지는 정확히 2개다 — 사이드바를 버린 이유가 여기 고정된다.
    await expect(nav.getByRole('link')).toHaveCount(2);

    // 활성 링크는 aria-current="page"
    await expect(nav.getByRole('link', { name: '테넌트' })).toHaveAttribute('aria-current', 'page');

    await page.getByRole('button', { name: '김운영' }).click();
    await expect(page.getByRole('menuitem', { name: '로그아웃' })).toBeVisible();
  });

  test('루트 경로는 /tenants 로 보낸다', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    await expect(page).toHaveURL('/tenants');
  });

  test('권한이 없으면 목적지를 렌더하지 않고 안내 문구를 보여준다', async ({ page }) => {
    // platform:member:read 만 가진 운영자 — 두 목적지 모두 가려진다.
    await loginAs(page, ['platform:member:read']);
    await page.goto('/tenants');

    await expect(
      page.getByText('이 콘솔에서 접근 가능한 메뉴가 없습니다. 플랫폼 관리자에게 권한을 요청하세요.'),
    ).toBeVisible();
    await expect(page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('link')).toHaveCount(0);
  });

  test('미인증이면 /login 으로 보낸다', async ({ authMockedPage: page }) => {
    // localStorage 플래그를 심지 않았으므로 AuthProvider 가 refresh 를 시도조차 하지 않는다.
    await page.goto('/tenants');
    await expect(page).toHaveURL('/login');
  });
});
