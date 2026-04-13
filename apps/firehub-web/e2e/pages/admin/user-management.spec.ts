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

    // 행 개수 확인: 헤더 1행 + 데이터 3행 = 4행
    await expect(page.getByRole('row')).toHaveCount(4);

    // 첫 번째 데이터 행의 셀 내용 확인 — 아이디(username), 이메일이 올바르게 렌더링되는지 검증
    await expect(page.getByRole('cell', { name: 'user1', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'user1@example.com', exact: true })).toBeVisible();

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
    // 검색 요청 캡처를 위해 goto 이전에 mockApi capture 설정
    const capture = await mockApi(page, 'GET', '/api/v1/users', createPageResponse(
      Array.from({ length: 3 }, (_, i) => ({
        id: i + 1,
        name: `사용자 ${i + 1}`,
        username: `user${i + 1}`,
        email: `user${i + 1}@example.com`,
        active: true,
        roles: [],
      })),
    ), { capture: true });

    await page.goto('/admin/users');

    // 검색 입력 필드 확인
    const searchInput = page.getByPlaceholder('이름 또는 아이디로 검색...');
    await expect(searchInput).toBeVisible();

    // 검색어 입력 후 debounce 대기
    await searchInput.fill('사용자');
    await page.waitForTimeout(400);

    // 검색 파라미터가 API에 전달되는지 확인
    const req = capture.lastRequest();
    if (req) {
      expect(req.searchParams.get('search')).toBe('사용자');
    }

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

    // 상세 페이지에 사용자 정보가 표시되는지 확인 — 이름과 아이디가 렌더링되어야 한다
    await expect(page.getByRole('heading', { name: '사용자 상세' })).toBeVisible();
    // setupUserDetailMocks는 name='테스트 사용자', username='user1'을 반환한다
    // exact: true로 'user1@example.com'과의 strict 충돌 방지
    await expect(page.getByText('user1', { exact: true })).toBeVisible();
  });

  test('사용자 상세 페이지에서 기본 정보가 표시된다', async ({ authenticatedPage: page }) => {
    await setupUserDetailMocks(page, 1);
    await page.goto('/admin/users/1');

    // 상세 페이지 제목 및 기본 정보 카드 확인
    await expect(page.getByRole('heading', { name: '사용자 상세' })).toBeVisible();
    await expect(page.getByText('기본 정보')).toBeVisible();

    // 팩토리 데이터 검증 — setupUserDetailMocks(1): username='user1', email='user1@example.com'
    // exact: true로 'user1@example.com'과의 strict 충돌 방지
    await expect(page.getByText('user1', { exact: true })).toBeVisible();
    await expect(page.getByText('user1@example.com')).toBeVisible();
  });

  test('사용자 상세 페이지에서 역할 할당 섹션이 표시된다', async ({ authenticatedPage: page }) => {
    await setupUserDetailMocks(page, 1);
    await page.goto('/admin/users/1');

    // 역할 할당 카드 확인
    await expect(page.getByText('역할 할당')).toBeVisible();

    // 역할 저장 버튼 확인
    await expect(page.getByRole('button', { name: '역할 저장' })).toBeVisible();

    // setupUserDetailMocks는 USER, ADMIN 역할 2개를 반환한다 — 역할 체크박스로 확인
    // 역할 이름은 <Label htmlFor="role-N">에 포함되어 있어 체크박스 accessible name으로 검색한다
    await expect(page.getByRole('checkbox', { name: /USER/ })).toBeVisible();
    await expect(page.getByRole('checkbox', { name: /ADMIN/ })).toBeVisible();
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

    // 활성 상태 변경 PUT API 캡처 설정 — goto 이전에 등록해야 한다
    const capture = await mockApi(page, 'PUT', '/api/v1/users/1/active', {}, { capture: true });

    await page.goto('/admin/users/1');

    // 활성 상태 카드 확인
    await expect(page.getByText('활성 상태')).toBeVisible();

    // Switch 컴포넌트가 렌더링되는지 확인 (role="switch")
    const toggle = page.getByRole('switch');
    await expect(toggle).toBeVisible();

    // 스위치 클릭 → PUT /api/v1/users/1/active 호출 확인
    await toggle.click();
    const req = await capture.waitForRequest();
    expect(req).toBeTruthy();
  });

  test('사용자 활성 상태 토글 — PUT payload 검증', async ({ authenticatedPage: page }) => {
    await setupUserDetailMocks(page, 1);

    // PUT /api/v1/users/1/active 캡처 설정 — goto 이전에 등록해야 한다
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/users/1/active',
      { id: 1, isActive: false },
      { capture: true },
    );

    await page.goto('/admin/users/1');

    // 활성 상태 스위치 클릭 — 현재 active=true 이므로 비활성화로 전환
    const toggle = page.getByRole('switch');
    await expect(toggle).toBeVisible();
    await toggle.click();

    // PUT API payload 검증 — active: false 가 전달되어야 한다 (현재 isActive=true → 반전)
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ active: false });
  });

  test('사용자 역할 저장 — PUT payload 검증', async ({ authenticatedPage: page }) => {
    await setupUserDetailMocks(page, 1);

    // PUT /api/v1/users/1/roles 캡처 설정 — goto 이전에 등록해야 한다
    const saveCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/users/1/roles',
      { id: 1, roles: [] },
      { capture: true },
    );
    // 저장 후 사용자 재조회 모킹
    await setupUserDetailMocks(page, 1);

    await page.goto('/admin/users/1');

    // 역할 할당 섹션에서 USER 체크박스 토글 (현재 체크 상태 → 해제)
    const userRoleCheckbox = page.getByRole('checkbox', { name: /USER/ });
    await expect(userRoleCheckbox).toBeVisible();
    await userRoleCheckbox.click();

    // 역할 저장 버튼 클릭 → PUT /api/v1/users/1/roles 호출
    await page.getByRole('button', { name: '역할 저장' }).click();

    // API payload 검증 — roleIds 배열이 전달되어야 한다
    const req = await saveCapture.waitForRequest();
    expect(req.payload).toHaveProperty('roleIds');
    expect(Array.isArray((req.payload as { roleIds: number[] }).roleIds)).toBe(true);
  });
});
