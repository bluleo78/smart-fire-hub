import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 박스 플롯 통계 컬럼 안내·저장 차단 E2E 테스트 (WD-305)
 * - 박스 플롯은 Y축 선택이 아니라 쿼리 결과의 min·q1·median·q3·max 컬럼을 읽어 그린다.
 * - 그런 컬럼이 없는 일반 쿼리면 예전에는 모든 값을 0 으로 읽어 납작한 선만 그리고 그대로 저장됐다.
 * - 이제는 미리보기에 필요한 컬럼을 안내하고, 저장을 막으며, 쓰이지 않는 「Y축 (다중 선택)」을 숨긴다.
 */

/** 이슈 재현 — 통계 컬럼이 없는 일반 쿼리(city·cnt·delta) */
const plainQueryResult = createQueryResult({
  columns: ['city', 'cnt', 'delta'],
  rows: [
    { city: '서울', cnt: 120, delta: -5.5 },
    { city: '부산', cnt: 80, delta: 3.2 },
    { city: '인천', cnt: 45, delta: 12.75 },
  ],
  totalRows: 3,
});

/** 통계 컬럼이 다 있는 쿼리 — 정상적으로 그려지고 저장된다 */
const statsQueryResult = createQueryResult({
  columns: ['region', 'min', 'q1', 'median', 'q3', 'max'],
  rows: [
    { region: '서울', min: 1, q1: 3, median: 5, q3: 7, max: 9 },
    { region: '부산', min: 2, q1: 4, median: 6, q3: 8, max: 10 },
  ],
  totalRows: 2,
});

async function runQueryAndSelectBoxplot(page: import('@playwright/test').Page) {
  await page.getByRole('combobox').click();
  await page.getByRole('option', { name: '저장 쿼리 1' }).click();
  await page.getByRole('button', { name: '쿼리 실행' }).click();
  await expect(page.getByText(/컬럼.*행 로드됨/)).toBeVisible();
  await page.getByRole('button', { name: '박스 플롯' }).click();
}

test.describe('박스 플롯 통계 컬럼 — ChartBuilderPage (WD-305)', () => {
  test('통계 컬럼이 없으면 미리보기에 필요한 컬럼을 안내하고 Y축 다중 선택을 숨긴다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', plainQueryResult);

    await page.goto('/analytics/charts/new');
    await runQueryAndSelectBoxplot(page);

    // 쓰이지 않는 Y축 선택 대신 무엇을 읽는지 안내한다
    await expect(page.getByText('Y축 (다중 선택)')).toHaveCount(0);
    await expect(page.getByTestId('boxplot-stats-hint')).toHaveText(
      '박스 플롯은 X축 카테고리별로 쿼리 결과의 min·q1·median·q3·max 컬럼을 읽어 그립니다.',
    );

    // 미리보기: 0 에 납작한 박스 대신 빠진 컬럼 안내
    const notice = page.getByTestId('boxplot-missing-stats');
    await expect(notice).toContainText('박스 플롯은 쿼리 결과에 min·q1·median·q3·max 컬럼이 있어야 그릴 수 있습니다.');
    await expect(notice).toContainText('없는 컬럼: min, q1, median, q3, max');
  });

  test('통계 컬럼이 없으면 저장을 막는다', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', plainQueryResult);

    let createCalled = false;
    await page.route('**/api/v1/analytics/charts', (route) => {
      if (route.request().method() === 'POST') createCalled = true;
      return route.continue();
    });

    await page.goto('/analytics/charts/new');
    await runQueryAndSelectBoxplot(page);

    await page.getByRole('button', { name: '저장' }).click();
    await page.getByLabel('이름 *').fill('통계 없는 박스 플롯');
    await page.getByRole('dialog').getByRole('button', { name: '저장' }).click();

    await expect(
      page.getByText(
        '박스 플롯은 쿼리 결과에 min·q1·median·q3·max 컬럼이 있어야 저장할 수 있습니다. 없는 컬럼: min, q1, median, q3, max',
      ),
    ).toBeVisible();
    await expect(page.getByRole('dialog')).toBeVisible();
    expect(createCalled).toBe(false);
  });

  test('통계 컬럼이 있으면 박스를 그리고 저장 payload 에 카테고리 X축이 담긴다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', statsQueryResult);
    const cap = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/charts',
      {
        id: 102,
        name: '지역별 분포',
        description: '',
        savedQueryId: 1,
        savedQueryName: '저장 쿼리 1',
        chartType: 'BOXPLOT',
        config: { xAxis: 'region', yAxis: [] },
        isShared: false,
        createdByName: '테스트',
        createdBy: 1,
        createdAt: '2024-01-01T00:00:00Z',
        updatedAt: '2024-01-01T00:00:00Z',
      },
      { capture: true },
    );

    await page.goto('/analytics/charts/new');
    await runQueryAndSelectBoxplot(page);

    // 안내 없이 카테고리별 박스(IQR rect)가 그려진다
    await expect(page.getByTestId('boxplot-missing-stats')).toHaveCount(0);
    // IQR 박스는 반투명 채움 rect — 아이콘 svg 의 rect 와 구분하려고 fill-opacity 로 찾는다
    await expect(page.locator('rect[fill-opacity="0.25"]')).toHaveCount(2);

    await page.getByRole('button', { name: '저장' }).click();
    await page.getByLabel('이름 *').fill('지역별 분포');
    await page.getByRole('dialog').getByRole('button', { name: '저장' }).click();

    const req = await cap.waitForRequest();
    expect(req.payload).toMatchObject({ name: '지역별 분포', chartType: 'BOXPLOT', config: { xAxis: 'region' } });
  });
});
