import { createPermission, createRoleDetail } from '../../factories/admin.factory';
import {
  setupAdminAuth,
  setupRoleDetailMocks,
  setupRoleListMocks,
} from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 역할 관리 페이지 E2E 테스트
 * - 역할 목록, 생성 다이얼로그, 시스템 역할 보호, 커스텀 역할 삭제를 검증한다.
 * - AdminRoute 통과를 위해 ADMIN 역할로 users/me를 오버라이드한다.
 */
/** 역할 정보 카드의 「저장」 — 권한 저장·열람 등급 저장 버튼과 섞이지 않게 카드 안으로 좁힌다 (WD-119). */
async function saveRoleInfo(page: import('@playwright/test').Page) {
  await page
    .locator('[data-slot="card"]')
    .filter({ hasText: '역할 정보' })
    .getByRole('button', { name: '저장', exact: true })
    .click();
}

test.describe('역할 관리 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // AdminRoute 통과를 위해 ADMIN 역할로 오버라이드
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
    await page.getByRole('button', { name: '역할 추가' }).click();

    // 다이얼로그 열림 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('새 역할 생성')).toBeVisible();

    // 입력 필드 확인 및 값 입력
    const nameInput = page.getByLabel('역할 이름');
    const descInput = page.getByLabel('설명 (선택)');
    await expect(nameInput).toBeVisible();
    await expect(descInput).toBeVisible();

    await nameInput.fill('NEW_ROLE');
    await descInput.fill('새 역할 설명');

    // 생성 버튼 클릭 → POST /api/v1/roles 호출
    await page.getByRole('button', { name: '생성' }).click();

    // API payload 검증 — 입력한 이름이 전달되어야 한다
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ name: 'NEW_ROLE' });
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

  test('역할 상세 페이지에서 권한 할당 섹션이 카테고리별로 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupRoleDetailMocks(page, 3, false);
    await page.goto('/admin/roles/3');

    // 권한 할당 카드 확인
    await expect(page.getByText('권한 할당')).toBeVisible();

    // 카테고리 이름(대문자 h3 heading) 확인 — 팩토리에서 DATASET, PIPELINE, ADMIN 카테고리 포함
    // strict 모드 위반 방지를 위해 h3 태그(카테고리 섹션 헤더)로 명시적 선택
    await expect(page.getByRole('heading', { name: 'DATASET' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'PIPELINE' })).toBeVisible();

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

  test('역할 정보 로드 실패 시 에러 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    // 역할 API 500 에러 → fetchData catch 분기 (lines 61-62)
    await mockApi(page, 'GET', '/api/v1/roles/1', { message: '서버 오류' }, { status: 500 });
    await mockApi(page, 'GET', '/api/v1/permissions', []);
    // 에러 후 /admin/roles 로 이동하므로 역할 목록도 모킹
    await setupRoleListMocks(page);

    await page.goto('/admin/roles/1');

    // 에러 토스트 확인 (line 61) — Sonner는 동일 메시지 토스트를 중복 렌더링할 수 있으므로 first() 사용
    await expect(page.getByText('역할 정보를 불러오는데 실패했습니다.').first()).toBeVisible({ timeout: 5000 });
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
    await page.getByRole('button', { name: '역할 추가' }).click();

    const nameInput = page.getByLabel('역할 이름');
    await nameInput.fill('ADMIN');
    await page.getByRole('button', { name: '생성' }).click();

    // 서버 에러 메시지가 표시된다
    const errorMessage = page.getByText('이미 존재하는 역할 이름입니다: ADMIN');
    await expect(errorMessage).toBeVisible();

    // 제출하지 않은 채 이름만 다른 값으로 고치면 stale 서버 에러가 즉시 사라져야 한다
    await nameInput.fill('EXPLORER_TEST_ROLE_1');
    await expect(errorMessage).not.toBeVisible();
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
          createPermission({ id: 3, code: 'PIPELINE_READ', category: 'PIPELINE' }),
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
      expect((await saveCapture.waitForRequest()).payload).toEqual({ permissionIds: [1, 2, 3] });
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
