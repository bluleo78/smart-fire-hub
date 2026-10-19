import type { Page } from '@playwright/test';

import { createPlatformUser, createTenant } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

test.describe('테넌트 생성', () => {
  test('폼과 도움말이 렌더된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await page.goto('/tenants/new');

    await expect(page.getByRole('heading', { name: '테넌트 생성' })).toBeVisible();
    await expect(page.getByLabel('이름')).toBeVisible();
    await expect(page.getByText('조직에 표시될 이름입니다.')).toBeVisible();
    await expect(page.getByLabel('식별자')).toBeVisible();
    await expect(page.getByText('테넌트를 구분하는 고유한 영문 이름입니다. 소문자·숫자·하이픈만 사용합니다.')).toBeVisible();
    await expect(page.getByText('생성 후에는 변경할 수 없습니다.')).toBeVisible();
    await expect(page.getByLabel('초기 Owner')).toBeVisible();
    await expect(
      page.getByText(
        '이 테넌트의 첫 소유자가 될 사용자입니다. 소유자 없이는 아무도 이 테넌트에 로그인할 수 없습니다.',
      ),
    ).toBeVisible();
    // Task 5 검색 엔드포인트는 20건 상한을 넘겨도 잘렸다는 신호를 안 준다 — 정적 안내로 보완한다.
    await expect(
      page.getByText('검색 결과는 최대 20건까지 표시됩니다. 찾는 사용자가 없으면 검색어를 더 좁혀보세요.'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: '테넌트 생성' })).toBeVisible();
    await expect(page.getByRole('button', { name: '취소' })).toBeVisible();
  });

  test('빈 폼 제출은 클라이언트 검증에서 막힌다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'POST', '/api/platform/tenants', createTenant(), { capture: true });
    await page.goto('/tenants/new');

    await page.getByRole('button', { name: '테넌트 생성' }).click();

    // 두 문구가 **동시에** 떠야 한다. Owner 검증이 zod 밖(제출 콜백 안)에 있으면
    // handleSubmit 이 name/slug 실패 때문에 콜백을 부르지 않아 두 번째 문구가 안 뜬다.
    await expect(page.getByText('이름을 입력하세요.')).toBeVisible();
    await expect(page.getByText('Owner 사용자를 선택하세요.')).toBeVisible();
    await page.waitForTimeout(300);
    expect(capture.lastRequest()).toBeUndefined();
  });

  test('Owner 를 골랐다가 해제하면 제출이 다시 막힌다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
    const capture = await mockApi(page, 'POST', '/api/platform/tenants', createTenant(), { capture: true });
    await page.goto('/tenants/new');

    await page.getByLabel('이름').fill('한빛소방서');
    await page.getByLabel('식별자').fill('hanbit');
    await page.getByLabel('초기 Owner').fill('박소');
    await page.waitForTimeout(400);
    await page.getByRole('option', { name: /박소유/ }).click();
    // 선택 상태는 접근 가능 이름으로 도달할 수 있어야 한다(div 에 htmlFor 를 걸면 이름이 안 생긴다).
    await expect(page.getByRole('group', { name: '초기 Owner' })).toContainText(
      '박소유 · owner@example.com',
    );

    await page.getByRole('button', { name: 'Owner 선택 해제' }).click();
    await page.getByRole('button', { name: '테넌트 생성' }).click();

    // 해제가 폼 값을 실제로 비우지 않으면 낡은 id 로 POST 가 나간다 — 조용한 오기록이다.
    await expect(page.getByText('Owner 사용자를 선택하세요.')).toBeVisible();
    await page.waitForTimeout(300);
    expect(capture.lastRequest()).toBeUndefined();
  });

  test('잘못된 slug 형식은 서버로 가지 않는다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'POST', '/api/platform/tenants', createTenant(), { capture: true });
    await page.goto('/tenants/new');

    await page.getByLabel('이름').fill('한빛소방서');
    await page.getByLabel('식별자').fill('-Bad_Slug');
    await page.getByRole('button', { name: '테넌트 생성' }).click();

    await expect(
      page.getByText('식별자는 소문자·숫자·하이픈만 사용할 수 있으며 소문자 또는 숫자로 시작해야 합니다.'),
    ).toBeVisible();
    await page.waitForTimeout(300);
    expect(capture.lastRequest()).toBeUndefined();
  });

  test('Owner 검색은 2자부터 호출하고 고르면 선택 상태가 된다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(
      page,
      'GET',
      '/api/platform/users',
      [createPlatformUser({ id: 42, name: '박소유', email: 'owner@example.com' })],
      { capture: true },
    );
    await page.goto('/tenants/new');

    const owner = page.getByLabel('초기 Owner');
    await owner.fill('박');
    await page.waitForTimeout(400);
    // 1자는 서버 하한(2자) 미만이라 호출하지 않는다.
    expect(capture.lastRequest()).toBeUndefined();

    await owner.fill('박소');
    await page.waitForTimeout(400);
    const req = await capture.waitForRequest();
    expect(req.searchParams.get('q')).toBe('박소');

    await page.getByRole('option', { name: /박소유/ }).click();
    await expect(page.getByText('박소유 · owner@example.com')).toBeVisible();
  });

  test('검색 결과가 없으면 안내 문구를 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/users', []);
    await page.goto('/tenants/new');

    await page.getByLabel('초기 Owner').fill('없는사람');
    await page.waitForTimeout(400);
    await expect(page.getByText('검색 결과가 없습니다.')).toBeVisible();
  });

  test('검색 조회 실패는 빈 결과와 다른 문구를 보여준다', async ({ authenticatedPage: page }) => {
    // 양성 대조군: 같은 화면·같은 입력에서 성공 응답이면 "없습니다" 가 뜨는지 먼저 확인한다.
    // 이 없이는 아래 실패 단언이 "그냥 항상 안 보이는" 죽은 로케이터를 통과시킬 수 있다.
    await mockApi(page, 'GET', '/api/platform/users', []);
    await page.goto('/tenants/new');
    const owner = page.getByLabel('초기 Owner');
    await owner.fill('없는사람');
    await page.waitForTimeout(400);
    await expect(page.getByText('검색 결과가 없습니다.')).toBeVisible();
    await expect(page.getByText('검색 결과를 불러오지 못했습니다.')).not.toBeVisible();

    // 이제 같은 화면에서 서버가 500 을 주면: "없습니다" 가 아니라 별도 실패 문구여야 한다.
    // 불러오지 못한 것을 0건이라고 말하면 조작자가 "그런 사람 없음"으로 오해한다.
    await mockApi(page, 'GET', '/api/platform/users', {}, { status: 500 });
    await owner.fill('다시검색');
    await expect(page.getByText('검색 결과를 불러오지 못했습니다.')).toBeVisible();
    await expect(page.getByText('검색 결과가 없습니다.')).not.toBeVisible();
  });

  test('생성 후 목록으로 돌아가면 새 테넌트가 즉시 보인다(캐시 무효화)', async ({
    authenticatedPage: page,
  }) => {
    // 목록 응답이 생성 전후로 달라야 이 단언이 공허하지 않다 — 항상 같은 목록을 주면
    // 무효화가 있든 없든 통과해 버린다. 1회차는 새 테넌트가 없는 목록, 2회차부터는
    // 있는 목록을 준다. 무효화가 없으면 staleTime(30_000) 때문에 재조회가 안 일어나
    // 1회차 응답이 그대로 남아 이 테스트가 빨개진다.
    const beforeList = [createTenant({ id: 1, name: '기존테넌트', slug: 'existing' })];
    const afterList = [
      ...beforeList,
      createTenant({ id: 7, name: '한빛소방서', slug: 'hanbit' }),
    ];
    let listCallCount = 0;
    await page.route(
      (url) => url.pathname === '/api/platform/tenants',
      (route) => {
        if (route.request().method() !== 'GET') {
          return route.fallback();
        }
        listCallCount += 1;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(listCallCount === 1 ? beforeList : afterList),
        });
      },
    );
    await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
    await mockApi(
      page,
      'POST',
      '/api/platform/tenants',
      createTenant({ id: 7, name: '한빛소방서', slug: 'hanbit' }),
      { status: 201 },
    );
    await mockApi(
      page,
      'GET',
      '/api/platform/tenants/7',
      createTenant({ id: 7, name: '한빛소방서', slug: 'hanbit' }),
    );
    await mockApi(page, 'GET', '/api/platform/tenants/7/members', []);

    // 먼저 목록을 방문해 1회차(기존 테넌트만 있는) 응답을 캐시에 채운다.
    // 이후 이동은 반드시 앱 내부 링크/버튼 클릭이어야 한다 — `page.goto()` 로 경로를 바꾸면
    // 브라우저가 실제로 다시 로드되어(SPA 클라이언트 라우팅이 아니라 서버 왕복) QueryClient
    // 인메모리 캐시가 통째로 사라진다. 그러면 무효화가 있든 없든 재방문 시 무조건 새로
    // 조회하게 되어 이 테스트가 아무것도 증명하지 못한다.
    await page.goto('/tenants');
    await expect(page.getByRole('cell', { name: '기존테넌트', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: '한빛소방서', exact: true })).not.toBeVisible();

    await page.getByRole('link', { name: '테넌트 생성' }).click();
    // 지연 로딩된 페이지가 완전히 마운트되기 전에 입력하면 react-hook-form 의 register
    // ref 가 아직 붙지 않은 엔드포인트에 값을 채우는 꼴이 되어 조용히 빈 채로 남는다
    // (실측: heading 대기 없이 곧장 fill 하면 제출 시 이름·slug 가 비어 있었다).
    await expect(page.getByRole('heading', { name: '테넌트 생성' })).toBeVisible();
    await page.getByLabel('이름').fill('한빛소방서');
    await page.getByLabel('식별자').fill('hanbit');
    await page.getByLabel('초기 Owner').fill('박소');
    await page.waitForTimeout(400);
    await page.getByRole('option', { name: /박소유/ }).click();
    await page.getByRole('button', { name: '테넌트 생성' }).click();
    await expect(page).toHaveURL('/tenants/7');

    await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
    await expect(page).toHaveURL('/tenants');
    await expect(page.getByRole('cell', { name: '한빛소방서', exact: true })).toBeVisible();
  });

  test('생성에 성공하면 상세로 이동하고 토스트를 띄운다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
    const created = createTenant({ id: 7, name: '한빛소방서', slug: 'hanbit' });
    const capture = await mockApi(page, 'POST', '/api/platform/tenants', created, { status: 201, capture: true });
    await mockApi(page, 'GET', '/api/platform/tenants/7', created);
    await mockApi(page, 'GET', '/api/platform/tenants/7/members', []);
    await page.goto('/tenants/new');

    await page.getByLabel('이름').fill('한빛소방서');
    await page.getByLabel('식별자').fill('hanbit');
    await page.getByLabel('초기 Owner').fill('박소');
    await page.waitForTimeout(400);
    await page.getByRole('option', { name: /박소유/ }).click();
    await page.getByRole('button', { name: '테넌트 생성' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '한빛소방서', slug: 'hanbit', ownerUserId: 42 });
    await expect(page).toHaveURL('/tenants/7');
    await expect(page.getByText('테넌트가 생성되었습니다.')).toBeVisible();
  });

  test('서버 400 메시지를 그대로 필드 아래 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
    await mockApi(
      page,
      'POST',
      '/api/platform/tenants',
      { status: 400, error: 'Bad Request', message: '이미 사용 중인 식별자입니다: hanbit' },
      { status: 400 },
    );
    await page.goto('/tenants/new');

    await page.getByLabel('이름').fill('한빛소방서');
    await page.getByLabel('식별자').fill('hanbit');
    await page.getByLabel('초기 Owner').fill('박소');
    await page.waitForTimeout(400);
    await page.getByRole('option', { name: /박소유/ }).click();
    await page.getByRole('button', { name: '테넌트 생성' }).click();

    // 프런트가 문구를 다시 쓰지 않는다 — 서버가 유일한 판정자다.
    await expect(page.getByText('이미 사용 중인 식별자입니다: hanbit')).toBeVisible();
    await expect(page).toHaveURL('/tenants/new');
  });

  test('생성 권한이 없으면 폼 대신 권한 배너를 보여준다(M-1)', async ({ page }) => {
    await loginAs(page, ['platform:tenant:read']);
    const capture = await mockApi(page, 'POST', '/api/platform/tenants', createTenant(), { capture: true });
    await page.goto('/tenants/new');

    await expect(page.getByRole('heading', { name: '테넌트 생성' })).toBeVisible();
    // role 까지 고정한다 — TenantListPage 의 403 테스트와 같은 근거.
    await expect(
      page.getByRole('status').filter({ hasText: '이 작업을 수행할 권한이 없습니다.' }),
    ).toBeVisible();
    // 양성 대조군: 배너만 얹히고 폼은 여전히 그려지는 변이를 잡는다 — 폼 필드/제출 버튼이
    // 실제로 없어야 한다.
    await expect(page.getByLabel('이름')).not.toBeVisible();
    await expect(page.getByRole('button', { name: '테넌트 생성' })).not.toBeVisible();
    expect(capture.requests.length).toBe(0);
  });

  test('제출 후 서버가 403 을 주면 일반 실패가 아니라 권한 메시지를 보여준다(M-1)', async ({
    authenticatedPage: page,
  }) => {
    // 라우트 게이트를 통과한 뒤(전 권한 보유) 서버가 그래도 403 을 주는 경합 상황을 대비한
    // 심층방어 분기. 위 '기타 실패는 토스트로 알린다'(500) 테스트가 양성 대조군이다 —
    // 같은 화면에서 500 은 일반 문구, 403 은 권한 문구여야 한다.
    await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
    await mockApi(page, 'POST', '/api/platform/tenants', {}, { status: 403 });
    await page.goto('/tenants/new');

    await page.getByLabel('이름').fill('한빛소방서');
    await page.getByLabel('식별자').fill('hanbit');
    await page.getByLabel('초기 Owner').fill('박소');
    await page.waitForTimeout(400);
    await page.getByRole('option', { name: /박소유/ }).click();
    await page.getByRole('button', { name: '테넌트 생성' }).click();

    await expect(page.getByText('이 작업을 수행할 권한이 없습니다.')).toBeVisible();
    await expect(page.getByText('테넌트 생성에 실패했습니다.')).not.toBeVisible();
  });

  test('기타 실패는 토스트로 알린다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
    await mockApi(page, 'POST', '/api/platform/tenants', {}, { status: 500 });
    await page.goto('/tenants/new');

    await page.getByLabel('이름').fill('한빛소방서');
    await page.getByLabel('식별자').fill('hanbit');
    await page.getByLabel('초기 Owner').fill('박소');
    await page.waitForTimeout(400);
    await page.getByRole('option', { name: /박소유/ }).click();
    await page.getByRole('button', { name: '테넌트 생성' }).click();

    await expect(page.getByText('테넌트 생성에 실패했습니다.')).toBeVisible();
  });

  test.describe('데이터셋 저장소 선택 (WD-73)', () => {
    const BACKENDS_ALL = [
      { backend: 'POSTGRESQL', available: true },
      { backend: 'TIBERO', available: true },
      { backend: 'MYSQL', available: false },
    ];

    /** 이름·식별자·Owner 를 채운다(저장소 입력 전 공통 단계). */
    async function fillBasics(page: Page) {
      await expect(page.getByRole('heading', { name: '테넌트 생성' })).toBeVisible();
      await page.getByLabel('이름').fill('한빛소방서');
      await page.getByLabel('식별자').fill('hanbit');
      await page.getByLabel('초기 Owner').fill('박소');
      await page.waitForTimeout(400);
      await page.getByRole('option', { name: /박소유/ }).click();
    }

    async function chooseTibero(page: Page) {
      await page.getByRole('combobox', { name: '저장소 종류' }).click();
      await page.getByRole('option', { name: 'Tibero' }).click();
    }

    /** 생성 성공 후 상세 화면이 요구하는 모킹 묶음. */
    async function mockDetail(page: Page) {
      await mockApi(page, 'GET', '/api/platform/tenants/7', createTenant({ id: 7 }));
      await mockApi(page, 'GET', '/api/platform/tenants/7/members', []);
      await mockApi(page, 'GET', '/api/platform/tenants/7/data-store', {
        backend: 'POSTGRESQL',
        accountUsernames: [],
      });
    }

    test('기본은 PostgreSQL 이고 기존 페이로드 그대로 보낸다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
      const capture = await mockApi(page, 'POST', '/api/platform/tenants', createTenant({ id: 7 }), {
        status: 201,
        capture: true,
      });
      await mockDetail(page);
      await page.goto('/tenants/new');

      await expect(page.getByRole('combobox', { name: '저장소 종류' })).toContainText('PostgreSQL (기본)');
      await fillBasics(page);
      await page.getByRole('button', { name: '테넌트 생성' }).click();

      const req = await capture.waitForRequest();
      expect(req.payload).toEqual({ name: '한빛소방서', slug: 'hanbit', ownerUserId: 42 });
      expect(Object.keys(req.payload as object)).not.toContain('dataStore');
    });

    test('사용 불가 백엔드는 선택지에서 비활성으로 보인다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      await page.goto('/tenants/new');

      await page.getByRole('combobox', { name: '저장소 종류' }).click();
      const mysql = page.getByRole('option', { name: /MySQL/ });
      await expect(mysql).toContainText('준비 중');
      await expect(mysql).toHaveAttribute('aria-disabled', 'true');
      await expect(page.getByRole('option', { name: 'Tibero' })).not.toHaveAttribute('aria-disabled', 'true');
    });

    test('Tibero DBA 자동 생성 — 연결 정보와 DBA 계정을 보낸다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
      const capture = await mockApi(page, 'POST', '/api/platform/tenants', createTenant({ id: 7 }), {
        status: 201,
        capture: true,
      });
      await mockDetail(page);
      await page.goto('/tenants/new');

      await fillBasics(page);
      await chooseTibero(page);
      // 기본값: 포트 8629, 테이블스페이스 USR, 모드 DBA 자동.
      await expect(page.getByLabel('포트')).toHaveValue('8629');
      await expect(page.getByLabel('테이블스페이스')).toHaveValue('USR');
      await page.getByLabel('호스트').fill('192.168.179.80');
      await page.getByLabel('SID').fill('tibero');
      await page.getByLabel('DBA 계정', { exact: true }).fill('sys');
      await page.getByLabel('DBA 비밀번호').fill('tibero-pw');
      await page.getByRole('button', { name: '테넌트 생성' }).click();

      const req = await capture.waitForRequest();
      expect(req.payload).toEqual({
        name: '한빛소방서',
        slug: 'hanbit',
        ownerUserId: 42,
        dataStore: {
          backend: 'TIBERO',
          host: '192.168.179.80',
          port: 8629,
          database: 'tibero',
          tablespace: 'USR',
          mode: 'DBA_AUTO',
          dba: { username: 'sys', password: 'tibero-pw' },
        },
      });
      await expect(page).toHaveURL('/tenants/7');
    });

    test('연결 테스트 성공·실패 결과를 보여 준다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      const okCapture = await mockApi(
        page,
        'POST',
        '/api/platform/tenants/data-store/test',
        { ok: true, serverVersion: 'Tibero 7 (FS02)', missingPrivileges: [], message: '연결 성공' },
        { capture: true },
      );
      await page.goto('/tenants/new');
      await chooseTibero(page);
      await page.getByLabel('호스트').fill('192.168.179.80');
      await page.getByLabel('SID').fill('tibero');
      await page.getByLabel('DBA 계정', { exact: true }).fill('sys');
      await page.getByLabel('DBA 비밀번호').fill('tibero-pw');

      await page.getByRole('button', { name: '연결 테스트' }).click();
      await expect(page.getByText('Tibero 7 (FS02)')).toBeVisible();
      // 권한까지 확인된 성공은 성공 배너다.
      await expect(page.locator('[data-slot="inline-banner"][data-variant="success"]')).toContainText(
        'Tibero 7 (FS02)',
      );
      const req = await okCapture.waitForRequest();
      expect(req.payload).toEqual({
        backend: 'TIBERO',
        host: '192.168.179.80',
        port: 8629,
        database: 'tibero',
        credentials: { username: 'sys', password: 'tibero-pw' },
      });

      await mockApi(page, 'POST', '/api/platform/tenants/data-store/test', {
        ok: false,
        serverVersion: null,
        missingPrivileges: ['CREATE USER', 'ALTER USER'],
        message: '권한이 부족합니다.',
      });
      await page.getByRole('button', { name: '연결 테스트' }).click();
      await expect(page.getByText('권한이 부족합니다.')).toBeVisible();
      await expect(page.getByText('CREATE USER')).toBeVisible();
      await expect(page.getByText('ALTER USER')).toBeVisible();
      await expect(page.getByText('Tibero 7 (FS02)')).not.toBeVisible();
    });

    test('연결은 됐지만 권한을 확인하지 못하면 경고 배너로 서버 메시지와 버전을 보인다', async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      await mockApi(page, 'POST', '/api/platform/tenants/data-store/test', {
        ok: true,
        serverVersion: '7.2.6',
        missingPrivileges: null,
        message: '접속은 됐지만 권한을 확인하지 못했습니다: ORA-00942 table or view does not exist',
      });
      await page.goto('/tenants/new');
      await chooseTibero(page);
      await page.getByLabel('호스트').fill('192.168.179.80');
      await page.getByLabel('SID').fill('tibero');
      await page.getByLabel('DBA 계정', { exact: true }).fill('sys');
      await page.getByLabel('DBA 비밀번호').fill('tibero-pw');

      await page.getByRole('button', { name: '연결 테스트' }).click();

      const warning = page.locator('[data-slot="inline-banner"][data-variant="warning"]');
      await expect(warning).toContainText('접속은 됐지만 권한을 확인하지 못했습니다: ORA-00942');
      await expect(warning).toContainText('7.2.6');
      // 순수 성공 배너로 보이면 안 된다.
      await expect(page.locator('[data-slot="inline-banner"][data-variant="success"]')).toHaveCount(0);
    });

    test('연결 테스트 422 는 서버 메시지를 배너로 보인다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      await mockApi(
        page,
        'POST',
        '/api/platform/tenants/data-store/test',
        {
          status: 422,
          error: 'Unprocessable Entity',
          code: 'DATA_STORE_PROVISIONING_FAILED',
          message: '연결 테스트를 할 수 없습니다: 드라이버 오류',
        },
        { status: 422 },
      );
      await page.goto('/tenants/new');
      await chooseTibero(page);
      await page.getByLabel('호스트').fill('192.168.179.80');
      await page.getByLabel('SID').fill('tibero');
      await page.getByLabel('DBA 계정', { exact: true }).fill('sys');
      await page.getByLabel('DBA 비밀번호').fill('tibero-pw');

      await page.getByRole('button', { name: '연결 테스트' }).click();

      await expect(page.locator('[data-slot="inline-banner"][data-variant="caution"]')).toContainText(
        '연결 테스트를 할 수 없습니다: 드라이버 오류',
      );
    });

    test('백엔드·방식을 바꾸면 이전 연결 테스트 결과가 사라진다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      await mockApi(page, 'POST', '/api/platform/tenants/data-store/test', {
        ok: true,
        serverVersion: 'Tibero 7 (FS02)',
        missingPrivileges: [],
        message: '연결 성공',
      });
      await page.goto('/tenants/new');
      await chooseTibero(page);
      await page.getByLabel('호스트').fill('192.168.179.80');
      await page.getByLabel('SID').fill('tibero');
      await page.getByLabel('DBA 계정', { exact: true }).fill('sys');
      await page.getByLabel('DBA 비밀번호').fill('tibero-pw');
      await page.getByRole('button', { name: '연결 테스트' }).click();
      await expect(page.getByText('Tibero 7 (FS02)')).toBeVisible();

      await page.getByRole('combobox', { name: '프로비저닝 방식' }).click();
      await page.getByRole('option', { name: 'DBA가 스크립트로 생성' }).click();
      await page.getByRole('combobox', { name: '프로비저닝 방식' }).click();
      await page.getByRole('option', { name: 'DBA 계정으로 자동 생성' }).click();

      await expect(page.getByRole('button', { name: '연결 테스트' })).toBeVisible();
      await expect(page.getByText('Tibero 7 (FS02)')).toHaveCount(0);
    });

    test('테이블스페이스를 비우면 페이로드에서 뺀다(서버가 USR 을 쓴다)', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
      const capture = await mockApi(page, 'POST', '/api/platform/tenants', createTenant({ id: 7 }), {
        status: 201,
        capture: true,
      });
      await mockDetail(page);
      await page.goto('/tenants/new');

      await fillBasics(page);
      await chooseTibero(page);
      await page.getByLabel('호스트').fill('192.168.179.80');
      await page.getByLabel('SID').fill('tibero');
      await page.getByLabel('테이블스페이스').fill('   ');
      await page.getByLabel('DBA 계정', { exact: true }).fill('sys');
      await page.getByLabel('DBA 비밀번호').fill('tibero-pw');
      await page.getByRole('button', { name: '테넌트 생성' }).click();

      const req = await capture.waitForRequest();
      const dataStore = (req.payload as { dataStore: Record<string, unknown> }).dataStore;
      expect(Object.keys(dataStore)).not.toContain('tablespace');
      expect(dataStore).toEqual({
        backend: 'TIBERO',
        host: '192.168.179.80',
        port: 8629,
        database: 'tibero',
        mode: 'DBA_AUTO',
        dba: { username: 'sys', password: 'tibero-pw' },
      });
    });

    test('규칙 밖 테이블스페이스·포트는 필드 오류를 보이고 요청을 보내지 않는다', async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
      const capture = await mockApi(page, 'POST', '/api/platform/tenants', createTenant(), { capture: true });
      await page.goto('/tenants/new');

      await fillBasics(page);
      await chooseTibero(page);
      await page.getByLabel('호스트').fill('192.168.179.80');
      await page.getByLabel('SID').fill('tibero');
      await page.getByLabel('테이블스페이스').fill('USR" QUOTA');
      // Number('1e3') 는 1000 이지만 숫자만 허용한다.
      await page.getByLabel('포트').fill('1e3');
      await page.getByLabel('DBA 계정', { exact: true }).fill('sys');
      await page.getByLabel('DBA 비밀번호').fill('tibero-pw');
      await page.getByRole('button', { name: '테넌트 생성' }).click();

      await expect(page.getByText('테이블스페이스를 올바르게 입력하세요.')).toBeVisible();
      await expect(page.getByText('포트는 1~65535 범위여야 합니다.')).toBeVisible();
      await page.waitForTimeout(300);
      expect(capture.lastRequest()).toBeUndefined();
    });

    test('스크립트 모드 — 생성 후 스크립트 대화상자를 보여 주고 닫으면 상세로 이동', async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
      const created = {
        ...createTenant({ id: 7 }),
        dataStoreSetupScript: 'CREATE USER "GENIA_T7" IDENTIFIED BY "abc";',
      };
      const capture = await mockApi(page, 'POST', '/api/platform/tenants', created, {
        status: 201,
        capture: true,
      });
      await mockDetail(page);
      await page.goto('/tenants/new');

      await fillBasics(page);
      await chooseTibero(page);
      await page.getByRole('combobox', { name: '프로비저닝 방식' }).click();
      await page.getByRole('option', { name: 'DBA가 스크립트로 생성' }).click();
      // 스크립트 모드에서는 DBA 입력·연결 테스트가 없다.
      await expect(page.getByLabel('DBA 계정', { exact: true })).toHaveCount(0);
      await expect(page.getByLabel('DBA 비밀번호')).toHaveCount(0);
      await expect(page.getByRole('button', { name: '연결 테스트' })).toHaveCount(0);
      await page.getByLabel('호스트').fill('192.168.179.80');
      await page.getByLabel('SID').fill('tibero');
      await page.getByRole('button', { name: '테넌트 생성' }).click();

      const req = await capture.waitForRequest();
      expect((req.payload as { dataStore: Record<string, unknown> }).dataStore).toEqual({
        backend: 'TIBERO',
        host: '192.168.179.80',
        port: 8629,
        database: 'tibero',
        tablespace: 'USR',
        mode: 'SCRIPT',
      });

      const dialog = page.getByRole('dialog');
      await expect(dialog).toContainText('CREATE USER "GENIA_T7" IDENTIFIED BY "abc";');
      await expect(dialog).toContainText(
        '이 스크립트는 다시 표시되지 않습니다. 잃어버리면 테넌트 상세에서 재발급하세요.',
      );
      await expect(dialog.getByRole('button', { name: '복사' })).toBeVisible();
      await expect(dialog.getByRole('region', { name: '스크립트 본문' })).toContainText('GENIA_T7');
      // 바깥 클릭·ESC 로는 닫히지 않는다 — 운영자가 확인해야 한다.
      await page.mouse.click(5, 5);
      await page.keyboard.press('Escape');
      await expect(dialog).toBeVisible();
      await expect(page).toHaveURL('/tenants/new');

      await dialog.getByRole('button', { name: '확인' }).click();
      await expect(page).toHaveURL('/tenants/7');
    });

    test('Tibero 필수값 검증', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
      const capture = await mockApi(page, 'POST', '/api/platform/tenants', createTenant(), { capture: true });
      await page.goto('/tenants/new');

      await fillBasics(page);
      await chooseTibero(page);
      await page.getByLabel('포트').fill('0');
      await page.getByLabel('SID').fill('   ');
      await page.getByLabel('DBA 계정', { exact: true }).fill('sys');
      await page.getByRole('button', { name: '테넌트 생성' }).click();

      await expect(page.getByText('호스트를 올바르게 입력하세요.')).toBeVisible();
      await expect(page.getByText('포트는 1~65535 범위여야 합니다.')).toBeVisible();
      await expect(page.getByText('SID 를 올바르게 입력하세요.')).toBeVisible();
      await expect(page.getByText('DBA 비밀번호를 입력하세요.')).toBeVisible();
      await page.waitForTimeout(300);
      expect(capture.lastRequest()).toBeUndefined();
    });

    test('프로비저닝 실패 422 메시지를 그대로 보여 준다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/data-store/backends', BACKENDS_ALL);
      await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
      await mockApi(
        page,
        'POST',
        '/api/platform/tenants',
        {
          status: 422,
          error: 'Unprocessable Entity',
          code: 'DATA_STORE_PROVISIONING_FAILED',
          message: '저장소에 접속하지 못했습니다: 연결 시간 초과',
        },
        { status: 422 },
      );
      await page.goto('/tenants/new');

      await fillBasics(page);
      await chooseTibero(page);
      await page.getByLabel('호스트').fill('192.168.179.80');
      await page.getByLabel('SID').fill('tibero');
      await page.getByLabel('DBA 계정', { exact: true }).fill('sys');
      await page.getByLabel('DBA 비밀번호').fill('tibero-pw');
      await page.getByRole('button', { name: '테넌트 생성' }).click();

      await expect(page.getByText('저장소에 접속하지 못했습니다: 연결 시간 초과')).toBeVisible();
      await expect(page).toHaveURL('/tenants/new');
    });
  });
});
