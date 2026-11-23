import type { Page } from '@playwright/test';

import { createUser } from '../../factories/auth.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { failPageRequests, holdRequests } from '../../fixtures/hold-api';
import { expectOfflineNoticeExposed, isUnderInert } from '../../fixtures/inert-a11y';

/**
 * 사용자 관리 목록 쪽 이동 유지 (WD-982)
 * - 이동 응답을 기다리는 동안 페이지네이션·키보드 초점이 남고, 이전 행은 흐려지고 눌리지 않는다.
 * - 이동 중 검색어를 바꾸면 이전 조건의 행이 남지 않는다.
 * - 다음 쪽 조회가 실패해도 페이지네이션이 남아 이전 쪽으로 돌아갈 수 있다(기존 isError 행이 안내).
 */
const API = '/api/v1/users';
const firstRowName = '사용자 1쪽 행';

/** 쪽마다 다른 행 하나를 돌려주는 전체 3쪽짜리 목록 모킹 — 요청의 page 쿼리를 읽어 응답한다 */
async function mockPagedList(page: Page) {
  await page.route(
    (url) => url.pathname === API,
    async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const p = Number(new URL(route.request().url()).searchParams.get('page') ?? '0');
      const row = createUser({ id: p + 1, name: `사용자 ${p + 1}쪽 행`, username: `user${p + 1}@acme.io` });
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createPageResponse([row], { page: p, size: 10, totalElements: 25, totalPages: 3 })),
      });
    },
  );
}

test('쪽 이동 중에도 페이지네이션과 초점이 남는다 (WD-982)', async ({ authenticatedPage: page }) => {
  await setupAdminAuth(page);
  await mockPagedList(page);
  await page.goto('/admin/users');
  const nav = page.getByRole('navigation', { name: '페이지네이션' });
  const next = nav.getByRole('button', { name: '다음 페이지' });
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

  const held = await holdRequests(page, API, 'GET');
  await next.focus();
  await page.keyboard.press('Enter');
  await expect.poll(held.count).toBeGreaterThan(0);
  await expect(nav).toBeVisible();
  await expect(next).toBeFocused();
  const table = page.getByRole('table', { name: '사용자 목록' });
  await expect(table).toHaveAttribute('aria-busy', 'true');
  // 이전 행은 보이되 키보드로 닿지 않는다(tbody inert)
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();
  expect(await isUnderInert(page.getByText(firstRowName, { exact: true }))).toBe(true);

  held.release();
  await expect(page.getByText('사용자 2쪽 행', { exact: true })).toBeVisible();
  await expect(table).not.toHaveAttribute('aria-busy', 'true');
  await expect(next).toBeFocused();
});

test('쪽 이동 중 검색어를 바꾸면 이전 행이 남지 않는다 (WD-982)', async ({ authenticatedPage: page }) => {
  await setupAdminAuth(page);
  await mockPagedList(page);
  await page.goto('/admin/users');
  const nav = page.getByRole('navigation', { name: '페이지네이션' });
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

  const held = await holdRequests(page, API, 'GET');
  await nav.getByRole('button', { name: '다음 페이지' }).click();
  await expect.poll(held.count).toBeGreaterThan(0);
  const before = held.count();
  await page.getByPlaceholder('이름 또는 아이디로 검색...').fill('다른 조건');
  await expect.poll(held.count).toBeGreaterThan(before);
  await expect(page.getByText(firstRowName, { exact: true })).toHaveCount(0);
  held.release();
});

test('다음 쪽이 실패해도 페이지네이션이 남고 이전 쪽으로 돌아간다 (WD-982)', async ({ authenticatedPage: page }) => {
  await setupAdminAuth(page);
  await mockPagedList(page);
  await page.goto('/admin/users');
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

test('오프라인에서 다음 쪽으로 가면 오프라인 안내가 보이고 페이지네이션이 남는다 (WD-982 리뷰 후속)', async ({
  authenticatedPage: page,
}) => {
  await setupAdminAuth(page);
  await mockPagedList(page);
  await page.goto('/admin/users');
  const nav = page.getByRole('navigation', { name: '페이지네이션' });
  await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

  await page.context().setOffline(true);
  await nav.getByRole('button', { name: '다음 페이지' }).click();

  await expect(
    page.getByText('인터넷 연결이 없어 사용자 목록을 불러오지 못하고 있습니다. 연결되면 자동으로 다시 불러옵니다.'),
  ).toBeVisible();
  await expect(nav).toBeVisible();

  // WD-982 code-review: 안내가 흐림·inert 밖에서 노출된다 — 스크린리더에 들리고 비활성처럼 보이지 않는다
  await expectOfflineNoticeExposed(page, '사용자 목록');

  await page.context().setOffline(false);
  await expect(page.getByText('사용자 2쪽 행', { exact: true })).toBeVisible();
});
