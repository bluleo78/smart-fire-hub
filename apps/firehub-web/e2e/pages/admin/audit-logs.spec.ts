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

    // 첫 번째 데이터 행 내용 검증 — createAuditLogs(5): username='testuser', description='감사 로그 1'~'감사 로그 5'
    // 사용자명 'testuser'가 렌더링되는지 확인
    await expect(page.getByRole('cell', { name: 'testuser' }).first()).toBeVisible();
    // 첫 번째 로그의 description '감사 로그 1'이 렌더링되는지 확인
    await expect(page.getByText('감사 로그 1')).toBeVisible();
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
    // 검색 요청 캡처를 위해 goto 이전에 mockApi capture 설정
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([createAuditLog({ id: 1, username: 'testuser', description: '감사 로그 1' })]),
      { capture: true },
    );

    await page.goto('/admin/audit-logs');

    // 검색 필드에 텍스트 입력
    const searchInput = page.getByPlaceholder('사용자명 또는 설명으로 검색...');
    await expect(searchInput).toBeVisible();
    await searchInput.fill('testuser');

    // debounce 대기
    await page.waitForTimeout(400);

    // 검색 파라미터가 API에 전달되는지 확인
    const req = capture.lastRequest();
    if (req) {
      expect(req.searchParams.get('search')).toBe('testuser');
    }

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

    // 같은 행에 username 'testuser'도 표시되는지 확인
    await expect(page.getByRole('cell', { name: 'testuser' })).toBeVisible();
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

    // 행 개수 확인: 헤더 1행 + 데이터 20행 = 21행
    await expect(page.getByRole('row')).toHaveCount(21);

    // 페이지네이션 네비게이션이 렌더링되는지 확인
    await expect(page.getByRole('navigation')).toBeVisible();
  });
});
