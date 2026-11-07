import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetMocks } from '../fixtures/dataset.fixture';

/**
 * 폼 검증 오류 문구의 접근성 연결 (WD-515)
 * - 예전에는 오류 문구가 시각적으로만 칸 아래에 붙어, 스크린 리더는 어느 입력의 오류인지·오류가 생겼는지 알 수 없었다.
 * - 이제 오류 문구는 id 를 갖고 role=alert 로 알려지며, 입력은 aria-invalid=true + aria-describedby 로 그 문구를 가리킨다.
 * - 데이터셋 만들기는 공용 FormField 를 거치므로 FormField 를 쓰는 다른 폼도 같은 연결을 얻는다.
 */
test.describe('폼 오류 문구 접근성 연결 (WD-515)', () => {
  test('로그인: 빈 제출 시 두 칸의 오류가 입력과 연결되고 alert 로 알려진다', async ({ authMockedPage: page }) => {
    const login = await mockApi(page, 'POST', '/api/v1/auth/login', {}, { capture: true });
    await page.goto('/login');
    await page.getByRole('button', { name: '로그인' }).click();

    const username = page.getByLabel('아이디 (이메일)');
    const password = page.getByLabel('비밀번호', { exact: true });
    // 입력 → 오류 문구 연결: 접근 가능한 설명이 오류 문구 그 자체다
    await expect(username).toHaveAttribute('aria-invalid', 'true');
    await expect(username).toHaveAccessibleDescription('유효한 이메일 형식의 아이디를 입력하세요');
    await expect(password).toHaveAttribute('aria-invalid', 'true');
    await expect(password).toHaveAccessibleDescription('비밀번호를 입력하세요');
    // 오류 문구는 alert 로 알려진다(두 개)
    await expect(page.getByRole('alert')).toHaveText(['유효한 이메일 형식의 아이디를 입력하세요', '비밀번호를 입력하세요']);
    expect(login.requests).toHaveLength(0);

    // 고치면(제출 뒤에는 입력마다 다시 검사한다) 연결이 풀린다 — 오류가 없는 칸은 invalid 가 아니고 설명도 없다
    await username.fill('user@example.com');
    await password.fill('secret');
    await expect(username).toHaveAttribute('aria-invalid', 'false');
    await expect(username).not.toHaveAttribute('aria-describedby', /.+/);
    await expect(password).toHaveAttribute('aria-invalid', 'false');
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('로그인: 서버 자격증명 오류도 alert 로 알려진다', async ({ page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/login', { status: 401, message: '아이디 또는 비밀번호가 올바르지 않습니다.' }, { status: 401 });
    await mockApi(page, 'POST', '/api/v1/auth/refresh', {}, { status: 401 });
    await page.goto('/login');
    await page.getByLabel('아이디 (이메일)').fill('wrong@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('wrongpassword');
    await page.getByRole('button', { name: '로그인' }).click();

    await expect(page.getByRole('alert')).toHaveText('아이디 또는 비밀번호가 올바르지 않습니다.');
  });

  test('데이터셋 만들기: 이름·테이블명·칼럼명 오류가 각 입력과 연결되고 alert 로 알려진다', async ({
    authenticatedPage: page,
  }) => {
    await grantDatasetPermissions(page);
    await setupDatasetMocks(page);
    const create = await mockApi(page, 'POST', '/api/v1/datasets', {}, { status: 201, capture: true });

    await page.goto('/data/datasets/new');
    await page.getByRole('button', { name: '만들기' }).click();

    const name = page.getByLabel('데이터셋 이름');
    await expect(name).toHaveAttribute('aria-invalid', 'true');
    await expect(name).toHaveAccessibleDescription('데이터셋 이름을 입력하세요');
    await expect(page.locator('#name-error')).toHaveAttribute('role', 'alert');

    const tableName = page.getByLabel('테이블명');
    await expect(tableName).toHaveAttribute('aria-invalid', 'true');
    await expect(tableName).toHaveAttribute('aria-describedby', 'tableName-error');
    await expect(page.locator('#tableName-error')).toHaveAttribute('role', 'alert');

    // 칼럼명 입력(첫 칼럼)도 오류 문구와 연결된다 — 예전에는 aria-invalid 조차 없었다
    const columnName = page.locator('[id="columns.0.columnName"]');
    await expect(columnName).toHaveAttribute('aria-invalid', 'true');
    const columnError = page.locator('[id="columns.0.columnName-error"]');
    await expect(columnError).toHaveAttribute('role', 'alert');
    await expect(columnName).toHaveAccessibleDescription((await columnError.textContent()) ?? '');
    expect(create.requests).toHaveLength(0);

    // 이름을 고치면 이름 칸의 연결만 풀리고 다른 칸은 그대로다
    // 한 글자씩 쳐서 오류가 사라지는 순간에도 입력이 다시 그려지지 않아 포커스·값이 그대로인지 본다
    await name.click();
    await name.pressSequentially('접근성 확인');
    await expect(name).toHaveValue('접근성 확인');
    await expect(name).toBeFocused();
    await expect(page.locator('#name-error')).toHaveCount(0);
    await expect(name).not.toHaveAttribute('aria-describedby', /name-error/);
    await expect(tableName).toHaveAttribute('aria-describedby', 'tableName-error');
  });
});
