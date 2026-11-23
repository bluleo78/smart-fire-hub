import type { Locator, Page, Route } from '@playwright/test';

import { offlineListMessage } from '../../../src/hooks/useOnlineStatus';
import { createJob, createJobExecution, createReport } from '../../factories/ai-insight.factory';
import { mockProactivePermissions, setupJobDetailMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트·실행 이력 「더 보기」 실패 복구 E2E (WD-956)
 * - limit 이 늘어난 조회가 실패해도 보던 목록이 남고, 실패가 alert 로 알려지며 「다시 시도」로 이어 받아야 한다.
 */

const FAIL_MESSAGE = '다음 목록을 불러오지 못했습니다. 다시 시도하세요.';

/** 목록 API 500 응답 — 「더 보기」 실패를 만든다. */
function fulfill500(route: Route) {
  return route.fulfill({
    status: 500,
    contentType: 'application/json',
    body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: 'boom' }),
  });
}

/**
 * limit 쿼리별로 응답하는 목록 API 를 모킹하고 limit 별 요청 수를 돌려준다.
 * - failUntil40: limit=40 요청 수가 이 값에 못 미치는 동안 500 (전역 재시도 1회도 요청으로 센다).
 * - build(limit): limit 건 응답 본문
 */
async function mockLimitedList(
  page: Page,
  pathname: string,
  failUntil40: number,
  build: (n: number) => unknown[],
  // limit 별 응답 붙잡기 — 시험이 gates[limit] 에 deferred promise 를 넣으면 그 limit 의 요청이 풀릴 때까지 응답하지 않는다.
  // (실패 응답은 붙잡은 뒤에 준다. 고정 지연 대신 시험이 직접 풀어 시간에 기대지 않는다.)
  gates: Record<number, Promise<void> | undefined> = {},
) {
  const counts: Record<number, number> = { 20: 0, 40: 0 };
  await page.route(
    (url) => url.pathname === pathname,
    async (route) => {
      const limit = Number(new URL(route.request().url()).searchParams.get('limit') ?? '20');
      counts[limit] = (counts[limit] ?? 0) + 1;
      await gates[limit];
      if (limit === 40 && counts[40] <= failUntil40) {
        return fulfill500(route);
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(build(limit)) });
    },
  );
  return counts;
}

const reportRows = (n: number) =>
  Array.from({ length: n }, (_, i) =>
    createReport({ executionId: i + 1, title: `리포트 ${i + 1}` }),
  );

const executionRows = (n: number) =>
  Array.from({ length: n }, (_, i) =>
    createJobExecution({ id: i + 1, jobId: 1, startedAt: `2024-01-01T09:${String(i % 60).padStart(2, '0')}:00Z` }),
  );

/** 시험이 직접 푸는 deferred — promise 를 gates 에 넣고 resolve 로 응답을 푼다. */
function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

test.describe('「더 보기」 조회 실패 (WD-956)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
  });

  async function setupReports(page: Page, failUntil40: number) {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 3 })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    return mockLimitedList(page, '/api/v1/proactive/reports', failUntil40, reportRows);
  }

  test('리포트 「더 보기」가 실패하면 보던 20건이 남고 실패가 알려지며 다시 시도로 이어 받는다 (WD-956)', async ({
    authenticatedPage: page,
  }) => {
    // 첫 요청과 전역 재시도 1회가 실패하고 세 번째부터 성공한다
    const counts = await setupReports(page, 2);
    await page.goto('/ai-insights/reports');
    await expect(page.getByRole('link', { name: '리포트 1', exact: true })).toBeVisible();

    await page.getByRole('button', { name: '더 보기' }).click();

    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    await expect(page.getByRole('link', { name: '리포트 1', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveCount(0);
    // 첫 요청 + 전역 재시도 1회 = 실패 2번
    expect(counts[40]).toBe(2);

    await page.getByRole('button', { name: '다시 시도' }).click();

    await expect(page.getByRole('link', { name: '리포트 21', exact: true })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    // 다시 시도는 요청을 하나만 보냈고 그 뒤 더 늘지 않는다
    expect(counts[40]).toBe(3);
    await page.waitForTimeout(500);
    expect(counts[40]).toBe(3);
  });

  test('리포트 「더 보기」 다시 시도도 실패하면 안내와 보던 행이 그대로 남는다 (WD-956)', async ({
    authenticatedPage: page,
  }) => {
    const counts = await setupReports(page, 99);
    await page.goto('/ai-insights/reports');
    await expect(page.getByRole('link', { name: '리포트 1', exact: true })).toBeVisible();

    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    expect(counts[40]).toBe(2);

    await page.getByRole('button', { name: '다시 시도' }).click();

    // 다시 시도 한 번 = 요청 + 전역 재시도 1회, 모두 실패해 합계 4
    await expect.poll(() => counts[40]).toBe(4);
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    await expect(page.getByRole('link', { name: '리포트 1', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible();
    await page.waitForTimeout(500);
    expect(counts[40]).toBe(4);
  });

  test('리포트 「다시 시도」를 Enter 로 눌러 응답을 기다리는 동안 블록이 남고 초점을 유지하며 성공 뒤 사라진다 (WD-956 리뷰 후속)', async ({
    authenticatedPage: page,
  }) => {
    const gates: Record<number, Promise<void> | undefined> = {};
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 3 })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockLimitedList(page, '/api/v1/proactive/reports', 2, reportRows, gates);
    await page.goto('/ai-insights/reports');
    await expect(page.getByRole('link', { name: '리포트 1', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);

    // 재시도 응답을 시험이 풀 때까지 붙잡는다
    const gate = deferred();
    gates[40] = gate.promise;
    const retry = page.getByRole('button', { name: '다시 시도' });
    await retry.focus();
    await page.keyboard.press('Enter');
    // 응답 대기 중 블록이 남고 초점이 유지된다
    await expect(retry).toBeFocused();
    await expect(retry).toHaveAttribute('aria-disabled', 'true');
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    // 응답을 풀면 성공 뒤 블록이 사라진다
    gate.resolve();
    await expect(page.getByRole('link', { name: '리포트 21', exact: true })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '다시 시도' })).toHaveCount(0);
  });

  test('작업을 바꾸면 이전 작업의 실행 이력 실패 안내가 남지 않는다 (WD-956 리뷰 후속)', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    await setupJobDetailMocks(page, 2);
    await mockLimitedList(page, '/api/v1/proactive/jobs/1/executions', 99, executionRows);
    // 같은 탭을 앱 안에서 옮긴다(페이지 재로드 없이) — 작업 상세가 캐시돼 있으면 로딩 화면이 탭을 내리지 않아 key 가 없으면 상태가 남는다.
    const gotoJob = (id: number) =>
      page.evaluate((jid) => {
        window.history.pushState({}, '', `/ai-insights/jobs/${jid}?tab=executions`);
        window.dispatchEvent(new PopStateEvent('popstate'));
      }, id);
    // B 작업(2)을 먼저 열어 캐시에 둔 뒤 A 작업(1)으로 옮긴다
    await page.goto('/ai-insights/jobs/2?tab=executions');
    await expect(page.getByRole('tab', { name: /실행 이력/ })).toBeVisible();
    await gotoJob(1);
    await expect(page.getByRole('link', { name: /\(.*\)$/ })).toHaveCount(20);
    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);

    await gotoJob(2);
    await expect(page).toHaveURL(/jobs\/2/);
    await expect(page.getByRole('link', { name: /\(.*\)$/ })).toHaveCount(0);
    await expect(page.getByRole('alert').filter({ hasText: FAIL_MESSAGE })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '다시 시도' })).toHaveCount(0);
  });

  test('실행 이력 「더 보기」가 실패하면 보던 행이 남고 다시 시도로 이어 받는다 (WD-956)', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    // setupJobDetailMocks 의 빈 실행 목록 모킹보다 나중에 등록해 이 모킹이 우선한다
    const counts = await mockLimitedList(page, '/api/v1/proactive/jobs/1/executions', 2, executionRows);
    await page.goto('/ai-insights/jobs/1?tab=executions');
    await expect(page.getByRole('link', { name: /\(.*\)$/ })).toHaveCount(20);

    await page.getByRole('button', { name: '더 보기' }).click();

    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    await expect(page.getByRole('link', { name: /\(.*\)$/ })).toHaveCount(20);
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveCount(0);
    expect(counts[40]).toBe(2);

    await page.getByRole('button', { name: '다시 시도' }).focus();
    await page.keyboard.press('Enter');

    await expect(page.getByRole('link', { name: /\(.*\)$/ })).toHaveCount(40);
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect(counts[40]).toBe(3);
    // 실패 블록이 사라져도 초점이 body 로 빠지지 않고 같은 자리의 「더 보기」로 간다(WD-965 리뷰 후속)
    await expect(page.getByRole('button', { name: '더 보기' })).toBeFocused();
  });

  test('실행 이력 RUNNING 폴링 재조회 중에도 다시 시도가 잠기지 않고 초점을 유지한다 (WD-956)', async ({
    authenticatedPage: page,
  }) => {
    await page.clock.install();
    await setupJobDetailMocks(page, 1);
    const gates: Record<number, Promise<void> | undefined> = {};
    // 첫 행을 RUNNING 으로 둬 5초 폴링이 돌게 한다
    const running = (n: number) =>
      executionRows(n).map((e, i) => (i === 0 ? { ...e, status: 'RUNNING' as const, completedAt: null } : e));
    const counts = await mockLimitedList(page, '/api/v1/proactive/jobs/1/executions', 99, running, gates);
    await page.goto('/ai-insights/jobs/1?tab=executions');
    await expect(page.getByRole('link', { name: /\(.*\)$/ })).toHaveCount(20);

    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    const retryButton = page.getByRole('button', { name: '다시 시도' });
    await retryButton.focus();
    await expect(retryButton).toBeFocused();

    // 되돌린 limit=20 쿼리의 폴링 재조회 응답을 시험이 풀 때까지 붙잡는다
    const gate = deferred();
    gates[20] = gate.promise;
    const before = counts[20];
    await page.clock.fastForward(5000);
    await expect.poll(() => counts[20]).toBeGreaterThan(before);

    // 재조회가 진행되는 동안에도 버튼은 enabled 이고 초점을 잃지 않는다
    await expect(retryButton).toBeEnabled();
    await expect(retryButton).toBeFocused();
    gate.resolve();
  });
});

const END_STATUS = '실행 이력 5건을 더 불러왔습니다. 마지막 실행 이력입니다.';

/** 작업 1 실행 이력 탭을 build 응답으로 열고 첫 20행이 보이길 기다린 뒤 행 링크 locator 를 돌려준다(WD-965 리뷰 후속). */
async function openExecutions(page: Page, build: (n: number) => unknown[]) {
  await setupJobDetailMocks(page, 1);
  await mockLimitedList(page, '/api/v1/proactive/jobs/1/executions', 0, build);
  await page.goto('/ai-insights/jobs/1?tab=executions');
  const rows = page.getByRole('link', { name: /\(.*\)$/ });
  await expect(rows).toHaveCount(20);
  return rows;
}

/**
 * 캐시의 limit=40 조회를 낡게 만든다 — 시계를 넘기고 다른 탭에 갔다 오면 limit 이 20 으로 돌아가고 40 캐시는 staleTime 을 지났다(WD-1019).
 * 기본은 작업 상세 「실행 이력」(「개요」에 갔다 옴), 리포트 목록은 tabs 로 탭 이름을 넘긴다(WD-1037).
 * page.clock.install() 이 먼저 돼 있어야 한다.
 */
async function makeCacheStale(page: Page, rows: Locator, tabs = { other: '개요', self: '실행 이력' }) {
  await page.clock.fastForward(60_000);
  await page.getByRole('tab', { name: tabs.other }).click();
  await expect(rows).toHaveCount(0);
  await page.getByRole('tab', { name: tabs.self }).click();
  await expect(rows).toHaveCount(20);
}

/** 프레임이 두 번 돌 때까지 기다린다 — 응답이 그려진 뒤 알림 프레임이 쓰였을 시간을 준다(WD-1019). */
async function waitFrames(page: Page) {
  await page.evaluate(() => new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r()))));
}

/** 「더 보기」에 초점을 두고 Enter 로 누른다 — 키보드 사용자의 누름. */
async function pressMoreWithKeyboard(page: Page) {
  await page.getByRole('button', { name: '더 보기' }).focus();
  await page.keyboard.press('Enter');
}

test.describe('실행 이력 「더 보기」 응답 대기 (WD-965)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
  });

  test('실행 이력 「더 보기」를 Enter 로 누르면 응답 전에도 보던 행과 버튼이 남고 초점을 유지한다 (WD-965)', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    const gates: Record<number, Promise<void> | undefined> = {};
    const counts = await mockLimitedList(page, '/api/v1/proactive/jobs/1/executions', 0, executionRows, gates);
    await page.goto('/ai-insights/jobs/1?tab=executions');
    const rows = page.getByRole('link', { name: /\(.*\)$/ });
    await expect(rows).toHaveCount(20);

    // limit=40 응답을 시험이 풀 때까지 붙잡는다
    const gate = deferred();
    gates[40] = gate.promise;
    const more = page.getByRole('button', { name: '더 보기' });
    await more.focus();
    await page.keyboard.press('Enter');

    // 응답 전: 보던 20행과 버튼이 남고 초점이 버튼에 있으며 스켈레톤이 없다
    await expect(more).toHaveAttribute('aria-disabled', 'true');
    await expect(more).toBeFocused();
    await expect(rows).toHaveCount(20);
    await expect(page.locator('.animate-pulse')).toHaveCount(0);

    // 진행 중 다시 Enter 해도 limit=60 요청은 나가지 않는다
    await page.keyboard.press('Enter');

    gate.resolve();
    await expect(rows).toHaveCount(40);
    // 시간에 기대지 않고, 응답이 풀려 40행이 된 뒤에도 limit=60 요청이 한 번도 없었음을 확인한다
    expect(counts[60] ?? 0).toBe(0);
    await expect(more).not.toHaveAttribute('aria-disabled', 'true');
    await expect(more).toBeFocused();
    // 새로 받은 행 수를 화면 밖 status 영역으로 알린다(리뷰 후속)
    const status = page.getByTestId('executions-load-status');
    await expect(status).toHaveAttribute('role', 'status');
    await expect(status).toHaveText('실행 이력 20건을 더 불러왔습니다.');
  });

  test('실행 이력 「더 보기」 응답이 끝 목록이면 버튼이 사라지고 초점이 첫 새 행으로 가며 끝임을 알린다 (WD-965 리뷰 후속)', async ({
    authenticatedPage: page,
  }) => {
    const rows = await openExecutions(page, (n) => executionRows(n === 40 ? 25 : n));
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(25);
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveCount(0);
    // 버튼이 사라져도 초점이 body 로 빠지지 않고 새로 받은 첫 행(21번째)으로 간다
    await expect(rows.nth(20)).toBeFocused();
    await expect(page.getByTestId('executions-load-status')).toHaveText(END_STATUS);
  });

  test('실행 이력 「더 보기」를 초점 없이 누르면 버튼이 사라져도 초점을 옮기지 않는다 (WD-965 리뷰 후속)', async ({
    authenticatedPage: page,
  }) => {
    const rows = await openExecutions(page, (n) => executionRows(n === 40 ? 25 : n));
    // 마우스 클릭이 버튼에 초점을 주지 않는 브라우저(Safari)처럼 — 초점 없이 click 만 보낸다
    await page.getByRole('button', { name: '더 보기' }).dispatchEvent('click');
    await expect(rows).toHaveCount(25);
    await expect(page.getByTestId('executions-load-status')).toHaveText(END_STATUS);
    await expect(rows.nth(20)).not.toBeFocused();
    // 초점이 body 에 그대로 남는다 — 다른 요소로 옮겨지지 않았음을 직접 확인(WD-1019)
    expect(await page.evaluate(() => document.activeElement === document.body)).toBe(true);
  });

  /**
   * 낡은 캐시 재조회 모킹 (WD-1019·WD-1038 공통) — 전역 staleTime(30초)을 넘기려고 가짜 시계를 먼저 깐다(실제로 기다리지 않는다).
   * limit=40 첫 조회는 firstCount 건, 낡은 캐시를 다시 조회하는 두 번째부터는 gate 를 풀 때까지 붙잡았다가 refetchCount 건을 준다.
   * 그 밖의 limit 은 limit 건. 호출 수는 calls() 로 읽는다.
   */
  async function mockStaleRefetch(page: Page, firstCount: number, refetchCount: number) {
    await page.clock.install();
    await setupJobDetailMocks(page, 1);
    const gate = deferred();
    const counts: Record<number, number> = {};
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs/1/executions',
      async (route) => {
        const limit = Number(new URL(route.request().url()).searchParams.get('limit'));
        counts[limit] = (counts[limit] ?? 0) + 1;
        let count = limit;
        if (limit === 40) {
          count = firstCount;
          if (counts[40] >= 2) {
            await gate.promise;
            count = refetchCount;
          }
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(executionRows(count)) });
      },
    );
    return { gate, calls: (limit: number) => counts[limit] ?? 0 };
  }

  test('실행 이력 「더 보기」 새 limit 이 낡은 캐시로 먼저 보여도 재조회가 끝난 행으로 알린다 (WD-1019)', async ({
    authenticatedPage: page,
  }) => {
    // limit=40 은 첫 조회에 40건, 낡은 캐시를 다시 조회하는 두 번째부터 25건(붙잡았다가 시험이 풂)
    const { gate, calls } = await mockStaleRefetch(page, 40, 25);
    await page.goto('/ai-insights/jobs/1?tab=executions');
    const rows = page.getByRole('link', { name: /\(.*\)$/ });
    await expect(rows).toHaveCount(20);
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(40);

    // 다른 탭으로 갔다가 돌아오면 limit 이 20 으로 돌아가고, 캐시의 limit=40 조회는 낡아 있다
    await makeCacheStale(page, rows);

    await pressMoreWithKeyboard(page);
    // 재조회(limit=40, 25건)가 붙잡힌 동안에는 낡은 캐시 40건으로 알리지 않는다
    await expect.poll(() => calls(40)).toBe(2);
    // 낡은 캐시 40건이 먼저 그려진 것을 보고 나서 알림이 비어 있음을 확인한다
    await expect(rows).toHaveCount(40);
    await waitFrames(page);
    await expect(page.getByTestId('executions-load-status')).toHaveText('');
    // 재조회 중에는 「더 보기」가 잠겨 있고 다시 눌러도 limit=60 요청이 나가지 않는다 — 기다림의 마지막 행·행 수가 낡은 값으로 덮이지 않게
    const more = page.getByRole('button', { name: '더 보기' });
    await expect(more).toHaveAttribute('aria-disabled', 'true');
    await page.keyboard.press('Enter');
    await waitFrames(page);
    expect(calls(60)).toBe(0);

    gate.resolve();
    await expect(rows).toHaveCount(25);
    await expect(page.getByTestId('executions-load-status')).toHaveText(END_STATUS);
    await expect(rows.nth(20)).toBeFocused();
  });

  /**
   * 낡은 캐시가 limit 보다 짧은 길의 공통 준비 (WD-1038) — limit=40 첫 조회는 25건(끝 목록), 두 번째부터는 gate 를 풀 때까지 붙잡았다가
   * refetchCount 건을 준다. 20행 → 더 보기(25행, 버튼 사라짐) → 캐시를 낡게 → 다시 눌러 재조회가 붙잡힌 때까지 진행하고,
   * 낡은 25행이 limit(40)보다 적어도 재조회가 확정되기 전에는 버튼이 사라지지 않고 잠긴 채 초점을 지키는지 확인해 돌려준다.
   */
  async function pressMoreOnShortStaleCache(page: Page, refetchCount: number) {
    const { gate, calls } = await mockStaleRefetch(page, 25, refetchCount);
    await page.goto('/ai-insights/jobs/1?tab=executions');
    const rows = page.getByRole('link', { name: /\(.*\)$/ });
    const more = page.getByRole('button', { name: '더 보기' });
    await expect(rows).toHaveCount(20);
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(25);
    await expect(more).toHaveCount(0);

    await makeCacheStale(page, rows);
    await pressMoreWithKeyboard(page);
    await expect.poll(() => calls(40)).toBe(2);
    await expect(rows).toHaveCount(25);
    await expect(more).toBeVisible();
    await expect(more).toHaveAttribute('aria-disabled', 'true');
    await expect(more).toBeFocused();
    return { rows, more, gate };
  }

  test('실행 이력 「더 보기」 낡은 캐시가 limit 보다 짧아도 재조회를 기다리는 동안 버튼이 잠긴 채 남고, 재조회가 더 가져오면 그대로 남는다 (WD-1038)', async ({
    authenticatedPage: page,
  }) => {
    const { rows, more, gate } = await pressMoreOnShortStaleCache(page, 40);
    gate.resolve();
    await expect(rows).toHaveCount(40);
    await expect(more).toBeVisible();
    await expect(more).not.toHaveAttribute('aria-disabled', 'true');
    await expect(more).toBeFocused();
  });

  test('실행 이력 「더 보기」 낡은 캐시 재조회가 여전히 limit 보다 짧으면 끝난 뒤에야 버튼이 사라지고 초점이 첫 새 행으로 간다 (WD-1038)', async ({
    authenticatedPage: page,
  }) => {
    const { rows, more, gate } = await pressMoreOnShortStaleCache(page, 25);
    gate.resolve();
    await expect(more).toHaveCount(0);
    await expect(page.getByTestId('executions-load-status')).toHaveText(END_STATUS);
    await expect(rows.nth(20)).toBeFocused();
  });

  test('실행 이력 「더 보기」 앞 응답의 알림 프레임이 돌기 전에 다시 누르면 지난 알림이 다시 쓰이지 않는다 (WD-1019)', async ({
    authenticatedPage: page,
  }) => {
    // requestAnimationFrame 을 시험이 쥔다 — __holdFrames 를 켠 뒤 걸린 콜백만 모아 두고 __flushFrames 로 돌린다.
    // (다른 화면 동작이 멈추지 않게 평소에는 그대로 통과시킨다.)
    await page.addInitScript(() => {
      const w = window as unknown as Record<string, unknown>;
      const origRaf = window.requestAnimationFrame.bind(window);
      const origCancel = window.cancelAnimationFrame.bind(window);
      const held = new Map<number, FrameRequestCallback>();
      let nextId = 1_000_000;
      w.__holdFrames = false;
      w.__heldFrames = () => held.size;
      window.requestAnimationFrame = (cb: FrameRequestCallback) => {
        if (!w.__holdFrames) return origRaf(cb);
        const id = nextId++;
        held.set(id, cb);
        return id;
      };
      window.cancelAnimationFrame = (id: number) => {
        if (held.delete(id)) return;
        origCancel(id);
      };
      w.__flushFrames = () => {
        const cbs = [...held.values()];
        held.clear();
        cbs.forEach((cb) => cb(performance.now()));
      };
    });
    await setupJobDetailMocks(page, 1);
    const gates: Record<number, Promise<void> | undefined> = {};
    // limit=60 은 45건 — 두 번째 응답의 문구가 첫 응답의 「20건」과 다르게
    await mockLimitedList(
      page,
      '/api/v1/proactive/jobs/1/executions',
      0,
      (n) => executionRows(n === 60 ? 45 : n),
      gates,
    );
    await page.goto('/ai-insights/jobs/1?tab=executions');
    const rows = page.getByRole('link', { name: /\(.*\)$/ });
    await expect(rows).toHaveCount(20);

    await page.evaluate(() => {
      (window as unknown as Record<string, unknown>).__holdFrames = true;
    });
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(40);
    // 첫 응답의 알림 프레임이 걸려 있다(아직 돌지 않음)
    await expect.poll(() => page.evaluate(() => (window as unknown as { __heldFrames: () => number }).__heldFrames())).toBeGreaterThan(0);
    const status = page.getByTestId('executions-load-status');
    await expect(status).toHaveText('');

    // 프레임이 돌기 전에 다시 누르고 limit=60 응답을 붙잡는다
    const gate = deferred();
    gates[60] = gate.promise;
    await pressMoreWithKeyboard(page);
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveAttribute('aria-disabled', 'true');
    // 지난 프레임을 풀어도 지난 문구(20건)가 다시 쓰이지 않는다
    await page.evaluate(() => (window as unknown as { __flushFrames: () => void }).__flushFrames());
    await expect(status).toHaveText('');

    gate.resolve();
    await expect(rows).toHaveCount(45);
    await expect.poll(() => page.evaluate(() => (window as unknown as { __heldFrames: () => number }).__heldFrames())).toBeGreaterThan(0);
    await page.evaluate(() => (window as unknown as { __flushFrames: () => void }).__flushFrames());
    await expect(status).toHaveText(END_STATUS);
  });

  test('실행 이력 「더 보기」 낡은 캐시 재조회가 실패하면 실패 안내와 보던 20건으로 돌아가고 나중의 성공 재조회는 알림도 초점 이동도 하지 않는다 (WD-1019)', async ({
    authenticatedPage: page,
  }) => {
    await page.clock.install();
    await setupJobDetailMocks(page, 1);
    // 첫 행이 RUNNING 이라 5초 폴링이 돈다 — 실패 뒤 「나중의 성공 재조회」를 시계로 결정적으로 일으킨다
    const withRunning = (n: number) => executionRows(n).map((e, i) => (i === 0 ? { ...e, status: 'RUNNING' } : e));
    const gate = deferred();
    let calls40 = 0;
    let calls20 = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs/1/executions',
      async (route) => {
        const limit = Number(new URL(route.request().url()).searchParams.get('limit') ?? '20');
        if (limit === 20) calls20 += 1;
        if (limit === 40) {
          calls40 += 1;
          // 2·3 번째(낡은 캐시 재조회 + 전역 재시도 1회)는 붙잡았다가 500, 그 뒤는 성공
          if (calls40 === 2 || calls40 === 3) {
            await gate.promise;
            return fulfill500(route);
          }
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(withRunning(limit)) });
      },
    );
    await page.goto('/ai-insights/jobs/1?tab=executions');
    const rows = page.getByRole('link', { name: /\(.*\)$/ });
    await expect(rows).toHaveCount(20);
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(40);

    // 탭을 갔다 와 limit 20 으로 돌아가고, 캐시의 limit=40 조회는 낡았다
    await makeCacheStale(page, rows);

    await pressMoreWithKeyboard(page);
    await expect.poll(() => calls40).toBe(2);
    // 재시도 대기(1초)를 시계로 넘기고 두 번째 실패까지 끝낸다
    gate.resolve();
    await page.clock.fastForward(2_000);
    await expect.poll(() => calls40).toBe(3);
    // 실패는 조용히 묻히지 않는다 — 안내가 알려지고 보던 20행으로 되돌아간다
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    await expect(rows).toHaveCount(20);
    await waitFrames(page);
    const status = page.getByTestId('executions-load-status');
    await expect(status).toHaveText('');

    // 나중의 폴링 재조회(성공)가 와도 지난 「더 보기」의 알림·초점 이동이 일어나지 않는다
    const before = calls20;
    await page.clock.fastForward(6_000);
    await expect.poll(() => calls20).toBeGreaterThan(before);
    await waitFrames(page);
    await expect(rows).toHaveCount(20);
    await expect(status).toHaveText('');
    // 키보드로 눌러 실패했으므로 「다시 시도」에 초점이 있고, 폴링이 행으로 옮기지 않는다 (WD-1036)
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeFocused();

    // 「다시 시도」 — 실패한 limit=40 캐시(오류+데이터)를 다시 열어도 같은 렌더에서 되돌리지 않고 요청이 나가 40행을 받는다 (WD-1019 리뷰)
    const callsBeforeRetry = calls40;
    await page.getByRole('button', { name: '다시 시도' }).click();
    await expect.poll(() => calls40).toBeGreaterThan(callsBeforeRetry);
    await expect(rows).toHaveCount(40);
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('실행 이력 「더 보기」 오프라인에서 낡은 캐시로 보여도 성공으로 알리지 않고 잠긴 채 기다리며 연결 뒤 재조회 실패를 알린다 (WD-1019 리뷰)', async ({
    authenticatedPage: page,
  }) => {
    await page.clock.install();
    await setupJobDetailMocks(page, 1);
    let calls40 = 0;
    let failOnline = false;
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs/1/executions',
      async (route) => {
        const limit = Number(new URL(route.request().url()).searchParams.get('limit') ?? '20');
        if (limit === 40) {
          calls40 += 1;
          // 연결이 돌아온 뒤의 재조회(+ 전역 재시도 1회)는 실패
          if (failOnline) {
            return fulfill500(route);
          }
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(executionRows(limit)) });
      },
    );
    await page.goto('/ai-insights/jobs/1?tab=executions');
    const rows = page.getByRole('link', { name: /\(.*\)$/ });
    await expect(rows).toHaveCount(20);
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(40);

    // 탭을 갔다 와 limit 20 으로 돌아가고, 캐시의 limit=40 조회는 낡았다
    await makeCacheStale(page, rows);

    // 오프라인에서 「더 보기」 — 낡은 캐시 40행이 보이지만 재조회가 멈춰(paused) 성공으로 세지 않는다
    await page.context().setOffline(true);
    failOnline = true;
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(40);
    await waitFrames(page);
    const status = page.getByTestId('executions-load-status');
    await expect(status).toHaveText('');
    // 기다림이 끝나지 않아 버튼이 잠긴 채 남는다 — 다시 눌러 기다림을 낡은 값으로 덮지 못하게
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveAttribute('aria-disabled', 'true');
    expect(calls40).toBe(1);

    // 연결이 돌아와 재조회가 실패하면 묻히지 않고 안내와 보던 20행으로 돌아간다
    await page.context().setOffline(false);
    await expect.poll(() => calls40).toBeGreaterThan(1);
    await page.clock.fastForward(2_000);
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    await expect(rows).toHaveCount(20);
  });

  test('실행 이력 「더 보기」 응답 사이 맨 위에 새 실행이 끼어도 새로 받은 행 수와 첫 새 행이 맞다 (WD-965 리뷰 후속)', async ({
    authenticatedPage: page,
  }) => {
    // limit=40 응답은 기다리는 사이 시작된 실행(id 999)이 맨 위에 붙고 그 뒤로 25건이 온다
    const fresh = createJobExecution({ id: 999, jobId: 1, startedAt: '2024-01-02T09:00:00Z' });
    const rows = await openExecutions(page, (n) => (n === 40 ? [fresh, ...executionRows(25)] : executionRows(n)));
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(26);
    // 위치(21번째)가 아니라 누른 때 마지막 행(id 20) 뒤의 행(id 21)으로 간다
    await expect(page.locator('a[href$="/jobs/1/executions/21"]')).toBeFocused();
    await expect(page.getByTestId('executions-load-status')).toHaveText(END_STATUS);
  });

  test('실행 이력 「더 보기」 응답에 새 행이 없으면 초점이 마지막 행으로 가고 더 없음을 알린다 (WD-965 리뷰 후속)', async ({
    authenticatedPage: page,
  }) => {
    // 정확히 20건으로 끝나는 목록 — 버튼이 한 번 더 보이고 빈 추가 응답으로 사라진다
    const rows = await openExecutions(page, (n) => executionRows(Math.min(n, 20)));
    await pressMoreWithKeyboard(page);
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveCount(0);
    await expect(rows.nth(19)).toBeFocused();
    await expect(page.getByTestId('executions-load-status')).toHaveText('더 불러올 실행 이력이 없습니다.');
  });

  test('작업을 바꾸면 새 작업의 응답 전에 이전 작업의 실행 행이 한 순간도 보이지 않는다 (WD-965)', async ({
    authenticatedPage: page,
  }) => {
    // 이 시험이 지키는 것: 작업 전환 시 새 작업은 이전 작업의 행을 보이지 않는다.
    // 현재는 페이지의 key={jobId} 재마운트와 useJobExecutions 의 placeholderData jobId 가드 어느 쪽이든 이를 만족시킨다.
    await setupJobDetailMocks(page, 1);
    await setupJobDetailMocks(page, 2);
    const gates: Record<number, Promise<void> | undefined> = {};
    await mockLimitedList(page, '/api/v1/proactive/jobs/1/executions', 0, executionRows);
    // 2번 작업은 실행 id 를 101 부터 다르게 두고, 응답을 시험이 풀 때까지 붙잡는다
    const gate = deferred();
    gates[20] = gate.promise;
    await mockLimitedList(
      page,
      '/api/v1/proactive/jobs/2/executions',
      0,
      (n) =>
        Array.from({ length: Math.min(n, 3) }, (_, i) =>
          createJobExecution({ id: 101 + i, jobId: 2, startedAt: `2024-02-01T09:0${i}:00Z` }),
        ),
      gates,
    );
    await page.goto('/ai-insights/jobs/1?tab=executions');
    await expect(page.locator('a[href*="/jobs/1/executions/"]')).toHaveCount(20);

    // React Router 는 위치 변경을 startTransition 으로 적용하므로 URL 이 바뀐 직후엔 아직 1번 행이 남을 수 있다.
    // 2번 탭이 마운트된 뒤에야 나가는 2번 실행 이력 요청을 기다린 다음에 즉시 확인한다.
    const req = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/v1/proactive/jobs/2/executions');
    await page.evaluate(() => {
      window.history.pushState({}, '', '/ai-insights/jobs/2?tab=executions');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await expect(page).toHaveURL(/jobs\/2/);
    await req;
    // 2번 작업 응답 대기 중: 재시도 없는 즉시 확인(1번 행도, 아직 오지 않은 2번 행도 없다)
    expect(await page.locator('a[href*="/jobs/1/executions/"]').count()).toBe(0);
    expect(await page.locator('a[href*="/jobs/2/executions/"]').count()).toBe(0);

    gate.resolve();
    await expect(page.locator('a[href*="/jobs/2/executions/"]')).toHaveCount(3);
    await expect(page.locator('a[href*="/jobs/1/executions/"]')).toHaveCount(0);
  });
});

test.describe('리포트 「더 보기」 응답 대기 (WD-1016)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 3 })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  });

  test('리포트 「더 보기」를 Enter 로 누르면 응답 전에도 보던 행과 버튼이 남고 초점을 유지한다 (WD-1016)', async ({
    authenticatedPage: page,
  }) => {
    const gates: Record<number, Promise<void> | undefined> = {};
    const counts = await mockLimitedList(page, '/api/v1/proactive/reports', 0, reportRows, gates);
    await page.goto('/ai-insights/reports');
    const rows = page.getByRole('link', { name: /^리포트 \d+$/ });
    await expect(rows).toHaveCount(20);

    // limit=40 응답을 시험이 풀 때까지 붙잡는다
    const gate = deferred();
    gates[40] = gate.promise;
    const more = page.getByRole('button', { name: '더 보기' });
    await more.focus();
    await page.keyboard.press('Enter');

    // 응답 전: 보던 20행과 버튼이 남고 초점이 버튼에 있다
    await expect(more).toBeVisible();
    await expect(more).toHaveAttribute('aria-disabled', 'true');
    await expect(more).toBeFocused();
    await expect(rows).toHaveCount(20);

    // 진행 중 다시 Enter — 잠금이 풀려 있으면 limit=60 이 요청된다
    await expect.poll(() => counts[40]).toBe(1);
    await page.keyboard.press('Enter');

    gate.resolve();
    await expect(rows).toHaveCount(40);
    await expect(more).not.toHaveAttribute('aria-disabled', 'true');
    await expect(more).toBeFocused();
    expect(counts[40]).toBe(1);
    expect(counts[60] ?? 0).toBe(0);
  });

  test('리포트 「더 보기」 응답이 끝 목록이면 버튼이 사라진다 (WD-1016)', async ({ authenticatedPage: page }) => {
    await mockLimitedList(page, '/api/v1/proactive/reports', 0, (limit) => reportRows(Math.min(limit, 25)));
    await page.goto('/ai-insights/reports');
    const rows = page.getByRole('link', { name: /^리포트 \d+$/ });
    await expect(rows).toHaveCount(20);
    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(rows).toHaveCount(25);
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveCount(0);
  });
});

/**
 * 「더 보기」 오프라인 안내 E2E (WD-1035)
 * - 오프라인에서 「더 보기」를 누르면 보던 행을 지키고 버튼은 잠긴 채, 버튼 아래에 연결 대기 안내가 보이며 연결되면 사라진다.
 */
test.describe('「더 보기」 오프라인 안내 (WD-1035)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
  });

  test('실행 이력 「더 보기」를 오프라인에서 누르면 보던 행이 남고 버튼 아래 연결 대기 안내가 보이며 연결되면 이어 받는다 (WD-1035)', async ({
    authenticatedPage: page,
  }) => {
    const message = offlineListMessage('실행 이력');
    const rows = await openExecutions(page, executionRows);

    // 오프라인이 돼도 누르기 전에는 안내가 없다
    await page.context().setOffline(true);
    await expect(page.getByText(message)).toHaveCount(0);

    await pressMoreWithKeyboard(page);
    // 보던 20행 유지 + 안내 + 버튼 잠김·초점 유지
    await expect(rows).toHaveCount(20);
    await expect(page.getByText(message)).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: message })).toBeVisible();
    const more = page.getByRole('button', { name: '더 보기' });
    await expect(more).toHaveAttribute('aria-disabled', 'true');
    await expect(more).toBeFocused();

    // 연결되면 이어 받고 안내가 사라진다
    await page.context().setOffline(false);
    await expect(rows).toHaveCount(40);
    await expect(page.getByText(message)).toHaveCount(0);
  });

  test('실행 이력 「더 보기」 낡은 캐시로 보인 채 오프라인으로 재조회가 멈추면 연결 대기 안내가 보인다 (WD-1035)', async ({
    authenticatedPage: page,
  }) => {
    const message = offlineListMessage('실행 이력');
    await page.clock.install();
    const rows = await openExecutions(page, executionRows);
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(40);

    await makeCacheStale(page, rows);

    await page.context().setOffline(true);
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(40);
    await expect(page.getByText(message)).toBeVisible();
    // 낡은 캐시 기다림은 자리표시와 다른 길(settle)로 잠그므로 이 길에서도 잠김을 본다
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveAttribute('aria-disabled', 'true');

    await page.context().setOffline(false);
    await expect(page.getByText(message)).toHaveCount(0);
  });

  test('리포트 「더 보기」를 오프라인에서 누르면 보던 행이 남고 버튼 아래 연결 대기 안내가 보이며 연결되면 이어 받는다 (WD-1035)', async ({
    authenticatedPage: page,
  }) => {
    const message = offlineListMessage('리포트');
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 3 })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockLimitedList(page, '/api/v1/proactive/reports', 0, reportRows);
    await page.goto('/ai-insights/reports');
    const rows = page.getByRole('link', { name: /^리포트 \d+$/ });
    await expect(rows).toHaveCount(20);

    await page.context().setOffline(true);
    await expect(page.getByText(message)).toHaveCount(0);

    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(20);
    await expect(page.getByText(message)).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: message })).toBeVisible();
    const more = page.getByRole('button', { name: '더 보기' });
    await expect(more).toHaveAttribute('aria-disabled', 'true');
    await expect(more).toBeFocused();

    await page.context().setOffline(false);
    await expect(rows).toHaveCount(40);
    await expect(page.getByText(message)).toHaveCount(0);
  });

  test('실행 이력 「더 보기」 실패 뒤 오프라인에서 「다시 시도」를 누르면 실패 블록 아래에 연결 대기 안내가 보인다 (WD-1035 리뷰)', async ({
    authenticatedPage: page,
  }) => {
    const message = offlineListMessage('실행 이력');
    await setupJobDetailMocks(page, 1);
    // limit=40 은 처음 두 요청(전역 재시도 포함)만 실패
    await mockLimitedList(page, '/api/v1/proactive/jobs/1/executions', 2, executionRows);
    await page.goto('/ai-insights/jobs/1?tab=executions');
    const rows = page.getByRole('link', { name: /\(.*\)$/ });
    await expect(rows).toHaveCount(20);
    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);

    await page.context().setOffline(true);
    await page.getByRole('button', { name: '다시 시도' }).click();
    await expect(rows).toHaveCount(20);
    await expect(page.getByText(message)).toBeVisible();

    await page.context().setOffline(false);
    await expect(rows).toHaveCount(40);
    await expect(page.getByText(message)).toHaveCount(0);
  });

  test('실행 이력 「더 보기」 낡은 캐시가 limit 보다 짧아도 오프라인 재조회 대기 중 버튼이 잠긴 채 남고 연결 대기 안내가 보인다 (WD-1035 리뷰·WD-1038)', async ({
    authenticatedPage: page,
  }) => {
    const message = offlineListMessage('실행 이력');
    await page.clock.install();
    // limit=40 은 끝 목록(25건) — 받은 행이 limit 보다 적어 처음에는 「더 보기」가 그려지지 않는다
    const rows = await openExecutions(page, (n) => executionRows(n === 40 ? 25 : n));
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(25);

    await makeCacheStale(page, rows);

    await page.context().setOffline(true);
    await pressMoreWithKeyboard(page);
    // 낡은 25행이 먼저 보이고 재조회는 멈춘다 — 재조회가 확정되기 전이라 버튼은 잠긴 채 남고 안내도 보인다 (WD-1038)
    await expect(rows).toHaveCount(25);
    await expect(page.getByText(message)).toBeVisible();
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveAttribute('aria-disabled', 'true');

    await page.context().setOffline(false);
    await expect(page.getByText(message)).toHaveCount(0);
  });

  test('리포트 「더 보기」 실패 뒤 오프라인에서 「다시 시도」를 누르면 실패 블록 아래에 연결 대기 안내가 보인다 (WD-1035 리뷰)', async ({
    authenticatedPage: page,
  }) => {
    const message = offlineListMessage('리포트');
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 3 })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockLimitedList(page, '/api/v1/proactive/reports', 2, reportRows);
    await page.goto('/ai-insights/reports');
    const rows = page.getByRole('link', { name: /^리포트 \d+$/ });
    await expect(rows).toHaveCount(20);
    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);

    await page.context().setOffline(true);
    await page.getByRole('button', { name: '다시 시도' }).click();
    await expect(rows).toHaveCount(20);
    await expect(page.getByText(message)).toBeVisible();

    await page.context().setOffline(false);
    await expect(rows).toHaveCount(40);
    await expect(page.getByText(message)).toHaveCount(0);
  });
});

/** 리포트 목록의 limit=40 조회를 낡게 만든다 — 「리포트 양식」 탭에 갔다 온다(WD-1037). */
const makeReportCacheStale = (page: Page, rows: Locator) =>
  makeCacheStale(page, rows, { other: '리포트 양식', self: '생성된 리포트' });

test.describe('리포트 목록 「더 보기」 재조회 (WD-1037)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 3 })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
  });

  /** limit=40 의 n 번째 요청을 붙잡았다가 500 으로 답하는 리포트 목록 모킹 — failCalls 에 든 번호만 실패. */
  async function mockReports(page: Page, failCalls: (n: number) => boolean, gate?: Promise<void>) {
    const state = { calls40: 0, calls60: 0 };
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/reports',
      async (route) => {
        const limit = Number(new URL(route.request().url()).searchParams.get('limit') ?? '20');
        if (limit === 60) {
          state.calls60 += 1;
        }
        if (limit === 40) {
          state.calls40 += 1;
          if (failCalls(state.calls40)) {
            await gate;
            return fulfill500(route);
          }
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(reportRows(limit)) });
      },
    );
    return state;
  }

  test('리포트 목록: 낡은 캐시로 먼저 보인 더 보기의 재조회가 500 이면 실패를 알리고 20건으로 돌아간다', async ({
    authenticatedPage: page,
  }) => {
    await page.clock.install();
    const gate = deferred();
    // 2·3 번째(낡은 캐시 재조회 + 전역 재시도 1회)는 실패, 그 뒤는 성공
    const state = await mockReports(page, (n) => n === 2 || n === 3, gate.promise);
    await page.goto('/ai-insights/reports');
    const rows = page.getByRole('link', { name: /^리포트 \d+$/ });
    await expect(rows).toHaveCount(20);
    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(rows).toHaveCount(40);

    await makeReportCacheStale(page, rows);

    await page.getByRole('button', { name: '더 보기' }).click();
    await expect.poll(() => state.calls40).toBe(2);
    // 재조회가 끝나기 전에는 버튼이 잠긴다 — 다시 눌러도 limit=60 요청이 나가지 않는다(WD-1037 리뷰)
    const more = page.getByRole('button', { name: '더 보기' });
    await expect(more).toHaveAttribute('aria-disabled', 'true');
    // 잠긴 버튼은 클릭으로 누를 수 없어(Playwright 는 aria-disabled 를 사용 불가로 본다) 키보드로 누른다
    await more.focus();
    await page.keyboard.press('Enter');
    expect(state.calls60).toBe(0);
    gate.resolve();
    await page.clock.fastForward(2_000);
    await expect.poll(() => state.calls40).toBe(3);
    // 실패는 묻히지 않는다 — 안내가 알려지고 보던 20행으로 돌아간다
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    await expect(rows).toHaveCount(20);
  });

  test('리포트 목록: 오류 캐시로 다시 시도하면 요청이 나가고 성공하면 40건이 보인다', async ({
    authenticatedPage: page,
  }) => {
    await page.clock.install();
    const gate = deferred();
    const state = await mockReports(page, (n) => n === 2 || n === 3, gate.promise);
    await page.goto('/ai-insights/reports');
    const rows = page.getByRole('link', { name: /^리포트 \d+$/ });
    await expect(rows).toHaveCount(20);
    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(rows).toHaveCount(40);
    await makeReportCacheStale(page, rows);
    await page.getByRole('button', { name: '더 보기' }).click();
    await expect.poll(() => state.calls40).toBe(2);
    gate.resolve();
    await page.clock.fastForward(2_000);
    await expect.poll(() => state.calls40).toBe(3);
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    await expect(rows).toHaveCount(20);

    // 40 캐시는 이제 오류+데이터 — 다시 시도가 같은 렌더에서 접히지 않고 요청이 나가 40행을 받는다
    const before = state.calls40;
    await page.getByRole('button', { name: '다시 시도' }).click();
    await expect.poll(() => state.calls40).toBeGreaterThan(before);
    await expect(rows).toHaveCount(40);
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('리포트 목록: 오프라인으로 재조회가 멈추면 성공으로 세지 않는다', async ({ authenticatedPage: page }) => {
    await page.clock.install();
    let failOnline = false;
    const state = await mockReports(page, () => failOnline);
    await page.goto('/ai-insights/reports');
    const rows = page.getByRole('link', { name: /^리포트 \d+$/ });
    await expect(rows).toHaveCount(20);
    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(rows).toHaveCount(40);
    await makeReportCacheStale(page, rows);

    // 오프라인에서 「더 보기」 — 낡은 캐시 40행이 보이지만 재조회가 멈춰(paused) 성공으로 세지 않는다
    await page.context().setOffline(true);
    failOnline = true;
    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(rows).toHaveCount(40);
    // 기다림이 끝나지 않아 버튼이 잠긴 채 남는다 — 실행 이력(WD-1019)과 같다
    await expect(page.getByRole('button', { name: '더 보기' })).toHaveAttribute('aria-disabled', 'true');
    expect(state.calls40).toBe(1);

    // 연결이 돌아와 재조회가 실패하면 묻히지 않고 안내와 보던 20행으로 돌아간다
    await page.context().setOffline(false);
    await expect.poll(() => state.calls40).toBeGreaterThan(1);
    await page.clock.fastForward(2_000);
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    await expect(rows).toHaveCount(20);
  });
});

test.describe('「더 보기」 실패 초점 (WD-1036)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
  });

  /** limit=40 이 늘 500 인 실행 이력 탭을 열고 첫 20행을 기다려 행 locator 를 돌려준다. */
  async function openAlwaysFailing(page: Page) {
    await setupJobDetailMocks(page, 1);
    await mockLimitedList(page, '/api/v1/proactive/jobs/1/executions', 99, executionRows);
    await page.goto('/ai-insights/jobs/1?tab=executions');
    const rows = page.getByRole('link', { name: /\(.*\)$/ });
    await expect(rows).toHaveCount(20);
    return rows;
  }

  test('실행 이력 「더 보기」를 Enter 로 눌러 실패하면 초점이 「다시 시도」로 가고 실패가 알려진다 (WD-1036)', async ({
    authenticatedPage: page,
  }) => {
    const rows = await openAlwaysFailing(page);
    await pressMoreWithKeyboard(page);
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    // 버튼이 실패 블록으로 바뀌어도 초점이 body 로 빠지지 않고 「다시 시도」로 간다
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeFocused();
    await expect(rows).toHaveCount(20);
  });

  test('실행 이력 「더 보기」를 초점 없이 눌러 실패하면 초점을 옮기지 않는다 (WD-1036)', async ({
    authenticatedPage: page,
  }) => {
    await openAlwaysFailing(page);
    // 마우스 클릭이 초점을 주지 않는 브라우저처럼 — 초점 없이 click 만 보낸다
    await page.getByRole('button', { name: '더 보기' }).dispatchEvent('click');
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    expect(await page.evaluate(() => document.activeElement === document.body)).toBe(true);
  });

  /** limit=40 이 늘 500 인 리포트 목록을 열고 첫 20행을 기다려 limit 별 요청 수를 돌려준다. */
  async function openReportsAlwaysFailing(page: Page) {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 3 })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    const counts = await mockLimitedList(page, '/api/v1/proactive/reports', 99, reportRows);
    await page.goto('/ai-insights/reports');
    await expect(page.getByRole('link', { name: /^리포트 \d+$/ })).toHaveCount(20);
    return counts;
  }

  test('리포트 「더 보기」를 Enter 로 눌러 실패하면 초점이 「다시 시도」로 가고 실패가 알려진다 (WD-1036)', async ({
    authenticatedPage: page,
  }) => {
    await openReportsAlwaysFailing(page);
    await pressMoreWithKeyboard(page);
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeFocused();
    await expect(page.getByRole('link', { name: /^리포트 \d+$/ })).toHaveCount(20);
  });

  test('리포트 「더 보기」를 초점 없이 눌러 실패하면 초점을 옮기지 않는다 (WD-1036)', async ({
    authenticatedPage: page,
  }) => {
    await openReportsAlwaysFailing(page);
    await page.getByRole('button', { name: '더 보기' }).dispatchEvent('click');
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    expect(await page.evaluate(() => document.activeElement === document.body)).toBe(true);
  });

  test('리포트 「다시 시도」를 Enter 로 눌러 또 실패해도 초점이 「다시 시도」에 남는다 (WD-1036)', async ({
    authenticatedPage: page,
  }) => {
    const counts = await openReportsAlwaysFailing(page);
    await pressMoreWithKeyboard(page);
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    const retry = page.getByRole('button', { name: '다시 시도' });
    await expect(retry).toBeFocused();
    // 첫 실패(요청 + 전역 재시도 1회 = 2)에 다시 시도의 실패 2번이 더해질 때까지 기다린다
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect.poll(() => counts[40]).toBe(4);
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    await expect(retry).toBeFocused();
  });
});
