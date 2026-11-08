import type { Page, Route } from '@playwright/test';

import { createPermissions, createRoleDetail } from '../factories/admin.factory';
import { createChartListItem, createDashboardListItem, createSavedQueryList } from '../factories/analytics.factory';
import { createRole, createUserDetail } from '../factories/auth.factory';
import { setupAdminAuth, setupRoleDetailMocks, setupRoleListMocks } from '../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../fixtures/security-level.fixture';

/**
 * 상세 화면 진입 조회의 경합·출처 처리 (WD-646)
 *
 * WD-581 로 역할·사용자·대시보드 상세가 진입 조회 404 를 화면 안 「찾을 수 없음」 안내(ResourceNotFound)로 보이게 됐는데,
 * - 진입 조회 effect 에 취소가 없어, id 를 옮긴 뒤 늦게 온 이전 id 의 응답(404·성공)이 새 id 화면을 덮어썼다.
 * - 안내 문구를 effect 안에서만 지워, 없는 id 에서 다른 id 로 옮긴 첫 렌더에 이전 안내가 비쳤다.
 * - 역할 상세는 함께 읽는 전체 권한 목록의 404 까지 「역할 없음」으로 보였다.
 * - 대시보드 상세는 진입 404 뒤 목록 캐시를 무효화하지 않아 「목록으로」에 지워진 대시보드가 남았다.
 */

const ROLE_GONE_MESSAGE = '역할을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.';
const USER_NOT_FOUND_MESSAGE = '사용자를 찾을 수 없습니다.';

/** 응답을 붙잡아 두는 경로 — release() 를 부를 때까지 응답하지 않아 「늦게 온 응답」을 결정적으로 만든다 */
async function holdRoute(
  page: Page,
  path: string,
  respond: (route: Route) => Promise<void>,
  method: 'GET' | 'PUT' = 'GET',
) {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let markReceived!: () => void;
  const received = new Promise<void>((resolve) => {
    markReceived = resolve;
  });
  let markDone!: () => void;
  const done = new Promise<void>((resolve) => {
    markDone = resolve;
  });
  await page.route(
    (url) => url.pathname === path,
    async (route) => {
      if (route.request().method() !== method) return route.fallback();
      markReceived();
      await gate;
      await respond(route);
      markDone();
    },
  );
  return { release, received, done };
}

const json = (route: Route, status: number, body: unknown) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

/** 새로고침 없이(같은 페이지 컴포넌트 그대로) 앱 안에서 다른 주소로 옮긴다 */
async function navigateInApp(page: Page, path: string) {
  await page.evaluate((to) => {
    window.history.pushState({}, '', to);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

/**
 * React 가 화면을 반영(commit)할 때마다 불리는 자리를 심는다 — React DevTools 가 쓰는 전역 훅과 같은 모양이다.
 * 「한 프레임 비치는」 이전 안내는 시간(프레임)으로는 잡히지 않는다: 새 주소의 첫 렌더가 몇 프레임 걸려도 이전 안내가 그대로라
 * 고친 화면과 구분되지 않는다. 대신 이동 뒤 매 commit 직후의 DOM 을 보면, 첫 렌더가 이전 안내를 그대로 commit 했는지 알 수 있다.
 * 앱 스크립트보다 먼저 심어야 React 가 이 훅에 붙는다(react-refresh 도 있으면 이 훅을 감싸 그대로 부른다).
 */
async function installCommitProbe(page: Page) {
  await page.addInitScript(() => {
    const w = window as unknown as Record<string, unknown>;
    if (w.__REACT_DEVTOOLS_GLOBAL_HOOK__) return;
    let nextId = 1;
    w.__REACT_DEVTOOLS_GLOBAL_HOOK__ = {
      renderers: new Map(),
      supportsFiber: true,
      inject: () => nextId++,
      onScheduleFiberRoot: () => {},
      onCommitFiberRoot: () => {
        const probe = w.__onReactCommit as (() => void) | undefined;
        probe?.();
      },
      onCommitFiberUnmount: () => {},
      onPostCommitFiberRoot: () => {},
      checkDCE: () => {},
    };
  });
}

/**
 * 앱 안에서 주소를 옮기고, 그 뒤 React commit 마다 주어진 제목이 화면에 남아 있었는지 기록하기 시작한다.
 * 결과는 readHeadingAfterCommits 로 읽는다.
 */
async function navigateAndProbeHeading(page: Page, path: string, heading: string) {
  await page.evaluate(
    ({ to, text }) => {
      const w = window as unknown as Record<string, unknown>;
      const log: boolean[] = [];
      w.__headingAfterCommits = log;
      w.__onReactCommit = () => {
        log.push([...document.querySelectorAll('h1')].some((h) => h.textContent?.trim() === text));
      };
      window.history.pushState({}, '', to);
      window.dispatchEvent(new PopStateEvent('popstate'));
    },
    { to: path, text: heading },
  );
}

/** 이동 뒤 commit 마다 제목이 있었는지(true) 목록 */
const readHeadingAfterCommits = (page: Page) =>
  page.evaluate(() => (window as unknown as { __headingAfterCommits: boolean[] }).__headingAfterCommits);

test.describe('역할 상세 — 진입 조회 경합·출처 (WD-646)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSecurityLevelMocks(page);
    await setupRoleListMocks(page);
    await mockApi(page, 'GET', '/api/v1/permissions', createPermissions());
    await mockApi(page, 'GET', '/api/v1/roles/4/clearance', { roleId: 4, securityLevelId: 1, userCount: 0, fixed: false });
  });

  test('이전 id 의 늦은 404 가 옮겨 간 역할 화면을 「찾을 수 없음」으로 덮지 않는다', async ({ authenticatedPage: page }) => {
    const slow = await holdRoute(page, '/api/v1/roles/3', (route) =>
      json(route, 404, { status: 404, message: ROLE_GONE_MESSAGE }),
    );
    await setupRoleDetailMocks(page, 4);

    await page.goto('/admin/roles/3');
    await slow.received;
    await navigateInApp(page, '/admin/roles/4');
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');

    // 이제서야 id 3 의 404 가 도착한다
    slow.release();
    await slow.done;

    await expect(page.getByRole('heading', { name: ROLE_GONE_MESSAGE })).toHaveCount(0);
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');
    await expect(page).toHaveURL(/\/admin\/roles\/4$/);
  });

  test('이전 id 의 늦은 성공 응답이 옮겨 간 역할의 폼을 덮어쓰지 않는다', async ({ authenticatedPage: page }) => {
    const slow = await holdRoute(page, '/api/v1/roles/3', (route) =>
      json(route, 200, createRoleDetail({ id: 3, name: 'OLD_ROLE', description: '이전 역할' })),
    );
    await setupRoleDetailMocks(page, 4);

    await page.goto('/admin/roles/3');
    await slow.received;
    await navigateInApp(page, '/admin/roles/4');
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');

    slow.release();
    await slow.done;

    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');
    await expect(page.getByLabel('설명')).toHaveValue('편집자 역할');
  });

  test('역할 A 의 저장 응답이 B 로 옮긴 뒤 와도 B 가 스켈레톤에 갇히거나 A 로 덮이지 않는다', async ({ authenticatedPage: page }) => {
    // 시스템 역할은 저장 버튼이 막히므로 사용자 정의 역할로 둔다(역할 4 는 fixture 의 사용자 정의 역할)
    await mockApi(page, 'GET', '/api/v1/roles/3', createRoleDetail({ id: 3, name: 'OLD_ROLE', description: '이전 역할', isSystem: false }));
    await setupRoleDetailMocks(page, 4);
    // 역할 3 의 이름·설명 저장 응답을 붙잡아 둔다 — 저장 뒤 재조회(GET /roles/3)가 setRole 로 역할 3 을 다시 넣는 경로다
    const save = await holdRoute(page, '/api/v1/roles/3', (route) => json(route, 200, {}), 'PUT');

    await page.goto('/admin/roles/3');
    await expect(page.getByLabel('역할 이름')).toHaveValue('OLD_ROLE');
    // 고친 것 없이 저장한다 — 폼이 dirty 가 아니라 이탈 확인 없이 옮길 수 있다
    await page.locator('[data-slot="card"]').filter({ hasText: '역할 정보' }).getByRole('button', { name: '저장', exact: true }).click();
    await save.received;

    await navigateInApp(page, '/admin/roles/4');
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');
    // 옮긴 뒤 역할 3 을 다시 읽는지 센다 — 떠난 화면의 저장 핸들러가 이어 돌면 재조회(GET /roles/3)가 나간다
    let staleReloads = 0;
    page.on('request', (req) => {
      if (req.method() === 'GET' && new URL(req.url()).pathname === '/api/v1/roles/3') staleReloads += 1;
    });

    // 이제서야 역할 3 의 저장이 끝난다
    save.release();
    await save.done;
    // 떠난 화면의 핸들러가 이어 돌 시간을 준다 — 이어 돌면 이 사이에 토스트·재조회가 일어난다
    await page.waitForTimeout(500);

    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');
    await expect(page.getByLabel('설명')).toHaveValue('편집자 역할');
    await expect(page.getByText('역할 정보가 업데이트되었습니다.')).toHaveCount(0);
    expect(staleReloads).toBe(0);
  });

  test('없는 역할에서 다른 역할로 옮기면 이전 「찾을 수 없음」 안내가 한 프레임도 비치지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/roles/3', { status: 404, message: ROLE_GONE_MESSAGE }, { status: 404 });
    // 새 역할 응답을 붙잡아 둬 「불러오는 중」 동안 이전 안내가 남는지 볼 수 있게 한다
    const next = await holdRoute(page, '/api/v1/roles/4', (route) =>
      json(route, 200, createRoleDetail({ id: 4, name: 'EDITOR', description: '편집자 역할' })),
    );

    await installCommitProbe(page);
    await page.goto('/admin/roles/3');
    await expect(page.getByRole('heading', { name: ROLE_GONE_MESSAGE })).toBeVisible();

    await navigateAndProbeHeading(page, '/admin/roles/4', ROLE_GONE_MESSAGE);
    await expect(page.getByRole('heading', { name: ROLE_GONE_MESSAGE })).toHaveCount(0);
    // 새 주소를 그린 commit 부터 이전 안내가 없어야 한다 — effect 에서야 지우면 첫 commit 에 이전 안내가 남는다
    const commits = await readHeadingAfterCommits(page);
    expect(commits.length).toBeGreaterThan(0);
    expect(commits).not.toContain(true);

    next.release();
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');
  });

  test('역할은 있고 전체 권한 목록만 404 면 「역할 없음」 안내가 아니라 일반 불러오기 실패로 다룬다', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 4);
    await mockApi(page, 'GET', '/api/v1/permissions', { status: 404, message: 'Not found' }, { status: 404 });

    await page.goto('/admin/roles/4');

    await expect(page.getByText('역할 정보를 불러오는 데 실패했습니다.').first()).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/roles$/);
    await expect(page.getByRole('heading', { name: ROLE_GONE_MESSAGE })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: '역할을 찾을 수 없습니다.' })).toHaveCount(0);
  });
});

test.describe('사용자 상세 — 진입 조회 경합·출처 (WD-646)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockApi(page, 'GET', '/api/v1/roles', [
      createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
    ]);
  });

  test('이전 id 의 늦은 404 가 옮겨 간 사용자 화면을 「찾을 수 없음」으로 덮지 않는다', async ({ authenticatedPage: page }) => {
    const slow = await holdRoute(page, '/api/v1/users/3', (route) => json(route, 404, { status: 404, message: 'Not found' }));
    await mockApi(page, 'GET', '/api/v1/users/4', createUserDetail({ id: 4, username: 'user4', email: 'user4@example.com' }));

    await page.goto('/admin/users/3');
    await slow.received;
    await navigateInApp(page, '/admin/users/4');
    await expect(page.getByText('user4@example.com')).toBeVisible();

    slow.release();
    await slow.done;

    await expect(page.getByRole('heading', { name: USER_NOT_FOUND_MESSAGE })).toHaveCount(0);
    await expect(page.getByText('user4@example.com')).toBeVisible();
  });

  test('이전 id 의 늦은 성공 응답이 옮겨 간 사용자 화면을 덮어쓰지 않는다', async ({ authenticatedPage: page }) => {
    const slow = await holdRoute(page, '/api/v1/users/3', (route) =>
      json(route, 200, createUserDetail({ id: 3, username: 'user3', email: 'user3@example.com' })),
    );
    await mockApi(page, 'GET', '/api/v1/users/4', createUserDetail({ id: 4, username: 'user4', email: 'user4@example.com' }));

    await page.goto('/admin/users/3');
    await slow.received;
    await navigateInApp(page, '/admin/users/4');
    await expect(page.getByText('user4@example.com')).toBeVisible();

    slow.release();
    await slow.done;

    await expect(page.getByText('user4@example.com')).toBeVisible();
    await expect(page.getByText('user3@example.com')).toHaveCount(0);
  });

  test('사용자 A 의 상태 변경 응답이 B 로 옮긴 뒤 와도 B 가 스켈레톤에 갇히거나 A 로 덮이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 정지된 멤버라 스위치를 누르면 확인 없이 바로 재활성 요청이 나간다
    await mockApi(page, 'GET', '/api/v1/users/3', createUserDetail({ id: 3, username: 'user3', email: 'user3@example.com', isActive: false }));
    await mockApi(page, 'GET', '/api/v1/users/4', createUserDetail({ id: 4, username: 'user4', email: 'user4@example.com' }));
    const toggle = await holdRoute(page, '/api/v1/users/3/active', (route) => json(route, 200, {}), 'PUT');

    await page.goto('/admin/users/3');
    await expect(page.getByText('user3@example.com')).toBeVisible();
    await page.getByRole('switch').click();
    await toggle.received;

    await navigateInApp(page, '/admin/users/4');
    await expect(page.getByText('user4@example.com')).toBeVisible();

    // 이제서야 사용자 3 의 상태 변경이 끝난다 — 예전엔 setUser 로 사용자 3 이 들어가 「id 가 주소와 다름」 스켈레톤에 갇혔다
    toggle.release();
    await toggle.done;

    await expect(page.getByText('user4@example.com')).toBeVisible();
    await expect(page.getByRole('switch')).toBeVisible();
    await expect(page.getByText('user3@example.com')).toHaveCount(0);
  });

  test('없는 사용자에서 다른 사용자로 옮기면 이전 「찾을 수 없음」 안내가 한 프레임도 비치지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/users/3', { status: 404, message: 'Not found' }, { status: 404 });
    const next = await holdRoute(page, '/api/v1/users/4', (route) =>
      json(route, 200, createUserDetail({ id: 4, username: 'user4', email: 'user4@example.com' })),
    );

    await installCommitProbe(page);
    await page.goto('/admin/users/3');
    await expect(page.getByRole('heading', { name: USER_NOT_FOUND_MESSAGE })).toBeVisible();

    await navigateAndProbeHeading(page, '/admin/users/4', USER_NOT_FOUND_MESSAGE);
    await expect(page.getByRole('heading', { name: USER_NOT_FOUND_MESSAGE })).toHaveCount(0);
    // 새 주소를 그린 commit 부터 이전 안내가 없어야 한다 — effect 에서야 지우면 첫 commit 에 이전 안내가 남는다
    const commits = await readHeadingAfterCommits(page);
    expect(commits.length).toBeGreaterThan(0);
    expect(commits).not.toContain(true);

    next.release();
    await expect(page.getByText('user4@example.com')).toBeVisible();
  });

  test('사용자는 있고 역할 목록만 404 면 「사용자 없음」 안내가 아니라 일반 불러오기 실패로 다룬다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/users/4', createUserDetail({ id: 4, username: 'user4', email: 'user4@example.com' }));
    await mockApi(page, 'GET', '/api/v1/roles', { status: 404, message: 'Not found' }, { status: 404 });

    await page.goto('/admin/users/4');

    await expect(page.getByText('사용자 정보를 불러오는 데 실패했습니다.').first()).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/users$/);
    await expect(page.getByRole('heading', { name: USER_NOT_FOUND_MESSAGE })).toHaveCount(0);
  });
});

/** 목록에서 연 상세가 첫 조회 404 일 때 「목록으로」에서 목록을 다시 받는지 — 리소스별 같은 흐름이라 표로 돈다 */
const LIST_REFETCH_CASES = [
  {
    label: '대시보드',
    listPath: '/analytics/dashboards',
    notFoundMessage: '대시보드를 찾을 수 없습니다.',
    listItem: (id: number, name: string) => createDashboardListItem({ id, name }),
    extraMocks: async () => {},
  },
  {
    label: '차트',
    listPath: '/analytics/charts',
    notFoundMessage: '차트를 찾을 수 없습니다.',
    listItem: (id: number, name: string) => createChartListItem({ id, name }),
    // 차트 빌더가 저장 쿼리 목록을 함께 읽는다
    extraMocks: (page: Page) => mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse(createSavedQueryList(2))),
  },
];

test.describe('상세 진입 404 뒤 목록 캐시 (WD-646)', () => {
  for (const c of LIST_REFETCH_CASES) {
    test(`목록에서 연 ${c.label}가 404 면 「목록으로」에서 목록을 다시 받아 지워진 항목이 남지 않는다`, async ({
      authenticatedPage: page,
    }) => {
      // 첫 목록에는 항목 1 이 있고, 그 뒤(지워진 뒤) 목록에는 없다
      const listRequests: string[] = [];
      await page.route(
        (url) => url.pathname === `/api/v1${c.listPath}`,
        (route) => {
          if (route.request().method() !== 'GET') return route.fallback();
          listRequests.push(route.request().url());
          const kept = c.listItem(2, `남는 ${c.label}`);
          const items = listRequests.length === 1 ? [c.listItem(1, `지워질 ${c.label}`), kept] : [kept];
          return json(route, 200, createPageResponse(items));
        },
      );
      await mockApi(page, 'GET', `/api/v1${c.listPath}/1`, { message: c.notFoundMessage }, { status: 404 });
      await c.extraMocks(page);

      await page.goto(c.listPath);
      await expect(page.getByText(`지워질 ${c.label}`)).toBeVisible();
      await expect.poll(() => listRequests.length).toBe(1);

      // 행을 눌러 상세로 간다(앱 안 이동이라 목록 캐시가 유지된다 — 무효화하지 않으면 staleTime 30초 동안 다시 받지 않는다)
      await page.getByText(`지워질 ${c.label}`).click();
      await expect(page.getByRole('heading', { name: c.notFoundMessage })).toBeVisible();
      await page.getByRole('button', { name: '목록으로' }).click();

      await expect(page).toHaveURL(new RegExp(`${c.listPath}$`));
      await expect.poll(() => listRequests.length).toBeGreaterThan(1);
      await expect(page.getByText(`남는 ${c.label}`)).toBeVisible();
      await expect(page.getByText(`지워질 ${c.label}`)).toHaveCount(0);
    });
  }
});
