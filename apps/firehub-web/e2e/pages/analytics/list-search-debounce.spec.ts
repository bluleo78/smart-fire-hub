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

  /**
   * 페이지 초기화·빈 결과 「검색 초기화」 회귀 (WD-597)
   *
   * 검색어와 페이지 초기화는 SearchInput 의 debounce 뒤 같은 onChange 에서 함께 바뀐다(WD-513).
   * 검색어만 따로 늦추면(useDebounceValue) 페이지가 먼저 0 으로 바뀌어 "옛 검색어 + 첫 페이지" 요청이 한 번 더 나가므로,
   * 2페이지에서 10자를 입력했을 때
   * - 최종 검색어 요청은 정확히 1회이고 그 요청은 첫 페이지(page=0)다,
   * - 최종 검색어로 2페이지(page=1)를 요청하지 않는다,
   * - 검색어 없이 첫 페이지를 다시 요청하지도 않는다(입력 도중 페이지만 먼저 초기화되지 않음),
   * - 결과가 없을 때 빈 상태에 검색어가 보이고, 「검색 초기화」 는 기다리지 않고 입력 칸·목록을 바로 되돌린다.
   */
  for (const c of cases.filter((x) => x.url.startsWith('/analytics/'))) {
    test(`${c.title} — 2페이지에서 검색하면 첫 페이지로 한 번만 요청하고, 빈 결과의 검색 초기화가 바로 반영된다 (WD-597)`, async ({
      authenticatedPage: page,
    }) => {
      await c.extraMocks?.(page);

      // 나간 목록 GET 의 (search, page) 를 모두 기록한다
      const calls: { search: string; page: string }[] = [];
      await page.route(
        (url) => url.pathname === c.apiPath,
        (route) => {
          if (route.request().method() !== 'GET') return route.fallback();
          const params = new URL(route.request().url()).searchParams;
          const search = params.get('search') ?? '';
          const pageNo = params.get('page') ?? '0';
          calls.push({ search, page: pageNo });
          // 'zzz' 로 시작하는 검색어는 결과 없음 — 빈 상태 검증용
          if (search.startsWith('zzz')) {
            return route.fulfill({
              status: 200,
              contentType: 'application/json',
              body: JSON.stringify(createPageResponse([])),
            });
          }
          // 검색 결과·검색 없는 목록 모두 3페이지짜리 — 페이지네이션이 보여야 현재 페이지(aria-current)를 확인할 수 있다
          const body = search
            ? createPageResponse([c.item(99, `검색결과 ${search}`)], { page: Number(pageNo), totalElements: 30 })
            : createPageResponse([c.item(Number(pageNo) + 1, `항목 p${pageNo}`)], {
                page: Number(pageNo),
                totalElements: 30,
              });
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
        },
      );

      await page.goto(c.url);
      await expect(page.getByText('항목 p0', { exact: true })).toBeVisible();

      // 2페이지로 이동
      await page.getByRole('button', { name: '2 페이지' }).click();
      await expect(page.getByText('항목 p1', { exact: true })).toBeVisible();
      calls.length = 0;

      const input = page.getByRole('textbox', { name: c.placeholder });
      await input.pressSequentially(TERM, { delay: 40 });
      // 입력 칸은 매 글자 즉시 보인다(제어 입력)
      await expect(input).toHaveValue(TERM);

      // 결과 목록이 최종 검색어 응답으로 갱신되고, 페이지는 첫 페이지로 돌아간다
      await expect(page.getByText(`검색결과 ${TERM}`, { exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: '1 페이지' })).toHaveAttribute('aria-current', 'page');
      await page.waitForTimeout(400);

      // 최종 검색어 요청 1회(첫 페이지), 그 외 요청 없음 — 중간 접두사·옛 검색어+첫 페이지·새 검색어+2페이지 모두 0회
      expect(calls).toEqual([{ search: TERM, page: '0' }]);

      // 결과 없는 검색어 → 빈 상태에 검색어 표시
      await input.fill('');
      await input.pressSequentially('zzz', { delay: 40 });
      await expect(page.getByText("'zzz'", { exact: true })).toBeVisible();
      await expect(page.getByText('에 대한 결과가 없습니다.')).toBeVisible();
      await page.waitForTimeout(400);
      calls.length = 0;

      // 「검색 초기화」: debounce 를 기다리지 않고 바로 입력 칸을 비우고 전체 목록(캐시)으로 돌아간다
      await page.getByRole('button', { name: '검색 초기화' }).click();
      await expect(input).toHaveValue('', { timeout: 250 });
      await expect(page.getByText('항목 p0', { exact: true })).toBeVisible();
      await page.waitForTimeout(400);
      // 초기화 뒤 옛 검색어(zzz)로 늦게 나가는 요청이 없다
      expect(calls.filter((x) => x.search !== '')).toEqual([]);
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
