import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/** 스펙 §5-1 의존성: /admin/settings 가드를 "ADMIN 또는 security:settings" 로, 탭별 권한 노출. */
test.describe('데이터 보안 설정 접근', () => {
  test('security:settings 만 가진 일반 사용자는 데이터 보안 탭만 본다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['security:settings', 'dataset:read']);
    await setupSecurityLevelMocks(page);
    await page.goto('/admin/settings');
    await expect(page).toHaveURL(/\/admin\/settings$/);
    await expect(page.getByRole('heading', { name: '설정' })).toBeVisible();
    await expect(page.getByTestId('security-level-row')).toHaveCount(4);
    await expect(page.getByRole('tab', { name: 'AI 에이전트' })).toHaveCount(0);
    // 사이드바 관리 섹션에는 설정만
    await expect(page.getByRole('link', { name: '설정' })).toBeVisible();
    await expect(page.getByRole('link', { name: '사용자 관리' })).toHaveCount(0);
  });

  // WD-121: 권한이 없으면 홈으로 튕기지 않고 레이아웃 안에서 권한 안내를 보인다(말없이 홈으로 보내면 이유를 모른다).
  test('권한 없는 일반 사용자에게는 홈으로 튕기지 않고 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/settings');
    await expect(page.getByTestId('screen-forbidden')).toHaveText('이 화면을 볼 권한이 없습니다. 관리자에게 권한을 요청하세요.');
    await expect(page).toHaveURL(/\/admin\/settings$/);
    await expect(page.getByRole('heading', { name: '설정' })).toHaveCount(0);
  });

  // WD-121: 다른 관리 화면은 ADMIN 전용이 아니라 각자의 권한 코드로 열린다 — security:settings 로는 역할 관리가 열리지 않는다.
  test('security:settings 로는 역할 관리(role:read)가 열리지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['security:settings']);
    await page.goto('/admin/roles');
    await expect(page.getByTestId('screen-forbidden')).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/roles$/);
    await expect(page.getByRole('heading', { name: '역할 관리' })).toHaveCount(0);
  });
});
