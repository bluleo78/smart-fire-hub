import { createTenant } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

const TENANTS = [
  createTenant({ id: 1, name: '한빛소방서', slug: 'hanbit', status: 'ACTIVE', memberCount: 12, createdAt: '2026-03-04T09:21:14' }),
  createTenant({ id: 2, name: '남부지사', slug: 'nambu', status: 'SUSPENDED', memberCount: 3, createdAt: '2026-05-19T11:00:00' }),
];

test.describe('테넌트 목록', () => {
  test('목록이 렌더되고 상태 배지가 붙는다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants', TENANTS);
    await page.goto('/tenants');

    await expect(page.getByRole('heading', { name: '테넌트' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'slug' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '상태' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '멤버' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '생성일' })).toBeVisible();

    await expect(page.getByRole('cell', { name: '한빛소방서', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'hanbit', exact: true })).toBeVisible();
    await expect(page.getByText('활성')).toBeVisible();
    await expect(page.getByText('정지됨')).toBeVisible();
    await expect(page.getByRole('cell', { name: '2026-03-04', exact: true })).toBeVisible();

    // 기본 정렬은 createdAt 내림차순 — 남부지사(2026-05-19)가 먼저다.
    const rows = page.getByRole('button', { name: /상세 보기/ });
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toHaveAttribute('aria-label', '테넌트 남부지사 상세 보기');
  });

  test('이름/slug 로 클라이언트 검색한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants', TENANTS);
    await page.goto('/tenants');

    const search = page.getByPlaceholder('이름 또는 slug로 검색...');
    await search.fill('nambu');
    await page.waitForTimeout(400);

    await expect(page.getByRole('button', { name: /상세 보기/ })).toHaveCount(1);
    await expect(page.getByRole('cell', { name: '남부지사', exact: true })).toBeVisible();
  });

  test('검색 결과가 0건이면 검색 빈 상태와 초기화 버튼이 나온다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants', TENANTS);
    await page.goto('/tenants');

    await page.getByPlaceholder('이름 또는 slug로 검색...').fill('없는이름');
    await page.waitForTimeout(400);

    await expect(page.getByText("'없는이름'에 대한 결과가 없습니다.")).toBeVisible();
    await page.getByRole('button', { name: '검색 초기화' }).click();
    await expect(page.getByRole('button', { name: /상세 보기/ })).toHaveCount(2);
  });

  test('상태 필터가 동작한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants', TENANTS);
    await page.goto('/tenants');

    await page.getByLabel('상태 필터').click();
    await page.getByRole('option', { name: '정지됨' }).click();

    await expect(page.getByRole('button', { name: /상세 보기/ })).toHaveCount(1);
    await expect(page.getByRole('cell', { name: '남부지사', exact: true })).toBeVisible();
  });

  test('행 전체가 상세 이동 액션이다 (클릭/Enter)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants', TENANTS);
    await mockApi(page, 'GET', '/api/platform/tenants/1', TENANTS[0]);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', []);
    await page.goto('/tenants');

    const row = page.getByRole('button', { name: '테넌트 한빛소방서 상세 보기' });
    const tabIndex = await row.evaluate((el) => (el as HTMLElement).tabIndex);
    expect(tabIndex).toBe(0);

    await row.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL('/tenants/1');
  });

  test('빈 목록은 전용 문구를 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants', []);
    await page.goto('/tenants');
    await expect(page.getByText('등록된 테넌트가 없습니다.')).toBeVisible();
  });

  test('조회 실패는 표 안에 에러 문구를 그린다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants', {}, { status: 500 });
    await page.goto('/tenants');
    await expect(page.getByText('데이터를 불러오는데 실패했습니다.')).toBeVisible();
  });

  test('403 이면 본문을 권한 배너로 교체한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants', {}, { status: 403 });
    await page.goto('/tenants');
    // role 까지 고정한다 — InlineBanner 는 role="status" + aria-live="polite" 다.
    // 문구만 잡으면 나중에 누가 평범한 <p> 로 바꿔도 테스트가 통과해 버린다.
    await expect(
      page.getByRole('status').filter({ hasText: '이 작업을 수행할 권한이 없습니다.' }),
    ).toBeVisible();
  });

  test('생성 권한이 없으면 생성 버튼을 렌더하지 않는다', async ({ page }) => {
    await loginAs(page, ['platform:tenant:read', 'platform:settings:read']);
    await mockApi(page, 'GET', '/api/platform/tenants', TENANTS);
    await page.goto('/tenants');

    await expect(page.getByRole('heading', { name: '테넌트' })).toBeVisible();
    await expect(page.getByRole('link', { name: '테넌트 생성' })).toHaveCount(0);
  });

  test('20건을 넘으면 클라이언트 페이지네이션이 나온다', async ({ authenticatedPage: page }) => {
    const many = Array.from({ length: 24 }, (_, i) =>
      createTenant({ id: i + 1, name: `테넌트 ${i + 1}`, slug: `t${i + 1}`, createdAt: '2026-01-01T00:00:00' }),
    );
    await mockApi(page, 'GET', '/api/platform/tenants', many);
    await page.goto('/tenants');

    await expect(page.getByRole('button', { name: /상세 보기/ })).toHaveCount(20);
    await expect(page.getByText('총 24건 중 1-20')).toBeVisible();
    await page.getByRole('button', { name: '다음 페이지' }).click();
    await expect(page.getByRole('button', { name: /상세 보기/ })).toHaveCount(4);
  });
});
