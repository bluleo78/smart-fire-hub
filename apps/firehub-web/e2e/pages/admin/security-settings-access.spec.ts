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

  test('권한 없는 일반 사용자는 홈으로 돌려보낸다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/settings');
    await expect(page).toHaveURL(/\/$/);
  });

  test('다른 관리 화면은 여전히 ADMIN 전용', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['security:settings']);
    await page.goto('/admin/roles');
    await expect(page).toHaveURL(/\/$/);
  });
});
