import { createTokenResponse, createUser } from '../../factories/auth.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, MOCK_USER_DETAIL, test } from '../../fixtures/auth.fixture';

/**
 * 첫 로그인 비밀번호 변경 강제(WD-2) — /change-password.
 * 서버가 준 mustChangePassword(로그인·refresh 응답)와 403 PASSWORD_CHANGE_REQUIRED 를 웹이 어떻게
 * 따르는지 입력 → API → 화면 전체를 검증한다.
 */
const PWC_TOKEN = createTokenResponse({ mustChangePassword: true });
const PWC_ME = createUser({ mustChangePassword: true });

// 필드 라벨 — FormField required 가 라벨 끝에 `*` 를 붙이므로(디자인 시스템 09 §J) 접근성 이름은
// "새 비밀번호*" 다. exact 문자열 대신 고정 정규식으로 잡아 "새 비밀번호"·"새 비밀번호 확인"을 구분하고,
// PasswordInput 의 "비밀번호 보기" 토글과도 충돌하지 않게 한다.
const CURRENT_PASSWORD = /^현재\(임시\) 비밀번호\*?$/;
const NEW_PASSWORD = /^새 비밀번호\*?$/;
const CONFIRM_PASSWORD = /^새 비밀번호 확인\*?$/;

test.describe('비밀번호 변경 강제', () => {
  test('임시 비밀번호로 로그인하면 변경 화면으로 간다', { tag: '@smoke' }, async ({ authMockedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/login', PWC_TOKEN);
    await mockApi(page, 'GET', '/api/v1/auth/me', PWC_ME);

    await page.goto('/login');
    await page.getByLabel('아이디 (이메일)').fill('test@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('TempPass1x'); // '비밀번호 보기' 토글과 구분
    await page.getByRole('button', { name: '로그인' }).click();

    await expect(page).toHaveURL(/\/change-password$/);
    await expect(page.getByRole('heading', { level: 1, name: '비밀번호 변경' })).toBeVisible();
    await expect(page.getByText('관리자가 발급한 임시 비밀번호입니다. 계속하려면 새 비밀번호를 설정하세요.')).toBeVisible();
  });

  test('변경 전에는 다른 주소로 들어가도 변경 화면으로 돌아온다', async ({ authMockedPage: page }) => {
    await page.addInitScript(() => localStorage.setItem('hasSession', 'true'));
    await mockApi(page, 'POST', '/api/v1/auth/refresh', PWC_TOKEN);
    await mockApi(page, 'GET', '/api/v1/auth/me', PWC_ME);

    await page.goto('/data/datasets');

    await expect(page).toHaveURL(/\/change-password$/);
  });

  test('API 가 403 PASSWORD_CHANGE_REQUIRED 를 주면 변경 화면으로 이동한다', async ({ authenticatedPage: page }) => {
    // 첫 부팅 refresh 는 일반 토큰(관리자가 방금 비밀번호를 초기화한 상황 흉내), 하드 이동 후 두 번째
    // 부팅 refresh 는 서버 DB 값인 pwc 토큰. 순서를 카운터로 고정해 모킹 등록 타이밍 경합을 없앤다.
    let refreshCount = 0;
    await page.route((url) => url.pathname === '/api/v1/auth/refresh', (route) => {
      refreshCount += 1;
      const body = refreshCount === 1 ? createTokenResponse() : PWC_TOKEN;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    });
    await mockApi(page, 'GET', '/api/v1/auth/me', PWC_ME);
    await mockApi(page, 'GET', '/api/v1/datasets',
      { status: 403, error: 'Forbidden', message: '비밀번호를 변경해야 계속할 수 있습니다', code: 'PASSWORD_CHANGE_REQUIRED' },
      { status: 403 });

    await page.goto('/data/datasets');

    await expect(page).toHaveURL(/\/change-password$/);
    // URL 은 assign 직후 바로 바뀌므로 그것만으로는 "하드 이동 후 부팅 refresh 가 표식을 다시 읽었다"의
    // 증거가 아니다. 제목이 보인다 = 두 번째 refresh 의 pwc 토큰으로 세션이 섰다(표식이 없으면 이 화면은
    // `/` 로 돌려보낸다). 그 뒤에 호출 횟수를 확인한다.
    await expect(page.getByRole('heading', { level: 1, name: '비밀번호 변경' })).toBeVisible();
    expect(refreshCount).toBeGreaterThanOrEqual(2);
  });

  test('멤버십 1개: 변경 성공 → refresh 로 표식이 꺼진 토큰을 받고 홈으로 바로 진입한다', async ({ authMockedPage: page }) => {
    await page.addInitScript(() => localStorage.setItem('hasSession', 'true'));
    let changed = false;
    await page.route((url) => url.pathname === '/api/v1/auth/refresh', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(createTokenResponse({ mustChangePassword: !changed })) }));
    await mockApi(page, 'GET', '/api/v1/auth/me', PWC_ME);
    await mockApi(page, 'GET', '/api/v1/users/me', MOCK_USER_DETAIL);
    const capture = await mockApi(page, 'PUT', '/api/v1/users/me/password', null, { status: 204, capture: true });

    await page.goto('/change-password');
    await page.getByLabel(CURRENT_PASSWORD).fill('TempPass1x');
    await page.getByLabel(NEW_PASSWORD).fill('NewPass1x');
    await page.getByLabel(CONFIRM_PASSWORD).fill('NewPass1x');
    changed = true;
    await page.getByRole('button', { name: '변경하고 계속' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ currentPassword: 'TempPass1x', newPassword: 'NewPass1x' });
    await expect(page).toHaveURL(/\/$/);
  });

  test('변경 성공 후 refresh 가 실패하면 경고 토스트와 함께 로그인 화면으로 보낸다', async ({ authMockedPage: page }) => {
    await page.addInitScript(() => localStorage.setItem('hasSession', 'true'));
    // 부팅 refresh(1회차)는 pwc 토큰으로 세션을 세우고, 변경 직후 refresh(2회차 이후)만 500 — 순서를 카운터로 고정.
    let refreshCount = 0;
    await page.route((url) => url.pathname === '/api/v1/auth/refresh', (route) => {
      refreshCount += 1;
      return refreshCount === 1
        ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PWC_TOKEN) })
        : route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ status: 500, message: 'boom' }) });
    });
    await mockApi(page, 'GET', '/api/v1/auth/me', PWC_ME);
    await mockApi(page, 'PUT', '/api/v1/users/me/password', null, { status: 204 });
    const logout = await mockApi(page, 'POST', '/api/v1/auth/logout', null, { status: 204, capture: true });

    await page.goto('/change-password');
    await page.getByLabel(CURRENT_PASSWORD).fill('TempPass1x');
    await page.getByLabel(NEW_PASSWORD).fill('NewPass1x');
    await page.getByLabel(CONFIRM_PASSWORD).fill('NewPass1x');
    await page.getByRole('button', { name: '변경하고 계속' }).click();

    // 비밀번호는 이미 바뀌었으므로 같은 폼을 다시 내게 하지 않고(데드엔드) 세션을 정리해 로그인으로 보낸다.
    await expect(page.getByText('비밀번호가 변경되어 새 비밀번호로 다시 로그인해야 합니다')).toBeVisible();
    await logout.waitForRequest();
    await expect(page).toHaveURL(/\/login$/);
    expect(refreshCount).toBeGreaterThanOrEqual(2);
  });

  test('멤버십 여러 개(테넌트 미선택): 변경 화면이 먼저, 성공 후 워크스페이스 선택 화면', async ({ authMockedPage: page }) => {
    await page.addInitScript(() => localStorage.setItem('hasSession', 'true'));
    const memberships = [
      { tenantId: 1, tenantSlug: 'a', tenantName: 'A 워크스페이스', role: 'MEMBER' },
      { tenantId: 2, tenantSlug: 'b', tenantName: 'B 워크스페이스', role: 'MEMBER' },
    ];
    let changed = false;
    await page.route((url) => url.pathname === '/api/v1/auth/refresh', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(createTokenResponse({ activeTenantId: null, memberships, mustChangePassword: !changed })) }));
    await mockApi(page, 'GET', '/api/v1/auth/me', PWC_ME);
    await mockApi(page, 'GET', '/api/v1/users/me', MOCK_USER_DETAIL);
    await mockApi(page, 'PUT', '/api/v1/users/me/password', null, { status: 204 });

    await page.goto('/');
    // 테넌트 선택보다 비밀번호 변경이 먼저(스펙 순서).
    await expect(page).toHaveURL(/\/change-password$/);
    await page.getByLabel(CURRENT_PASSWORD).fill('TempPass1x');
    await page.getByLabel(NEW_PASSWORD).fill('NewPass1x');
    await page.getByLabel(CONFIRM_PASSWORD).fill('NewPass1x');
    changed = true;
    await page.getByRole('button', { name: '변경하고 계속' }).click();

    await expect(page.getByRole('heading', { name: '워크스페이스 선택' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'A 워크스페이스 워크스페이스 선택' })).toBeVisible();
  });

  test('검증: 확인 불일치·정책 위반·임시 비밀번호 재사용', async ({ authMockedPage: page }) => {
    await page.addInitScript(() => localStorage.setItem('hasSession', 'true'));
    await mockApi(page, 'POST', '/api/v1/auth/refresh', PWC_TOKEN);
    await mockApi(page, 'GET', '/api/v1/auth/me', PWC_ME);
    await page.goto('/change-password');

    await page.getByLabel(CURRENT_PASSWORD).fill('TempPass1x');
    await page.getByLabel(NEW_PASSWORD).fill('weak');
    await page.getByLabel(CONFIRM_PASSWORD).fill('different');
    await page.getByRole('button', { name: '변경하고 계속' }).click();
    await expect(page.getByText('새 비밀번호는 8자 이상이어야 합니다')).toBeVisible();
    await expect(page.getByText('비밀번호가 일치하지 않습니다')).toBeVisible();

    await page.getByLabel(NEW_PASSWORD).fill('TempPass1x');
    await page.getByLabel(CONFIRM_PASSWORD).fill('TempPass1x');
    await page.getByRole('button', { name: '변경하고 계속' }).click();
    await expect(page.getByText('새 비밀번호는 임시 비밀번호와 달라야 합니다')).toBeVisible();
  });

  test('현재 비밀번호가 틀리면 폼 상단에 서버 메시지를 보여 준다', async ({ authMockedPage: page }) => {
    await page.addInitScript(() => localStorage.setItem('hasSession', 'true'));
    await mockApi(page, 'POST', '/api/v1/auth/refresh', PWC_TOKEN);
    await mockApi(page, 'GET', '/api/v1/auth/me', PWC_ME);
    await mockApi(page, 'PUT', '/api/v1/users/me/password',
      { status: 400, error: 'Bad Request', message: '현재 비밀번호가 올바르지 않습니다' }, { status: 400 });
    await page.goto('/change-password');

    await page.getByLabel(CURRENT_PASSWORD).fill('Wrong1Pass');
    await page.getByLabel(NEW_PASSWORD).fill('NewPass1x');
    await page.getByLabel(CONFIRM_PASSWORD).fill('NewPass1x');
    await page.getByRole('button', { name: '변경하고 계속' }).click();

    await expect(page.getByRole('alert')).toHaveText('현재 비밀번호가 올바르지 않습니다');
    await expect(page).toHaveURL(/\/change-password$/);
  });

  test('접근성: 첫 필드 자동 포커스, Tab 순서 현재→새→확인→버튼→로그아웃, 자동완성·도움말 연결', async ({ authMockedPage: page }) => {
    await page.addInitScript(() => localStorage.setItem('hasSession', 'true'));
    await mockApi(page, 'POST', '/api/v1/auth/refresh', PWC_TOKEN);
    await mockApi(page, 'GET', '/api/v1/auth/me', PWC_ME);
    await page.goto('/change-password');

    const current = page.getByLabel(CURRENT_PASSWORD);
    const next = page.getByLabel(NEW_PASSWORD);
    const confirm = page.getByLabel(CONFIRM_PASSWORD);
    await expect(current).toBeFocused();
    // "비밀번호 보기" 토글은 tabIndex=-1 이라 Tab 순서에 끼지 않는다.
    await page.keyboard.press('Tab');
    await expect(next).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(confirm).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: '변경하고 계속' })).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeFocused();

    await expect(current).toHaveAttribute('autocomplete', 'current-password');
    await expect(next).toHaveAttribute('autocomplete', 'new-password');
    await expect(confirm).toHaveAttribute('autocomplete', 'new-password');
    await expect(next).toHaveAccessibleDescription('8~128자, 대·소문자·숫자 포함');
    // 사이드바·헤더(AppLayout)를 그리지 않는다 — 변경 전에는 그 API 들이 전부 403 이다.
    await expect(page.getByRole('navigation')).toHaveCount(0);

    // 필드 오류는 aria-invalid 로 표시된다(빨간 테두리 토큰).
    await page.getByRole('button', { name: '변경하고 계속' }).click();
    await expect(current).toHaveAttribute('aria-invalid', 'true');
  });

  test('로그아웃 링크는 세션을 끝내고 로그인 화면으로 보낸다', async ({ authMockedPage: page }) => {
    await page.addInitScript(() => localStorage.setItem('hasSession', 'true'));
    await mockApi(page, 'POST', '/api/v1/auth/refresh', PWC_TOKEN);
    await mockApi(page, 'GET', '/api/v1/auth/me', PWC_ME);
    const logout = await mockApi(page, 'POST', '/api/v1/auth/logout', null, { status: 204, capture: true });
    await page.goto('/change-password');

    await page.getByRole('button', { name: '로그아웃' }).click();

    await logout.waitForRequest();
    await expect(page).toHaveURL(/\/login$/);
  });
});
