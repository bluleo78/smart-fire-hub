import type { Page } from '@playwright/test';

import type { AnalyticsAdminItem } from '../../../src/types/analytics';
import { createDeleteImpact } from '../../factories/analytics.factory';
import { setupChartListMocks, setupDashboardListMocks, setupQueryListMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 워크스페이스 분석 자산 관리 E2E (WD-199).
 *
 * 대시보드·차트·저장 쿼리의 수정·삭제는 작성자 한정이라, 작성자가 정지·제거되면 그가 공유한 자산을 아무도(관리자도) 정리할 수 없었다.
 * analytics:admin 이 있으면 세 목록 화면의 「워크스페이스 전체」 보기에서 남의 자산을 최소 정보로 보고 공유 해제·삭제만 할 수 있다
 * (WD-192 스마트 작업 「워크스페이스 전체」와 같은 방식). 권한이 없으면 탭 자체가 없다.
 */

function createAdminItem(overrides?: Partial<AnalyticsAdminItem>): AnalyticsAdminItem {
  return {
    id: 10,
    name: '남의 자산',
    ownerId: 99,
    ownerName: '홍길동',
    ownerActive: true,
    isShared: true,
    updatedAt: '2026-10-20T09:00:00',
    ...overrides,
  };
}

/** 관리자 권한(analytics:admin) — 실제 ADMIN 처럼 읽기·쓰기도 함께 준다 */
async function mockAdminPermissions(page: Page) {
  await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['analytics:read', 'analytics:write', 'analytics:admin']);
}

/** 관리자 목록 API 호출 수를 센다 — 권한 없는 사용자 화면에서는 0이어야 한다 */
function countAdminCalls(page: Page) {
  const calls: string[] = [];
  page.on('request', (req) => {
    if (new URL(req.url()).pathname.startsWith('/api/v1/analytics/admin/')) calls.push(req.url());
  });
  return calls;
}

test.describe('분석 자산 「워크스페이스 전체」 보기 (WD-199)', () => {
  test('권한 없는 사용자 — 세 목록 모두 「워크스페이스 전체」 탭이 없고 관리자 API 를 부르지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['analytics:read', 'analytics:write']);
    await setupChartListMocks(page, 1);
    await setupDashboardListMocks(page, 1);
    await setupQueryListMocks(page, 1);
    const calls = countAdminCalls(page);

    for (const [path, mineTab] of [
      ['/analytics/charts', '내 차트'],
      ['/analytics/dashboards', '내 대시보드'],
      ['/analytics/queries', '내 쿼리'],
    ] as const) {
      await page.goto(path);
      await expect(page.getByRole('tab', { name: mineTab })).toBeVisible();
      await expect(page.getByRole('tab', { name: '공유됨' })).toBeVisible();
      await expect(page.getByRole('tab', { name: '워크스페이스 전체' })).toHaveCount(0);
    }
    expect(calls).toHaveLength(0);
  });

  test('관리자 차트 — 작성자·공유 여부와 함께 보이고 공유 해제·삭제가 관리자 API 로 간다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await setupChartListMocks(page, 1);
    await mockAdminPermissions(page);
    const listCapture = await mockApi(
      page,
      'GET',
      '/api/v1/analytics/admin/charts',
      [
        createAdminItem({ id: 10, name: '정지된 사람 공유 차트', ownerName: '홍길동', ownerActive: false, isShared: true }),
        createAdminItem({ id: 11, name: '비공개 차트', ownerName: '김철수', isShared: false }),
      ],
      { capture: true },
    );
    const unshareCapture = await mockApi(page, 'POST', '/api/v1/analytics/admin/charts/10/unshare', {}, { capture: true });
    const impactCapture = await mockApi(
      page,
      'GET',
      '/api/v1/analytics/admin/charts/11/delete-impact',
      createDeleteImpact({ widgetCount: 2, hiddenDashboardCount: 2 }),
      { capture: true },
    );
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/analytics/admin/charts/11', {}, { capture: true });

    await page.goto('/analytics/charts');
    // 기본은 내 차트
    await expect(page.getByRole('table', { name: '차트 목록' })).toBeVisible();

    await page.getByRole('tab', { name: '워크스페이스 전체' }).click();
    await listCapture.waitForRequest();
    const table = page.getByRole('table', { name: '워크스페이스 전체 차트' });
    for (const header of ['이름', '작성자', '공유', '수정일']) {
      await expect(table.getByRole('columnheader', { name: header, exact: true })).toBeVisible();
    }
    // 작성자 목록 표(차트 목록)는 이 보기에서 그리지 않는다
    await expect(page.getByRole('table', { name: '차트 목록' })).toHaveCount(0);

    const row10 = page.getByTestId('workspace-analytics-charts-10');
    await expect(row10).toContainText('정지된 사람 공유 차트');
    await expect(row10).toContainText('홍길동');
    await expect(row10).toContainText('비활성 작성자');
    await expect(row10).toContainText('공유');
    const row11 = page.getByTestId('workspace-analytics-charts-11');
    await expect(row11).toContainText('김철수');
    await expect(row11).toContainText('비공개');
    await expect(row11).not.toContainText('비활성 작성자');

    // 행 동작은 공유 해제·삭제뿐 — 비공개 항목에는 공유 해제가 없다. 편집·보기 버튼도 없다
    await expect(row10.getByRole('button')).toHaveText(['공유 해제', '삭제']);
    await expect(row11.getByRole('button')).toHaveText(['삭제']);

    // 행을 눌러도 상세(남의 비공개 → 404)로 가지 않는다
    await row10.getByText('정지된 사람 공유 차트').click();
    await expect(page).toHaveURL(/\/analytics\/charts$/);

    // 공유 해제 — 확인 창(브라우저 confirm 아님)을 거친다
    await row10.getByRole('button', { name: '정지된 사람 공유 차트 공유 해제' }).click();
    const unshareDialog = page.getByRole('alertdialog');
    await expect(unshareDialog).toContainText('차트 공유 해제');
    await expect(unshareDialog).toContainText('작성자: 홍길동');
    await unshareDialog.getByRole('button', { name: '공유 해제' }).click();
    await unshareCapture.waitForRequest();
    await expect(page.getByText('차트 "정지된 사람 공유 차트"의 공유를 해제했습니다.')).toBeVisible();

    // 삭제 — 관리자 영향 범위 API 로 함께 사라지는 위젯을 보이고, 작성자를 함께 보여 오삭제를 막는다
    await row11.getByRole('button', { name: '비공개 차트 삭제' }).click();
    const deleteDialog = page.getByRole('alertdialog');
    await impactCapture.waitForRequest();
    await expect(deleteDialog).toContainText('작성자: 김철수');
    await expect(deleteDialog.getByTestId('delete-impact')).toContainText('2개');
    await deleteDialog.getByRole('button', { name: '삭제' }).click();
    await deleteCapture.waitForRequest();
    await expect(page.getByText('차트 "비공개 차트"가 삭제되었습니다.')).toBeVisible();
  });

  test('관리자 대시보드 — 일반 삭제 확인 창으로 관리자 삭제 API 를 부른다', async ({ authenticatedPage: page }) => {
    await setupDashboardListMocks(page, 1);
    await mockAdminPermissions(page);
    await mockApi(page, 'GET', '/api/v1/analytics/admin/dashboards', [
      createAdminItem({ id: 20, name: '정지된 사람 대시보드', ownerActive: false }),
    ]);
    const unshareCapture = await mockApi(page, 'POST', '/api/v1/analytics/admin/dashboards/20/unshare', {}, { capture: true });
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/analytics/admin/dashboards/20', {}, { capture: true });

    await page.goto('/analytics/dashboards');
    await page.getByRole('tab', { name: '워크스페이스 전체' }).click();
    const row = page.getByTestId('workspace-analytics-dashboards-20');
    await expect(row).toContainText('비활성 작성자');

    await row.getByRole('button', { name: '정지된 사람 대시보드 공유 해제' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '공유 해제' }).click();
    await unshareCapture.waitForRequest();
    await expect(page.getByText('대시보드 "정지된 사람 대시보드"의 공유를 해제했습니다.')).toBeVisible();

    await row.getByRole('button', { name: '정지된 사람 대시보드 삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toContainText('대시보드 삭제');
    await expect(dialog).toContainText('작성자: 홍길동');
    await dialog.getByRole('button', { name: '삭제' }).click();
    await deleteCapture.waitForRequest();
    await expect(page.getByText('대시보드 "정지된 사람 대시보드"가 삭제되었습니다.')).toBeVisible();
  });

  test('관리자 쿼리 — 함께 삭제되는 남의 차트는 개수만 보이고, 삭제 실패는 한국어 토스트로 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryListMocks(page, 1);
    await mockAdminPermissions(page);
    await mockApi(page, 'GET', '/api/v1/analytics/admin/queries', [
      createAdminItem({ id: 30, name: '정지된 사람 쿼리', isShared: true }),
    ]);
    // 작성자 경로의 영향 조회는 소유자 관문(404) — 관리자 창이 이것을 부르면 「본인이 만든 항목만」으로 막힌다
    const ownerImpactCalls: string[] = [];
    page.on('request', (req) => {
      if (new URL(req.url()).pathname === '/api/v1/analytics/queries/30/delete-impact') ownerImpactCalls.push(req.url());
    });
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/admin/queries/30/delete-impact',
      createDeleteImpact({ charts: [{ id: 1, name: '공유된 차트' }], hiddenChartCount: 2, widgetCount: 3, hiddenDashboardCount: 1 }),
    );
    await mockApi(
      page,
      'DELETE',
      '/api/v1/analytics/admin/queries/30',
      { status: 500, error: 'Internal Server Error', message: '일시적인 오류가 발생했습니다' },
      { status: 500 },
    );

    await page.goto('/analytics/queries');
    await page.getByRole('tab', { name: '워크스페이스 전체' }).click();
    const row = page.getByTestId('workspace-analytics-queries-30');
    await row.getByRole('button', { name: '정지된 사람 쿼리 삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    const impact = dialog.getByTestId('delete-impact');
    await expect(impact).toContainText('이 쿼리로 만든 차트 3개');
    await expect(impact).toContainText('공유된 차트');
    await expect(impact).toContainText('볼 수 없는 차트 2개');
    expect(ownerImpactCalls).toHaveLength(0);

    await dialog.getByRole('button', { name: '삭제' }).click();
    await expect(page.getByText('일시적인 오류가 발생했습니다').first()).toBeVisible();
    // 실패해도 목록 행은 그대로 남는다
    await expect(row).toBeVisible();
  });

  test('관리자 — 이미 지워진 항목의 삭제 창은 확인 버튼을 막고 안내한다', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 1);
    await mockAdminPermissions(page);
    await mockApi(page, 'GET', '/api/v1/analytics/admin/charts', [createAdminItem({ id: 12, name: '지워진 차트' })]);
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/admin/charts/12/delete-impact',
      { status: 404, error: 'Not Found', message: '차트를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.' },
      { status: 404 },
    );

    await page.goto('/analytics/charts');
    await page.getByRole('tab', { name: '워크스페이스 전체' }).click();
    await page.getByTestId('workspace-analytics-charts-12').getByRole('button', { name: '지워진 차트 삭제' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByTestId('delete-not-owner')).toHaveText('이미 삭제되었거나 찾을 수 없는 항목입니다.');
    await expect(dialog.getByRole('button', { name: '삭제' })).toBeDisabled();
  });

  test('관리자 — 목록 오류는 빈 상태가 아니라 불러오기 실패로 보이고, 검색은 이름·작성자로 거른다', async ({
    authenticatedPage: page,
  }) => {
    await setupChartListMocks(page, 1);
    await mockAdminPermissions(page);
    await mockApi(page, 'GET', '/api/v1/analytics/admin/queries', [
      createAdminItem({ id: 31, name: '매출 쿼리', ownerName: '홍길동' }),
      createAdminItem({ id: 32, name: '재고 쿼리', ownerName: '김철수' }),
    ]);
    await setupQueryListMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/analytics/admin/charts', { status: 500, message: '서버 오류' }, { status: 500 });

    await page.goto('/analytics/charts');
    await page.getByRole('tab', { name: '워크스페이스 전체' }).click();
    await expect(page.getByTestId('workspace-analytics-error')).toContainText('워크스페이스 차트 목록을 불러오지 못했습니다');
    await expect(page.getByText('워크스페이스에 차트가 없습니다.')).toHaveCount(0);

    await page.goto('/analytics/queries');
    await page.getByRole('tab', { name: '워크스페이스 전체' }).click();
    await page.getByPlaceholder('이름·작성자 검색...').fill('김철수');
    await expect(page.getByTestId('workspace-analytics-queries-32')).toBeVisible();
    await expect(page.getByTestId('workspace-analytics-queries-31')).toHaveCount(0);
  });
});
