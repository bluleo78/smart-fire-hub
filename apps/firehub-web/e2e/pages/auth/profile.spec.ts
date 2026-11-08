/**
 * 프로필 페이지 E2E 테스트
 *
 * ProfilePage.tsx 커버 경로:
 * - 프로필 정보 폼 표시 및 초기값 로드 (useEffect → profileForm.reset)
 * - 로그인 계정(username) 읽기 전용 필드 표시
 * - email이 null일 때 username이 이메일 필드 기본값으로 사용됨 (이슈 #18 회귀 방지)
 * - 프로필 저장 성공 → PUT /api/v1/users/me → toast.success
 * - 프로필 저장 실패 → root 에러 메시지 표시
 * - 비밀번호 변경 성공 → PUT /api/v1/users/me/password → toast.success + form reset
 * - 비밀번호 확인 불일치 → Zod 유효성 에러
 */

import type { UserDetailResponse } from '../../../src/types/user';
import { mockApi } from '../../fixtures/api-mock';
import { expect, MOCK_USER, test } from '../../fixtures/auth.fixture';

/** 대·소문자·숫자를 모두 넣어 복잡도는 통과하고 길이(129자)만 위반하는 값 (WD-270) */
const PASSWORD_129 = 'Aa1' + 'a'.repeat(126);
/** ChangePasswordRequest 길이 위반 시 서버 400 응답 (WD-270) */
const NEW_PASSWORD_SIZE_400 = {
  status: 400,
  error: 'Bad Request',
  message: 'Validation failed',
  errors: { newPassword: '새 비밀번호는 8~128자여야 합니다' },
};

test.describe('프로필 페이지', () => {
  /**
   * 프로필 페이지 기본 렌더링 — useEffect로 user 정보가 폼에 초기값으로 로드된다
   */
  test('프로필 정보가 폼에 초기값으로 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    await expect(page.getByRole('heading', { name: '내 프로필' })).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('프로필 정보')).toBeVisible();
    // CardTitle "비밀번호 변경"과 submit 버튼 "비밀번호 변경" 두 곳에 텍스트가 있으므로 first() 사용
    await expect(page.getByText('비밀번호 변경').first()).toBeVisible();

    // MOCK_USER.name = '테스트 사용자', email = 'test@example.com', username = 'test@example.com'
    await expect(page.locator('#profile-username')).toHaveValue('test@example.com');
    await expect(page.locator('#profile-name')).toHaveValue('테스트 사용자');
    await expect(page.locator('#profile-email')).toHaveValue('test@example.com');
  });

  /**
   * 회귀 테스트 (이슈 #18): email이 null인 경우 username이 이메일 필드에 표시된다
   * - /api/v1/users/me 응답에서 email이 null이어도 username을 통해 로그인 계정을 확인할 수 있어야 한다
   */
  test('email이 null인 경우 username이 이메일 필드 기본값으로 표시된다 (이슈 #18)', async ({ authenticatedPage: page }) => {
    // email이 null인 사용자 응답 모킹 — 실제 운영 환경과 동일한 조건
    const userWithNullEmail: UserDetailResponse = {
      ...MOCK_USER,
      email: null,
      roles: [{ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }],
    };
    await mockApi(page, 'GET', '/api/v1/users/me', userWithNullEmail);

    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: '내 프로필' })).toBeVisible({ timeout: 5000 });

    // 로그인 계정(username)은 항상 표시된다
    await expect(page.locator('#profile-username')).toHaveValue('test@example.com');
    // email이 null이면 username이 이메일 필드 기본값으로 채워진다
    await expect(page.locator('#profile-email')).toHaveValue('test@example.com');
    // 로그인 계정 필드는 읽기 전용이다
    await expect(page.locator('#profile-username')).toBeDisabled();
  });

  /**
   * 프로필 저장 성공 — PUT /api/v1/users/me 호출 및 payload 검증
   * onProfileSubmit → usersApi.updateMe → refreshUser → toast.success
   */
  test('프로필 저장 성공 → PUT /users/me 호출되고 성공 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    const updateCapture = await mockApi(page, 'PUT', '/api/v1/users/me', {}, { capture: true });

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    await page.locator('#profile-name').fill('수정된 사용자');

    await page.getByRole('button', { name: '저장' }).first().click();

    // PUT payload 검증
    const captured = await updateCapture.waitForRequest();
    expect(captured.payload).toMatchObject({ name: '수정된 사용자' });

    // toast.success 확인
    await expect(page.getByText('프로필이 업데이트되었습니다.').first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * 프로필 저장 실패 — API 500 → root 에러 메시지가 폼에 표시된다
   */
  test('프로필 저장 실패 → 에러 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    // PUT 실패 응답 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/users/me' && !url.pathname.includes('password'),
      (route) => {
        if (route.request().method() === 'PUT') {
          return route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ message: '서버 오류가 발생했습니다.' }),
          });
        }
        return route.fallback();
      },
    );

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    await page.locator('#profile-name').fill('에러 테스트');
    await page.getByRole('button', { name: '저장' }).first().click();

    // profileForm.setError('root') → 에러 메시지 표시
    await expect(page.getByText(/프로필 업데이트에 실패|서버 오류/).first()).toBeVisible({ timeout: 5000 });
  });

  /** WD-275: 공백만 있는 이름은 이름 칸 아래 「이름을 입력하세요」로 막고 저장 요청을 보내지 않는다. */
  test('이름에 공백만 넣으면 이름 칸에 오류가 뜨고 저장 요청이 나가지 않는다 (WD-275)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    let apiCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/users/me' && !url.pathname.includes('password'),
      (route) => {
        if (route.request().method() === 'PUT') {
          apiCalled = true;
          return route.fulfill({ status: 204 });
        }
        return route.fallback();
      },
    );

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    await page.locator('#profile-name').fill('   ');
    await page.getByRole('button', { name: '저장' }).first().click();

    await expect(page.locator('#profile-name-error')).toHaveText('이름을 입력하세요');
    expect(apiCalled).toBe(false);
  });

  /** WD-275: 이름 앞뒤 공백은 잘라서 저장 요청에 싣는다. */
  test('이름 앞뒤 공백은 잘려서 저장 요청에 실린다 (WD-275)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    const capture = await mockApi(page, 'PUT', '/api/v1/users/me', {}, { capture: true });

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    await page.locator('#profile-name').fill('  모바일 테스터  ');
    await page.getByRole('button', { name: '저장' }).first().click();

    const captured = await capture.waitForRequest();
    expect(captured.payload).toMatchObject({ name: '모바일 테스터' });
  });

  /** WD-275: 서버 검증 400 의 이름 오류는 폼 아래가 아니라 이름 칸 아래에 붙는다. */
  test('서버가 이름 검증 오류(400)를 주면 이름 칸 아래에 표시된다 (WD-275)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    await page.route(
      (url) => url.pathname === '/api/v1/users/me' && !url.pathname.includes('password'),
      (route) => {
        if (route.request().method() === 'PUT') {
          return route.fulfill({
            status: 400,
            contentType: 'application/json',
            body: JSON.stringify({ status: 400, error: 'Bad Request', message: 'Validation failed', errors: { name: '이름을 입력하세요' } }),
          });
        }
        return route.fallback();
      },
    );

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    await page.locator('#profile-name').fill('정상 이름');
    await page.getByRole('button', { name: '저장' }).first().click();

    await expect(page.locator('#profile-name-error')).toHaveText('이름을 입력하세요');
    await expect(page.getByText('Validation failed')).toHaveCount(0);
  });

  /** WD-640: 필드에 딸리지 않은 서버 검증 오류(클래스 단위 합성 제약 — errors._global)는 폼 상단에 서버 문구 그대로 보인다. */
  test('서버의 필드 없는 검증 오류(_global)는 폼 상단에 서버 문구로 표시된다 (WD-640)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    await page.route(
      (url) => url.pathname === '/api/v1/users/me',
      (route) => {
        if (route.request().method() === 'PUT') {
          return route.fulfill({
            status: 400,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 400,
              error: 'Bad Request',
              code: 'VALIDATION_FAILED',
              message: '입력값이 올바르지 않습니다.',
              errors: { _global: '이름과 이메일을 함께 비울 수 없습니다.' },
            }),
          });
        }
        return route.fallback();
      },
    );

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    await page.locator('#profile-name').fill('정상 이름');
    await page.getByRole('button', { name: '저장' }).first().click();

    await expect(page.getByText('이름과 이메일을 함께 비울 수 없습니다.')).toBeVisible();
    await expect(page.getByText('입력값을 확인하세요.')).toHaveCount(0);
  });

  /** WD-640: 응답이 없는 실패(네트워크)는 입력 탓(「입력값을 확인하세요.」)으로 안내하지 않는다. */
  test('프로필 저장 요청이 네트워크로 실패하면 입력 탓이 아닌 저장 실패 안내를 보인다 (WD-640)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    await page.route(
      (url) => url.pathname === '/api/v1/users/me',
      (route) => (route.request().method() === 'PUT' ? route.abort() : route.fallback()),
    );

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    await page.locator('#profile-name').fill('정상 이름');
    await page.getByRole('button', { name: '저장' }).first().click();

    await expect(page.getByText('프로필을 저장하지 못했습니다. 잠시 후 다시 시도하세요.')).toBeVisible();
    await expect(page.getByText('입력값을 확인하세요.')).toHaveCount(0);
  });

  /**
   * WD-654: 저장(PUT)은 성공했는데 뒤이은 사용자 정보 다시 읽기(GET /users/me)가 실패하면 — 저장 실패 문구가 아니라 저장 완료 안내 한 줄만
   * 보이고, 폼은 서버에 보낸 값을 보인다. 예전에는 다시 읽기 실패가 저장 실패와 같은 catch 로 떨어져 「프로필을 저장하지 못했습니다」가 떴다.
   */
  const failGetAfterSave = async (page: import('@playwright/test').Page) => {
    // PUT 을 받은 뒤부터만 GET 을 실패시킨다 — 첫 화면 로드의 GET 은 auth fixture 응답을 그대로 쓴다. 401 은 토큰 갱신 흐름을 타므로 500.
    const state = { saved: false, putPayload: undefined as unknown, getAfterSave: 0 };
    await page.route(
      (url) => url.pathname === '/api/v1/users/me',
      (route) => {
        const method = route.request().method();
        if (method === 'PUT') {
          state.saved = true;
          state.putPayload = route.request().postDataJSON();
          return route.fulfill({ status: 204 });
        }
        if (method === 'GET' && state.saved) {
          state.getAfterSave += 1;
          return route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: '일시적인 오류입니다.' }),
          });
        }
        return route.fallback();
      },
    );
    return state;
  };

  test('저장 성공 뒤 사용자 정보 다시 읽기가 실패해도 저장 실패로 안내하지 않는다 (WD-654)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    await expect(page.locator('#profile-name')).toHaveValue(MOCK_USER.name, { timeout: 5000 });
    const state = await failGetAfterSave(page);

    await page.locator('#profile-name').fill('저장된 이름');
    await page.getByRole('button', { name: '저장' }).first().click();

    // 안내는 하나 — 저장 완료 + 새로고침 안내. 일반 성공 토스트가 겹쳐 뜨지 않는다.
    await expect(page.getByText('저장은 완료되었습니다. 최신 정보는 새로고침하면 반영됩니다.').first()).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('프로필이 업데이트되었습니다.')).toHaveCount(0);
    expect(state.putPayload).toMatchObject({ name: '저장된 이름' });
    expect(state.getAfterSave).toBeGreaterThan(0);
    await expect(page.getByText('프로필을 저장하지 못했습니다. 잠시 후 다시 시도하세요.')).toHaveCount(0);
    await expect(page.getByText('일시적인 오류입니다.')).toHaveCount(0);
    // 폼은 방금 저장한 값을 그대로 보인다(예전 값으로 되돌아가지 않는다).
    await expect(page.locator('#profile-name')).toHaveValue('저장된 이름');
  });

  /** WD-654: 이메일을 비워 저장하면 서버엔 이메일이 안 가고(비움), 다시 읽기가 실패해도 폼은 빈 이메일 표시 규칙(로그인 계정)을 따른다. */
  test('이메일을 비워 저장한 뒤 다시 읽기가 실패하면 폼은 보낸 값 기준으로 보인다 (WD-654)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    await expect(page.locator('#profile-name')).toHaveValue(MOCK_USER.name, { timeout: 5000 });
    const state = await failGetAfterSave(page);

    await page.locator('#profile-email').fill('');
    await page.getByRole('button', { name: '저장' }).first().click();

    await expect(page.getByText('저장은 완료되었습니다. 최신 정보는 새로고침하면 반영됩니다.').first()).toBeVisible({ timeout: 5000 });
    expect(state.putPayload).not.toHaveProperty('email');
    // 서버는 이메일을 비웠다 — 처음 불러올 때와 같은 규칙으로 로그인 계정이 보인다(보내지 않은 빈 값 '' 이 남지 않는다).
    await expect(page.locator('#profile-email')).toHaveValue(MOCK_USER.username);
  });

  /** WD-640: 한글이 없는 서버 문구(게이트웨이 영어 문구 등)는 그대로 보이지 않고 한국어 대체 문구를 쓴다. */
  test('서버가 영어 문구만 주면 프로필 저장 실패를 한국어로 안내한다 (WD-640)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    await page.route(
      (url) => url.pathname === '/api/v1/users/me',
      (route) =>
        route.request().method() === 'PUT'
          ? route.fulfill({ status: 504, contentType: 'application/json', body: JSON.stringify({ message: 'Request timed out' }) })
          : route.fallback(),
    );

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    await page.locator('#profile-name').fill('정상 이름');
    await page.getByRole('button', { name: '저장' }).first().click();

    await expect(page.getByText('프로필을 저장하지 못했습니다. 잠시 후 다시 시도하세요.')).toBeVisible();
    await expect(page.getByText('Request timed out')).toHaveCount(0);
  });

  /** WD-640: 비밀번호 변경이 네트워크로 실패하면 「현재 비밀번호가 올바르지 않습니다」로 오도하지 않는다. */
  test('비밀번호 변경 요청이 네트워크로 실패하면 현재 비밀번호 탓으로 안내하지 않는다 (WD-640)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    await page.route('**/api/v1/users/me/password', (route) => route.abort());

    await expect(page.locator('#current-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#current-password').fill('OldPass123!');
    await page.locator('#new-password').fill('NewPass456!');
    await page.locator('#confirm-password').fill('NewPass456!');
    await page.getByRole('button', { name: '비밀번호 변경' }).click();

    await expect(page.getByText('비밀번호를 변경하지 못했습니다. 잠시 후 다시 시도하세요.')).toBeVisible();
    await expect(page.getByText(/현재 비밀번호가 올바르지 않습니다/)).toHaveCount(0);
  });

  /**
   * 비밀번호 변경 성공 → PUT /api/v1/users/me/password → toast.success + 폼 초기화
   */
  test('비밀번호 변경 성공 → PUT /users/me/password 호출되고 성공 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    const pwCapture = await mockApi(page, 'PUT', '/api/v1/users/me/password', {}, { capture: true });

    await expect(page.locator('#current-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#current-password').fill('OldPass123!');
    await page.locator('#new-password').fill('NewPass456!');
    await page.locator('#confirm-password').fill('NewPass456!');

    await page.getByRole('button', { name: '비밀번호 변경' }).click();

    // payload 검증
    const captured = await pwCapture.waitForRequest();
    expect(captured.payload).toMatchObject({
      currentPassword: 'OldPass123!',
      newPassword: 'NewPass456!',
    });

    // toast.success 확인
    await expect(page.getByText('비밀번호가 변경되었습니다.').first()).toBeVisible({ timeout: 5000 });

    // onPasswordSubmit → passwordForm.reset() → 폼 초기화
    await expect(page.locator('#current-password')).toHaveValue('', { timeout: 3000 });
  });

  /**
   * 회귀 테스트 (이슈 #26, WD-125): 이름 상한은 DB 컬럼과 같은 50자 — 51자는 화면에서 막혀 저장 요청이 나가지 않는다.
   * maxLength 를 우회해 51자를 직접 넣어 Zod max(50) 에러를 유발하고 에러 메시지와 API 미호출을 검증
   */
  test('이름 50자 초과 → 유효성 에러 메시지가 표시되고 API 호출이 안 된다 (이슈 #26, WD-125)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    // PUT API 호출 감지용 — 호출되면 실패
    let apiCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/users/me' && !url.pathname.includes('password'),
      (route) => {
        if (route.request().method() === 'PUT') {
          apiCalled = true;
          return route.fulfill({ status: 200, body: '{}' });
        }
        return route.fallback();
      },
    );

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    // maxLength 속성을 우회하여 51자를 직접 주입 — Zod max(50) 검증 트리거 (#26, WD-125)
    await page.evaluate(() => {
      const input = document.querySelector('#profile-name') as HTMLInputElement;
      const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
      nativeInputValueSetter?.call(input, 'A'.repeat(51));
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.getByRole('button', { name: '저장' }).first().click();

    // Zod max(50) 에러 메시지 표시
    await expect(page.getByText('이름은 50자 이하여야 합니다')).toBeVisible({ timeout: 5000 });
    // API가 호출되지 않아야 한다
    expect(apiCalled).toBe(false);
  });

  /**
   * 회귀 테스트 (이슈 #26): 이름 필드 maxLength 속성 검증 — HTML 레벨 입력 차단
   */
  test('이름 필드는 maxLength=50 속성을 가진다 (이슈 #26, WD-125)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    // input의 maxLength 속성이 DB 컬럼과 같은 50임을 확인
    await expect(page.locator('#profile-name')).toHaveAttribute('maxlength', '50');
  });

  /**
   * 회귀 테스트 (WD-538): 프로필 화면 모든 입력에 표준 autocomplete 값이 지정돼 있다.
   * - 가입·로그인·강제 변경 화면과 같은 값이어야 브라우저·비밀번호 관리자가 계정·비밀번호를 올바르게 채우고 저장한다.
   */
  test('프로필·비밀번호 입력에 표준 autocomplete 값이 지정돼 있다 (WD-538)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });

    // id → 기대 autocomplete 값 (가입·강제 변경 화면과 동일)
    const expected: Record<string, string> = {
      'profile-username': 'username',
      'profile-name': 'name',
      'profile-email': 'email',
      'current-password': 'current-password',
      'new-password': 'new-password',
      'confirm-password': 'new-password',
    };
    for (const [id, value] of Object.entries(expected)) {
      await expect(page.locator(`#${id}`)).toHaveAttribute('autocomplete', value);
    }
  });

  /** WD-125: 상한 경계 — 정확히 50자는 화면 검증을 통과해 그대로 저장 요청에 실린다. */
  test('이름 50자는 그대로 저장 요청에 실린다 (WD-125)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    let payload: { name?: string } | undefined;
    await page.route(
      (url) => url.pathname === '/api/v1/users/me',
      (route) => {
        if (route.request().method() === 'PUT') {
          payload = route.request().postDataJSON();
          return route.fulfill({ status: 204, body: '' });
        }
        return route.fallback();
      },
    );
    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    const name50 = '가'.repeat(50);
    await page.locator('#profile-name').fill(name50 + '초과');
    // 브라우저 maxLength 가 50자에서 자른다
    await expect(page.locator('#profile-name')).toHaveValue(name50);
    await page.getByRole('button', { name: '저장' }).first().click();
    await expect.poll(() => payload?.name).toBe(name50);
  });

  /**
   * 회귀 테스트 (이슈 #27): 잘못된 현재 비밀번호 입력 시 400 응답 → UI 에러 메시지 표시
   * 서버 500 + UI 피드백 없음 버그 방지
   */
  test('잘못된 현재 비밀번호 → 에러 메시지가 폼에 표시된다 (이슈 #27)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    // 서버 400 응답 모킹 (현재 비밀번호 불일치)
    await page.route(
      (url) => url.pathname === '/api/v1/users/me/password',
      (route) => {
        if (route.request().method() === 'PUT') {
          return route.fulfill({
            status: 400,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 400,
              error: 'Bad Request',
              code: 'WRONG_CURRENT_PASSWORD',
              message: '현재 비밀번호가 올바르지 않습니다',
            }),
          });
        }
        return route.fallback();
      },
    );

    await expect(page.locator('#current-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#current-password').fill('wrongpassword');
    await page.locator('#new-password').fill('NewPass456!');
    await page.locator('#confirm-password').fill('NewPass456!');

    await page.getByRole('button', { name: '비밀번호 변경' }).click();

    // onPasswordSubmit catch → passwordForm.setError('root') → 에러 메시지 표시
    await expect(page.getByText('현재 비밀번호가 올바르지 않습니다').first()).toBeVisible({ timeout: 5000 });
    // 폼이 리셋되지 않아야 한다 (변경 실패)
    await expect(page.locator('#current-password')).toHaveValue('wrongpassword');
  });

  /**
   * 회귀 테스트 (이슈 #70): 비밀번호 변경 폼이 onChange 모드로 동작하여
   * 제출 전(입력 도중)에도 새/확인 비밀번호 불일치 에러가 인라인으로 즉시 표시된다.
   * - mode 옵션이 기본값 'onSubmit' 그대로면 submit 전에는 에러가 표시되지 않아 회귀가 발생한다.
   */
  test('비밀번호 확인 불일치 → 제출 전에 인라인 에러가 즉시 표시된다 (이슈 #70)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    await expect(page.locator('#new-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#new-password').fill('NewPass456!');
    await page.locator('#confirm-password').fill('Different999');

    // 포커스를 다음 요소로 이동시켜 onChange 검증을 트리거하고 잠시 대기 (debounce 안전 마진)
    await page.locator('#confirm-password').blur();

    // submit 버튼을 누르지 않은 상태에서 에러가 노출되어야 한다
    await expect(page.getByText('비밀번호가 일치하지 않습니다')).toBeVisible({ timeout: 3000 });
  });

  /**
   * 비밀번호 확인 불일치 → Zod confirmPassword 에러 메시지
   */
  test('비밀번호 확인 불일치 → 유효성 에러 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    await expect(page.locator('#current-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#current-password').fill('OldPass123!');
    await page.locator('#new-password').fill('NewPass456!');
    await page.locator('#confirm-password').fill('DifferentPass!');

    await page.getByRole('button', { name: '비밀번호 변경' }).click();

    // changePasswordSchema: confirmPassword !== newPassword → 에러
    await expect(page.getByText(/비밀번호가 일치하지 않|확인/).first()).toBeVisible({ timeout: 5000 });
    // 폼이 닫히지 않아야 한다 (API 호출 안 됨)
    await expect(page.locator('#current-password')).toBeVisible();
  });

  /**
   * 회귀 테스트 (이슈 #521): 새 비밀번호가 8자 이상이지만 대/소문자+숫자 복잡도를 만족하지 않으면
   * 클라이언트 Zod 검증에서 즉시 차단되어야 한다. 이전에는 changePasswordSchema에 min(8)만 있어
   * 서버까지 요청이 도달했고, 서버가 반환한 영문 에러 메시지가 화면에 그대로 노출되는 UX 결함이 있었다.
   * 이 테스트는 API가 호출되지 않는지(=클라이언트에서 차단됨) 확인하여 회귀를 방지한다.
   */
  test('새 비밀번호가 복잡도 규칙(대/소문자+숫자)을 만족하지 않으면 API 호출 없이 한국어 에러가 표시된다 (이슈 #521)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');

    let passwordApiCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/users/me/password',
      (route) => {
        passwordApiCalled = true;
        return route.fallback();
      },
    );

    await expect(page.locator('#current-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#current-password').fill('currentpass');
    // 8자 이상이지만 소문자+숫자만 포함 — 서버의 복잡도 규칙을 위반하는 값
    await page.locator('#new-password').fill('newpass123');
    await page.locator('#confirm-password').fill('newpass123');

    await page.getByRole('button', { name: '비밀번호 변경' }).click();

    // 한국어 복잡도 에러 메시지가 표시되어야 한다 (영문 서버 메시지가 아님)
    await expect(page.getByText('대문자, 소문자, 숫자를 모두 포함해야 합니다').first()).toBeVisible({
      timeout: 5000,
    });
    // 클라이언트에서 차단되어 서버 API가 호출되지 않아야 한다
    expect(passwordApiCalled).toBe(false);
  });

  /** 회귀 테스트 (WD-270): 129자는 제출 전에 새 비밀번호 아래에서 막히고 PUT 이 나가지 않는다. */
  test('새 비밀번호 129자 → 새 비밀번호 아래 128자 상한 에러가 표시되고 API 호출이 안 된다 (WD-270)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    const pwCapture = await mockApi(page, 'PUT', '/api/v1/users/me/password', {}, { capture: true });

    await expect(page.locator('#current-password')).toBeVisible({ timeout: 5000 });
    // 강제 변경 화면과 같은 규칙 안내가 aria-describedby 로 연결돼 있어야 한다
    await expect(page.locator('#new-password')).toHaveAccessibleDescription('8~128자, 대·소문자·숫자 포함');
    await page.locator('#current-password').fill('OldPass123');
    await page.locator('#new-password').fill(PASSWORD_129);
    await page.locator('#confirm-password').fill(PASSWORD_129);

    await page.getByRole('button', { name: '비밀번호 변경' }).click();

    await expect(page.locator('#new-password-error')).toHaveText('새 비밀번호는 128자 이하여야 합니다', { timeout: 5000 });
    expect(pwCapture.requests).toHaveLength(0);
  });

  /** 회귀 테스트 (WD-270): 서버 필드 errors 400 은 폼 상단이 아니라 해당 입력 아래에 표시한다. */
  test('서버 필드 검증 오류(errors.newPassword) → 새 비밀번호 아래에 표시된다 (WD-270)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    await mockApi(page, 'PUT', '/api/v1/users/me/password', NEW_PASSWORD_SIZE_400, { status: 400 });

    await expect(page.locator('#current-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#current-password').fill('OldPass123');
    await page.locator('#new-password').fill('NewPass456');
    await page.locator('#confirm-password').fill('NewPass456');
    await page.getByRole('button', { name: '비밀번호 변경' }).click();

    await expect(page.locator('#new-password-error')).toHaveText('새 비밀번호는 8~128자여야 합니다', { timeout: 5000 });
    // 폼 상단 기본 문구로 떨어지지 않아야 한다
    await expect(page.getByText('현재 비밀번호가 올바르지 않습니다.')).toHaveCount(0);
  });
  /** 회귀 테스트 (WD-572): Enter 로 저장해 실패해도 포커스가 body 로 떨어지지 않고 저장 버튼에 남는다. */
  test('Enter 로 프로필 저장이 실패해도 포커스가 저장 버튼에 남는다 (WD-572)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    const save = await mockApi(page, 'PUT', '/api/v1/users/me', { message: '서버 오류가 발생했습니다.' },
      { status: 500, delayMs: 500, capture: true });

    await expect(page.locator('#profile-name')).toBeVisible({ timeout: 5000 });
    await page.locator('#profile-name').fill('에러 테스트');
    const button = page.getByRole('button', { name: '저장', exact: true });
    await button.focus();
    await page.keyboard.press('Enter');

    const pending = page.getByRole('button', { name: '저장 중...' });
    await expect(pending).toHaveAttribute('aria-disabled', 'true');
    await expect(pending).toBeFocused();
    // 제출 중 다시 Enter — 요청이 두 번 나가지 않아야 한다
    await page.keyboard.press('Enter');

    await expect(page.getByText('서버 오류가 발생했습니다.')).toBeVisible({ timeout: 5000 });
    await expect(button).toBeFocused();
    expect(save.requests.length).toBe(1);
  });

  /** 회귀 테스트 (WD-572): Enter 로 비밀번호 변경이 현재 비밀번호 오류로 실패하면 포커스가 현재 비밀번호 칸으로 간다. */
  test('Enter 로 비밀번호 변경이 실패하면 포커스가 현재 비밀번호 칸으로 간다 (WD-572)', async ({ authenticatedPage: page }) => {
    await page.goto('/profile');
    const change = await mockApi(page, 'PUT', '/api/v1/users/me/password',
      { status: 400, error: 'Bad Request', code: 'WRONG_CURRENT_PASSWORD', message: '현재 비밀번호가 올바르지 않습니다' },
      { status: 400, delayMs: 500, capture: true });

    await expect(page.locator('#current-password')).toBeVisible({ timeout: 5000 });
    await page.locator('#current-password').fill('wrongpassword');
    await page.locator('#new-password').fill('NewPass456!');
    await page.locator('#confirm-password').fill('NewPass456!');
    await page.getByRole('button', { name: '비밀번호 변경' }).focus();
    await page.keyboard.press('Enter');

    const pending = page.getByRole('button', { name: '변경 중...' });
    await expect(pending).toHaveAttribute('aria-disabled', 'true');
    await expect(pending).toBeFocused();
    await page.keyboard.press('Enter');

    await expect(page.getByText('현재 비밀번호가 올바르지 않습니다').first()).toBeVisible({ timeout: 5000 });
    await expect(page.locator('#current-password')).toBeFocused();
    expect(change.requests.length).toBe(1);
  });
});
