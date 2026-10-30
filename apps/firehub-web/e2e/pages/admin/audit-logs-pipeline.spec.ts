import { createAuditLog } from '../../factories/admin.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 감사 로그 — 파이프라인·트리거 변경과 실행 시작 주체(WD-462)
 * - api 가 새로 남기는 파이프라인 CREATE/UPDATE/DELETE 와 트리거(resource=pipeline_trigger) CREATE/UPDATE/STATUS_CHANGE/DELETE 가
 *   영문 raw 대신 한글 라벨로 보이고, 리소스 필터 「트리거」가 resource=pipeline_trigger 쿼리로 실리는지 검증한다.
 * - 실행(EXECUTE) 상세에는 실행 번호(설명)와 시작 주체(metadata.triggeredBy)가 보인다.
 */
const PIPELINE_UPDATE = createAuditLog({
  id: 41,
  username: 'pipeeditor',
  actionType: 'UPDATE',
  resource: 'pipeline',
  resourceId: '5',
  description: '파이프라인 수정: 일일 집계 (바뀐 항목: 이름·스텝)',
  metadata: { name: '일일 집계', changedFields: ['name', 'steps'] },
});
const PIPELINE_EXECUTE = createAuditLog({
  id: 42,
  username: 'chainowner',
  actionType: 'EXECUTE',
  resource: 'pipeline',
  resourceId: '5',
  description: '파이프라인 실행: 일일 집계 (실행 #120)',
  metadata: { executionId: 120, triggeredBy: 'PIPELINE_CHAIN', triggerId: 12 },
});
const TRIGGER_CREATE = createAuditLog({
  id: 43,
  username: 'trgcreator',
  actionType: 'CREATE',
  resource: 'pipeline_trigger',
  resourceId: '12',
  description: '트리거 생성: 매일 9시',
  metadata: { name: '매일 9시', triggerType: 'SCHEDULE', pipelineId: 5, pipelineName: '일일 집계' },
});
const TRIGGER_TOGGLE = createAuditLog({
  id: 44,
  username: 'trgtoggler',
  actionType: 'STATUS_CHANGE',
  resource: 'pipeline_trigger',
  resourceId: '12',
  description: '트리거 끄기: 매일 9시',
  metadata: { name: '매일 9시', triggerType: 'SCHEDULE', pipelineId: 5, pipelineName: '일일 집계', enabled: false },
});
const TRIGGER_DELETE = createAuditLog({
  id: 45,
  username: 'trgdeleter',
  actionType: 'DELETE',
  resource: 'pipeline_trigger',
  resourceId: '12',
  description: '트리거 삭제: 매일 9시',
});

test.describe('감사 로그 — 파이프라인·트리거(WD-462)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([PIPELINE_UPDATE, PIPELINE_EXECUTE, TRIGGER_CREATE, TRIGGER_TOGGLE, TRIGGER_DELETE]),
    );
  });

  test('파이프라인·트리거 행의 액션·리소스가 한글 라벨로 보인다', async ({ authenticatedPage: page }) => {
    // 미등록 액션·리소스는 console.warn 을 남긴다 — 경고가 없어야 모두 등록된 것이다.
    const warnings: string[] = [];
    page.on('console', (m) => {
      if (m.type() === 'warning' && m.text().startsWith('[AuditLog]')) warnings.push(m.text());
    });
    await page.goto('/admin/audit-logs');
    const row = (name: string) => page.getByRole('row').filter({ hasText: name });
    await expect(row('pipeeditor').getByRole('cell', { name: '수정', exact: true })).toBeVisible();
    await expect(row('pipeeditor').getByRole('cell', { name: '파이프라인', exact: true })).toBeVisible();
    await expect(row('chainowner').getByRole('cell', { name: '실행', exact: true })).toBeVisible();
    await expect(row('trgcreator').getByRole('cell', { name: '생성', exact: true })).toBeVisible();
    await expect(row('trgcreator').getByRole('cell', { name: '트리거', exact: true })).toBeVisible();
    await expect(row('trgtoggler').getByRole('cell', { name: '상태 변경', exact: true })).toBeVisible();
    await expect(row('trgtoggler').getByRole('cell', { name: '트리거', exact: true })).toBeVisible();
    await expect(row('trgdeleter').getByRole('cell', { name: '삭제', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'pipeline_trigger', exact: true })).toHaveCount(0);
    expect(warnings).toEqual([]);
  });

  test('리소스 필터의 「트리거」를 고르면 resource=pipeline_trigger 쿼리가 실린다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    await expect(page.getByRole('row').filter({ hasText: 'trgcreator' })).toBeVisible();
    const req = page.waitForRequest(
      (r) =>
        new URL(r.url()).pathname === '/api/v1/admin/audit-logs' &&
        new URL(r.url()).searchParams.get('resource') === 'pipeline_trigger',
    );
    const filter = page.getByRole('combobox', { name: '리소스 필터' });
    await filter.click();
    await page.getByRole('option', { name: '트리거', exact: true }).click();
    await req;
    await expect(filter).toHaveText('트리거');
  });

  test('실행 상세에 실행 번호와 시작 주체가, 트리거 상세에 리소스 라벨이 보인다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    const dialog = page.getByRole('dialog');

    await page.getByRole('row').filter({ hasText: 'chainowner' }).click();
    await expect(dialog.getByText('파이프라인 실행: 일일 집계 (실행 #120)', { exact: true })).toBeVisible();
    await expect(dialog.getByText('파이프라인 (5)', { exact: true })).toBeVisible();
    await expect(dialog.getByText(/"triggeredBy": "PIPELINE_CHAIN"/)).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    await page.getByRole('row').filter({ hasText: 'trgtoggler' }).click();
    await expect(dialog.getByText('트리거 (12)', { exact: true })).toBeVisible();
    await expect(dialog.getByText('상태 변경', { exact: true })).toBeVisible();
  });
});
