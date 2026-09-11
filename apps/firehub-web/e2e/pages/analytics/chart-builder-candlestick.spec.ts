import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 캔들스틱 차트 OHLC 필드 매핑 E2E 테스트 (#663)
 * - 쿼리 컬럼명이 open/high/low/close가 아닐 때 자동 감지가 실패해도
 *   AxisConfigPanel에서 시가/고가/저가/종가를 직접 매핑할 수 있어야 한다.
 * - 매핑이 비어 있으면 미리보기에 경고를 표시하고 저장을 막아야 한다.
 * - 매핑을 완료하면 저장 payload에 open/high/low/close가 실제 선택한 컬럼명으로 담겨야 한다.
 */

// OHLC와 무관한 컬럼명 — 자동 감지(#663 원인)가 실패하는 조건을 재현한다.
const nonOhlcQueryResult = createQueryResult({
  columns: ['trade_date', 'start_price', 'peak_price', 'bottom_price', 'end_price'],
  rows: [
    { trade_date: '2024-01-01', start_price: 100, peak_price: 120, bottom_price: 90, end_price: 110 },
    { trade_date: '2024-01-02', start_price: 110, peak_price: 130, bottom_price: 105, end_price: 125 },
  ],
  totalRows: 2,
});

async function runQueryAndSelectCandlestick(page: import('@playwright/test').Page) {
  await page.getByRole('combobox').click();
  await page.getByRole('option', { name: '저장 쿼리 1' }).click();
  await page.getByRole('button', { name: '쿼리 실행' }).click();
  await expect(page.getByText(/컬럼.*행 로드됨/)).toBeVisible();
  await page.getByRole('button', { name: '캔들스틱' }).click();
}

test.describe('캔들스틱 OHLC 필드 매핑 — ChartBuilderPage (#663)', () => {
  test('컬럼명이 open/high/low/close가 아니면 축 설정 패널에 OHLC 매핑 select가 나타난다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', nonOhlcQueryResult);

    await page.goto('/analytics/charts/new');
    await runQueryAndSelectCandlestick(page);

    // 일반 차트의 "Y축 (다중 선택)" 체크박스 UI가 아니라 4개의 OHLC 전용 select가 보여야 한다
    await expect(page.getByText('Y축 (다중 선택)')).not.toBeVisible();
    await expect(page.getByLabel('시가 (Open)')).toBeVisible();
    await expect(page.getByLabel('고가 (High)')).toBeVisible();
    await expect(page.getByLabel('저가 (Low)')).toBeVisible();
    await expect(page.getByLabel('종가 (Close)')).toBeVisible();

    // 자동 감지 실패 → 매핑 미설정 상태이므로 미리보기에 명시적 안내가 떠야 한다
    // (구 로직은 open=high=low=close=0 flat 캔들을 오류 없이 렌더링했다)
    await expect(page.getByText('시가/고가/저가/종가 컬럼을 선택하세요.')).toBeVisible();
  });

  test('OHLC 매핑이 비어 있으면 저장을 막는다', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', nonOhlcQueryResult);

    let createCalled = false;
    await page.route('**/api/v1/analytics/charts', (route) => {
      if (route.request().method() === 'POST') {
        createCalled = true;
      }
      return route.continue();
    });

    await page.goto('/analytics/charts/new');
    await runQueryAndSelectCandlestick(page);

    await page.getByRole('button', { name: '저장' }).click();
    await page.getByLabel('이름 *').fill('매핑 안 된 캔들스틱');
    await page.getByRole('dialog').getByRole('button', { name: '저장' }).click();

    // 검증 실패 토스트가 뜨고, 저장 다이얼로그는 닫히지 않으며, API는 호출되지 않아야 한다
    await expect(page.getByText('시가/고가/저가/종가 컬럼을 모두 선택하세요.')).toBeVisible();
    await expect(page.getByRole('dialog')).toBeVisible();
    expect(createCalled).toBe(false);
  });

  test('OHLC 4개 컬럼을 모두 매핑하면 저장 payload에 선택한 컬럼명이 담긴다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', nonOhlcQueryResult);

    const cap = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/charts',
      {
        id: 101,
        name: '주가 캔들스틱',
        description: '',
        savedQueryId: 1,
        savedQueryName: '저장 쿼리 1',
        chartType: 'CANDLESTICK',
        config: {
          xAxis: 'trade_date',
          yAxis: [],
          open: 'start_price',
          high: 'peak_price',
          low: 'bottom_price',
          close: 'end_price',
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
    await runQueryAndSelectCandlestick(page);

    // 4개 OHLC select를 실제 쿼리 컬럼명으로 매핑
    await page.getByLabel('시가 (Open)').click();
    await page.getByRole('option', { name: 'start_price' }).click();
    await page.getByLabel('고가 (High)').click();
    await page.getByRole('option', { name: 'peak_price' }).click();
    await page.getByLabel('저가 (Low)').click();
    await page.getByRole('option', { name: 'bottom_price' }).click();
    await page.getByLabel('종가 (Close)').click();
    await page.getByRole('option', { name: 'end_price' }).click();

    // 매핑 완료 → 경고 문구가 사라지고 캔들스틱 SVG가 렌더링돼야 한다
    await expect(page.getByText('시가/고가/저가/종가 컬럼을 선택하세요.')).not.toBeVisible();

    await page.getByRole('button', { name: '저장' }).click();
    await page.getByLabel('이름 *').fill('주가 캔들스틱');
    await page.getByRole('dialog').getByRole('button', { name: '저장' }).click();

    const req = await cap.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '주가 캔들스틱',
      chartType: 'CANDLESTICK',
      config: {
        open: 'start_price',
        high: 'peak_price',
        low: 'bottom_price',
        close: 'end_price',
      },
    });
  });
});
