import type { Page } from '@playwright/test';

import { createAdminUserDetail, createRole, createUserDetail } from '../../factories/auth.factory';
import {
  setupAdminAuth,
  setupApiConnectionDetailMocks,
  setupApiConnectionListMocks,
  setupAuditLogMocks,
  setupRoleDetailMocks,
  setupRoleListMocks,
  setupUserDetailMocks,
  setupUserListMocks,
} from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/**
 * 관리 화면 권한 기반 접근 (WD-121).
 *
 * 관리 화면은 예전에 역할 이름(ADMIN)으로만 열려, 역할에 user:read·role:read·audit:read 를 줘도 웹에서 아무 효과가 없었고
 * 「자동화 > API 연결」은 모두에게 보이는데 누르면 말없이 홈으로 튕겼다. 이제 화면·메뉴는 화면별 권한 코드(ADMIN 은 전부)로,
 * 화면 안 추가·수정·삭제 버튼은 서버가 막는 권한 코드로만 노출하고, 권한 없는 직접 URL 은 레이아웃 안 안내를 보인다.
 *
 * 기본 사용자(authenticatedPage)는 USER 역할(비 ADMIN)이고, 권한 코드는 각 테스트가 /auth/me/permissions 로 준다.
 */

const FORBIDDEN_MESSAGE = '이 화면을 볼 권한이 없습니다. 관리자에게 권한을 요청하세요.';

/** 권한 응답이 도착한 뒤에 판정하도록 이동한다 — 권한이 오기 전에는 메뉴·버튼이 원래 없어 음성 단언이 공허해진다. */
async function gotoWithPermissions(page: Page, path: string) {
  const permissions = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/auth/me/permissions');
  await page.goto(path);
  await permissions;
}

/** 응답 처리 후 한 프레임 뒤(리렌더 반영) — 「없다」 단언 직전에 둔다. */
async function nextFrame(page: Page) {
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
}

async function grant(page: Page, permissions: string[]) {
  await mockApi(page, 'GET', '/api/v1/auth/me/permissions', permissions);
}

/** 사용자 상세·멤버 추가가 고를 역할 — 시스템 USER·ADMIN 과 사용자 정의 EDITOR. */
const ROLES_WITH_EDITOR = [
  createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
  createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자', isSystem: true }),
  createRole({ id: 3, name: 'EDITOR', description: '편집자', isSystem: false }),
];
const ADMIN_ROLE_LOCK_HINT = '시스템 ADMIN 역할은 ADMIN 만 부여하거나 회수할 수 있습니다';

/** 응답을 손으로 붙잡아 두는 권한 모킹 — 조회 중 화면을 관찰하려고 쓴다. release() 를 부르면 permissions 로 응답한다. */
async function holdPermissions(page: Page, permissions: string[]) {
  let release: () => void = () => {};
  const released = new Promise<void>((r) => {
    release = r;
  });
  let requested = false;
  await page.route(
    (url) => url.pathname === '/api/v1/auth/me/permissions',
    async (route) => {
      requested = true;
      await released;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(permissions) });
    },
  );
  return { release: () => release(), wasRequested: () => requested };
}

const sidebarLink = (page: Page, name: string) => page.getByRole('navigation').getByRole('link', { name, exact: true });

/** 직접 URL 로 들어왔을 때: 홈으로 튕기지 않고(URL 유지) 레이아웃 안에서 권한 안내, 화면 제목은 없다. */
async function expectForbidden(page: Page, path: RegExp, heading: string) {
  await expect(page.getByTestId('screen-forbidden')).toHaveText(FORBIDDEN_MESSAGE);
  await expect(page).toHaveURL(path);
  await expect(page.getByRole('heading', { level: 1, name: heading })).toHaveCount(0);
  // 레이아웃 안 — 사이드바가 함께 그려져 있다.
  await expect(sidebarLink(page, '홈')).toBeVisible();
}

test.describe('사용자 관리 — user:read', () => {
  test('user:read 만 있으면 메뉴가 보이고 목록·상세가 열리지만 쓰기 진입점은 없다', async ({ authenticatedPage: page }) => {
    await grant(page, ['user:read']);
    await setupUserListMocks(page);
    await setupUserDetailMocks(page, 5);
    // 역할 할당 권한이 없으면 역할 목록(GET /roles, role:read)을 읽지 않아야 한다 — 읽으면 403 으로 상세가 안 열렸다.
    const rolesCapture = await mockApi(page, 'GET', '/api/v1/roles', [], { capture: true });

    await gotoWithPermissions(page, '/admin/users');
    await expect(sidebarLink(page, '사용자 관리')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: '사용자 관리' })).toBeVisible();
    await expect(page.getByRole('button', { name: '사용자 사용자 1 상세 보기' })).toBeVisible();
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '멤버 추가' })).toHaveCount(0);

    await page.goto('/admin/users/5');
    await expect(page.getByRole('heading', { level: 1, name: '사용자 상세' })).toBeVisible();
    await expect(page.getByText('user5', { exact: true })).toBeVisible();
    // 역할은 읽기 전용 목록으로 — 응답의 역할 이름이 그대로 보인다.
    await expect(page.getByRole('list', { name: '보유 역할' })).toHaveText('USER');
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '역할 저장' })).toHaveCount(0);
    await expect(page.getByRole('switch', { name: '이 워크스페이스에서 활성' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '제거' })).toHaveCount(0);
    expect(rolesCapture.requests).toHaveLength(0);
  });

  test('user:write·role:assign·role:read 가 있으면 같은 화면에 쓰기 진입점이 보이고 역할 저장이 PUT 된다', async ({
    authenticatedPage: page,
  }) => {
    await grant(page, ['user:read', 'user:write', 'role:assign', 'role:read']);
    await setupUserListMocks(page);
    await setupUserDetailMocks(page, 5);
    await mockApi(page, 'GET', '/api/v1/roles', ROLES_WITH_EDITOR);
    const put = await mockApi(page, 'PUT', '/api/v1/users/5/roles', {}, { capture: true });

    await gotoWithPermissions(page, '/admin/users');
    await expect(page.getByRole('button', { name: '사용자 사용자 1 상세 보기' })).toBeVisible();
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '멤버 추가' })).toHaveCount(1);

    await page.goto('/admin/users/5');
    await expect(page.getByRole('heading', { level: 1, name: '사용자 상세' })).toBeVisible();
    await expect(page.getByRole('switch', { name: '이 워크스페이스에서 활성' })).toBeVisible();
    await expect(page.getByRole('button', { name: '제거' })).toBeVisible();
    // 위임받은 역할 관리자(비 ADMIN)는 시스템 ADMIN 역할을 붙일 수 없다(WD-116) — 잠금과 이유가 함께 보인다.
    await expect(page.getByRole('checkbox', { name: /ADMIN/ })).toBeDisabled();
    await expect(page.getByText(ADMIN_ROLE_LOCK_HINT)).toBeVisible();
    // 사용자 정의 역할은 고를 수 있고, 저장하면 그대로 PUT 된다.
    await page.getByRole('checkbox', { name: /EDITOR/ }).check();
    await page.getByRole('button', { name: '역할 저장' }).click();
    expect((await put.waitForRequest()).payload).toEqual({ roleIds: [1, 3] });
  });

  test('비 ADMIN 은 대상이 가진 시스템 ADMIN 역할도 뗄 수 없다', async ({ authenticatedPage: page }) => {
    await grant(page, ['user:read', 'role:assign', 'role:read']);
    await mockApi(page, 'GET', '/api/v1/roles', ROLES_WITH_EDITOR);
    await mockApi(
      page,
      'GET',
      '/api/v1/users/6',
      createUserDetail({ id: 6, username: 'user6', roles: [createRole(), createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자' })] }),
    );

    await gotoWithPermissions(page, '/admin/users/6');
    const adminRole = page.getByRole('checkbox', { name: /ADMIN/ });
    await expect(adminRole).toBeChecked();
    await expect(adminRole).toBeDisabled();
    await expect(page.getByText(ADMIN_ROLE_LOCK_HINT)).toBeVisible();
    await expect(page.getByRole('checkbox', { name: /EDITOR/ })).toBeEnabled();
  });

  test('ADMIN 은 사용자 상세에서 시스템 ADMIN 역할을 그대로 고를 수 있다(대조군)', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupUserDetailMocks(page, 5);
    await mockApi(page, 'GET', '/api/v1/roles', ROLES_WITH_EDITOR);
    const put = await mockApi(page, 'PUT', '/api/v1/users/5/roles', {}, { capture: true });

    await gotoWithPermissions(page, '/admin/users/5');
    await expect(page.getByRole('checkbox', { name: /ADMIN/ })).toBeEnabled();
    await expect(page.getByText(ADMIN_ROLE_LOCK_HINT)).toHaveCount(0);
    await page.getByRole('checkbox', { name: /ADMIN/ }).check();
    await page.getByRole('button', { name: '역할 저장' }).click();
    expect((await put.waitForRequest()).payload).toEqual({ roleIds: [1, 2] });
  });

  test('비 ADMIN 의 멤버 추가 창에서도 시스템 ADMIN 역할은 잠겨 있다', async ({ authenticatedPage: page }) => {
    await grant(page, ['user:read', 'user:write', 'role:assign', 'role:read']);
    await setupUserListMocks(page);
    await mockApi(page, 'GET', '/api/v1/roles', ROLES_WITH_EDITOR);
    const post = await mockApi(page, 'POST', '/api/v1/users', { userId: 9, created: true }, { status: 201, capture: true });

    await gotoWithPermissions(page, '/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    await expect(dialog.getByRole('checkbox', { name: /ADMIN/ })).toBeDisabled();
    await expect(dialog.getByText(ADMIN_ROLE_LOCK_HINT)).toBeVisible();
    await dialog.getByLabel('이메일').fill('n@acme.io');
    await dialog.getByLabel('이름').fill('N');
    await dialog.getByRole('checkbox', { name: /EDITOR/ }).check();
    await dialog.getByRole('button', { name: '추가' }).click();
    expect((await post.waitForRequest()).payload).toMatchObject({ roleIds: [3] });
  });

  test('role:assign 은 있지만 role:read 가 없으면 사용자 상세의 역할은 읽기 전용이고 역할 목록을 읽지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await grant(page, ['user:read', 'role:assign']);
    await setupUserDetailMocks(page, 5);
    const rolesCapture = await mockApi(page, 'GET', '/api/v1/roles', ROLES_WITH_EDITOR, { capture: true });

    await gotoWithPermissions(page, '/admin/users/5');
    await expect(page.getByRole('heading', { level: 1, name: '사용자 상세' })).toBeVisible();
    await expect(page.getByRole('list', { name: '보유 역할' })).toHaveText('USER');
    await nextFrame(page);
    await expect(page.getByRole('checkbox')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '역할 저장' })).toHaveCount(0);
    expect(rolesCapture.requests).toHaveLength(0);
  });

  test('user:write 만 있고 role:read 가 없으면 멤버 추가 창이 역할 목록을 읽지 않고 오류도 보이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await grant(page, ['user:read', 'user:write']);
    await setupUserListMocks(page);
    const rolesCapture = await mockApi(page, 'GET', '/api/v1/roles', [], { capture: true });
    const post = await mockApi(page, 'POST', '/api/v1/users', { userId: 9, created: true }, { status: 201, capture: true });

    await gotoWithPermissions(page, '/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    await expect(dialog.getByText('역할 지정 권한이 없어 USER 역할로만 추가됩니다')).toBeVisible();
    await dialog.getByLabel('이메일').fill('n@acme.io');
    await dialog.getByLabel('이름').fill('N');
    await dialog.getByRole('button', { name: '추가' }).click();
    expect((await post.waitForRequest()).payload).toMatchObject({ roleIds: [] });
    // 권한 상태를 「불러오지 못했습니다」 오류로 그리지 않는다 — 확실히 403 일 요청 자체를 보내지 않는다.
    await expect(page.getByText('역할 목록을 불러오지 못했습니다', { exact: false })).toHaveCount(0);
    expect(rolesCapture.requests).toHaveLength(0);
  });

  test('user:read 가 없으면 메뉴가 없고 직접 URL 은 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    // 대조: 다른 관리 권한(role:read)은 있어 「관리」 그룹 자체는 그려진다 — 「사용자 관리」 항목만 빠져야 한다.
    await grant(page, ['role:read']);
    await setupUserListMocks(page);
    const usersCapture = await mockApi(page, 'GET', '/api/v1/users', { content: [] }, { capture: true });

    await gotoWithPermissions(page, '/admin/users');
    await expectForbidden(page, /\/admin\/users$/, '사용자 관리');
    await expect(sidebarLink(page, '역할 관리')).toBeVisible();
    await expect(sidebarLink(page, '사용자 관리')).toHaveCount(0);
    // 화면을 그리지 않으므로 목록 요청도 보내지 않는다.
    expect(usersCapture.requests).toHaveLength(0);
  });
});

test.describe('역할 관리 — role:read', () => {
  test('role:read 만 있으면 목록·상세가 열리고 편집 진입점 없이 가진 권한만 읽기 전용으로 보인다', async ({
    authenticatedPage: page,
  }) => {
    await grant(page, ['role:read']);
    await setupRoleListMocks(page);
    await setupRoleDetailMocks(page, 3, false);
    await setupSecurityLevelMocks(page);
    await mockApi(page, 'GET', '/api/v1/roles/3/clearance', { roleId: 3, securityLevelId: 2, userCount: 14, fixed: false });
    // 전체 권한 목록(GET /permissions)은 permission:read — 없으면 읽지 않아야 한다(읽으면 403 으로 상세가 안 열렸다).
    const catalogCapture = await mockApi(page, 'GET', '/api/v1/permissions', [], { capture: true });

    await gotoWithPermissions(page, '/admin/roles');
    await expect(sidebarLink(page, '역할 관리')).toBeVisible();
    await expect(page.getByRole('cell', { name: 'EDITOR', exact: true })).toBeVisible();
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '역할 추가' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'EDITOR 역할 삭제' })).toHaveCount(0);

    await page.getByRole('cell', { name: 'EDITOR', exact: true }).click();
    await expect(page).toHaveURL(/\/admin\/roles\/3$/);
    await expect(page.getByRole('heading', { level: 1, name: '역할 상세' })).toBeVisible();
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');
    await expect(page.getByLabel('역할 이름')).toBeDisabled();
    // 이 역할이 가진 권한(응답의 2개)만, 체크된 채 잠겨 있다.
    await expect(page.getByRole('checkbox', { name: 'DATASET_WRITE' })).toBeChecked();
    await expect(page.getByRole('checkbox', { name: 'DATASET_WRITE' })).toBeDisabled();
    await expect(page.getByRole('checkbox')).toHaveCount(2);
    const card = page.getByTestId('role-clearance-card');
    await expect(card.getByRole('radio', { name: /내부/ })).toBeChecked();
    await expect(card.getByRole('radio', { name: /내부/ })).toBeDisabled();
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '권한 저장' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
    expect(catalogCapture.requests).toHaveLength(0);
  });

  test('role:write·role:delete·permission:read 가 있으면 편집 진입점이 보이고 권한 저장이 PUT 된다', async ({
    authenticatedPage: page,
  }) => {
    // 호출자는 PIPELINE_READ 만 갖고 ADMIN_ACCESS 는 없다 — 자기에게 없는 권한은 역할에 붙일 수 없다(WD-116).
    await grant(page, ['role:read', 'role:write', 'role:delete', 'permission:read', 'PIPELINE_READ']);
    await setupRoleListMocks(page);
    await setupRoleDetailMocks(page, 3, false);
    await setupSecurityLevelMocks(page);
    await mockApi(page, 'GET', '/api/v1/roles/3/clearance', { roleId: 3, securityLevelId: 2, userCount: 14, fixed: false });
    const put = await mockApi(page, 'PUT', '/api/v1/roles/3/permissions', {}, { capture: true });

    await gotoWithPermissions(page, '/admin/roles');
    await expect(page.getByRole('cell', { name: 'EDITOR', exact: true })).toBeVisible();
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '역할 추가' })).toHaveCount(1);
    await expect(page.getByRole('button', { name: 'EDITOR 역할 삭제' })).toHaveCount(1);

    await page.goto('/admin/roles/3');
    await expect(page.getByLabel('역할 이름')).toBeEnabled();
    // 전체 목록(5개)이 보인다.
    await expect(page.getByRole('checkbox')).toHaveCount(5);
    // 갖지 않은 권한(ADMIN_ACCESS)은 추가할 수 없게 잠기고 이유가 보인다. 이미 붙어 있던 권한(DATASET_READ)은 호출자에게 없어도 뺄 수 있다.
    await expect(page.getByRole('checkbox', { name: 'ADMIN_ACCESS' })).toBeDisabled();
    await expect(page.getByText('자신에게 없는 권한은 추가할 수 없습니다')).toBeVisible();
    await expect(page.getByRole('checkbox', { name: 'DATASET_READ' })).toBeEnabled();
    await page.getByRole('checkbox', { name: 'PIPELINE_READ' }).check();
    await page.getByRole('checkbox', { name: 'DATASET_WRITE' }).uncheck();
    await expect(page.getByTestId('role-clearance-card').getByRole('button', { name: '저장' })).toBeVisible();
    await page.getByRole('button', { name: '권한 저장' }).click();
    expect((await put.waitForRequest()).payload).toEqual({ permissionIds: [1, 3] });
  });

  test('ADMIN 은 역할 상세에서 어떤 권한이든 추가할 수 있다(대조군)', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupRoleDetailMocks(page, 3, false);
    await setupSecurityLevelMocks(page);
    await mockApi(page, 'GET', '/api/v1/roles/3/clearance', { roleId: 3, securityLevelId: 2, userCount: 14, fixed: false });
    const put = await mockApi(page, 'PUT', '/api/v1/roles/3/permissions', {}, { capture: true });

    await gotoWithPermissions(page, '/admin/roles/3');
    await expect(page.getByRole('checkbox')).toHaveCount(5);
    await expect(page.getByRole('checkbox', { name: 'ADMIN_ACCESS' })).toBeEnabled();
    await expect(page.getByText('자신에게 없는 권한은 추가할 수 없습니다')).toHaveCount(0);
    await page.getByRole('checkbox', { name: 'ADMIN_ACCESS' }).check();
    await page.getByRole('button', { name: '권한 저장' }).click();
    expect((await put.waitForRequest()).payload).toEqual({ permissionIds: [1, 2, 5] });
  });

  test('role:read 가 없으면 메뉴가 없고 직접 URL 은 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await grant(page, ['user:read']);
    await setupUserListMocks(page);

    await gotoWithPermissions(page, '/admin/roles/3');
    await expectForbidden(page, /\/admin\/roles\/3$/, '역할 상세');
    await expect(sidebarLink(page, '사용자 관리')).toBeVisible();
    await expect(sidebarLink(page, '역할 관리')).toHaveCount(0);
  });
});

test.describe('감사 로그 — audit:read', () => {
  test('audit:read 만 있으면 감사 로그가 열리고, 사용자 목록 권한이 없으니 사용자 필터는 없다', async ({
    authenticatedPage: page,
  }) => {
    await grant(page, ['audit:read']);
    await setupAuditLogMocks(page);
    const usersCapture = await mockApi(page, 'GET', '/api/v1/users', { content: [] }, { capture: true });

    await gotoWithPermissions(page, '/admin/audit-logs');
    await expect(sidebarLink(page, '감사 로그')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: '감사 로그' })).toBeVisible();
    await expect(page.getByText('감사 로그 1', { exact: true })).toBeVisible();
    await nextFrame(page);
    await expect(page.getByRole('combobox', { name: '사용자 필터' })).toHaveCount(0);
    // user:read 없는 사람에게 확실히 403 일 GET /users 를 보내지 않는다.
    expect(usersCapture.requests).toHaveLength(0);
  });

  test('audit:read 와 user:read 가 있으면 사용자 필터가 보이고 목록을 채운다(대조군)', async ({ authenticatedPage: page }) => {
    await grant(page, ['audit:read', 'user:read']);
    await setupAuditLogMocks(page);

    await gotoWithPermissions(page, '/admin/audit-logs');
    await expect(page.getByText('감사 로그 1', { exact: true })).toBeVisible();
    await page.getByRole('combobox', { name: '사용자 필터' }).click();
    await expect(page.getByRole('option', { name: '관리자 (admin)' })).toBeVisible();
  });

  test('audit:read 가 없으면 메뉴가 없고 직접 URL 은 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await grant(page, ['user:read']);
    await setupUserListMocks(page);
    const auditCapture = await mockApi(page, 'GET', '/api/v1/admin/audit-logs', { content: [] }, { capture: true });

    await gotoWithPermissions(page, '/admin/audit-logs');
    await expectForbidden(page, /\/admin\/audit-logs$/, '감사 로그');
    await expect(sidebarLink(page, '사용자 관리')).toBeVisible();
    await expect(sidebarLink(page, '감사 로그')).toHaveCount(0);
    expect(auditCapture.requests).toHaveLength(0);
  });
});

test.describe('API 연결 — apiconnection:read', () => {
  // 일반 USER 역할의 시드 권한(V1·V26) — apiconnection:read 를 기본으로 가진다.
  const USER_ROLE_PERMISSIONS = ['user:read:self', 'user:write:self', 'apiconnection:read'];

  test('일반 USER 가 「자동화 > API 연결」을 누르면 홈으로 튕기지 않고 목록이 열린다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await grant(page, USER_ROLE_PERMISSIONS);
    await setupApiConnectionListMocks(page);

    await gotoWithPermissions(page, '/');
    await sidebarLink(page, 'API 연결').click();
    await expect(page).toHaveURL(/\/admin\/api-connections$/);
    await expect(page.getByRole('heading', { level: 1, name: 'API 연결 관리' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '공공 데이터 API' })).toBeVisible();
    // 읽기만 가능 — 새 연결·전체 갱신·삭제 진입점이 없다.
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '새 연결' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '전체 갱신' })).toHaveCount(0);
    await expect(page.getByRole('row', { name: /공공 데이터 API/ }).getByRole('button')).toHaveCount(0);
    // USER 는 관리 권한이 하나도 없으므로 「관리」 그룹은 통째로 없다.
    await expect(page.getByRole('navigation').getByRole('button', { name: '관리', exact: true })).toHaveCount(0);
  });

  test('읽기 전용 사용자는 상세에서 입력이 잠기고 확인·저장·인증 변경·삭제가 없다', async ({ authenticatedPage: page }) => {
    await grant(page, USER_ROLE_PERMISSIONS);
    await setupApiConnectionDetailMocks(page, 1);
    const testCapture = await mockApi(page, 'POST', '/api/v1/api-connections/1/test', {}, { capture: true });

    await gotoWithPermissions(page, '/admin/api-connections/1');
    await expect(page.getByRole('heading', { level: 1, name: 'API 연결 상세' })).toBeVisible();
    await expect(page.getByLabel('연결 이름')).toHaveValue('공공 데이터 API');
    await expect(page.getByLabel('연결 이름')).toBeDisabled();
    await expect(page.getByLabel('Base URL')).toBeDisabled();
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '지금 확인' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '인증 정보 변경' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '이 연결 삭제' })).toHaveCount(0);
    expect(testCapture.requests).toHaveLength(0);
  });

  test('apiconnection:write·delete 가 있으면 목록·상세에 쓰기 진입점이 보이고 지금 확인이 요청된다', async ({
    authenticatedPage: page,
  }) => {
    await grant(page, [...USER_ROLE_PERMISSIONS, 'apiconnection:write', 'apiconnection:delete']);
    await setupApiConnectionListMocks(page);
    await setupApiConnectionDetailMocks(page, 1);

    await gotoWithPermissions(page, '/admin/api-connections');
    await expect(page.getByRole('cell', { name: '공공 데이터 API' })).toBeVisible();
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '새 연결' })).toHaveCount(1);
    await expect(page.getByRole('button', { name: '전체 갱신' })).toHaveCount(1);
    await expect(page.getByRole('row', { name: /공공 데이터 API/ }).getByRole('button')).toHaveCount(1);

    const testRequest = page.waitForRequest(
      (r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/v1/api-connections/1/test',
    );
    await page.goto('/admin/api-connections/1');
    await expect(page.getByLabel('연결 이름')).toBeEnabled();
    await expect(page.getByRole('button', { name: '인증 정보 변경' })).toBeVisible();
    await expect(page.getByRole('button', { name: '이 연결 삭제' })).toBeVisible();
    await page.getByRole('button', { name: '지금 확인' }).click();
    await testRequest;
    await expect(page.getByTestId('test-result-card')).toBeVisible();
  });

  test('apiconnection:read 가 없으면 메뉴가 없고 직접 URL 은 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await grant(page, ['user:read:self', 'user:write:self']);

    await gotoWithPermissions(page, '/admin/api-connections');
    await expectForbidden(page, /\/admin\/api-connections$/, 'API 연결 관리');
    // 같은 그룹의 「파이프라인」은 그대로 보인다 — 「API 연결」만 빠진다.
    await expect(sidebarLink(page, '파이프라인')).toBeVisible();
    await expect(sidebarLink(page, 'API 연결')).toHaveCount(0);
  });
});

test.describe('권한 조회 중 판단 보류·조회 실패', () => {
  test('권한 응답이 오기 전에는 권한 안내를 그리지 않고, 응답 후 화면이 열린다', async ({ authenticatedPage: page }) => {
    await setupUserListMocks(page);
    // 권한 응답을 손으로 붙잡아 둔다 — 그 사이 「권한 없음」으로 판정해 안내가 깜빡이면 안 된다.
    const held = await holdPermissions(page, ['user:read']);

    await page.goto('/admin/users');
    await expect.poll(() => held.wasRequested()).toBe(true);
    // 레이아웃은 그려졌고(사이드바) 권한만 아직이다 — 레이아웃 안 스켈레톤으로 기다린다.
    await expect(sidebarLink(page, '홈')).toBeVisible();
    await expect(page.getByTestId('screen-permission-loading')).toBeVisible();
    await nextFrame(page);
    await expect(page.getByTestId('screen-forbidden')).toHaveCount(0);
    await expect(page.getByRole('heading', { level: 1, name: '사용자 관리' })).toHaveCount(0);

    held.release();
    await expect(page.getByRole('heading', { level: 1, name: '사용자 관리' })).toBeVisible();
    await expect(page.getByTestId('screen-forbidden')).toHaveCount(0);
    await expect(page.getByTestId('screen-permission-loading')).toHaveCount(0);
  });

  test('ADMIN 도 권한 응답을 기다린 뒤 화면을 그린다 — 빈 권한으로 먼저 그려 버튼이 늦게 나타나지 않게', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupApiConnectionListMocks(page);
    const held = await holdPermissions(page, ['apiconnection:read', 'apiconnection:write', 'apiconnection:delete']);

    await page.goto('/admin/api-connections');
    await expect.poll(() => held.wasRequested()).toBe(true);
    await expect(page.getByTestId('screen-permission-loading')).toBeVisible();
    await nextFrame(page);
    await expect(page.getByRole('heading', { level: 1, name: 'API 연결 관리' })).toHaveCount(0);

    held.release();
    await expect(page.getByRole('heading', { level: 1, name: 'API 연결 관리' })).toBeVisible();
    // 처음 그려질 때부터 권한이 반영돼 있다.
    await expect(page.getByRole('button', { name: '새 연결' })).toBeVisible();
  });

  test('권한 조회가 실패하면 「권한 없음」이 아니라 불러오기 실패를 알리고, 다시 시도로 회복한다', async ({
    authenticatedPage: page,
  }) => {
    await setupAuditLogMocks(page);
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', { message: 'error' }, { status: 500 });

    await page.goto('/admin/audit-logs');
    const error = page.getByTestId('screen-permission-error');
    await expect(error).toContainText('권한 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.');
    await expect(page.getByTestId('screen-forbidden')).toHaveCount(0);
    await expect(page).toHaveURL(/\/admin\/audit-logs$/);

    // 서버가 회복하면 다시 시도로 화면이 열린다.
    await grant(page, ['audit:read']);
    await error.getByRole('button', { name: '다시 시도' }).click();
    await expect(page.getByRole('heading', { level: 1, name: '감사 로그' })).toBeVisible();
    await expect(page.getByTestId('screen-permission-error')).toHaveCount(0);
  });
});

test.describe('로그아웃 후 다른 사용자 로그인', () => {
  test('A(관리 권한)로 로그인했다가 로그아웃하고 새로고침 없이 B(권한 없음)로 로그인하면 A 의 권한이 남지 않는다', async ({
    authMockedPage: page,
  }) => {
    // A: ADMIN 역할 + 관리 권한
    await mockApi(page, 'GET', '/api/v1/users/me', createAdminUserDetail({ id: 2, name: '관리자 A' }));
    await grant(page, ['user:read', 'role:read', 'audit:read', 'apiconnection:read']);
    await mockApi(page, 'POST', '/api/v1/auth/logout', {});
    await setupUserListMocks(page);

    await page.goto('/login');
    await page.getByLabel('아이디 (이메일)').fill('a@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('password123');
    await page.getByRole('button', { name: '로그인' }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect(sidebarLink(page, '사용자 관리')).toBeVisible();

    // 로그아웃 — /login 으로 하드 리로드해 캐시를 비운다(WD-276). 권한이 캐시에 남지 않는지는 여기서도 그대로 본다.
    await page.getByText('관리자 A', { exact: true }).click();
    await page.getByRole('menuitem', { name: '로그아웃' }).click();
    await page.waitForURL('**/login');

    // B: USER 역할 + 관리 권한 없음
    await mockApi(page, 'GET', '/api/v1/users/me', createUserDetail({ id: 7, name: '일반 B' }));
    const bPermissions = await mockApi(page, 'GET', '/api/v1/auth/me/permissions', [], { capture: true });
    await page.getByLabel('아이디 (이메일)').fill('b@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('password123');
    await page.getByRole('button', { name: '로그인' }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByText('일반 B', { exact: true })).toBeVisible();

    // 새로고침 없이 앱 안에서 관리 화면으로 가면 권한 안내가 보인다 — A 의 캐시로 판정하면 목록이 열린다.
    await page.evaluate(() => {
      window.history.pushState({}, '', '/admin/users');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await expectForbidden(page, /\/admin\/users$/, '사용자 관리');
    await expect(sidebarLink(page, '사용자 관리')).toHaveCount(0);
    await expect(page.getByRole('navigation').getByRole('button', { name: '관리', exact: true })).toHaveCount(0);
    // B 의 권한을 실제로 다시 물었다(A 의 캐시로 판정하지 않았다).
    expect(bPermissions.requests.length).toBeGreaterThan(0);
  });
});

test.describe('ADMIN — 기존대로 전부', () => {
  test('ADMIN 은 권한 코드와 무관하게 관리 메뉴 전부가 보이고 화면이 열린다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    // 메뉴·화면 진입은 ADMIN 이면 통과(라우트 가드 선례) — 권한 응답이 비어도 마찬가지인지 본다.
    await grant(page, []);
    await setupAuditLogMocks(page);

    await gotoWithPermissions(page, '/admin/audit-logs');
    await expect(page.getByRole('heading', { level: 1, name: '감사 로그' })).toBeVisible();
    await expect(page.getByText('감사 로그 1', { exact: true })).toBeVisible();
    await expect(page.getByTestId('screen-forbidden')).toHaveCount(0);
    for (const name of ['사용자 관리', '역할 관리', '감사 로그', '설정', 'API 연결']) {
      await expect(sidebarLink(page, name)).toBeVisible();
    }
  });

  test('ADMIN(기본 권한 전부)은 API 연결 목록에서 새 연결·전체 갱신·삭제가 그대로 보인다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupApiConnectionListMocks(page);

    await gotoWithPermissions(page, '/admin/api-connections');
    await expect(page.getByRole('cell', { name: '공공 데이터 API' })).toBeVisible();
    await expect(page.getByRole('button', { name: '새 연결' })).toBeVisible();
    await expect(page.getByRole('button', { name: '전체 갱신' })).toBeVisible();
    await expect(page.getByRole('row', { name: /공공 데이터 API/ }).getByRole('button')).toHaveCount(1);
  });
});
