import { createPlatformMe, createTokenResponse } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, test } from './fixtures/auth.fixture';

const STATUS = '/api/platform/auth/bootstrap-status';
const SIGNUP = '/api/platform/auth/signup';

/** 폼을 채우고 제출한다. 비밀번호 확인은 기본으로 같은 값을 넣는다. */
async function fillAndSubmit(
  page: import('@playwright/test').Page,
  opts: { email?: string; confirm?: string } = {},
) {
  await page.getByLabel('아이디(이메일)').fill('admin@example.com');
  await page.getByLabel('이름').fill('최고 운영자');
  if (opts.email) await page.getByLabel('이메일(선택)').fill(opts.email);
  await page.getByLabel('비밀번호', { exact: true }).fill('Passw0rdOk');
  await page.getByLabel('비밀번호 확인').fill(opts.confirm ?? 'Passw0rdOk');
  await page.getByRole('button', { name: '계정 만들기' }).click();
}

test.describe('첫 운영자 가입', () => {
  test('사용자가 없으면 로그인 화면에 첫 운영자 계정 만들기 링크가 보인다', async ({
    authMockedPage: page,
  }) => {
    await mockApi(page, 'GET', STATUS, { available: true });
    await page.goto('/login');

    const link = page.getByRole('link', { name: '첫 운영자 계정 만들기' });
    await expect(link).toBeVisible();
    await link.click();

    await expect(page).toHaveURL('/signup');
    await expect(page.getByRole('heading', { name: '첫 운영자 계정 만들기' })).toBeVisible();
    await expect(page).toHaveTitle('첫 운영자 계정 · 운영자 콘솔 · Smart Fire Hub');
  });

  test('사용자가 있으면 진입점이 없고 /signup 은 /login 으로 돌아간다', async ({
    authMockedPage: page,
  }) => {
    await mockApi(page, 'GET', STATUS, { available: false });
    await page.goto('/login');
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible();
    await expect(page.getByRole('link')).toHaveCount(0);

    await page.goto('/signup');
    await expect(page).toHaveURL('/login');
  });

  test('상태 조회 실패면 진입점이 없다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'GET', STATUS, {}, { status: 500 });
    await page.goto('/login');
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible();
    await expect(page.getByRole('link')).toHaveCount(0);
  });

  test('가입하면 콘솔에 들어간다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'GET', STATUS, { available: true });
    await mockApi(page, 'GET', '/api/platform/tenants', []);
    await mockApi(page, 'GET', '/api/platform/auth/me', createPlatformMe());
    const capture = await mockApi(page, 'POST', SIGNUP, createTokenResponse(), {
      status: 201,
      capture: true,
    });
    await page.goto('/signup');

    await expect(page.getByText('이 계정은 플랫폼 최고 관리자(SUPER_ADMIN)가 되고')).toBeVisible();
    await fillAndSubmit(page, { email: 'contact@example.com' });

    await expect(page).toHaveURL('/tenants');
    expect(capture.lastRequest()?.payload).toEqual({
      username: 'admin@example.com',
      name: '최고 운영자',
      email: 'contact@example.com',
      password: 'Passw0rdOk',
    });
  });

  test('가입은 됐는데 세션 확인이 실패하면 로그인 안내를 보인다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'GET', STATUS, { available: true });
    await mockApi(page, 'POST', SIGNUP, createTokenResponse(), { status: 201 });
    await mockApi(page, 'GET', '/api/platform/auth/me', {}, { status: 500 });
    await page.goto('/signup');
    await fillAndSubmit(page);

    await expect(page.getByText('계정은 만들어졌습니다. 로그인해 주세요.')).toBeVisible();
    await expect(page.getByText('계정을 만들지 못했습니다')).toHaveCount(0);
    await page.getByRole('link', { name: '로그인으로 이동' }).click();
    await expect(page).toHaveURL('/login');
    // 가입이 닫혔으므로 로그인 화면에 진입점이 다시 보이지 않는다.
    await expect(page.getByRole('link', { name: '첫 운영자 계정 만들기' })).toHaveCount(0);
  });

  test('이메일을 비우면 email 을 보내지 않는다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'GET', STATUS, { available: true });
    await mockApi(page, 'GET', '/api/platform/tenants', []);
    const capture = await mockApi(page, 'POST', SIGNUP, createTokenResponse(), {
      status: 201,
      capture: true,
    });
    await page.goto('/signup');
    await fillAndSubmit(page);

    await expect(page).toHaveURL('/tenants');
    expect(capture.lastRequest()?.payload).toEqual({
      username: 'admin@example.com',
      name: '최고 운영자',
      password: 'Passw0rdOk',
    });
  });

  test('그 사이 다른 사람이 가입했으면 안내한다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'GET', STATUS, { available: true });
    await mockApi(
      page,
      'POST',
      SIGNUP,
      { status: 403, error: 'Forbidden', code: 'SIGNUP_DISABLED', message: '가입이 닫혀 있습니다.' },
      { status: 403 },
    );
    await page.goto('/signup');
    await fillAndSubmit(page);

    await expect(page.getByText('이미 운영자 계정이 있습니다. 로그인하세요.')).toBeVisible();
    await page.getByRole('link', { name: '로그인으로 이동' }).click();
    await expect(page).toHaveURL('/login');
  });

  test('비밀번호 확인이 다르면 제출하지 않는다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'GET', STATUS, { available: true });
    const capture = await mockApi(page, 'POST', SIGNUP, createTokenResponse(), {
      status: 201,
      capture: true,
    });
    await page.goto('/signup');
    await fillAndSubmit(page, { confirm: 'Different1' });

    await expect(page.getByText('비밀번호가 일치하지 않습니다')).toBeVisible();
    expect(capture.lastRequest()).toBeUndefined();
  });

  test('서버 400 필드 오류는 해당 칸 아래에 보인다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'GET', STATUS, { available: true });
    await mockApi(
      page,
      'POST',
      SIGNUP,
      {
        status: 400,
        error: 'Bad Request',
        code: 'VALIDATION_FAILED',
        message: '입력값이 올바르지 않습니다.',
        errors: { username: '이미 사용 중인 아이디입니다' },
      },
      { status: 400 },
    );
    await page.goto('/signup');
    await fillAndSubmit(page);

    await expect(page.locator('#username-error')).toHaveText('이미 사용 중인 아이디입니다');
    await expect(page).toHaveURL('/signup');
  });

  test('429 LOGIN_BUSY 는 서버 메시지를 보여준다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'GET', STATUS, { available: true });
    await mockApi(
      page,
      'POST',
      SIGNUP,
      {
        status: 429,
        error: 'Too Many Requests',
        code: 'LOGIN_BUSY',
        message: '요청이 몰리고 있습니다. 잠시 후 다시 시도해 주세요.',
      },
      { status: 429 },
    );
    await page.goto('/signup');
    await fillAndSubmit(page);

    await expect(page.getByRole('status')).toHaveText('요청이 몰리고 있습니다. 잠시 후 다시 시도해 주세요.');
  });

  test('로그인한 운영자가 /signup 을 열면 콘솔로 보낸다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants', []);
    // 세션 복원(refresh/me) 응답을 bootstrap-status(available:false) 보다 늦게 준다 — 복원 전에 판정하면 /login 으로 튕기는 경합을 재현한다.
    await page.route('**/api/platform/auth/refresh', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 300));
      await route.fallback();
    });
    await page.goto('/signup');

    await expect(page).toHaveURL('/tenants');
    await expect(page.getByRole('heading', { name: '첫 운영자 계정 만들기' })).toHaveCount(0);
  });
});
