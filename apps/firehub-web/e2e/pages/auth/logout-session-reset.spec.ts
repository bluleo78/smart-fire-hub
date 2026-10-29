/**
 * 로그아웃 뒤 다음 사용자에게 앞 사람의 화면·데이터가 남지 않는다 (WD-276).
 *
 * <p>회귀 배경: 로그아웃이 인증 상태만 비우고 react-query 캐시(staleTime 30초)를 남겼고, ProtectedRoute 가
 * 로그아웃 순간의 주소를 딥링크(state.from, #675)로 실어 보냈다. 그래서 같은 브라우저에서 다른 계정으로 로그인하면
 * 앞 사람이 보던 화면으로 바로 이동하고, 그 화면의 데이터가 재요청 없이 캐시에서 그려졌다(서버는 403 인데도).
 * 수정은 로그아웃을 /login 하드 리로드로 끝내 캐시·history state 를 모두 비운다.
 *
 * <p>UI 로그인(authMockedPage)으로 시작한다 — authenticatedPage 의 세션 플래그 주입과 무관하게 실제 흐름을 본다.
 */
import type { Page } from '@playwright/test';

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 로그인 화면에서 테스트 계정으로 로그인한다. */
async function loginViaForm(page: Page) {
  await page.getByLabel('아이디 (이메일)').fill('test@example.com');
  await page.getByLabel('비밀번호', { exact: true }).fill('testpassword123');
  await page.getByRole('button', { name: '로그인' }).click();
}

/** 사용자 메뉴 → 로그아웃 → /login 리로드가 끝나 로그인 폼이 보일 때까지 기다린다. */
async function logoutViaMenu(page: Page) {
  await page.getByText('테스트 사용자', { exact: true }).click();
  await page.getByRole('menuitem', { name: '로그아웃' }).click();
  await expect(page.getByLabel('아이디 (이메일)')).toBeVisible();
}

test.describe('로그아웃 후 다른 사용자 로그인 (WD-276)', () => {
  test.beforeEach(async ({ authMockedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/logout', {});
  });

  test('로그아웃 순간 보던 화면으로 돌아가지 않고 홈으로 들어간다', async ({ authMockedPage: page }) => {
    await page.goto('/login');
    await loginViaForm(page);
    await page.waitForURL('/');

    // 앞 사람이 다른 화면(내 프로필)을 보다가 로그아웃한다
    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: '내 프로필' })).toBeVisible();
    await logoutViaMenu(page);
    await expect(page).toHaveURL(/\/login$/);

    // 다음 사람의 로그인은 앞 사람의 마지막 화면(/profile)이 아니라 홈으로 간다
    await loginViaForm(page);
    await page.waitForURL('/');
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();
  });

  test('다음 로그인은 앞 사람이 받아 둔 캐시를 쓰지 않고 데이터를 새로 받는다', async ({ authMockedPage: page }) => {
    // 홈 통계 요청 수를 센다 — 캐시가 남으면 30초(staleTime) 안의 재진입은 다시 요청하지 않는다
    let statsRequests = 0;
    page.on('request', (req) => {
      if (new URL(req.url()).pathname === '/api/v1/dashboard/stats') statsRequests += 1;
    });

    await page.goto('/login');
    await loginViaForm(page);
    await page.waitForURL('/');
    await expect.poll(() => statsRequests).toBe(1);

    await logoutViaMenu(page);
    await loginViaForm(page);
    await page.waitForURL('/');

    // 같은 화면이라도 새 사용자 권한으로 다시 받는다
    await expect.poll(() => statsRequests).toBe(2);
  });

  test('로그아웃 뒤 「뒤로」를 눌러도 앞 사람이 보던 화면으로 돌아가지 않는다', async ({ authMockedPage: page }) => {
    await page.goto('/login');
    await loginViaForm(page);
    await page.waitForURL('/');
    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: '내 프로필' })).toBeVisible();

    await logoutViaMenu(page);
    // 로그아웃은 history 의 /profile 자리를 /login 으로 바꾼다 — 뒤로 가도 /profile 이 다시 열리지 않는다
    await page.goBack();
    await expect(page).not.toHaveURL(/\/profile/);
    await expect(page.getByLabel('아이디 (이메일)')).toBeVisible();

    await loginViaForm(page);
    await expect(page).not.toHaveURL(/\/profile/);
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();
  });
});
