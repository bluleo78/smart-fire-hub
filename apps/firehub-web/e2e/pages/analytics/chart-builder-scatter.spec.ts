/**
 * 차트 빌더 산점도 — 숫자 X축·여러 Y축 E2E (WD-308)
 *
 * 결함: 다른 타입에서 산점도로 바꾸면 X축이 문자 컬럼(city)으로 남아 점 없는 빈 격자만 보였고, 안내도 없었다.
 * Y축을 여러 개 골라도 첫 번째만 그려지고 나머지는 말없이 빠졌다.
 * 이제 산점도로 바꾸면 X축을 숫자 컬럼으로 고르고, Y축마다 계열을 그리며, 숫자 컬럼이 없으면 이유를 알린다.
 */

import type { Page } from '@playwright/test';

import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 저장 쿼리 1 을 고르고 실행한 뒤 산점도로 바꾼다 */
async function runQueryThenPickScatter(page: Page, loadedText: string) {
  await page.goto('/analytics/charts/new');
  await page.getByRole('combobox').first().click();
  await page.getByRole('option', { name: '저장 쿼리 1' }).click();
  await page.getByRole('button', { name: '쿼리 실행' }).click();
  await expect(page.getByText(loadedText)).toBeVisible();
  await page.getByRole('button', { name: '산점도' }).click();
}

test.describe('차트 빌더 산점도 (WD-308)', () => {
  test('문자 X축 차트에서 산점도로 바꾸면 X축이 숫자 컬럼이 되고 Y축마다 점이 그려진다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    // 날짜 컬럼이 있어 처음엔 선 차트(X=city)로 잡힌다 — 이슈 재현 데이터와 같은 모양
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/1/execute',
      createQueryResult({
        columns: ['city', 'cnt', 'delta', 'size', 'd'],
        rows: [
          { city: '서울', cnt: 10, delta: -3, size: 5, d: '2026-10-01' },
          { city: '부산', cnt: 20, delta: 4, size: 7, d: '2026-10-02' },
          { city: '대구', cnt: 30, delta: 8, size: 9, d: '2026-10-03' },
        ],
        totalRows: 3,
      }),
    );

    await runQueryThenPickScatter(page, '5개 컬럼, 3개 행 로드됨');

    // 안내 문구 없이 점이 그려진다 — cnt 를 X 로 쓰고 delta·size 두 계열 × 3행 = 6점
    await expect(page.getByText('산점도의 X축은 숫자 컬럼이어야 합니다')).toHaveCount(0);
    await expect(page.locator('.recharts-scatter-symbol')).toHaveCount(6);
    // 두 Y축이 모두 범례에 있다(전에는 첫 번째만)
    const legend = page.locator('.recharts-legend-wrapper');
    await expect(legend.getByText('delta')).toBeVisible();
    await expect(legend.getByText('size')).toBeVisible();
  });

  test('숫자 컬럼이 하나도 없으면 빈 격자 대신 「산점도의 X축은 숫자 컬럼이어야 합니다」를 보여 준다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/1/execute',
      createQueryResult({
        columns: ['city', 'label'],
        rows: [
          { city: '서울', label: 'a' },
          { city: '부산', label: 'b' },
        ],
        totalRows: 2,
      }),
    );

    await runQueryThenPickScatter(page, '2개 컬럼, 2개 행 로드됨');

    await expect(page.getByText('산점도의 X축은 숫자 컬럼이어야 합니다')).toBeVisible();
    await expect(page.locator('.recharts-scatter-symbol')).toHaveCount(0);
  });
});
