import type { Page } from '@playwright/test';

import { createCategories, createDataset, createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 목록 — 조회 실패·범위 밖 페이지 표시 (WD-146)
 *
 * 예전에는 목록 조회가 실패해도(서버 400·500·403) 「데이터셋이 없습니다」가 떴고, 낡은 북마크(?page=999)는
 * 빈 목록과 「총 15건 중 9991-15」를 보였다. 조회 실패는 오류 안내 + 「다시 시도」로, 범위 밖 페이지는 마지막 페이지로 옮긴다.
 */
test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
});

/** 15건(size 10 → 2페이지)을 page 파라미터대로 돌려주는 목록 모킹. 범위 밖 페이지는 서버처럼 빈 content 를 준다. */
async function mockFifteenDatasets(page: Page) {
  const all = Array.from({ length: 15 }, (_, i) =>
    createDataset({ id: i + 1, name: `데이터셋 ${i + 1}`, tableName: `dataset_${i + 1}` }),
  );
  const requestedPages: string[] = [];
  await page.route(
    (url) => url.pathname === '/api/v1/datasets',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const p = Number(new URL(route.request().url()).searchParams.get('page') ?? '0');
      requestedPages.push(String(p));
      const content = all.slice(p * 10, p * 10 + 10);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createPageResponse(content, { page: p, totalElements: 15, totalPages: 2 })),
      });
    },
  );
  return requestedPages;
}

test.describe('데이터셋 목록 — 조회 실패·범위 밖 페이지 (WD-146)', () => {
  test('범위 밖 페이지 주소(?page=999)는 마지막 페이지로 옮겨 남은 데이터셋을 보인다', async ({ authenticatedPage: page }) => {
    const requestedPages = await mockFifteenDatasets(page);

    await page.goto('/data/datasets?page=999');

    // 마지막 페이지(page=1)로 옮겨 11~15 를 보인다 — 빈 목록 안내가 아니다
    await expect(page).toHaveURL(/[?&]page=1(&|$)/);
    await expect(page.getByText('데이터셋 11', { exact: true })).toBeVisible();
    await expect(page.getByText('데이터셋 15', { exact: true })).toBeVisible();
    await expect(page.getByText('데이터셋이 없습니다.')).toHaveCount(0);
    // 깨진 범위(9991-15) 대신 실제 범위
    await expect(page.getByText('총 15건 중 11-15')).toBeVisible();
    await expect(page.getByText(/9991/)).toHaveCount(0);
    expect(requestedPages).toContain('999');
    expect(requestedPages).toContain('1');
  });

  test('0건이 된 목록의 뒷 페이지 주소는 첫 페이지로 옮겨 빈 목록 안내를 보인다', async ({ authenticatedPage: page }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse([], { totalElements: 0, totalPages: 0 })),
        }),
    );

    await page.goto('/data/datasets?page=3');

    await expect(page).not.toHaveURL(/page=/);
    await expect(page.getByText('데이터셋이 없습니다.')).toBeVisible();
  });

  test('숫자가 아닌 페이지 주소(?page=abc)는 첫 페이지로 읽어 page=0 으로 조회한다', async ({ authenticatedPage: page }) => {
    const requestedPages = await mockFifteenDatasets(page);

    await page.goto('/data/datasets?page=abc');

    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();
    await expect(page.getByText('총 15건 중 1-10')).toBeVisible();
    // 예전에는 page=NaN 을 보내 서버 400 → 빈 목록이었다
    expect(requestedPages.every((p) => p === '0')).toBe(true);
  });

  test('목록 조회가 실패하면 빈 목록 대신 서버 사유와 「다시 시도」를 보이고, 다시 시도하면 목록을 보인다', async ({ authenticatedPage: page }) => {
    let fail = true;
    let calls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        calls += 1;
        if (fail) {
          return route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: '일시적인 서버 오류입니다.' }),
          });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(createDatasets(3))),
        });
      },
    );

    await page.goto('/data/datasets');

    const alert = page.getByRole('alert').filter({ hasText: '일시적인 서버 오류입니다.' });
    await expect(alert).toBeVisible({ timeout: 15000 });
    await expect(page.getByText('데이터셋이 없습니다.')).toHaveCount(0);
    // 오류 상태에서는 「새 데이터셋 만들기」 빈 목록 안내도 없다
    await expect(page.getByRole('button', { name: '새 데이터셋 만들기' })).toHaveCount(0);

    fail = false;
    const before = calls;
    await alert.getByRole('button', { name: '다시 시도' }).click();
    await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: '일시적인 서버 오류입니다.' })).toHaveCount(0);
    expect(calls).toBeGreaterThan(before);
  });

  test('조회 권한 오류(403)는 권한 안내만 보이고 「다시 시도」를 두지 않는다', async ({ authenticatedPage: page }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) =>
        route.fulfill({
          status: 403,
          contentType: 'application/json',
          body: JSON.stringify({ status: 403, error: 'Forbidden', message: 'Access denied' }),
        }),
    );

    await page.goto('/data/datasets');

    const alert = page.getByRole('alert').filter({ hasText: '데이터셋 목록을 볼 권한이 없습니다.' });
    await expect(alert).toBeVisible({ timeout: 15000 });
    await expect(alert.getByRole('button', { name: '다시 시도' })).toHaveCount(0);
    await expect(page.getByText('데이터셋이 없습니다.')).toHaveCount(0);
  });
});
