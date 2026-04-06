import {
  setupAdminAuth,
  setupUserDetailMocks,
  setupUserListMocks,
} from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 사용자 관리 페이지 E2E 테스트
 * - 사용자 목록 및 상세 페이지 UI를 검증한다.
 * - AdminRoute 통과를 위해 ADMIN 역할로 users/me를 오버라이드한다.
 */
test.describe('사용자 관리 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // AdminRoute 통과를 위해 ADMIN 역할로 오버라이드
    await setupAdminAuth(page);
  });

  test('사용자 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 3명의 사용자 목록 모킹
    await setupUserListMocks(page, 3);
    await page.goto('/admin/users');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '사용자 관리' })).toBeVisible();

    // 테이블 헤더 컬럼 확인
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '아이디' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '이메일' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '상태' })).toBeVisible();

    // 사용자 행이 렌더링되는지 확인
    await expect(page.getByRole('cell', { name: '사용자 1', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: '사용자 3', exact: true })).toBeVisible();
  });

  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 빈 목록 응답 모킹
    await mockApi(page, 'GET', '/api/v1/users', createPageResponse([]));
    await page.goto('/admin/users');

    // 빈 상태 메시지 확인
    await expect(page.getByText('사용자가 없습니다.')).toBeVisible();
  });

  test('검색 입력 필드가 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupUserListMocks(page);
    await page.goto('/admin/users');

    // 검색 입력 필드 확인
    const searchInput = page.getByPlaceholder('이름 또는 아이디로 검색...');
    await expect(searchInput).toBeVisible();

    // 검색어 입력
    await searchInput.fill('사용자');
    await page.waitForTimeout(400);

    // 검색 필드에 입력값 유지 확인
    await expect(searchInput).toHaveValue('사용자');
  });

  test('사용자 행 클릭 시 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupUserListMocks(page, 3);
    // 상세 페이지 API 미리 모킹
    await setupUserDetailMocks(page, 1);

    await page.goto('/admin/users');

    // 첫 번째 사용자 행 클릭
    await page.getByRole('cell', { name: '사용자 1', exact: true }).click();

    // 상세 페이지로 이동 확인
    await expect(page).toHaveURL(/\/admin\/users\/1/);
  });

  test('사용자 상세 페이지에서 기본 정보가 표시된다', async ({ authenticatedPage: page }) => {
    await setupUserDetailMocks(page, 1);
    await page.goto('/admin/users/1');

    // 상세 페이지 제목 및 기본 정보 카드 확인
    await expect(page.getByRole('heading', { name: '사용자 상세' })).toBeVisible();
    await expect(page.getByText('기본 정보')).toBeVisible();
  });

  test('사용자 상세 페이지에서 역할 할당 섹션이 표시된다', async ({ authenticatedPage: page }) => {
    await setupUserDetailMocks(page, 1);
    await page.goto('/admin/users/1');

    // 역할 할당 카드 확인
    await expect(page.getByText('역할 할당')).toBeVisible();

    // 역할 저장 버튼 확인
    await expect(page.getByRole('button', { name: '역할 저장' })).toBeVisible();
  });

  test('사용자 상세 페이지에서 뒤로 가기 버튼이 동작한다', async ({ authenticatedPage: page }) => {
    await setupUserDetailMocks(page, 1);
    // 뒤로 이동 시 목록 API도 모킹
    await setupUserListMocks(page, 3);

    await page.goto('/admin/users/1');

    // 상세 페이지 로드 완료 대기
    await expect(page.getByRole('heading', { name: '사용자 상세' })).toBeVisible();

    // 뒤로 가기 버튼 클릭 — 제목 행의 첫 번째 버튼 (ArrowLeft ghost icon 버튼)
    // 제목("사용자 상세")과 같은 flex 행에 있는 버튼을 찾는다
    const headingRow = page.locator('.flex.items-center.gap-4').first();
    await headingRow.getByRole('button').click();

    // 목록 페이지로 이동 확인
    await expect(page).toHaveURL('/admin/users');
  });

  test('활성 상태 토글 스위치가 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupUserDetailMocks(page, 1);
    await page.goto('/admin/users/1');

    // 활성 상태 카드 확인
    await expect(page.getByText('활성 상태')).toBeVisible();

    // Switch 컴포넌트가 렌더링되는지 확인 (role="switch")
    await expect(page.getByRole('switch')).toBeVisible();
  });
});
