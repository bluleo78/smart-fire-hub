import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { setupChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 차트 컬럼 누락 안내 회귀 테스트 (WD-88)
 *
 * 원본 쿼리의 컬럼 이름이 바뀌면(region → area) 저장된 차트 설정이 결과에 없는 컬럼을 가리킨다.
 * 과거에는 빈 격자와 범례만 그리고 아무 안내가 없었다. 이제 차트 대신 「차트에 쓰는 컬럼이 쿼리 결과에 없습니다」와
 * 없는 컬럼 이름, 해결 방법을 보여 준다. 대시보드 위젯과 차트 편집 미리보기를 검증한다(AI 챗은 ai-chat 폴더).
 */

const NOTICE_TITLE = '차트에 쓰는 컬럼이 쿼리 결과에 없습니다';
const NOTICE_HINT = '원본 쿼리의 컬럼 이름이 바뀌었을 수 있습니다. 차트 편집에서 축을 다시 고르세요.';

/** 컬럼 이름이 바뀐 뒤의 쿼리 결과 — region → area, total → sum */
const RENAMED_RESULT = createQueryResult({
  columns: ['area', 'sum'],
  rows: [
    { area: '서울', sum: 120 },
    { area: '부산', sum: 80 },
  ],
  totalRows: 2,
});

test.describe('대시보드 위젯 — 차트 컬럼 누락 안내 (WD-88)', () => {
  test('이름이 바뀐 컬럼을 쓰는 위젯은 안내를, 정상 위젯은 차트를 보여 준다', async ({ authenticatedPage: page }) => {
    const staleWidget = createWidget({ id: 1, chartId: 1, chartName: '합계 막대', width: 6, height: 4 });
    const okWidget = createWidget({ id: 2, chartId: 2, chartName: '정상 막대', positionX: 6, width: 6, height: 4 });
    const staleChart = createChart({ id: 1, name: '합계 막대', config: { xAxis: 'region', yAxis: ['total'] } });
    const okChart = createChart({ id: 2, name: '정상 막대', config: { xAxis: 'area', yAxis: ['sum'] } });

    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets: [staleWidget, okWidget] }));
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', staleChart);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/2', okChart);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', { chart: staleChart, queryResult: RENAMED_RESULT });
    await mockApi(page, 'GET', '/api/v1/analytics/charts/2/data', { chart: okChart, queryResult: RENAMED_RESULT });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/analytics/dashboards/1');

    const staleCard = page.locator('[data-slot="card"]').filter({ hasText: '합계 막대' });
    const okCard = page.locator('[data-slot="card"]').filter({ hasText: '정상 막대' });

    // 누락 위젯: 안내 제목·없는 컬럼 이름(설정 순서)·해결 방법이 보이고 차트(svg)는 그리지 않는다
    const notice = staleCard.getByTestId('chart-missing-columns');
    await expect(notice).toBeVisible();
    await expect(notice).toContainText(NOTICE_TITLE);
    await expect(notice).toContainText(NOTICE_HINT);
    await expect(notice.locator('code')).toHaveText(['region', 'total']);
    await expect(staleCard.locator('.recharts-surface')).toHaveCount(0);

    // 정상 위젯: 안내 없이 차트가 그려진다
    await expect(okCard.locator('.recharts-surface').first()).toBeVisible();
    await expect(okCard.getByTestId('chart-missing-columns')).toHaveCount(0);
  });

  test('0행 결과여도 컬럼 이름이 틀렸으면 「데이터가 없습니다」 대신 컬럼 안내를 보여 준다', async ({
    authenticatedPage: page,
  }) => {
    const widget = createWidget({ id: 1, chartId: 1, chartName: '빈 결과 막대', width: 6, height: 4 });
    const chart = createChart({ id: 1, name: '빈 결과 막대', config: { xAxis: 'region', yAxis: ['sum'] } });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets: [widget] }));
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chart);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', {
      chart,
      queryResult: createQueryResult({ columns: ['area', 'sum'], rows: [], totalRows: 0 }),
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/analytics/dashboards/1');

    const card = page.locator('[data-slot="card"]').filter({ hasText: '빈 결과 막대' });
    const notice = card.getByTestId('chart-missing-columns');
    await expect(notice).toBeVisible();
    await expect(notice.locator('code')).toHaveText(['region']);
    await expect(card.getByText('데이터가 없습니다.')).toHaveCount(0);
  });
});

test.describe('차트 편집 미리보기 — 차트 컬럼 누락 안내 (WD-88)', () => {
  test('저장된 축이 결과에 없으면 미리보기에 안내가 나오고, 축을 다시 고르면 차트가 그려진다', async ({
    authenticatedPage: page,
  }) => {
    await setupChartBuilderMocks(page);
    // 기존 차트(id=1)의 저장된 축은 이름이 바뀌기 전 컬럼을 가리킨다
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts/1',
      createChart({ id: 1, config: { xAxis: 'region', yAxis: ['total'] } }),
    );
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', RENAMED_RESULT);

    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByText(/행 로드됨/).first()).toBeVisible();

    // 미리보기: 차트 대신 안내 + 없는 컬럼 이름
    const notice = page.getByTestId('chart-missing-columns');
    await expect(notice).toBeVisible();
    await expect(notice).toContainText(NOTICE_TITLE);
    await expect(notice.locator('code')).toHaveText(['region', 'total']);
    await expect(page.locator('.recharts-surface')).toHaveCount(0);

    // X축을 결과에 있는 컬럼으로 다시 고른다 — Y축(total)이 아직 없으므로 안내는 남고 목록이 줄어든다
    await page.getByLabel('X축', { exact: true }).click();
    await page.getByRole('option', { name: 'area', exact: true }).click();
    await expect(notice.locator('code')).toHaveText(['total']);

    // Y축을 다시 고르면(남아 있던 total 은 걷어 냄) 안내가 사라지고 차트가 그려진다
    await page.getByRole('checkbox', { name: 'sum' }).click();
    await expect(page.getByTestId('chart-missing-columns')).toHaveCount(0);
    await expect(page.locator('.recharts-surface').first()).toBeVisible();
  });

  test('결과에 없는 컬럼을 가리키면 저장을 막고, 축을 다시 고르면 고친 설정으로 저장된다', async ({
    authenticatedPage: page,
  }) => {
    await setupChartBuilderMocks(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts/1',
      createChart({ id: 1, config: { xAxis: 'region', yAxis: ['total'] } }),
    );
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', RENAMED_RESULT);
    const updateCapture = await mockApi(page, 'PUT', '/api/v1/analytics/charts/1', createChart({ id: 1 }), {
      capture: true,
    });
    let putCount = 0;
    page.on('request', (req) => {
      if (req.method() === 'PUT' && new URL(req.url()).pathname === '/api/v1/analytics/charts/1') putCount += 1;
    });

    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByTestId('chart-missing-columns')).toBeVisible();

    // 저장 시도 → 누락 컬럼을 알리는 오류로 막히고 PUT 은 나가지 않는다
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('dialog').getByRole('button', { name: '수정' }).click();
    await expect(page.getByText('쿼리 결과에 없는 컬럼이 있어 저장할 수 없습니다: region, total')).toBeVisible();
    expect(putCount).toBe(0);

    // 대화상자를 닫고 축을 다시 고른 뒤 저장하면 결과에 있는 컬럼만으로 저장된다
    await page.getByRole('dialog').getByRole('button', { name: '취소' }).click();
    await page.getByLabel('X축', { exact: true }).click();
    await page.getByRole('option', { name: 'area', exact: true }).click();
    await page.getByRole('checkbox', { name: 'sum' }).click();
    await expect(page.getByTestId('chart-missing-columns')).toHaveCount(0);
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('dialog').getByRole('button', { name: '수정' }).click();
    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ config: { xAxis: 'area', yAxis: ['sum'] } });
  });
});
