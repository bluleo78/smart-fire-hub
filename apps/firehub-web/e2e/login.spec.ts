import { mockApi } from './fixtures/api-mock';
import { expect, MOCK_USER, test } from './fixtures/auth.fixture';

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

    // 자격증명 입력
    await page.getByLabel('아이디 (이메일)').fill('test@example.com');
    await page.getByLabel('비밀번호').fill('testpassword123');

    // 로그인 버튼 클릭
    await page.getByRole('button', { name: '로그인' }).click();

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
});
