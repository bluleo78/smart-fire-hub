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
test.describe('역할 관리 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // AdminRoute 통과를 위해 ADMIN 역할로 오버라이드
    await setupAdminAuth(page);
  });

  test('역할 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
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
    const trashButtons = page.locator('button').filter({ hasText: '' }).locator('svg.lucide-trash-2');
    await expect(trashButtons).toHaveCount(1);
  });

  test('커스텀 역할 삭제 버튼 클릭 시 확인 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);

    // EDITOR 역할(id=3) 삭제 DELETE API 캡처 설정
    const capture = await mockApi(page, 'DELETE', '/api/v1/roles/3', {}, { capture: true });

    await page.goto('/admin/roles');

    // EDITOR 행의 삭제 버튼 (Outline 버튼) 클릭
    // DeleteConfirmDialog trigger 버튼: Outline + sm size
    const editorRow = page.getByRole('row', { name: /EDITOR/ });
    await editorRow.getByRole('button').click();

    // 삭제 확인 다이얼로그 열림 확인 (AlertDialog)
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // 확인 버튼 클릭 → DELETE API 호출 검증
    const confirmButton = page.getByRole('alertdialog').getByRole('button', { name: /삭제|확인/ });
    await confirmButton.click();

    // DELETE API가 실제로 호출되었는지 확인
    const req = await capture.waitForRequest();
    expect(req).toBeTruthy();
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
});
