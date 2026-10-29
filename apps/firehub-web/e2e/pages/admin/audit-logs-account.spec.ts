import { createAuditLog } from '../../factories/admin.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 감사 로그 — 계정 사건(WD-290)
 * - api 가 새로 남기는 로그인 실패(LOGIN + FAILURE)·계정 잠금(ACCOUNT_LOCKED)·비밀번호 변경(PASSWORD_CHANGE) 행이
 *   영문 raw 대신 한글 라벨로 보이는지 검증한다.
 * - 「계정 잠금」·「비밀번호 변경」을 액션 필터로 고르면 actionType 쿼리로 그대로 실리는지,
 *   「로그인」+「실패」 조합이 actionType=LOGIN·result=FAILURE 로 실리는지(무차별 대입 찾기) 검증한다.
 */
const LOGIN_FAILED = createAuditLog({
  id: 1,
  username: 'brute@example.com',
  actionType: 'LOGIN',
  resource: 'auth',
  description: '로그인 실패 — 비밀번호 불일치',
  result: 'FAILURE',
  metadata: { reason: 'BAD_PASSWORD' },
});
const LOCKED = createAuditLog({
  id: 2,
  username: 'locked@example.com',
  actionType: 'ACCOUNT_LOCKED',
  resource: 'auth',
  description: '로그인 5회 연속 실패로 계정 잠금',
  result: 'SUCCESS',
});
const PASSWORD = createAuditLog({
  id: 3,
  username: 'changer@example.com',
  actionType: 'PASSWORD_CHANGE',
  resource: 'user',
  description: '임시 비밀번호 변경',
  result: 'SUCCESS',
  metadata: { temporaryPassword: true },
});

test.describe('감사 로그 — 계정 사건(WD-290)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('로그인 실패·계정 잠금·비밀번호 변경 행이 한글 라벨로 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/admin/audit-logs', createPageResponse([LOGIN_FAILED, LOCKED, PASSWORD]));
    await page.goto('/admin/audit-logs');

    const failedRow = page.getByRole('row').filter({ hasText: 'brute@example.com' });
    await expect(failedRow.getByRole('cell', { name: '로그인', exact: true })).toBeVisible();
    await expect(failedRow.getByText('실패', { exact: true })).toBeVisible();
    await expect(failedRow.getByText('로그인 실패 — 비밀번호 불일치')).toBeVisible();

    const lockedRow = page.getByRole('row').filter({ hasText: 'locked@example.com' });
    await expect(lockedRow.getByRole('cell', { name: '계정 잠금', exact: true })).toBeVisible();

    const pwRow = page.getByRole('row').filter({ hasText: 'changer@example.com' });
    await expect(pwRow.getByRole('cell', { name: '비밀번호 변경', exact: true })).toBeVisible();
    await expect(pwRow.getByText('임시 비밀번호 변경')).toBeVisible();

    // 매핑이 빠지면 raw enum 이 그대로 보인다 — 한 번도 보이면 안 된다.
    await expect(page.getByText('ACCOUNT_LOCKED')).toHaveCount(0);
    await expect(page.getByText('PASSWORD_CHANGE')).toHaveCount(0);
  });

  for (const [label, actionType] of [
    ['계정 잠금', 'ACCOUNT_LOCKED'],
    ['비밀번호 변경', 'PASSWORD_CHANGE'],
  ] as const) {
    test(`액션 필터에서 「${label}」을 고르면 actionType=${actionType} 로 조회한다`, async ({ authenticatedPage: page }) => {
      const capture = await mockApi(page, 'GET', '/api/v1/admin/audit-logs', createPageResponse([LOCKED, PASSWORD]), {
        capture: true,
      });
      await page.goto('/admin/audit-logs');
      await expect(page.getByRole('row').filter({ hasText: 'locked@example.com' })).toBeVisible();

      await page.getByRole('combobox', { name: '액션 유형 필터' }).click();
      await page.getByRole('option', { name: label, exact: true }).click();
      await expect.poll(() => capture.lastRequest()?.searchParams.get('actionType')).toBe(actionType);
    });
  }

  test('「로그인」+「실패」를 고르면 actionType=LOGIN·result=FAILURE 로 조회한다(무차별 대입 찾기)', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'GET', '/api/v1/admin/audit-logs', createPageResponse([LOGIN_FAILED]), {
      capture: true,
    });
    await page.goto('/admin/audit-logs');
    await expect(page.getByRole('row').filter({ hasText: 'brute@example.com' })).toBeVisible();

    await page.getByRole('combobox', { name: '액션 유형 필터' }).click();
    await page.getByRole('option', { name: '로그인', exact: true }).click();
    await page.getByRole('combobox', { name: '결과 필터' }).click();
    await page.getByRole('option', { name: '실패', exact: true }).click();

    await expect.poll(() => capture.lastRequest()?.searchParams.get('result')).toBe('FAILURE');
    expect(capture.lastRequest()?.searchParams.get('actionType')).toBe('LOGIN');
  });
});
