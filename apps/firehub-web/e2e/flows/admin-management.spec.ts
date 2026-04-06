import {
  setupAdminAuth,
  setupRoleListMocks,
  setupUserDetailMocks,
  setupUserListMocks,
} from '../fixtures/admin.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * 관리자 도메인 플로우 E2E 테스트
 * - 사용자 목록 → 상세, 역할 목록 → 생성 등 주요 플로우를 검증한다.
 * - AdminRoute를 통과하기 위해 ADMIN 역할로 users/me를 오버라이드한다.
 */
test.describe('관리자 플로우', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // AdminRoute 통과를 위해 ADMIN 역할로 오버라이드
    await setupAdminAuth(page);
  });

  test('사용자 목록에서 행 클릭 시 사용자 상세 페이지로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    // 목록 페이지 API 모킹
    await setupUserListMocks(page, 3);
    // 상세 페이지 API 모킹 (클릭 후 이동할 페이지)
    await setupUserDetailMocks(page, 1);

    await page.goto('/admin/users');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '사용자 관리' })).toBeVisible();

    // 첫 번째 사용자 행 클릭
    await page.getByRole('cell', { name: '사용자 1' }).click();

    // 상세 페이지로 이동 확인
    await expect(page).toHaveURL(/\/admin\/users\/1/);

    // 상세 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '사용자 상세' })).toBeVisible();
  });

  test('역할 목록에서 역할 추가 버튼 클릭 시 생성 다이얼로그가 열린다', async ({
    authenticatedPage: page,
  }) => {
    // 역할 목록 API 모킹
    await setupRoleListMocks(page);
    // 역할 생성 API 모킹 (다이얼로그 제출 시 사용)
    await mockApi(page, 'POST', '/api/v1/roles', {
      id: 10,
      name: 'CUSTOM_ROLE',
      description: '커스텀 역할',
      isSystem: false,
    });

    await page.goto('/admin/roles');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '역할 관리' })).toBeVisible();

    // "역할 추가" 버튼 클릭
    await page.getByRole('button', { name: '역할 추가' }).click();

    // 생성 다이얼로그가 열리는지 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('새 역할 생성')).toBeVisible();

    // 역할 이름 입력
    await page.getByLabel('역할 이름').fill('CUSTOM_ROLE');

    // 생성 버튼 클릭
    await page.getByRole('button', { name: '생성' }).click();

    // 다이얼로그가 닫히는지 확인 (생성 성공 후 닫힘)
    await expect(page.getByRole('dialog')).not.toBeVisible();
  });
});
