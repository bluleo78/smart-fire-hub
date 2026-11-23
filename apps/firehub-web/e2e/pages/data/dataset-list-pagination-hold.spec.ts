import type { Page } from '@playwright/test';

import { createCategories, createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';
import { failPageRequests, holdRequests } from '../../fixtures/hold-api';
import { isUnderInert } from '../../fixtures/inert-a11y';

/**
 * 데이터셋 목록 쪽 이동 유지 (WD-982)
 * - 이동 응답을 기다리는 동안 페이지네이션·키보드 초점이 남고, 이전 행은 흐려지고 눌리지 않는다.
 * - 이동 중 검색어·쪽 크기를 바꾸면 이전 조건의 행이 남지 않는다.
 * - 다음 쪽 조회가 실패해도 페이지네이션이 남아 이전 쪽으로 돌아갈 수 있다.
 */
const API = '/api/v1/datasets';
const firstRowName = '데이터셋 1쪽 행';

test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
});

/** 쪽마다 다른 행 하나를 돌려주는 전체 3쪽짜리 목록 모킹 — 요청의 page 쿼리를 읽어 응답한다 */
async function mockPagedList(page: Page) {
  await page.route(
    (url) => url.pathname === API,
    async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const p = Number(new URL(route.request().url()).searchParams.get('page') ?? '0');
      const row = createDataset({ id: p + 1, name: `데이터셋 ${p + 1}쪽 행`, tableName: `ds_${p + 1}` });
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createPageResponse([row], { page: p, size: 10, totalElements: 25, totalPages: 3 })),
      });
    },
  );
}

test('쪽 이동 중에도 페이지네이션과 초점이 남는다 (WD-982)', async ({ authenticatedPage: page }) => {
  await mockPagedList(page);
  await page.goto('/data/datasets');
  const nav = page.getByRole('navigation', { name: '페이지네이션' });
  const next = nav.getByRole('button', { name: '다음 페이지' });
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

  const held = await holdRequests(page, API, 'GET');
  await next.focus();
  await page.keyboard.press('Enter');
  await expect.poll(held.count).toBeGreaterThan(0);
  await expect(nav).toBeVisible();
  await expect(next).toBeFocused();
  const table = page.getByRole('table', { name: '데이터셋 목록' });
  await expect(table).toHaveAttribute('aria-busy', 'true');
  // 이전 행은 보이되 키보드로 닿지 않는다(tbody inert)
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();
  expect(await isUnderInert(page.getByText(firstRowName, { exact: true }))).toBe(true);

  held.release();
  await expect(page.getByText('데이터셋 2쪽 행', { exact: true })).toBeVisible();
  await expect(table).not.toHaveAttribute('aria-busy', 'true');
  await expect(next).toBeFocused();
});

test('쪽 이동 중 검색어를 바꾸면 이전 행이 남지 않는다 (WD-982)', async ({ authenticatedPage: page }) => {
  await mockPagedList(page);
  await page.goto('/data/datasets');
  const nav = page.getByRole('navigation', { name: '페이지네이션' });
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

  const held = await holdRequests(page, API, 'GET');
  await nav.getByRole('button', { name: '다음 페이지' }).click();
  await expect.poll(held.count).toBeGreaterThan(0);
  const before = held.count();
  await page.getByPlaceholder('데이터셋 검색...').fill('다른 조건');
  await expect.poll(held.count).toBeGreaterThan(before);
  await expect(page.getByText(firstRowName, { exact: true })).toHaveCount(0);
  held.release();
});

test('쪽 이동 중 쪽 크기를 바꾸면 이전 행이 남지 않는다 (WD-982)', async ({ authenticatedPage: page }) => {
  await mockPagedList(page);
  await page.goto('/data/datasets');
  const nav = page.getByRole('navigation', { name: '페이지네이션' });
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

  const held = await holdRequests(page, API, 'GET');
  await nav.getByRole('button', { name: '다음 페이지' }).click();
  await expect.poll(held.count).toBeGreaterThan(0);
  const before = held.count();
  await page.getByRole('combobox', { name: '페이지 사이즈' }).click();
  await page.getByRole('option', { name: '20', exact: true }).click();
  await expect.poll(held.count).toBeGreaterThan(before);
  await expect(page.getByText(firstRowName, { exact: true })).toHaveCount(0);
  held.release();
});

test('다음 쪽이 실패해도 페이지네이션이 남고 이전 쪽으로 돌아간다 (WD-982)', async ({ authenticatedPage: page }) => {
  await mockPagedList(page);
  await page.goto('/data/datasets');
  const nav = page.getByRole('navigation', { name: '페이지네이션' });
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

  // 2쪽(page=1)만 500 — 나중에 등록한 route 가 먼저 받는다
  await failPageRequests(page, API, 1);
  // 키보드(초점+Enter)로 이동 — 실패해도 nav 가 다시 마운트되지 않아 초점이 남아야 한다 (WD-982)
  const next = nav.getByRole('button', { name: '다음 페이지' });
  await next.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(nav).toBeVisible();
  await expect(next).toBeFocused();

  await nav.getByRole('button', { name: '이전 페이지' }).click();
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('다음 쪽이 500 → 다시 시도 → 다시 500 → 초점이 「다시 시도」 버튼으로 돌아온다 (WD-982)', async ({
  authenticatedPage: page,
}) => {
  await mockPagedList(page);
  await page.goto('/data/datasets');
  const nav = page.getByRole('navigation', { name: '페이지네이션' });
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

  // 2쪽(page=1)은 계속 500 — 나중에 등록한 route 가 먼저 받는다
  await failPageRequests(page, API, 1);
  await nav.getByRole('button', { name: '다음 페이지' }).click();
  await expect(page.getByRole('alert')).toBeVisible();

  // 다시 시도 중 placeholder 로 이전 쪽 행이 isPending=false 로 보여도, 다시 실패하면 초점이 버튼으로 돌아와야 한다(WD-987)
  await page.getByRole('button', { name: '다시 시도' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByRole('button', { name: '다시 시도' })).toBeFocused();
});

test('캐시된 목록이 3쪽인데 서버가 6쪽이 된 뒤 5쪽 주소로 가도 옛 마지막 쪽으로 돌아가지 않는다 (WD-982)', async ({
  authenticatedPage: page,
}) => {
  await mockPagedList(page);
  await page.goto('/data/datasets');
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

  // 이후 요청부터 서버는 전체 6쪽을 준다(나중에 등록한 route 가 먼저 받는다)
  await page.route(
    (url) => url.pathname === API,
    async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const p = Number(new URL(route.request().url()).searchParams.get('page') ?? '0');
      const row = createDataset({ id: p + 1, name: `데이터셋 ${p + 1}쪽 행`, tableName: `ds_${p + 1}` });
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createPageResponse([row], { page: p, size: 10, totalElements: 55, totalPages: 6 })),
      });
    },
  );
  const held = await holdRequests(page, API, 'GET');

  // 앱 안 이동(뒤로가기와 같은 popstate)으로 옛 totalPages(3) 밖인 5쪽(page=4)으로 간다
  await page.evaluate(() => {
    window.history.pushState(window.history.state, '', '/data/datasets?page=4');
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }));
  });
  await expect.poll(held.count).toBeGreaterThan(0);
  // 응답 전에 이전 쪽 placeholder 의 totalPages 로 범위 밖 판정해 주소를 옛 마지막 쪽으로 바꾸면 안 된다
  await page.waitForTimeout(500);
  await expect(page).toHaveURL(/page=4/);

  held.release();
  await expect(page.getByText('데이터셋 5쪽 행', { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/page=4/);
});

test('오프라인에서 다음 쪽으로 가면 오프라인 안내가 보인다 (WD-982)', async ({ authenticatedPage: page }) => {
  await mockPagedList(page);
  await page.goto('/data/datasets');
  const nav = page.getByRole('navigation', { name: '페이지네이션' });
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

  await page.context().setOffline(true);
  await nav.getByRole('button', { name: '다음 페이지' }).click();

  // 캐시 없는 다음 쪽 조회가 오프라인에서 대기한다 — 흐린 이전 행만 남지 않고 안내를 보이며, 페이지네이션은 남는다(WD-966)
  await expect(
    page.getByText('인터넷 연결이 없어 데이터셋 목록을 불러오지 못하고 있습니다. 연결되면 자동으로 다시 불러옵니다.'),
  ).toBeVisible();
  await expect(nav).toBeVisible();

  await page.context().setOffline(false);
  await expect(page.getByText('데이터셋 2쪽 행', { exact: true })).toBeVisible();
});

test('온라인에서 다음 쪽 재시도가 멈춰도(탭 숨김) 오프라인 안내를 보이지 않는다 (WD-982)', async ({
  authenticatedPage: page,
}) => {
  await mockPagedList(page);
  await page.goto('/data/datasets');
  const nav = page.getByRole('navigation', { name: '페이지네이션' });
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

  // 2쪽(page=1)을 계속 500 으로 — 재시도(1회, 지연) 동안 대기 상태가 된다
  await failPageRequests(page, API, 1);
  // 문서를 숨김으로 만든다 — TanStack focusManager 는 document.visibilityState 를 보고, 숨김이면 재시도를 멈춰 paused 가 된다
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await nav.getByRole('button', { name: '다음 페이지' }).click();

  // 온라인이므로 오프라인 안내는 없고 이전 행이 남는다
  await page.waitForTimeout(2500);
  await expect(page.getByText('인터넷 연결이 없어', { exact: false })).toHaveCount(0);
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();
  await expect(nav).toBeVisible();
});
