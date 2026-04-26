import { createAuditLog } from '../../factories/admin.factory';
import { setupAdminAuth, setupAuditLogMocks } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 감사 로그 페이지 E2E 테스트
 * - 목록 렌더링, 필터, 검색, 페이지네이션을 검증한다.
 * - 날짜 범위 필터(startDate/endDate) 파라미터 전달을 검증한다.
 * - 행 클릭 시 상세 보기 다이얼로그에서 description 전문을 표시하는지 검증한다.
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

    // 페이지네이션 네비게이션이 렌더링되는지 확인 (사이드바 nav 와 구분하기 위해 aria-label 지정)
    await expect(page.getByRole('navigation', { name: '페이지네이션' })).toBeVisible();
  });

  /**
   * 날짜 범위 필터 테스트
   * - 시작일/종료일 입력 필드가 렌더링되는지 확인
   * - 날짜 입력 시 startDate/endDate 파라미터가 API에 전달되는지 검증
   */
  test('날짜 범위 필터 입력 필드가 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupAuditLogMocks(page);
    await page.goto('/admin/audit-logs');

    // 시작/종료 날짜 입력 필드 존재 확인
    await expect(page.getByLabel('시작 날짜')).toBeVisible();
    await expect(page.getByLabel('종료 날짜')).toBeVisible();
  });

  test('시작 날짜 입력 시 startDate 파라미터가 API에 전달된다', async ({ authenticatedPage: page }) => {
    // 날짜 필터 요청 캡처
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([createAuditLog({ id: 1 })]),
      { capture: true },
    );
    await page.goto('/admin/audit-logs');

    // 초기 요청 소비 후 날짜 입력
    await page.waitForTimeout(200);

    const startInput = page.getByLabel('시작 날짜');
    await startInput.fill('2026-04-01');

    // 날짜 변경 후 API 재호출 대기
    await page.waitForTimeout(300);

    // API에 startDate 파라미터가 전달됐는지 검증
    const req = capture.lastRequest();
    if (req) {
      const startDate = req.searchParams.get('startDate');
      expect(startDate).toContain('2026-04-01');
    }
  });

  test('종료 날짜 입력 시 endDate 파라미터가 API에 전달된다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([createAuditLog({ id: 1 })]),
      { capture: true },
    );
    await page.goto('/admin/audit-logs');

    await page.waitForTimeout(200);

    const endInput = page.getByLabel('종료 날짜');
    await endInput.fill('2026-04-30');

    await page.waitForTimeout(300);

    const req = capture.lastRequest();
    if (req) {
      const endDate = req.searchParams.get('endDate');
      expect(endDate).toContain('2026-04-30');
    }
  });

  /**
   * 행 클릭 상세 보기 테스트
   * - 테이블 행 클릭 시 다이얼로그가 열리는지 확인
   * - 다이얼로그에 description 전문이 표시되는지 검증 (truncate 해소)
   * - IP 주소, 사용자명 등 추가 필드도 표시되는지 확인
   */
  test('행 클릭 시 상세 보기 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    const longDescription = '파이프라인 실행: 데이터 수집 → 전처리 → 분석 → 저장 단계가 모두 완료되었습니다. 처리된 레코드 수: 100,000건. 소요 시간: 3분 45초.';
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([
        createAuditLog({
          id: 1,
          username: 'admin',
          description: longDescription,
          ipAddress: '192.168.1.100',
          result: 'SUCCESS',
        }),
      ]),
    );
    await page.goto('/admin/audit-logs');

    // 데이터 행이 렌더링될 때까지 대기
    await expect(page.getByRole('cell', { name: 'admin' })).toBeVisible();

    // 행 클릭
    const row = page.getByRole('row').nth(1); // 헤더 제외 첫 번째 데이터 행
    await row.click();

    // 다이얼로그가 열리는지 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('감사 로그 상세')).toBeVisible();
  });

  test('상세 보기 다이얼로그에 description 전문이 표시된다', async ({ authenticatedPage: page }) => {
    // truncate되어 잘리는 긴 description
    const longDescription = '파이프라인 실행: 데이터 수집 → 전처리 → 분석 → 저장 단계가 모두 완료되었습니다. 처리된 레코드 수: 100,000건. 소요 시간: 3분 45초.';
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([
        createAuditLog({ id: 1, description: longDescription, username: 'admin' }),
      ]),
    );
    await page.goto('/admin/audit-logs');

    await expect(page.getByRole('cell', { name: 'admin' })).toBeVisible();

    // 행 클릭 → 다이얼로그 열기
    await page.getByRole('row').nth(1).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 다이얼로그 안에서 description 전문이 표시되는지 확인 (dialog 범위로 제한)
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText(longDescription)).toBeVisible();
  });

  test('상세 보기 다이얼로그에 IP 주소가 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([
        createAuditLog({ id: 1, ipAddress: '192.168.1.100', username: 'admin' }),
      ]),
    );
    await page.goto('/admin/audit-logs');

    await expect(page.getByRole('cell', { name: 'admin' })).toBeVisible();
    await page.getByRole('row').nth(1).click();

    // IP 주소가 다이얼로그 안에 표시되는지 검증 (dialog 범위로 제한)
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('192.168.1.100')).toBeVisible();
  });

  /**
   * 이슈 #71: metadata(JSON payload) 필드 표시 검증
   * - metadata 가 있는 경우 JSON.stringify(..., null, 2) 형태로 들여쓰기 렌더되는지 확인
   */
  test('상세 보기 다이얼로그에 metadata JSON 이 표시된다', async ({ authenticatedPage: page }) => {
    const metadata = { before: { name: 'old' }, after: { name: 'new' }, changedFields: ['name'] };
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([
        createAuditLog({ id: 1, username: 'admin', metadata }),
      ]),
    );
    await page.goto('/admin/audit-logs');

    await expect(page.getByRole('cell', { name: 'admin' })).toBeVisible();
    await page.getByRole('row').nth(1).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    // Metadata 섹션 라벨이 보이는지
    await expect(dialog.getByText('Metadata')).toBeVisible();
    // JSON.stringify(metadata, null, 2) 결과 문자열이 그대로 렌더되는지 검증
    const expectedJson = JSON.stringify(metadata, null, 2);
    const pre = dialog.locator('pre');
    await expect(pre).toBeVisible();
    await expect(pre).toHaveText(expectedJson);
  });

  /**
   * 이슈 #71: metadata 가 null/empty 인 경우 섹션 자체가 숨겨지는지 검증
   */
  test('metadata 가 null 인 경우 Metadata 섹션이 표시되지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([
        createAuditLog({ id: 1, username: 'admin', metadata: null }),
      ]),
    );
    await page.goto('/admin/audit-logs');

    await expect(page.getByRole('cell', { name: 'admin' })).toBeVisible();
    await page.getByRole('row').nth(1).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    // Metadata 라벨이 표시되지 않아야 함 (조건부 렌더링)
    await expect(dialog.getByText('Metadata')).toHaveCount(0);
  });

  /**
   * 이슈 #71: userAgent 필드 표시 검증
   */
  test('상세 보기 다이얼로그에 User Agent 가 표시된다', async ({ authenticatedPage: page }) => {
    const ua = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/130.0.0.0 Safari/537.36';
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([
        createAuditLog({ id: 1, username: 'admin', userAgent: ua }),
      ]),
    );
    await page.goto('/admin/audit-logs');

    await expect(page.getByRole('cell', { name: 'admin' })).toBeVisible();
    await page.getByRole('row').nth(1).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('User Agent')).toBeVisible();
    await expect(dialog.getByText(ua)).toBeVisible();
  });

  /**
   * 이슈 #71: userAgent 가 null 인 경우 섹션 자체가 숨겨지는지 검증
   */
  test('userAgent 가 null 인 경우 User Agent 섹션이 표시되지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([
        createAuditLog({ id: 1, username: 'admin', userAgent: null }),
      ]),
    );
    await page.goto('/admin/audit-logs');

    await expect(page.getByRole('cell', { name: 'admin' })).toBeVisible();
    await page.getByRole('row').nth(1).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('User Agent')).toHaveCount(0);
  });

  test('다이얼로그 닫기 버튼 클릭 시 다이얼로그가 닫힌다', async ({ authenticatedPage: page }) => {
    await setupAuditLogMocks(page, 1);
    await page.goto('/admin/audit-logs');

    await expect(page.getByRole('cell', { name: 'testuser' })).toBeVisible();
    await page.getByRole('row').nth(1).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // X 버튼 클릭
    await page.getByRole('dialog').getByRole('button').click();

    // 다이얼로그가 닫히는지 확인
    await expect(page.getByRole('dialog')).not.toBeVisible();
  });

  /**
   * 닫기 버튼 sr-only 텍스트 한국어 검증
   * - DialogContent의 X 버튼 sr-only 텍스트가 '닫기'(한국어)인지 확인 (이슈 #53)
   * - 스크린리더 사용자에게 한국어 앱과 일관된 언어 제공
   */
  test('상세 보기 다이얼로그 닫기 버튼 sr-only 텍스트가 한국어 "닫기"이다', async ({ authenticatedPage: page }) => {
    await setupAuditLogMocks(page, 1);
    await page.goto('/admin/audit-logs');

    await expect(page.getByRole('cell', { name: 'testuser' })).toBeVisible();
    await page.getByRole('row').nth(1).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // sr-only span 텍스트가 '닫기'인지 검증 — 영문 'Close'가 아닌지 확인 (이슈 #53)
    const closeButton = page.getByRole('dialog').getByRole('button');
    await expect(closeButton).toBeVisible();
    const srOnlyText = await closeButton.locator('span.sr-only').textContent();
    expect(srOnlyText).toBe('닫기');
  });
});
