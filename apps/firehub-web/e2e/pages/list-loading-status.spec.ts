import type { Page } from '@playwright/test';

import { setupAdminAuth, setupUserListMocks } from '../fixtures/admin.fixture';
import { mockProactivePermissions, setupJobListMocks } from '../fixtures/ai-insight.fixture';
import { setupChartListMocks, setupDashboardListMocks } from '../fixtures/analytics.fixture';
import { expect, test } from '../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetMocks } from '../fixtures/dataset.fixture';
import { grantPipelinePermissions, setupPipelineMocks } from '../fixtures/pipeline.fixture';

/**
 * 목록 로딩 스켈레톤의 로딩 상태 (WD-518, WCAG 4.1.2·4.1.3)
 *
 * 공용 TableSkeletonRows 가 그리는 스켈레톤은 빈 셀뿐이라 스크린리더가 로딩 중임을 알 수 없었다.
 * 목록 요청이 끝나기 전에는 표가 aria-busy="true" 이고 스켈레톤 행은 보조기기에서 숨겨지며,
 * 응답이 오면 aria-busy 가 빠지고 실제 행이 그려지는지 화면마다 확인한다.
 */

/**
 * 목록 GET 요청을 붙잡아 두는 라우트 — 나중에 등록한 라우트가 먼저 잡히므로 기존 모킹보다 앞선다.
 * 돌려준 release() 를 부르면 붙잡은 요청을 기존 모킹(fallback)으로 넘긴다.
 */
async function holdListRequest(page: Page, path: string) {
  let release!: () => void;
  const released = new Promise<void>((resolve) => (release = resolve));
  await page.route(
    (url) => url.pathname === path,
    async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      await released;
      return route.fallback();
    },
  );
  return release;
}

/**
 * 응답 전에는 표가 aria-busy="true" 이고 스켈레톤 행(빈 셀)이 보조기기에서 숨겨져 있는지,
 * 응답 뒤에는 aria-busy 가 빠지고 실제 행이 보이는지 확인한다.
 */
async function expectBusyThenRows(page: Page, release: () => void, tableName: string, firstRowText: string) {
  const table = page.getByRole('table', { name: tableName, exact: true });
  await expect(table).toHaveAttribute('aria-busy', 'true');
  const skeletonRows = table.locator('tbody tr');
  await expect(skeletonRows.first()).toHaveAttribute('aria-hidden', 'true');
  await expect(skeletonRows.first().locator('[data-slot="skeleton"]').first()).toBeVisible();

  release();
  await expect(table.getByText(firstRowText).first()).toBeVisible();
  await expect(table).not.toHaveAttribute('aria-busy');
  await expect(table.locator('tbody tr[aria-hidden="true"]')).toHaveCount(0);
}

test.describe('목록 로딩 안내 (WD-518)', () => {
  test('차트 목록 — 불러오는 동안 표가 aria-busy 이고, 응답 뒤 풀린다', async ({
    authenticatedPage: page,
  }) => {
    await setupChartListMocks(page, 2);
    const release = await holdListRequest(page, '/api/v1/analytics/charts');
    await page.goto('/analytics/charts');
    await expectBusyThenRows(page, release, '차트 목록', '테스트 차트 1');
  });

  test('대시보드 목록 — 불러오는 동안 aria-busy, 응답 뒤 실제 행', async ({ authenticatedPage: page }) => {
    await setupDashboardListMocks(page, 2);
    const release = await holdListRequest(page, '/api/v1/analytics/dashboards');
    await page.goto('/analytics/dashboards');
    await expectBusyThenRows(page, release, '대시보드 목록', '테스트 대시보드 1');
  });

  test('데이터셋 목록 — 불러오는 동안 aria-busy, 응답 뒤 실제 행', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
    await setupDatasetMocks(page);
    const release = await holdListRequest(page, '/api/v1/datasets');
    await page.goto('/data/datasets');
    await expectBusyThenRows(page, release, '데이터셋 목록', '데이터셋 1');
  });

  test('파이프라인 목록 — 불러오는 동안 aria-busy, 응답 뒤 실제 행', async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
    await setupPipelineMocks(page, 2);
    const release = await holdListRequest(page, '/api/v1/pipelines');
    await page.goto('/pipelines');
    await expectBusyThenRows(page, release, '파이프라인 목록', '파이프라인 1');
  });

  test('스마트 작업 목록 — 불러오는 동안 aria-busy, 응답 뒤 실제 행', async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
    await setupJobListMocks(page, 2);
    const release = await holdListRequest(page, '/api/v1/proactive/jobs');
    await page.goto('/ai-insights/jobs');
    await expectBusyThenRows(page, release, '스마트 작업 목록', '잡 1');
  });

  test('사용자 목록 — 불러오는 동안 aria-busy, 응답 뒤 실제 행', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupUserListMocks(page, 2);
    const release = await holdListRequest(page, '/api/v1/users');
    await page.goto('/admin/users');
    await expectBusyThenRows(page, release, '사용자 목록', '사용자 1');
  });
});
