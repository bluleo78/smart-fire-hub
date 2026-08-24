import { createPlatformUser, createTenant } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, test } from './fixtures/auth.fixture';

test.describe('테넌트 생성', () => {
  test('폼과 도움말이 렌더된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await page.goto('/tenants/new');

    await expect(page.getByRole('heading', { name: '테넌트 생성' })).toBeVisible();
    await expect(page.getByLabel('이름')).toBeVisible();
    await expect(page.getByText('조직에 표시될 이름입니다.')).toBeVisible();
    await expect(page.getByLabel('slug')).toBeVisible();
    await expect(page.getByText('소문자·숫자·하이픈만 사용합니다.')).toBeVisible();
    await expect(page.getByText('생성 후에는 변경할 수 없습니다.')).toBeVisible();
    await expect(page.getByLabel('초기 Owner')).toBeVisible();
    await expect(
      page.getByText(
        '이 테넌트의 첫 소유자가 될 사용자입니다. Owner 없이는 아무도 이 테넌트에 로그인할 수 없습니다.',
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
    await page.getByLabel('slug').fill('hanbit');
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
    await page.getByLabel('slug').fill('-Bad_Slug');
    await page.getByRole('button', { name: '테넌트 생성' }).click();

    await expect(
      page.getByText('slug 는 소문자·숫자·하이픈만 사용할 수 있으며 소문자 또는 숫자로 시작해야 합니다.'),
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
    await page.getByLabel('slug').fill('hanbit');
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
    await page.getByLabel('slug').fill('hanbit');
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
      { status: 400, error: 'Bad Request', message: '이미 사용 중인 slug 입니다: hanbit' },
      { status: 400 },
    );
    await page.goto('/tenants/new');

    await page.getByLabel('이름').fill('한빛소방서');
    await page.getByLabel('slug').fill('hanbit');
    await page.getByLabel('초기 Owner').fill('박소');
    await page.waitForTimeout(400);
    await page.getByRole('option', { name: /박소유/ }).click();
    await page.getByRole('button', { name: '테넌트 생성' }).click();

    // 프런트가 문구를 다시 쓰지 않는다 — 서버가 유일한 판정자다.
    await expect(page.getByText('이미 사용 중인 slug 입니다: hanbit')).toBeVisible();
    await expect(page).toHaveURL('/tenants/new');
  });

  test('기타 실패는 토스트로 알린다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 42 })]);
    await mockApi(page, 'POST', '/api/platform/tenants', {}, { status: 500 });
    await page.goto('/tenants/new');

    await page.getByLabel('이름').fill('한빛소방서');
    await page.getByLabel('slug').fill('hanbit');
    await page.getByLabel('초기 Owner').fill('박소');
    await page.waitForTimeout(400);
    await page.getByRole('option', { name: /박소유/ }).click();
    await page.getByRole('button', { name: '테넌트 생성' }).click();

    await expect(page.getByText('테넌트 생성에 실패했습니다.')).toBeVisible();
  });
});
