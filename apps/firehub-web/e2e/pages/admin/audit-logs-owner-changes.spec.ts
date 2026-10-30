import { createAuditLog } from '../../factories/admin.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 감사 로그 — 작성자 본인의 분석 자산·스마트 작업·리포트 양식·카테고리 변경(WD-464)
 * - api 가 새로 남기는 저장 쿼리·차트·대시보드(CREATE/UPDATE/STATUS_CHANGE 공유 켜기·끄기/DELETE),
 *   스마트 작업(STATUS_CHANGE 켜기·끄기), 리포트 양식(resource=report_template), 데이터셋 카테고리(resource=dataset_category)
 *   행이 영문 raw 대신 한글 라벨로 보이는지 검증한다.
 * - 새 리소스 필터 「리포트 양식」·「데이터셋 카테고리」가 resource 쿼리로 실리고, 상세에 바뀐 항목 이름(metadata.changedFields)이 보인다.
 */
const QUERY_UPDATE = createAuditLog({
  id: 51,
  username: 'queryeditor',
  actionType: 'UPDATE',
  resource: 'saved_query',
  resourceId: '7',
  description: '저장 쿼리 수정: 월별 매출 (바뀐 항목: 이름·SQL)',
  metadata: { name: '월별 매출', changedFields: ['name', 'sqlText'] },
});
const DASHBOARD_SHARE = createAuditLog({
  id: 52,
  username: 'dashsharer',
  actionType: 'STATUS_CHANGE',
  resource: 'dashboard',
  resourceId: '3',
  description: '대시보드 공유 켜기: 운영 현황',
  metadata: { name: '운영 현황', changedFields: ['isShared'], isShared: true },
});
const JOB_DISABLE = createAuditLog({
  id: 53,
  username: 'jobowner',
  actionType: 'STATUS_CHANGE',
  resource: 'proactive_job',
  resourceId: '11',
  description: '스마트 작업 끄기: 아침 브리핑',
  metadata: { name: '아침 브리핑', changedFields: ['enabled'], enabled: false },
});
const TEMPLATE_DELETE = createAuditLog({
  id: 54,
  username: 'tplowner',
  actionType: 'DELETE',
  resource: 'report_template',
  resourceId: '21',
  description: '리포트 양식 삭제: 주간 요약',
  metadata: { name: '주간 요약' },
});
const CATEGORY_CREATE = createAuditLog({
  id: 55,
  username: 'catcreator',
  actionType: 'CREATE',
  resource: 'dataset_category',
  resourceId: '4',
  description: '카테고리 생성: 행정',
  metadata: { name: '행정' },
});

test.describe('감사 로그 — 작성자 본인 변경(WD-464)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([QUERY_UPDATE, DASHBOARD_SHARE, JOB_DISABLE, TEMPLATE_DELETE, CATEGORY_CREATE]),
    );
  });

  test('작성자 변경 행의 액션·리소스가 한글 라벨로 보인다', async ({ authenticatedPage: page }) => {
    // 미등록 액션·리소스는 console.warn 을 남긴다 — 경고가 없어야 모두 등록된 것이다.
    const warnings: string[] = [];
    page.on('console', (m) => {
      if (m.type() === 'warning' && m.text().startsWith('[AuditLog]')) warnings.push(m.text());
    });
    await page.goto('/admin/audit-logs');
    const row = (name: string) => page.getByRole('row').filter({ hasText: name });
    await expect(row('queryeditor').getByRole('cell', { name: '수정', exact: true })).toBeVisible();
    await expect(row('queryeditor').getByRole('cell', { name: '저장 쿼리', exact: true })).toBeVisible();
    await expect(row('dashsharer').getByRole('cell', { name: '상태 변경', exact: true })).toBeVisible();
    await expect(row('dashsharer').getByRole('cell', { name: '대시보드', exact: true })).toBeVisible();
    await expect(row('jobowner').getByRole('cell', { name: '상태 변경', exact: true })).toBeVisible();
    await expect(row('jobowner').getByRole('cell', { name: '스마트 작업', exact: true })).toBeVisible();
    await expect(row('tplowner').getByRole('cell', { name: '삭제', exact: true })).toBeVisible();
    await expect(row('tplowner').getByRole('cell', { name: '리포트 양식', exact: true })).toBeVisible();
    await expect(row('catcreator').getByRole('cell', { name: '생성', exact: true })).toBeVisible();
    await expect(row('catcreator').getByRole('cell', { name: '데이터셋 카테고리', exact: true })).toBeVisible();
    // 영문 raw 리소스 값은 보이지 않는다
    await expect(page.getByRole('cell', { name: 'report_template', exact: true })).toHaveCount(0);
    await expect(page.getByRole('cell', { name: 'dataset_category', exact: true })).toHaveCount(0);
    expect(warnings).toEqual([]);
  });

  test('리소스 필터 「리포트 양식」·「데이터셋 카테고리」가 resource 쿼리로 실린다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    await expect(page.getByRole('row').filter({ hasText: 'tplowner' })).toBeVisible();
    const filter = page.getByRole('combobox', { name: '리소스 필터' });

    const templateReq = page.waitForRequest(
      (r) =>
        new URL(r.url()).pathname === '/api/v1/admin/audit-logs' &&
        new URL(r.url()).searchParams.get('resource') === 'report_template',
    );
    await filter.click();
    await page.getByRole('option', { name: '리포트 양식', exact: true }).click();
    await templateReq;
    await expect(filter).toHaveText('리포트 양식');

    const categoryReq = page.waitForRequest(
      (r) =>
        new URL(r.url()).pathname === '/api/v1/admin/audit-logs' &&
        new URL(r.url()).searchParams.get('resource') === 'dataset_category',
    );
    await filter.click();
    await page.getByRole('option', { name: '데이터셋 카테고리', exact: true }).click();
    await categoryReq;
    await expect(filter).toHaveText('데이터셋 카테고리');
  });

  test('상세에 설명·리소스 라벨과 바뀐 항목 이름이 보인다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    const dialog = page.getByRole('dialog');

    await page.getByRole('row').filter({ hasText: 'queryeditor' }).click();
    await expect(dialog.getByText('저장 쿼리 수정: 월별 매출 (바뀐 항목: 이름·SQL)', { exact: true })).toBeVisible();
    await expect(dialog.getByText('저장 쿼리 (7)', { exact: true })).toBeVisible();
    await expect(dialog.getByText(/"changedFields": \[/)).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    await page.getByRole('row').filter({ hasText: 'dashsharer' }).click();
    await expect(dialog.getByText('대시보드 공유 켜기: 운영 현황', { exact: true })).toBeVisible();
    await expect(dialog.getByText('대시보드 (3)', { exact: true })).toBeVisible();
  });
});
