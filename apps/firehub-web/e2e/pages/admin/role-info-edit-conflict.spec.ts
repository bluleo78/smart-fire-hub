/**
 * 역할 상세 — 이름·설명 동시 편집 충돌 E2E (WD-575)
 *
 * 결함: 두 관리자가 같은 역할을 열어 둔 상태에서 A 가 이름을 바꾸고, 옛 화면의 B 가 설명만 고쳐 저장하면
 * B 의 옛 이름이 다시 써져 A 의 변경이 조용히 사라졌다(둘 다 204).
 * 이제 이름·설명 저장이 편집을 시작하며 읽은 updatedAt 을 expectedUpdatedAt 으로 싣고,
 * 서버가 409 EDIT_CONFLICT 로 거부하면 충돌 안내 창(다시 불러오기 / 내 변경으로 덮어쓰기)이 뜬다.
 *
 * 서버는 상태를 가진 모킹이다 — 기준이 지금 서버 값과 다르면 실제 서버처럼 409 를 돌려준다.
 */

import type { Page, Route } from '@playwright/test';

import type { UpdateRoleRequest } from '@/types/role';

import { createPermissions, createRoleDetail } from '../../factories/admin.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { expectLockedInFlight, holdRequests } from '../../fixtures/hold-api';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

const conflictDialog = (page: Page) => page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });

/** 역할 정보 카드의 저장 버튼 — 열람 등급 카드에도 「저장」이 있어 이름 입력란이 든 폼으로 좁힌다. */
const roleInfoSave = (page: Page) =>
  page.locator('form').filter({ has: page.getByLabel('역할 이름') }).getByRole('button', { name: '저장' });

const json = (route: Route, body: unknown) =>
  route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

/** 이름·설명·권한을 가진 가짜 역할 서버 — 역할 3(EDITOR)은 DATASET_READ·DATASET_WRITE 권한으로 시작한다. */
async function mockRoleInfoServer(page: Page) {
  let name = 'EDITOR';
  let description: string | null = '편집자 역할';
  let permissionIds = [1, 2];
  // 서버처럼 마이크로초까지 둔 문자열 — 화면은 받은 값을 그대로 돌려보내야 한다
  let updatedAt = '2026-01-01T09:00:00.123456';
  let version = 0;
  const bump = () => {
    version += 1;
    updatedAt = `2026-01-01T09:00:0${version}.654321`;
  };
  const puts: UpdateRoleRequest[] = [];

  await mockApi(page, 'GET', '/api/v1/permissions', createPermissions());
  await mockApi(page, 'GET', '/api/v1/roles/3/clearance', { roleId: 3, securityLevelId: 2, userCount: 14, fixed: false });
  await setupSecurityLevelMocks(page);
  await page.route(
    (url) => url.pathname === '/api/v1/roles/3',
    (route) => {
      const method = route.request().method();
      if (method === 'GET') {
        return json(
          route,
          createRoleDetail({
            id: 3,
            name,
            description,
            isSystem: false,
            permissions: createPermissions().filter((p) => permissionIds.includes(p.id)),
            updatedAt,
          }),
        );
      }
      if (method !== 'PUT') return route.fallback();
      const body = route.request().postDataJSON() as UpdateRoleRequest;
      puts.push(body);
      if (body.expectedUpdatedAt && body.expectedUpdatedAt !== updatedAt) {
        return route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            status: 409,
            code: 'EDIT_CONFLICT',
            message: '다른 관리자가 이 역할의 이름·설명을 바꿨습니다. 새로 불러온 뒤 다시 저장하세요.',
          }),
        });
      }
      name = body.name;
      description = body.description ?? null;
      bump();
      return route.fulfill({ status: 204 });
    },
  );
  // 권한 저장 — 서버처럼 역할의 수정 시각(이름·설명 버전)은 바꾸지 않는다
  await page.route(
    (url) => url.pathname === '/api/v1/roles/3/permissions',
    (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      permissionIds = (route.request().postDataJSON() as { permissionIds: number[] }).permissionIds;
      return json(route, {});
    },
  );
  return {
    puts,
    state: () => ({ name, description, updatedAt }),
    /** 다른 관리자가 먼저 역할 이름을 바꿨다 */
    otherAdminRenames: () => {
      name = 'EDITOR_RENAMED';
      bump();
    },
  };
}

test.describe('역할 상세 — 이름·설명 동시 편집 충돌 (WD-575)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('저장이 처음 읽은 updatedAt 을 싣고, 그사이 이름이 바뀌었으면 충돌 안내 후 다시 불러오면 최신 이름이 보인다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockRoleInfoServer(page);
    await page.goto('/admin/roles/3');
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');

    // 이 화면을 연 뒤 다른 관리자가 이름을 바꿨다. 옛 화면에서 설명만 고쳐 저장한다.
    server.otherAdminRenames();
    await page.getByLabel('설명').fill('B 가 고친 설명');
    await roleInfoSave(page).click();

    await expect(conflictDialog(page)).toBeVisible();
    await expect(conflictDialog(page)).toContainText('역할 정보');
    expect(server.puts[0]).toMatchObject({
      name: 'EDITOR',
      description: 'B 가 고친 설명',
      expectedUpdatedAt: '2026-01-01T09:00:00.123456',
    });
    // 먼저 바꾼 이름이 옛 이름으로 되돌려지지 않았다
    expect(server.state().name).toBe('EDITOR_RENAMED');
    // 충돌은 입력란 아래 오류가 아니라 안내 창으로만 알린다
    await expect(page.getByText('다른 관리자가 이 역할의 이름·설명을 바꿨습니다')).toBeHidden();

    await conflictDialog(page).getByRole('button', { name: '다시 불러오기' }).click();
    await expect(conflictDialog(page)).toBeHidden();
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR_RENAMED');
    await expect(page.getByLabel('설명')).toHaveValue('편집자 역할');

    // 새 기준으로 다시 저장하면 통과한다
    await page.getByLabel('설명').fill('다시 고친 설명');
    await roleInfoSave(page).click();
    await expect(page.getByText('역할 정보가 업데이트되었습니다.')).toBeVisible();
    expect(server.puts[1]).toMatchObject({ name: 'EDITOR_RENAMED', expectedUpdatedAt: '2026-01-01T09:00:01.654321' });
    expect(server.state().description).toBe('다시 고친 설명');
  });

  test('「내 변경으로 덮어쓰기」는 expectedUpdatedAt 없이 다시 저장한다', async ({ authenticatedPage: page }) => {
    const server = await mockRoleInfoServer(page);
    await page.goto('/admin/roles/3');
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');

    server.otherAdminRenames();
    await page.getByLabel('설명').fill('내 설명');
    await roleInfoSave(page).click();
    await expect(conflictDialog(page)).toBeVisible();

    await conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
    await expect(conflictDialog(page)).toBeHidden();
    expect(server.puts).toHaveLength(2);
    expect(server.puts[1]).not.toHaveProperty('expectedUpdatedAt');
    expect(server.state()).toMatchObject({ name: 'EDITOR', description: '내 설명' });
  });

  test('저장에 성공하면 새 updatedAt 을 다음 저장의 기준으로 삼아 자기 저장과 충돌하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockRoleInfoServer(page);
    await page.goto('/admin/roles/3');
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');

    await page.getByLabel('설명').fill('첫 저장');
    await roleInfoSave(page).click();
    await expect(page.getByText('역할 정보가 업데이트되었습니다.')).toBeVisible();

    await page.getByLabel('설명').fill('두 번째 저장');
    await roleInfoSave(page).click();
    await expect.poll(() => server.state().description).toBe('두 번째 저장');
    expect(server.puts[0].expectedUpdatedAt).toBe('2026-01-01T09:00:00.123456');
    expect(server.puts[1].expectedUpdatedAt).toBe('2026-01-01T09:00:01.654321');
    await expect(conflictDialog(page)).toBeHidden();
  });

  test('권한을 저장해 역할을 다시 읽어도 이름·설명의 기준은 바뀌지 않아, 그사이 바뀐 이름을 옛 이름으로 되돌리지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockRoleInfoServer(page);
    await page.goto('/admin/roles/3');
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');

    // 다른 관리자가 이름을 바꾼 뒤, 이 화면에서 권한부터 저장한다(권한 저장은 역할을 다시 읽는다)
    server.otherAdminRenames();
    await page.getByRole('checkbox', { name: 'PIPELINE_READ' }).check();
    await page.getByRole('button', { name: '권한 저장' }).click();
    await expect(page.getByText('권한이 저장되었습니다.')).toBeVisible();

    // 폼에는 여전히 옛 이름이 남아 있다 — 설명만 고쳐 저장하면 처음 기준으로 비교돼 충돌로 거부돼야 한다
    await page.getByLabel('설명').fill('권한 저장 뒤 설명');
    await roleInfoSave(page).click();
    await expect(conflictDialog(page)).toBeVisible();
    expect(server.puts[0].expectedUpdatedAt).toBe('2026-01-01T09:00:00.123456');
    expect(server.state().name).toBe('EDITOR_RENAMED');
  });
});

test.describe('역할 상세 — 충돌 창 진행 중 포커스 유지 (WD-678)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('Enter 로 「다시 불러오기」를 누르면 진행 중 포커스가 그 버튼에 남고, 다시 Enter 를 눌러도 GET 은 한 번만 간다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockRoleInfoServer(page);
    await page.goto('/admin/roles/3');
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');

    server.otherAdminRenames();
    await page.getByLabel('설명').fill('B 가 고친 설명');
    await roleInfoSave(page).click();
    await expect(conflictDialog(page)).toBeVisible();

    // 화면 첫 조회 뒤부터 역할 다시 읽기를 붙잡는다
    const hold = await holdRequests(page, '/api/v1/roles/3', 'GET');
    const reload = conflictDialog(page).getByRole('button', { name: '다시 불러오기' });
    await reload.focus();
    await page.keyboard.press('Enter');

    // 응답 전: disabled 가 아니라 aria-disabled 로 잠겨 포커스가 body 로 떨어지지 않는다
    await expect.poll(() => hold.count()).toBe(1);
    await expectLockedInFlight(conflictDialog(page), reload);

    // 진행 중 다시 Enter — 두 번째 조회가 나가지 않는다
    await page.keyboard.press('Enter');
    await expect(conflictDialog(page)).toBeVisible();
    // 두 번째 요청이 나갈 틈을 잠깐 준 뒤 센다 — 누른 직후 바로 세면 아직 안 나간 요청을 놓친다
    await page.waitForTimeout(300);
    expect(hold.count()).toBe(1);

    hold.release();
    await expect(conflictDialog(page)).toBeHidden();
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR_RENAMED');
    await expect(page.getByLabel('설명')).toHaveValue('편집자 역할');
    expect(hold.count()).toBe(1);
    // 다시 불러오기는 저장하지 않는다 — 충돌을 낸 PUT 한 번뿐
    expect(server.puts).toHaveLength(1);
  });
});
