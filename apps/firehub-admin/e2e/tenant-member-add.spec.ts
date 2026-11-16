import { createMember, createPlatformUser, createTenant } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

/**
 * 테넌트 상세 「멤버 추가」(WD-750). 운영자가 테넌트에 기존 계정 또는 새 계정을 멤버로 넣는다.
 * 서버 응답은 모두 mock — 화면이 보내는 요청 본문·결과 화면·오류 안내·목록 재조회를 검증한다.
 */
const ACTIVE = createTenant({ id: 1, name: '한빛소방서', slug: 'hanbit', status: 'ACTIVE', memberCount: 1 });
const SUSPENDED = createTenant({ ...ACTIVE, status: 'SUSPENDED' });
const MEMBERS = [createMember({ userId: 10, username: 'kimsb', email: 'kim@example.com', role: 'OWNER' })];

/** 서버 비밀번호 정책(소문자·대문자·숫자 각 1자 이상, 8자 이상). */
const POLICY = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/;

test.describe('테넌트 멤버 추가 (WD-750)', () => {
  test('기존 계정을 검색해 멤버로 추가한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    const membersCapture = await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS, { capture: true });
    await mockApi(page, 'GET', '/api/platform/users', [
      createPlatformUser({ id: 42, email: 'park@example.com', name: '박기존' }),
    ]);
    const capture = await mockApi(
      page,
      'POST',
      '/api/platform/tenants/1/members',
      { userId: 42, created: false },
      { status: 201, capture: true },
    );
    await page.goto('/tenants/1');
    await expect(page.getByRole('cell', { name: 'kim@example.com' })).toBeVisible();
    const before = membersCapture.requests.length;

    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: '멤버 추가' })).toBeVisible();
    await expect(dialog.getByText('USER 역할은 항상 부여됩니다', { exact: false })).toBeVisible();

    // 기본 탭은 기존 계정 — 2자 이상 입력하면 디바운스 검색 결과에서 고른다.
    await dialog.getByRole('combobox').fill('park');
    await dialog.getByRole('option', { name: /박기존/ }).click();
    await expect(dialog.getByRole('group', { name: '추가할 계정' })).toContainText('park@example.com');

    await dialog.getByRole('button', { name: '추가', exact: true }).click();
    // 기존 계정은 계정 id 로만 지정한다 — email·name·임시 비밀번호를 보내지 않는다(username ≠ email 인 계정도 정확히 그 계정).
    const request = await capture.waitForRequest();
    expect(request.payload).toEqual({ userId: 42, admin: false });
    expect(request.payload as Record<string, unknown>).not.toHaveProperty('email');

    // 기존 계정이면 비밀번호 화면 없이 토스트만 띄우고 닫는다. 멤버 목록을 다시 읽는다.
    await expect(page.getByText('박기존님을 멤버로 추가했습니다')).toBeVisible();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect.poll(() => membersCapture.requests.length).toBeGreaterThan(before);
  });

  test('이메일이 없는 계정도 골라 멤버로 추가한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await mockApi(page, 'GET', '/api/platform/users', [createPlatformUser({ id: 7, email: null, name: '관리자' })]);
    const capture = await mockApi(
      page,
      'POST',
      '/api/platform/tenants/1/members',
      { userId: 7, created: false },
      { status: 201, capture: true },
    );
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('combobox').fill('관리');
    const option = dialog.getByRole('option', { name: /관리자/ });
    await expect(option).toContainText('이메일 없음');
    await expect(option).toBeEnabled();
    await option.click();
    await expect(dialog.getByRole('group', { name: '추가할 계정' })).toContainText('이메일 없음');

    await dialog.getByRole('button', { name: '추가', exact: true }).click();
    const request = await capture.waitForRequest();
    expect(request.payload).toEqual({ userId: 7, admin: false });
    await expect(page.getByText('관리자님을 멤버로 추가했습니다')).toBeVisible();
  });

  test('비활성 계정이면 서버 안내를 보여 준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await mockApi(page, 'GET', '/api/platform/users', [
      createPlatformUser({ id: 43, email: 'off@example.com', name: '최비활' }),
    ]);
    await mockApi(
      page,
      'POST',
      '/api/platform/tenants/1/members',
      {
        status: 409,
        error: 'Conflict',
        message: '비활성화된 계정은 멤버로 추가할 수 없습니다. 먼저 계정을 활성화하세요',
        code: 'ACCOUNT_INACTIVE',
      },
      { status: 409 },
    );
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('combobox').fill('off');
    await dialog.getByRole('option', { name: /최비활/ }).click();
    await dialog.getByRole('button', { name: '추가', exact: true }).click();

    await expect(dialog.getByRole('alert')).toHaveText(
      '비활성화된 계정은 멤버로 추가할 수 없습니다. 먼저 계정을 활성화하세요',
    );
    await expect(dialog).toBeVisible();
  });

  test('새 계정을 만들어 추가하면 임시 비밀번호를 한 번 보여 준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    const capture = await mockApi(
      page,
      'POST',
      '/api/platform/tenants/1/members',
      { userId: 77, created: true },
      { status: 201, capture: true },
    );
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('tab', { name: '새 계정' }).click();

    const password = dialog.getByLabel('임시 비밀번호');
    const generated = await password.inputValue();
    expect(generated).toMatch(POLICY);

    await dialog.getByLabel('이메일(로그인 아이디)').fill('New@Example.com');
    await dialog.getByLabel('이름').fill('신규자');
    await dialog.getByLabel('관리자(ADMIN) 역할도 부여').check();
    await dialog.getByRole('button', { name: '추가', exact: true }).click();

    const request = await capture.waitForRequest();
    expect(request.payload).toEqual({
      email: 'New@Example.com',
      name: '신규자',
      temporaryPassword: generated,
      admin: true,
    });

    // 결과 화면: 이메일·임시 비밀번호, 다시 볼 수 없다는 경고, 함께 복사. Esc 로 닫히지 않는다.
    await expect(dialog.getByText('이 비밀번호는 다시 볼 수 없습니다', { exact: false })).toBeVisible();
    await expect(dialog.getByText('new@example.com', { exact: true })).toBeVisible();
    await expect(dialog.getByText(generated, { exact: true })).toBeVisible();
    await expect(dialog.getByRole('button', { name: '이메일·비밀번호 함께 복사' })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeVisible();

    await dialog.getByRole('button', { name: '확인' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('이미 멤버면 안내를 보여 준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await mockApi(
      page,
      'POST',
      '/api/platform/tenants/1/members',
      { status: 409, error: 'Conflict', message: '이미 이 워크스페이스의 멤버입니다', code: 'MEMBER_ALREADY_EXISTS' },
      { status: 409 },
    );
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('tab', { name: '새 계정' }).click();
    await dialog.getByLabel('이메일(로그인 아이디)').fill('kim@example.com');
    await dialog.getByLabel('이름').fill('김중복');
    await dialog.getByRole('button', { name: '추가', exact: true }).click();

    await expect(dialog.getByRole('alert')).toHaveText('이미 이 테넌트의 멤버입니다');
    await expect(dialog).toBeVisible();
  });

  test('정지된 멤버면 상태 확인 안내를 보여 준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await mockApi(
      page,
      'POST',
      '/api/platform/tenants/1/members',
      {
        status: 409,
        error: 'Conflict',
        message: '이미 이 워크스페이스의 멤버입니다(정지됨).',
        code: 'MEMBER_SUSPENDED',
        errors: { userId: '10' },
      },
      { status: 409 },
    );
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('tab', { name: '새 계정' }).click();
    await dialog.getByLabel('이메일(로그인 아이디)').fill('kim@example.com');
    await dialog.getByLabel('이름').fill('김정지');
    await dialog.getByRole('button', { name: '추가', exact: true }).click();

    await expect(dialog.getByRole('alert')).toHaveText('이미 이 테넌트의 멤버입니다(정지됨). 멤버 상태를 확인하세요');
  });

  test('400 필드 오류는 해당 입력 아래에 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await mockApi(
      page,
      'POST',
      '/api/platform/tenants/1/members',
      {
        status: 400,
        error: 'Bad Request',
        message: '입력값이 올바르지 않습니다.',
        code: 'VALIDATION_FAILED',
        errors: { name: '이름에 쓸 수 없는 문자가 있습니다' },
      },
      { status: 400 },
    );
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('tab', { name: '새 계정' }).click();
    await dialog.getByLabel('이메일(로그인 아이디)').fill('a@example.com');
    await dialog.getByLabel('이름').fill('김검증');
    await dialog.getByRole('button', { name: '추가', exact: true }).click();

    await expect(dialog.getByText('이름에 쓸 수 없는 문자가 있습니다')).toBeVisible();
  });

  test('정지된 테넌트에서는 멤버 추가 버튼이 없다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', SUSPENDED);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await expect(page.getByRole('cell', { name: 'kim@example.com' })).toBeVisible();
    await expect(page.getByRole('button', { name: '멤버 추가' })).toHaveCount(0);
  });

  test('platform:tenant:create 권한이 없으면 멤버 추가 버튼이 없다', async ({ page }) => {
    await loginAs(page, ['platform:tenant:read', 'platform:member:read']);
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await expect(page.getByRole('cell', { name: 'kim@example.com' })).toBeVisible();
    await expect(page.getByRole('button', { name: '멤버 추가' })).toHaveCount(0);
  });
});
