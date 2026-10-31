/**
 * 주소의 id 가 숫자가 아닐 때 — 화면이 깨지지 않고 없는 id 와 같게 안내한다 (WD-434)
 *
 * `Number('abc')` = NaN 이 실행 상세에서는 `NaN !== NaN` 렌더 중 상태 조정으로 무한 렌더(앱 전체 오류 화면)를,
 * API 연결 상세에서는 조회가 꺼진 빈 화면을, 차트·쿼리에서는 `parseInt` 의 NaN 으로 「새로 만들기」 + 「NaN」 글자를,
 * `parseInt('15abc')` = 15 로 다른 항목 편집기를 열었다. 공용 parseRouteId 로 숫자만 받게 고쳤다.
 */

import type { Page } from '@playwright/test';

import { setupAdminAuth, setupApiConnectionListMocks } from '../fixtures/admin.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupPipelineEditorMocks } from '../fixtures/pipeline.fixture';

/** 주어진 경로의 상세 조회 API 가 불렸는지 기록한다 — 숫자가 아닌 id 로는 조회하지 않아야 한다 */
async function trackRequest(page: Page, pattern: string) {
  const hit = { called: false };
  await page.route(pattern, (route) => {
    hit.called = true;
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{"message":"Not found"}' });
  });
  return hit;
}

test.describe('숫자가 아닌 주소 id (WD-434)', () => {
  test('실행 상세 /pipelines/1/executions/abc — 오류 화면 없이 안내 후 파이프라인으로 돌아간다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    const pageErrors: string[] = [];
    page.on('pageerror', (e) => pageErrors.push(String(e)));
    const execDetail = await trackRequest(page, '**/api/v1/pipelines/1/executions/abc');

    await page.goto('/pipelines/1/executions/abc');

    await expect(page.getByText('실행 정보를 불러오는데 실패했습니다.')).toBeVisible();
    await expect(page).toHaveURL(/\/pipelines\/1$/);
    await expect(page.getByText('페이지를 불러오는 중 문제가 발생했습니다')).toHaveCount(0);
    expect(pageErrors.filter((e) => /Too many re-renders/.test(e))).toEqual([]);
    expect(execDetail.called).toBe(false);
  });

  test('API 연결 상세 /admin/api-connections/abc — 빈 화면 대신 안내 후 목록으로 간다', async ({
    authenticatedPage: page,
  }) => {
    // 관리 화면 권한 가드 통과용 — api-connections.spec.ts 와 같다
    await setupAdminAuth(page);
    await setupApiConnectionListMocks(page);
    const detail = await trackRequest(page, '**/api/v1/api-connections/abc');

    await page.goto('/admin/api-connections/abc');

    await expect(page.getByText('연결 정보를 불러오는데 실패했습니다.')).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/api-connections$/);
    expect(detail.called).toBe(false);
  });

  for (const raw of ['abc', '15abc']) {
    test(`차트 /analytics/charts/${raw} — 새 차트·「NaN」·다른 차트 대신 「차트를 찾을 수 없습니다」`, async ({
      authenticatedPage: page,
    }) => {
      // 15abc 를 15 로 읽으면 이 차트가 열린다 — 열리지 않아야 한다
      const chart15 = await trackRequest(page, '**/api/v1/analytics/charts/15');
      await mockApi(page, 'GET', '/api/v1/analytics/queries', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

      await page.goto(`/analytics/charts/${raw}`);

      await expect(page.getByText('차트를 찾을 수 없습니다.')).toBeVisible();
      await expect(page.getByRole('button', { name: '목록으로' })).toBeVisible();
      await expect(page.getByText('새 차트')).toHaveCount(0);
      await expect(page.getByText('NaN')).toHaveCount(0);
      expect(chart15.called).toBe(false);
    });
  }

  for (const raw of ['abc', '15abc']) {
    test(`저장 쿼리 /analytics/queries/${raw} — 새 쿼리 편집기 대신 「쿼리를 찾을 수 없습니다」`, async ({
      authenticatedPage: page,
    }) => {
      const query15 = await trackRequest(page, '**/api/v1/analytics/queries/15');
      await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', { tables: [] });
      await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);

      await page.goto(`/analytics/queries/${raw}`);

      await expect(page.getByText('쿼리를 찾을 수 없습니다.')).toBeVisible();
      await expect(page.getByText('새 쿼리')).toHaveCount(0);
      expect(query15.called).toBe(false);
    });
  }
});
