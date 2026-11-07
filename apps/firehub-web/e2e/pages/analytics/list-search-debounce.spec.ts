import type { Page } from '@playwright/test';

import {
  createChartListItem,
  createDashboardListItem,
  createSavedQueryListItem,
} from '../../factories/analytics.factory';
import { createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 목록 검색 debounce 회귀 테스트 (WD-513)
 *
 * 배경: 쿼리·차트·대시보드·데이터셋 목록의 검색 칸이 한 글자마다 목록 GET 을 보냈다.
 * 타이핑이 멈춘 뒤(300ms) 최종 검색어로 한 번만 요청해야 한다.
 *
 * 검증: 목록 API 를 route 로 모킹해 실제로 나간 요청의 search 파라미터를 모두 기록한 뒤,
 * 'abcdefghij' 를 40ms 간격으로 타이핑하면
 * - search=abcdefghij 요청은 정확히 1회,
 * - 중간 접두사(a, ab, …) 요청은 0회,
 * - 화면 목록은 검색 응답으로 갱신된다.
 */

const TERM = 'abcdefghij';

interface ListCase {
  title: string;
  url: string;
  apiPath: string;
  placeholder: string;
  /** 페이지가 함께 부르는 부가 API 모킹 (폴더·카테고리 등) */
  extraMocks?: (page: Page) => Promise<void>;
  /** 응답 항목 하나를 이름으로 만든다 — 결과 목록 갱신을 이름으로 확인한다 */
  item: (id: number, name: string) => unknown;
}

const cases: ListCase[] = [
  {
    title: '쿼리 목록',
    url: '/analytics/queries',
    apiPath: '/api/v1/analytics/queries',
    placeholder: '쿼리 검색...',
    extraMocks: async (page) => {
      await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);
    },
    item: (id, name) => createSavedQueryListItem({ id, name }),
  },
  {
    title: '차트 목록',
    url: '/analytics/charts',
    apiPath: '/api/v1/analytics/charts',
    placeholder: '차트 검색...',
    item: (id, name) => createChartListItem({ id, name }),
  },
  {
    title: '대시보드 목록',
    url: '/analytics/dashboards',
    apiPath: '/api/v1/analytics/dashboards',
    placeholder: '대시보드 검색...',
    item: (id, name) => createDashboardListItem({ id, name }),
  },
  {
    title: '데이터셋 목록',
    url: '/data/datasets',
    apiPath: '/api/v1/datasets',
    placeholder: '데이터셋 검색...',
    extraMocks: async (page) => {
      await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
      await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    },
    item: (id, name) => createDataset({ id, name }),
  },
];

test.describe('목록 검색 debounce (WD-513)', () => {
  for (const c of cases) {
    test(`${c.title} — 타이핑이 멈춘 뒤 최종 검색어로 한 번만 요청한다`, async ({ authenticatedPage: page }) => {
      await c.extraMocks?.(page);

      // 실제로 나간 목록 GET 의 search 파라미터를 모두 기록한다 (없으면 '')
      const searches: string[] = [];
      await page.route(
        (url) => url.pathname === c.apiPath,
        (route) => {
          if (route.request().method() !== 'GET') return route.fallback();
          const search = new URL(route.request().url()).searchParams.get('search') ?? '';
          searches.push(search);
          // 검색어가 있으면 그 검색어를 이름에 담은 결과를, 없으면 초기 목록을 준다
          const body = search
            ? createPageResponse([c.item(99, `검색결과 ${search}`)])
            : createPageResponse([c.item(1, '초기 항목')]);
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
        },
      );

      await page.goto(c.url);
      await expect(page.getByText('초기 항목', { exact: true })).toBeVisible();

      await page.getByRole('textbox', { name: c.placeholder }).pressSequentially(TERM, { delay: 40 });

      // 결과 목록이 최종 검색어 응답으로 갱신된다
      await expect(page.getByText(`검색결과 ${TERM}`, { exact: true })).toBeVisible();
      await expect(page.getByText('초기 항목', { exact: true })).toHaveCount(0);

      // 최종 검색어 요청은 정확히 1회, 중간 접두사 요청은 0회
      const withSearch = searches.filter((s) => s !== '');
      expect(withSearch.filter((s) => s === TERM)).toHaveLength(1);
      expect(withSearch.filter((s) => s !== TERM)).toEqual([]);
    });
  }

  test('쿼리 목록 — 검색 중 탭을 바꾸면 옛 검색어로 새 탭을 요청하지 않고, 지우기(X)는 기다리지 않고 바로 반영된다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);
    // 나간 목록 GET 의 (탭 범위, search) 를 모두 기록한다
    const calls: { shared: boolean; search: string }[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const params = new URL(route.request().url()).searchParams;
        const search = params.get('search') ?? '';
        calls.push({ shared: params.get('sharedOnly') === 'true', search });
        const body = search
          ? createPageResponse([createSavedQueryListItem({ id: 99, name: `검색결과 ${search}` })])
          : createPageResponse([createSavedQueryListItem({ id: 1, name: '초기 항목' })]);
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      },
    );

    await page.goto('/analytics/queries');
    await expect(page.getByText('초기 항목', { exact: true })).toBeVisible();
    const input = page.getByRole('textbox', { name: '쿼리 검색...' });

    // 지우기(X): 검색이 반영된 뒤 X 를 누르면 debounce(300ms)를 기다리지 않고 바로 전체 목록으로 돌아간다.
    // (검색 없는 첫 목록은 캐시에 있어 새 요청 없이 즉시 그려진다 — 250ms 안에 보여야 대기 없이 반영된 것)
    await input.pressSequentially('abc', { delay: 40 });
    await expect(page.getByText('검색결과 abc', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '검색어 지우기' }).click();
    await expect(page.getByText('초기 항목', { exact: true })).toBeVisible({ timeout: 250 });
    await expect(input).toHaveValue('');

    // 탭 전환: 검색어를 넣은 채 「공유됨」 으로 바꾸면 공유 탭 요청에 옛 검색어(abc)가 실리지 않는다
    await input.pressSequentially('abc', { delay: 40 });
    await expect(page.getByText('검색결과 abc', { exact: true })).toBeVisible();
    await page.getByRole('tab', { name: '공유됨' }).click();
    await expect.poll(() => calls.some((c) => c.shared)).toBe(true);
    await page.waitForTimeout(500);
    expect(calls.filter((c) => c.shared && c.search !== '')).toEqual([]);
  });
});
