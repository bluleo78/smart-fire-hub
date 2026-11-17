import type { Locator, Page } from '@playwright/test';

import type { TenantMemberResponse } from '../src/types/platform';
import { createAccount, createAccountPage, createMember, createPlatformUser, createTenant } from './factories/platform.factory';
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

  test.describe('새 계정 탭 기존 이메일 안내 (WD-770)', () => {
    // 사전 확인은 계정 목록(username 기준)을 쓴다 — 서버가 기존 계정을 찾는 규칙과 같다.
    const KIM = createAccount({ id: 42, username: 'kim@example.com', email: 'kim@example.com', name: '김철수', active: true });
    const ACCOUNTS = '/api/platform/accounts';
    const NOTICE = '이미 가입된 계정입니다 — 김철수(kim@example.com). 새 계정을 만들지 않고 이 계정을 멤버로 추가합니다.';

    /**
     * 테넌트 상세에서 멤버 추가 대화상자를 열어 「새 계정」 탭까지 간다 — 이 묶음의 시험이 공통으로 쓰는 준비.
     * `members` 로 멤버 목록 응답을 바꿀 수 있다(WD-773 이미 멤버 안내).
     */
    async function openNewAccountTab(page: Page, members: TenantMemberResponse[] = MEMBERS) {
      await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
      await mockApi(page, 'GET', '/api/platform/tenants/1/members', members);
      await page.goto('/tenants/1');
      return openDialogNewTab(page);
    }

    /** 이미 열린 테넌트 상세에서 멤버 추가 대화상자를 열어 「새 계정」 탭으로 간다(직접 목을 건 시험·다시 열기용). */
    async function openDialogNewTab(page: Page) {
      await page.getByRole('button', { name: '멤버 추가' }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('tab', { name: '새 계정' }).click();
      return dialog;
    }

    /** 이메일 칸에 값을 넣고 포커스를 빼 사전 확인을 부른다(WD-773). */
    async function checkEmail(dialog: Locator, value: string) {
      const email = dialog.getByLabel('이메일(로그인 아이디)');
      await email.fill(value);
      await email.blur();
    }

    /** 이 테넌트의 멤버가 된 kim(계정 id 42) — 멤버십 상태만 바꿔 쓴다(WD-773). */
    const kimMember = (status = 'ACTIVE') =>
      createMember({ userId: 42, username: 'kim@example.com', role: 'MEMBER', status });

    test('새 계정 탭에 기존 이메일을 넣으면 기존 계정으로 추가하라고 안내한다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', ACCOUNTS, createAccountPage([KIM]));
      const capture = await mockApi(
        page,
        'POST',
        '/api/platform/tenants/1/members',
        { userId: 42, created: false },
        { status: 201, capture: true },
      );
      const dialog = await openNewAccountTab(page);

      const email = dialog.getByLabel('이메일(로그인 아이디)');
      await email.fill('Kim@Example.com ');
      await email.blur();
      await expect(dialog.getByText(NOTICE)).toBeVisible();

      await dialog.getByRole('button', { name: '기존 계정으로 추가' }).click();
      await expect(dialog.getByRole('tab', { name: '기존 계정' })).toHaveAttribute('aria-selected', 'true');
      await expect(dialog.getByRole('group', { name: '추가할 계정' })).toContainText('김철수');

      await dialog.getByRole('button', { name: '추가', exact: true }).click();
      const request = await capture.waitForRequest();
      expect(request.payload).toEqual({ userId: 42, admin: false });
      for (const key of ['email', 'name', 'temporaryPassword']) {
        expect(request.payload as Record<string, unknown>).not.toHaveProperty(key);
      }
    });

    test('이메일을 고치면 이전 안내가 사라진다', async ({ authenticatedPage: page }) => {
      // 검색어에 따라 다른 결과 — kim 은 일치 계정, 그 밖은 빈 목록.
      await page.route('**/api/platform/accounts?*', async (route) => {
        const q = new URL(route.request().url()).searchParams.get('q');
        const body = createAccountPage(q === 'kim@example.com' ? [KIM] : []);
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      });
      const dialog = await openNewAccountTab(page);

      const email = dialog.getByLabel('이메일(로그인 아이디)');
      await email.fill('kim@example.com');
      await email.blur();
      await expect(dialog.getByText(NOTICE)).toBeVisible();

      await email.fill('other@example.com');
      // 고치는 즉시(blur 전에도) 이전 확인 결과의 안내는 남지 않는다.
      await expect(dialog.getByText(NOTICE)).toHaveCount(0);
      await email.blur();
      await expect(dialog.getByText(NOTICE)).toHaveCount(0);
      await expect(dialog.getByRole('button', { name: '기존 계정으로 추가' })).toHaveCount(0);
    });

    test('검색이 실패해도 새 계정으로 추가할 수 있다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', ACCOUNTS, {}, { status: 500 });
      await mockApi(page, 'POST', '/api/platform/tenants/1/members', { userId: 77, created: true }, { status: 201 });
      const dialog = await openNewAccountTab(page);

      const email = dialog.getByLabel('이메일(로그인 아이디)');
      await email.fill('new@example.com');
      await email.blur();
      await dialog.getByLabel('이름').fill('신규자');
      await expect(dialog.getByText('이미 가입된 계정입니다', { exact: false })).toHaveCount(0);

      await dialog.getByRole('button', { name: '추가', exact: true }).click();
      await expect(dialog.getByText('이 비밀번호는 다시 볼 수 없습니다', { exact: false })).toBeVisible();
    });

    test('새 계정 탭 요청이 기존 계정에 붙으면 비밀번호가 쓰이지 않았다고 경고한다', async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', ACCOUNTS, createAccountPage([]));
      await mockApi(page, 'POST', '/api/platform/tenants/1/members', { userId: 9, created: false }, { status: 201 });
      const dialog = await openNewAccountTab(page);

      const email = dialog.getByLabel('이메일(로그인 아이디)');
      await email.fill('Old@Example.com');
      await email.blur();
      await dialog.getByLabel('이름').fill('기존인');
      await dialog.getByRole('button', { name: '추가', exact: true }).click();

      await expect(
        page.getByText(
          '이미 있는 계정(old@example.com)이라 기존 계정으로 추가했습니다 — 입력한 이름·임시 비밀번호는 쓰지 않았습니다',
        ),
      ).toBeVisible();
      await expect(page.getByText('이 비밀번호는 다시 볼 수 없습니다', { exact: false })).toHaveCount(0);
      await expect(page.getByRole('dialog')).toHaveCount(0);
    });

    // 안내가 없어야 하는 경우 — 조회 응답을 기다린 뒤에도 안내가 없는지 본다(고정 대기 없이).
    const NO_NOTICE_CASES = [
      { title: 'username 이 이메일과 다른 계정은 안내하지 않는다', account: createAccount({ ...KIM, username: 'jdoe' }) },
      { title: '비활성 계정은 안내하지 않는다', account: createAccount({ ...KIM, active: false }) },
    ];
    for (const { title, account } of NO_NOTICE_CASES) {
      test(title, async ({ authenticatedPage: page }) => {
        await mockApi(page, 'GET', ACCOUNTS, createAccountPage([account]));
        const dialog = await openNewAccountTab(page);

        const email = dialog.getByLabel('이메일(로그인 아이디)');
        await email.fill('kim@example.com');
        const lookup = page.waitForResponse((r) => new URL(r.url()).pathname === ACCOUNTS);
        await email.blur();
        await lookup;
        await expect(dialog.getByText('이미 가입된 계정입니다', { exact: false })).toHaveCount(0);
      });
    }

    test('탭을 오가도 안내가 유지된다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', ACCOUNTS, createAccountPage([KIM]));
      const dialog = await openNewAccountTab(page);

      const email = dialog.getByLabel('이메일(로그인 아이디)');
      await email.fill('kim@example.com');
      await email.blur();
      await expect(dialog.getByText(NOTICE)).toBeVisible();

      await dialog.getByRole('tab', { name: '기존 계정' }).click();
      await dialog.getByRole('tab', { name: '새 계정' }).click();
      await expect(dialog.getByText(NOTICE)).toBeVisible();
    });

    // ── WD-773: 사전 확인 정확도 ─────────────────────────────────────────────

    test('username 이 대소문자만 다른 계정도 기존 계정으로 안내하고, 정규화 이메일로 첫 쪽 20건을 조회한다', async ({
      authenticatedPage: page,
    }) => {
      // 서버는 username 을 대소문자 무시로 찾는다(findByUsernameIgnoreCase) — 대문자가 섞여 저장된 username 도 같은 계정이다.
      const mixed = createAccount({ ...KIM, username: 'Kim@Example.com' });
      const capture = await mockApi(page, 'GET', ACCOUNTS, createAccountPage([mixed]), { capture: true });
      const dialog = await openNewAccountTab(page);

      await checkEmail(dialog, ' KIM@example.com');
      await expect(
        dialog.getByText('이미 가입된 계정입니다 — 김철수(Kim@Example.com). 새 계정을 만들지 않고 이 계정을 멤버로 추가합니다.'),
      ).toBeVisible();
      await expect(dialog.getByRole('button', { name: '기존 계정으로 추가' })).toBeVisible();

      const request = capture.lastRequest();
      expect(request?.searchParams.get('q')).toBe('kim@example.com');
      expect(request?.searchParams.get('page')).toBe('0');
      expect(request?.searchParams.get('size')).toBe('20');
    });

    test('계정을 비활성화하면(계정 목록 무효화) 다시 확인한 결과로 안내가 사라진다', async ({ authenticatedPage: page }) => {
      // 계정 목록 응답은 비활성화 전후로 바뀐다 — 비활성화 POST 가 오면 그 뒤 GET 은 비활성 계정을 준다.
      let active = true;
      const emailChecks: string[] = [];
      await page.route('**/api/platform/accounts?*', async (route) => {
        const q = new URL(route.request().url()).searchParams.get('q');
        if (q === 'kim@example.com') emailChecks.push(q);
        const body = createAccountPage([createAccount({ ...KIM, active })]);
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      });
      await page.route('**/api/platform/accounts/42/deactivate', async (route) => {
        active = false;
        await route.fulfill({ status: 204, body: '' });
      });
      const dialog = await openNewAccountTab(page);

      await checkEmail(dialog, 'kim@example.com');
      await expect(dialog.getByText(NOTICE)).toBeVisible();
      await dialog.getByRole('button', { name: '취소' }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);

      // 같은 앱 안에서(쿼리 캐시 유지) 계정 화면으로 가 비활성화한다 — 계정 화면은 ['platform-accounts'] 접두로 무효화한다.
      await page.getByRole('link', { name: '계정' }).click();
      await page.getByRole('button', { name: '김철수 계정 비활성화' }).click();
      await page.getByRole('alertdialog').getByRole('button', { name: '비활성화' }).click();
      await expect(page.getByText('계정을 비활성화했습니다.')).toBeVisible();
      await page.goBack();

      const reopened = await openDialogNewTab(page);
      await checkEmail(reopened, 'kim@example.com');
      // 30초 캐시가 남아 있어도 무효화됐으므로 다시 조회한다 — 그 응답(비활성)으로는 안내하지 않는다.
      await expect.poll(() => emailChecks.length).toBe(2);
      await expect(reopened.getByText('이미 가입된 계정입니다', { exact: false })).toHaveCount(0);
      await expect(reopened.getByRole('button', { name: '기존 계정으로 추가' })).toHaveCount(0);
    });

    // 이미 이 테넌트의 멤버면 「기존 계정으로 추가」는 409 로 끝난다 — 멤버라는 사실만 알리고 버튼을 두지 않는다.
    const MEMBER_CASES = [
      { status: 'ACTIVE', text: '이미 이 테넌트의 멤버입니다 — 김철수(kim@example.com).' },
      {
        status: 'SUSPENDED',
        text: '이미 이 테넌트의 멤버입니다(정지됨) — 김철수(kim@example.com). 멤버 상태를 확인하세요.',
      },
    ];
    for (const { status, text } of MEMBER_CASES) {
      test(`이미 멤버(${status})면 멤버라고 안내하고 「기존 계정으로 추가」를 두지 않는다`, async ({ authenticatedPage: page }) => {
        await mockApi(page, 'GET', ACCOUNTS, createAccountPage([KIM]));
        const members = [...MEMBERS, kimMember(status)];
        const dialog = await openNewAccountTab(page, members);

        await checkEmail(dialog, 'kim@example.com');
        await expect(dialog.getByText(text)).toBeVisible();
        await expect(dialog.getByText('이미 가입된 계정입니다', { exact: false })).toHaveCount(0);
        await expect(dialog.getByRole('button', { name: '기존 계정으로 추가' })).toHaveCount(0);
      });
    }

    test('대소문자만 다른 username 계정이 여럿이면 서버처럼 id 가 가장 작은 계정을 안내한다', async ({ authenticatedPage: page }) => {
      // 응답 순서가 id 순이 아니어도(뒤 계정이 먼저 와도) 서버 findByUsernameIgnoreCase 가 붙일 최소 id 계정을 고른다.
      const newer = createAccount({ ...KIM, id: 50, username: 'kim@example.com', name: '김나중' });
      const older = createAccount({ ...KIM, id: 42, username: 'Kim@Example.com', email: null, name: '김먼저' });
      await mockApi(page, 'GET', ACCOUNTS, createAccountPage([newer, older]));
      const dialog = await openNewAccountTab(page);

      await checkEmail(dialog, 'kim@example.com');
      await expect(dialog.getByText('이미 가입된 계정입니다 — 김먼저(Kim@Example.com).', { exact: false })).toBeVisible();
    });

    test('비활성 계정은 이 테넌트의 멤버여도 안내하지 않는다', async ({ authenticatedPage: page }) => {
      // 서버는 이미 멤버인지보다 비활성(409 ACCOUNT_INACTIVE)을 먼저 본다 — 비활성이면 멤버 여부와 무관하게 사전 안내를 두지 않는다.
      await mockApi(page, 'GET', ACCOUNTS, createAccountPage([createAccount({ ...KIM, active: false })]));
      const members = [...MEMBERS, kimMember()];
      const dialog = await openNewAccountTab(page, members);

      const email = dialog.getByLabel('이메일(로그인 아이디)');
      await email.fill('kim@example.com');
      const lookup = page.waitForResponse((r) => new URL(r.url()).pathname === ACCOUNTS);
      await email.blur();
      await lookup;
      await expect(dialog.getByText('이미 이 테넌트의 멤버입니다', { exact: false })).toHaveCount(0);
      await expect(dialog.getByText('이미 가입된 계정입니다', { exact: false })).toHaveCount(0);
    });

    test('정지·활성이 아닌 다른 멤버십 상태는 상태 꼬리표 없이 멤버라고만 안내한다', async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', ACCOUNTS, createAccountPage([KIM]));
      const members = [...MEMBERS, kimMember('PENDING')];
      const dialog = await openNewAccountTab(page, members);

      await checkEmail(dialog, 'kim@example.com');
      await expect(dialog.getByText('이미 이 테넌트의 멤버입니다 — 김철수(kim@example.com).', { exact: true })).toBeVisible();
      await expect(dialog.getByText('정지됨', { exact: false })).toHaveCount(0);
    });

    test('「새 계정」 탭을 열 때 멤버 목록을 다시 읽어, 화면을 연 뒤 추가된 멤버도 이미 멤버로 안내한다', async ({
      authenticatedPage: page,
    }) => {
      // 테넌트 상세를 연 뒤 다른 곳(웹 관리자 등)에서 kim 이 멤버가 됐다 — 처음 읽은 멤버 목록 캐시로 판정하면 「기존 계정으로 추가」를 잘못 권한다.
      let members = MEMBERS;
      let memberRequests = 0;
      await page.route('**/api/platform/tenants/1/members', async (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        memberRequests += 1;
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(members) });
      });
      await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
      await mockApi(page, 'GET', ACCOUNTS, createAccountPage([KIM]));
      await page.goto('/tenants/1');
      await expect(page.getByRole('cell', { name: 'kimsb' })).toBeVisible();
      const before = memberRequests;
      members = [...MEMBERS, kimMember()];

      const dialog = await openDialogNewTab(page);
      await expect.poll(() => memberRequests).toBeGreaterThan(before);
      await checkEmail(dialog, 'kim@example.com');
      await expect(dialog.getByText('이미 이 테넌트의 멤버입니다 — 김철수(kim@example.com).')).toBeVisible();
      await expect(dialog.getByRole('button', { name: '기존 계정으로 추가' })).toHaveCount(0);
    });

    test('새 계정을 만들어 추가한 뒤 다시 열면 낭비 요청 없이 새로 확인해 그 계정을 안내한다', async ({
      authenticatedPage: page,
    }) => {
      // 추가 전에는 계정이 없고, 추가 뒤에는 kim 이 계정이자 멤버다.
      let added = false;
      let emailChecks = 0;
      let memberRequests = 0;
      await page.route('**/api/platform/accounts?*', async (route) => {
        if (new URL(route.request().url()).searchParams.get('q') === 'kim@example.com') emailChecks += 1;
        const body = createAccountPage(added ? [KIM] : []);
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      });
      await page.route('**/api/platform/tenants/1/members', async (route) => {
        if (route.request().method() === 'POST') {
          added = true;
          await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ userId: 42, created: true }) });
          return;
        }
        memberRequests += 1;
        const body = added ? [...MEMBERS, kimMember()] : MEMBERS;
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      });
      await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
      await page.goto('/tenants/1');
      const dialog = await openDialogNewTab(page);
      await checkEmail(dialog, 'kim@example.com');
      await expect.poll(() => emailChecks).toBe(1);
      await dialog.getByLabel('이름').fill('김철수');
      const beforeAdd = memberRequests;
      await dialog.getByRole('button', { name: '추가', exact: true }).click();
      await expect(dialog.getByText('이 비밀번호는 다시 볼 수 없습니다', { exact: false })).toBeVisible();
      // 추가 성공 무효화(멤버 목록 재조회)가 끝난 시점에 확인 쿼리는 낡음 표시만 되고 다시 조회되지 않았다(refetchType none).
      await expect.poll(() => memberRequests).toBeGreaterThan(beforeAdd);
      expect(emailChecks).toBe(1);
      await dialog.getByRole('button', { name: '확인' }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);

      const reopened = await openDialogNewTab(page);
      await checkEmail(reopened, 'kim@example.com');
      // 30초 캐시 안이지만 낡음 표시가 돼 있어 다시 조회한다 — 「계정 없음」 캐시로 안내를 놓치지 않는다.
      await expect.poll(() => emailChecks).toBe(2);
      await expect(reopened.getByText('이미 이 테넌트의 멤버입니다 — 김철수(kim@example.com).')).toBeVisible();
    });

    test('멤버 목록을 읽지 못했으면 멤버 여부를 모르므로 기존 계정 안내를 띄우지 않는다', async ({
      authenticatedPage: page,
    }) => {
      // 멤버인지 모르는 채 「기존 계정으로 추가」를 권하면 이미 멤버인 계정에 409 를 부를 수 있다 — 아무 안내도 하지 않는다(안전망은 제출 뒤 안내).
      await mockApi(page, 'GET', ACCOUNTS, createAccountPage([KIM]));
      await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
      await mockApi(
        page,
        'GET',
        '/api/platform/tenants/1/members',
        { status: 403, error: 'Forbidden', message: '권한이 없습니다' },
        { status: 403 },
      );
      await page.goto('/tenants/1');
      const dialog = await openDialogNewTab(page);

      const email = dialog.getByLabel('이메일(로그인 아이디)');
      await email.fill('kim@example.com');
      const lookup = page.waitForResponse((r) => new URL(r.url()).pathname === ACCOUNTS);
      await email.blur();
      await lookup;
      await expect(dialog.getByText('이미 가입된 계정입니다', { exact: false })).toHaveCount(0);
      await expect(dialog.getByText('이미 이 테넌트의 멤버입니다', { exact: false })).toHaveCount(0);
    });
  });
});
