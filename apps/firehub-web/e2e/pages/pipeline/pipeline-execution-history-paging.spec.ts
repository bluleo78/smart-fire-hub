import type { Route } from '@playwright/test';

import { createExecution } from '../../factories/pipeline.factory';
import { createPageResponse } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/**
 * 파이프라인 실행 이력 페이지 나눔 (WD-532)
 * - 예전에는 실행 이력 API 가 전체를 배열로 내려주고 화면도 받은 행을 전부 그려, 실행이 쌓일수록(2천 건이면 DOM 3만 노드)
 *   응답·화면이 끝없이 커졌다.
 * - 이제 화면은 page/size 로 한 페이지(20건)만 받아 그리고, 페이지 나눔과 「총 N건」을 보인다.
 */
const TOTAL = 45;
const SIZE = 20;

/** 최신순 실행 id — 1페이지는 45..26, 2페이지는 25..6, 3페이지는 5..1 */
function pageOf(page: number) {
  const ids = Array.from({ length: TOTAL }, (_, i) => TOTAL - i).slice(page * SIZE, (page + 1) * SIZE);
  return createPageResponse(
    ids.map((id) => createExecution({ id, pipelineId: 1 })),
    { page, size: SIZE, totalElements: TOTAL },
  );
}

test.describe('파이프라인 실행 이력 페이지 나눔 (WD-532)', () => {
  test('한 페이지 20건만 그리고 총 건수를 보이며, 다음 페이지는 page 파라미터로 요청한다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    // 기본 목보다 나중에 등록해 먼저 잡는다 — 요청한 page·size 를 기록하고 그 페이지를 돌려준다.
    const requested: { page: string | null; size: string | null }[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1/executions',
      (route: Route) => {
        const url = new URL(route.request().url());
        const p = url.searchParams.get('page');
        requested.push({ page: p, size: url.searchParams.get('size') });
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pageOf(Number(p ?? 0))),
        });
      },
    );

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();

    // 머리글 1행 + 데이터 20행 — 45건 전부가 아니다
    await expect(page.getByRole('row')).toHaveCount(1 + SIZE);
    await expect(page.getByRole('link', { name: '45', exact: true })).toBeVisible();
    await expect(page.getByText(`총 ${TOTAL}건`)).toBeVisible();
    expect(requested[0]).toEqual({ page: '0', size: String(SIZE) });

    // 2페이지로 넘기면 page=1 로 요청하고 그 페이지의 실행을 그린다
    await page.getByRole('button', { name: '2 페이지' }).click();
    await expect(page.getByRole('link', { name: '25', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '45', exact: true })).toHaveCount(0);
    expect(requested.some((r) => r.page === '1' && r.size === String(SIZE))).toBe(true);

    // 마지막 페이지는 남은 5건
    await page.getByRole('button', { name: '3 페이지' }).click();
    await expect(page.getByRole('link', { name: '1', exact: true })).toBeVisible();
    await expect(page.getByRole('row')).toHaveCount(1 + 5);
  });

  test('실행이 한 페이지 안이면 페이지 나눔을 그리지 않는다', async ({ authenticatedPage: page }) => {
    // 기본 목: 실행 2건
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();

    await expect(page.getByRole('row')).toHaveCount(3);
    await expect(page.getByRole('navigation', { name: '페이지네이션' })).toHaveCount(0);
  });

  test('보던 페이지가 비면(이력이 줄어듦) 마지막 페이지로 옮긴다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    // 처음에는 45건(3페이지), 3페이지를 연 뒤에는 이력이 22건(2페이지)으로 줄어 3페이지가 빈 응답이 된다.
    let shrunk = false;
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1/executions',
      (route: Route) => {
        const p = Number(new URL(route.request().url()).searchParams.get('page') ?? 0);
        const total = shrunk ? 22 : TOTAL;
        const ids = Array.from({ length: total }, (_, i) => total - i).slice(p * SIZE, (p + 1) * SIZE);
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            createPageResponse(
              ids.map((id) => createExecution({ id, pipelineId: 1 })),
              { page: p, size: SIZE, totalElements: total },
            ),
          ),
        });
      },
    );

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();
    await page.getByRole('button', { name: '2 페이지' }).click();
    await expect(page.getByRole('link', { name: '25', exact: true })).toBeVisible();

    shrunk = true;
    await page.getByRole('button', { name: '3 페이지' }).click();
    // 3페이지는 비어 있으므로 마지막(2페이지 — 22건 중 2건)으로 옮겨 그린다
    await expect(page.getByRole('link', { name: '2', exact: true })).toBeVisible();
    await expect(page.getByText('실행 기록이 없습니다.')).toHaveCount(0);
  });
});
