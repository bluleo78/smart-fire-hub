import type { Page } from '@playwright/test';

import { createPipelines } from '../../factories/pipeline.factory';
import { createPageResponse } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions } from '../../fixtures/pipeline.fixture';
import { expectTableErrorAlert } from '../../fixtures/table-error-alert';

/**
 * 파이프라인 목록 — 다음 쪽 조회 실패 복구 (WD-956)
 * - 둘째 쪽 이상 조회가 실패해도 쪽 이동이 남아 이전 쪽으로 돌아가거나 「다시 시도」할 수 있어야 한다.
 */

const PAGE_FAIL_MESSAGE = '이 쪽을 불러오지 못했습니다. 다시 시도하거나 다른 쪽으로 이동하세요.';
const FIRST_FAIL_MESSAGE = '파이프라인 목록을 불러오지 못했습니다.';

/** 30건 중 쪽별 응답을 만든다 — page 쿼리 값으로 첫 쪽(1~10)·둘째 쪽(11~20)을 나눈다. */
function pageBody(pageIndex: number) {
  const all = createPipelines(30);
  return createPageResponse(all.slice(pageIndex * 10, pageIndex * 10 + 10), {
    page: pageIndex,
    totalElements: 30,
  });
}

/**
 * 목록 API 를 쪽별로 모킹하고 쪽(page 쿼리)별 요청 수를 돌려준다.
 * - failUntil[page]: 그 쪽의 요청 수가 이 값에 못 미치는 동안 500 을 준다(전역 재시도 1회도 요청으로 센다).
 */
async function mockPagedList(page: Page, failUntil: Record<number, number>, delayAfter?: { page: number; afterCount: number; ms: number }) {
  const counts: Record<number, number> = { 0: 0, 1: 0, 2: 0 };
  await page.route(
    (url) => url.pathname === '/api/v1/pipelines',
    async (route) => {
      const idx = Number(new URL(route.request().url()).searchParams.get('page') ?? '0');
      counts[idx] = (counts[idx] ?? 0) + 1;
      // 재요청 중 상태를 관찰할 수 있게 지정한 쪽의 N번째 이후 요청을 지연시킨다.
      if (delayAfter && idx === delayAfter.page && counts[idx] > delayAfter.afterCount) {
        await new Promise((r) => setTimeout(r, delayAfter.ms));
      }
      if (counts[idx] <= (failUntil[idx] ?? 0)) {
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: 'boom' }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(pageBody(idx)) });
    },
  );
  return counts;
}

test.describe('파이프라인 목록 다음 쪽 조회 실패 (WD-956)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('다음 쪽 조회가 실패하면 쪽 이동이 남아 이전 쪽으로 돌아갈 수 있고 실패가 alert 로 알려진다 (WD-956)', async ({ authenticatedPage: page }) => {
    await mockPagedList(page, { 1: 99 });
    await page.goto('/pipelines');
    await expect(page.getByRole('cell', { name: '파이프라인 1', exact: true })).toBeVisible();

    await page.getByRole('button', { name: '다음 페이지' }).click();

    const table = page.getByRole('table', { name: '파이프라인 목록' });
    await expectTableErrorAlert(page, PAGE_FAIL_MESSAGE, { action: '다시 시도' });
    await expect(table.getByRole('alert')).toHaveText(PAGE_FAIL_MESSAGE);
    // 쪽 이동이 사라지지 않는다
    const prev = page.getByRole('button', { name: '이전 페이지' });
    await expect(prev).toBeVisible();

    await prev.click();
    await expect(page.getByRole('cell', { name: '파이프라인 1', exact: true })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('다음 쪽 실패 뒤 「다시 시도」로 그 쪽을 다시 받는다 (WD-956)', async ({ authenticatedPage: page }) => {
    // 첫 요청과 전역 재시도 1회가 모두 실패하고 세 번째부터 성공한다
    const counts = await mockPagedList(page, { 1: 2 });
    await page.goto('/pipelines');
    await expect(page.getByRole('cell', { name: '파이프라인 1', exact: true })).toBeVisible();

    await page.getByRole('button', { name: '다음 페이지' }).click();
    await expectTableErrorAlert(page, PAGE_FAIL_MESSAGE, { action: '다시 시도' });
    const before = counts[1];

    await page.getByRole('button', { name: '다시 시도' }).click();

    await expect(page.getByRole('cell', { name: '파이프라인 11', exact: true })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect(counts[1]).toBe(before + 1);
  });

  test('「다시 시도」를 키보드로 눌러 재요청 중에도 오류 행·초점이 남고 또 실패해도 유지된다 (WD-956)', async ({ authenticatedPage: page }) => {
    // 둘째 쪽: 처음 2개(요청+재시도)는 즉시 실패, 이후 4개는 1.5초 지연 뒤 실패
    const counts = await mockPagedList(page, { 1: 99 }, { page: 1, afterCount: 2, ms: 1500 });
    await page.goto('/pipelines');
    await expect(page.getByRole('cell', { name: '파이프라인 1', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '다음 페이지' }).click();
    await expectTableErrorAlert(page, PAGE_FAIL_MESSAGE, { action: '다시 시도' });
    expect(counts[1]).toBe(2);

    const retry = page.getByRole('button', { name: '다시 시도' });
    await retry.focus();
    await page.keyboard.press('Enter');

    // 재요청 중 스켈레톤이 오류 행을 대신하지 않고 버튼은 잠기되 초점을 유지한다
    await expect(retry).toHaveAttribute('aria-disabled', 'true');
    await expect(retry).toBeFocused();
    await expect(page.getByRole('table', { name: '파이프라인 목록' }).getByRole('alert')).toHaveText(PAGE_FAIL_MESSAGE);
    await page.keyboard.press('Enter');
    // 또 실패해도 오류 행과 초점이 그대로다 (요청 수 4 = 다시 시도 1회 + 전역 재시도 1회, 겹침 없음)
    await expect.poll(() => counts[1], { timeout: 8000 }).toBe(4);
    await expect(retry).not.toHaveAttribute('aria-disabled', 'true'); // PendingButton 은 잠금 해제 시 속성을 뺀다
    await expect(retry).toBeFocused();
    await expect(page.getByRole('table', { name: '파이프라인 목록' }).getByRole('alert')).toHaveText(PAGE_FAIL_MESSAGE);
    await page.waitForTimeout(500);
    expect(counts[1]).toBe(4);
  });

  test('「다시 시도」 재요청이 지연된 뒤 성공하면 행이 보인다 (WD-956)', async ({ authenticatedPage: page }) => {
    // 둘째 쪽 처음 2개 실패, 3번째는 1.5초 지연 뒤 성공
    const counts = await mockPagedList(page, { 1: 2 }, { page: 1, afterCount: 2, ms: 1500 });
    await page.goto('/pipelines');
    await expect(page.getByRole('cell', { name: '파이프라인 1', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '다음 페이지' }).click();
    await expectTableErrorAlert(page, PAGE_FAIL_MESSAGE, { action: '다시 시도' });

    const retry = page.getByRole('button', { name: '다시 시도' });
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect(retry).toBeFocused();
    await expect(page.getByRole('cell', { name: '파이프라인 11', exact: true })).toBeVisible({ timeout: 8000 });
    expect(counts[1]).toBe(3);
  });

  test('쪽 이동이 없으면 다른 쪽으로 이동하라는 안내를 하지 않는다 (WD-956)', async ({ authenticatedPage: page }) => {
    // 한 쪽뿐인 목록에서 첫 쪽 실패 — 이동 권유 없이 다시 시도 안내만
    await mockPagedList(page, { 0: 99 });
    await page.goto('/pipelines');
    await expectTableErrorAlert(page, FIRST_FAIL_MESSAGE, { action: '다시 시도' });
    await expect(page.getByRole('alert')).not.toContainText('다른 쪽');
  });

  test('첫 쪽부터 실패하면 다시 시도만 보이고 쪽 이동은 없다 (WD-956)', async ({ authenticatedPage: page }) => {
    await mockPagedList(page, { 0: 99 });
    await page.goto('/pipelines');

    await expectTableErrorAlert(page, '파이프라인 목록을 불러오지 못했습니다.', { action: '다시 시도' });
    await expect(page.getByRole('button', { name: '다음 페이지' })).toHaveCount(0);
  });
});
