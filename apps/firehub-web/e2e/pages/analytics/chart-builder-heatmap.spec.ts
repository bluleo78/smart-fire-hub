import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 히트맵 차트 행/열/값(색상 기준) 매핑 E2E 테스트 (#664)
 * - AxisConfigPanel에 HEATMAP 전용 분기가 없으면 일반 X축/Y축(다중 선택) UI가
 *   그대로 노출되고, valueColumn을 지정/변경할 컨트롤이 전혀 없다.
 * - 행/열/값 컬럼이 서로 겹치면(예: 열=값) 미리보기가 깨지므로 경고를 표시하고
 *   저장을 막아야 한다.
 * - 세 컬럼을 모두 다르게 선택하면 저장 payload에 valueColumn이 담겨야 한다.
 */

// hour_slot 값은 "09시"처럼 숫자만이 아닌 문자열로 둔다 — 순수 숫자 문자열("09")은
// buildDefaultConfig의 isNumeric 휴리스틱(Number(v)가 NaN이 아니면 수치로 판단)에
// 걸려 카테고리가 아니라 수치 컬럼으로 오분류되어, HEATMAP 자동 추천/매핑 조건
// (카테고리 2개 + 수치 1개)이 깨지는 것을 피하기 위함이다.
const heatmapQueryResult = createQueryResult({
  columns: ['day_of_week', 'hour_slot', 'traffic'],
  rows: [
    { day_of_week: '월', hour_slot: '09시', traffic: 450 },
    { day_of_week: '월', hour_slot: '12시', traffic: 320 },
    { day_of_week: '화', hour_slot: '09시', traffic: 520 },
    { day_of_week: '화', hour_slot: '12시', traffic: 380 },
  ],
  totalRows: 4,
});

async function runQueryAndSelectHeatmap(page: import('@playwright/test').Page) {
  await page.getByRole('combobox').click();
  await page.getByRole('option', { name: '저장 쿼리 1' }).click();
  await page.getByRole('button', { name: '쿼리 실행' }).click();
  await expect(page.getByText(/컬럼.*행 로드됨/)).toBeVisible();
  await page.getByRole('button', { name: '히트맵' }).click();
}

test.describe('히트맵 행/열/값 매핑 — ChartBuilderPage (#664)', () => {
  test('축 설정 패널에 행/열/값(색상 기준) 3개의 독립된 select가 나타난다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', heatmapQueryResult);

    await page.goto('/analytics/charts/new');
    await runQueryAndSelectHeatmap(page);

    // 일반 차트의 "Y축 (다중 선택)" 체크박스 UI가 아니라 행/열/값 3개의 전용 select가 보여야 한다
    await expect(page.getByText('Y축 (다중 선택)')).not.toBeVisible();
    await expect(page.getByText('행 (카테고리)')).toBeVisible();
    await expect(page.getByText('열 (카테고리)')).toBeVisible();
    await expect(page.getByLabel('값 (색상 기준)')).toBeVisible();
  });

  test('행/열/값 컬럼이 겹치면 미리보기 경고가 뜨고 저장을 막는다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', heatmapQueryResult);

    let createCalled = false;
    await page.route('**/api/v1/analytics/charts', (route) => {
      if (route.request().method() === 'POST') {
        createCalled = true;
      }
      return route.continue();
    });

    await page.goto('/analytics/charts/new');
    await runQueryAndSelectHeatmap(page);

    // 열(yAxis[0])을 값(valueColumn)과 동일한 컬럼(traffic)으로 바꿔 겹치게 만든다
    // — 자동 감지로 이미 valueColumn=traffic이 선택돼 있으므로 열도 traffic으로 맞춘다
    await page.getByText('열 (카테고리)').locator('..').getByRole('combobox').click();
    await page.getByRole('option', { name: 'traffic' }).click();

    await expect(page.getByText('행/열/값(색상 기준) 컬럼을 서로 다르게 선택하세요.')).toBeVisible();

    await page.getByRole('button', { name: '저장' }).click();
    await page.getByLabel('이름 *').fill('겹친 히트맵');
    await page.getByRole('dialog').getByRole('button', { name: '저장' }).click();

    await expect(page.getByText('행, 열, 값 컬럼은 서로 달라야 합니다.')).toBeVisible();
    await expect(page.getByRole('dialog')).toBeVisible();
    expect(createCalled).toBe(false);
  });

  test('행/열/값을 모두 다르게 매핑하면 저장 payload에 valueColumn이 담긴다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', heatmapQueryResult);

    const cap = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/charts',
      {
        id: 102,
        name: '요일별 시간대 트래픽 히트맵',
        description: '',
        savedQueryId: 1,
        savedQueryName: '저장 쿼리 1',
        chartType: 'HEATMAP',
        config: {
          xAxis: 'day_of_week',
          yAxis: ['hour_slot'],
          valueColumn: 'traffic',
        },
        isShared: false,
        createdByName: '테스트',
        createdBy: 1,
        createdAt: '2024-01-01T00:00:00Z',
        updatedAt: '2024-01-01T00:00:00Z',
      },
      { capture: true },
    );

    await page.goto('/analytics/charts/new');
    await runQueryAndSelectHeatmap(page);

    // 자동 감지 결과(행=day_of_week, 열=hour_slot, 값=traffic)가 이미 유효하므로
    // 경고가 뜨지 않아야 한다.
    await expect(
      page.getByText('행/열/값(색상 기준) 컬럼을 서로 다르게 선택하세요.'),
    ).not.toBeVisible();

    await page.getByRole('button', { name: '저장' }).click();
    await page.getByLabel('이름 *').fill('요일별 시간대 트래픽 히트맵');
    await page.getByRole('dialog').getByRole('button', { name: '저장' }).click();

    const req = await cap.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '요일별 시간대 트래픽 히트맵',
      chartType: 'HEATMAP',
      config: {
        xAxis: 'day_of_week',
        yAxis: ['hour_slot'],
        valueColumn: 'traffic',
      },
    });
  });
});
