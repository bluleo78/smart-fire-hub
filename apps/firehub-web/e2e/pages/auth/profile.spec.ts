/**
 * 프로필 페이지 E2E 테스트
 *
 * ProfilePage.tsx 커버 경로:
 * - 프로필 정보 폼 표시 및 초기값 로드 (useEffect → profileForm.reset)
 * - 로그인 계정(username) 읽기 전용 필드 표시
 * - email이 null일 때 username이 이메일 필드 기본값으로 사용됨 (이슈 #18 회귀 방지)
 * - 프로필 저장 성공 → PUT /api/v1/users/me → toast.success
 * - 프로필 저장 실패 → root 에러 메시지 표시
 * - 비밀번호 변경 성공 → PUT /api/v1/users/me/password → toast.success + form reset
 * - 비밀번호 확인 불일치 → Zod 유효성 에러
 */

import type { UserDetailResponse } from '../../../src/types/user';
import { mockApi } from '../../fixtures/api-mock';
import { expect, MOCK_USER, test } from '../../fixtures/auth.fixture';

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

    // MOCK_USER.name = '테스트 사용자', email = 'test@example.com', username = 'test@example.com'
    await expect(page.locator('#profile-username')).toHaveValue('test@example.com');
    await expect(page.locator('#profile-name')).toHaveValue('테스트 사용자');
    await expect(page.locator('#profile-email')).toHaveValue('test@example.com');
  });

  /**
   * 회귀 테스트 (이슈 #18): email이 null인 경우 username이 이메일 필드에 표시된다
   * - /api/v1/users/me 응답에서 email이 null이어도 username을 통해 로그인 계정을 확인할 수 있어야 한다
   */
  test('email이 null인 경우 username이 이메일 필드 기본값으로 표시된다 (이슈 #18)', async ({ authenticatedPage: page }) => {
    // email이 null인 사용자 응답 모킹 — 실제 운영 환경과 동일한 조건
    const userWithNullEmail: UserDetailResponse = {
      ...MOCK_USER,
      email: null,
      roles: [{ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }],
    };
    await mockApi(page, 'GET', '/api/v1/users/me', userWithNullEmail);

    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: '내 프로필' })).toBeVisible({ timeout: 5000 });

    // 로그인 계정(username)은 항상 표시된다
    await expect(page.locator('#profile-username')).toHaveValue('test@example.com');
    // email이 null이면 username이 이메일 필드 기본값으로 채워진다
    await expect(page.locator('#profile-email')).toHaveValue('test@example.com');
    // 로그인 계정 필드는 읽기 전용이다
    await expect(page.locator('#profile-username')).toBeDisabled();
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
    const captured = await updateCapture.waitForRequest();
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
    const captured = await pwCapture.waitForRequest();
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
   * 회귀 테스트 (이슈 #26): 이름 필드 maxLength=100 초과 입력은 브라우저에서 100자로 잘린다
   * JavaScript로 직접 Zod max() 에러를 유발하여 에러 메시지와 API 미호출을 검증
   */
  test('이름 100자 초과 → 유효성 에러 메시지가 표시되고 API 호출이 안 된다 (이슈 #26)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    // PUT API 호출 감지용 — 호출되면 실패
    let apiCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/users/me' && !url.pathname.includes('password'),
      (route) => {
        if (route.request().method() === 'PUT') {
          apiCalled = true;
          return route.fulfill({ status: 200, body: '{}' });
        }
        return route.fallback();
      },
    );

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    // maxLength 속성을 우회하여 101자를 직접 주입 — Zod max(100) 검증 트리거 (#26)
    await page.evaluate(() => {
      const input = document.querySelector('#profile-name') as HTMLInputElement;
      const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
      nativeInputValueSetter?.call(input, 'A'.repeat(101));
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.getByRole('button', { name: '저장' }).first().click();

    // Zod max(100) 에러 메시지 표시
    await expect(page.getByText('이름은 100자 이하여야 합니다')).toBeVisible({ timeout: 5000 });
    // API가 호출되지 않아야 한다
    expect(apiCalled).toBe(false);
  });

  /**
   * 회귀 테스트 (이슈 #26): 이름 필드 maxLength 속성 검증 — HTML 레벨 입력 차단
   */
  test('이름 필드는 maxLength=100 속성을 가진다 (이슈 #26)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    // input의 maxLength 속성이 100임을 확인
    await expect(page.locator('#profile-name')).toHaveAttribute('maxlength', '100');
  });

  /**
   * 회귀 테스트 (이슈 #27): 잘못된 현재 비밀번호 입력 시 400 응답 → UI 에러 메시지 표시
   * 서버 500 + UI 피드백 없음 버그 방지
   */
  test('잘못된 현재 비밀번호 → 에러 메시지가 폼에 표시된다 (이슈 #27)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    // 서버 400 응답 모킹 (현재 비밀번호 불일치)
    await page.route(
      (url) => url.pathname === '/api/v1/users/me/password',
      (route) => {
        if (route.request().method() === 'PUT') {
          return route.fulfill({
            status: 400,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 400,
              error: 'Bad Request',
              message: '현재 비밀번호가 올바르지 않습니다',
            }),
          });
        }
        return route.fallback();
      },
    );

    await expect(page.locator('#current-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#current-password').fill('wrongpassword');
    await page.locator('#new-password').fill('NewPass456!');
    await page.locator('#confirm-password').fill('NewPass456!');

    await page.getByRole('button', { name: '비밀번호 변경' }).click();

    // onPasswordSubmit catch → passwordForm.setError('root') → 에러 메시지 표시
    await expect(page.getByText('현재 비밀번호가 올바르지 않습니다').first()).toBeVisible({ timeout: 5000 });
    // 폼이 리셋되지 않아야 한다 (변경 실패)
    await expect(page.locator('#current-password')).toHaveValue('wrongpassword');
  });

  /**
   * 회귀 테스트 (이슈 #70): 비밀번호 변경 폼이 onChange 모드로 동작하여
   * 제출 전(입력 도중)에도 새/확인 비밀번호 불일치 에러가 인라인으로 즉시 표시된다.
   * - mode 옵션이 기본값 'onSubmit' 그대로면 submit 전에는 에러가 표시되지 않아 회귀가 발생한다.
   */
  test('비밀번호 확인 불일치 → 제출 전에 인라인 에러가 즉시 표시된다 (이슈 #70)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    await expect(page.locator('#new-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#new-password').fill('NewPass456!');
    await page.locator('#confirm-password').fill('Different999');

    // 포커스를 다음 요소로 이동시켜 onChange 검증을 트리거하고 잠시 대기 (debounce 안전 마진)
    await page.locator('#confirm-password').blur();

    // submit 버튼을 누르지 않은 상태에서 에러가 노출되어야 한다
    await expect(page.getByText('비밀번호가 일치하지 않습니다')).toBeVisible({ timeout: 3000 });
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
