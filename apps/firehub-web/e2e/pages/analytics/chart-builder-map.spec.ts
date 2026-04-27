/**
 * 차트 빌더 — MAP 차트 타입 E2E 테스트
 *
 * MapChartView / AxisConfigPanel 의 isMap 분기 / handleChartTypeChange 의
 * MAP ↔ 비MAP 전환 리빌드 경로 커버리지가 목표.
 *
 * 주의: MapLibre GL 은 headless Chromium 에서 webgl context 생성에
 *   시간이 걸리거나 실패할 수 있으므로, 본 테스트는 MAP 차트 타입 선택 →
 *   공간 컬럼 설정 → 저장 payload 검증까지만 수행하고
 *   지도 타일 자체의 픽셀 단위 렌더링은 검증 범위에서 제외한다.
 */

import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('차트 빌더 — MAP 차트 타입', () => {
  /** GeoJSON 포함 쿼리 결과 — geom 컬럼이 Point FeatureCollection */
  const geoQueryResult = createQueryResult({
    columns: ['id', 'name', 'geom'],
    rows: [
      {
        id: 1,
        name: 'A 화재',
        // isGeoJsonColumn 은 'type' + 'coordinates' 속성만 검사하므로
        // FeatureCollection/Feature wrap 없이 raw geometry 오브젝트로 공급한다.
        geom: { type: 'Point', coordinates: [127.0, 37.5] },
      },
      {
        id: 2,
        name: 'B 화재',
        geom: { type: 'Point', coordinates: [128.0, 37.6] },
      },
    ],
    totalRows: 2,
  });

  test('쿼리 결과가 GeoJSON 이면 MAP 차트가 자동 추천된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', geoQueryResult);

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();

    // 쿼리 실행 후 컬럼 카운트 요약
    await expect(page.getByText('3개 컬럼, 2개 행 로드됨')).toBeVisible();

    // 자동 추천으로 '지도' 버튼(aria-label)이 default variant(선택 상태)가 된다
    await expect(page.getByRole('button', { name: '지도' })).toHaveAttribute(
      'data-variant',
      'default',
    );
  });

  test('MAP 차트 저장 시 spatialColumn 이 POST payload 에 포함된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', geoQueryResult);

    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/charts',
      {
        id: 99,
        name: '화재 지도',
        description: null,
        savedQueryId: 1,
        savedQueryName: '저장 쿼리 1',
        chartType: 'MAP',
        config: { xAxis: '', yAxis: [], spatialColumn: 'geom' },
        isShared: false,
        createdByName: '테스트 사용자',
        createdBy: 1,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
      { capture: true },
    );

    // 저장 후 navigate(/analytics/charts/99) 대응
    await mockApi(page, 'GET', '/api/v1/analytics/charts/99', {
      id: 99,
      name: '화재 지도',
      description: null,
      savedQueryId: 1,
      savedQueryName: '저장 쿼리 1',
      chartType: 'MAP',
      config: { xAxis: '', yAxis: [], spatialColumn: 'geom' },
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
    await expect(page.getByText('3개 컬럼, 2개 행 로드됨')).toBeVisible();

    // MAP 자동 추천 확인
    await expect(page.getByRole('button', { name: '지도' })).toHaveAttribute(
      'data-variant',
      'default',
    );

    // 저장
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByRole('heading', { name: '차트 저장' })).toBeVisible();

    await page.getByLabel('이름 *').fill('화재 지도');
    await page.getByRole('button', { name: '저장', exact: true }).last().click();

    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '화재 지도',
      chartType: 'MAP',
      config: { spatialColumn: 'geom' },
    });
  });
});
