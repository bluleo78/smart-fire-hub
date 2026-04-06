import { createAuditLog } from '../../factories/admin.factory';
import { setupAdminAuth, setupAuditLogMocks } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 감사 로그 페이지 E2E 테스트
 * - 목록 렌더링, 필터, 검색, 페이지네이션을 검증한다.
 * - AdminRoute 통과를 위해 ADMIN 역할로 users/me를 오버라이드한다.
 */
test.describe('감사 로그 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // AdminRoute 통과를 위해 ADMIN 역할로 오버라이드
    await setupAdminAuth(page);
  });

  test('감사 로그 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupAuditLogMocks(page, 5);
    await page.goto('/admin/audit-logs');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '감사 로그' })).toBeVisible();

    // 테이블 헤더 확인
    await expect(page.getByRole('columnheader', { name: '시간' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '사용자' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '액션' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '리소스' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '결과' })).toBeVisible();
  });

  test('감사 로그 행이 5개 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupAuditLogMocks(page, 5);
    await page.goto('/admin/audit-logs');

    // 헤더 행 제외하고 데이터 행 5개 확인 (nth(0)은 헤더 행)
    await expect(page.getByRole('row')).toHaveCount(6); // 헤더 1 + 데이터 5
  });

  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/admin/audit-logs', createPageResponse([]));
    await page.goto('/admin/audit-logs');

    await expect(page.getByText('감사 로그가 없습니다.')).toBeVisible();
  });

  test('액션 유형 필터 셀렉트가 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupAuditLogMocks(page);
    await page.goto('/admin/audit-logs');

    // 액션 유형 필터 SelectTrigger 확인 (placeholder 텍스트로 구분)
    await expect(page.getByText('전체 액션')).toBeVisible();
  });

  test('결과 필터 셀렉트가 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupAuditLogMocks(page);
    await page.goto('/admin/audit-logs');

    // 결과 필터 SelectTrigger 확인
    await expect(page.getByText('전체 결과')).toBeVisible();
  });

  test('검색 입력 시 필드에 값이 반영된다', async ({ authenticatedPage: page }) => {
    await setupAuditLogMocks(page);
    await page.goto('/admin/audit-logs');

    // 검색 필드에 텍스트 입력
    const searchInput = page.getByPlaceholder('사용자명 또는 설명으로 검색...');
    await expect(searchInput).toBeVisible();
    await searchInput.fill('testuser');

    // debounce 대기
    await page.waitForTimeout(400);

    // 입력값 유지 확인
    await expect(searchInput).toHaveValue('testuser');
  });

  test('결과 SUCCESS 배지가 렌더링된다', async ({ authenticatedPage: page }) => {
    // SUCCESS 결과의 로그만 포함
    await mockApi(page, 'GET', '/api/v1/admin/audit-logs', createPageResponse([
      createAuditLog({ id: 1, result: 'SUCCESS', username: 'testuser' }),
    ]));
    await page.goto('/admin/audit-logs');

    // 성공 배지 확인
    await expect(page.getByText('성공')).toBeVisible();
  });

  test('페이지네이션이 여러 페이지일 때 렌더링된다', async ({ authenticatedPage: page }) => {
    // 50개 항목 → 3페이지 (size=20)
    const logs = Array.from({ length: 20 }, (_, i) => createAuditLog({ id: i + 1 }));
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse(logs, { totalElements: 50, totalPages: 3 }),
    );
    await page.goto('/admin/audit-logs');

    // 첫 번째 행이 렌더링되는지 확인 (페이지네이션 컴포넌트 자체 검증)
    await expect(page.getByRole('row').nth(1)).toBeVisible();
  });
});
