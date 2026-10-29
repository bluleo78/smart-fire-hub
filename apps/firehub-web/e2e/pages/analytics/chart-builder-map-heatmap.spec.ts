/**
 * 차트 빌더 — MAP 히트맵 sub-mode E2E (#119)
 *
 * - 표시 모드 토글 노출/기본값
 * - heatmap 선택 시 colorByColumn → weightColumn 필드 스왑
 * - 저장 payload 에 mapDisplayMode/weightColumn 포함
 * - 50k 행 초과 시 경고 배지 노출
 */
import { createChart, createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const baseGeoResult = createQueryResult({
  columns: ['id', 'name', 'cnt', 'geom'],
  rows: [
    { id: 1, name: 'A', cnt: 3, geom: { type: 'Point', coordinates: [127.0, 37.5] } },
    { id: 2, name: 'B', cnt: 7, geom: { type: 'Point', coordinates: [128.0, 37.6] } },
  ],
  totalRows: 2,
});

test.describe('차트 빌더 — MAP 히트맵 sub-mode', () => {
  test('표시 모드 토글이 노출되고 기본은 점', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', baseGeoResult);

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await page.getByRole('button', { name: '지도' }).click();

    await expect(page.getByRole('tab', { name: '점' })).toHaveAttribute('data-state', 'active');
    await expect(page.getByRole('tab', { name: '히트맵' })).toBeVisible();
  });

  test('히트맵 선택 시 colorByColumn 이 가중치 컬럼 셀렉트로 교체된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', baseGeoResult);

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await page.getByRole('button', { name: '지도' }).click();

    await expect(page.getByText('색상 기준 (선택사항)')).toBeVisible();
    await page.getByRole('tab', { name: '히트맵' }).click();

    await expect(page.getByText('색상 기준 (선택사항)')).toHaveCount(0);
    await expect(page.getByText('가중치 컬럼 (선택사항)')).toBeVisible();
  });

  test('저장 payload 에 mapDisplayMode 와 weightColumn 이 포함된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', baseGeoResult);

    let captured: Record<string, unknown> | undefined;
    await page.route('**/api/v1/analytics/charts', (route) => {
      if (route.request().method() === 'POST') {
        captured = route.request().postDataJSON() as Record<string, unknown>;
        return route.fulfill({
          status: 201,
          body: JSON.stringify({
            id: 99,
            name: '히트맵 차트',
            chartType: 'MAP',
            config: captured.config,
            savedQueryId: 1,
            savedQueryName: '저장 쿼리 1',
            isShared: false,
            createdByName: '테스트 사용자',
            createdBy: 1,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          }),
        });
      }
      return route.continue();
    });
    // 저장 후 navigate(/analytics/charts/99) 대응
    await mockApi(page, 'GET', '/api/v1/analytics/charts/99', {
      id: 99,
      name: '히트맵 차트',
      chartType: 'MAP',
      config: { spatialColumn: 'geom', mapDisplayMode: 'heatmap', weightColumn: 'cnt' },
      savedQueryId: 1,
      savedQueryName: '저장 쿼리 1',
      isShared: false,
      createdByName: '테스트 사용자',
      createdBy: 1,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    });

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await page.getByRole('button', { name: '지도' }).click();

    // 공간 컬럼 선택
    await page.getByRole('combobox', { name: '공간 컬럼' }).click();
    await page.getByRole('option', { name: 'geom' }).click();

    // 히트맵 모드 진입 + 가중치 cnt 선택
    await page.getByRole('tab', { name: '히트맵' }).click();
    await page.getByRole('combobox', { name: '가중치 컬럼' }).click();
    await page.getByRole('option', { name: 'cnt' }).click();

    // 저장 다이얼로그 열기
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByRole('heading', { name: '차트 저장' })).toBeVisible();
    await page.getByLabel('이름 *').fill('히트맵 차트');
    await page.getByRole('button', { name: '저장', exact: true }).last().click();

    await expect.poll(() => captured).toBeDefined();
    const config = (captured as { config: Record<string, unknown> }).config;
    expect(config).toMatchObject({
      spatialColumn: 'geom',
      mapDisplayMode: 'heatmap',
      weightColumn: 'cnt',
    });
    expect(config.colorByColumn).toBeUndefined();
  });

  test('행 수가 5만을 초과하면 경고 배지가 노출된다', async ({ authenticatedPage: page }) => {
    const bigRows = Array.from({ length: 50_001 }, (_, i) => ({
      id: i,
      name: `p${i}`,
      cnt: 1,
      geom: { type: 'Point', coordinates: [127 + (i % 100) * 0.01, 37 + (i % 100) * 0.01] },
    }));
    const bigResult = createQueryResult({
      columns: ['id', 'name', 'cnt', 'geom'],
      rows: bigRows,
      totalRows: bigRows.length,
    });

    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', bigResult);

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await page.getByRole('button', { name: '지도' }).click();

    await page.getByRole('combobox', { name: '공간 컬럼' }).click();
    await page.getByRole('option', { name: 'geom' }).click();
    await page.getByRole('tab', { name: '히트맵' }).click();

    await expect(page.getByTestId('heatmap-row-warning')).toBeVisible();
    await expect(page.getByTestId('heatmap-row-warning')).toContainText('50,001');
  });

  /**
   * WD-348: 히트맵 모드도 점 모드처럼 데이터 위치로 지도를 맞춘다.
   * 예전엔 히트맵만 범위 맞춤이 없어 기본 위치(서울)에 머물렀고, 부산 데이터는 「1행 기준」인데 빈 지도로 보였다.
   */
  test('히트맵 모드로 저장된 차트를 열면 서울 밖 데이터(부산)로 지도가 이동한다 (WD-348)', async ({ authenticatedPage: page }) => {
    // 지도 스타일은 빈 스타일로 대신한다 — 외부 타일 서버에 기대지 않고 지도 load 이벤트만 일으킨다
    await page.route('https://tiles.openfreemap.org/styles/**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ version: 8, sources: {}, layers: [] }) }),
    );
    // 처음부터 히트맵 모드인 저장 차트 — 점 모드를 거치지 않아야 히트맵 자체의 범위 맞춤을 확인할 수 있다
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts/34',
      createChart({ id: 34, chartType: 'MAP', config: { xAxis: '', yAxis: [], spatialColumn: 'geom', mapDisplayMode: 'heatmap' } }),
    );
    await setupNewChartBuilderMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/1/execute',
      createQueryResult({
        columns: ['id', 'geom'],
        rows: [{ id: 1, geom: { type: 'Point', coordinates: [129.07, 35.18] } }],
        totalRows: 1,
      }),
    );

    await page.goto('/analytics/charts/34');
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByRole('tab', { name: '히트맵' })).toHaveAttribute('data-state', 'active');

    // 지도 중심이 기본값(서울 127.00,37.50)이 아니라 부산 좌표로 옮겨져야 한다
    await expect(page.locator('[data-map-center]').first()).toHaveAttribute('data-map-center', '129.07,35.18', {
      timeout: 15_000,
    });
  });
  /**
   * WD-354: 가중치 목록에는 숫자 컬럼만 보인다. 예전엔 문자 컬럼(name)도 나와 고르면 모든 가중치가 0 이 되어 히트맵이 사라졌다.
   */
  test('가중치 컬럼 목록에는 숫자 컬럼만 보인다 (WD-354)', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/1/execute',
      createQueryResult({
        columns: ['id', 'name', 'cnt', 'ratio', 'geom'],
        rows: [
          // ratio 는 DECIMAL 처럼 문자열로 오고 한 행은 비어 있다 — 그래도 숫자 컬럼이다
          { id: 1, name: 'A', cnt: 3, ratio: '0.0001', geom: { type: 'Point', coordinates: [127.0, 37.5] } },
          { id: 2, name: 'B', cnt: 7, ratio: null, geom: { type: 'Point', coordinates: [128.0, 37.6] } },
        ],
        totalRows: 2,
      }),
    );

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await page.getByRole('button', { name: '지도' }).click();
    await page.getByRole('combobox', { name: '공간 컬럼' }).click();
    await page.getByRole('option', { name: 'geom' }).click();
    await page.getByRole('tab', { name: '히트맵' }).click();

    await page.getByRole('combobox', { name: '가중치 컬럼' }).click();
    await expect(page.getByRole('option')).toHaveText(['없음 — 균등 가중치', 'id', 'cnt', 'ratio']);
    await page.getByRole('option', { name: 'ratio' }).click();
    await expect(page.getByRole('combobox', { name: '가중치 컬럼' })).toHaveText('ratio');
    await expect(page.getByText('숫자 컬럼만 고를 수 있습니다.')).toBeVisible();
    await expect(page.getByTestId('heatmap-weight-ignored')).toHaveCount(0);
  });

  /**
   * WD-354: 예전에 문자 컬럼을 가중치로 골라 저장한 차트 — 히트맵이 사라지지 않고 균등 가중치로 그린다고 알리며,
   * 설정 칸에서는 숫자 컬럼을 고르라고 안내한다.
   */
  test('문자 컬럼을 가중치로 저장한 차트는 균등 가중치로 그린다고 안내한다 (WD-354)', async ({
    authenticatedPage: page,
  }) => {
    await page.route('https://tiles.openfreemap.org/styles/**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ version: 8, sources: {}, layers: [] }) }),
    );
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts/36',
      createChart({
        id: 36,
        chartType: 'MAP',
        config: { xAxis: '', yAxis: [], spatialColumn: 'geom', mapDisplayMode: 'heatmap', weightColumn: 'label' },
      }),
    );
    await setupNewChartBuilderMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/1/execute',
      createQueryResult({
        columns: ['id', 'label', 'geom'],
        rows: [
          { id: 1, label: '가', geom: { type: 'Point', coordinates: [127.0, 37.5] } },
          { id: 2, label: '나', geom: { type: 'Point', coordinates: [127.01, 37.5] } },
        ],
        totalRows: 2,
      }),
    );

    await page.goto('/analytics/charts/36');
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByRole('tab', { name: '히트맵' })).toHaveAttribute('data-state', 'active');

    await expect(page.getByTestId('heatmap-weight-ignored')).toHaveText(
      '가중치 컬럼 「label」에 숫자 값이 없어 균등 가중치로 표시합니다',
    );
    await expect(page.getByRole('alert').filter({ hasText: '숫자 컬럼이 아니라' })).toContainText(
      '「label」은(는) 숫자 컬럼이 아니라 가중치로 쓸 수 없어 균등 가중치로 그립니다. 숫자 컬럼을 고르세요.',
    );
    // 저장된 선택은 선택 상자에 그대로 보인다(빈 상자로 보이지 않게)
    await expect(page.getByRole('combobox', { name: '가중치 컬럼' })).toHaveText('label');
  });
});

