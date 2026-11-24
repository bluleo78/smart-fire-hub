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
import { expect, test } from '../../fixtures/auth.fixture';

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
});
