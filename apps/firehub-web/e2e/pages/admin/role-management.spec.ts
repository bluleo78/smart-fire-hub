import type { Page } from '@playwright/test';

import type { UpdateRoleRequest } from '@/types/role';

import { createPermission, createRoleDetail } from '../../factories/admin.factory';
import { createRole } from '../../factories/auth.factory';
import {
  setupAdminAuth,
  setupRoleDetailMocks,
  setupRoleListMocks,
} from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { expectBackdropClickStaysOnList } from '../../fixtures/dialog.fixture';
import { holdApi } from '../../fixtures/hold-api';
import {
  expectLongValueTruncated,
  expectTableFits,
  expectValueNotTruncated,
  TABLE_LAYOUT_WIDTHS,
} from '../../fixtures/table-layout';

/**
 * 역할 관리 페이지 E2E 테스트
 * - 역할 목록, 생성 다이얼로그, 시스템 역할 보호, 커스텀 역할 삭제를 검증한다.
 * - 관리 화면 권한 가드(AdminOrPermissionRoute) 통과와 화면 안 버튼 노출을 위해 ADMIN 역할·관리 권한 코드로 users/me를 오버라이드한다.
 */
/** 역할 정보 카드의 「저장」 — 권한 저장·열람 등급 저장 버튼과 섞이지 않게 카드 안으로 좁힌다 (WD-119). */
async function saveRoleInfo(page: Page) {
  await page
    .locator('[data-slot="card"]')
    .filter({ hasText: '역할 정보' })
    .getByRole('button', { name: '저장', exact: true })
    .click();
}

/**
 * 역할 3 상세에서 이름을 바꿔 저장 → 409 충돌 창 → 「내 변경으로 덮어쓰기」까지 진행하고, 덮어쓰기 PUT 을 붙잡아 둔 채 돌려준다 (WD-1102 리뷰 후속).
 * 기준(expectedUpdatedAt)을 실은 저장은 늘 409, 기준이 없는 덮어쓰기 저장은 release() 할 때까지 붙잡았다가 주어진 응답을 준다 —
 * 덮어쓰기 요청이 진행 중일 때 창을 닫거나 입력을 고치는 경우를 시간에 기대지 않고 만든다.
 */
async function openHeldOverwrite(page: Page, name: string, status: number, body: unknown) {
  await setupRoleDetailMocks(page, 3, false);
  let release: () => void = () => {};
  const released = new Promise<void>((resolve) => {
    release = resolve;
  });
  let overwrites = 0;
  await page.route(
    (url) => url.pathname === '/api/v1/roles/3',
    async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      if ((route.request().postDataJSON() as UpdateRoleRequest).expectedUpdatedAt) {
        return route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ status: 409, code: 'EDIT_CONFLICT', message: '다른 관리자가 먼저 바꿨습니다' }),
        });
      }
      overwrites += 1;
      await released;
      return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    },
  );

  await page.goto('/admin/roles/3');
  const nameInput = page.getByLabel('역할 이름');
  await nameInput.fill(name);
  await saveRoleInfo(page);
  const dialog = page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
  await expect.poll(() => overwrites).toBe(1);
  return { release, dialog, nameInput };
}

test.describe('역할 관리 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // 관리 화면 권한 가드(AdminOrPermissionRoute) 통과와 화면 안 버튼 노출을 위해 ADMIN 역할·관리 권한 코드로 오버라이드
    await setupAdminAuth(page);
  });

  test('역할 목록이 올바르게 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '역할 관리' })).toBeVisible();

    // 테이블 헤더 확인
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '유형' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '설명' })).toBeVisible();

    // 행 개수 확인: 헤더 1행 + 데이터 3행 = 4행
    await expect(page.getByRole('row')).toHaveCount(4);

    // 역할 행 렌더링 확인
    await expect(page.getByRole('cell', { name: 'USER', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'ADMIN', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'EDITOR', exact: true })).toBeVisible();

    // 유형 컬럼 값 확인 — 시스템 역할(USER, ADMIN)은 '시스템', 커스텀 역할(EDITOR)은 '사용자 정의'
    const rows = page.getByRole('row');
    // USER 행: 유형 셀에 '시스템' 텍스트가 있어야 한다
    await expect(rows.filter({ hasText: 'USER' }).getByText('시스템')).toBeVisible();
    // EDITOR 행: 유형 셀에 '사용자 정의' 텍스트가 있어야 한다
    await expect(rows.filter({ hasText: 'EDITOR' }).getByText('사용자 정의')).toBeVisible();
  });

  /** WD-286: 역할 행은 마우스 클릭으로만 열렸다 — 역할 이름 링크로 키보드(Tab·Enter)만으로 상세에 들어간다. */
  test('키보드만으로 「역할 추가」 다음 Tab 으로 역할 이름 링크에 닿고 Enter 로 상세를 연다 (WD-286)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await setupRoleDetailMocks(page, 3, false);
    await page.goto('/admin/roles');

    // 표의 행·칸 의미는 그대로다 — 헤더 1행 + 데이터 3행, 이름 칸 안에 링크가 있다.
    await expect(page.getByRole('row')).toHaveCount(4);
    const userLink = page.getByRole('link', { name: 'USER', exact: true });
    const adminLink = page.getByRole('link', { name: 'ADMIN', exact: true });
    const editorLink = page.getByRole('link', { name: 'EDITOR', exact: true });
    await expect(editorLink).toHaveAttribute('href', '/admin/roles/3');

    // 「역할 추가」에서 Tab 을 누르면 표의 첫 역할 링크로 간다(예전에는 본문을 지나 사이드바로 갔다).
    await page.getByRole('button', { name: '새 역할' }).focus();
    await page.keyboard.press('Tab');
    await expect(userLink).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(adminLink).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(editorLink).toBeFocused();

    // Enter 로 그 역할의 상세가 열린다.
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/admin\/roles\/3$/);
    await expect(page.getByRole('heading', { name: '역할 상세' })).toBeVisible();
  });

  test('역할 행을 마우스로 눌러도 상세가 열리고, 행 안 삭제 버튼은 상세로 이동하지 않는다 (WD-286)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await setupRoleDetailMocks(page, 3, false);
    await page.goto('/admin/roles');

    // 삭제 버튼은 확인 창만 연다 — 행 이동으로 번지지 않는다.
    await page.getByRole('button', { name: 'EDITOR 역할 삭제' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('alertdialog')).toBeHidden();
    await expect(page).toHaveURL(/\/admin\/roles$/);

    // 이름 밖의 칸(설명)을 눌러도 예전처럼 행 클릭으로 상세가 열린다.
    await page.getByRole('row').filter({ hasText: 'EDITOR' }).getByText('사용자 정의').click();
    await expect(page).toHaveURL(/\/admin\/roles\/3$/);
  });

  test('삭제 확인 창의 배경을 눌러도 상세로 이동하지 않는다 (WD-943)', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    await setupRoleDetailMocks(page, 3, false);
    await page.goto('/admin/roles');
    // 요청 캡처는 누르기 전에 건다 — 늦게 걸면 번진 요청을 놓쳐 통과할 수 있다(WD-943).
    const { requests: deleteRequests } = await mockApi(page, 'DELETE', '/api/v1/roles/3', {}, { capture: true });

    await page.getByRole('button', { name: 'EDITOR 역할 삭제' }).click();
    await expectBackdropClickStaysOnList(page, deleteRequests);
  });

  test('역할 추가 버튼 클릭 시 생성 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);

    // 역할 생성 POST API 캡처 설정 — goto 이전에 등록해야 한다
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/roles',
      { id: 10, name: 'NEW_ROLE', description: '새 역할', isSystem: false },
      { capture: true },
    );

    await page.goto('/admin/roles');

    // "역할 추가" 버튼 클릭
    await page.getByRole('button', { name: '새 역할' }).click();

    // 다이얼로그 열림 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: '새 역할' })).toBeVisible();

    // 입력 필드 확인 및 값 입력
    const nameInput = page.getByLabel('역할 이름');
    const descInput = page.getByLabel('설명 (선택)');
    await expect(nameInput).toBeVisible();
    await expect(descInput).toBeVisible();

    await nameInput.fill('NEW_ROLE');
    await descInput.fill('새 역할 설명');

    // 생성 버튼 클릭 → POST /api/v1/roles 호출
    await page.getByRole('button', { name: '만들기' }).click();

    // API payload 검증 — 입력한 이름이 전달되어야 한다
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ name: 'NEW_ROLE' });
  });

  // WD-817: 역할 이름은 필수인데 표시가 없었고(옆 칸만 「설명 (선택)」), 대문자·숫자·밑줄 규칙은 제출해야만 알 수 있었다.
  // 라벨에 * 를 달고, 규칙을 입력 전에 보이는 설명으로 두어 aria-describedby 로 칸에 잇는다.
  test('새 역할 다이얼로그의 역할 이름은 필수 표시와 입력 규칙 안내를 입력 전에 보인다 (WD-817)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: '새 역할' }).click();

    const dialog = page.getByRole('dialog');
    const label = dialog.locator('label[for="role-name"]');
    await expect(label).toHaveText('역할 이름*');
    await expect(label.locator('span.text-destructive')).toHaveText('*');
    // 선택 칸에는 * 가 없다.
    await expect(dialog.locator('label[for="role-description"]')).toHaveText('설명 (선택)');

    // 입력하기 전에 규칙이 보이고 칸의 설명으로 이어진다.
    const rule = dialog.locator('#role-name-desc');
    await expect(rule).toBeVisible();
    await expect(rule).toContainText('대문자로 시작');
    await expect(rule).toContainText('DATA_EDITOR');
    const nameInput = dialog.getByLabel('역할 이름');
    await expect(nameInput).toHaveAttribute('aria-describedby', /role-name-desc/);
    await expect(nameInput).toHaveAccessibleDescription(/대문자로 시작/);
    await expect(nameInput).toHaveAttribute('aria-required', 'true');
  });

  // 안내를 더한 뒤에도 규칙에 맞는 이름은 그대로 만들어진다 — 입력 → 요청 payload 까지 본다.
  test('안내 예시대로 입력한 역할 이름은 그대로 생성 요청된다 (WD-817)', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/roles',
      { id: 11, name: 'DATA_EDITOR', description: '', isSystem: false },
      { capture: true },
    );
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: '새 역할' }).click();

    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('역할 이름').fill('DATA_EDITOR');
    await dialog.getByRole('button', { name: '만들기' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ name: 'DATA_EDITOR' });
  });

  // 기존 제출 시 오류 동작은 그대로다 — 규칙에 맞지 않는 이름은 칸 아래 오류로 막히고, 규칙 안내도 함께 이어진다.
  test('규칙에 맞지 않는 역할 이름은 제출 때 오류가 나고 안내와 함께 칸에 이어진다 (WD-817)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: '새 역할' }).click();

    const dialog = page.getByRole('dialog');
    const nameInput = dialog.getByLabel('역할 이름');
    await nameInput.fill('editor');
    await dialog.getByRole('button', { name: '만들기' }).click();

    await expect(dialog.locator('#role-name-error')).toHaveText('역할 이름은 대문자, 숫자, 밑줄만 허용됩니다');
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).toHaveAttribute('aria-describedby', /role-name-error/);
    await expect(nameInput).toHaveAttribute('aria-describedby', /role-name-desc/);
  });

  // WD-865: 설명은 255자까지다(DB VARCHAR(255)) — 한도를 입력 전에 안내하고, 초과 오류는 이름이 아닌 설명 칸에 붙인다.
  const DESCRIPTION_LIMIT_MESSAGE = '설명은 255자 이내여야 합니다';

  /** 새 역할 대화상자를 열어 이름·설명을 채운 뒤 「만들기」 직전 상태까지 만든다. */
  async function openNewRoleWith(page: Page, name: string, description: string) {
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: '새 역할' }).click();
    const dialog = page.getByRole('dialog');
    const nameInput = dialog.getByLabel('역할 이름');
    const descInput = dialog.getByLabel('설명');
    await nameInput.fill(name);
    await descInput.fill(description);
    return { dialog, nameInput, descInput };
  }

  test('새 역할: 설명 칸에 255자 제한 안내가 보인다 (WD-865)', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: '새 역할' }).click();

    await expect(page.getByRole('dialog').getByLabel('설명')).toHaveAccessibleDescription(/255자까지/);
  });

  test('새 역할: 설명 256자는 제출 전에 설명 칸 오류, 이름 칸은 오류 없음 (WD-865)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    const capture = await mockApi(page, 'POST', '/api/v1/roles', { id: 11, name: 'DESCLENPROBE' }, { capture: true });
    const { dialog, nameInput, descInput } = await openNewRoleWith(page, 'DESCLENPROBE', 'ㄱ'.repeat(256));
    await dialog.getByRole('button', { name: '만들기' }).click();

    await expect(descInput).toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByText(DESCRIPTION_LIMIT_MESSAGE)).toBeVisible();
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
    expect(capture.requests).toHaveLength(0);

    // 255자로 줄이면 오류 문구가 사라진다
    await descInput.fill('ㄱ'.repeat(255));
    await expect(dialog.getByText(DESCRIPTION_LIMIT_MESSAGE)).toHaveCount(0);
  });

  test('새 역할: 설명 255자는 통과 (WD-865)', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    const capture = await mockApi(page, 'POST', '/api/v1/roles', { id: 11, name: 'DESCLENPROBE' }, { capture: true });
    const { dialog } = await openNewRoleWith(page, 'DESCLENPROBE', 'ㄱ'.repeat(255));
    await dialog.getByRole('button', { name: '만들기' }).click();

    const req = await capture.waitForRequest();
    expect((req.payload as { description: string }).description).toHaveLength(255);
  });

  test('새 역할: 서버 400 의 설명 필드 오류는 설명 칸에 (WD-865)', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/roles',
      { status: 400, message: '입력값이 올바르지 않습니다', errors: { description: DESCRIPTION_LIMIT_MESSAGE } },
      { status: 400 },
    );
    const { dialog, nameInput, descInput } = await openNewRoleWith(page, 'DESCLENPROBE', '짧은 설명');
    await dialog.getByRole('button', { name: '만들기' }).click();

    await expect(descInput).toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByText(DESCRIPTION_LIMIT_MESSAGE)).toBeVisible();
    await expect(descInput).toBeFocused();
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
  });

  test('새 역할: 모르는 필드 오류만 있는 400 은 폼 오류 영역에만 보이고 이름 칸은 오류가 아니다 (WD-865)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/roles',
      { status: 400, message: '잘못된 요청', errors: { foo: 'x' } },
      { status: 400 },
    );
    const { dialog, nameInput, descInput } = await openNewRoleWith(page, 'DESCLENPROBE', '짧은 설명');
    await dialog.getByRole('button', { name: '만들기' }).click();

    // 폼 오류 영역(role=alert)에만 문구가 보이고, 이름·설명 칸은 오류 상태가 아니다
    const formAlert = dialog.getByRole('alert').filter({ hasText: /^x$/ });
    await expect(formAlert).toBeVisible();
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).not.toBeFocused();
    await expect(descInput).not.toHaveAttribute('aria-invalid', 'true');

    // 설명을 고치면 폼 오류가 사라진다
    await descInput.fill('짧은 설명 고침');
    await expect(formAlert).toHaveCount(0);

    // 같은 400 을 다시 받아 문구가 보인 뒤 이름을 고쳐도 사라진다
    await dialog.getByRole('button', { name: '만들기' }).click();
    await expect(formAlert).toBeVisible();
    await nameInput.fill('DESCLENPROBE2');
    await expect(formAlert).toHaveCount(0);
  });

  test('새 역할: errors 가 빈 400 은 조용히 실패하지 않고 이름 칸에 문구를 보인다 (WD-865)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/roles',
      { status: 400, message: '잘못된 요청입니다', errors: {} },
      { status: 400 },
    );
    const { dialog, nameInput } = await openNewRoleWith(page, 'DESCLENPROBE', '짧은 설명');
    await dialog.getByRole('button', { name: '만들기' }).click();

    // 기존 400 분기 그대로 — 문구가 이름 칸 오류로 이어지고(aria-invalid) 폼 오류 영역으로 가지 않는다
    await expect(dialog.getByText('잘못된 요청입니다')).toBeVisible();
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
  });

  test('새 역할: 이름 중복 400 뒤 설명만 고치면 이름 칸 오류는 남는다 (WD-865)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/roles',
      { status: 400, message: '이미 존재하는 역할 이름입니다: ADMIN' },
      { status: 400 },
    );
    const { dialog, nameInput, descInput } = await openNewRoleWith(page, 'ADMIN', '짧은 설명');
    await dialog.getByRole('button', { name: '만들기' }).click();

    const nameAlert = dialog.getByRole('alert').filter({ hasText: '이미 존재하는 역할 이름입니다: ADMIN' });
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(nameAlert).toBeVisible();

    await descInput.fill('다른 설명');
    await expect(nameAlert).toBeVisible();
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
  });

  test('새 역할: 설명 오류와 다른 필드 오류가 함께 오면 두 메시지 모두 보인다 (WD-865)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/roles',
      {
        status: 400,
        message: '잘못된 요청',
        errors: { description: DESCRIPTION_LIMIT_MESSAGE, securityLevelId: '보안 등급이 없습니다' },
      },
      { status: 400 },
    );
    const { dialog, descInput } = await openNewRoleWith(page, 'DESCLENPROBE', '짧은 설명입니다 열자');
    await dialog.getByRole('button', { name: '만들기' }).click();

    // 설명 칸 오류와 함께, root 로 사라지던 모르는 필드 오류도 폼 오류 영역에 보인다
    await expect(descInput).toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByText(DESCRIPTION_LIMIT_MESSAGE)).toBeVisible();
    await expect(dialog.getByText('보안 등급이 없습니다')).toBeVisible();
  });

  test('새 역할: 이름·설명 모두 잘못되면 두 칸 모두 오류 (WD-865)', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    const { dialog, nameInput, descInput } = await openNewRoleWith(page, 'bad name', 'ㄱ'.repeat(256));
    await dialog.getByRole('button', { name: '만들기' }).click();

    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(descInput).toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByText(DESCRIPTION_LIMIT_MESSAGE)).toBeVisible();
  });

  test('역할 상세: 설명 256자 저장은 설명 칸 오류 (WD-865)', async ({ authenticatedPage: page }) => {
    await setupRoleDetailMocks(page, 3, false);
    const capture = await mockApi(page, 'PUT', '/api/v1/roles/3', { id: 3 }, { capture: true });
    await page.goto('/admin/roles/3');

    const descInput = page.getByLabel('설명');
    await expect(descInput).toHaveAccessibleDescription(/255자까지/);
    await descInput.fill('ㄱ'.repeat(256));
    await saveRoleInfo(page);

    await expect(descInput).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByText(DESCRIPTION_LIMIT_MESSAGE)).toBeVisible();
    expect(capture.requests).toHaveLength(0);
  });

  test('시스템 역할에는 삭제 버튼이 표시되지 않는다', async ({ authenticatedPage: page }) => {
    // USER, ADMIN은 isSystem=true → 삭제 버튼 없음
    // EDITOR는 isSystem=false → 삭제 버튼 있음
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');

    // 삭제 버튼이 커스텀 역할(EDITOR)에만 1개 있는지 확인 (시스템 역할은 삭제 불가)
    // aria-label 기반 셀렉터로 버튼 존재 검증
    await expect(page.getByRole('button', { name: /역할 삭제/ })).toHaveCount(1);
  });

  test('커스텀 역할 삭제 버튼 클릭 시 확인 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);

    // EDITOR 역할(id=3) 삭제 DELETE API 캡처 설정
    const capture = await mockApi(page, 'DELETE', '/api/v1/roles/3', {}, { capture: true });

    await page.goto('/admin/roles');

    // EDITOR 역할 삭제 버튼 클릭 — aria-label 기반 셀렉터로 특정
    await page.getByRole('button', { name: 'EDITOR 역할 삭제' }).click();

    // 삭제 확인 다이얼로그 열림 확인 (AlertDialog)
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // 확인 버튼 클릭 → DELETE API 호출 검증
    const confirmButton = page.getByRole('alertdialog').getByRole('button', { name: /삭제|확인/ });
    await confirmButton.click();

    // DELETE API가 실제로 호출되었는지 확인
    const req = await capture.waitForRequest();
    expect(req).toBeTruthy();
  });

  /** WD-123: 사용 중인 역할을 지우면 그 사용자들이 권한을 잃으므로 확인 창에 영향받는 사람 수를 보인다. */
  test('사용자가 있는 역할을 삭제하려 하면 확인 창에 영향받는 사용자 수가 보인다 (WD-123)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    const clearanceCapture = await mockApi(
      page,
      'GET',
      '/api/v1/roles/3/clearance',
      { roleId: 3, securityLevelId: 1, userCount: 2, fixed: false },
      { capture: true },
    );
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/roles/3', {}, { capture: true });
    await page.goto('/admin/roles');

    await page.getByRole('button', { name: 'EDITOR 역할 삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByTestId('role-delete-impact')).toHaveText(
      '이 역할을 쓰는 사용자 2명이 이 역할의 권한을 즉시 잃습니다.',
    );
    // 창을 연 역할(id=3)의 사용자 수를 물었다
    expect(clearanceCapture.requests).toHaveLength(1);

    // 안내를 본 뒤에도 삭제는 그대로 된다
    await dialog.getByRole('button', { name: '삭제', exact: true }).click();
    await deleteCapture.waitForRequest();
  });

  test('사용자가 없는 역할은 영향 안내 없이 일반 확인 창만 보인다 (WD-123)', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    await mockApi(page, 'GET', '/api/v1/roles/3/clearance', { roleId: 3, securityLevelId: 1, userCount: 0, fixed: false });
    await page.goto('/admin/roles');

    await page.getByRole('button', { name: 'EDITOR 역할 삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText(/정말 삭제/)).toBeVisible();
    await expect(dialog.getByText('이 역할을 쓰는 사용자를 확인하는 중…')).toHaveCount(0);
    await expect(dialog.getByTestId('role-delete-impact')).toHaveCount(0);
    await expect(dialog.getByText(/불러오지 못했습니다/)).toHaveCount(0);
  });

  test('사용자 수 조회가 실패하면 영향 가능성 경고를 보이고 삭제는 막지 않는다 (WD-123)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await mockApi(page, 'GET', '/api/v1/roles/3/clearance', { message: 'error' }, { status: 500 });
    await page.goto('/admin/roles');

    await page.getByRole('button', { name: 'EDITOR 역할 삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(
      dialog.getByText('이 역할을 쓰는 사용자 수를 불러오지 못했습니다. 사용자가 있다면 이 역할의 권한을 잃습니다.'),
    ).toBeVisible({ timeout: 10000 });
    await expect(dialog.getByRole('button', { name: '삭제', exact: true })).toBeEnabled();
  });

  test('창을 다시 열면 사용자 수를 새로 묻고, 확인하는 동안에는 삭제 버튼이 막힌다 (WD-123)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    // 첫 조회는 0명, 두 번째 조회는 그 사이 다른 화면에서 역할을 받은 2명 — 두 번째 응답은 release 전까지 붙잡아 둔다.
    let calls = 0;
    let release!: () => void;
    const held = new Promise<void>((r) => (release = r));
    await page.route(
      (url) => url.pathname === '/api/v1/roles/3/clearance',
      async (route) => {
        calls += 1;
        const userCount = calls === 1 ? 0 : 2;
        if (calls > 1) await held;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ roleId: 3, securityLevelId: 1, userCount, fixed: false }),
        });
      },
    );
    await page.goto('/admin/roles');

    await page.getByRole('button', { name: 'EDITOR 역할 삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByRole('button', { name: '삭제', exact: true })).toBeEnabled();
    await expect(dialog.getByTestId('role-delete-impact')).toHaveCount(0);
    await dialog.getByRole('button', { name: '취소' }).click();

    await page.getByRole('button', { name: 'EDITOR 역할 삭제' }).click();
    // 캐시된 0명을 쓰지 않고 다시 묻는 중 — 안내를 보기 전에 지우지 못하게 막는다
    await expect(dialog.getByText('이 역할을 쓰는 사용자를 확인하는 중…')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '삭제', exact: true })).toBeDisabled();
    release();
    await expect(dialog.getByTestId('role-delete-impact')).toHaveText(
      '이 역할을 쓰는 사용자 2명이 이 역할의 권한을 즉시 잃습니다.',
    );
    await expect(dialog.getByRole('button', { name: '삭제', exact: true })).toBeEnabled();
    expect(calls).toBe(2);
  });

  test('역할 삭제 확인 버튼은 destructive 다 (WD-5)', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: 'EDITOR 역할 삭제' }).click();

    const confirm = page.getByRole('alertdialog').getByRole('button', { name: '삭제', exact: true });
    await expect(confirm).toHaveAttribute('data-variant', 'destructive');
    // 취소는 보조 액션(outline) 그대로
    await expect(page.getByRole('alertdialog').getByRole('button', { name: '취소' })).toHaveAttribute('data-variant', 'outline');
  });

  test('역할 상세 페이지에서 시스템 역할 이름 필드는 비활성화된다', async ({ authenticatedPage: page }) => {
    // isSystem=true 역할 상세 모킹
    await setupRoleDetailMocks(page, 1, true);
    await page.goto('/admin/roles/1');

    // 역할 상세 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '역할 상세' })).toBeVisible();

    // 시스템 역할 배지 확인
    await expect(page.getByText('시스템 역할')).toBeVisible();

    // 역할 이름 필드가 비활성화(disabled)되어 있는지 확인
    await expect(page.getByLabel('역할 이름')).toBeDisabled();
  });

  test('시스템 역할 상세 페이지에서 설명 필드도 비활성화된다 (#646)', async ({
    authenticatedPage: page,
  }) => {
    // 버그: "역할 이름"에는 disabled={role.isSystem}가 있었으나 "설명"에는 누락되어
    // 저장이 원천 차단된 시스템 역할에서도 설명 필드가 편집 가능해 보였다 (#646).
    await setupRoleDetailMocks(page, 1, true);
    await page.goto('/admin/roles/1');

    await expect(page.getByText('시스템 역할')).toBeVisible();

    // 설명 필드가 비활성화(disabled)되어 있는지 확인 — 이름 필드와 동일하게 처리되어야 한다
    await expect(page.getByLabel('설명')).toBeDisabled();
  });

  test('커스텀 역할 상세 페이지에서는 설명 필드가 정상적으로 편집 가능하다 (#646)', async ({
    authenticatedPage: page,
  }) => {
    // isSystem=false 역할(EDITOR)은 이름/설명 모두 편집 가능해야 한다 — 시스템 역할에만 적용되는 회귀 방지
    await setupRoleDetailMocks(page, 3, false);
    await page.goto('/admin/roles/3');

    const descInput = page.getByLabel('설명');
    await expect(descInput).toBeEnabled();
    await descInput.fill('편집 가능 확인');
    await expect(descInput).toHaveValue('편집 가능 확인');
  });

  test('시스템 역할 상세 페이지에서 저장 버튼은 비활성화된다 (refs #3)', async ({ authenticatedPage: page }) => {
    // isSystem=true 역할 상세 모킹 — 저장 버튼이 disabled 처리되어야 한다
    await setupRoleDetailMocks(page, 1, true);
    await page.goto('/admin/roles/1');

    // 저장 버튼이 비활성화(disabled) 상태인지 확인
    // 버그: isSavingRole만 체크하고 role.isSystem을 체크하지 않아 활성화된 채로 표시되었음
    const saveButton = page.getByRole('button', { name: '저장', exact: true });
    await expect(saveButton).toBeDisabled();
  });

  // WD-817: 상세 화면의 역할 이름 칸도 생성 다이얼로그와 같은 스키마라, 고칠 수 있는 사용자 역할이면 필수 * 와
  // 규칙 안내(#role-name-desc)가 보이고 칸의 aria-describedby 로 이어진다.
  test('편집 가능한 역할 상세의 역할 이름 칸에 필수 표시와 규칙 안내가 이어진다 (WD-817)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    await page.goto('/admin/roles/3');

    const input = page.locator('#role-name');
    await expect(input).toHaveValue('EDITOR');
    await expect(input).toBeEnabled();
    await expect(page.locator('label[for="role-name"]')).toHaveText('역할 이름*');
    await expect(input).toHaveAttribute('aria-required', 'true');
    const desc = page.locator('#role-name-desc');
    await expect(desc).toContainText('대문자로 시작');
    await expect(desc).toContainText('DATA_EDITOR');
    await expect(input).toHaveAttribute('aria-describedby', /role-name-desc/);
  });

  // WD-817 리뷰: 시스템 역할은 칸이 잠겨 고칠 수 없으니, 규칙 안내도 필수 표시(*·aria-required)도 보이지 않는다.
  test('시스템 역할 상세의 잠긴 역할 이름 칸에는 필수 표시·규칙 안내가 없다 (WD-817)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 1, true);
    await page.goto('/admin/roles/1');

    const input = page.locator('#role-name');
    await expect(input).toHaveValue('USER');
    await expect(input).toBeDisabled();
    await expect(page.locator('label[for="role-name"]')).toHaveText('역할 이름');
    await expect(input).not.toHaveAttribute('aria-required', 'true');
    await expect(page.locator('#role-name-desc')).toHaveCount(0);
  });

  test('역할 상세 페이지에서 권한 할당 섹션이 카테고리별로 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupRoleDetailMocks(page, 3, false);
    await page.goto('/admin/roles/3');

    // 권한 할당 카드 확인
    await expect(page.getByText('권한 할당')).toBeVisible();

    // 카테고리 그룹 제목(h3) 확인 — 서버는 소문자 키(dataset, pipeline, admin)를 보내고
    // 화면은 한국어 라벨(데이터셋, 파이프라인)로 보여준다(WD-814).
    // admin 은 마이그레이션이 시드하지 않는 테스트 전용 키로, 원시 키 폴백을 고정하기 위해 쓴다.
    await expect(page.getByRole('heading', { level: 3, name: '데이터셋', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { level: 3, name: '파이프라인', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { level: 3, name: 'admin', exact: true })).toBeVisible();

    // createPermissions()에서 생성된 특정 권한 이름/설명이 렌더링되는지 확인
    // code: 'DATASET_READ', description: '데이터셋 조회'
    await expect(page.getByText('DATASET_READ')).toBeVisible();
    // code: 'PIPELINE_READ', description: '파이프라인 조회'
    await expect(page.getByText('PIPELINE_READ')).toBeVisible();

    // 권한 저장 버튼 확인
    await expect(page.getByRole('button', { name: '권한 저장' })).toBeVisible();
  });

  test('권한 토글 — 퍼미션 체크박스 클릭 시 권한 목록이 업데이트된다', async ({ authenticatedPage: page }) => {
    // isSystem=false 역할(EDITOR)의 상세 모킹 — 초기 권한: DATASET_READ(id=1), DATASET_WRITE(id=2)
    await setupRoleDetailMocks(page, 3, false);
    await page.goto('/admin/roles/3');

    // DATASET_READ 권한 체크박스 확인 — 초기 체크 상태 (createRoleDetail 기본값 포함)
    // 체크박스 id는 perm-{permId} 형식이며, Label의 htmlFor로 연결된다
    const datasetReadCheckbox = page.getByRole('checkbox', { name: /DATASET_READ/ });
    await expect(datasetReadCheckbox).toBeVisible();

    // PIPELINE_READ 권한은 초기에 체크되지 않은 상태여야 한다 (역할에 미포함)
    const pipelineReadCheckbox = page.getByRole('checkbox', { name: /PIPELINE_READ/ });
    await expect(pipelineReadCheckbox).not.toBeChecked();

    // PIPELINE_READ 체크박스 클릭 → 선택 상태로 변경
    await pipelineReadCheckbox.click();

    // 클릭 후 체크 상태로 변경되었는지 확인
    await expect(pipelineReadCheckbox).toBeChecked();
  });

  test('권한 저장 — PUT payload 검증', async ({ authenticatedPage: page }) => {
    // isSystem=false 역할(EDITOR) 상세 모킹
    await setupRoleDetailMocks(page, 3, false);

    // PUT /api/v1/roles/3/permissions 캡처 설정 — goto 이전에 등록해야 한다
    const saveCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/roles/3/permissions',
      { id: 3, name: 'EDITOR', permissions: [] },
      { capture: true },
    );
    // 저장 후 역할 재조회 모킹 — handleSavePermissions에서 getRoleById 재호출
    await setupRoleDetailMocks(page, 3, false);

    await page.goto('/admin/roles/3');

    // 권한 저장 버튼 클릭 → PUT /api/v1/roles/3/permissions 호출
    await page.getByRole('button', { name: '권한 저장' }).click();

    // API payload 검증 — permissionIds 배열이 전달되어야 한다
    const req = await saveCapture.waitForRequest();
    expect(req.payload).toHaveProperty('permissionIds');
    expect(Array.isArray((req.payload as { permissionIds: number[] }).permissionIds)).toBe(true);

    // 성공 토스트 확인 — handleSavePermissions 성공 경로 (lines 118-120)
    await expect(page.getByText('권한이 저장되었습니다.')).toBeVisible({ timeout: 3000 });
  });

  test('역할 정보 저장 — PUT payload 및 성공 토스트 검증', async ({ authenticatedPage: page }) => {
    // isSystem=false 역할(EDITOR) 상세 모킹
    await setupRoleDetailMocks(page, 3, false);

    // PUT /api/v1/roles/3 캡처 설정
    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/roles/3',
      { id: 3, name: 'EDITOR_NEW', description: '편집자 역할', isSystem: false, permissions: [] },
      { capture: true },
    );

    await page.goto('/admin/roles/3');
    await expect(page.getByRole('heading', { name: '역할 상세' })).toBeVisible();

    // 역할 이름 변경
    const nameInput = page.getByLabel('역할 이름');
    await nameInput.clear();
    await nameInput.fill('EDITOR_NEW');

    // 저장 버튼(type=submit) 클릭 — onRoleSubmit 실행 (lines 89-99)
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // PUT payload 검증
    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: 'EDITOR_NEW' });

    // 성공 토스트 확인 (line 99)
    await expect(page.getByText('역할 정보가 업데이트되었습니다.')).toBeVisible({ timeout: 3000 });
  });

  test('끝 공백이 붙어 저장된 옛 역할은 설명만 고쳐도 공백을 잘라 저장된다 (WD-279)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    // 서버가 이름 규칙을 검사하기 전에 저장된 비정상 이름 — 입력란에서는 끝 공백이 보이지 않는다
    await mockApi(
      page,
      'GET',
      '/api/v1/roles/3',
      createRoleDetail({ id: 3, name: 'EDITOR ', description: '편집자 역할', isSystem: false }),
    );
    const updateCapture = await mockApi(page, 'PUT', '/api/v1/roles/3', {}, { capture: true });

    await page.goto('/admin/roles/3');
    const descInput = page.getByLabel('설명');
    await descInput.clear();
    await descInput.fill('설명만 고침');
    await saveRoleInfo(page);

    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: 'EDITOR', description: '설명만 고침' });
    await expect(page.getByText('역할 이름은 대문자, 숫자, 밑줄만 허용됩니다')).toHaveCount(0);
    await expect(page.getByText('역할 정보가 업데이트되었습니다.')).toBeVisible();
    // 입력란도 잘린 이름으로 맞춰져, 저장 직후 나가도 「저장하지 않은 변경사항」 경고가 뜨지 않는다(WD-119 회귀 방지)
    await expect(page.getByLabel('역할 이름')).toHaveValue('EDITOR');
    await setupRoleListMocks(page);
    await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
    await expect(page).toHaveURL(/\/admin\/roles$/);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });

  test('시스템 역할과 같은 이름으로 바꾸면 서버의 중복 안내가 보인다 (WD-279)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/roles/3',
      { status: 400, message: '이미 존재하는 역할 이름입니다: ADMIN' },
      { status: 400, capture: true },
    );

    await page.goto('/admin/roles/3');
    const nameInput = page.getByLabel('역할 이름');
    await nameInput.clear();
    await nameInput.fill('ADMIN ');
    await saveRoleInfo(page);

    // 끝 공백은 잘라 보내 서버의 이름 규칙이 아닌 중복 검사에 걸린다
    expect((await updateCapture.waitForRequest()).payload).toMatchObject({ name: 'ADMIN' });
    await expect(page.getByText('이미 존재하는 역할 이름입니다: ADMIN')).toBeVisible();
  });

  test('역할 상세 — 중복 이름 400 은 이름 칸 오류(alert·aria-invalid·포커스)로 보인다 (WD-1102)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    // 필드 errors 없는 400 — 서버의 이름 중복 응답
    await mockApi(
      page,
      'PUT',
      '/api/v1/roles/3',
      { status: 400, message: '이미 존재하는 역할 이름입니다: ADMIN' },
      { status: 400 },
    );

    await page.goto('/admin/roles/3');
    const nameInput = page.getByLabel('역할 이름');
    await nameInput.fill('ADMIN');
    await saveRoleInfo(page);

    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).toBeFocused();
    const alert = page.locator('#role-name-error');
    await expect(alert).toHaveAttribute('role', 'alert');
    await expect(alert).toHaveText('이미 존재하는 역할 이름입니다: ADMIN');
    await expect(nameInput).toHaveAttribute('aria-describedby', /role-name-error/);
    // 공통 오류 영역에 같은 문구가 겹쳐 나오지 않는다
    await expect(page.getByText('이미 존재하는 역할 이름입니다: ADMIN')).toHaveCount(1);

    // 설명 칸을 고쳐도 이름 칸 오류는 남는다
    await page.getByLabel('설명').fill('설명 추가');
    await expect(alert).toBeVisible();

    // 이름을 고치면 오류가 지워진다
    await nameInput.fill('ADMIN2');
    await expect(page.locator('#role-name-error')).toHaveCount(0);
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');

    // 오류 뒤 다시 저장이 성공하면 이름 칸 오류가 남지 않는다 (WD-1102)
    await mockApi(page, 'PUT', '/api/v1/roles/3', { id: 3, name: 'ADMIN2', description: '편집자 역할', isSystem: false, permissions: [] });
    await saveRoleInfo(page);
    await expect(page.getByText('역할 정보가 업데이트되었습니다.')).toBeVisible({ timeout: 3000 });
    await expect(page.locator('#role-name-error')).toHaveCount(0);
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
  });

  test('역할 상세 — 400 에 name 필드 오류가 실리면 이름 칸에 붙는다 (WD-1102)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    await mockApi(
      page,
      'PUT',
      '/api/v1/roles/3',
      {
        status: 400,
        message: '입력값 검증 실패',
        errors: { name: '역할 이름 형식이 올바르지 않습니다' },
      },
      { status: 400 },
    );

    await page.goto('/admin/roles/3');
    const nameInput = page.getByLabel('역할 이름');
    await nameInput.fill('BAD_NAME');
    await saveRoleInfo(page);

    const alert = page.locator('#role-name-error');
    await expect(alert).toHaveText('역할 이름 형식이 올바르지 않습니다');
    await expect(alert).toHaveAttribute('role', 'alert');
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).toBeFocused();
    await expect(page.getByText('입력값 검증 실패')).toHaveCount(0);
  });

  test('역할 상세 — 이름을 바꾸지 않은 저장의 필드 없는 400 은 공통 오류로 (WD-1102)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    await mockApi(
      page,
      'PUT',
      '/api/v1/roles/3',
      { status: 400, message: '시스템 역할의 이름·설명은 바꿀 수 없습니다' },
      { status: 400 },
    );

    await page.goto('/admin/roles/3');
    const nameInput = page.getByLabel('역할 이름');
    await expect(nameInput).toBeVisible();
    // 설명만 고친다 — 이름은 저장된 값 그대로
    await page.getByLabel('설명').fill('설명만 바꿈');
    await saveRoleInfo(page);

    const text = '시스템 역할의 이름·설명은 바꿀 수 없습니다';
    await expect(page.getByRole('alert').filter({ hasText: text })).toBeVisible();
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
    await expect(page.locator('#role-name-error')).toHaveCount(0);

    // 공통 오류는 이름·설명 편집 시 지워진다
    await page.getByLabel('설명').fill('설명만 바꿈 2');
    await expect(page.getByText(text)).toHaveCount(0);
  });

  test('역할 상세 — 메시지 없는 400 은 이름 칸이 아니라 공통 오류로 (WD-1102)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    await mockApi(page, 'PUT', '/api/v1/roles/3', { status: 400 }, { status: 400 });

    await page.goto('/admin/roles/3');
    const nameInput = page.getByLabel('역할 이름');
    await nameInput.fill('NEWNAME');
    await saveRoleInfo(page);

    await expect(page.getByRole('alert').filter({ hasText: '역할 업데이트에 실패했습니다.' })).toBeVisible();
    await expect(page.locator('#role-name-error')).toHaveCount(0);
  });

  test('역할 상세 — 덮어쓰기 저장이 중복 이름 400 이면 충돌 창을 닫고 이름 칸 오류 (WD-1102)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    // 첫 PUT 은 409 편집 충돌, 덮어쓰기(두 번째) PUT 은 중복 이름 400
    let puts = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/roles/3',
      (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        puts += 1;
        if (puts === 1) {
          return route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({ status: 409, code: 'EDIT_CONFLICT', message: '다른 관리자가 먼저 바꿨습니다' }),
          });
        }
        return route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ status: 400, message: '이미 존재하는 역할 이름입니다: ADMIN' }),
        });
      },
    );

    await page.goto('/admin/roles/3');
    const nameInput = page.getByLabel('역할 이름');
    await nameInput.fill('ADMIN');
    await saveRoleInfo(page);

    const dialog = page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();

    await expect(dialog).toBeHidden();
    await expect(page.locator('#role-name-error')).toHaveText('이미 존재하는 역할 이름입니다: ADMIN');
    await expect(nameInput).toBeFocused();
  });

  test('역할 상세 — 덮어쓰기 중 Escape 로 창을 닫은 뒤 중복 이름 400 이면 이름 칸에 바로 포커스하고 다음 창 닫힘이 포커스를 빼앗지 않는다 (WD-1102)', async ({
    authenticatedPage: page,
  }) => {
    const DUP = '이미 존재하는 역할 이름입니다: ADMIN';
    const { release, dialog, nameInput } = await openHeldOverwrite(page, 'ADMIN', 400, { status: 400, message: DUP });

    // 덮어쓰기 요청이 끝나기 전에 Escape 로 창을 닫는다 — 그 뒤 400 이 온다
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    release();

    // 창이 이미 닫혔으니 포커스 기록만 남기지 않고 이름 칸으로 바로 옮긴다
    await expect(page.locator('#role-name-error')).toHaveText(DUP);
    await expect(nameInput).toBeFocused();

    // 다음 저장의 충돌 창을 닫을 때 지난 기록이 포커스를 이름 칸으로 빼앗지 않는다(Radix 기본 복귀 — 저장 버튼)
    await page.getByLabel('설명').fill('다른 설명');
    await saveRoleInfo(page);
    await expect(dialog).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(
      page.locator('[data-slot="card"]').filter({ hasText: '역할 정보' }).getByRole('button', { name: '저장', exact: true }),
    ).toBeFocused();
  });

  test('역할 상세 — 덮어쓰기 중 Escape 직후(닫힘 애니메이션 중) 중복 이름 400 이 와도 닫힘이 끝난 뒤 이름 칸에 포커스가 남는다 (WD-1102)', async ({
    authenticatedPage: page,
  }) => {
    const DUP = '이미 존재하는 역할 이름입니다: ADMIN';
    const { release, dialog, nameInput } = await openHeldOverwrite(page, 'ADMIN', 400, { status: 400, message: DUP });

    // 창이 닫히기를 기다리지 않고 바로 400 을 돌려준다 — 닫힘 애니메이션 중 옮긴 포커스가 닫힘이 끝난 뒤에도 남아야 한다(Radix 가 옛 트리거로 되돌리지 않는지)
    await page.keyboard.press('Escape');
    release();

    await expect(dialog).toBeHidden();
    await expect(page.locator('#role-name-error')).toHaveText(DUP);
    await expect(nameInput).toBeFocused();
  });

  test('역할 상세 — 덮어쓰기 중 입력을 고친 뒤 400 이 오면 충돌 창을 닫고 옛 오류는 붙이지 않는다 (WD-1102)', async ({
    authenticatedPage: page,
  }) => {
    const DUP = '이미 존재하는 역할 이름입니다: ADMIN';
    const { release, dialog, nameInput } = await openHeldOverwrite(page, 'ADMIN', 400, { status: 400, message: DUP });

    // 창 뒤 입력란 값을 프로그램으로 바꾼다 — 모달이 뒤를 막아 fill({ force }) 도 닿지 않는다. 「저장 중 편집」 갈래를 만든다
    await nameInput.evaluate((el: HTMLInputElement) => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(el, 'ADMIN2');
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });
    release();

    // 400 은 입력 문제 — 창이 닫히고, 고친 입력에 옛 오류는 붙지 않는다
    await expect(dialog).toBeHidden();
    await expect(page.locator('#role-name-error')).toHaveCount(0);
    await expect(nameInput).toHaveValue('ADMIN2');
  });

  test('역할 상세 — 덮어쓰기 저장이 500 이면 창을 열어 둔 채 토스트로 알리고 창 뒤에는 오류를 쓰지 않는다 (WD-1102)', async ({
    authenticatedPage: page,
  }) => {
    const SERVER_MSG = '서버 오류로 저장하지 못했습니다';
    const { release, dialog } = await openHeldOverwrite(page, 'NEWNAME', 500, { status: 500, message: SERVER_MSG });
    release();

    await expect(page.locator('[data-sonner-toast]').filter({ hasText: SERVER_MSG })).toBeVisible();
    // 창은 그대로 — 다시 덮어쓰거나 다시 불러올 수 있다
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: '내 변경으로 덮어쓰기' })).toBeEnabled();
    // 창 뒤 역할 오류 영역에는 쓰지 않는다
    await expect(page.locator('form').filter({ has: page.getByLabel('역할 이름') }).getByText(SERVER_MSG)).toHaveCount(0);
  });

  test('역할 상세 — 저장 500 은 공통 오류(alert)로, 이름 칸은 오류 아님 (WD-1102)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    await mockApi(page, 'PUT', '/api/v1/roles/3', { status: 500, message: '서버 오류' }, { status: 500 });

    await page.goto('/admin/roles/3');
    const nameInput = page.getByLabel('역할 이름');
    await nameInput.fill('NEWNAME');
    await saveRoleInfo(page);

    await expect(page.getByRole('alert').filter({ hasText: '서버 오류' })).toBeVisible();
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
    await expect(page.locator('#role-name-error')).toHaveCount(0);
  });

  test('역할 정보 로드 실패 시 에러 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    // 역할 API 500 에러 → fetchData catch 분기 (lines 61-62)
    await mockApi(page, 'GET', '/api/v1/roles/1', { message: '서버 오류' }, { status: 500 });
    await mockApi(page, 'GET', '/api/v1/permissions', []);
    // 에러 후 /admin/roles 로 이동하므로 역할 목록도 모킹
    await setupRoleListMocks(page);

    await page.goto('/admin/roles/1');

    // 에러 토스트 확인 (line 61) — Sonner는 동일 메시지 토스트를 중복 렌더링할 수 있으므로 first() 사용
    await expect(page.getByText('역할 정보를 불러오는 데 실패했습니다.').first()).toBeVisible({ timeout: 5000 });
  });

  test('생성 실패 후 이름을 수정하면 서버 중복 에러 메시지가 지워진다 (#558)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);

    // 중복 이름 제출 시 409 에러를 반환하도록 모킹
    await mockApi(
      page,
      'POST',
      '/api/v1/roles',
      { message: '이미 존재하는 역할 이름입니다: ADMIN' },
      { status: 409 },
    );

    await page.goto('/admin/roles');
    await page.getByRole('button', { name: '새 역할' }).click();

    const nameInput = page.getByLabel('역할 이름');
    await nameInput.fill('ADMIN');
    await page.getByRole('button', { name: '만들기' }).click();

    // 서버 에러 메시지가 표시된다
    const errorMessage = page.getByText('이미 존재하는 역할 이름입니다: ADMIN');
    await expect(errorMessage).toBeVisible();

    // 제출하지 않은 채 이름만 다른 값으로 고치면 stale 서버 에러가 즉시 사라져야 한다
    await nameInput.fill('EXPLORER_TEST_ROLE_1');
    await expect(errorMessage).not.toBeVisible();
  });

  // WD-819 공용 — 서버는 중복 이름을 409 가 아니라 400 으로 돌려준다
  const DUPLICATE_ROLE_MESSAGE = '이미 존재하는 역할 이름입니다: ADMIN';
  const DUPLICATE_ROLE_BODY = { status: 400, message: DUPLICATE_ROLE_MESSAGE };

  /** 새 역할 대화상자를 열어 이름을 채우고 「만들기」를 누른 상태까지 만든다(POST 모킹은 호출 전에 등록). */
  async function submitNewRole(page: Page, name: string) {
    await page.goto('/admin/roles');
    await page.getByRole('button', { name: '새 역할' }).click();
    const dialog = page.getByRole('dialog');
    const nameInput = dialog.getByLabel('역할 이름');
    await nameInput.fill(name);
    await dialog.getByRole('button', { name: '만들기' }).click();
    return { dialog, nameInput };
  }

  test('새 역할의 같은 이름 서버 오류는 역할 이름 칸에 이어져 읽힌다 (WD-819)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await mockApi(page, 'POST', '/api/v1/roles', DUPLICATE_ROLE_BODY, { status: 400 });
    const { dialog, nameInput } = await submitNewRole(page, 'ADMIN');

    const alert = dialog.getByRole('alert').filter({ hasText: DUPLICATE_ROLE_MESSAGE });
    await expect(alert).toBeVisible();
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    const alertId = await alert.getAttribute('id');
    expect(alertId).toBeTruthy();
    expect(await nameInput.getAttribute('aria-describedby')).toContain(alertId as string);
    await expect(nameInput).toBeFocused();
  });

  test('새 역할 400 이름 오류 뒤 닫기(X)로 닫고 다시 열면 오류가 남지 않는다 (WD-819)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await mockApi(page, 'POST', '/api/v1/roles', DUPLICATE_ROLE_BODY, { status: 400 });
    const { dialog } = await submitNewRole(page, 'ADMIN');
    await expect(dialog.getByRole('alert').filter({ hasText: DUPLICATE_ROLE_MESSAGE })).toBeVisible();

    await dialog.getByRole('button', { name: '닫기' }).click();
    await expect(dialog).toHaveCount(0);
    await page.getByRole('button', { name: '새 역할' }).click();

    const reopened = page.getByRole('dialog');
    await expect(reopened).toBeVisible();
    await expect(reopened.getByRole('alert')).toHaveCount(0);
    await expect(reopened.getByLabel('역할 이름')).not.toHaveAttribute('aria-invalid', 'true');
  });

  test('새 역할 요청 중 ESC 로 닫은 뒤 늦게 온 400 오류는 다시 열어도 보이지 않는다 (WD-819)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    // 응답을 붙잡아 「요청 중에 닫힘」을 시간에 기대지 않고 만든다
    const held = await holdApi(page, 'POST', '/api/v1/roles', 400, DUPLICATE_ROLE_BODY);
    const { dialog } = await submitNewRole(page, 'ADMIN');
    await expect.poll(() => held.requests.length).toBe(1);
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    // 닫은 뒤에 응답을 풀어 늦게 온 400 이 catch 까지 처리되게 한다
    const responded = page.waitForResponse((r) => r.url().includes('/api/v1/roles') && r.request().method() === 'POST');
    held.release();
    await responded;
    // 응답 처리·렌더가 끝나도록 프레임 두 번을 보낸다(고정 대기 대신)
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));

    await page.getByRole('button', { name: '새 역할' }).click();
    const reopened = page.getByRole('dialog');
    await expect(reopened).toBeVisible();
    await expect(reopened.getByRole('alert')).toHaveCount(0);
    await expect(reopened.getByLabel('역할 이름')).not.toHaveAttribute('aria-invalid', 'true');
  });

  test('새 역할 요청 중 닫고 다시 열어 입력 중일 때 늦게 온 성공은 대화상자·입력을 건드리지 않고 토스트로 알리며 목록을 다시 읽는다 (WD-819)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    const held = await holdApi(page, 'POST', '/api/v1/roles', 201, { id: 99, name: 'NEW_ROLE' });
    const { dialog } = await submitNewRole(page, 'NEW_ROLE');
    await expect.poll(() => held.requests.length).toBe(1);
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await page.getByRole('button', { name: '새 역할' }).click();
    const reopened = page.getByRole('dialog');
    await expect(reopened).toBeVisible();
    await reopened.getByLabel('역할 이름').fill('OTHER_ROLE');
    // 응답을 풀기 전부터 목록 재조회 GET 을 센다
    let listGets = 0;
    page.on('request', (r) => {
      if (r.method() === 'GET' && new URL(r.url()).pathname === '/api/v1/roles') listGets += 1;
    });
    held.release();

    await expect(page.getByText('역할이 생성되었습니다.')).toBeVisible();
    await expect.poll(() => listGets).toBeGreaterThanOrEqual(1);
    await expect(reopened).toBeVisible();
    await expect(reopened.getByLabel('역할 이름')).toHaveValue('OTHER_ROLE');
  });

  test('새 역할 요청 중 닫고 다시 열지 않았다면 늦게 온 성공도 평소처럼 폼을 비워 다음에 열면 칸이 비어 있다 (WD-819)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    const held = await holdApi(page, 'POST', '/api/v1/roles', 201, { id: 99, name: 'NEW_ROLE' });
    const { dialog } = await submitNewRole(page, 'NEW_ROLE');
    await dialog.getByLabel('설명').fill('설명 값');
    await expect.poll(() => held.requests.length).toBe(1);
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    held.release();
    await expect(page.getByText('역할이 생성되었습니다.')).toBeVisible();

    await page.getByRole('button', { name: '새 역할' }).click();
    const reopened = page.getByRole('dialog');
    await expect(reopened).toBeVisible();
    await expect(reopened.getByLabel('역할 이름')).toHaveValue('');
    await expect(reopened.getByLabel('설명')).toHaveValue('');
  });

  test('새 역할 요청 중 닫은 뒤 늦게 온 400 실패는 오류 토스트로 알리고, 다시 열면 칸 오류가 없다 (WD-819)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    const held = await holdApi(page, 'POST', '/api/v1/roles', 400, DUPLICATE_ROLE_BODY);
    const { dialog } = await submitNewRole(page, 'ADMIN');
    await expect.poll(() => held.requests.length).toBe(1);
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    held.release();

    await expect(page.getByText(DUPLICATE_ROLE_MESSAGE)).toBeVisible();
    await page.getByRole('button', { name: '새 역할' }).click();
    const reopened = page.getByRole('dialog');
    await expect(reopened).toBeVisible();
    await expect(reopened.getByRole('alert')).toHaveCount(0);
    await expect(reopened.getByLabel('역할 이름')).not.toHaveAttribute('aria-invalid', 'true');
  });

  test('새 역할의 이름 무관 서버 오류(500)는 이름 칸에 붙지 않고 알림으로 읽힌다 (WD-819)', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleListMocks(page);
    await mockApi(page, 'POST', '/api/v1/roles', { message: '서버 오류' }, { status: 500 });
    const { dialog, nameInput } = await submitNewRole(page, 'SOME_ROLE');

    await expect(dialog.getByRole('alert').filter({ hasText: '서버 오류' })).toBeVisible();
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
  });

  // #567: 권한을 전부 해제하고 저장을 시도하면 확인 다이얼로그 없이 즉시 반영되면 안 된다 (#512와 동일 패턴)
  test('선택된 권한이 0개가 되면 저장 전 확인 다이얼로그가 표시된다 (#567)', async ({ authenticatedPage: page }) => {
    await setupRoleDetailMocks(page, 3, false);

    // 권한 API가 실제로 호출되면 실패해야 하므로 캡처만 하고, 다이얼로그가 뜨는 동안 호출되지 않았는지 확인한다
    const saveCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/roles/3/permissions',
      { id: 3, name: 'EDITOR', permissions: [] },
      { capture: true },
    );

    await page.goto('/admin/roles/3');

    // setupRoleDetailMocks(3, false)는 createRoleDetail 기본값으로 DATASET_READ, DATASET_WRITE 2개 권한을 부여 — 둘 다 해제하면 선택된 권한이 0개가 된다
    const readCheckbox = page.getByRole('checkbox', { name: /DATASET_READ/ });
    const writeCheckbox = page.getByRole('checkbox', { name: /DATASET_WRITE/ });
    await expect(readCheckbox).toBeChecked();
    await expect(writeCheckbox).toBeChecked();
    await readCheckbox.click();
    await writeCheckbox.click();
    await expect(readCheckbox).not.toBeChecked();
    await expect(writeCheckbox).not.toBeChecked();

    // 권한 저장 클릭 — API가 즉시 호출되지 않고 확인 다이얼로그가 먼저 떠야 한다
    await page.getByRole('button', { name: '권한 저장' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(
      page.getByText('이 역할의 모든 권한을 해제하면 이 역할을 사용 중인 모든 사용자가 해당 권한을 즉시 잃습니다. 계속하시겠습니까?'),
    ).toBeVisible();
    expect(saveCapture.requests).toHaveLength(0);

    // 취소 시 API가 호출되지 않아야 한다
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).not.toBeVisible();
    expect(saveCapture.requests).toHaveLength(0);

    // 다시 저장 시도 후 확인을 누르면 그때 PUT이 호출되어야 한다
    await page.getByRole('button', { name: '권한 저장' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('button', { name: '권한 해제' }).click();

    const req = await saveCapture.waitForRequest();
    expect((req.payload as { permissionIds: number[] }).permissionIds).toEqual([]);
  });

  test('커스텀 역할 삭제 버튼에 aria-label이 부여된다 (접근성 회귀)', async ({ authenticatedPage: page }) => {
    // 역할 목록 모킹
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');

    // aria-label="EDITOR 역할 삭제" 버튼이 렌더링되어야 한다
    const deleteButton = page.getByRole('button', { name: 'EDITOR 역할 삭제' });
    await expect(deleteButton).toBeVisible();

    // 시스템 역할(USER, ADMIN)에는 삭제 버튼이 없어야 한다
    await expect(page.getByRole('button', { name: 'USER 역할 삭제' })).not.toBeVisible();
    await expect(page.getByRole('button', { name: 'ADMIN 역할 삭제' })).not.toBeVisible();
  });

  /**
   * 이슈 #636 회귀 방지 — 역할 상세 페이지는 useUnsavedChangesGuard 자체를 호출하지 않아
   * "목록으로 돌아가기" 버튼이 navigate()를 직접 호출, dirty 상태에서도 경고 없이 이탈되던 결함.
   */
  test.describe('이슈 #636 — 미저장 변경 가드', () => {
    test('설명 필드 dirty 상태에서 "목록으로 돌아가기" 버튼 클릭 시 이탈 다이얼로그가 표시된다', async ({
      authenticatedPage: page,
    }) => {
      await setupRoleDetailMocks(page, 3, false);
      await page.goto('/admin/roles/3');
      await expect(page.getByRole('heading', { name: '역할 상세' })).toBeVisible();

      // 설명 필드 변경 (저장 버튼은 누르지 않음)
      const descInput = page.getByLabel('설명');
      await descInput.fill('636-unsaved-change-probe');

      // 목록으로 돌아가기 버튼 클릭 — dirty 상태이므로 즉시 이동하면 안 되고 확인 다이얼로그가 떠야 한다
      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(
        page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?'),
      ).toBeVisible();

      // 아직 역할 상세 페이지에 머물러 있어야 한다
      await expect(page).toHaveURL(/\/admin\/roles\/3$/);
    });

    test('이탈 다이얼로그에서 취소 클릭 시 페이지에 머무르고 입력값이 보존된다', async ({
      authenticatedPage: page,
    }) => {
      await setupRoleDetailMocks(page, 3, false);
      await page.goto('/admin/roles/3');

      const descInput = page.getByLabel('설명');
      await descInput.fill('636-unsaved-change-probe');

      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await page.getByRole('button', { name: '취소' }).click();
      await expect(page.getByRole('alertdialog')).not.toBeVisible();

      // 입력값이 그대로 보존되어야 한다
      await expect(descInput).toHaveValue('636-unsaved-change-probe');
      await expect(page).toHaveURL(/\/admin\/roles\/3$/);
    });

    test('이탈 다이얼로그에서 이탈 클릭 시 역할 목록 페이지로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupRoleDetailMocks(page, 3, false);
      await page.goto('/admin/roles/3');

      const descInput = page.getByLabel('설명');
      await descInput.fill('636-unsaved-change-probe');

      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await setupRoleListMocks(page);
      await page.getByRole('button', { name: '이탈' }).click();

      await expect(page).toHaveURL(/\/admin\/roles$/);
    });

    /**
     * WD-118 회귀 방지 — 가드가 이름·설명 폼의 isDirty 만 봐서, 권한 체크박스만 바꾸고 이동하면
     * 경고 없이 변경이 버려졌다. 권한 체크 변경도 미저장 변경으로 봐야 한다.
     */
    test('권한 체크만 바꾸고 "목록으로 돌아가기"를 누르면 이탈 다이얼로그가 표시된다 (WD-118)', async ({
      authenticatedPage: page,
    }) => {
      await setupRoleDetailMocks(page, 3, false);
      await page.goto('/admin/roles/3');

      const pipelineRead = page.getByRole('checkbox', { name: /PIPELINE_READ/ });
      await pipelineRead.click();
      await expect(pipelineRead).toBeChecked();

      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(
        page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?'),
      ).toBeVisible();
      await expect(page).toHaveURL(/\/admin\/roles\/3$/);

      // 취소하면 체크한 권한이 그대로 남아 있어야 한다
      await page.getByRole('button', { name: '취소' }).click();
      await expect(pipelineRead).toBeChecked();
    });

    test('권한 체크를 바꿨다가 원래대로 되돌리면 다이얼로그 없이 이동한다 (WD-118)', async ({
      authenticatedPage: page,
    }) => {
      await setupRoleDetailMocks(page, 3, false);
      await page.goto('/admin/roles/3');

      const pipelineRead = page.getByRole('checkbox', { name: /PIPELINE_READ/ });
      await pipelineRead.click();
      await pipelineRead.click();
      await expect(pipelineRead).not.toBeChecked();

      await setupRoleListMocks(page);
      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
      await expect(page.getByRole('alertdialog')).not.toBeVisible();
      await expect(page).toHaveURL(/\/admin\/roles$/);
    });

    test('권한을 저장한 뒤에는 다이얼로그 없이 이동한다 (WD-118)', async ({
      authenticatedPage: page,
    }) => {
      await setupRoleDetailMocks(page, 3, false);
      // 저장 뒤 재조회는 새 권한(PIPELINE_READ 포함)을 돌려준다 — 기준이 저장 상태로 바뀌어야 경고가 사라진다.
      const saved = createRoleDetail({
        id: 3,
        name: 'EDITOR',
        isSystem: false,
        permissions: [
          ...createRoleDetail().permissions,
          createPermission({ id: 3, code: 'PIPELINE_READ', category: 'pipeline' }),
        ],
      });
      const saveCapture = await mockApi(
        page,
        'PUT',
        '/api/v1/roles/3/permissions',
        {},
        { capture: true },
      );
      await page.goto('/admin/roles/3');

      const pipelineRead = page.getByRole('checkbox', { name: /PIPELINE_READ/ });
      await pipelineRead.click();
      await mockApi(page, 'GET', '/api/v1/roles/3', saved);
      await page.getByRole('button', { name: '권한 저장' }).click();
      expect((await saveCapture.waitForRequest()).payload).toEqual({ permissionIds: [1, 2, 3], expectedPermissionIds: [1, 2] });
      await expect(page.getByText('권한이 저장되었습니다.')).toBeVisible();
      await expect(pipelineRead).toBeChecked();

      await setupRoleListMocks(page);
      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
      await expect(page.getByRole('alertdialog')).not.toBeVisible();
      await expect(page).toHaveURL(/\/admin\/roles$/);
    });

    /**
     * WD-119 회귀 방지 — 이름·설명 저장 성공 뒤 폼 기준값을 바꾸지 않아 isDirty 가 남고,
     * 「역할 정보가 업데이트되었습니다」 직후 이동해도 미저장 경고가 떴다.
     */
    test('역할 정보를 저장한 직후 「목록으로 돌아가기」는 경고 없이 이동한다 (WD-119)', async ({
      authenticatedPage: page,
    }) => {
      await setupRoleDetailMocks(page, 3, false);
      const update = await mockApi(page, 'PUT', '/api/v1/roles/3', {}, { capture: true });
      await page.goto('/admin/roles/3');

      await page.getByLabel('설명').fill('저장한 뒤 경고가 없어야 하는 설명');
      await saveRoleInfo(page);
      expect((await update.waitForRequest()).payload).toMatchObject({
        description: '저장한 뒤 경고가 없어야 하는 설명',
      });
      await expect(page.getByText('역할 정보가 업데이트되었습니다.')).toBeVisible();
      // 저장한 값이 입력란에 그대로 남는다(기준값만 바뀜)
      await expect(page.getByLabel('설명')).toHaveValue('저장한 뒤 경고가 없어야 하는 설명');

      await setupRoleListMocks(page);
      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
      await expect(page).toHaveURL(/\/admin\/roles$/);
      await expect(page.getByRole('alertdialog')).toHaveCount(0);
    });

    test('역할 정보 저장이 실패하면 미저장 상태가 남아 경고가 뜬다 (WD-119)', async ({
      authenticatedPage: page,
    }) => {
      await setupRoleDetailMocks(page, 3, false);
      await mockApi(page, 'PUT', '/api/v1/roles/3', { message: '저장 실패' }, { status: 400 });
      await page.goto('/admin/roles/3');

      await page.getByLabel('설명').fill('실패한 저장');
      await saveRoleInfo(page);
      await expect(page.getByText('저장 실패')).toBeVisible();

      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(page).toHaveURL(/\/admin\/roles\/3$/);
    });

    test('역할 정보를 저장한 뒤 다시 고치면 경고가 다시 뜬다 (WD-119)', async ({
      authenticatedPage: page,
    }) => {
      await setupRoleDetailMocks(page, 3, false);
      await mockApi(page, 'PUT', '/api/v1/roles/3', {});
      await page.goto('/admin/roles/3');

      await page.getByLabel('설명').fill('첫 저장');
      await saveRoleInfo(page);
      await expect(page.getByText('역할 정보가 업데이트되었습니다.')).toBeVisible();

      await page.getByLabel('설명').fill('두 번째 미저장 수정');
      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(page).toHaveURL(/\/admin\/roles\/3$/);
    });

    test('dirty 상태가 아니면 목록으로 돌아가기 클릭 시 다이얼로그 없이 즉시 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupRoleDetailMocks(page, 3, false);
      await page.goto('/admin/roles/3');

      await setupRoleListMocks(page);
      await page.getByRole('button', { name: '목록으로 돌아가기' }).click();

      await expect(page.getByRole('alertdialog')).not.toBeVisible();
      await expect(page).toHaveURL(/\/admin\/roles$/);
    });
  });
});

/**
 * 역할 목록 — 띄어쓰기 없는 긴 이름·설명 (WD-559, WD-634)
 * - 100자 이름·설명이 칸을 늘려 삭제 칸이 화면 밖으로 밀렸다. 1280·1024 두 폭에서 표가 들어맞고 말줄임된다.
 */
test.describe('역할 목록 긴 이름·설명 말줄임 (WD-559)', () => {
  const LONG_NAME = 'R'.repeat(100);
  const LONG_DESC = '설'.repeat(100);

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  for (const width of TABLE_LAYOUT_WIDTHS) {
    test(`${width}px 에서 100자 이름·설명이 있어도 표가 넘치지 않고 삭제 버튼까지 보인다`, async ({
      authenticatedPage: page,
    }) => {
      await page.setViewportSize({ width, height: 800 });
      await setupRoleListMocks(page);
      await mockApi(page, 'GET', '/api/v1/roles', [
        createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
        createRole({ id: 3, name: LONG_NAME, description: LONG_DESC, isSystem: false }),
      ]);
      await page.goto('/admin/roles');

      const link = page.getByRole('link', { name: LONG_NAME });
      const row = page.getByRole('row').filter({ has: link });
      await expectTableFits(row.getByRole('button', { name: `${LONG_NAME} 역할 삭제` }));
      await expectLongValueTruncated(link, LONG_NAME);
      await expectLongValueTruncated(row.getByText(LONG_DESC), LONG_DESC);
    });

    test(`${width}px 에서 평범한 길이의 이름·설명은 잘리지 않는다`, async ({ authenticatedPage: page }) => {
      await page.setViewportSize({ width, height: 800 });
      await setupRoleListMocks(page);
      const NAME = 'DATA_PIPELINE_OPERATOR';
      const DESC = '파이프라인 실행과 트리거를 관리하는 운영 담당자 역할';
      await mockApi(page, 'GET', '/api/v1/roles', [createRole({ id: 3, name: NAME, description: DESC, isSystem: false })]);
      await page.goto('/admin/roles');

      const link = page.getByRole('link', { name: NAME });
      const row = page.getByRole('row').filter({ has: link });
      await expectTableFits(row.getByRole('cell').last());
      await expectValueNotTruncated(link);
      await expectValueNotTruncated(row.getByText(DESC, { exact: true }));
    });
  }
});
