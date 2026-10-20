import { createAuditLog } from '../../factories/admin.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 감사 로그 — 역할·권한 액션(WD-117)
 * - api 가 새로 남기는 ROLE_ASSIGN(사용자 역할 교체)·ROLE_CREATE/UPDATE/DELETE·ROLE_PERMISSIONS_UPDATE 가
 *   한글 라벨로 보이고, 액션 필터의 「역할·권한」 그룹에서 골라 actionType 쿼리로 실리는지 검증한다.
 * - 역할 교체·권한 교체 상세에는 metadata.added/removed 가, 역할 수정 상세에는 바뀐 칸이 「변경 요약」 한 줄로 보인다.
 */
const ROLE_ASSIGN = createAuditLog({
  id: 31,
  username: 'grantor',
  actionType: 'ROLE_ASSIGN',
  resource: 'user',
  resourceId: '42',
  description: '역할 변경',
  metadata: { before: ['USER'], after: ['ADMIN', 'USER'], added: ['ADMIN'], removed: [] },
});
const ROLE_PERMS = createAuditLog({
  id: 32,
  username: 'permadm',
  actionType: 'ROLE_PERMISSIONS_UPDATE',
  resource: 'role',
  resourceId: '7',
  description: '역할 권한 변경: ANALYST',
  metadata: { role: 'ANALYST', added: ['dataset:write', 'pipeline:read'], removed: ['user:read'] },
});
const ROLE_CREATE = createAuditLog({
  id: 33,
  username: 'creator',
  actionType: 'ROLE_CREATE',
  resource: 'role',
  resourceId: '8',
  description: '역할 생성: AUDITOR',
  metadata: { name: 'AUDITOR', description: null },
});
const ROLE_UPDATE = createAuditLog({
  id: 34,
  username: 'updater',
  actionType: 'ROLE_UPDATE',
  resource: 'role',
  resourceId: '7',
  description: '역할 수정: ANALYST2',
  metadata: {
    before: { name: 'ANALYST', description: '분석' },
    after: { name: 'ANALYST2', description: '분석' },
  },
});
const ROLE_DELETE = createAuditLog({ id: 35, username: 'deleter', actionType: 'ROLE_DELETE', resource: 'role' });

test.describe('감사 로그 — 역할·권한 액션(WD-117)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([ROLE_ASSIGN, ROLE_PERMS, ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE]),
    );
  });

  test('역할·권한 액션이 영문 raw 대신 한글 라벨로 보인다', async ({ authenticatedPage: page }) => {
    // 미등록 액션은 console.warn 을 남긴다 — 경고가 없어야 5종이 모두 등록된 것이다.
    const warnings: string[] = [];
    page.on('console', (m) => {
      if (m.type() === 'warning' && m.text().startsWith('[AuditLog]')) warnings.push(m.text());
    });
    await page.goto('/admin/audit-logs');
    const row = (name: string) => page.getByRole('row').filter({ hasText: name });
    await expect(row('grantor').getByRole('cell', { name: '사용자 역할 변경', exact: true })).toBeVisible();
    await expect(row('grantor').getByRole('cell', { name: '사용자', exact: true })).toBeVisible();
    await expect(row('permadm').getByRole('cell', { name: '역할 권한 변경', exact: true })).toBeVisible();
    await expect(row('permadm').getByRole('cell', { name: '역할', exact: true })).toBeVisible();
    await expect(row('creator').getByRole('cell', { name: '역할 생성', exact: true })).toBeVisible();
    await expect(row('updater').getByRole('cell', { name: '역할 수정', exact: true })).toBeVisible();
    await expect(row('deleter').getByRole('cell', { name: '역할 삭제', exact: true })).toBeVisible();
    for (const raw of ['ROLE_ASSIGN', 'ROLE_PERMISSIONS_UPDATE', 'ROLE_CREATE', 'ROLE_UPDATE', 'ROLE_DELETE']) {
      await expect(page.getByRole('cell', { name: raw, exact: true })).toHaveCount(0);
    }
    expect(warnings).toEqual([]);
  });

  test('액션 필터의 「역할·권한」 그룹에서 고르면 actionType 쿼리가 실린다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    await expect(page.getByRole('row').filter({ hasText: 'grantor' })).toBeVisible();
    const byAction = (value: string) =>
      page.waitForRequest(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/admin/audit-logs' &&
          new URL(r.url()).searchParams.get('actionType') === value,
      );
    const group = (name: string) => page.getByRole('group', { name, exact: true });
    const trigger = page.getByRole('combobox', { name: '액션 유형 필터' });

    let req = byAction('ROLE_ASSIGN');
    await trigger.click();
    // 그룹에 5종이 모두 있다.
    for (const label of ['사용자 역할 변경', '역할 생성', '역할 수정', '역할 삭제', '역할 권한 변경']) {
      await expect(group('역할·권한').getByRole('option', { name: label, exact: true })).toHaveCount(1);
    }
    await group('역할·권한').getByRole('option', { name: '사용자 역할 변경', exact: true }).click();
    await req;
    await expect(trigger).toHaveText('사용자 역할 변경');

    req = byAction('ROLE_PERMISSIONS_UPDATE');
    await trigger.click();
    await group('역할·권한').getByRole('option', { name: '역할 권한 변경', exact: true }).click();
    await req;
    await expect(trigger).toHaveText('역할 권한 변경');
  });

  test('역할 교체·권한 교체·역할 수정 상세에 「변경 요약」이 보인다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    const dialog = page.getByRole('dialog');

    await page.getByRole('row').filter({ hasText: 'grantor' }).click();
    await expect(dialog.getByText('사용자 역할 변경', { exact: true })).toBeVisible();
    await expect(dialog.getByText('사용자 (42)', { exact: true })).toBeVisible();
    // 제거가 비어 있으면 그 항목은 생략한다.
    await expect(dialog.getByTestId('audit-change-summary')).toHaveText('추가: ADMIN');
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    await page.getByRole('row').filter({ hasText: 'permadm' }).click();
    await expect(dialog.getByTestId('audit-change-summary')).toHaveText(
      '추가: dataset:write, pipeline:read · 제거: user:read',
    );
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    // 역할 수정은 바뀐 칸만 「이름: A → B」로 — 그대로인 설명은 빠진다.
    await page.getByRole('row').filter({ hasText: 'updater' }).click();
    await expect(dialog.getByTestId('audit-change-summary')).toHaveText('이름: ANALYST → ANALYST2');
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    // 역할 생성은 요약 대상이 아니다 — 필드 자체가 없고 Metadata 원문만 보인다.
    await page.getByRole('row').filter({ hasText: 'creator' }).click();
    await expect(dialog.getByText('Metadata')).toBeVisible();
    await expect(dialog.getByText('변경 요약', { exact: true })).toHaveCount(0);
  });
});
