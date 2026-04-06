import {
  setupAdminAuth,
  setupApiConnectionDetailMocks,
  setupApiConnectionListMocks,
} from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * API 연결 페이지 E2E 테스트
 * - 목록 렌더링, 생성 폼, 수정, 삭제를 검증한다.
 * - AdminRoute 통과를 위해 ADMIN 역할로 users/me를 오버라이드한다.
 */
test.describe('API 연결 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // AdminRoute 통과를 위해 ADMIN 역할로 오버라이드
    await setupAdminAuth(page);
  });

  test('API 연결 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: 'API 연결 관리' })).toBeVisible();

    // 테이블 헤더 확인
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '인증 유형' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '설명' })).toBeVisible();

    // 팩토리로 생성된 연결 목록 확인
    await expect(page.getByRole('cell', { name: '공공 데이터 API' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '내부 서비스 API' })).toBeVisible();

    // 행 개수 확인: 헤더 1행 + 데이터 2행 = 3행
    await expect(page.getByRole('row')).toHaveCount(3);

    // 인증 유형 배지 확인 — UI에서 API_KEY는 'API Key', BEARER는 'Bearer'로 렌더링된다
    const rows = page.getByRole('row');
    await expect(rows.filter({ hasText: '공공 데이터 API' }).getByText('API Key')).toBeVisible();
    await expect(rows.filter({ hasText: '내부 서비스 API' }).getByText('Bearer')).toBeVisible();
  });

  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/api-connections', []);
    await page.goto('/admin/api-connections');

    await expect(page.getByText('등록된 API 연결이 없습니다.')).toBeVisible();
  });

  test('새 연결 버튼 클릭 시 생성 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);

    // 연결 생성 POST API 캡처 설정 — goto 이전에 등록해야 한다
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/api-connections',
      { id: 10, name: '새 API 연결', authType: 'API_KEY' },
      { capture: true },
    );

    await page.goto('/admin/api-connections');

    // "새 연결" 버튼 클릭
    await page.getByRole('button', { name: '새 연결' }).click();

    // 생성 다이얼로그 열림 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('새 API 연결 생성')).toBeVisible();

    // 이름 필드 확인 및 입력 — placeholder로 접근
    const nameInput = page.getByPlaceholder('예: Make.com API');
    await expect(nameInput).toBeVisible();
    await nameInput.fill('새 API 연결');

    // API_KEY 인증 유형(기본값): 헤더 이름과 키 값도 필수 입력
    // placeholder='Authorization' → 헤더 이름 필드
    await page.getByPlaceholder('Authorization').fill('X-API-Key');
    // placeholder='API 키를 입력하세요' → 키 값 필드
    await page.getByPlaceholder('API 키를 입력하세요').fill('test-api-key-12345');

    // 생성 버튼 클릭 → POST /api/v1/api-connections 호출
    await page.getByRole('button', { name: '생성' }).click();

    // API payload 검증 — 입력한 이름과 authType이 전달되어야 한다
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '새 API 연결' });
    // authType은 기본값(API_KEY)이 전달된다
    expect((req.payload as Record<string, unknown>).authType).toBeTruthy();
  });

  test('생성 다이얼로그에서 API Key 인증 유형 선택 시 관련 필드가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');

    await page.getByRole('button', { name: '새 연결' }).click();

    // 기본값이 API_KEY이므로 헤더 이름 필드가 표시되는지 확인
    // (Label: "헤더 이름" 또는 "파라미터 이름" — 기본 placement는 'header')
    await expect(page.getByText('헤더 이름')).toBeVisible();
    await expect(page.getByText('키 값')).toBeVisible();
  });

  test('API 연결 행 클릭 시 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionListMocks(page);
    await setupApiConnectionDetailMocks(page, 1);

    await page.goto('/admin/api-connections');

    // 첫 번째 연결 이름 셀 클릭
    await page.getByRole('cell', { name: '공공 데이터 API' }).click();

    // 상세 페이지로 이동 확인
    await expect(page).toHaveURL(/\/admin\/api-connections\/1/);
  });

  test('API 연결 상세 페이지에서 기본 정보가 표시된다', async ({ authenticatedPage: page }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');

    // 상세 페이지 제목 확인
    await expect(page.getByRole('heading', { name: 'API 연결 상세' })).toBeVisible();

    // 기본 정보 카드 확인
    await expect(page.getByText('기본 정보')).toBeVisible();

    // 저장 버튼 확인
    await expect(page.getByRole('button', { name: '저장' })).toBeVisible();

    // setupApiConnectionDetailMocks(1)은 createApiConnections()[0]을 반환 — name='공공 데이터 API'
    // <Label>연결 이름</Label>에 htmlFor가 없으므로, 기본 정보 카드 첫 번째 textbox로 검증한다
    // 기본 정보 카드 내 첫 번째 Input이 연결 이름 필드이다
    const nameInput = page.getByRole('textbox').first();
    await expect(nameInput).toHaveValue('공공 데이터 API');
  });

  test('API 연결 상세 페이지에서 인증 정보 변경 버튼이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionDetailMocks(page, 1);
    await page.goto('/admin/api-connections/1');

    // 인증 설정 카드 확인
    await expect(page.getByText('인증 설정')).toBeVisible();

    // 인증 정보 변경 버튼 확인
    await expect(page.getByRole('button', { name: '인증 정보 변경' })).toBeVisible();
  });

  test('목록에서 삭제 버튼 클릭 시 확인 다이얼로그가 열린다', async ({
    authenticatedPage: page,
  }) => {
    await setupApiConnectionListMocks(page);

    // 첫 번째 연결(id=1) 삭제 DELETE API 캡처 설정
    const capture = await mockApi(page, 'DELETE', '/api/v1/api-connections/1', {}, { capture: true });

    await page.goto('/admin/api-connections');

    // 테이블 행의 삭제 트리거 버튼 클릭 — Outline + sm size 버튼
    // 목록 행마다 DeleteConfirmDialog trigger 버튼이 있으므로 첫 번째 행 버튼 클릭
    const rows = page.getByRole('row').filter({ has: page.getByRole('button') });
    const firstDataRow = rows.first();
    await firstDataRow.getByRole('button').click();

    // 삭제 확인 다이얼로그 열림 확인
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // 확인 버튼 클릭 → DELETE API 호출 검증
    const confirmButton = page.getByRole('alertdialog').getByRole('button', { name: /삭제|확인/ });
    await confirmButton.click();

    // DELETE API가 실제로 호출되었는지 확인
    const req = await capture.waitForRequest();
    expect(req).toBeTruthy();
  });
});
