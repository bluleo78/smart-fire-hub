import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';

// 쓰기 진입점은 서버 권한이 있을 때만 보인다(WD-139) — 이 파일은 편집·적재 흐름을 검증하므로 데이터셋 쓰기 권한을 준다.
// 개별 테스트가 다른 권한을 주면 그쪽이 나중에 등록돼 이긴다.
test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

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
  // 회귀 가드(WD-340): 범위 밖 좌표(POINT(999 999))가 섞여 있어도 그 도형만 건너뛰고 지도 탭이 깨지지 않는다.
  // 수정 전에는 maplibre fitBounds 가 「Invalid LngLat latitude value」를 던져 상세 화면 전체가 오류 경계 화면이 됐다.
  test('지도 탭: 범위 밖 좌표 행이 섞여 있어도 화면이 깨지지 않고 나머지 도형으로 경계를 잡는다 (WD-340)', async ({
    authenticatedPage: page,
  }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (m) => {
      if (m.type() === 'error') errors.push(m.text());
    });
    let styleRequested = false;
    await page.route('https://tiles.openfreemap.org/styles/**', (route) => {
      styleRequested = true;
      void route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ version: 8, sources: {}, layers: [] }),
      });
    });
    await setupMocks(page, 2, detailWithGeometry);
    // 나중에 등록한 라우트가 이긴다 — 정상 점 2개 + 범위 밖 점 1개
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/2/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: detailWithGeometry.columns,
            rows: [
              { _id: 1, id: 1, geom: '{"type":"Point","coordinates":[126.97,37.56]}' },
              { _id: 2, id: 2, geom: '{"type":"Point","coordinates":[999,999]}' },
              { _id: 3, id: 3, geom: '{"type":"Point","coordinates":[129.07,35.18]}' },
            ],
            page: 0,
            size: 2000,
            totalElements: 3,
            totalPages: 1,
          }),
        }),
    );

    await page.goto('/data/datasets/2');
    await page.getByRole('tab', { name: '지도' }).click();
    await expect(page.locator('[data-map-style]').first()).toBeVisible();
    await expect.poll(() => styleRequested).toBe(true);
    // 스타일 로드 뒤 setup(fitBounds)이 도는 시간을 준다
    await page.waitForTimeout(1000);

    // 범위 밖 행 1건은 빠지고 2건만 그린다는 것을 표시 건수로 안내한다
    await expect(page.getByText(/데이터: 2건 표시/)).toContainText('위치를 그릴 수 없는 1건 제외');
    await expect(page.getByText('페이지를 불러오는 중 문제가 발생했습니다')).toHaveCount(0);
    await expect(page.getByRole('tab', { name: '지도' })).toHaveAttribute('data-state', 'active');
    expect(errors.filter((e) => e.includes('Invalid LngLat'))).toEqual([]);
  });

  // 회귀 가드(WD-341): GeometryCollection 행도 지도 탭의 그릴 수 있는 행으로 세고 오류 없이 그린다.
  // (초기 경계 계산에 포함되는지는 fit-to-data.test.ts 단위 테스트가 좌표까지 검증한다.)
  test('지도 탭: GeometryCollection 행도 표시 건수에 포함되고 화면이 깨지지 않는다 (WD-341)', async ({
    authenticatedPage: page,
  }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    let styleRequested = false;
    await page.route('https://tiles.openfreemap.org/styles/**', (route) => {
      styleRequested = true;
      void route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ version: 8, sources: {}, layers: [] }),
      });
    });
    await setupMocks(page, 2, detailWithGeometry);
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/2/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: detailWithGeometry.columns,
            rows: [
              { _id: 1, id: 1, geom: '{"type":"Point","coordinates":[126.97,37.56]}' },
              { _id: 2, id: 2, geom: '{"type":"Point","coordinates":[129.07,35.18]}' },
              {
                _id: 3,
                id: 3,
                geom: '{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[-43.2,-22.9]},{"type":"LineString","coordinates":[[-46.6,-23.5],[-43.2,-22.9]]}]}',
              },
            ],
            page: 0,
            size: 2000,
            totalElements: 3,
            totalPages: 1,
          }),
        }),
    );

    await page.goto('/data/datasets/2');
    await page.getByRole('tab', { name: '지도' }).click();
    await expect(page.locator('[data-map-style]').first()).toBeVisible();
    await expect.poll(() => styleRequested).toBe(true);
    await page.waitForTimeout(1000);

    await expect(page.getByText(/데이터: 3건 표시/)).toBeVisible();
    await expect(page.getByText('페이지를 불러오는 중 문제가 발생했습니다')).toHaveCount(0);
    expect(errors).toEqual([]);
  });

  test('모바일 폭: 긴 값의 도형 정보 팝업이 지도 밖으로 잘리지 않는다 (WD-338)', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.route('https://tiles.openfreemap.org/styles/**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ version: 8, sources: {}, layers: [] }),
      }),
    );
    const detail = createDatasetDetail({
      id: 2,
      rowCount: 1,
      columns: [
        ...detailWithGeometry.columns,
        createColumn({ id: 3, columnName: 'place', displayName: '장소', dataType: 'TEXT', isPrimaryKey: false, columnOrder: 2 }),
      ],
    });
    await setupMocks(page, 2, detail);
    const longPlace = '아주 긴 지점 이름 — 서울특별시 중구 세종대로 110 서울시청 본관 앞 광장 동쪽 출입구 옆 화단';
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/2/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: detail.columns,
            rows: [
              {
                _id: 119,
                id: 119,
                place: longPlace,
                geom: '{"type":"Polygon","coordinates":[[[126.975,37.565],[126.98,37.565],[126.98,37.568],[126.975,37.568],[126.975,37.565]]]}',
              },
            ],
            page: 0,
            size: 2000,
            totalElements: 1,
            totalPages: 1,
          }),
        }),
    );

    await page.goto('/data/datasets/2');
    await page.getByRole('tab', { name: '지도' }).click();
    const mapBox = page.locator('[data-map-style]').first();
    await expect(mapBox).toBeVisible();
    await expect(page.getByText(/데이터: 1건 표시/)).toBeVisible();
    // 스타일 로드 뒤 fitBounds 로 도형이 지도 가운데를 채운다 — 가운데를 누르면 도형이 눌린다
    await page.waitForTimeout(1500);
    const box = (await mapBox.boundingBox())!;
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);

    const popup = page.locator('.maplibregl-popup');
    await expect(popup).toBeVisible();
    await expect(popup).toContainText(longPlace);
    // 가운데로 옮기는 애니메이션(300ms)이 끝난 뒤 팝업이 지도 영역 안에 있는지 본다
    await page.waitForTimeout(600);
    const p = (await popup.boundingBox())!;
    expect(p.x).toBeGreaterThanOrEqual(box.x);
    expect(p.x + p.width).toBeLessThanOrEqual(box.x + box.width + 0.5);
  });
});
