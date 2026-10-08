import { createAccount, createAccountPage } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

const KIM = createAccount({ id: 10, name: '김소방', username: 'kim@example.com', email: 'kim@example.com', active: true });
const LEE = createAccount({ id: 11, name: '이정지', username: 'lee@example.com', email: 'lee@example.com', active: false });
const OPS = createAccount({ id: 1, name: '김운영', username: 'ops@example.com', email: 'ops@example.com', operator: true });

const DEACTIVATE_COPY =
  '"김소방"(kim@example.com) 계정을 비활성화합니다. 모든 워크스페이스에서 로그인할 수 없고, 권한이 필요한 작업은 즉시 거부됩니다. 이미 열린 화면은 최대 30분간 일부 보일 수 있습니다. 재활성화하면 다시 로그인해 쓸 수 있습니다.';

test.describe('계정 화면 (#784)', () => {
  test('검색어 → GET /accounts?q= → 표에 이름·아이디·상태를 그린다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([KIM, LEE, OPS]), { capture: true });
    await page.goto('/accounts');

    await expect(page.getByRole('link', { name: '계정' })).toHaveAttribute('aria-current', 'page');
    await expect(page.getByRole('heading', { name: '계정' })).toBeVisible();
    await page.getByRole('textbox', { name: '계정 검색' }).fill('kim');

    // 첫 진입 요청(q 없음) 뒤에 검색 요청이 따로 나간다(WD-47) — 마지막 요청을 기다린다.
    await expect.poll(() => capture.requests.at(-1)?.searchParams.get('q')).toBe('kim');
    const table = page.getByRole('table', { name: '계정 목록' });
    const kimRow = table.getByRole('row').filter({ hasText: '김소방' });
    const kimUsernameCell = kimRow.getByRole('cell', { name: 'kim@example.com' }).first();
    await expect(kimUsernameCell).toBeVisible();
    await expect(kimRow.getByText('활성', { exact: true })).toBeVisible();
    // WD-7: 계정 식별자(아이디)는 일반 텍스트(mono 아님)
    await expect(kimUsernameCell).not.toHaveCSS('font-family', /mono/i);
    await expect(table.getByRole('row').filter({ hasText: '이정지' }).getByText('비활성', { exact: true })).toBeVisible();
    // WD-47: 20건 상한 안내는 사라졌다(페이지 목록).
    await expect(page.getByText(/최대 20건/)).toHaveCount(0);
  });

  test('아이디와 같은 이메일은 반복하지 않고, 다를 때만 보조 줄로 보인다 (WD-15)', async ({ authenticatedPage: page }) => {
    const DIFF = createAccount({ id: 12, name: '박연락', username: 'park@example.com', email: 'park.contact@example.org', active: true });
    // 대소문자만 다른 주소는 같은 메일함 — 반복으로 본다.
    const CASE = createAccount({ id: 13, name: '최대문', username: 'choi@example.com', email: 'CHOI@example.com', active: true });
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([KIM, DIFF, CASE]));
    await page.goto('/accounts');
    await page.getByRole('textbox', { name: '계정 검색' }).fill('ki');
    const table = page.getByRole('table', { name: '계정 목록' });
    await expect(table.getByRole('columnheader', { name: '이메일' })).toHaveCount(0);
    await expect(table.getByRole('row').filter({ hasText: '김소방' }).getByText('kim@example.com')).toHaveCount(1);
    await expect(table.getByRole('row').filter({ hasText: '박연락' }).getByText('이메일 park.contact@example.org')).toBeVisible();
    await expect(table.getByRole('row').filter({ hasText: '최대문' }).getByText(/이메일 /)).toHaveCount(0);
  });

  test('조회 실패는 표 안에 에러 문구를 그린다 (WD-16 안전망)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/accounts', {}, { status: 500 });
    await page.goto('/accounts');
    await page.getByRole('textbox', { name: '계정 검색' }).fill('kim');
    await expect(page.getByRole('table', { name: '계정 목록' }).getByText('데이터를 불러오는데 실패했습니다.')).toBeVisible();
  });

  test('첫 진입에 검색어 없이 전체 목록을 부르고 소속·생성일 열을 그린다 (WD-47)', async ({ authenticatedPage: page }) => {
    const LONELY = createAccount({ id: 12, name: '최미소', username: 'choi@example.com', email: 'choi@example.com', membershipCount: 0 });
    const MULTI = createAccount({ id: 13, name: '박다중', username: 'park@example.com', email: 'park@example.com', membershipCount: 3 });
    const capture = await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([KIM, LONELY, MULTI]), { capture: true });
    await page.goto('/accounts');

    const request = await capture.waitForRequest();
    expect(request.searchParams.has('q')).toBe(false);
    expect(request.searchParams.get('page')).toBe('0');
    expect(request.searchParams.get('size')).toBe('20');

    const table = page.getByRole('table', { name: '계정 목록' });
    await expect(table.getByRole('columnheader')).toHaveText(['이름', '아이디', '소속', '상태', '생성일', '작업']);
    await expect(table.getByRole('row').filter({ hasText: '최미소' }).getByText('미소속', { exact: true })).toBeVisible();
    await expect(table.getByRole('row').filter({ hasText: '박다중' }).getByText('3개', { exact: true })).toBeVisible();
    await expect(table.getByRole('row').filter({ hasText: '김소방' }).getByText('1개', { exact: true })).toBeVisible();
    // 생성일은 날짜만(로컬). 2026-03-04T00:21:14Z 는 어느 TZ 에서도 03-03 또는 03-04 다.
    await expect(table.getByRole('row').filter({ hasText: '김소방' }).getByText(/^2026-03-0[34]$/)).toBeVisible();
    await expect(page.getByText(/2자 이상/)).toHaveCount(0);
  });

  test('1자 검색어도 필터로 보내고, 검색어가 바뀌면 첫 페이지로 돌아간다 (WD-47)', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(
      page,
      'GET',
      '/api/platform/accounts',
      createAccountPage([KIM], { totalElements: 45, totalPages: 3 }),
      { capture: true },
    );
    await page.goto('/accounts');
    await expect(page.getByText('총 45건')).toBeVisible();

    // 2쪽으로 이동
    await page.getByRole('button', { name: '2 페이지' }).click();
    await expect.poll(() => capture.requests.at(-1)?.searchParams.get('page')).toBe('1');

    // 검색어 입력 → q=k, page=0 (이전 쪽 번호로 요청하지 않는다)
    await page.getByRole('textbox', { name: '계정 검색' }).fill('k');
    await expect.poll(() => capture.requests.at(-1)?.searchParams.get('q')).toBe('k');
    expect(capture.requests.at(-1)?.searchParams.get('page')).toBe('0');
    expect(capture.requests.some((r) => r.searchParams.get('q') === 'k' && r.searchParams.get('page') === '1')).toBe(false);
  });

  test('빈 결과: 검색 중이면 검색어와 초기화, 전체가 비면 "등록된 계정이 없습니다." (WD-47)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([]));
    await page.goto('/accounts');
    const table = page.getByRole('table', { name: '계정 목록' });
    await expect(table.getByText('등록된 계정이 없습니다.')).toBeVisible();

    await page.getByRole('textbox', { name: '계정 검색' }).fill('nobody');
    await expect(table.getByText("'nobody'")).toBeVisible();
    await expect(table.getByText('에 대한 결과가 없습니다.')).toBeVisible();
    await table.getByRole('button', { name: '검색 초기화' }).click();
    await expect(page.getByRole('textbox', { name: '계정 검색' })).toHaveValue('');
    await expect(table.getByText('등록된 계정이 없습니다.')).toBeVisible();
  });

  test('비활성화: 확인 문구(D-2) → POST deactivate → 토스트 → 상태 갱신', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([KIM]));
    const capture = await mockApi(page, 'POST', '/api/platform/accounts/10/deactivate', null, { status: 204, capture: true });
    await page.goto('/accounts');
    await page.getByRole('textbox', { name: '계정 검색' }).fill('kim');
    await page.getByRole('button', { name: '김소방 계정 비활성화' }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByRole('heading', { name: '계정 비활성화' })).toBeVisible();
    await expect(dialog.getByText(DEACTIVATE_COPY)).toBeVisible();
    // 다음 GET 은 비활성으로 — 나중에 등록한 route 가 우선한다.
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([{ ...KIM, active: false }]));
    await dialog.getByRole('button', { name: '비활성화' }).click();

    await capture.waitForRequest();
    await expect(page.getByText('계정을 비활성화했습니다.')).toBeVisible();
    await expect(page.getByRole('row').filter({ hasText: '김소방' }).getByText('비활성', { exact: true })).toBeVisible();
  });

  test('확인 다이얼로그에서 취소하면 API 를 부르지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([KIM]));
    const capture = await mockApi(page, 'POST', '/api/platform/accounts/10/deactivate', null, { status: 204, capture: true });
    await page.goto('/accounts');
    await page.getByRole('textbox', { name: '계정 검색' }).fill('kim');
    await page.getByRole('button', { name: '김소방 계정 비활성화' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    expect(capture.requests).toHaveLength(0);
  });

  test('재활성화는 확인 없이 즉시 POST activate (D-2)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([LEE]));
    const capture = await mockApi(page, 'POST', '/api/platform/accounts/11/activate', null, { status: 204, capture: true });
    await page.goto('/accounts');
    await page.getByRole('textbox', { name: '계정 검색' }).fill('lee');
    await page.getByRole('button', { name: '이정지 계정 재활성화' }).click();
    await capture.waitForRequest();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.getByText('계정을 재활성화했습니다.')).toBeVisible();
  });

  test('운영자 행은 배지가 붙고 비활성화 버튼이 막혀 있다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([OPS]));
    await page.goto('/accounts');
    await page.getByRole('textbox', { name: '계정 검색' }).fill('ops');
    const row = page.getByRole('row').filter({ hasText: '김운영' });
    await expect(row.getByText('운영자', { exact: true })).toBeVisible();
    const button = row.getByRole('button', { name: '김운영 계정 비활성화' });
    await expect(button).toBeDisabled();
    await expect(button).toHaveAttribute('title', '운영자 계정은 비활성화할 수 없습니다');
  });

  test('409 는 서버 메시지를 토스트로 보여 준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([KIM]));
    await mockApi(page, 'POST', '/api/platform/accounts/10/deactivate',
      { status: 409, error: 'Conflict', message: '운영자 계정은 비활성화할 수 없습니다' }, { status: 409 });
    await page.goto('/accounts');
    await page.getByRole('textbox', { name: '계정 검색' }).fill('kim');
    await page.getByRole('button', { name: '김소방 계정 비활성화' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '비활성화' }).click();
    await expect(page.getByText('운영자 계정은 비활성화할 수 없습니다')).toBeVisible();
  });

  test('변경 권한(platform:tenant:suspend)이 없으면 작업 열이 없다', async ({ page }) => {
    await loginAs(page, ['platform:tenant:read', 'platform:member:read']);
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([KIM]));
    await page.goto('/accounts');
    await page.getByRole('textbox', { name: '계정 검색' }).fill('kim');
    await expect(page.getByRole('row').filter({ hasText: '김소방' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '작업' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /계정 비활성화/ })).toHaveCount(0);
  });

  test('조회 권한(platform:member:read)이 없으면 메뉴가 없고 직접 진입은 권한 안내', async ({ page }) => {
    await loginAs(page, ['platform:tenant:read']);
    await page.goto('/accounts');
    await expect(page.getByRole('link', { name: '계정' })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: '계정' })).toBeVisible(); // ProtectedRoute deniedTitle
    await expect(page.getByText('이 작업을 수행할 권한이 없습니다.')).toBeVisible(); // PermissionDeniedBanner
  });
});
