/**
 * 다중 탭 로그아웃 동기화 E2E (#565).
 *
 * <p>액세스 토큰은 탭마다 별도의 JS 메모리에 있으므로, 한 탭(B)에서 로그아웃해도 다른 탭(A)은
 * 그 사실을 알 방법이 없어 만료 전까지 계속 인증된 것처럼 동작하는 결함이 있었다. 수정은
 * `AuthContext`가 `storage` 이벤트를 구독해 다른 탭이 `hasSession` 플래그를 지우면(로그아웃)
 * 이 탭도 즉시 인증 상태를 초기화하도록 한다. `storage` 이벤트는 변경을 일으킨 탭 자신에게는
 * 발생하지 않고 "다른" 탭에서만 발생하므로, 동일 `BrowserContext` 안에서 두 개의 `Page`를 열어
 * 실제 다중 탭과 같은 조건(같은 오리진 · 같은 localStorage)을 재현한다.
 */
import type { Page } from '@playwright/test';

import { mockApi } from '../../fixtures/api-mock';
import { expect, MOCK_TOKEN_RESPONSE, test } from '../../fixtures/auth.fixture';
import { setupHomeMocks } from '../../fixtures/base.fixture';

const MOCK_USER_DETAIL_FOR_LOGOUT = {
  id: 1,
  username: 'test@example.com',
  email: 'test@example.com',
  name: '테스트 사용자',
  isActive: true,
  createdAt: '2026-01-01T00:00:00',
  roles: [{ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }],
};

/** 탭 B에서도 인증/홈 API가 필요하므로 authenticatedPage와 동일한 모킹을 별도 page에 적용한다. */
async function setupTabMocks(page: Page) {
  await mockApi(page, 'POST', '/api/v1/auth/login', MOCK_TOKEN_RESPONSE);
  await mockApi(page, 'POST', '/api/v1/auth/refresh', MOCK_TOKEN_RESPONSE);
  await mockApi(page, 'GET', '/api/v1/users/me', MOCK_USER_DETAIL_FOR_LOGOUT);
  await mockApi(page, 'POST', '/api/v1/auth/logout', {});
  await setupHomeMocks(page);
}

test.describe('다중 탭 로그아웃 동기화', () => {
  // MT-01: 탭 B에서 로그아웃하면, 새로고침 없이도 탭 A가 자동으로 /login으로 이동한다.
  test('MT-01: 다른 탭에서 로그아웃하면 이 탭도 즉시 로그인 화면으로 이동한다', async ({ authenticatedPage: tabA }) => {
    await setupTabMocks(tabA);
    await tabA.goto('/');
    await expect(tabA.getByText('테스트 사용자')).toBeVisible();

    // 탭 B: 같은 BrowserContext(=같은 오리진의 localStorage 공유)에 새 탭을 연다.
    // hasSession 플래그는 탭 A가 이미 로그인 시 심어둔 실제 localStorage 값을 그대로 공유하므로
    // 별도 주입 없이도 탭 B는 인증된 화면으로 뜬다.
    const tabB = await tabA.context().newPage();
    await setupTabMocks(tabB);
    await tabB.goto('/');
    await expect(tabB.getByText('테스트 사용자')).toBeVisible();

    // 탭 B에서 사용자 메뉴 → 로그아웃
    await tabB.getByText('테스트 사용자').click();
    await tabB.getByRole('menuitem', { name: '로그아웃' }).click();
    await tabB.waitForURL('**/login');

    // 탭 A는 아무 조작(새로고침·클릭) 없이도 storage 이벤트를 받아 /login으로 리다이렉트되어야 한다.
    await tabA.waitForURL('**/login', { timeout: 5000 });
    await expect(tabA.getByLabel('아이디 (이메일)')).toBeVisible();
  });
});
