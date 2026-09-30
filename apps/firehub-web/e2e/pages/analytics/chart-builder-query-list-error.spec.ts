/**
 * 차트 빌더 — 저장 쿼리 목록 조회 실패 시 오류 표시·기존 쿼리 보존 E2E 테스트 (#738)
 *
 * 예전에는 `GET /analytics/queries` 가 실패하면 오류를 빈 목록으로 삼켜
 * "저장된 쿼리" 드롭다운이 "쿼리 선택" 하나만 보이고, 기존 차트의 쿼리 칸도 빈 칸이 됐다.
 * 사용자는 "쿼리 없음"과 "조회 실패"를 구분할 수 없고, 빈 칸을 보고 다른 쿼리를 골라 덮어쓸 수 있었다.
 * 이 테스트는 조회 실패 시 오류 안내가 보이고, 기존 차트의 쿼리 이름이 유지되며,
 * 드롭다운 선택이 막히고, 쿼리 실행이 원래 쿼리 id 로 요청되는지 검증한다.
 */

import type { Page } from '@playwright/test';

import { createChart } from '../../factories/analytics.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 저장 쿼리 목록 조회를 항상 500 으로 실패시킨다 */
async function mockQueryListFailure(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/analytics/queries',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      return route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ status: 500, error: 'Internal Server Error', message: '서버 오류' }),
      });
    },
  );
}

test.describe('차트 빌더 — 저장 쿼리 목록 조회 실패 (#738)', () => {
  test('기존 차트: 오류 안내가 보이고 쿼리 칸은 원래 쿼리 이름을 유지하며 다른 쿼리로 바꿀 수 없다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts/1',
      createChart({ id: 1, savedQueryId: 44, savedQueryName: '지역별 화재 통계' }),
    );
    await mockQueryListFailure(page);
    const executeCapture = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/44/execute',
      { columns: ['name', 'value'], rows: [{ name: 'a', value: 1 }], rowCount: 1, executionTimeMs: 1, truncated: false },
      { capture: true },
    );

    await page.goto('/analytics/charts/1');

    // 조회 실패를 빈 목록으로 위장하지 않고 오류로 안내한다
    await expect(page.getByRole('alert').filter({ hasText: '저장 쿼리 목록을 불러오지 못했습니다.' })).toBeVisible();

    // 기존 차트의 쿼리 칸은 빈 칸이 아니라 차트가 쓰는 쿼리 이름을 보인다
    const combobox = page.getByRole('combobox', { name: '저장된 쿼리' });
    await expect(combobox).toHaveText('지역별 화재 통계');
    // 목록을 모르는 상태에서 다른 쿼리로 덮어쓰지 못하도록 선택을 막는다
    await expect(combobox).toBeDisabled();

    // 쿼리 실행은 목록 실패와 무관하게 차트가 쓰는 원래 쿼리(44)로 요청된다 — 칸이 비어 id 를 잃지 않았음을 확인
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    const req = await executeCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/analytics/queries/44/execute');
  });

  test('새 차트: 오류 안내가 보이고 쿼리 드롭다운은 선택할 수 없다', async ({
    authenticatedPage: page,
  }) => {
    await mockQueryListFailure(page);

    await page.goto('/analytics/charts/new');

    await expect(page.getByRole('alert').filter({ hasText: '저장 쿼리 목록을 불러오지 못했습니다.' })).toBeVisible();
    await expect(page.getByRole('combobox', { name: '저장된 쿼리' })).toBeDisabled();
  });
});
