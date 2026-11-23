import type { Locator, Page, Route } from '@playwright/test';

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
 * page.clock.install() 이 먼저 돼 있어야 한다.
 */
async function makeCacheStale(page: Page, rows: Locator) {
  await page.clock.fastForward(60_000);
  await page.getByRole('tab', { name: '개요' }).click();
  await expect(rows).toHaveCount(0);
  await page.getByRole('tab', { name: '실행 이력' }).click();
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

  test('실행 이력 「더 보기」 새 limit 이 낡은 캐시로 먼저 보여도 재조회가 끝난 행으로 알린다 (WD-1019)', async ({
    authenticatedPage: page,
  }) => {
    // 전역 staleTime(30초)을 시험이 넘기려고 가짜 시계를 먼저 깐다 — 실제로 기다리지 않는다
    await page.clock.install();
    await setupJobDetailMocks(page, 1);
    // limit=40 은 첫 조회에 40건, 낡은 캐시를 다시 조회하는 두 번째부터 25건(붙잡았다가 시험이 풂)
    const gate = deferred();
    let calls40 = 0;
    let calls60 = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs/1/executions',
      async (route) => {
        const limit = Number(new URL(route.request().url()).searchParams.get('limit') ?? '20');
        let count = limit;
        if (limit === 60) calls60 += 1;
        if (limit === 40) {
          calls40 += 1;
          if (calls40 >= 2) {
            await gate.promise;
            count = 25;
          }
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(executionRows(count)) });
      },
    );
    await page.goto('/ai-insights/jobs/1?tab=executions');
    const rows = page.getByRole('link', { name: /\(.*\)$/ });
    await expect(rows).toHaveCount(20);
    await pressMoreWithKeyboard(page);
    await expect(rows).toHaveCount(40);

    // 다른 탭으로 갔다가 돌아오면 limit 이 20 으로 돌아가고, 캐시의 limit=40 조회는 낡아 있다
    await makeCacheStale(page, rows);

    await pressMoreWithKeyboard(page);
    // 재조회(limit=40, 25건)가 붙잡힌 동안에는 낡은 캐시 40건으로 알리지 않는다
    await expect.poll(() => calls40).toBe(2);
    // 낡은 캐시 40건이 먼저 그려진 것을 보고 나서 알림이 비어 있음을 확인한다
    await expect(rows).toHaveCount(40);
    await waitFrames(page);
    await expect(page.getByTestId('executions-load-status')).toHaveText('');
    // 재조회 중에는 「더 보기」가 잠겨 있고 다시 눌러도 limit=60 요청이 나가지 않는다 — 기다림의 마지막 행·행 수가 낡은 값으로 덮이지 않게
    const more = page.getByRole('button', { name: '더 보기' });
    await expect(more).toHaveAttribute('aria-disabled', 'true');
    await page.keyboard.press('Enter');
    await waitFrames(page);
    expect(calls60).toBe(0);

    gate.resolve();
    await expect(rows).toHaveCount(25);
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
    // 버튼이 실패 블록으로 바뀌어 초점은 body 에 있고, 폴링이 행으로 옮기지 않는다
    expect(await page.evaluate(() => document.activeElement === document.body)).toBe(true);

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
