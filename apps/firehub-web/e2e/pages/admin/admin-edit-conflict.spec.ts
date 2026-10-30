/**
 * 관리 화면 — 동시 편집 충돌 E2E (WD-440)
 *
 * 결함: 두 관리자가 같은 사용자의 역할·역할의 권한·보안 등급 정책을 고치면, 옛 화면에서 나중에 저장한 쪽이
 * 먼저 회수한 역할·권한을 경고 없이 되살리거나 먼저 강화한 정책을 다시 풀었다.
 * 이제 저장 요청이 편집을 시작하며 읽은 기준(expectedRoleIds·expectedPermissionIds·expected)을 싣고,
 * 서버가 409 EDIT_CONFLICT 로 거부하면 충돌 안내 창(다시 불러오기 / 내 변경으로 덮어쓰기)이 뜬다.
 *
 * 서버는 상태를 가진 모킹이다 — 기준이 지금 서버 값과 다르면 실제 서버처럼 409 를 돌려준다.
 */

import type { Page, Route } from '@playwright/test';

import type { RoleResponse } from '@/types/role';
import type { SecurityLevel, SecurityLevelRequest } from '@/types/security-level';

import { createPermissions, createRoleDetail } from '../../factories/admin.factory';
import { createRole, createUserDetail } from '../../factories/auth.factory';
import { createLevels } from '../../factories/security-level.factory';
import { setupAdminAuth, setupSettingsMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

const conflictDialog = (page: Page) => page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });

/** 실제 서버의 편집 충돌 응답과 같은 모양 */
function conflict(route: Route) {
  return route.fulfill({
    status: 409,
    contentType: 'application/json',
    body: JSON.stringify({ status: 409, code: 'EDIT_CONFLICT', message: '다른 관리자가 먼저 바꿨습니다. 새로 불러온 뒤 다시 저장하세요.' }),
  });
}

const json = (route: Route, body: unknown) =>
  route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

/** 순서와 무관하게 같은 id 집합인가 */
const sameIds = (a: number[], b: number[]) => [...a].sort().join(',') === [...b].sort().join(',');

const ROLES: RoleResponse[] = [
  createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
  createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자', isSystem: true }),
  createRole({ id: 3, name: 'EDITOR', description: '편집자', isSystem: false }),
];

/** 역할을 가진 가짜 사용자 서버 — 사용자 5 는 USER·EDITOR 로 시작한다. */
async function mockUserRolesServer(page: Page) {
  let roleIds = [1, 3];
  const puts: { roleIds: number[]; expectedRoleIds?: number[] }[] = [];
  await mockApi(page, 'GET', '/api/v1/roles', ROLES);
  await page.route(
    (url) => url.pathname === '/api/v1/users/5',
    (route) =>
      route.request().method() === 'GET'
        ? json(route, createUserDetail({ id: 5, username: 'user5', roles: ROLES.filter((r) => roleIds.includes(r.id)) }))
        : route.fallback(),
  );
  await page.route(
    (url) => url.pathname === '/api/v1/users/5/roles',
    (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      const body = route.request().postDataJSON() as (typeof puts)[number];
      puts.push(body);
      if (body.expectedRoleIds && !sameIds(body.expectedRoleIds, roleIds)) return conflict(route);
      roleIds = body.roleIds;
      return json(route, {});
    },
  );
  return {
    puts,
    roleIds: () => roleIds,
    /** 다른 관리자가 먼저 EDITOR 역할을 회수했다 */
    otherAdminRevokesEditor: () => {
      roleIds = roleIds.filter((id) => id !== 3);
    },
  };
}

/** 권한을 가진 가짜 역할 서버 — 역할 3(EDITOR)은 DATASET_READ·DATASET_WRITE 로 시작한다. */
async function mockRolePermissionsServer(page: Page) {
  let permissionIds = [1, 2];
  const puts: { permissionIds: number[]; expectedPermissionIds?: number[] }[] = [];
  await mockApi(page, 'GET', '/api/v1/permissions', createPermissions());
  await mockApi(page, 'GET', '/api/v1/roles/3/clearance', { roleId: 3, securityLevelId: 2, userCount: 14, fixed: false });
  await setupSecurityLevelMocks(page);
  await page.route(
    (url) => url.pathname === '/api/v1/roles/3',
    (route) =>
      route.request().method() === 'GET'
        ? json(
            route,
            createRoleDetail({
              id: 3,
              name: 'EDITOR',
              description: '편집자 역할',
              isSystem: false,
              permissions: createPermissions().filter((p) => permissionIds.includes(p.id)),
            }),
          )
        : route.fallback(),
  );
  await page.route(
    (url) => url.pathname === '/api/v1/roles/3/permissions',
    (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      const body = route.request().postDataJSON() as (typeof puts)[number];
      puts.push(body);
      if (body.expectedPermissionIds && !sameIds(body.expectedPermissionIds, permissionIds)) return conflict(route);
      permissionIds = body.permissionIds;
      return json(route, {});
    },
  );
  return {
    puts,
    permissionIds: () => permissionIds,
    /** 다른 관리자가 먼저 DATASET_WRITE 권한을 회수했다 */
    otherAdminRevokesDatasetWrite: () => {
      permissionIds = permissionIds.filter((id) => id !== 2);
    },
  };
}

/** 정책을 가진 가짜 보안 등급 서버 — 등급 3(민감)의 내보내기는 「권한 필요」로 시작한다. */
async function mockSecurityLevelServer(page: Page) {
  let levels: SecurityLevel[] = createLevels();
  const puts: SecurityLevelRequest[] = [];
  const level3 = () => levels.find((l) => l.id === 3)!;
  await page.route(
    (url) => url.pathname === '/api/v1/security-levels',
    (route) => (route.request().method() === 'GET' ? json(route, levels) : route.fallback()),
  );
  await page.route(
    (url) => url.pathname === '/api/v1/security-levels/3',
    (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      const body = route.request().postDataJSON() as SecurityLevelRequest;
      puts.push(body);
      const { expected } = body;
      if (expected && (Object.keys(expected) as (keyof typeof expected)[]).some((k) => expected[k] !== level3()[k])) {
        return conflict(route);
      }
      const { name, allowlistRequired, adminBypass, exportPolicy, aiPolicy, sharePolicy, auditAccess } = body;
      levels = levels.map((l) =>
        l.id === 3 ? { ...l, name, allowlistRequired, adminBypass, exportPolicy, aiPolicy, sharePolicy, auditAccess } : l,
      );
      return json(route, level3());
    },
  );
  return {
    puts,
    level3,
    /** 다른 관리자가 먼저 민감 등급의 내보내기를 차단으로 강화했다 */
    otherAdminDeniesExport: () => {
      levels = levels.map((l) => (l.id === 3 ? { ...l, exportPolicy: 'DENY' } : l));
    },
  };
}

test.describe('관리 화면 — 동시 편집 충돌 (WD-440)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('사용자 역할 — 저장이 처음 읽은 역할을 기준으로 싣고, 그사이 회수됐으면 충돌 안내 후 다시 불러오면 최신 역할로 돌아간다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockUserRolesServer(page);
    await page.goto('/admin/users/5');
    await expect(page.getByRole('checkbox', { name: /EDITOR/ })).toBeChecked();

    // 이 화면을 연 뒤 다른 관리자가 EDITOR 를 회수했다. 옛 화면에서 ADMIN 을 더해 저장한다.
    server.otherAdminRevokesEditor();
    await page.getByRole('checkbox', { name: /ADMIN/ }).check();
    await page.getByRole('button', { name: '역할 저장' }).click();

    await expect(conflictDialog(page)).toBeVisible();
    await expect(conflictDialog(page)).toContainText('사용자의 역할');
    expect(sameIds(server.puts[0].roleIds, [1, 2, 3])).toBe(true);
    expect(sameIds(server.puts[0].expectedRoleIds!, [1, 3])).toBe(true);
    // 회수된 EDITOR 가 되살아나지 않았다
    expect(server.roleIds()).toEqual([1]);

    await conflictDialog(page).getByRole('button', { name: '다시 불러오기' }).click();
    await expect(conflictDialog(page)).toBeHidden();
    await expect(page.getByRole('checkbox', { name: /EDITOR/ })).not.toBeChecked();
    await expect(page.getByRole('checkbox', { name: /ADMIN/ })).not.toBeChecked();

    // 새 기준으로 다시 저장하면 통과한다
    await page.getByRole('checkbox', { name: /ADMIN/ }).check();
    await page.getByRole('button', { name: '역할 저장' }).click();
    await expect.poll(() => server.roleIds().sort()).toEqual([1, 2]);
    expect(sameIds(server.puts[1].expectedRoleIds!, [1])).toBe(true);
  });

  test('사용자 역할 — 「내 변경으로 덮어쓰기」는 기준 없이 다시 저장한다', async ({ authenticatedPage: page }) => {
    const server = await mockUserRolesServer(page);
    await page.goto('/admin/users/5');
    await expect(page.getByRole('checkbox', { name: /EDITOR/ })).toBeChecked();

    server.otherAdminRevokesEditor();
    await page.getByRole('checkbox', { name: /ADMIN/ }).check();
    await page.getByRole('button', { name: '역할 저장' }).click();
    await expect(conflictDialog(page)).toBeVisible();

    await conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
    await expect(conflictDialog(page)).toBeHidden();
    expect(server.puts).toHaveLength(2);
    expect(server.puts[1]).not.toHaveProperty('expectedRoleIds');
    expect(server.roleIds().sort()).toEqual([1, 2, 3]);
  });

  test('역할 권한 — 저장이 처음 읽은 권한을 기준으로 싣고, 그사이 회수됐으면 충돌 안내 후 다시 불러오면 최신 권한으로 돌아간다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockRolePermissionsServer(page);
    await page.goto('/admin/roles/3');
    await expect(page.getByRole('checkbox', { name: 'DATASET_WRITE' })).toBeChecked();

    // 이 화면을 연 뒤 다른 관리자가 DATASET_WRITE 를 회수했다. 옛 화면에서 PIPELINE_READ 를 더해 저장한다.
    server.otherAdminRevokesDatasetWrite();
    await page.getByRole('checkbox', { name: 'PIPELINE_READ' }).check();
    await page.getByRole('button', { name: '권한 저장' }).click();

    await expect(conflictDialog(page)).toBeVisible();
    await expect(conflictDialog(page)).toContainText('역할의 권한');
    expect(sameIds(server.puts[0].permissionIds, [1, 2, 3])).toBe(true);
    expect(sameIds(server.puts[0].expectedPermissionIds!, [1, 2])).toBe(true);
    expect(server.permissionIds()).toEqual([1]);

    await conflictDialog(page).getByRole('button', { name: '다시 불러오기' }).click();
    await expect(conflictDialog(page)).toBeHidden();
    await expect(page.getByRole('checkbox', { name: 'DATASET_WRITE' })).not.toBeChecked();
    await expect(page.getByRole('checkbox', { name: 'PIPELINE_READ' })).not.toBeChecked();
    await expect(page.getByRole('checkbox', { name: 'DATASET_READ' })).toBeChecked();
  });

  test('역할 권한 — 「내 변경으로 덮어쓰기」는 기준 없이 다시 저장한다', async ({ authenticatedPage: page }) => {
    const server = await mockRolePermissionsServer(page);
    await page.goto('/admin/roles/3');
    await expect(page.getByRole('checkbox', { name: 'DATASET_WRITE' })).toBeChecked();

    server.otherAdminRevokesDatasetWrite();
    await page.getByRole('checkbox', { name: 'PIPELINE_READ' }).check();
    await page.getByRole('button', { name: '권한 저장' }).click();
    await expect(conflictDialog(page)).toBeVisible();

    await conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
    await expect(conflictDialog(page)).toBeHidden();
    expect(server.puts).toHaveLength(2);
    expect(server.puts[1]).not.toHaveProperty('expectedPermissionIds');
    expect(server.permissionIds().sort()).toEqual([1, 2, 3]);
  });

  test.describe('보안 등급', () => {
    test.beforeEach(async ({ authenticatedPage: page }) => {
      await setupSettingsMocks(page);
      await setupSecurityLevelMocks(page);
    });

    async function openSensitive(page: Page) {
      await page.goto('/admin/settings');
      await page.getByRole('tab', { name: '데이터 보안' }).click();
      await page.getByRole('button', { name: '민감 펼치기' }).click();
    }
    const exportDeny = (page: Page) =>
      page.getByRole('radiogroup', { name: '내보내기' }).getByRole('radio', { name: '차단' });
    const aiDeny = (page: Page) => page.getByRole('radiogroup', { name: 'AI 분석' }).getByRole('radio', { name: '차단' });

    test('저장이 처음 읽은 정책을 기준으로 싣고, 그사이 강화됐으면 충돌 안내 후 다시 불러오면 최신 정책이 보인다', async ({
      authenticatedPage: page,
    }) => {
      const server = await mockSecurityLevelServer(page);
      await openSensitive(page);
      await expect(exportDeny(page)).not.toBeChecked();

      // 이 화면을 연 뒤 다른 관리자가 내보내기를 차단으로 강화했다. 옛 폼에서 AI 분석만 바꿔 저장한다.
      server.otherAdminDeniesExport();
      await aiDeny(page).check();
      await page.getByRole('button', { name: '저장', exact: true }).click();

      await expect(conflictDialog(page)).toBeVisible();
      await expect(conflictDialog(page)).toContainText('보안 등급');
      expect(server.puts[0]).toMatchObject({
        exportPolicy: 'PERMISSION',
        aiPolicy: 'DENY',
        expected: { name: '민감', exportPolicy: 'PERMISSION', aiPolicy: 'SELF_HOSTED_ONLY', auditAccess: true },
      });
      // 강화된 정책이 옛 값으로 풀리지 않았다
      expect(server.level3().exportPolicy).toBe('DENY');

      await conflictDialog(page).getByRole('button', { name: '다시 불러오기' }).click();
      await expect(conflictDialog(page)).toBeHidden();
      // 다시 불러오면 행이 서버 값으로 새로 그려진다(내 변경은 버린다)
      const expand = page.getByRole('button', { name: '민감 펼치기' });
      if ((await expand.getAttribute('aria-expanded')) !== 'true') await expand.click();
      await expect(exportDeny(page)).toBeChecked();
      await expect(aiDeny(page)).not.toBeChecked();
    });

    test('「내 변경으로 덮어쓰기」는 기준 없이 다시 저장한다', async ({ authenticatedPage: page }) => {
      const server = await mockSecurityLevelServer(page);
      await openSensitive(page);
      await expect(exportDeny(page)).not.toBeChecked();

      server.otherAdminDeniesExport();
      await aiDeny(page).check();
      await page.getByRole('button', { name: '저장', exact: true }).click();
      await expect(conflictDialog(page)).toBeVisible();

      await conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
      await expect(conflictDialog(page)).toBeHidden();
      expect(server.puts).toHaveLength(2);
      expect(server.puts[1]).not.toHaveProperty('expected');
      expect(server.level3()).toMatchObject({ exportPolicy: 'PERMISSION', aiPolicy: 'DENY' });
    });
  });
});
