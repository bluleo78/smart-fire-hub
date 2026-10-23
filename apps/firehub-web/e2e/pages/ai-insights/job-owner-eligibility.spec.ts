import type { ProactiveJobAdminSummary } from '../../../src/api/proactive';
import { createJob } from '../../factories/ai-insight.factory';
import { mockProactivePermissions, setupJobDetailMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 작성자 실행 자격 표시 + 워크스페이스 관리자 보기 E2E (WD-192).
 *
 * - 작성자가 정지·제거되거나 스마트 작업 쓰기 권한을 잃으면 작업은 「활성」인 채 실행 때마다 건너뛴다(WD-166 — 자동으로 끄지 않는다).
 *   예전엔 목록·상세가 정상처럼 보였으므로 서버가 실은 ownerEligible=false 를 「작성자 권한 없음 — 실행되지 않음」으로 보인다.
 * - proactive:admin 이 있으면 「워크스페이스 전체」 보기에서 남의 작업을 최소 정보로 보고 끄거나 삭제만 할 수 있다.
 */

const NO_PERMISSION_LABEL = '작성자 권한 없음 — 실행되지 않음';
const INACTIVE_LABEL = '작성자 정지·제거 — 실행되지 않음';

function createAdminRow(overrides?: Partial<ProactiveJobAdminSummary>): ProactiveJobAdminSummary {
  return {
    id: 10,
    name: '남의 작업',
    ownerName: '홍길동',
    enabled: true,
    ownerEligible: true,
    ownerIneligibleReason: null,
    nextExecuteAt: null,
    timezone: 'Asia/Seoul',
    lastExecutionStatus: null,
    ...overrides,
  };
}

/** 관리자 권한(proactive:admin) — 실제 ADMIN 처럼 읽기·쓰기도 함께 준다 */
async function mockAdminPermissions(page: import('@playwright/test').Page) {
  await mockApi(page, 'GET', '/api/v1/auth/me/permissions', [
    'proactive:read',
    'proactive:write',
    'proactive:admin',
  ]);
}

test.describe('작성자 실행 자격 표시 (WD-192)', () => {
  test('목록 — 자격 없는 작업에만 「실행되지 않음」이 보이고 사유별 문구가 다르다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 1, name: '정상 작업' }),
      createJob({ id: 2, name: '권한 잃은 작업', ownerEligible: false, ownerIneligibleReason: 'OWNER_NO_PERMISSION' }),
      createJob({ id: 3, name: '정지된 작업', ownerEligible: false, ownerIneligibleReason: 'OWNER_INACTIVE' }),
      // 판정 값이 없는 응답(구버전 서버)은 조용히 둔다
      createJob({ id: 4, name: '판정 없는 작업', ownerEligible: undefined, ownerIneligibleReason: undefined }),
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockProactivePermissions(page, { canWrite: false });

    await page.goto('/ai-insights/jobs');

    const table = page.getByRole('table', { name: '스마트 작업 목록' });
    const row = (name: string) => table.getByRole('row').filter({ hasText: name });
    await expect(row('권한 잃은 작업').getByTestId('owner-ineligible')).toHaveText(NO_PERMISSION_LABEL);
    await expect(row('정지된 작업').getByTestId('owner-ineligible')).toHaveText(INACTIVE_LABEL);
    await expect(row('정상 작업').getByTestId('owner-ineligible')).toHaveCount(0);
    await expect(row('판정 없는 작업').getByTestId('owner-ineligible')).toHaveCount(0);
    await expect(table.getByTestId('owner-ineligible')).toHaveCount(2);
  });

  test('상세 — 쓰기 권한 잃은 작성자: 배지가 보이고 끄기·삭제가 각자 API 로 간다', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1',
      createJob({ id: 1, ownerEligible: false, ownerIneligibleReason: 'OWNER_NO_PERMISSION' }),
    );
    await mockProactivePermissions(page, { canWrite: false });
    const disableCapture = await mockApi(page, 'POST', '/api/v1/proactive/jobs/1/disable', {}, { capture: true });
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/proactive/jobs/1', {}, { capture: true });

    await page.goto('/ai-insights/jobs/1');

    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();
    await expect(page.getByTestId('owner-ineligible')).toHaveText(NO_PERMISSION_LABEL);
    // 편집·지금 실행·복제는 없다 — 끄기·삭제만
    for (const name of ['편집', '지금 실행', '복제']) {
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0);
    }

    await page.getByRole('button', { name: '끄기', exact: true }).click();
    await disableCapture.waitForRequest();
    await expect(page.getByText('작업이 비활성화되었습니다.')).toBeVisible();

    // 삭제는 기존 확인 다이얼로그를 거친다
    await page.getByRole('button', { name: '삭제', exact: true }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toContainText('매일 현황 리포트');
    await dialog.getByRole('button', { name: '삭제' }).click();
    await deleteCapture.waitForRequest();
    await expect(page).toHaveURL(/\/ai-insights\/jobs$/);
  });

  test('상세 — 꺼진 작업에는 끄기 버튼이 없다(켜기는 쓰기 권한 필요)', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1, enabled: false }));
    await mockProactivePermissions(page, { canWrite: false });

    await page.goto('/ai-insights/jobs/1');

    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();
    await expect(page.getByRole('button', { name: '끄기', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toBeVisible();
    // 정상 작업에는 자격 배지가 없다
    await expect(page.getByTestId('owner-ineligible')).toHaveCount(0);
  });
});

test.describe('워크스페이스 전체 보기 — 관리자 (WD-192)', () => {
  test('관리 권한이 없으면 「워크스페이스 전체」 탭이 없고 관리자 목록을 부르지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 1, name: '내 작업' })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockProactivePermissions(page, { canWrite: true });
    let adminListCalls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/admin/jobs',
      (route) => {
        adminListCalls += 1;
        return route.fulfill({ status: 403, body: '{}' });
      },
    );

    // 주소로 보기를 지정해도 관리자 보기로 열리지 않는다
    await page.goto('/ai-insights/jobs?view=workspace');

    await expect(page.getByRole('table', { name: '스마트 작업 목록' }).getByText('내 작업')).toBeVisible();
    await expect(page.getByRole('tab', { name: '워크스페이스 전체' })).toHaveCount(0);
    expect(adminListCalls).toBe(0);
  });

  test('관리자 — 남의 작업이 작성자·실행 자격과 함께 보이고 끄기·삭제가 관리자 API 로 간다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 1, name: '관리자 본인 작업' })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockAdminPermissions(page);
    const listCapture = await mockApi(
      page,
      'GET',
      '/api/v1/proactive/admin/jobs',
      [
        createAdminRow({
          id: 10,
          name: '권한 잃은 사람 작업',
          ownerName: '홍길동',
          ownerEligible: false,
          ownerIneligibleReason: 'OWNER_NO_PERMISSION',
          lastExecutionStatus: 'FAILED',
        }),
        createAdminRow({ id: 11, name: '꺼진 작업', ownerName: '김철수', enabled: false, lastExecutionStatus: 'COMPLETED' }),
      ],
      { capture: true },
    );
    const disableCapture = await mockApi(page, 'POST', '/api/v1/proactive/admin/jobs/10/disable', {}, { capture: true });
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/proactive/admin/jobs/11', {}, { capture: true });

    await page.goto('/ai-insights/jobs');
    // 기본은 내 작업
    await expect(page.getByRole('table', { name: '스마트 작업 목록' }).getByText('관리자 본인 작업')).toBeVisible();

    await page.getByRole('tab', { name: '워크스페이스 전체' }).click();
    await expect(page).toHaveURL(/view=workspace/);
    await listCapture.waitForRequest();

    const table = page.getByRole('table', { name: '워크스페이스 전체 스마트 작업' });
    await expect(table.getByRole('columnheader', { name: '작성자' })).toBeVisible();
    await expect(table.getByRole('columnheader', { name: '실행 자격' })).toBeVisible();
    const row10 = page.getByTestId('workspace-job-10');
    await expect(row10).toContainText('권한 잃은 사람 작업');
    await expect(row10).toContainText('홍길동');
    await expect(page.getByTestId('workspace-job-10-ineligible')).toHaveText(NO_PERMISSION_LABEL);
    await expect(row10).toContainText('실패');
    const row11 = page.getByTestId('workspace-job-11');
    await expect(row11).toContainText('김철수');
    await expect(row11).toContainText('정상');
    await expect(row11).toContainText('성공');
    await expect(row11).toContainText('비활성');

    // 행 동작은 끄기·삭제뿐 — 켜기·편집·지금 실행은 없다. 꺼진 작업에는 끄기도 없다
    await expect(row10.getByRole('button')).toHaveText(['끄기', '삭제']);
    await expect(row11.getByRole('button')).toHaveText(['삭제']);
    await expect(table.getByRole('switch')).toHaveCount(0);

    // 행을 눌러도 상세(남의 작업 → 404)로 가지 않는다
    await row10.getByText('권한 잃은 사람 작업').click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\?view=workspace$/);

    await row10.getByRole('button', { name: '권한 잃은 사람 작업 끄기' }).click();
    await disableCapture.waitForRequest();
    await expect(page.getByText('"권한 잃은 사람 작업" 작업을 껐습니다.')).toBeVisible();

    // 삭제는 기존 확인 다이얼로그 — 작성자를 함께 보여 오삭제를 막는다
    await row11.getByRole('button', { name: '꺼진 작업 삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toContainText('꺼진 작업');
    await expect(dialog).toContainText('작성자: 김철수');
    await dialog.getByRole('button', { name: '삭제' }).click();
    await deleteCapture.waitForRequest();
    await expect(page.getByText('"꺼진 작업" 작업을 삭제했습니다.')).toBeVisible();
  });

  test('관리자 — 관리자 목록 오류는 빈 상태가 아니라 불러오기 실패로 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockAdminPermissions(page);
    await mockApi(page, 'GET', '/api/v1/proactive/admin/jobs', { status: 500, message: '서버 오류' }, { status: 500 });

    await page.goto('/ai-insights/jobs?view=workspace');

    await expect(page.getByTestId('workspace-jobs-error')).toContainText('워크스페이스 작업 목록을 불러오지 못했습니다');
    await expect(page.getByText('워크스페이스에 스마트 작업이 없습니다.')).toHaveCount(0);
  });
});
