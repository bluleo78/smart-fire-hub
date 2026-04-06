import { createUser, createUserDetail } from '../factories/auth.factory';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * 인증 전체 플로우 E2E 테스트
 * - 회원가입 → 로그인, 로그인 → 프로필 수정, 미인증 접근 보호 등 실제 사용자 시나리오를 검증한다.
 */
test.describe('인증 전체 플로우', () => {
  test('회원가입 후 홈에 도달한다', async ({ authMockedPage: page }) => {
    // 회원가입 API 성공 응답 모킹 — capture: true로 요청 payload를 캡처한다
    // signup() 내부에서 login()이 자동 호출되므로 회원가입 성공 시 '/'로 바로 이동한다
    const capture = await mockApi(page, 'POST', '/api/v1/auth/signup', createUser(), { capture: true });

    // 회원가입 페이지에서 가입
    await page.goto('/signup');
    await page.getByLabel('아이디 (이메일)').fill('newuser@example.com');
    await page.getByLabel('비밀번호').fill('password123');
    await page.getByLabel('이름').fill('새 사용자');
    await page.getByRole('button', { name: '회원가입' }).click();

    // 회원가입 API에 전달된 payload 검증 — 폼 입력값이 정확히 전달되는지 확인
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      username: 'newuser@example.com',
      password: 'password123',
      name: '새 사용자',
    });

    // signup() → login() 자동 호출 → 홈('/')에 도달 확인
    await page.waitForURL('/');
    await expect(page).toHaveURL('/');
  });

  test('로그인 후 프로필 페이지에서 이름을 확인하고 수정한다', async ({ authenticatedPage: page }) => {
    // 프로필 수정 API 모킹 — PUT 요청을 capture: true로 등록하여 payload를 캡처한다
    const capture = await mockApi(page, 'PUT', '/api/v1/users/me', {}, { capture: true });
    // refreshUser()가 호출하는 GET /users/me를 수정된 이름으로 재모킹
    // (authenticatedPage에서 이미 설정된 모킹보다 나중에 등록된 route가 우선 적용됨)
    await mockApi(page, 'GET', '/api/v1/users/me', createUserDetail({ name: '수정된 이름' }));

    // 프로필 페이지 이동
    await page.goto('/profile');

    // 이름 필드를 지우고 새 이름 입력 (초기값 확인 없이 바로 수정)
    await page.getByLabel('이름').clear();
    await page.getByLabel('이름').fill('수정된 이름');

    // 저장 버튼 클릭
    await page.getByRole('button', { name: '저장' }).click();

    // 프로필 수정 API에 전달된 payload 검증 — 수정된 이름이 정확히 전달되는지 확인
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '수정된 이름' });

    // 저장 성공 후 폼에 수정된 이름이 반영되어 있는지 확인
    await expect(page.getByLabel('이름')).toHaveValue('수정된 이름');
  });

  test('미인증 상태에서 보호 페이지 접근 시 /login으로 리다이렉트된다', async ({ authMockedPage: page }) => {
    // 로그인하지 않은 상태(authMockedPage)에서 보호된 페이지 접근 시도
    await page.goto('/data/datasets');

    // ProtectedRoute에 의해 /login으로 리다이렉트 확인
    await expect(page).toHaveURL(/\/login/);
  });
});
