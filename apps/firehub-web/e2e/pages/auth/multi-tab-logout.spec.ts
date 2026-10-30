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

/**
 * 탭 여러 개를 한꺼번에 열 때 토큰 갱신이 겹쳐도 로그아웃되지 않는다(WD-438).
 *
 * <p>서버처럼 리프레시 토큰을 갱신마다 회전시키는 모의 `/auth/refresh` 를 둔다 — 지금 쿠키와 같은 토큰이면 200 과 새 쿠키,
 * 아니면(이미 회전된 토큰) 401. 수정 전에는 동시에 열린 탭들이 같은 쿠키로 동시에 갱신해 진 탭이 401 을 받고,
 * 그 401 이 `hasSession` 삭제(로그아웃 신호)가 되어 모든 탭이 로그인 화면으로 갔다. 수정 뒤에는 탭 사이 잠금으로
 * 갱신이 차례로 나가 뒤 탭이 회전된 쿠키로 성공한다.
 */
/** 로그인한 탭의 머리글 사용자 메뉴(이름·이메일) 버튼. */
const userMenu = (page: Page) => page.getByRole('button', { name: /테스트 사용자 test@example\.com/ });

test.describe('다중 탭 동시 갱신 (WD-438)', () => {
  /** 인증·홈 화면 모킹 — 갱신(/auth/refresh)은 컨텍스트 단의 회전 모의가 받도록 페이지 단에서는 걸지 않는다. */
  async function setupPageMocks(page: Page) {
    await mockApi(page, 'GET', '/api/v1/users/me', MOCK_USER_DETAIL_FOR_LOGOUT);
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', []);
    await mockApi(page, 'GET', '/api/v1/auth/signup-status', { open: false });
    await setupHomeMocks(page);
  }

  test('동시에 열린 탭들의 갱신이 겹쳐도 모든 탭이 로그인 상태로 남는다', async ({ context, baseURL }) => {
    const origin = baseURL!;
    let current = 'rt-0';
    let seq = 0;
    const statuses: number[] = [];
    await context.addCookies([{ name: 'refresh_token', value: current, url: origin }]);
    await context.route(
      (url) => url.pathname === '/api/v1/auth/refresh',
      async (route) => {
        const cookie = (await route.request().allHeaders())['cookie'] ?? '';
        const sent = /refresh_token=([^;]+)/.exec(cookie)?.[1];
        if (sent !== current) {
          // 이미 회전된 토큰 — 서버의 동시 갱신 패자와 같은 401
          statuses.push(401);
          return route.fulfill({
            status: 401,
            json: { status: 401, error: 'Unauthorized', message: '만료되었거나 폐기된 토큰입니다. 다시 로그인해 주세요.' },
          });
        }
        current = `rt-${++seq}`;
        // 응답이 늦게 도착하게 해 다른 탭의 갱신이 그 사이에 겹치게 한다
        await new Promise((resolve) => setTimeout(resolve, 300));
        await context.addCookies([{ name: 'refresh_token', value: current, url: origin }]);
        statuses.push(200);
        return route.fulfill({ json: MOCK_TOKEN_RESPONSE });
      },
    );

    // 탭 0: 로그인해 쓰던 탭
    const tab0 = await context.newPage();
    await setupPageMocks(tab0);
    await tab0.goto('/login');
    await tab0.evaluate(() => localStorage.setItem('hasSession', 'true'));
    await tab0.goto('/');
    // 로그인 상태 표식은 머리글 사용자 메뉴 버튼으로 본다 — 텍스트로 찾으면 홈 인사말(「환영합니다, 테스트 사용자님!」)이 먼저 그려진
    // 순간 두 요소에 걸려 strict mode 오류로 가끔 실패한다(부하 중 재현).
    await expect(userMenu(tab0)).toBeVisible();

    // 탭 두 개를 같은 순간에 연다(탭 복원·여러 링크 열기와 같은 상황)
    const tab1 = await context.newPage();
    const tab2 = await context.newPage();
    await Promise.all([setupPageMocks(tab1), setupPageMocks(tab2)]);
    await Promise.all([tab1.goto('/'), tab2.goto('/')]);

    for (const tab of [tab1, tab2]) {
      await expect(userMenu(tab)).toBeVisible();
    }
    // 갱신은 모두 성공해야 한다 — 401 이 하나라도 있으면 그 탭이 로그아웃 신호를 퍼뜨린다
    expect(statuses).toEqual([200, 200, 200]);
    // 잠시 뒤에도 원래 탭을 포함해 어느 탭도 로그인 화면으로 가지 않고 세션 표식이 남아 있다
    await tab0.waitForTimeout(1000);
    for (const tab of [tab0, tab1, tab2]) {
      expect(new URL(tab.url()).pathname).not.toBe('/login');
    }
    expect(await tab0.evaluate(() => localStorage.getItem('hasSession'))).toBe('true');
  });
});
