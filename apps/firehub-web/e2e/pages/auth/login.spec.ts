import { createTokenResponse } from '../../factories/auth.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 로그인 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 프론트엔드 로그인 플로우를 검증한다.
 */
test.describe('로그인 페이지', () => {
  test('로그인 페이지가 올바르게 렌더링된다', async ({ authMockedPage: page }) => {
    await page.goto('/login');

    // 페이지 타이틀 확인
    await expect(page.getByText('Smart Fire Hub')).toBeVisible();
    // 입력 필드 존재 확인
    await expect(page.getByLabel('아이디 (이메일)')).toBeVisible();
    await expect(page.getByLabel('비밀번호')).toBeVisible();
    // 로그인 버튼 존재 확인
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible();
    // 회원가입 링크 존재 확인
    await expect(page.getByText('계정이 없으신가요? 회원가입')).toBeVisible();
  });

  test('로그인 성공 시 홈 페이지로 이동한다', async ({ authMockedPage: page }) => {
    await page.goto('/login');

    // fixture가 이미 POST /api/v1/auth/login을 모킹하고 있으므로,
    // goto() 이후에 capture: true로 재등록하여 fixture 모킹을 덮어쓴다 (나중에 등록된 route가 우선 적용됨)
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/auth/login',
      { accessToken: 'mock-jwt-access-token', tokenType: 'Bearer', expiresIn: 3600 },
      { capture: true },
    );

    // 자격증명 입력
    await page.getByLabel('아이디 (이메일)').fill('test@example.com');
    await page.getByLabel('비밀번호').fill('testpassword123');

    // 로그인 버튼 클릭
    await page.getByRole('button', { name: '로그인' }).click();

    // 로그인 API에 전달된 payload 검증 — username/password가 정확히 전달되는지 확인
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      username: 'test@example.com',
      password: 'testpassword123',
    });

    // 홈 페이지('/')로 리다이렉트 확인
    await page.waitForURL('/');
    await expect(page).toHaveURL('/');
  });

  test('로그인 실패 시 에러 메시지를 표시한다', async ({ authMockedPage: page }) => {
    // 로그인 API를 401 에러로 오버라이드
    await mockApi(page, 'POST', '/api/v1/auth/login', {
      status: 401,
      message: '아이디 또는 비밀번호가 올바르지 않습니다.',
    }, { status: 401 });

    await page.goto('/login');

    // 자격증명 입력
    await page.getByLabel('아이디 (이메일)').fill('wrong@example.com');
    await page.getByLabel('비밀번호').fill('wrongpassword');

    // 로그인 버튼 클릭
    await page.getByRole('button', { name: '로그인' }).click();

    // 에러 메시지 표시 확인
    await expect(
      page.getByText('아이디 또는 비밀번호가 올바르지 않습니다.'),
    ).toBeVisible();

    // 여전히 로그인 페이지에 머물러 있는지 확인
    await expect(page).toHaveURL(/\/login/);
  });

  test('빈 필드 제출 시 유효성 검사 메시지를 표시한다', async ({ authMockedPage: page }) => {
    await page.goto('/login');

    // 빈 상태로 로그인 버튼 클릭
    await page.getByRole('button', { name: '로그인' }).click();

    // Zod 유효성 검사 에러 메시지 확인
    await expect(page.getByText('유효한 이메일 형식의 아이디를 입력하세요')).toBeVisible();
    await expect(page.getByText('비밀번호를 입력하세요')).toBeVisible();
  });

  test('이미 로그인된 상태에서 로그인 페이지 접근 시 홈으로 리다이렉트된다', async ({
    authenticatedPage: page,
  }) => {
    // authenticatedPage는 이미 로그인 완료 상태
    // 로그인 페이지로 직접 이동 시도
    await page.goto('/login');

    // 이미 인증되었으므로 홈('/')으로 리다이렉트
    await expect(page).toHaveURL('/');
  });

  test('서버 에러(500) 시 일반 에러 메시지를 표시한다', async ({ authMockedPage: page }) => {
    // 로그인 API를 500 서버 에러로 오버라이드 — message 필드 없이 반환하여 fallback 메시지 확인
    await mockApi(page, 'POST', '/api/v1/auth/login', {}, { status: 500 });

    await page.goto('/login');

    // 자격증명 입력
    await page.getByLabel('아이디 (이메일)').fill('test@example.com');
    await page.getByLabel('비밀번호').fill('testpassword123');

    // 로그인 버튼 클릭
    await page.getByRole('button', { name: '로그인' }).click();

    // 서버 에러 시 fallback 에러 메시지 표시 확인
    await expect(
      page.getByText('로그인에 실패했습니다.'),
    ).toBeVisible();

    // 여전히 로그인 페이지에 머물러 있는지 확인
    await expect(page).toHaveURL(/\/login/);
  });

  test('로그인 중 버튼이 비활성화된다 (중복 제출 방지)', async ({ authMockedPage: page }) => {
    // 응답을 지연시켜 isSubmitting 상태를 관찰한다
    // page.waitForTimeout은 route handler 내에서 사용 불가 — Promise + setTimeout으로 대체
    await page.route('**/api/v1/auth/login', async (route) => {
      if (route.request().method() === 'POST') {
        // 800ms 지연 후 토큰 응답 반환
        await new Promise<void>((resolve) => { setTimeout(resolve, 800); });
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createTokenResponse()),
        });
      }
      return route.continue();
    });

    await page.goto('/login');

    // 자격증명 입력
    await page.getByLabel('아이디 (이메일)').fill('test@example.com');
    await page.getByLabel('비밀번호').fill('testpassword123');

    // 로그인 버튼 클릭 (응답 대기 없이 즉시 확인)
    await page.getByRole('button', { name: '로그인' }).click();

    // 로그인 처리 중 버튼이 disabled이고 텍스트가 변경되는지 확인
    const button = page.getByRole('button', { name: '로그인 중...' });
    await expect(button).toBeDisabled();
  });

  test('회원가입 링크 클릭 시 /signup으로 이동한다', async ({ authMockedPage: page }) => {
    await page.goto('/login');

    // 회원가입 링크 클릭
    await page.getByText('계정이 없으신가요? 회원가입').click();

    // /signup으로 이동했는지 확인
    await expect(page).toHaveURL(/\/signup/);
  });
});
