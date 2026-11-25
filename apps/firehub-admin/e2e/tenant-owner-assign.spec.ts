import { createMember, createTenant } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

/**
 * 테넌트 상세 「소유자로 지정」(WD-1132). 운영자가 멤버 한 명을 워크스페이스 소유자로 지정한다.
 * 서버 응답은 모두 mock — 요청 본문·토스트·목록 재조회·버튼 노출 규칙·오류 안내를 검증한다.
 */
const TENANT = createTenant({ id: 1, name: '한빛소방서', slug: 'hanbit', memberCount: 4 });
const MEMBERS = [
  createMember({ userId: 10, username: 'kimsb', email: 'kim@example.com', role: 'OWNER' }),
  createMember({ userId: 11, username: 'leead', email: 'lee@example.com', role: 'ADMIN' }),
  createMember({ userId: 12, username: 'parkmb', email: 'park@example.com', role: 'MEMBER', status: 'SUSPENDED' }),
  createMember({ userId: 13, username: 'choimb', email: 'choi@example.com', role: 'MEMBER', accountActive: false }),
];

test.describe('테넌트 소유자 지정 (WD-1132)', () => {
  test('운영자가 멤버를 소유자로 지정한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', TENANT);
    const membersCapture = await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS, { capture: true });
    const capture = await mockApi(page, 'POST', '/api/platform/tenants/1/owner', null, { status: 204, capture: true });
    await page.goto('/tenants/1');
    await expect(page.getByRole('cell', { name: 'lee@example.com' })).toBeVisible();
    const before = membersCapture.requests.length;

    await page.getByRole('button', { name: '소유자로 지정' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByRole('heading', { name: 'leead을(를) 소유자로 지정할까요?' })).toBeVisible();
    await dialog.getByLabel('확인하려면 테넌트 이름을 입력하세요').fill('  한빛소방서 ');
    await dialog.getByRole('button', { name: '소유자로 지정' }).click();

    const request = await capture.waitForRequest();
    expect(request.payload).toEqual({ userId: 11, confirmName: '한빛소방서' });
    await expect(page.getByText('소유자를 지정했습니다')).toBeVisible();
    await expect.poll(() => membersCapture.requests.length).toBeGreaterThan(before);
  });

  test('저장된 테넌트 이름 앞뒤에 공백이 있어도 공백 없이 입력하면 지정할 수 있다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', { ...TENANT, name: ' 한빛소방서 ' });
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    const capture = await mockApi(page, 'POST', '/api/platform/tenants/1/owner', null, { status: 204, capture: true });
    await page.goto('/tenants/1');
    await expect(page.getByRole('cell', { name: 'lee@example.com' })).toBeVisible();

    await page.getByRole('button', { name: '소유자로 지정' }).click();
    const dialog = page.getByRole('alertdialog');
    await dialog.getByLabel('확인하려면 테넌트 이름을 입력하세요').fill('한빛소방서');
    const confirm = dialog.getByRole('button', { name: '소유자로 지정' });
    await expect(confirm).toBeEnabled();
    await confirm.click();

    const request = await capture.waitForRequest();
    expect(request.payload).toEqual({ userId: 11, confirmName: '한빛소방서' });
  });

  test('platform:tenant:create 권한이 없으면 「소유자로 지정」 버튼이 없다', async ({ page }) => {
    await loginAs(page, ['platform:tenant:read', 'platform:member:read']);
    await mockApi(page, 'GET', '/api/platform/tenants/1', TENANT);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await expect(page.getByRole('cell', { name: 'lee@example.com' })).toBeVisible();
    await expect(page.getByRole('button', { name: '소유자로 지정' })).toHaveCount(0);
  });

  test('응답이 늦어 확인 창이 먼저 닫혀도 토스트와 목록 재조회가 일어난다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', TENANT);
    const membersCapture = await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS, { capture: true });
    await page.route(
      (url) => url.pathname === '/api/platform/tenants/1/owner',
      async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 800));
        await route.fulfill({ status: 204 });
      },
    );
    await page.goto('/tenants/1');
    await expect(page.getByRole('cell', { name: 'lee@example.com' })).toBeVisible();
    const before = membersCapture.requests.length;

    await page.getByRole('button', { name: '소유자로 지정' }).click();
    const dialog = page.getByRole('alertdialog');
    await dialog.getByLabel('확인하려면 테넌트 이름을 입력하세요').fill('한빛소방서');
    await dialog.getByRole('button', { name: '소유자로 지정' }).click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    await expect(page.getByText('소유자를 지정했습니다')).toBeVisible();
    await expect.poll(() => membersCapture.requests.length).toBeGreaterThan(before);
  });

  test('소유자·정지된 멤버십·비활성 계정 행에는 버튼이 없다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', TENANT);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');
    await expect(page.getByRole('cell', { name: 'lee@example.com' })).toBeVisible();

    // 활성 ADMIN 한 명뿐 — OWNER(kimsb)·정지 멤버십(parkmb)·비활성 계정(choimb)은 대상이 아니다.
    await expect(page.getByRole('button', { name: '소유자로 지정' })).toHaveCount(1);
    await expect(page.getByRole('row', { name: /kimsb/ }).getByRole('button')).toHaveCount(0);
    await expect(page.getByRole('row', { name: /parkmb/ }).getByRole('button')).toHaveCount(0);
    await expect(page.getByRole('row', { name: /choimb/ }).getByRole('button')).toHaveCount(0);
  });

  test('테넌트 이름이 틀리면 확인 버튼이 비활성이다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', TENANT);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '소유자로 지정' }).click();
    const dialog = page.getByRole('alertdialog');
    const confirm = dialog.getByRole('button', { name: '소유자로 지정' });
    await expect(confirm).toBeDisabled();
    await dialog.getByLabel('확인하려면 테넌트 이름을 입력하세요').fill('한빛');
    await expect(confirm).toBeDisabled();
    await dialog.getByLabel('확인하려면 테넌트 이름을 입력하세요').fill('한빛소방서');
    await expect(confirm).toBeEnabled();
    await dialog.getByLabel('확인하려면 테넌트 이름을 입력하세요').fill('');
    await expect(confirm).toBeDisabled();
  });

  test('서버 409 메시지를 토스트로 보여 준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', TENANT);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.route(
      (url) => url.pathname === '/api/platform/tenants/1/owner',
      async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 800));
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ status: 409, error: 'Conflict', message: '이미 이 워크스페이스의 유일한 소유자입니다' }),
        });
      },
    );
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '소유자로 지정' }).click();
    const dialog = page.getByRole('alertdialog');
    await dialog.getByLabel('확인하려면 테넌트 이름을 입력하세요').fill('한빛소방서');
    await dialog.getByRole('button', { name: '소유자로 지정' }).click();

    await expect(page.getByText('이미 이 워크스페이스의 유일한 소유자입니다')).toBeVisible();
  });
});
