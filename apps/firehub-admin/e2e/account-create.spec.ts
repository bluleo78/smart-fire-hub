import { createAccount, createAccountPage, createPlatformUser, createTenant } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

/** 생성 응답 — 서버는 username = 소문자 이메일, 활성·비운영자로 준다. */
const CREATED = createAccount({ id: 77, username: 'new@example.com', email: 'new@example.com', name: '신규자', active: true });

/** 서버 비밀번호 정책(소문자·대문자·숫자 각 1자 이상, 8자 이상). */
const POLICY = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/;

test.describe('계정 생성 (WD-46)', () => {
  test('계정 화면: 자동 생성 비밀번호로 만들고 결과 화면에서 1회 보여 준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([]));
    const capture = await mockApi(page, 'POST', '/api/platform/accounts', CREATED, { status: 201, capture: true });
    await page.goto('/accounts');

    await page.getByRole('button', { name: '계정 생성' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: '계정 생성' })).toBeVisible();
    await expect(
      dialog.getByText(
        '이 계정은 아직 어느 워크스페이스에도 속하지 않습니다. 테넌트 Owner로 지정하거나 워크스페이스 관리자가 멤버로 추가해야 사용할 수 있습니다.',
      ),
    ).toBeVisible();
    // 열 때 비밀번호가 이미 정책을 만족하는 값으로 채워져 있고, 기본은 가려져 있다.
    const password = dialog.getByLabel('임시 비밀번호');
    await expect(password).toHaveAttribute('type', 'password');
    const generated = await password.inputValue();
    expect(generated).toMatch(POLICY);
    await dialog.getByRole('button', { name: '비밀번호 보기' }).click();
    await expect(password).toHaveAttribute('type', 'text');
    // 재생성은 다른 값을 만든다.
    await dialog.getByRole('button', { name: '재생성' }).click();
    const regenerated = await password.inputValue();
    expect(regenerated).toMatch(POLICY);
    expect(regenerated).not.toBe(generated);

    await dialog.getByLabel('이메일(로그인 아이디)').fill('New@Example.com');
    await dialog.getByLabel('이름').fill('신규자');
    await dialog.getByRole('button', { name: '계정 생성' }).click();

    const request = await capture.waitForRequest();
    expect(request.payload).toEqual({ email: 'New@Example.com', name: '신규자', temporaryPassword: regenerated });

    // 결과 화면: 경고·아이디·비밀번호·함께 복사. X 닫기가 없고 Esc 로도 닫히지 않는다.
    await expect(dialog.getByText('이 화면을 닫으면 비밀번호를 다시 볼 수 없습니다.')).toBeVisible();
    await expect(dialog.getByText('지금 복사해 본인에게 직접 전달하세요.', { exact: false })).toBeVisible();
    await expect(dialog.getByText('new@example.com', { exact: true })).toBeVisible();
    await expect(dialog.getByText(regenerated, { exact: true })).toBeVisible();
    await expect(dialog.getByRole('button', { name: '아이디·비밀번호 함께 복사' })).toBeFocused();
    await expect(dialog.getByRole('button', { name: '닫기' })).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(dialog).toBeVisible();

    await dialog.getByRole('button', { name: '확인' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);

    // 다시 열면 이전 입력·비밀번호가 남지 않는다(새 비밀번호).
    await page.getByRole('button', { name: '계정 생성' }).click();
    await expect(page.getByRole('dialog').getByLabel('이메일(로그인 아이디)')).toHaveValue('');
    await expect(page.getByRole('dialog').getByLabel('임시 비밀번호')).not.toHaveValue(regenerated);
  });

  test('409 ACCOUNT_ALREADY_EXISTS 는 이메일 입력 아래에 서버 문구로 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([]));
    await mockApi(
      page,
      'POST',
      '/api/platform/accounts',
      { status: 409, error: 'Conflict', message: '이미 같은 아이디 또는 이메일의 계정이 있습니다', code: 'ACCOUNT_ALREADY_EXISTS' },
      { status: 409 },
    );
    await page.goto('/accounts');
    await page.getByRole('button', { name: '계정 생성' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('이메일(로그인 아이디)').fill('kim@example.com');
    await dialog.getByLabel('이름').fill('김중복');
    await dialog.getByRole('button', { name: '계정 생성' }).click();

    const email = dialog.getByLabel('이메일(로그인 아이디)');
    await expect(dialog.getByText('이미 같은 아이디 또는 이메일의 계정이 있습니다')).toBeVisible();
    await expect(email).toHaveAttribute('aria-invalid', 'true');
    await expect(email).toHaveAttribute('aria-describedby', 'account-email-error');
    await expect(email).toBeFocused();
    // 결과 화면으로 넘어가지 않는다.
    await expect(dialog.getByRole('heading', { name: '계정 생성' })).toBeVisible();
  });

  test('서버 Bean Validation 400 은 필드 아래로 돌려 준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([]));
    await mockApi(
      page,
      'POST',
      '/api/platform/accounts',
      { status: 400, error: 'Bad Request', message: 'Validation failed', errors: { name: '이름은 50자 이하여야 합니다' } },
      { status: 400 },
    );
    await page.goto('/accounts');
    await page.getByRole('button', { name: '계정 생성' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('이메일(로그인 아이디)').fill('x@example.com');
    await dialog.getByLabel('이름').fill('이름');
    await dialog.getByRole('button', { name: '계정 생성' }).click();
    await expect(dialog.getByText('이름은 50자 이하여야 합니다')).toBeVisible();
  });

  test('platform:tenant:create 가 없으면 계정 생성 버튼이 없다', async ({ page }) => {
    await loginAs(page, ['platform:tenant:read', 'platform:member:read', 'platform:tenant:suspend']);
    await mockApi(page, 'GET', '/api/platform/accounts', createAccountPage([]));
    await page.goto('/accounts');
    await expect(page.getByRole('heading', { name: '계정', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '계정 생성' })).toHaveCount(0);
  });

  test('테넌트 생성: 검색 결과 없음 → 이메일 prefill 다이얼로그 → 완료하면 Owner 로 선택된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/users', []);
    const accountCapture = await mockApi(page, 'POST', '/api/platform/accounts', CREATED, { status: 201, capture: true });
    const tenantCapture = await mockApi(page, 'POST', '/api/platform/tenants', createTenant({ id: 9 }), { capture: true });
    await page.goto('/tenants/new');

    await expect(page.getByText('찾는 사용자가 없으면 검색 후 새 계정을 만들 수 있습니다.')).toBeVisible();
    await page.getByLabel('초기 Owner').fill('new@example.com');
    await expect(page.getByText('검색 결과가 없습니다.')).toBeVisible();
    await page.getByRole('button', { name: '"new@example.com"으로 새 계정 만들기' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByLabel('이메일(로그인 아이디)')).toHaveValue('new@example.com');
    // 이메일이 채워져 있으면 이름부터 입력한다.
    await expect(dialog.getByLabel('이름')).toBeFocused();
    await dialog.getByLabel('이름').fill('신규자');
    await dialog.getByRole('button', { name: '계정 생성' }).click();
    await accountCapture.waitForRequest();

    // 바깥 테넌트 폼이 함께 제출되지 않는다(포털이어도 React submit 이벤트는 트리를 타고 올라간다).
    await expect(page.getByText('이름을 입력하세요.')).toHaveCount(0);
    expect(tenantCapture.requests).toHaveLength(0);

    await dialog.getByRole('button', { name: 'Owner로 선택하고 닫기' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.getByRole('group', { name: '초기 Owner' })).toContainText('신규자 · new@example.com');
    // 닫힌 뒤 포커스가 사라진 행 대신 선택 해제 버튼으로 돌아온다.
    await expect(page.getByRole('button', { name: 'Owner 선택 해제' })).toBeFocused();

    // 선택된 새 계정으로 테넌트가 실제로 만들어진다.
    await page.getByLabel('이름').fill('한빛소방서');
    await page.getByLabel('식별자').fill('hanbit');
    await page.getByRole('button', { name: '테넌트 생성' }).click();
    expect((await tenantCapture.waitForRequest()).payload).toEqual({ name: '한빛소방서', slug: 'hanbit', ownerUserId: 77 });
  });

  test('테넌트 생성: 이메일이 아닌 검색어면 prefill 없이 "새 계정 만들기"', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/users', []);
    await page.goto('/tenants/new');
    await page.getByLabel('초기 Owner').fill('홍길');
    await page.getByRole('button', { name: '새 계정 만들기', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByLabel('이메일(로그인 아이디)')).toHaveValue('');
    await expect(dialog.getByLabel('이메일(로그인 아이디)')).toBeFocused();
    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('테넌트 생성: 검색 결과가 있으면 새 계정 만들기 행이 없다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser()]);
    await page.goto('/tenants/new');
    await page.getByLabel('초기 Owner').fill('박소');
    await expect(page.getByRole('option', { name: /박소유/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /새 계정 만들기/ })).toHaveCount(0);
  });
});
