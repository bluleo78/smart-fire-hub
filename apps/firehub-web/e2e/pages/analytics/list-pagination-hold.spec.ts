import type { Page } from '@playwright/test';

import {
  createChartListItem,
  createDashboardListItem,
  createSavedQueryListItem,
} from '../../factories/analytics.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { failPageRequests, holdRequests } from '../../fixtures/hold-api';
import { expectOfflineNoticeExposed, isUnderInert } from '../../fixtures/inert-a11y';

/**
 * 분석 목록 세 화면(쿼리·차트·대시보드)의 쪽 이동 유지 (WD-982)
 * - 이동 응답을 기다리는 동안 페이지네이션·키보드 초점이 남고, 이전 행은 흐려지고 눌리지 않는다.
 * - 이동 중 검색어를 바꾸면 이전 조건의 행이 남지 않는다.
 * - 다음 쪽 조회가 실패해도 페이지네이션이 남아 이전 쪽으로 돌아갈 수 있다.
 */

interface Screen {
  label: string;
  path: string;
  apiPath: string;
  tableName: string;
  searchPlaceholder: string;
  /** 쪽 번호(0 부터)에 맞는 목록 행 하나 */
  item: (page: number) => unknown;
  /** 화면별 부가 API 모킹 */
  extra?: (page: Page) => Promise<void>;
}

const SCREENS: Screen[] = [
  {
    label: '쿼리',
    path: '/analytics/queries',
    apiPath: '/api/v1/analytics/queries',
    tableName: '저장된 쿼리 목록',
    searchPlaceholder: '쿼리 검색...',
    item: (p) => createSavedQueryListItem({ id: p + 1, name: `쿼리 ${p + 1}쪽 행` }),
    extra: (page) => mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []),
  },
  {
    label: '차트',
    path: '/analytics/charts',
    apiPath: '/api/v1/analytics/charts',
    tableName: '차트 목록',
    searchPlaceholder: '차트 검색...',
    item: (p) => createChartListItem({ id: p + 1, name: `차트 ${p + 1}쪽 행` }),
  },
  {
    label: '대시보드',
    path: '/analytics/dashboards',
    apiPath: '/api/v1/analytics/dashboards',
    tableName: '대시보드 목록',
    searchPlaceholder: '대시보드 검색...',
    item: (p) => createDashboardListItem({ id: p + 1, name: `대시보드 ${p + 1}쪽 행` }),
  },
];

/** 쪽마다 다른 행 하나를 돌려주는 전체 3쪽짜리 목록 모킹 — 요청의 page 쿼리를 읽어 응답한다 */
async function mockPagedList(page: Page, screen: Screen) {
  await screen.extra?.(page);
  await page.route(
    (url) => url.pathname === screen.apiPath,
    async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const p = Number(new URL(route.request().url()).searchParams.get('page') ?? '0');
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createPageResponse([screen.item(p)], { page: p, size: 10, totalElements: 25, totalPages: 3 })),
      });
    },
  );
}

for (const screen of SCREENS) {
  test.describe(`${screen.label} 목록 쪽 이동 유지 (WD-982)`, () => {
    const firstRowName = `${screen.label} 1쪽 행`;

    test('쪽 이동 중에도 페이지네이션과 초점이 남는다 (WD-982)', async ({ authenticatedPage: page }) => {
      await mockPagedList(page, screen);
      await page.goto(screen.path);
      const nav = page.getByRole('navigation', { name: '페이지네이션' });
      const next = nav.getByRole('button', { name: '다음 페이지' });
      await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

      const held = await holdRequests(page, screen.apiPath, 'GET');
      await next.focus();
      await page.keyboard.press('Enter');
      await expect.poll(held.count).toBeGreaterThan(0);
      await expect(nav).toBeVisible();
      await expect(next).toBeFocused();
      const table = page.getByRole('table', { name: screen.tableName });
      await expect(table).toHaveAttribute('aria-busy', 'true');
      // 이전 행은 보이되 키보드로 닿지 않는다(tbody inert)
      await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();
      expect(await isUnderInert(page.getByText(firstRowName, { exact: true }))).toBe(true);

      held.release();
      await expect(page.getByText(`${screen.label} 2쪽 행`, { exact: true })).toBeVisible();
      await expect(table).not.toHaveAttribute('aria-busy', 'true');
      await expect(next).toBeFocused();
    });

    test('쪽 이동 중 검색어를 바꾸면 이전 행이 남지 않는다 (WD-982)', async ({ authenticatedPage: page }) => {
      await mockPagedList(page, screen);
      await page.goto(screen.path);
      const nav = page.getByRole('navigation', { name: '페이지네이션' });
      await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

      const held = await holdRequests(page, screen.apiPath, 'GET');
      await nav.getByRole('button', { name: '다음 페이지' }).click();
      await expect.poll(held.count).toBeGreaterThan(0);
      const before = held.count();
      await page.getByPlaceholder(screen.searchPlaceholder).fill('다른 조건');
      await expect.poll(held.count).toBeGreaterThan(before);
      await expect(page.getByText(firstRowName, { exact: true })).toHaveCount(0);
      held.release();
    });

    test('오프라인에서 다음 쪽으로 가면 오프라인 안내가 보이고 페이지네이션이 남는다 (WD-982 리뷰 후속)', async ({
      authenticatedPage: page,
    }) => {
      await mockPagedList(page, screen);
      await page.goto(screen.path);
      const nav = page.getByRole('navigation', { name: '페이지네이션' });
      await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

      await page.context().setOffline(true);
      await nav.getByRole('button', { name: '다음 페이지' }).click();

      // 캐시 없는 다음 쪽 조회가 오프라인에서 대기한다 — 흐린 이전 행만 남지 않고 안내를 보이며, 페이지네이션은 남는다
      await expect(
        page.getByText(`인터넷 연결이 없어 ${screen.label} 목록을 불러오지 못하고 있습니다. 연결되면 자동으로 다시 불러옵니다.`),
      ).toBeVisible();
      await expect(nav).toBeVisible();

      // WD-982 code-review: 안내가 흐림·inert 밖에서 노출된다 — 스크린리더에 들리고 비활성처럼 보이지 않는다
      await expectOfflineNoticeExposed(page, screen.tableName);

      await page.context().setOffline(false);
      await expect(page.getByText(`${screen.label} 2쪽 행`, { exact: true })).toBeVisible();
    });

    test('다음 쪽이 실패해도 페이지네이션이 남고 이전 쪽으로 돌아간다 (WD-982)', async ({
      authenticatedPage: page,
    }) => {
      await mockPagedList(page, screen);
      await page.goto(screen.path);
      const nav = page.getByRole('navigation', { name: '페이지네이션' });
      await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

      // 2쪽(page=1)만 500 — 나중에 등록한 route 가 먼저 받는다
      await failPageRequests(page, screen.apiPath, 1);
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
      await mockPagedList(page, screen);
      await page.goto(screen.path);
      const nav = page.getByRole('navigation', { name: '페이지네이션' });
      await expect(page.getByText(firstRowName, { exact: true })).toBeVisible();

      // 2쪽(page=1)은 계속 500 — 나중에 등록한 route 가 먼저 받는다
      await failPageRequests(page, screen.apiPath, 1);
      await nav.getByRole('button', { name: '다음 페이지' }).click();
      await expect(page.getByRole('alert')).toBeVisible();

      // 다시 시도 중 placeholder 로 이전 쪽 행이 isPending=false 로 보여도, 다시 실패하면 초점이 버튼으로 돌아와야 한다(WD-987)
      await page.getByRole('button', { name: '다시 시도' }).click();
      await expect(page.getByRole('alert')).toBeVisible();
      await expect(page.getByRole('button', { name: '다시 시도' })).toBeFocused();
    });
  });
}
