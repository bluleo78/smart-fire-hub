import { createMember, createTenant } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

const ACTIVE = createTenant({ id: 1, name: '한빛소방서', slug: 'hanbit', status: 'ACTIVE', memberCount: 12, createdAt: '2026-03-04T09:21:14' });
const SUSPENDED = createTenant({ ...ACTIVE, status: 'SUSPENDED' });
const MEMBERS = [
  createMember({ userId: 10, username: 'kimsb', email: 'kim@example.com', role: 'OWNER', status: 'ACTIVE' }),
  createMember({ userId: 11, username: 'leejh', email: 'lee@example.com', role: 'MEMBER', status: 'ACTIVE' }),
];

test.describe('테넌트 상세', () => {
  test('기본 정보와 멤버 표를 그린다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await expect(page.getByRole('heading', { name: '한빛소방서' })).toBeVisible();
    await expect(page.getByText('기본 정보')).toBeVisible();
    await expect(page.getByText('hanbit')).toBeVisible();
    await expect(page.getByText('2026-03-04 09:21')).toBeVisible();
    await expect(page.getByText('멤버 (12)')).toBeVisible();
    await expect(page.getByRole('cell', { name: 'kim@example.com' })).toBeVisible();
    await expect(page.getByText('OWNER')).toBeVisible();
    await expect(
      page.getByText('역할은 워크스페이스 내 표시용이며, 실제 권한은 워크스페이스 관리자가 설정합니다.'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: '목록으로 돌아가기' })).toBeVisible();
  });

  test('정지 확인 다이얼로그가 이름·slug·인원·30분 지연을 말한다 (D-2)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    const capture = await mockApi(page, 'POST', '/api/platform/tenants/1/suspend', {}, { status: 204, capture: true });
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '테넌트 정지' }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('테넌트 정지')).toBeVisible();
    await expect(
      dialog.getByText(
        '"한빛소방서"(hanbit) 를 정지합니다. 이 워크스페이스의 멤버 12명은 새로 로그인하거나 세션을 갱신하는 시점부터 접근이 차단됩니다. 이미 발급된 세션은 최대 30분간 유효합니다. 정지는 언제든 되돌릴 수 있습니다.',
      ),
    ).toBeVisible();

    await page.getByRole('button', { name: '정지', exact: true }).click();
    await capture.waitForRequest();
    await expect(page.getByText('테넌트를 정지했습니다.')).toBeVisible();
  });

  test('정지 다이얼로그에서 취소하면 API 를 부르지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    const capture = await mockApi(page, 'POST', '/api/platform/tenants/1/suspend', {}, { status: 204, capture: true });
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '테넌트 정지' }).click();
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).not.toBeVisible();

    await page.waitForTimeout(300);
    expect(capture.lastRequest()).toBeUndefined();
  });

  test('활성화는 확인 없이 즉시 실행된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', SUSPENDED);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    const capture = await mockApi(page, 'POST', '/api/platform/tenants/1/activate', {}, { status: 204, capture: true });
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '테넌트 활성화' }).click();
    await expect(page.getByRole('alertdialog')).not.toBeVisible();
    await capture.waitForRequest();
    await expect(page.getByText('테넌트를 활성화했습니다.')).toBeVisible();
  });

  test('정지 실패는 토스트로 알린다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await mockApi(page, 'POST', '/api/platform/tenants/1/suspend', {}, { status: 500 });
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '테넌트 정지' }).click();
    await page.getByRole('button', { name: '정지', exact: true }).click();
    await expect(page.getByText('테넌트 정지에 실패했습니다.')).toBeVisible();
  });

  test('404 는 본문 전체를 교체한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/99', {}, { status: 404 });
    await mockApi(page, 'GET', '/api/platform/tenants/99/members', [], { status: 404 });
    await page.goto('/tenants/99');

    await expect(page.getByText('테넌트를 찾을 수 없습니다.')).toBeVisible();
    await expect(page.getByRole('link', { name: '목록으로 돌아가기' })).toBeVisible();
  });

  test('멤버 조회 403 이면 멤버 카드만 배너로 바뀌고 기본 정보는 남는다', async ({ page }) => {
    await loginAs(page, ['platform:tenant:read', 'platform:tenant:suspend']);
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', {}, { status: 403 });
    await page.goto('/tenants/1');

    await expect(page.getByText('기본 정보')).toBeVisible();
    await expect(page.getByText('hanbit')).toBeVisible();
    await expect(page.getByText('멤버를 조회할 권한이 없습니다.')).toBeVisible();
  });

  test('멤버 0명이면 빈 상태를 그린다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', createTenant({ id: 1, memberCount: 0 }));
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', []);
    await page.goto('/tenants/1');
    await expect(page.getByText('멤버가 없습니다.')).toBeVisible();
  });

  test('정지 권한이 없으면 액션 버튼을 렌더하지 않는다', async ({ page }) => {
    await loginAs(page, ['platform:tenant:read', 'platform:member:read']);
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await expect(page.getByRole('heading', { name: '한빛소방서' })).toBeVisible();
    await expect(page.getByRole('button', { name: '테넌트 정지' })).toHaveCount(0);
  });
});
