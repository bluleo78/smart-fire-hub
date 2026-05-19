/**
 * 차트 빌더 — MAP 히트맵 sub-mode E2E (#119)
 *
 * - 표시 모드 토글 노출/기본값
 * - heatmap 선택 시 colorByColumn → weightColumn 필드 스왑
 * - 저장 payload 에 mapDisplayMode/weightColumn 포함
 * - 50k 행 초과 시 경고 배지 노출
 */
import { createQueryResult } from '../../factories/analytics.factory';
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
});
