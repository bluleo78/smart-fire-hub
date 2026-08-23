import { createPlatformMe, createTokenResponse } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, test } from './fixtures/auth.fixture';

test.describe('운영자 로그인', () => {
  test('로그인 전 화면이 콘솔 정체성을 못 박는다', async ({ authMockedPage: page }) => {
    await page.goto('/login');

    await expect(page).toHaveTitle('운영자 콘솔 · Smart Fire Hub');
    await expect(page.getByText('Smart Fire Hub')).toBeVisible();
    await expect(page.getByText('운영자 콘솔')).toBeVisible();
    await expect(
      page.getByText(
        '이 콘솔은 플랫폼 운영자 전용입니다. 워크스페이스 사용자는 조직 주소로 로그인하세요.',
      ),
    ).toBeVisible();

    // 회원가입·비밀번호 찾기 링크는 존재하지 않는다 — 운영자 계정은 승격으로만 생긴다.
    await expect(page.getByRole('link')).toHaveCount(0);
  });

  test('로그인에 성공하면 테넌트 목록으로 간다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants', []);
    await page.goto('/login');

    await page.getByLabel('아이디').fill('ops@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('pw');
    await page.getByRole('button', { name: '로그인' }).click();

    await expect(page).toHaveURL('/tenants');
  });

  test('401 은 자격증명 오류와 같은 문구를 보여준다 (D-1)', async ({ page }) => {
    // 플랫폼 롤이 없는 유효 사용자도 서버가 같은 401 을 준다 — 화면도 구별하지 않아야 한다.
    await mockApi(
      page,
      'POST',
      '/api/platform/auth/login',
      { status: 401, error: 'Unauthorized', message: '아이디 또는 비밀번호가 올바르지 않습니다.' },
      { status: 401 },
    );
    await page.goto('/login');

    await page.getByLabel('아이디').fill('ops@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('wrong');
    await page.getByRole('button', { name: '로그인' }).click();

    // getByText 는 부분일치라 "서버 문구 + 롤 힌트 덧붙이기" 회귀를 못 잡는다(공허 단언 방증됨,
    // task-4-report.md 참고) — 배너 전체 텍스트를 정확히 대조해 반열거를 실제로 검증한다.
    await expect(page.getByRole('status')).toHaveText('아이디 또는 비밀번호가 올바르지 않습니다.');
    await expect(page).toHaveURL('/login');
  });

  test('잠금·비활성 계정은 서버 메시지를 그대로 보여준다', async ({ page }) => {
    await mockApi(
      page,
      'POST',
      '/api/platform/auth/login',
      {
        status: 401,
        error: 'Unauthorized',
        message: '로그인 시도 횟수를 초과하였습니다. 잠시 후 다시 시도해 주세요.',
      },
      { status: 401 },
    );
    await page.goto('/login');
    await page.getByLabel('아이디').fill('ops@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('pw');
    await page.getByRole('button', { name: '로그인' }).click();

    await expect(
      page.getByText('로그인 시도 횟수를 초과하였습니다. 잠시 후 다시 시도해 주세요.'),
    ).toBeVisible();
  });

  test('5xx 는 고정 문구로 대체한다', async ({ page }) => {
    await mockApi(page, 'POST', '/api/platform/auth/login', {}, { status: 500 });
    await page.goto('/login');
    await page.getByLabel('아이디').fill('ops@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('pw');
    await page.getByRole('button', { name: '로그인' }).click();

    await expect(
      page.getByText('로그인 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.'),
    ).toBeVisible();
  });

  test('빈 필드는 클라이언트 검증에서 막힌다', async ({ page }) => {
    const capture = await mockApi(
      page,
      'POST',
      '/api/platform/auth/login',
      createTokenResponse(),
      { capture: true },
    );
    await mockApi(page, 'GET', '/api/platform/auth/me', createPlatformMe());
    await page.goto('/login');

    await page.getByRole('button', { name: '로그인' }).click();

    await expect(page.getByText('아이디를 입력하세요')).toBeVisible();
    await expect(page.getByText('비밀번호를 입력하세요')).toBeVisible();
    await page.waitForTimeout(300);
    expect(capture.lastRequest()).toBeUndefined();
  });
});
