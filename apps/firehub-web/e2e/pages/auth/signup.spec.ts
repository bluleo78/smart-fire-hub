import { createUser } from '../../factories/auth.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 회원가입 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 회원가입 플로우를 검증한다.
 */
test.describe('회원가입 페이지', () => {
  test('회원가입 페이지가 올바르게 렌더링된다', async ({ authMockedPage: page }) => {
    await page.goto('/signup');

    // 페이지 제목 확인 — CardTitle은 div이므로 first()로 제목 요소 한정 (버튼과 구분)
    await expect(page.getByText('회원가입').first()).toBeVisible();
    // 입력 필드 존재 확인
    await expect(page.getByLabel('아이디 (이메일)')).toBeVisible();
    await expect(page.getByLabel('비밀번호')).toBeVisible();
    await expect(page.getByLabel('이름')).toBeVisible();
    // 회원가입 버튼 존재 확인
    await expect(page.getByRole('button', { name: '회원가입' })).toBeVisible();
  });

  test('회원가입 성공 시 홈 페이지로 이동한다', async ({ authMockedPage: page }) => {
    // 회원가입 API 성공 응답 모킹 — capture: true로 요청 payload를 캡처한다
    // signup()은 내부적으로 login()을 자동 호출하므로 회원가입 성공 시 '/'로 이동한다
    const capture = await mockApi(page, 'POST', '/api/v1/auth/signup', createUser(), { capture: true });

    await page.goto('/signup');

    // 필드 채우기
    await page.getByLabel('아이디 (이메일)').fill('newuser@example.com');
    await page.getByLabel('비밀번호').fill('Password123');
    await page.getByLabel('이름').fill('새 사용자');

    // 회원가입 버튼 클릭
    await page.getByRole('button', { name: '회원가입' }).click();

    // 회원가입 API에 전달된 payload 검증 — 폼 입력값이 정확히 전달되는지 확인
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      username: 'newuser@example.com',
      password: 'Password123',
      name: '새 사용자',
    });

    // signup() 내부에서 login()이 자동 호출되어 홈('/')으로 이동 확인
    await page.waitForURL('/');
    await expect(page).toHaveURL('/');
  });

  test('빈 필드 제출 시 유효성 검사 메시지를 표시한다', async ({ authMockedPage: page }) => {
    await page.goto('/signup');

    // 빈 상태로 회원가입 버튼 클릭
    await page.getByRole('button', { name: '회원가입' }).click();

    // Zod 유효성 검사 에러 메시지 확인 — username은 이메일 형식, name은 min(1)
    await expect(page.getByText('유효한 이메일 형식의 아이디를 입력하세요')).toBeVisible();
    await expect(page.getByText('비밀번호는 8자 이상이어야 합니다')).toBeVisible();
    await expect(page.getByText('이름을 입력하세요')).toBeVisible();
  });

  test('비밀번호 8자 미만 시 유효성 에러를 표시한다', async ({ authMockedPage: page }) => {
    await page.goto('/signup');

    // 비밀번호 8자 미만 입력
    await page.getByLabel('비밀번호').fill('short');

    // 다른 필드로 포커스 이동 후 제출하여 유효성 트리거
    await page.getByRole('button', { name: '회원가입' }).click();

    // 비밀번호 최소 길이 에러 확인
    await expect(page.getByText('비밀번호는 8자 이상이어야 합니다')).toBeVisible();
  });

  test('중복 아이디 서버 에러(409) 시 에러 메시지를 표시한다', async ({ authMockedPage: page }) => {
    // 회원가입 API를 409 충돌 에러로 모킹
    await mockApi(page, 'POST', '/api/v1/auth/signup', {
      status: 409,
      message: '이미 사용 중인 아이디입니다.',
    }, { status: 409 });

    await page.goto('/signup');

    // 필드 채우기
    await page.getByLabel('아이디 (이메일)').fill('existing@example.com');
    await page.getByLabel('비밀번호').fill('Password123');
    await page.getByLabel('이름').fill('기존 사용자');

    // 회원가입 버튼 클릭
    await page.getByRole('button', { name: '회원가입' }).click();

    // 서버 에러 메시지 표시 확인
    await expect(page.getByText('이미 사용 중인 아이디입니다.')).toBeVisible();

    // 여전히 회원가입 페이지에 머물러 있는지 확인
    await expect(page).toHaveURL(/\/signup/);
  });

  test('비밀번호에 대문자/숫자 없으면 형식 에러를 표시한다', async ({ authMockedPage: page }) => {
    await page.goto('/signup');

    // 소문자만 8자 이상 — 대문자·숫자 미포함 → regex 실패
    await page.getByLabel('비밀번호').fill('alllowercase');
    await page.getByRole('button', { name: '회원가입' }).click();

    // Zod regex 에러 메시지가 비밀번호 필드 아래에 표시되어야 한다
    await expect(
      page.getByText('비밀번호는 영문 대문자, 소문자, 숫자를 각각 하나 이상 포함해야 합니다'),
    ).toBeVisible();
  });

  test('서버 errors.password 반환 시 비밀번호 필드에 인라인 에러를 표시한다', async ({
    authMockedPage: page,
  }) => {
    // 서버가 400 + errors.password 를 반환하도록 모킹 (Zod를 우회하는 케이스)
    await mockApi(
      page,
      'POST',
      '/api/v1/auth/signup',
      {
        status: 400,
        error: 'Bad Request',
        message: 'Validation failed',
        errors: { password: '비밀번호는 영문 대문자, 소문자, 숫자를 각각 하나 이상 포함해야 합니다' },
      },
      { status: 400 },
    );

    await page.goto('/signup');

    // 프론트 Zod를 통과하는 올바른 형식 입력 — 서버 측 에러만 테스트
    await page.getByLabel('아이디 (이메일)').fill('user@example.com');
    await page.getByLabel('비밀번호').fill('Password123');
    await page.getByLabel('이름').fill('테스트');
    await page.getByRole('button', { name: '회원가입' }).click();

    // 서버 errors.password가 비밀번호 필드 아래에 인라인으로 표시되어야 한다
    await expect(
      page.getByText('비밀번호는 영문 대문자, 소문자, 숫자를 각각 하나 이상 포함해야 합니다'),
    ).toBeVisible();

    // 일반 serverError 단락이 아닌 폼 필드 에러로 표시 — 여전히 signup 페이지에 머무름
    await expect(page).toHaveURL(/\/signup/);
  });

  test('로그인 링크 클릭 시 /login으로 이동한다', async ({ authMockedPage: page }) => {
    await page.goto('/signup');

    // 로그인 링크 클릭
    await page.getByText('이미 계정이 있으신가요? 로그인').click();

    // /login으로 이동했는지 확인
    await expect(page).toHaveURL(/\/login/);
  });
});
