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
  // limit=20 응답을 이 시간(ms)만큼 늦춘다 — 되돌린 쿼리의 재조회(isFetching)를 일부러 길게 잡는 시험용.
  slow20 = { ms: 0 },
) {
  const counts: Record<number, number> = { 20: 0, 40: 0 };
  await page.route(
    (url) => url.pathname === pathname,
    async (route) => {
      const limit = Number(new URL(route.request().url()).searchParams.get('limit') ?? '20');
      counts[limit] = (counts[limit] ?? 0) + 1;
      if (limit === 20 && slow20.ms > 0) await new Promise((r) => setTimeout(r, slow20.ms));
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
    const slow20 = { ms: 0 };
    // 첫 행을 RUNNING 으로 둬 5초 폴링이 돌게 한다
    const running = (n: number) =>
      executionRows(n).map((e, i) => (i === 0 ? { ...e, status: 'RUNNING' as const, completedAt: null } : e));
    const counts = await mockLimitedList(page, '/api/v1/proactive/jobs/1/executions', 99, running, slow20);
    await page.goto('/ai-insights/jobs/1?tab=executions');
    await expect(page.getByRole('link', { name: /\(.*\)$/ })).toHaveCount(20);

    await page.getByRole('button', { name: '더 보기' }).click();
    await expect(page.getByRole('alert')).toHaveText(FAIL_MESSAGE);
    const retryButton = page.getByRole('button', { name: '다시 시도' });
    await retryButton.focus();
    await expect(retryButton).toBeFocused();

    // 되돌린 limit=20 쿼리의 폴링 재조회를 늦은 응답으로 길게 잡는다
    slow20.ms = 1500;
    const before = counts[20];
    await page.clock.fastForward(5000);
    await expect.poll(() => counts[20]).toBeGreaterThan(before);

    // 재조회가 진행되는 동안에도 버튼은 enabled 이고 초점을 잃지 않는다
    await expect(retryButton).toBeEnabled();
    await expect(retryButton).toBeFocused();
  });
});
