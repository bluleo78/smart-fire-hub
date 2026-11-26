/**
 * 세션 만료 안내 (WD-830).
 *
 * <p>배경: 세션 표식(hasSession)이 있는데 부팅 refresh 가 401 로 거절되면 앱은 아무 설명 없이 로그인 화면으로
 * 보냈다. 이제 refresh 가 401 일 때만 로그인 화면에 「세션 만료」 경고를 한 번 보인다. 서버 장애(5xx)·
 * 네트워크 오류·첫 방문·refresh 이후 단계(/users/me)의 401 이 아닌 실패는 만료가 아니므로 안내하지 않는다
 * (/users/me 의 401 은 인터셉터의 refresh 를 거치고 그 refresh 가 401 이면 진짜 만료다).
 */
import type { Page } from '@playwright/test';

import { setupAdminAuth, setupRoleDetailMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, jwtFor, MOCK_TOKEN_RESPONSE, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

const EXPIRED_TEXT = '세션이 만료되어 다시 로그인해 주세요';
const REFRESH_URL = '/api/v1/auth/refresh';

/** 만료 안내 토스트 로케이터. */
function expiredToast(page: Page) {
  return page.locator('[data-sonner-toast]').filter({ hasText: EXPIRED_TEXT });
}

/** 렌더 프레임 두 번을 기다린다 — 마운트 effect 가 띄우는 토스트가 있다면 그 사이에 나타난다. */
function nextFrames(page: Page) {
  return page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/** /auth/refresh 요청·완료(성공·실패·중단) 횟수를 센다 — 고정 대기 대신 refresh 가 끝난 시점을 기다리기 위해. */
function watchRefresh(page: Page) {
  const state = { requested: 0, settled: 0 };
  const isRefresh = (url: string) => url.includes(REFRESH_URL);
  page.on('request', (r) => { if (isRefresh(r.url())) state.requested += 1; });
  page.on('requestfinished', (r) => { if (isRefresh(r.url())) state.settled += 1; });
  page.on('requestfailed', (r) => { if (isRefresh(r.url())) state.settled += 1; });
  return state;
}

/** 로그인 화면에서 만료 안내가 뜨지 않았음을 단언한다(프레임 두 번 뒤에도 0건). */
async function expectNoNotice(page: Page) {
  await expect(page.getByLabel('아이디 (이메일)')).toBeVisible();
  await nextFrames(page);
  await expect(expiredToast(page)).toHaveCount(0);
}

test.describe('세션 만료 안내 (WD-830)', () => {
  test('부팅 refresh 가 401 이면 로그인 화면에 만료 안내가 한 번 보인다', async ({ authenticatedPage: page }) => {
    const refresh = watchRefresh(page);
    await mockApi(page, 'POST', REFRESH_URL, {}, { status: 401 });

    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
    await expect(expiredToast(page)).toBeVisible();
    await expect(expiredToast(page)).toHaveCount(1);
    expect(refresh.requested).toBe(1);

    // 새로고침: 세션 표식이 지워졌으니 refresh 를 다시 부르지 않고, 안내도 다시 뜨지 않는다
    await page.reload();
    await expectNoNotice(page);
    expect(refresh.requested).toBe(1);
  });

  test('세션 표식이 남은 채 /login 으로 바로 들어와 refresh 가 401 이면 안내가 한 번 보인다', async ({ authenticatedPage: page }) => {
    // refresh 응답을 늦춰 LoginPage 마운트가 부팅 인증 완료보다 먼저 오게 한다 — 실제 네트워크에서 흔한 순서(WD-830)
    await mockApi(page, 'POST', REFRESH_URL, {}, { status: 401, delayMs: 1000 });

    await page.goto('/login');
    await expect(expiredToast(page)).toBeVisible();
    await expect(expiredToast(page)).toHaveCount(1);
  });

  test('첫 방문(세션 표식 없음)은 안내가 없다', async ({ authMockedPage: page }) => {
    const refresh = watchRefresh(page);
    await page.goto('/login');
    await expectNoNotice(page);
    expect(refresh.requested).toBe(0);
  });

  test('부팅 refresh 가 500 이면 안내가 없다', async ({ authenticatedPage: page }) => {
    const refresh = watchRefresh(page);
    await mockApi(page, 'POST', REFRESH_URL, {}, { status: 500 });

    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    await expectNoNotice(page);
  });

  test('refresh 는 됐지만 /users/me 가 실패하면 만료 안내가 없다', async ({ authenticatedPage: page }) => {
    const refresh = watchRefresh(page);
    await mockApi(page, 'GET', '/api/v1/users/me', {}, { status: 500 });

    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    await expectNoNotice(page);
  });

  // 끝 슬래시도 라우터는 같은 가입 화면으로 그린다 — 안내를 맡아 두지 않아야 한다(WD-830 리뷰)
  for (const path of ['/signup', '/signup/']) {
    test(`${path} 에서 부팅 refresh 가 401 이어도 이후 로그인 화면에 안내가 없다`, async ({ authenticatedPage: page }) => {
      const refresh = watchRefresh(page);
      await mockApi(page, 'POST', REFRESH_URL, {}, { status: 401 });

      await page.goto(path);
      await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
      // 앱 안에서 로그인 링크로 이동 — 안내를 맡아 두지 않았으니 보이지 않는다
      await page.getByRole('link', { name: /로그인/ }).first().click();
      await expectNoNotice(page);
    });
  }
});

/**
 * 정상 부팅 뒤 사용 중 상황을 만든다 (WD-830).
 *
 * <p>홈이 보인 다음 모든 API 를 401 로, refresh 를 주어진 응답(상태 코드 또는 중단)으로 바꾸고, SPA 이동(풀 리로드 아님 —
 * 부팅 refresh 와 구분)으로 데이터셋 목록 요청을 일으킨다. 요청 401 → client.ts 의 refresh → 실패 → /login 하드 리로드까지 기다린다.
 */
async function expireMidSession(page: Page, refresh: { status: number } | 'abort') {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();

  // 로그인 화면이 부르는 가입 가능 여부는 제외 — 이것까지 401 이면 로그인 화면에서 또 refresh → 리로드가 반복된다
  await page.route('**/api/v1/**', (route) =>
    route.request().url().includes('/auth/signup-status')
      ? route.fallback()
      : route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }),
  );
  if (refresh === 'abort') await page.route(`**${REFRESH_URL}`, (route) => route.abort());
  else await mockApi(page, 'POST', REFRESH_URL, {}, refresh);

  await page.evaluate(() => {
    history.pushState({}, '', '/data/datasets');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await expect(page).toHaveURL(/\/login$/);
}

/**
 * 역할 상세 화면(WD-1122) — 이름 칸을 고치면 react-hook-form 이 dirty 가 되어 `useUnsavedChangesGuard` 가 떠나기 확인 가드를
 * 레지스트리에 **등록**한다. 직접 단 beforeunload 리스너는 레지스트리에 없어 가드로 보이지 않으므로,
 * 표식이 「가드가 걸린 탭」을 가리는 시험은 이 실제 화면의 폼이 필요하다. 저장 버튼은 깨끗해도 눌러지므로 같은 화면 요청을 만든다.
 */
const ROLE_PATH = '/admin/roles/3';
const ROLE_API_PATH = '/api/v1/roles/3';

/** 역할 상세 화면을 연다 — 이름 칸은 EDITOR 로 시작한다(setupRoleDetailMocks). */
async function openRoleEditor(page: Page) {
  await setupAdminAuth(page);
  await setupSecurityLevelMocks(page);
  await setupRoleDetailMocks(page, 3, false);
  await mockApi(page, 'GET', '/api/v1/roles/3/clearance', { roleId: 3, securityLevelId: 2, userCount: 14, fixed: false });
  await mockApi(page, 'POST', '/api/v1/auth/logout', {});
  await page.goto(ROLE_PATH);
  await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');
}

/** 역할 이름 칸 — 값을 바꾸면 dirty(가드 등록), 처음 값 EDITOR 로 되돌리면 깨끗(가드 해제)이다. */
const roleNameInput = (page: Page) => page.getByLabel('역할 이름');

/** 역할 정보 카드의 저장 버튼 — 열람 등급 카드에도 「저장」이 있어 이름 입력란이 든 폼으로 좁힌다. */
const roleInfoSave = (page: Page) =>
  page.locator('form').filter({ has: page.getByLabel('역할 이름') }).getByRole('button', { name: '저장' });

/** 떠나기 확인(beforeunload) 창을 세고 모두 취소한다 — 사용자가 그 화면에 남기로 한 것이다. */
function dismissDialogs(page: Page) {
  const state = { count: 0 };
  page.on('dialog', (dialog) => {
    state.count += 1;
    void dialog.dismiss();
  });
  return state;
}

/**
 * 이 탭이 `hasSession` storage 이벤트를 받았는지 조건으로 알기 위한 표시를 단다 (WD-1122) — 앱의 리스너보다 늦게 달려
 * 앱 처리가 끝난 뒤에 불린다. 다른 탭의 로그인 신호가 이 탭에 닿았는지 고정 대기 없이 기다리는 데 쓴다.
 * removalOnly 가 true 면 삭제(`newValue === null`, 다른 탭 로그아웃) 이벤트만 따로 표시한다 — 앞선 설정 이벤트와 구분하기 위해서다.
 */
async function watchHasSessionStorageEvent(page: Page, removalOnly = false) {
  await page.evaluate((removal) => {
    const flags = window as unknown as Record<string, boolean>;
    const flag = removal ? '__storageRemovalSeen' : '__storageSeen';
    window.addEventListener('storage', (e) => {
      if (e.key === 'hasSession' && (!removal || e.newValue === null)) flags[flag] = true;
    });
  }, removalOnly);
}

/** watchHasSessionStorageEvent 의 표시가 켜질 때까지 기다린다 — 이벤트 처리가 끝난 뒤에 다음 요청을 만들기 위해서다. */
async function waitHasSessionStorageEvent(page: Page, removalOnly = false) {
  await page.waitForFunction(
    (removal) => (window as unknown as Record<string, boolean>)[removal ? '__storageRemovalSeen' : '__storageSeen'] === true,
    removalOnly,
  );
}

/** 역할 저장(PUT) 요청·완료 횟수 — 같은 화면 요청이 실제로 나가 응답까지 끝났는지 조건으로 기다리기 위한 계수기. */
function watchRolePuts(page: Page) {
  const state = { requested: 0, settled: 0 };
  const isRolePut = (r: { url(): string; method(): string }) =>
    r.method() === 'PUT' && new URL(r.url()).pathname === ROLE_API_PATH;
  page.on('request', (r) => { if (isRolePut(r)) state.requested += 1; });
  page.on('requestfinished', (r) => { if (isRolePut(r)) state.settled += 1; });
  page.on('requestfailed', (r) => { if (isRolePut(r)) state.settled += 1; });
  return state;
}

/** 사용 중 세션 만료로 만든다 — 역할 저장은 401, 갱신도 늘 401(그 밖의 요청은 그대로). */
async function expireSession(page: Page) {
  await page.route(
    (url) => url.pathname === ROLE_API_PATH,
    (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      return route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
    },
  );
  await page.route(`**${REFRESH_URL}`, (route) =>
    route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }),
  );
}

test.describe('사용 중 세션 만료 안내 (WD-830)', () => {
  test('사용 중 refresh 가 401 이면 로그인 화면에 만료 안내가 한 번 보인다', async ({ authenticatedPage: page }) => {
    await expireMidSession(page, { status: 401 });
    await expect(page.getByLabel('아이디 (이메일)')).toBeVisible();
    await expect(expiredToast(page)).toBeVisible();
    // 여러 요청이 동시에 401 이어도 안내는 한 번
    await expect(expiredToast(page)).toHaveCount(1);
  });

  test('사용 중 refresh 가 네트워크 오류면 만료 안내가 없다', async ({ authenticatedPage: page }) => {
    const refresh = watchRefresh(page);
    await expireMidSession(page, 'abort');
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    await expectNoNotice(page);
  });

  for (const stale of [false, true]) {
    test(`${stale ? '안내가 소비되지 않은 채 남아도 ' : ''}직접 로그아웃 뒤에는 만료 안내가 없다`, async ({ authenticatedPage: page }) => {
      const refresh = watchRefresh(page);
      await mockApi(page, 'POST', '/api/v1/auth/logout', {});
      await page.goto('/');
      await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();
      // 소비되지 않고 남은 안내를 흉내 낸다
      if (stale) await page.evaluate((text) => sessionStorage.setItem('loginNotice', text), EXPIRED_TEXT);
      const before = refresh.requested; // 정상 부팅의 refresh 는 제외 — 로그아웃 뒤로는 없어야 한다
      await page.getByText('테스트 사용자', { exact: true }).click();
      await page.getByRole('menuitem', { name: '로그아웃' }).click();
      await expectNoNotice(page);
      expect(refresh.requested).toBe(before);
    });
  }

  test('로그아웃 때 떠나기 확인을 취소한 뒤 다시 로그아웃하면 로그인 화면으로 간다 (WD-830 리뷰)', async ({ authenticatedPage: page }) => {
    // 실제 dirty 폼(레지스트리에 등록되는 가드)으로 떠나기 확인을 건다 (WD-1142) — 직접 단 beforeunload 리스너는 레지스트리에 없다.
    // 첫 확인은 취소한다
    await openRoleEditor(page);
    await roleNameInput(page).fill('EDITOR_X');
    const dialogs = dismissDialogs(page);
    const logout = async () => {
      await page.getByText('관리자', { exact: true }).click();
      await page.getByRole('menuitem', { name: '로그아웃' }).click();
    };

    await logout();
    await expect.poll(() => dialogs.count).toBe(1);
    // 취소했으니 머문다
    await expect(page).toHaveURL(/\/admin\/roles\/3$/);

    // 변경을 버린 셈으로 가드를 풀고 다시 로그아웃하면 이번에는 로그인 화면으로 간다 — 종료 표시가 남아 조용히 막히지 않는다
    await roleNameInput(page).fill('EDITOR');
    // 취소로 남아 있을 수 있는 사용자 메뉴를 닫고 다시 연다
    await page.keyboard.press('Escape');
    await expect(page.getByRole('menu')).toHaveCount(0);
    await logout();
    await expectNoNotice(page);
    await expect(page).toHaveURL(/\/login$/);
    // 확인 창은 처음 한 번뿐이다 — 가드를 푼 뒤 로그아웃은 확인 없이 간다 (WD-1142)
    expect(dialogs.count).toBe(1);
  });

  test('만료 이동 때 떠나기 확인을 취소한 뒤 직접 로그아웃하면 만료 안내 없이 로그인 화면으로 간다 (WD-830 리뷰)', async ({
    authenticatedPage: page,
  }) => {
    // 실제 dirty 폼(레지스트리에 등록되는 가드)으로 시험한다 (WD-1142) — 직접 단 beforeunload 리스너는 레지스트리에 없어
    // 가드로 보이지 않으므로 같은 화면 배경 요청이 이동을 다시 거는 경로까지 실제로 닫혔는지 확인되지 않았다.
    await openRoleEditor(page);
    await roleNameInput(page).fill('EDITOR_X');
    const dialogs = dismissDialogs(page);
    const refresh = watchRefresh(page);
    // 사용 중 만료 — 모든 API 401, refresh 도 401.
    // 첫 refresh 401(=만료) 뒤로는 API 를 네트워크 오류로 끊는다. react-query 재시도(1회·1초 뒤, src/main.tsx)가
    // 다시 401→refresh 401 로 두 번째 자동 만료 이동을 일으키면, 가드를 푼 뒤 로그아웃을 누르기 전에
    // 그 이동이 먼저 /login 으로 보내 버리는 겨루기가 생긴다(WD-1078).
    let sessionGone = false;
    await page.route('**/api/v1/**', (route) => {
      const url = route.request().url();
      if (url.includes('/auth/signup-status') || url.includes('/auth/logout')) return route.fallback();
      if (sessionGone) return route.abort();
      return route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
    });
    // catch-all 뒤에 등록해야 이 라우트가 먼저 걸린다(Playwright 는 나중 라우트부터). 첫 refresh 만 401(=만료)이다.
    // 끊기 직전 401 을 받은 요청의 뒤늦은 refresh 는 응답하지 않고 묶는다 — 401 이든 네트워크 오류든 실패하면
    // runRefresh(src/api/client.ts)가 다시 이동해 같은 겨루기가 된다. 묶인 동안 다른 401 은 갱신 큐에서 기다리므로
    // 로그아웃은 위 mockApi(200)로 받는다. 두 번째 자동 만료 이동 자체는 WD-1082.
    await page.route(`**${REFRESH_URL}`, (route) => {
      if (sessionGone) return;
      sessionGone = true;
      return route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
    });

    // 저장 — 401 → 갱신도 401 → 만료 이동의 떠나기 확인이 뜬다. 취소했으니 그 화면에 남는다.
    await roleInfoSave(page).click();
    await expect.poll(() => dialogs.count).toBe(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    // 취소했으니 머문다 — 만료 안내는 이미 맡겨져 있다
    await expect(page).not.toHaveURL(/\/login$/);

    // 직접 로그아웃 — 다시 이동하고, 앞선 만료 안내는 남지 않는다
    // 지울 안내가 실제로 있어야 아래 「안내 없음」 단언이 의미 있다(헛통과 방지, WD-1078)
    expect(await page.evaluate(() => sessionStorage.getItem('loginNotice'))).toBe(EXPIRED_TEXT);
    // 변경을 버려(처음 값으로 되돌려) 가드를 푼다
    await roleNameInput(page).fill('EDITOR');
    await page.getByText('관리자', { exact: true }).click();
    await page.getByRole('menuitem', { name: '로그아웃' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await expectNoNotice(page);
    // 확인 창은 처음 한 번뿐이다 — 실제 폼에서도 두 번 뜨지 않는다 (WD-1142)
    expect(dialogs.count).toBe(1);
  });

  test('세션 만료 뒤 재시도로 떠나기 확인이 다시 뜨지 않는다 (WD-1082)', async ({ authenticatedPage: page }) => {
    // 실제 dirty 폼(레지스트리에 등록되는 가드)으로 시험한다 — 직접 단 beforeunload 리스너는 레지스트리에 없어
    // WD-1122 의 「가드가 풀린 탭」으로 보이므로, 같은 화면 재요청이 이동을 다시 거는 것이 맞다(별도 시험이 그걸 검증).
    await openRoleEditor(page);
    await roleNameInput(page).fill('EDITOR_X');
    const dialogs = dismissDialogs(page);
    const refresh = watchRefresh(page);
    const puts = watchRolePuts(page);
    await expireSession(page);

    // 저장 — 401 → 갱신도 401 → 만료 이동의 떠나기 확인이 뜬다. 취소했으니 그 화면에 남는다.
    await roleInfoSave(page).click();
    await expect.poll(() => dialogs.count).toBeGreaterThanOrEqual(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    await expect(page).not.toHaveURL(/\/login$/);

    // 같은 화면에서의 재요청 한 건 — 폼은 여전히 dirty 라 가드가 걸려 있다(주소의 pathname 도 그대로다).
    // 표식은 HTTP 갱신만 막으므로 이 요청은 갱신을 만들지 않고, 로그인 이동도 다시 건드리지 않는다(WD-1082 반려).
    await roleInfoSave(page).click();
    await expect.poll(() => puts.settled).toBeGreaterThanOrEqual(2);
    // 재요청이 끝난 뒤 렌더 프레임 두 번 — 두 번째 만료 이동·확인 창이 있었다면 그 사이 나타난다
    await nextFrames(page);

    // 갱신은 한 번만 요청한다 — 표식이 두 번째 HTTP 갱신을 만들지 않는다
    expect(refresh.requested).toBe(1);
    // 확인 창은 처음 한 번뿐이다 — 취소한 사용자에게 다시 뜨지 않는다
    expect(dialogs.count).toBe(1);
    await expect(page).not.toHaveURL(/\/login$/);
  });

  test('역할 편집 화면에서 모든 GET 이 401 이어도 query 재시도로 떠나기 확인이 다시 뜨지 않는다 (WD-1142)', async ({
    authenticatedPage: page,
  }) => {
    // 저장(PUT)이 아닌 GET 쿼리가 401 을 맞아도 react-query 가 재시도하지 않아야 한다(main.tsx — WD-1082, WD-1142) —
    // 재시도하면 같은 화면에서 만료 이동·확인 창이 한 번 더 걸린다. 실제 dirty 폼으로 가드를 건다.
    await openRoleEditor(page);
    await roleNameInput(page).fill('EDITOR_X');
    const card = page.getByTestId('role-clearance-card');
    await mockApi(page, 'POST', '/api/v1/roles/3/clearance/preview', {
      callerLostDatasetCount: 0,
      topLevelRoleCountAfter: 1,
      roleUserCount: 14,
    });
    await mockApi(page, 'PUT', '/api/v1/roles/3/clearance', {}, { status: 204 });
    await card.getByRole('radio', { name: /민감/ }).check();
    await expect(card.getByRole('button', { name: '저장' })).toBeEnabled();
    const dialogs = dismissDialogs(page);
    const refresh = watchRefresh(page);
    // 401 을 받은 주소를 모아 두고, 그 뒤에 같은 주소가 다시 요청되면 재시도로 센다.
    // (화면 로드가 같은 주소를 두 번 부르는 정상 중복은 401 전이라 걸리지 않는다.)
    const expired = new Set<string>();
    const retried: string[] = [];
    const isApiGet = (r: { url(): string; method(): string }) =>
      r.method() === 'GET' && r.url().includes('/api/v1/');
    page.on('request', (r) => { if (isApiGet(r) && expired.has(r.url())) retried.push(r.url()); });
    page.on('response', (res) => {
      if (isApiGet(res.request()) && res.status() === 401) expired.add(res.url());
    });
    // 이 화면의 GET 은 모두 401, 갱신도 401 — 등급 저장(PUT)은 성공하고 그 뒤 무효화된 쿼리 GET 이 401 을 맞는다
    await page.route('**/api/v1/**', (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      return route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
    });
    await page.route(`**${REFRESH_URL}`, (route) =>
      route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }),
    );

    await card.getByRole('button', { name: '저장' }).click();
    await expect.poll(() => dialogs.count).toBe(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    await expect.poll(() => expired.size).toBeGreaterThanOrEqual(1);
    // react-query 의 재시도 지연(기본 1초)을 넘겨 기다린다 — 재시도가 있었다면 그 사이 두 번째 요청·확인 창이 나타난다(없음을 보는 단언)
    await page.waitForTimeout(1500);
    await nextFrames(page);
    // 401 GET 은 재시도되지 않는다 — 401 을 받은 각 주소는 그 첫 401 뒤에 다시 요청되지 않아야 한다(WD-1142)
    expect(retried).toEqual([]);
    expect(refresh.requested).toBe(1);
    expect(dialogs.count).toBe(1);
    await expect(page).not.toHaveURL(/\/login$/);
  });

  test('만료된 탭은 다른 탭에서 다른 사용자가 로그인하면 그 사용자로 되살아나지 않고 홈으로 다시 연다 (WD-1122)', async ({
    authenticatedPage: tabA,
  }) => {
    // 이 탭의 사용자는 1 — 부팅 refresh 가 sub 1 토큰을 준다(JWT 모양이어야 client.ts 가 사용자를 알아본다)
    await mockApi(tabA, 'POST', REFRESH_URL, { ...MOCK_TOKEN_RESPONSE, accessToken: jwtFor('1') });
    await openRoleEditor(tabA);
    await roleNameInput(tabA).fill('EDITOR_X');
    const dialogs = dismissDialogs(tabA);
    const refresh = watchRefresh(tabA);
    await expireSession(tabA);

    // 만료 이동의 확인 창을 취소해 만료로 멈춘 탭이 남는다
    await roleInfoSave(tabA).click();
    await expect.poll(() => dialogs.count).toBeGreaterThanOrEqual(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    await expect(tabA).not.toHaveURL(/\/login$/);
    expect(refresh.requested).toBe(1);
    // 만료 이동이 세션 표식을 지웠다 — 다른 탭의 로그인이 표식에 변화를 주려면 지금 비어 있어야 한다
    expect(await tabA.evaluate(() => localStorage.getItem('hasSession'))).toBeNull();

    // 공유 refresh 쿠키는 이제 다른 사용자(2)의 것이다 — 갱신 응답을 sub 2 토큰으로 바꿔 둔다
    await tabA.route(`**${REFRESH_URL}`, (route) =>
      route.fulfill({ json: { ...MOCK_TOKEN_RESPONSE, accessToken: jwtFor('2') } }),
    );
    // 이 탭이 storage 이벤트를 받았는지 조건으로 알기 위한 표시를 단다(WD-1122)
    await watchHasSessionStorageEvent(tabA);

    // 다른 탭에서 사용자 2 가 로그인 — AuthContext 와 같이 `hasSession` 을 심는다(같은 오리진·localStorage → 이 탭에 storage 이벤트)
    const tabB = await tabA.context().newPage();
    await tabB.goto('/login');
    await tabB.evaluate(() => localStorage.setItem('hasSession', 'true'));
    await waitHasSessionStorageEvent(tabA);

    // 하드 이동(풀 리로드)이었는지 가리는 표시 — 리로드되면 window 변수는 사라진다
    await tabA.evaluate(() => {
      (window as unknown as { __beforeSwitch: boolean }).__beforeSwitch = true;
    });

    // 변경을 버려 가드를 풀고 요청을 만든다 — 표식이 비워졌으니 갱신이 나가고, 돌아온 토큰은 다른 사용자(2)의 것이다
    await roleNameInput(tabA).fill('EDITOR');
    await roleInfoSave(tabA).click();

    // 그 사용자로 되살아나 역할 화면을 이어 쓰지 않고, 홈으로 하드 이동해 앞 화면의 폼·캐시를 버린다
    await expect(tabA).toHaveURL((url) => url.pathname === '/');
    await expect(tabA.getByRole('heading', { name: '홈' })).toBeVisible();
    await expect(roleNameInput(tabA)).toHaveCount(0);
    // 풀 리로드로 열렸다 — 앞 화면의 window 상태가 남아 있지 않다
    expect(await tabA.evaluate(() => (window as unknown as { __beforeSwitch?: boolean }).__beforeSwitch)).toBeUndefined();
    // 홈에 만료 안내가 뜨지 않는다 — 로그인 화면으로 간 것이 아니다
    await nextFrames(tabA);
    await expect(expiredToast(tabA)).toHaveCount(0);
    // 다른 사용자의 세션 표식은 건드리지 않는다 — 로그아웃으로 흘리지 않는다
    expect(await tabA.evaluate(() => localStorage.getItem('hasSession'))).toBe('true');
    // 확인 창은 처음 한 번뿐이다 — 홈으로 다시 열 때 가드가 풀려 있어 다시 뜨지 않는다
    expect(dialogs.count).toBe(1);
  });

  test('사용자 바뀜으로 남은 탭은 다른 탭이 로그아웃하면 로그인 화면으로 다시 연다 (WD-1142)', async ({
    authenticatedPage: tabA,
  }) => {
    await mockApi(tabA, 'POST', REFRESH_URL, { ...MOCK_TOKEN_RESPONSE, accessToken: jwtFor('1') });
    await openRoleEditor(tabA);
    await roleNameInput(tabA).fill('EDITOR_X');
    const dialogs = dismissDialogs(tabA);
    const refresh = watchRefresh(tabA);
    await expireSession(tabA);
    await roleInfoSave(tabA).click();
    await expect.poll(() => dialogs.count).toBeGreaterThanOrEqual(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);

    // 다른 사용자(2) 가 로그인 — 이 탭은 다음 갱신에서 사용자 바뀜을 알아본다
    await tabA.route(`**${REFRESH_URL}`, (route) =>
      route.fulfill({ json: { ...MOCK_TOKEN_RESPONSE, accessToken: jwtFor('2') } }),
    );
    await watchHasSessionStorageEvent(tabA);
    const tabB = await tabA.context().newPage();
    await tabB.goto('/login');
    await tabB.evaluate(() => localStorage.setItem('hasSession', 'true'));
    await waitHasSessionStorageEvent(tabA);

    // 폼이 여전히 dirty 라 홈 이동의 확인 창을 취소하면 탭이 사용자 바뀜 표식만 든 채 남는다
    const before = dialogs.count;
    await roleInfoSave(tabA).click();
    await expect.poll(() => dialogs.count).toBeGreaterThan(before);
    await expect(tabA).not.toHaveURL(/\/login$/);

    // 가드를 풀고(변경 되돌림), 다른 탭이 실제로 로그아웃한다(표식 제거 → storage 이벤트) — 토큰은 이미 없지만 표식이 있어 로그인 화면으로 다시 연다
    await roleNameInput(tabA).fill('EDITOR');
    await tabB.evaluate(() => localStorage.removeItem('hasSession'));
    await expect(tabA).toHaveURL(/\/login$/);
  });

  test('만료 이동을 취소한 뒤 같은 화면 배경 요청에는 확인이 다시 뜨지 않고, 다른 화면으로 옮기면 로그인 화면으로 간다 (WD-1160)', async ({
    authenticatedPage: page,
  }) => {
    await openRoleEditor(page);
    await roleNameInput(page).fill('EDITOR_X');
    // 만료 이동의 beforeunload 확인 창(네이티브)은 취소한다 — 이후 사용자의 화면 이동은 앱 안 확인 창(alertdialog)으로 묻는다
    const dialogs = dismissDialogs(page);
    const refresh = watchRefresh(page);
    const puts = watchRolePuts(page);
    await expireSession(page);

    // 저장 — 401 → 갱신도 401 → 만료 이동의 떠나기 확인이 뜨고 취소한다
    await roleInfoSave(page).click();
    await expect.poll(() => dialogs.count).toBe(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    await expect(page).not.toHaveURL(/\/login$/);

    // 같은 화면 배경 요청 — 확인 창이 다시 뜨지 않는다
    await roleInfoSave(page).click();
    await expect.poll(() => puts.settled).toBeGreaterThanOrEqual(2);
    await nextFrames(page);
    expect(dialogs.count).toBe(1);
    await expect(page).not.toHaveURL(/\/login$/);

    // 다른 화면의 요청은 401 이 되게 한다 — 화면을 옮긴 뒤의 요청이 로그인 이동을 다시 걸어야 한다
    await page.route('**/api/v1/**', (route) =>
      route.request().url().includes('/auth/signup-status')
        ? route.fallback()
        : route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }),
    );
    // 사이드바 링크로 다른 화면 이동 — 가드의 확인 창이 뜨고 「이탈」을 누르면 옮겨 간다
    await page.getByRole('link', { name: '사용자 관리' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL(/\/login$/);
    // 네이티브 확인 창은 처음 한 번뿐이다 — 이동·로그인 화면 하드 이동에 다시 뜨지 않는다
    expect(dialogs.count).toBe(1);
    // HTTP 갱신은 처음 한 번뿐이다
    expect(refresh.requested).toBe(1);
  });

  test('사용자 바뀜 뒤 떠나기 확인을 취소한 탭은 다른 탭이 로그아웃해도 같은 화면에서 확인이 다시 뜨지 않는다 (WD-1160)', async ({
    authenticatedPage: tabA,
  }) => {
    await mockApi(tabA, 'POST', REFRESH_URL, { ...MOCK_TOKEN_RESPONSE, accessToken: jwtFor('1') });
    await openRoleEditor(tabA);
    await roleNameInput(tabA).fill('EDITOR_X');
    const dialogs = dismissDialogs(tabA);
    const refresh = watchRefresh(tabA);
    await expireSession(tabA);
    await roleInfoSave(tabA).click();
    await expect.poll(() => dialogs.count).toBeGreaterThanOrEqual(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);

    // 다른 사용자(2) 가 로그인해 이 탭이 사용자 바뀜 표식을 든 채 남는다
    await tabA.route(`**${REFRESH_URL}`, (route) =>
      route.fulfill({ json: { ...MOCK_TOKEN_RESPONSE, accessToken: jwtFor('2') } }),
    );
    await watchHasSessionStorageEvent(tabA);
    const tabB = await tabA.context().newPage();
    await tabB.goto('/login');
    await tabB.evaluate(() => localStorage.setItem('hasSession', 'true'));
    await waitHasSessionStorageEvent(tabA);
    const before = dialogs.count;
    await roleInfoSave(tabA).click();
    await expect.poll(() => dialogs.count).toBeGreaterThan(before);
    await expect(tabA).not.toHaveURL(/\/login$/);

    // 폼은 여전히 dirty(가드 유지) — 다른 탭 로그아웃의 storage 이벤트가 이 탭에 닿은 것을 조건으로 알고 기다린다
    const afterCancel = dialogs.count;
    await watchHasSessionStorageEvent(tabA, true);
    await tabB.evaluate(() => localStorage.removeItem('hasSession'));
    await waitHasSessionStorageEvent(tabA, true);
    await nextFrames(tabA);

    // 같은 화면에서 이미 취소했으니 확인 창이 다시 뜨지 않고 화면에 머문다
    expect(dialogs.count).toBe(afterCancel);
    await expect(tabA).not.toHaveURL(/\/login$/);
    // 앱 안 떠나기 확인 창(alertdialog)도 다시 열리지 않았다
    await expect(tabA.getByRole('alertdialog')).toHaveCount(0);
  });

  test('역할 상세 저장 중 다른 사용자 로그인이 감지되면 일반 저장 실패 토스트를 띄우지 않는다 (WD-1142)', async ({
    authenticatedPage: page,
  }) => {
    // 이 탭의 사용자는 1 — 부팅 refresh 가 sub 1 토큰을 준다
    await mockApi(page, 'POST', REFRESH_URL, { ...MOCK_TOKEN_RESPONSE, accessToken: jwtFor('1') });
    await openRoleEditor(page);
    await roleNameInput(page).fill('EDITOR_X');
    const dialogs = dismissDialogs(page);
    const refresh = watchRefresh(page);
    // 저장 요청은 붙잡아 둔다 — 저장 중 입력을 더 고쳐야 RoleDetailPage 가 오류를 직접 토스트(extractApiError)로 보인다(WD-866).
    // 사용자 바뀜이면 일반 실패 문구가 아니라 바뀜 안내여야 한다(WD-1142).
    let releasePut: () => void = () => {};
    const putGate = new Promise<void>((resolve) => {
      releasePut = resolve;
    });
    await page.route(
      (url) => url.pathname === ROLE_API_PATH,
      async (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        await putGate;
        return route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
      },
    );
    // 공유 refresh 쿠키는 이제 다른 사용자(2)의 것이다 — 401 뒤의 갱신이 sub 2 토큰을 돌려준다
    await page.route(`**${REFRESH_URL}`, (route) =>
      route.fulfill({ json: { ...MOCK_TOKEN_RESPONSE, accessToken: jwtFor('2') } }),
    );

    await roleInfoSave(page).click();
    await roleNameInput(page).fill('EDITOR_XY');
    releasePut();

    // 홈으로 다시 여는 떠나기 확인을 취소해 남은 탭에 머문다
    await expect.poll(() => dialogs.count).toBe(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    // 호출부가 직접 띄우는 토스트가 사용자 바뀜 안내로 나온다 — 실제로 그 직접 토스트 경로가 탔다는 증거다
    const toasts = page.locator('[data-sonner-toast]');
    await expect(toasts.filter({ hasText: '다른 사용자로 로그인되어 화면을 다시 엽니다.' })).toHaveCount(1);
    // 일반 저장 실패 토스트는 없다 — 곧 다시 열릴 화면에 「저장에 실패」로 읽히지 않는다
    await nextFrames(page);
    await expect(toasts.filter({ hasText: '실패' })).toHaveCount(0);
    await expect(page).not.toHaveURL(/\/login$/);
  });

  test('떠나기 확인을 취소한 뒤 가드를 풀고 이어진 요청은 로그인 화면으로 보낸다 (WD-1082 반려)', async ({
    authenticatedPage: page,
  }) => {
    // 실제 dirty 폼(레지스트리에 등록되는 가드)으로 시험한다 — 직접 단 beforeunload 리스너는 레지스트리에 없어
    // 레지스트리가 가드로 보지 않아, 같은 화면 배경 요청이 가드 해제 전에 이동을 다시 걸어 확인 창이 두 번 뜰 수 있다(WD-1122 최종 리뷰).
    await openRoleEditor(page);
    await roleNameInput(page).fill('EDITOR_X');
    const dialogs = dismissDialogs(page);
    const refresh = watchRefresh(page);
    await expireSession(page);

    // 첫 확인 창을 취소해 이 화면에 남는다
    await roleInfoSave(page).click();
    await expect.poll(() => dialogs.count).toBeGreaterThanOrEqual(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    // 취소했으니 머문다
    await expect(page).not.toHaveURL(/\/login$/);
    expect(refresh.requested).toBe(1);

    // 변경을 버려(처음 값으로 되돌려) 가드를 풀고 이어서 요청한다 — 이어지는 요청은 만료 표식으로 곧바로 거절되지만,
    // 표식이 로그인 이동까지 막지 않으므로 /login 으로 가야 한다(죽은 화면에 남지 않음, WD-830 이동은 늘 다시 한다)
    await roleNameInput(page).fill('EDITOR');
    await roleInfoSave(page).click();

    await expect(page).toHaveURL(/\/login$/);
    // 로그인 화면에 만료 안내가 보인다(WD-1122 — 가드가 풀린 같은 화면의 다음 요청도 안내와 함께 보낸다)
    await expect(expiredToast(page)).toBeVisible();
    // HTTP 갱신은 처음 한 번뿐이다 — 표식이 갱신 요청을 다시 만들지 않는다
    expect(refresh.requested).toBe(1);
    // 가드를 푼 뒤에는 확인 창 없이 간다
    expect(dialogs.count).toBe(1);
  });

  test('만료로 멈춘 뒤 다른 탭이 다시 로그인하면 이 탭의 갱신이 살아난다 (WD-1082 반려)', async ({ authenticatedPage: tabA }) => {
    // 실제 dirty 폼으로 시험한다 — 손수 단 beforeunload 는 레지스트리에 없어 배경 요청이 이동을 다시 걸 수 있다(WD-1122 최종 리뷰).
    await openRoleEditor(tabA);
    await roleNameInput(tabA).fill('EDITOR_X');
    const dialogs = dismissDialogs(tabA);
    const refresh = watchRefresh(tabA);
    await expireSession(tabA);

    // 만료 이동의 확인 창을 취소해 이 탭이 남는다
    await roleInfoSave(tabA).click();
    await expect.poll(() => dialogs.count).toBeGreaterThanOrEqual(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    await expect(tabA).not.toHaveURL(/\/login$/);
    expect(refresh.requested).toBe(1);
    // 만료 이동이 세션 표식을 지웠다 — 다른 탭의 로그인이 표식에 변화를 주려면 지금 비어 있어야 한다
    expect(await tabA.evaluate(() => localStorage.getItem('hasSession'))).toBeNull();

    // 다른 탭이 다시 로그인했으니 공유 refresh 쿠키가 살아 있다 — 갱신 응답을 성공으로 먼저 바꿔 둔다.
    // 역할 저장은 처음 한 번만 401(만료된 토큰)이고 갱신 뒤 재발송은 성공한다.
    await tabA.route(`**${REFRESH_URL}`, (route) => route.fulfill({ json: MOCK_TOKEN_RESPONSE }));
    let putCalls = 0;
    await tabA.route(
      (url) => url.pathname === ROLE_API_PATH,
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        putCalls += 1;
        if (putCalls === 1) return route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
        return route.fulfill({ json: { id: 3, name: 'EDITOR_X' } });
      },
    );
    // 이 탭이 storage 이벤트를 받았는지 조건으로 알기 위한 표시를 단다(WD-1122)
    await watchHasSessionStorageEvent(tabA);

    // 다른 탭 — 로그인은 AuthContext 에서 그대로 `localStorage.setItem('hasSession', 'true')` 다.
    // 같은 오리진·같은 localStorage 를 쓰므로 여기서 넣으면 이 탭에 storage 이벤트가 간다.
    const tabB = await tabA.context().newPage();
    await tabB.goto('/login');
    await tabB.evaluate(() => localStorage.setItem('hasSession', 'true'));
    // storage 이벤트 처리가 끝난 뒤에 다음 요청을 만든다 — 표식이 비워지기 전 요청이 섞이면 시험이 시각에 따라 갈린다
    await waitHasSessionStorageEvent(tabA);

    // 다른 탭의 로그인으로 표식이 비워졌다면, 이 탭의 갱신은 다시 시도되어 살아난다(WD-1082 반려 · 검토자 지적).
    // 저장 요청을 하나 더 만들어 갱신 재시도를 결정적으로 일으킨다(폼은 여전히 dirty — 가드 유지).
    await roleInfoSave(tabA).click();
    await expect.poll(() => refresh.requested, { timeout: 10000 }).toBeGreaterThanOrEqual(2);

    // 갱신이 다시 살아났으니 로그인 이동·확인 창은 없다 — 이 탭은 그대로 쓸 수 있다
    // 갱신 요청이 끝난 뒤 렌더 프레임 두 번 — 조건(갱신 완료)을 기다린 뒤 이동·확인 창이 없는지 본다(고정 대기 대신, WD-1122)
    await expect.poll(() => refresh.settled, { timeout: 10000 }).toBeGreaterThanOrEqual(2);
    await nextFrames(tabA);
    expect(dialogs.count).toBe(1);
    await expect(tabA).not.toHaveURL(/\/login$/);
  });
});
