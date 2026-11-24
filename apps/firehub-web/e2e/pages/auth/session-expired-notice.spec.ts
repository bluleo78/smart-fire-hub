/**
 * 세션 만료 안내 (WD-830).
 *
 * <p>배경: 세션 표식(hasSession)이 있는데 부팅 refresh 가 401 로 거절되면 앱은 아무 설명 없이 로그인 화면으로
 * 보냈다. 이제 refresh 가 401 일 때만 로그인 화면에 「세션 만료」 경고를 한 번 보인다. 서버 장애(5xx)·
 * 네트워크 오류·첫 방문·refresh 이후 단계(/users/me)의 401 이 아닌 실패는 만료가 아니므로 안내하지 않는다
 * (/users/me 의 401 은 인터셉터의 refresh 를 거치고 그 refresh 가 401 이면 진짜 만료다).
 */
import type { Page } from '@playwright/test';

import { mockApi } from '../../fixtures/api-mock';
import { expect, MOCK_TOKEN_RESPONSE, test } from '../../fixtures/auth.fixture';

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

/** /auth 를 뺀 API 요청 수 — 같은 화면의 재요청이 실제로 나갔는지 보기 위한 계수기(WD-1082 반려). */
function watchApiRequests(page: Page) {
  const state = { count: 0 };
  page.on('request', (r) => {
    const url = r.url();
    if (url.includes('/api/v1/') && !url.includes('/api/v1/auth/')) state.count += 1;
  });
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
    await mockApi(page, 'POST', '/api/v1/auth/logout', {});
    await page.goto('/');
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();
    // 저장하지 않은 변경이 있는 화면(useUnsavedChangesGuard)처럼 떠나기 확인을 건다 — 첫 확인은 취소한다
    await page.evaluate(() => {
      const guard = (e: BeforeUnloadEvent) => {
        e.preventDefault();
        e.returnValue = '';
      };
      (window as unknown as { __guard: typeof guard }).__guard = guard;
      window.addEventListener('beforeunload', guard);
    });
    let dialogs = 0;
    page.on('dialog', (dialog) => {
      dialogs += 1;
      void dialog.dismiss();
    });
    const logout = async () => {
      await page.getByText('테스트 사용자', { exact: true }).click();
      await page.getByRole('menuitem', { name: '로그아웃' }).click();
    };

    await logout();
    await expect.poll(() => dialogs).toBe(1);
    // 취소했으니 머문다
    await expect(page).toHaveURL(/\/$/);

    // 변경을 버린 셈으로 가드를 풀고 다시 로그아웃하면 이번에는 로그인 화면으로 간다 — 종료 표시가 남아 조용히 막히지 않는다
    await page.evaluate(() => {
      window.removeEventListener('beforeunload', (window as unknown as { __guard: EventListener }).__guard);
    });
    // 취소로 남아 있을 수 있는 사용자 메뉴를 닫고 다시 연다
    await page.keyboard.press('Escape');
    await expect(page.getByRole('menu')).toHaveCount(0);
    await logout();
    await expectNoNotice(page);
    await expect(page).toHaveURL(/\/login$/);
  });

  test('만료 이동 때 떠나기 확인을 취소한 뒤 직접 로그아웃하면 만료 안내 없이 로그인 화면으로 간다 (WD-830 리뷰)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'POST', '/api/v1/auth/logout', {});
    await page.goto('/');
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();
    await page.evaluate(() => {
      const guard = (e: BeforeUnloadEvent) => {
        e.preventDefault();
        e.returnValue = '';
      };
      (window as unknown as { __guard: typeof guard }).__guard = guard;
      window.addEventListener('beforeunload', guard);
    });
    let dialogs = 0;
    page.on('dialog', (dialog) => {
      dialogs += 1;
      void dialog.dismiss();
    });
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
    const goTo = (path: string) =>
      page.evaluate((p) => {
        history.pushState({}, '', p);
        window.dispatchEvent(new PopStateEvent('popstate'));
      }, path);

    await goTo('/data/datasets');
    await expect.poll(() => dialogs).toBeGreaterThanOrEqual(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    // 취소했으니 머문다 — 만료 안내는 이미 맡겨져 있다
    await expect(page).not.toHaveURL(/\/login$/);

    // 직접 로그아웃 — 다시 이동하고, 앞선 만료 안내는 남지 않는다
    // 지울 안내가 실제로 있어야 아래 「안내 없음」 단언이 의미 있다(헛통과 방지, WD-1078)
    expect(await page.evaluate(() => sessionStorage.getItem('loginNotice'))).toBe(EXPIRED_TEXT);
    await page.evaluate(() => {
      window.removeEventListener('beforeunload', (window as unknown as { __guard: EventListener }).__guard);
    });
    await page.getByText('테스트 사용자', { exact: true }).click();
    await page.getByRole('menuitem', { name: '로그아웃' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await expectNoNotice(page);
  });

  test('세션 만료 뒤 재시도로 떠나기 확인이 다시 뜨지 않는다 (WD-1082)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/logout', {});
    await page.goto('/');
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();

    // 떠나기 확인 가드 — 첫 확인을 취소한다
    await page.evaluate(() => {
      const guard = (e: BeforeUnloadEvent) => {
        e.preventDefault();
        e.returnValue = '';
      };
      (window as unknown as { __guard: typeof guard }).__guard = guard;
      window.addEventListener('beforeunload', guard);
    });
    let dialogs = 0;
    page.on('dialog', (dialog) => {
      dialogs += 1;
      void dialog.dismiss();
    });
    const refresh = watchRefresh(page);

    // 사용 중 세션 만료 — 모든 API 401, refresh 도 늘 401 (WD-1078 시험의 "원래 설정")
    await page.route('**/api/v1/**', (route) => {
      const url = route.request().url();
      if (url.includes('/auth/signup-status') || url.includes('/auth/logout')) return route.fallback();
      return route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
    });
    await page.route(`**${REFRESH_URL}`, (route) =>
      route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }),
    );

    const goTo = (path: string) =>
      page.evaluate((p) => {
        history.pushState({}, '', p);
        window.dispatchEvent(new PopStateEvent('popstate'));
      }, path);

    goTo('/data/datasets');
    await expect.poll(() => dialogs).toBeGreaterThanOrEqual(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    // 취소했으니 그 화면에 남는다
    await expect(page).not.toHaveURL(/\/login$/);

    // 같은 화면에서의 재요청 한 건 — 검색어를 바꿔 목록을 다시 부른다(주소의 pathname 은 그대로다).
    // 표식은 HTTP 갱신만 막으므로 이 요청은 갱신을 만들지 않고, 로그인 이동도 다시 건드리지 않는다(WD-1082 반려).
    const api = watchApiRequests(page);
    const before = api.count;
    await page.getByPlaceholder('데이터셋 검색...').fill('검색어');
    await expect.poll(() => api.count).toBeGreaterThan(before);

    // 재요청이 끝난 뒤에도 두 번째 만료 이동·두 번째 갱신이 없는지 본다
    await page.waitForTimeout(1000);

    // 갱신은 한 번만 요청한다 — 표식이 두 번째 HTTP 갱신을 만들지 않는다
    expect(refresh.requested).toBe(1);
    // 확인 창은 처음 한 번뿐이다 — 취소한 사용자에게 다시 뜨지 않는다
    expect(dialogs).toBe(1);
    await expect(page).not.toHaveURL(/\/login$/);
  });

  test('떠나기 확인을 취소한 뒤 가드를 풀고 이어진 요청은 로그인 화면으로 보낸다 (WD-1082 반려)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'POST', '/api/v1/auth/logout', {});
    await page.goto('/');
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();

    // 떠나기 확인 가드 — 첫 확인을 취소한다(사용자가 그 화면에 남기로 함)
    await page.evaluate(() => {
      const guard = (e: BeforeUnloadEvent) => {
        e.preventDefault();
        e.returnValue = '';
      };
      (window as unknown as { __guard: typeof guard }).__guard = guard;
      window.addEventListener('beforeunload', guard);
    });
    let dialogs = 0;
    page.on('dialog', (dialog) => {
      dialogs += 1;
      void dialog.dismiss();
    });
    const refresh = watchRefresh(page);

    // 사용 중 세션 만료 — 모든 API 401, refresh 도 늘 401 (WD-1078 시험의 "원래 설정")
    await page.route('**/api/v1/**', (route) => {
      const url = route.request().url();
      if (url.includes('/auth/signup-status') || url.includes('/auth/logout')) return route.fallback();
      return route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
    });
    await page.route(`**${REFRESH_URL}`, (route) =>
      route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }),
    );

    const goTo = (path: string) =>
      page.evaluate((p) => {
        history.pushState({}, '', p);
        window.dispatchEvent(new PopStateEvent('popstate'));
      }, path);

    await goTo('/data/datasets');
    await expect.poll(() => dialogs).toBeGreaterThanOrEqual(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    // 취소했으니 머문다
    await expect(page).not.toHaveURL(/\/login$/);
    expect(refresh.requested).toBe(1);

    // 변경을 버린 셈으로 가드를 풀고 다른 화면으로 간다 — 이어지는 요청은 만료 표식으로 곧바로 거절되지만,
    // 표식이 로그인 이동까지 막지 않으므로 /login 으로 가야 한다(죽은 화면에 남지 않음, WD-830 이동은 늘 다시 한다)
    await page.evaluate(() => {
      window.removeEventListener('beforeunload', (window as unknown as { __guard: EventListener }).__guard);
    });
    await goTo('/pipelines');

    await expect(page).toHaveURL(/\/login$/);
    // HTTP 갱신은 처음 한 번뿐이다 — 표식이 갱신 요청을 다시 만들지 않는다
    expect(refresh.requested).toBe(1);
    // 가드를 푼 뒤에는 확인 창 없이 간다
    expect(dialogs).toBe(1);
  });

  test('만료로 멈춘 뒤 다른 탭이 다시 로그인하면 이 탭의 갱신이 살아난다 (WD-1082 반려)', async ({ authenticatedPage: tabA }) => {
    await mockApi(tabA, 'POST', '/api/v1/auth/logout', {});
    await tabA.goto('/');
    await expect(tabA.getByRole('heading', { name: '홈' })).toBeVisible();

    // 떠나기 확인 가드 — 첫 확인을 취소해 만료 이동이 막히고 이 탭이 남는다
    await tabA.evaluate(() => {
      const guard = (e: BeforeUnloadEvent) => {
        e.preventDefault();
        e.returnValue = '';
      };
      (window as unknown as { __guard: typeof guard }).__guard = guard;
      window.addEventListener('beforeunload', guard);
    });
    let dialogs = 0;
    tabA.on('dialog', (dialog) => {
      dialogs += 1;
      void dialog.dismiss();
    });
    const refresh = watchRefresh(tabA);

    // 사용 중 세션 만료 — 모든 API 401, refresh 도 늘 401 (WD-1078 시험의 "원래 설정")
    await tabA.route('**/api/v1/**', (route) => {
      const url = route.request().url();
      if (url.includes('/auth/signup-status') || url.includes('/auth/logout')) return route.fallback();
      return route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
    });
    await tabA.route(`**${REFRESH_URL}`, (route) =>
      route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }),
    );

    await tabA.evaluate(() => {
      history.pushState({}, '', '/data/datasets');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await expect.poll(() => dialogs).toBeGreaterThanOrEqual(1);
    await expect.poll(() => refresh.settled).toBeGreaterThanOrEqual(1);
    await expect(tabA).not.toHaveURL(/\/login$/);
    expect(refresh.requested).toBe(1);
    // 만료 이동이 세션 표식을 지웠다 — 다른 탭의 로그인이 표식에 변화를 주려면 지금 비어 있어야 한다
    expect(await tabA.evaluate(() => localStorage.getItem('hasSession'))).toBeNull();

    // 다른 탭 — 로그인은 AuthContext 에서 그대로 `localStorage.setItem('hasSession', 'true')` 다.
    // 같은 오리진·같은 localStorage 를 쓰므로 여기서 넣으면 이 탭에 storage 이벤트가 간다.
    const tabB = await tabA.context().newPage();
    await tabB.goto('/login');
    await tabB.evaluate(() => localStorage.setItem('hasSession', 'true'));
    // storage 이벤트는 문서 사이로 곧바로 건너간다 — 이 탭이 표식을 비우는 사이의 여유
    await tabA.waitForTimeout(500);
    // 갱신은 아직 한 번뿐이다 — 표식이 남아 있으면 이 아래의 재요청도 갱신을 만들지 못한다
    expect(refresh.requested).toBe(1);

    // 다른 탭의 로그인으로 표식이 비워졌다면, 이 탭의 갱신은 다시 시도되어 살아난다(WD-1082 반려 · 검토자 지적)
    await tabA.route(`**${REFRESH_URL}`, (route) => route.fulfill({ json: MOCK_TOKEN_RESPONSE }));
    await tabA.getByPlaceholder('데이터셋 검색...').fill('검색어');
    await expect.poll(() => refresh.requested, { timeout: 10000 }).toBeGreaterThanOrEqual(2);

    // 갱신이 다시 살아났으니 로그인 이동·확인 창은 없다 — 이 탭은 그대로 쓸 수 있다
    await tabA.waitForTimeout(1000);
    expect(dialogs).toBe(1);
    await expect(tabA).not.toHaveURL(/\/login$/);
  });
});
