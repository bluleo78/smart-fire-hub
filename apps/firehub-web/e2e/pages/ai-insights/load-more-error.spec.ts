import type { Page } from '@playwright/test';

import { createJob, createJobExecution, createReport } from '../../factories/ai-insight.factory';
import { mockProactivePermissions, setupJobDetailMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트·실행 이력 「더 보기」 실패 복구 E2E (WD-956)
 * - limit 이 늘어난 조회가 실패해도 보던 목록이 남고, 실패가 alert 로 알려지며 「다시 시도」로 이어 받아야 한다.
 */

const FAIL_MESSAGE = '다음 목록을 불러오지 못했습니다. 다시 시도하세요.';

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
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: 'boom' }),
        });
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

    await page.getByRole('button', { name: '다시 시도' }).click();

    await expect(page.getByRole('link', { name: /\(.*\)$/ })).toHaveCount(40);
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect(counts[40]).toBe(3);
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
