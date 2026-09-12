/**
 * 딥링크 보존 로그인 리다이렉트 E2E 테스트 (#675)
 *
 * 회귀 배경: 로그아웃 상태에서 보호된 페이지에 직접 진입하면 /login으로 리다이렉트되는데,
 * 로그인 성공 후 원래 요청했던 경로가 아니라 항상 홈('/')으로 이동하던 결함.
 * - ProtectedRoute가 미인증 리다이렉트 시 state.from으로 원래 위치를 전달하고,
 *   LoginPage가 로그인 성공 시 이를 읽어 원래 경로로 돌아가는지 검증한다.
 * - 딥링크 없이 /login에 직접 접근한 일반적인 로그인은 여전히 '/'로 이동해야 한다(회귀 없음).
 */

import { expect, test } from '../../fixtures/auth.fixture';

test.describe('딥링크 보존 로그인 리다이렉트 (#675)', () => {
  test('보호된 딥링크 접근 → 로그인 리다이렉트 → 로그인 성공 시 원래 경로로 복귀한다', async ({
    authMockedPage: page,
  }) => {
    // 미인증 상태로 보호된 페이지(/profile)에 직접 진입
    await page.goto('/profile');

    // ProtectedRoute가 미인증으로 판단해 /login으로 리다이렉트
    await expect(page).toHaveURL(/\/login/);

    // 로그인 수행
    await page.getByLabel('아이디 (이메일)').fill('test@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('testpassword123');
    await page.getByRole('button', { name: '로그인' }).click();

    // 원래 요청했던 '/profile'로 되돌아가야 한다 (홈이 아님)
    await page.waitForURL('/profile');
    await expect(page).toHaveURL('/profile');
    await expect(page.getByRole('heading', { name: '내 프로필' })).toBeVisible({ timeout: 5000 });
  });

  test('딥링크 없이 /login에 직접 접근한 로그인은 여전히 홈으로 이동한다 (회귀 없음)', async ({
    authMockedPage: page,
  }) => {
    // 딥링크를 거치지 않고 /login에 직접 진입 (state.from 없음)
    await page.goto('/login');

    await page.getByLabel('아이디 (이메일)').fill('test@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('testpassword123');
    await page.getByRole('button', { name: '로그인' }).click();

    // state.from이 없으므로 기존과 동일하게 홈('/')으로 이동
    await page.waitForURL('/');
    await expect(page).toHaveURL('/');
  });
});
