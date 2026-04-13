/**
 * 프로필 페이지 E2E 테스트
 *
 * ProfilePage.tsx 커버 경로:
 * - 프로필 정보 폼 표시 및 초기값 로드 (useEffect → profileForm.reset)
 * - 프로필 저장 성공 → PUT /api/v1/users/me → toast.success
 * - 프로필 저장 실패 → root 에러 메시지 표시
 * - 비밀번호 변경 성공 → PUT /api/v1/users/me/password → toast.success + form reset
 * - 비밀번호 확인 불일치 → Zod 유효성 에러
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('프로필 페이지', () => {
  /**
   * 프로필 페이지 기본 렌더링 — useEffect로 user 정보가 폼에 초기값으로 로드된다
   */
  test('프로필 정보가 폼에 초기값으로 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    await expect(page.getByRole('heading', { name: '내 프로필' })).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('프로필 정보')).toBeVisible();
    // CardTitle "비밀번호 변경"과 submit 버튼 "비밀번호 변경" 두 곳에 텍스트가 있으므로 first() 사용
    await expect(page.getByText('비밀번호 변경').first()).toBeVisible();

    // MOCK_USER.name = '테스트 사용자', email = 'test@example.com'
    await expect(page.locator('#profile-name')).toHaveValue('테스트 사용자');
    await expect(page.locator('#profile-email')).toHaveValue('test@example.com');
  });

  /**
   * 프로필 저장 성공 — PUT /api/v1/users/me 호출 및 payload 검증
   * onProfileSubmit → usersApi.updateMe → refreshUser → toast.success
   */
  test('프로필 저장 성공 → PUT /users/me 호출되고 성공 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    const updateCapture = await mockApi(page, 'PUT', '/api/v1/users/me', {}, { capture: true });

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    await page.locator('#profile-name').fill('수정된 사용자');

    await page.getByRole('button', { name: '저장' }).first().click();

    // PUT payload 검증
    const captured = await updateCapture.waitForRequest({ timeout: 5000 });
    expect(captured.payload).toMatchObject({ name: '수정된 사용자' });

    // toast.success 확인
    await expect(page.getByText('프로필이 업데이트되었습니다.').first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * 프로필 저장 실패 — API 500 → root 에러 메시지가 폼에 표시된다
   */
  test('프로필 저장 실패 → 에러 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    // PUT 실패 응답 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/users/me' && !url.pathname.includes('password'),
      (route) => {
        if (route.request().method() === 'PUT') {
          return route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ message: '서버 오류가 발생했습니다.' }),
          });
        }
        return route.fallback();
      },
    );

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    await page.locator('#profile-name').fill('에러 테스트');
    await page.getByRole('button', { name: '저장' }).first().click();

    // profileForm.setError('root') → 에러 메시지 표시
    await expect(page.getByText(/프로필 업데이트에 실패|서버 오류/).first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * 비밀번호 변경 성공 → PUT /api/v1/users/me/password → toast.success + 폼 초기화
   */
  test('비밀번호 변경 성공 → PUT /users/me/password 호출되고 성공 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    const pwCapture = await mockApi(page, 'PUT', '/api/v1/users/me/password', {}, { capture: true });

    await expect(page.locator('#current-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#current-password').fill('OldPass123!');
    await page.locator('#new-password').fill('NewPass456!');
    await page.locator('#confirm-password').fill('NewPass456!');

    await page.getByRole('button', { name: '비밀번호 변경' }).click();

    // payload 검증
    const captured = await pwCapture.waitForRequest({ timeout: 5000 });
    expect(captured.payload).toMatchObject({
      currentPassword: 'OldPass123!',
      newPassword: 'NewPass456!',
    });

    // toast.success 확인
    await expect(page.getByText('비밀번호가 변경되었습니다.').first()).toBeVisible({ timeout: 5000 });

    // onPasswordSubmit → passwordForm.reset() → 폼 초기화
    await expect(page.locator('#current-password')).toHaveValue('', { timeout: 3000 });
  });

  /**
   * 비밀번호 확인 불일치 → Zod confirmPassword 에러 메시지
   */
  test('비밀번호 확인 불일치 → 유효성 에러 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    await expect(page.locator('#current-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#current-password').fill('OldPass123!');
    await page.locator('#new-password').fill('NewPass456!');
    await page.locator('#confirm-password').fill('DifferentPass!');

    await page.getByRole('button', { name: '비밀번호 변경' }).click();

    // changePasswordSchema: confirmPassword !== newPassword → 에러
    await expect(page.getByText(/비밀번호가 일치하지 않|확인/).first()).toBeVisible({ timeout: 5000 });
    // 폼이 닫히지 않아야 한다 (API 호출 안 됨)
    await expect(page.locator('#current-password')).toBeVisible();
  });
});
