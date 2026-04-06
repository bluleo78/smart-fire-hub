import {
  setupAdminAuth,
  setupRoleDetailMocks,
  setupRoleListMocks,
} from '../../fixtures/admin.fixture';
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

    // 역할 행 렌더링 확인
    await expect(page.getByRole('cell', { name: 'USER', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'ADMIN', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'EDITOR', exact: true })).toBeVisible();
  });

  test('역할 추가 버튼 클릭 시 생성 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    await setupRoleListMocks(page);
    await page.goto('/admin/roles');

    // "역할 추가" 버튼 클릭
    await page.getByRole('button', { name: '역할 추가' }).click();

    // 다이얼로그 열림 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('새 역할 생성')).toBeVisible();

    // 입력 필드 확인
    await expect(page.getByLabel('역할 이름')).toBeVisible();
    await expect(page.getByLabel('설명 (선택)')).toBeVisible();
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
    await page.goto('/admin/roles');

    // EDITOR 행의 삭제 버튼 (Outline 버튼) 클릭
    // DeleteConfirmDialog trigger 버튼: Outline + sm size
    const editorRow = page.getByRole('row', { name: /EDITOR/ });
    await editorRow.getByRole('button').click();

    // 삭제 확인 다이얼로그 열림 확인 (AlertDialog)
    await expect(page.getByRole('alertdialog')).toBeVisible();
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

  test('역할 상세 페이지에서 권한 할당 섹션이 카테고리별로 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupRoleDetailMocks(page, 3, false);
    await page.goto('/admin/roles/3');

    // 권한 할당 카드 확인
    await expect(page.getByText('권한 할당')).toBeVisible();

    // 카테고리 이름(대문자 h3 heading) 확인 — 팩토리에서 DATASET, PIPELINE, ADMIN 카테고리 포함
    // strict 모드 위반 방지를 위해 h3 태그(카테고리 섹션 헤더)로 명시적 선택
    await expect(page.getByRole('heading', { name: 'DATASET' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'PIPELINE' })).toBeVisible();

    // 권한 저장 버튼 확인
    await expect(page.getByRole('button', { name: '권한 저장' })).toBeVisible();
  });
});
