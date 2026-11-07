import { setupAdminAuth, setupUserListMocks } from '../fixtures/admin.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, MOCK_MEMBERSHIP, MOCK_TOKEN_RESPONSE, test } from '../fixtures/auth.fixture';
import { mockBrandingConfig } from '../fixtures/branding.fixture';
import { setupDatasetDetailMocks, setupDatasetMocks } from '../fixtures/dataset.fixture';
import { setupPipelineMocks } from '../fixtures/pipeline.fixture';

/**
 * 화면별 탭 제목 E2E (WD-580, WCAG 2.4.2 Page Titled).
 *
 * 수정 전에는 index.html·config.js 가 시작할 때 넣은 브랜드명 하나가 모든 화면의 제목이었다.
 * SPA 경로 이동(사이드바 클릭) 때마다 제목이 「화면 이름 · 브랜드명」으로 바뀌는지,
 * 홈은 브랜드명만 쓰는지, 404·로그인·관리 화면까지 같은 규칙인지 확인한다.
 */

test.describe('화면별 탭 제목', () => {
  test('사이드바로 이동할 때마다 제목이 「화면 이름 · 브랜드명」으로 바뀐다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupDatasetMocks(page);
    await setupPipelineMocks(page);
    await setupUserListMocks(page);

    // 홈은 브랜드명만
    await page.goto('/');
    await expect(page.getByRole('navigation').getByRole('link', { name: '홈' })).toBeVisible();
    await expect(page).toHaveTitle('Smart Fire Hub');

    // SPA 이동 — 새로고침 없이 경로만 바뀌어도 제목이 따라와야 한다(이 이슈의 핵심)
    await page.getByRole('link', { name: '데이터셋', exact: true }).click();
    await expect(page).toHaveURL(/\/data\/datasets$/);
    await expect(page).toHaveTitle('데이터셋 · Smart Fire Hub');

    await page.getByRole('link', { name: '파이프라인', exact: true }).click();
    await expect(page).toHaveURL(/\/pipelines$/);
    await expect(page).toHaveTitle('파이프라인 · Smart Fire Hub');

    // 관리 화면
    await page.getByRole('link', { name: '사용자 관리', exact: true }).click();
    await expect(page).toHaveURL(/\/admin\/users$/);
    await expect(page).toHaveTitle('사용자 관리 · Smart Fire Hub');

    // 홈으로 돌아오면 다시 브랜드명만
    await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page).toHaveTitle('Smart Fire Hub');
  });

  test('상세 화면은 섹션 이름을 쓴다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, 1);
    await page.goto('/data/datasets/1');
    await expect(page).toHaveTitle('데이터셋 · Smart Fire Hub');
  });

  test('없는 경로는 404 제목을 쓴다', async ({ authenticatedPage: page }) => {
    await page.goto('/this-page-does-not-exist-580');
    await expect(page.getByText('페이지를 찾을 수 없습니다')).toBeVisible();
    await expect(page).toHaveTitle('페이지를 찾을 수 없음 · Smart Fire Hub');
  });

  test('로그인 화면 제목', async ({ authMockedPage: page }) => {
    await page.goto('/login');
    await expect(page.getByLabel('아이디 (이메일)')).toBeVisible();
    await expect(page).toHaveTitle('로그인 · Smart Fire Hub');
  });

  test('브랜드명은 런타임 브랜딩(config.js)을 따른다', async ({ authenticatedPage: page }) => {
    await mockBrandingConfig(page, { brandName: 'Acme Data', logoUrl: null, faviconUrl: '/vite.svg' });
    await setupDatasetMocks(page);

    await page.goto('/');
    await expect(page).toHaveTitle('Acme Data');

    await page.getByRole('link', { name: '데이터셋', exact: true }).click();
    await expect(page).toHaveURL(/\/data\/datasets$/);
    await expect(page).toHaveTitle('데이터셋 · Acme Data');
    // 벤더 기본 브랜드가 제목에 남지 않아야 한다
    await expect(page).not.toHaveTitle(/Smart Fire Hub/);
  });

  /**
   * URL 은 그대로 두고 다른 화면을 그 자리에서 그리는 경우 — 경로 이름이 아니라 실제 보이는 화면 이름이어야 한다.
   */
  test('테넌트 미선택이면 원래 경로 대신 워크스페이스 선택 제목을 쓴다', async ({ authenticatedPage: page }) => {
    // 멤버십이 둘이라 백엔드가 자동 선택하지 않은 토큰 — ProtectedRoute 가 SelectTenantPage 를 그 자리에서 그린다
    await mockApi(page, 'POST', '/api/v1/auth/refresh', {
      ...MOCK_TOKEN_RESPONSE,
      activeTenantId: null,
      memberships: [MOCK_MEMBERSHIP, { ...MOCK_MEMBERSHIP, tenantId: 2, tenantSlug: 'second', tenantName: '두번째 워크스페이스' }],
    });

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: '워크스페이스 선택' })).toBeVisible();
    // URL 은 그대로인데 제목이 「데이터셋」이면 보이는 화면과 어긋난다
    await expect(page).toHaveURL(/\/data\/datasets$/);
    await expect(page).toHaveTitle('워크스페이스 선택 · Smart Fire Hub');
  });

  test('화면 권한이 없으면 접근 권한 없음 제목을 쓰고, 다른 화면으로 가면 풀린다', async ({ authenticatedPage: page }) => {
    // 기본 사용자는 USER 역할·빈 권한 — /admin/users(user:read) 는 레이아웃 안 권한 안내만 보인다
    await page.goto('/admin/users');
    await expect(page.getByTestId('screen-forbidden')).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/users$/);
    await expect(page).toHaveTitle('접근 권한 없음 · Smart Fire Hub');

    // 안내가 언마운트되면 덮어쓰기가 풀려 경로 기반 제목으로 돌아온다
    await setupDatasetMocks(page);
    await page.getByRole('link', { name: '데이터셋', exact: true }).click();
    await expect(page).toHaveURL(/\/data\/datasets$/);
    await expect(page).toHaveTitle('데이터셋 · Smart Fire Hub');
  });
});
