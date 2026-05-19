import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 상세 — 지도 탭 E2E 테스트
 *
 * DatasetMapTab.tsx / useColumnManager.ts 의 라인 커버리지 증가를 목표로 한다.
 * MapLibre는 canvas 기반이라 직접 조작이 어려우므로:
 * - GEOMETRY 컬럼 없을 때 안내 메시지 검증
 * - GEOMETRY 컬럼 있을 때 지도 탭 진입 + 맵 컨테이너 렌더링 확인
 * - 필드 탭(useColumnManager 초기화 경로) 관련 테스트도 포함
 */
test.describe('데이터셋 상세 — 지도 탭', () => {
  /** GEOMETRY 컬럼 없는 기본 데이터셋 (기본 factory와 동일) */
  const detailNoGeometry = createDatasetDetail({
    id: 1,
    rowCount: 2,
    columns: [
      createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true, columnOrder: 0 }),
      createColumn({ id: 2, columnName: 'name', displayName: '이름', dataType: 'TEXT', isPrimaryKey: false, columnOrder: 1 }),
    ],
  });

  /** GEOMETRY 컬럼을 포함한 데이터셋 — 지도 탭이 렌더링된다 */
  const detailWithGeometry = createDatasetDetail({
    id: 2,
    rowCount: 1,
    columns: [
      createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true, columnOrder: 0 }),
      createColumn({ id: 2, columnName: 'geom', displayName: '공간', dataType: 'GEOMETRY', isPrimaryKey: false, columnOrder: 1 }),
    ],
  });

  /** 데이터셋 공통 모킹 헬퍼 */
  async function setupMocks(
    page: import('@playwright/test').Page,
    datasetId: number,
    detail: typeof detailNoGeometry,
  ) {
    await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}`, detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}/queries`, createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', `/api/v1/datasets/${datasetId}/stats`, []);

    // /data 엔드포인트 — infinite query 방식으로 호출됨
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${datasetId}/data`,
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: detail.columns,
            rows: [{ _id: 1, id: 1 }],
            page: 0,
            size: 2000,
            totalElements: 1,
            totalPages: 1,
          }),
        }),
    );
  }

  test('GEOMETRY 컬럼이 없는 데이터셋은 지도 탭이 표시되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page, 1, detailNoGeometry);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 지도 탭이 없어야 한다 — hasGeometry=false 이므로 TabsTrigger 렌더링 안 됨
    await expect(page.getByRole('tab', { name: '지도' })).not.toBeVisible();
  });

  test('GEOMETRY 컬럼이 있는 데이터셋은 지도 탭이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page, 2, detailWithGeometry);

    await page.goto('/data/datasets/2');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // GEOMETRY 컬럼이 있으므로 지도 탭이 렌더링되어야 한다
    await expect(page.getByRole('tab', { name: '지도' })).toBeVisible();
  });

  test('지도 탭 클릭 시 맵 컨테이너가 렌더링된다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page, 2, detailWithGeometry);

    await page.goto('/data/datasets/2');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 지도 탭 클릭
    await page.getByRole('tab', { name: '지도' }).click();
    await expect(page.getByRole('tab', { name: '지도' })).toHaveAttribute('data-state', 'active');

    // DatasetMapTab 내 맵 컨테이너 — height:500px 인 div가 렌더링된다
    const mapContainer = page.locator('div[style*="height: 500px"], div[style*="height:500px"]');
    await expect(mapContainer).toBeVisible();
  });

  test('지도 탭: OpenFreeMap 크레딧 링크가 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page, 2, detailWithGeometry);

    await page.goto('/data/datasets/2');
    await page.getByRole('tab', { name: '지도' }).click();

    // 크레딧 텍스트
    await expect(page.getByRole('link', { name: 'OpenFreeMap' })).toBeVisible();
  });

  test('지도 탭: 데이터 로딩 후 표시 건수가 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page, 2, detailWithGeometry);

    await page.goto('/data/datasets/2');
    await page.getByRole('tab', { name: '지도' }).click();

    // 건수 텍스트 — "N건 표시" 패턴
    await expect(page.getByText(/건 표시/)).toBeVisible();
  });

  test('필드 탭 진입 시 useColumnManager 가 초기화되고 컬럼 목록이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page, 1, detailNoGeometry);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 필드 탭 클릭 → useColumnManager 훅 초기화 경로 커버
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('tab', { name: '필드' })).toHaveAttribute('data-state', 'active');

    // 컬럼 목록: 컬럼명 columnName 이 렌더링되어야 한다 (exact match로 strict mode 회피)
    await expect(page.getByText('id').first()).toBeVisible();
    await expect(page.getByText('name').first()).toBeVisible();
  });

  test('필드 탭: 필드 추가 버튼 클릭 시 다이얼로그가 열린다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page, 1, detailNoGeometry);

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '필드' }).click();

    // "필드 추가" 버튼 클릭 → addColumnOpen=true (useColumnManager의 setAddColumnOpen 경로)
    const addBtn = page.getByRole('button', { name: /필드 추가/ });
    await expect(addBtn).toBeVisible();
    await addBtn.click();

    // 다이얼로그 혹은 Sheet가 열림
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 5000 });
  });

  /**
   * 회귀 방지: MapView DARK_STYLE URL이 LIGHT_STYLE과 달라야 한다 (#180)
   *
   * MapLibre가 headless 환경에서 스타일 URL을 실제로 fetch하므로
   * page.route()로 타일 요청을 가로채 URL 패턴을 검증한다.
   * 다크 모드 전환 시 `/styles/dark` URL, 라이트 모드 시 `/styles/liberty` URL이
   * 요청되어야 하며, 두 URL이 달라야 한다.
   */
  // 회귀 가드(#258, #180): 라이트 모드에서 MapView 가 liberty 스타일을 요청한다.
  test('MapView: 라이트 모드에서 LIGHT_STYLE URL(/styles/liberty)이 요청된다', async ({
    authenticatedPage: page,
  }) => {
    const emptyStyle = { version: 8, sources: {}, layers: [] };
    const styleUrls: string[] = [];
    await page.route('https://tiles.openfreemap.org/styles/**', (route) => {
      styleUrls.push(route.request().url());
      void route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(emptyStyle),
      });
    });

    await page.addInitScript(() => localStorage.setItem('theme', 'light'));
    await setupMocks(page, 2, detailWithGeometry);
    await page.goto('/data/datasets/2');
    await page.getByRole('tab', { name: '지도' }).click();

    const mapContainer = page.locator('[data-map-style]').first();
    await expect(mapContainer).toHaveAttribute('data-map-style', 'light');
    await expect
      .poll(() => styleUrls.filter((u) => u.includes('/styles/liberty')).length)
      .toBeGreaterThan(0);
    expect(styleUrls.some((u) => u.includes('/styles/dark'))).toBe(false);
  });

  // 회귀 가드(#258, #180): 다크 모드에서 MapView 가 dark 스타일을 요청한다.
  // next-themes localStorage 를 사전 설정해 system 매체 쿼리 race 를 회피한다.
  test('MapView: 다크 모드에서 DARK_STYLE URL(/styles/dark)이 요청된다', async ({
    authenticatedPage: page,
  }) => {
    const emptyStyle = { version: 8, sources: {}, layers: [] };
    const styleUrls: string[] = [];
    await page.route('https://tiles.openfreemap.org/styles/**', (route) => {
      styleUrls.push(route.request().url());
      void route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(emptyStyle),
      });
    });

    await page.addInitScript(() => localStorage.setItem('theme', 'dark'));
    await setupMocks(page, 2, detailWithGeometry);
    await page.goto('/data/datasets/2');
    await page.getByRole('tab', { name: '지도' }).click();

    const mapContainer = page.locator('[data-map-style]').first();
    await expect(mapContainer).toHaveAttribute('data-map-style', 'dark');
    await expect
      .poll(() => styleUrls.filter((u) => u.includes('/styles/dark')).length)
      .toBeGreaterThan(0);
    // 핵심 회귀 검증: 다크 모드에서 liberty 가 호출되면 안 됨 — DARK_STYLE ≠ LIGHT_STYLE
    expect(styleUrls.some((u) => u.includes('/styles/liberty'))).toBe(false);
  });
});
