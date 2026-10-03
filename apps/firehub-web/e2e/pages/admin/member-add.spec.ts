import { createRole, createUser } from '../../factories/auth.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 멤버 추가(WD-2) — 와이어프레임 ①②③. 입력 → POST /users payload → 결과 화면/토스트/인라인 오류.
 * 문구는 스펙 §4 인용문 기준(pre-flight F8): 결과 화면 "직접 전달하세요. 첫 로그인 시 변경해야 합니다",
 * 기존 계정 토스트 "이미 가입된 계정을 이 워크스페이스에 추가했습니다. 기존 비밀번호로 로그인합니다".
 */
const ROLES = [
  createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
  createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자', isSystem: true }),
];

test.describe('멤버 추가', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockApi(page, 'GET', '/api/v1/roles', ROLES);
    await mockApi(
      page,
      'GET',
      '/api/v1/users',
      createPageResponse([
        createUser({ id: 1, name: '양동희', username: 'owner@acme.io', email: 'owner@acme.io', membershipRole: 'OWNER' }),
        createUser({ id: 3, name: '박민호', username: 'minho@acme.io', email: 'minho@acme.io', isActive: false }),
      ]),
    );
  });

  test('새 계정: 입력값이 그대로 전송되고 임시 비밀번호를 1회 보여 준다', { tag: '@smoke' }, async ({ authenticatedPage: page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    const capture = await mockApi(page, 'POST', '/api/v1/users', { userId: 9, created: true }, { status: 201, capture: true });

    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    await dialog.getByLabel('이메일').fill('newbie@acme.io');
    await dialog.getByLabel('이름').fill('뉴비');
    const password = await dialog.getByLabel('임시 비밀번호').inputValue();
    expect(password).toMatch(/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{12}$/); // 열 때 자동 생성
    await dialog.getByRole('checkbox', { name: /ADMIN/ }).check();
    await dialog.getByRole('button', { name: '추가' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ email: 'newbie@acme.io', name: '뉴비', temporaryPassword: password, roleIds: [2] });
    // 결과 화면에서는 다이얼로그 제목이 바뀌므로 이름 없는 dialog 로 다시 잡는다.
    const result = page.getByRole('dialog');
    await expect(result.getByRole('heading', { name: '계정을 만들었습니다' })).toBeVisible();
    await expect(result.getByText('이 화면을 닫으면 비밀번호를 다시 볼 수 없습니다')).toBeVisible();
    await expect(result.getByText('직접 전달하세요. 첫 로그인 시 변경해야 합니다', { exact: true })).toBeVisible();
    await expect(result.getByText('newbie@acme.io')).toBeVisible();
    await expect(result.getByText(password)).toBeVisible();
    // 결과 화면으로 바뀌면 포커스가 "함께 복사" 버튼으로 옮겨진다(제출 버튼이 사라져 포커스 유실 방지).
    const copyAll = result.getByRole('button', { name: '아이디·비밀번호 함께 복사' });
    await expect(copyAll).toBeFocused();

    await copyAll.click();
    await expect(page.getByText('클립보드에 복사되었습니다')).toBeVisible();
    const clip = await page.evaluate(() => navigator.clipboard.readText());
    expect(clip).toBe(`아이디: newbie@acme.io\n임시 비밀번호: ${password}`);

    // 결과 화면은 바깥 클릭으로 닫히지 않는다(비밀번호를 다시 볼 수 없으므로).
    await page.mouse.click(5, 5);
    await expect(result.getByRole('heading', { name: '계정을 만들었습니다' })).toBeVisible();

    await result.getByRole('button', { name: '닫기' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);

    // 다시 열면 결과 화면이 아니라 빈 폼 + 새 임시 비밀번호 — 이전 비밀번호는 다시 보이지 않는다.
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const reopened = page.getByRole('dialog', { name: '멤버 추가' });
    await expect(reopened.getByLabel('이메일')).toHaveValue('');
    await expect(reopened.getByLabel('임시 비밀번호')).not.toHaveValue(password);
    await expect(page.getByText(password)).toHaveCount(0);
  });

  test('기존 계정: 토스트로 알리고 다이얼로그를 닫는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/users', { userId: 5, created: false }, { status: 201 });
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    await dialog.getByLabel('이메일').fill('jisu@other.io');
    await dialog.getByLabel('이름').fill('김지수');
    await dialog.getByRole('button', { name: '추가' }).click();

    await expect(page.getByText('이미 가입된 계정을 이 워크스페이스에 추가했습니다. 기존 비밀번호로 로그인합니다', { exact: true })).toBeVisible();
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('이미 멤버(409): 이메일 아래 인라인 오류', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'POST',
      '/api/v1/users',
      { status: 409, error: 'Conflict', message: '이미 이 워크스페이스의 멤버입니다', code: 'MEMBER_ALREADY_EXISTS' },
      { status: 409 },
    );
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    await dialog.getByLabel('이메일').fill('jisu@acme.io');
    await dialog.getByLabel('이름').fill('김지수');
    await dialog.getByRole('button', { name: '추가' }).click();

    await expect(dialog.getByText('이미 이 워크스페이스의 멤버입니다')).toBeVisible();
    await expect(dialog.getByLabel('이메일')).toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByLabel('이메일')).toBeFocused();
  });

  test('정지 멤버(409): 상세 재활성 링크', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'POST',
      '/api/v1/users',
      {
        status: 409,
        error: 'Conflict',
        message: '이미 이 워크스페이스의 멤버입니다(정지됨). 상세에서 재활성화하세요',
        code: 'MEMBER_SUSPENDED',
        errors: { userId: '3' },
      },
      { status: 409 },
    );
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    await dialog.getByLabel('이메일').fill('minho@acme.io');
    await dialog.getByLabel('이름').fill('박민호');
    await dialog.getByRole('button', { name: '추가' }).click();

    await expect(dialog.getByText('이미 이 워크스페이스의 멤버입니다(정지됨)')).toBeVisible();
    const link = dialog.getByRole('link', { name: '상세에서 재활성화 →' });
    await expect(link).toHaveAttribute('href', '/admin/users/3');
    // 링크는 이메일 오류 문구 안(같은 줄)에 있고, 그 문구가 이메일 입력의 설명으로 연결된다.
    await expect(dialog.locator('#member-email-error')).toContainText('상세에서 재활성화 →');
    await expect(dialog.getByLabel('이메일')).toHaveAttribute('aria-describedby', 'member-email-error');
    await expect(dialog.getByLabel('이메일')).toHaveAccessibleDescription(/이미 이 워크스페이스의 멤버입니다\(정지됨\).*상세에서 재활성화/);
    // 이메일을 고치면 링크는 사라진다(다른 사람에 대한 링크가 남지 않게).
    await dialog.getByLabel('이메일').fill('other@acme.io');
    await expect(dialog.getByRole('link', { name: '상세에서 재활성화 →' })).toHaveCount(0);
  });

  test('자동 생성: 누를 때마다 정책을 만족하는 새 값', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    const seen = new Set<string>();
    for (let i = 0; i < 5; i++) {
      await dialog.getByRole('button', { name: '자동 생성' }).click();
      const v = await dialog.getByLabel('임시 비밀번호').inputValue();
      expect(v).toMatch(/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{12}$/);
      seen.add(v);
    }
    expect(seen.size).toBe(5);
  });

  test('복사 버튼: 입력란의 임시 비밀번호를 클립보드에 복사', async ({ authenticatedPage: page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    const value = await dialog.getByLabel('임시 비밀번호').inputValue();
    await dialog.getByRole('button', { name: '비밀번호 복사' }).click();
    await expect(page.getByText('클립보드에 복사되었습니다')).toBeVisible();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(value);
  });

  test('검증: 필수·형식·비밀번호 정책', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    await dialog.getByLabel('임시 비밀번호').fill('weak');
    await dialog.getByRole('button', { name: '추가' }).click();
    await expect(dialog.getByText('이메일은 필수입니다')).toBeVisible();
    await expect(dialog.getByText('이름은 필수입니다')).toBeVisible();
    await expect(dialog.getByText('비밀번호는 8자 이상이어야 합니다')).toBeVisible();
    await expect(dialog.getByLabel('이메일')).toHaveAttribute('aria-invalid', 'true');
    await dialog.getByLabel('이메일').fill('not-an-email');
    await dialog.getByLabel('이름').click();
    await expect(dialog.getByText('올바른 이메일 형식이 아닙니다')).toBeVisible();
  });

  test('USER 는 기본 역할로 고정(체크·해제 불가)되고 선택 역할만 전송된다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'POST', '/api/v1/users', { userId: 9, created: true }, { status: 201, capture: true });
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    const user = dialog.getByRole('checkbox', { name: /USER/ });
    // 구분 기호(·)는 aria-hidden — 이름이 "USER기본 역할" 로 붙지 않고 띄어 읽혀야 한다.
    await expect(user).toHaveAccessibleName('USER 기본 역할');
    await expect(user).toBeChecked();
    await expect(user).toBeDisabled();
    await expect(dialog.getByText('기본 역할', { exact: true })).toBeVisible();
    await expect(dialog.getByText('USER 는 워크스페이스 기본 역할로 항상 부여됩니다. 선택한 역할은 여기에 추가됩니다.')).toBeVisible();
    await user.click({ force: true }); // 비활성이라 상태가 바뀌면 안 된다
    await expect(user).toBeChecked();
    await dialog.getByLabel('이메일').fill('n@acme.io');
    await dialog.getByLabel('이름').fill('N');
    await dialog.getByRole('checkbox', { name: /ADMIN/ }).check();
    await dialog.getByRole('button', { name: '추가' }).click();
    // USER 는 서버가 붙인다 — 전송 목록에는 선택 역할만.
    expect((await capture.waitForRequest()).payload).toMatchObject({ roleIds: [2] });
  });

  test('role:assign 이 없으면 다른 역할은 고를 수 없고 roleIds 는 빈 배열', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['user:read', 'user:write']);
    const capture = await mockApi(page, 'POST', '/api/v1/users', { userId: 9, created: true }, { status: 201, capture: true });
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    await expect(dialog.getByRole('checkbox', { name: /ADMIN/ })).toBeDisabled();
    await expect(dialog.getByText('역할 지정 권한이 없어 USER 역할로만 추가됩니다')).toBeVisible();
    await dialog.getByLabel('이메일').fill('n@acme.io');
    await dialog.getByLabel('이름').fill('N');
    await dialog.getByRole('button', { name: '추가' }).click();
    expect((await capture.waitForRequest()).payload).toMatchObject({ roleIds: [] });
  });

  test('user:write 가 없으면 버튼이 없다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['user:read']);
    // 권한 응답이 오기 전에는 버튼이 원래 없다 — 응답 도착을 기다린 뒤 판정해야 공허한 통과가 되지 않는다.
    const permissions = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/auth/me/permissions');
    await page.goto('/admin/users');
    await permissions;
    await expect(page.getByRole('heading', { level: 1, name: '사용자 관리' })).toBeVisible();
    await expect(page.getByRole('button', { name: '사용자 양동희 상세 보기' })).toBeVisible(); // 목록 로드 완료 후 판정
    // 응답 처리 후 한 프레임 뒤(리렌더 반영) 판정.
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
    await expect(page.getByRole('button', { name: '멤버 추가' })).toHaveCount(0);
  });

  // 양성 대조군: 위 음성 테스트와 같은 준비(권한 응답 대기 → 목록 로드 → 한 프레임)로 user:write 가 있으면
  // 버튼이 보인다. 이 준비 절차 자체가 버튼을 못 보이게 만들지 않음을 증명해 음성 단언이 공허하지 않게 한다.
  test('user:write 가 있으면 같은 준비 후 버튼이 보인다(대조군)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['user:read', 'user:write']);
    const permissions = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/auth/me/permissions');
    await page.goto('/admin/users');
    await permissions;
    await expect(page.getByRole('heading', { level: 1, name: '사용자 관리' })).toBeVisible();
    await expect(page.getByRole('button', { name: '사용자 양동희 상세 보기' })).toBeVisible();
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
    await expect(page.getByRole('button', { name: '멤버 추가' })).toHaveCount(1);
  });

  test('잘못된 역할(400 INVALID_ROLE): 폼 상단 오류',async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'POST',
      '/api/v1/users',
      { status: 400, error: 'Bad Request', message: '이 워크스페이스에 없는 역할이 포함되어 있습니다', code: 'INVALID_ROLE' },
      { status: 400 },
    );
    await page.goto('/admin/users');
    await page.getByRole('button', { name: '멤버 추가' }).click();
    const dialog = page.getByRole('dialog', { name: '멤버 추가' });
    await dialog.getByLabel('이메일').fill('n@acme.io');
    await dialog.getByLabel('이름').fill('N');
    await dialog.getByRole('button', { name: '추가' }).click();
    await expect(dialog.getByRole('alert')).toHaveText('이 워크스페이스에 없는 역할이 포함되어 있습니다');
  });

  test('목록: 상태 열은 멤버십 기준(활성/정지), OWNER 라벨', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/users');
    await expect(page.getByRole('columnheader', { name: '상태' })).toBeVisible();
    const ownerRow = page.getByRole('button', { name: '사용자 양동희 상세 보기' });
    await expect(ownerRow.getByText('OWNER', { exact: true })).toBeVisible();
    await expect(ownerRow.getByText('활성', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '사용자 박민호 상세 보기' }).getByText('정지', { exact: true })).toBeVisible();
  });

  test('키보드: Esc 로 닫으면 포커스가 "멤버 추가" 버튼으로 돌아간다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/users');
    const trigger = page.getByRole('button', { name: '멤버 추가' });
    await trigger.click();
    await expect(page.getByRole('dialog').getByLabel('이메일')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });
});
