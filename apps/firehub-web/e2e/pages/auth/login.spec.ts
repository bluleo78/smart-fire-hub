import { createTokenResponse } from '../../factories/auth.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 로그인 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 프론트엔드 로그인 플로우를 검증한다.
 */
test.describe('로그인 페이지', () => {
  test('로그인 페이지가 올바르게 렌더링된다', async ({ authMockedPage: page }) => {
    // 링크 존재 단언이 있으므로 공개 가입 "열림" 상태로 모킹한다(WD-2)
    await mockApi(page, 'GET', '/api/v1/auth/signup-status', { open: true });
    await page.goto('/login');

    // 페이지 타이틀 확인
    await expect(page.getByText('Smart Fire Hub')).toBeVisible();
    // 입력 필드 존재 확인
    await expect(page.getByLabel('아이디 (이메일)')).toBeVisible();
    await expect(page.getByLabel('비밀번호', { exact: true })).toBeVisible();
    // 로그인 버튼 존재 확인
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible();
    // 회원가입 링크 존재 확인
    await expect(page.getByText('계정이 없으신가요? 회원가입')).toBeVisible();
  });

  // 회귀 방지(#433): 제목이 CardTitle(<div>)로 렌더돼 문서 개요에 heading 이 없던 결함.
  // 존재 여부만이 아니라 "정확히 1개"와 접근 가능한 이름까지 단언해야 공허해지지 않는다.
  test('페이지 제목이 h1 heading 으로 렌더된다 (#433)', async ({ authMockedPage: page }) => {
    await page.goto('/login');

    const h1 = page.getByRole('heading', { level: 1 });
    await expect(h1).toHaveCount(1);
    await expect(h1).toHaveText('Smart Fire Hub');
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
    await page.getByLabel('비밀번호', { exact: true }).fill('testpassword123');

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

  test('비활성화된 계정은 고정 문구 대신 계정이 막혔다는 서버 안내를 보여 준다 (WD-278)', async ({ page }) => {
    // 서버는 비밀번호가 맞을 때만 401 + code ACCOUNT_DEACTIVATED 와 계정 상태 문구를 준다 — 그 문구를 그대로 보여야 한다.
    let capturedPayload: unknown;
    await page.route(
      (url) => url.pathname === '/api/v1/auth/login',
      (route) => {
        capturedPayload = route.request().postDataJSON();
        return route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({
            status: 401,
            error: 'Unauthorized',
            message: '비활성화된 계정입니다. 관리자에게 문의하세요.',
            code: 'ACCOUNT_DEACTIVATED',
          }),
        });
      },
    );
    await mockApi(page, 'POST', '/api/v1/auth/refresh', {}, { status: 401 });

    await page.goto('/login');
    await page.getByLabel('아이디 (이메일)').fill('deactivated@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('CorrectPass123');
    await page.getByRole('button', { name: '로그인' }).click();

    // 입력값이 그대로 로그인 요청에 실린다
    await expect.poll(() => capturedPayload).toEqual({ username: 'deactivated@example.com', password: 'CorrectPass123' });
    // 계정이 막혔다는 안내가 보이고, 비밀번호를 의심하게 만드는 고정 문구는 보이지 않는다
    await expect(page.getByText('비활성화된 계정입니다. 관리자에게 문의하세요.')).toBeVisible();
    await expect(page.getByText('이메일 또는 비밀번호가 올바르지 않습니다.')).not.toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test('문구 없는 401 이면 기본 자격증명 오류 문구를 보여 준다', async ({ page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/login', { status: 401 }, { status: 401 });
    await mockApi(page, 'POST', '/api/v1/auth/refresh', {}, { status: 401 });

    await page.goto('/login');
    await page.getByLabel('아이디 (이메일)').fill('wrong@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('wrongpassword');
    await page.getByRole('button', { name: '로그인' }).click();

    await expect(page.getByText('이메일 또는 비밀번호가 올바르지 않습니다.')).toBeVisible();
  });

  test('로그인 실패 시 에러 메시지를 표시한다', async ({ page }) => {
    // authMockedPage 대신 plain page 사용
    // — authMockedPage는 /auth/refresh를 200으로 모킹하므로 인터셉터 우회 여부를 검증할 수 없음
    // 실제 브라우저 동작: /auth/login 401 → 인터셉터가 refresh를 시도하지 않고 catch 블록으로 전달
    // (client.ts에서 /auth/login, /auth/refresh URL은 refresh 시도를 스킵하도록 수정됨 — refs #31)

    // /auth/login → 401 + 서버의 자격증명 오류 문구 (AuthService 가 실제로 반환하는 형태)
    await mockApi(page, 'POST', '/api/v1/auth/login', {
      status: 401,
      message: '아이디 또는 비밀번호가 올바르지 않습니다.',
    }, { status: 401 });

    // /auth/refresh도 401로 모킹 — 인터셉터가 잘못 refresh를 시도해도 강제 리다이렉트가 발생하지 않음을 검증
    await mockApi(page, 'POST', '/api/v1/auth/refresh', {}, { status: 401 });

    await page.goto('/login');

    // 자격증명 입력
    await page.getByLabel('아이디 (이메일)').fill('wrong@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('wrongpassword');

    // 로그인 버튼 클릭
    await page.getByRole('button', { name: '로그인' }).click();

    // 401 의 서버 문구를 그대로 보여 준다(WD-278 — 이유는 서버만 안다)
    await expect(page.getByText('아이디 또는 비밀번호가 올바르지 않습니다.')).toBeVisible();

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
    await page.getByLabel('비밀번호', { exact: true }).fill('testpassword123');

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
    await page.getByLabel('비밀번호', { exact: true }).fill('testpassword123');

    // 로그인 버튼 클릭 (응답 대기 없이 즉시 확인)
    await page.getByRole('button', { name: '로그인' }).click();

    // 로그인 처리 중 버튼이 disabled이고 텍스트가 변경되는지 확인
    const button = page.getByRole('button', { name: '로그인 중...' });
    await expect(button).toBeDisabled();
  });

  test('공개 가입이 열려 있으면(첫 사용자) 회원가입 링크로 이동한다', async ({ authMockedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/auth/signup-status', { open: true });
    await page.goto('/login');
    await page.getByText('계정이 없으신가요? 회원가입').click();
    await expect(page).toHaveURL(/\/signup/);
  });

  test('공개 가입이 닫혀 있으면 회원가입 링크를 숨긴다', async ({ authMockedPage: page }) => {
    const status = await mockApi(page, 'GET', '/api/v1/auth/signup-status', { open: false }, { capture: true });
    await page.goto('/login');
    await status.waitForRequest();
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible();
    await expect(page.getByText('계정이 없으신가요? 회원가입')).toHaveCount(0);
  });

  // #77: 입력 필드 autocomplete 속성 검증
  test('이메일/비밀번호 필드에 적절한 autocomplete 속성이 설정되어 있다', async ({
    authMockedPage: page,
  }) => {
    await page.goto('/login');

    await expect(page.getByLabel('아이디 (이메일)')).toHaveAttribute('autocomplete', 'username');
    await expect(page.getByLabel('비밀번호', { exact: true })).toHaveAttribute(
      'autocomplete',
      'current-password',
    );
  });

  // WD-269: 모바일 자동 대문자·자동 수정으로 아이디가 바뀌어 로그인에 실패하지 않게 한다.
  test('아이디 입력칸은 자동 대문자·자동 수정·맞춤법 검사를 끈다', async ({
    authMockedPage: page,
  }) => {
    await page.goto('/login');

    const username = page.getByLabel('아이디 (이메일)');
    await expect(username).toHaveAttribute('autocapitalize', 'none');
    await expect(username).toHaveAttribute('autocorrect', 'off');
    await expect(username).toHaveAttribute('spellcheck', 'false');
  });

  // WD-269: 대소문자를 섞어 입력해도 클라이언트가 바꾸지 않고 그대로 보낸다 — 대소문자 정규화는 서버 몫이다.
  test('대소문자를 섞은 아이디도 입력 그대로 로그인 요청에 실린다', async ({
    authMockedPage: page,
  }) => {
    await page.goto('/login');
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/auth/login',
      createTokenResponse(),
      { capture: true },
    );

    await page.getByLabel('아이디 (이메일)').fill('QA267.Kim@Example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('testpassword123');
    await page.getByRole('button', { name: '로그인' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ username: 'QA267.Kim@Example.com' });
    await page.waitForURL('/');
  });

  // #78: 비밀번호 표시/숨김 토글 동작 검증
  test('비밀번호 토글 버튼 클릭 시 type이 password ↔ text 로 전환된다', async ({
    authMockedPage: page,
  }) => {
    await page.goto('/login');

    const pwInput = page.getByLabel('비밀번호', { exact: true });
    await pwInput.fill('mypassword');
    await expect(pwInput).toHaveAttribute('type', 'password');

    await page.getByRole('button', { name: '비밀번호 보기' }).click();
    await expect(pwInput).toHaveAttribute('type', 'text');

    await page.getByRole('button', { name: '비밀번호 숨기기' }).click();
    await expect(pwInput).toHaveAttribute('type', 'password');
  });
});
