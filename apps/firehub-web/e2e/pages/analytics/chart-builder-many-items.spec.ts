/**
 * 차트 빌더 — 저장 쿼리·대시보드가 서버 목록 상한(100)을 넘을 때의 선택 목록 E2E 테스트 (#737)
 *
 * 서버(SavedQueryController·AnalyticsDashboardController)는 목록 size 를 100 으로 조용히 자른다.
 * 예전 차트 빌더는 size=100 첫 페이지만 써서 101번째 이후 저장 쿼리·대시보드가 목록에서 빠졌다.
 * 이 모킹은 서버의 상한·페이지 분할을 흉내 내어 두 번째 페이지 항목까지 보이고 고를 수 있는지 검증한다.
 */

import type { Page } from '@playwright/test';

import {
  createChart,
  createDashboardListItem,
  createSavedQueryList,
} from '../../factories/analytics.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 서버 목록 조회 상한 — 두 컨트롤러의 Math.min(size, 100) 과 같게 둔다. */
const SERVER_MAX_SIZE = 100;

/** 서버처럼 size 를 100 으로 자르고 page 에 맞는 조각을 돌려주는 목록 모킹 */
async function mockPagedList<T>(page: Page, path: string, all: T[]) {
  const requestedSizes: number[] = [];
  await page.route(
    (url) => url.pathname === path,
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const params = new URL(route.request().url()).searchParams;
      const reqSize = Number(params.get('size') ?? 20);
      requestedSizes.push(reqSize);
      const size = Math.max(1, Math.min(reqSize, SERVER_MAX_SIZE));
      const pageNo = Math.max(0, Number(params.get('page') ?? 0));
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content: all.slice(pageNo * size, pageNo * size + size),
          page: pageNo,
          size,
          totalElements: all.length,
          totalPages: Math.ceil(all.length / size),
        }),
      });
    },
  );
  return requestedSizes;
}

/** 저장 쿼리 102개(101·102 는 두 번째 페이지에만 있음) */
const ALL_QUERIES = createSavedQueryList(102);
/** 대시보드 102개(101·102 는 두 번째 페이지에만 있음) */
const ALL_DASHBOARDS = Array.from({ length: 102 }, (_, i) =>
  createDashboardListItem({ id: i + 1, name: `대시보드 ${i + 1}` }),
);

test.describe('차트 빌더 — 저장 쿼리·대시보드 100개 초과 (#737)', () => {
  test('두 번째 페이지의 저장 쿼리를 쓰는 기존 차트는 쿼리 칸에 그 이름이 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, savedQueryId: 102 }));
    const requestedSizes = await mockPagedList(page, '/api/v1/analytics/queries', ALL_QUERIES);

    await page.goto('/analytics/charts/1');

    await expect(page.getByRole('combobox', { name: '저장된 쿼리' })).toHaveText('저장 쿼리 102');
    expect(requestedSizes.every((s) => s <= SERVER_MAX_SIZE)).toBe(true);
  });

  test('"대시보드에 추가" 다이얼로그에 두 번째 페이지 대시보드도 보이고 골라 추가된다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, chartType: 'BAR' }));
    await mockPagedList(page, '/api/v1/analytics/queries', ALL_QUERIES);
    await mockPagedList(page, '/api/v1/analytics/dashboards', ALL_DASHBOARDS);
    const widgetCapture = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/dashboards/101/widgets',
      { id: 999, chartId: 1, positionX: 0, positionY: 0, width: 6, height: 4 },
      { capture: true },
    );
    // 추가 후 이동하는 대시보드 상세는 이 테스트의 관심사가 아니므로 최소 응답만 둔다
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/101', {
      id: 101,
      name: '대시보드 101',
      description: null,
      isShared: false,
      autoRefreshSeconds: null,
      widgets: [],
      createdByName: '테스트',
      createdBy: 1,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-01T00:00:00Z',
    });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/101/data', { dashboardId: 101, widgets: [] });

    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '대시보드에 추가' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('대시보드 1', { exact: true })).toBeVisible();
    await dialog.getByText('대시보드 101', { exact: true }).click();
    await dialog.getByRole('button', { name: '추가' }).click();

    const req = await widgetCapture.waitForRequest();
    expect(req.payload).toMatchObject({ chartId: 1 });
  });
});
