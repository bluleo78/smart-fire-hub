import { createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 목록 — 필터/미리보기 E2E 테스트
 *
 * DatasetListPage 의 storageType/originType / statusFilter / favoriteOnly / preview 모달
 * 분기와 useDatasets 훅의 파라미터 전달 경로를 커버한다. (기존 dataset-list.spec.ts
 * 에서는 기본 렌더링/삭제/페이지네이션만 다룬다.)
 */
test.describe('데이터셋 목록 — 필터 및 미리보기', () => {
  const datasets = [
    createDataset({
      id: 1,
      name: '원본 데이터셋 — 인증',
      storageType: 'TABLE',
      originType: 'SOURCE',
      status: 'CERTIFIED',
      isFavorite: true,
      tags: ['공공', '화재', '2026', '중요', '정기'],
    }),
    createDataset({
      id: 2,
      name: '파생 데이터셋 — 사용중단',
      storageType: 'TABLE',
      originType: 'DERIVED',
      status: 'DEPRECATED',
      isFavorite: false,
      tags: [],
    }),
    createDataset({
      id: 3,
      name: '임시 데이터셋',
      storageType: 'TABLE',
      originType: 'TEMP',
      status: 'NONE',
      isFavorite: false,
      tags: ['임시'],
    }),
  ];

  async function captureListRequests(page: import('@playwright/test').Page) {
    const urls: URL[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        urls.push(new URL(route.request().url()));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(datasets)),
        });
      },
    );
    return urls;
  }

  async function setupCommon(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  }

  test('CERTIFIED/DEPRECATED 뱃지가 각각 표시되고 출처 뱃지도 렌더된다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    await captureListRequests(page);

    await page.goto('/data/datasets');

    // CERTIFIED → "Certified" 뱃지
    await expect(page.getByText(/Certified/)).toBeVisible();
    // DEPRECATED → "Deprecated" 뱃지
    await expect(page.getByText(/Deprecated/)).toBeVisible();
    // originType: SOURCE → "원본", DERIVED → "파생", TEMP → "임시"
    await expect(page.getByText('원본', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('파생', { exact: true }).first()).toBeVisible();
    // "임시"는 tag 뱃지와 출처 뱃지 양쪽에 존재 가능 — 최소 1개 이상
    await expect(page.getByText('임시', { exact: true }).first()).toBeVisible();

    // 태그가 3개 초과면 "+N" 뱃지
    await expect(page.getByText('+2')).toBeVisible();
  });

  test('출처 SELECT 변경 시 API 에 originType 파라미터가 전달된다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await captureListRequests(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // 출처 SELECT — "전체 출처" 트리거로 식별
    const originCombobox = page.getByRole('combobox').filter({ hasText: /전체 출처/ }).first();
    await originCombobox.click();
    await page.getByRole('option', { name: '원본' }).click();

    // API 가 originType=SOURCE 로 재호출되었는지 확인
    await expect.poll(() => urls.some((u) => u.searchParams.get('originType') === 'SOURCE')).toBeTruthy();
  });

  test('DOCUMENT 유형 데이터셋이 "문서" 배지로 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    // DOCUMENT 유형 데이터셋 1건을 포함한 목록을 모킹한다
    const documentDataset = createDataset({
      id: 10,
      name: '문서 데이터셋 — 소방청 고시',
      storageType: 'DOCUMENT',
      originType: 'SOURCE',
      status: 'NONE',
      isFavorite: false,
      tags: [],
    });

    await setupCommon(page);
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse([documentDataset])),
        }),
    );

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // DOCUMENT 유형 → "문서" 배지가 해당 행에 표시되어야 한다
    const docRow = page.getByRole('row', { name: /문서 데이터셋 — 소방청 고시/ });
    await expect(docRow).toBeVisible();
    await expect(docRow.getByText('문서', { exact: true })).toBeVisible();
  });

  test('저장방식 필터에서 "문서" 선택 시 API 에 storageType=DOCUMENT 가 전달된다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await captureListRequests(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // 저장방식 SELECT 드롭다운 열기 — "전체 저장방식" 트리거로 식별 후 "문서" 옵션 선택
    const storageCombobox = page
      .getByRole('combobox')
      .filter({ hasText: /전체 저장방식/ })
      .first();
    await storageCombobox.click();
    await page.getByRole('option', { name: '문서' }).click();

    // API 재호출 시 storageType=DOCUMENT 쿼리 파라미터가 포함되어야 한다
    await expect
      .poll(() => urls.some((u) => u.searchParams.get('storageType') === 'DOCUMENT'))
      .toBeTruthy();
  });

  test('즐겨찾기 필터 버튼 클릭 시 API 에 favoriteOnly=true 가 전달된다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await captureListRequests(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // "즐겨찾기" 필터 버튼 클릭 (exact: true 로 행 안의 "즐겨찾기 추가"와 구분)
    await page.getByRole('button', { name: '즐겨찾기', exact: true }).click();

    await expect.poll(() => urls.some((u) => u.searchParams.get('favoriteOnly') === 'true')).toBeTruthy();
  });

  test('미리보기 버튼 클릭 시 DatasetPreviewModal 이 열린다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    await captureListRequests(page);

    // 미리보기 모달이 내부적으로 /datasets/1 과 /datasets/1/data 를 요청할 수 있으므로 모킹
    await mockApi(page, 'GET', '/api/v1/datasets/1', {
      ...datasets[0],
      columns: [],
      sourceType: 'MANUAL',
      sourceConfig: {},
      rowCount: 0,
      updatedAt: '2026-01-01T00:00:00Z',
    });
    await mockApi(page, 'GET', '/api/v1/datasets/1/data', {
      columns: [],
      rows: [],
      page: 0,
      size: 50,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // 첫 번째 행의 "미리보기" 버튼 — hover-opacity 이므로 force click
    const previewBtn = page.getByRole('button', { name: '미리보기' }).first();
    await previewBtn.click({ force: true });

    // 모달이 열리면 어떤 형태로든 dialog role 이 생긴다
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  /**
   * 이슈 #111: 미리보기 다이얼로그 샘플 크기 셀렉트 회귀 방지.
   * - 기본 5행 + 셀렉트 변경(50) 시 size 파라미터가 50으로 API 재호출되는지 검증.
   * - 컬럼 헤더에 dataType 보조 텍스트가 노출되는지 검증.
   */
  test('미리보기 다이얼로그 — 샘플 크기 셀렉트 변경 시 size 파라미터가 API에 전달된다 (#111)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    await captureListRequests(page);

    await mockApi(page, 'GET', '/api/v1/datasets/1', {
      ...datasets[0],
      columns: [],
      sourceType: 'MANUAL',
      sourceConfig: {},
      rowCount: 0,
      updatedAt: '2026-01-01T00:00:00Z',
    });

    // /datasets/1/data 캡처 — size 파라미터가 변경되는지 검증
    const dataUrls: URL[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) => {
        dataUrls.push(new URL(route.request().url()));
        const size = Number(new URL(route.request().url()).searchParams.get('size') ?? 0);
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: [
              { id: 1, columnName: 'name', displayName: '이름', dataType: 'VARCHAR', maxLength: 50, isNullable: false, isIndexed: false, isPrimaryKey: false, description: null, columnOrder: 0 },
            ],
            rows: Array.from({ length: size }, (_, i) => ({ name: `row${i}` })),
            page: 0,
            size,
            totalElements: 100,
            totalPages: Math.ceil(100 / Math.max(size, 1)),
          }),
        });
      },
    );

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    const previewBtn = page.getByRole('button', { name: '미리보기' }).first();
    await previewBtn.click({ force: true });
    await expect(page.getByRole('dialog')).toBeVisible();

    // 초기 진입: size=5 가 전달되어야 함
    await expect.poll(() => dataUrls.some((u) => u.searchParams.get('size') === '5')).toBeTruthy();

    // dataType 보조 표시 확인 (컬럼 헤더에 'VARCHAR' 노출)
    await expect(page.getByRole('dialog').getByText('VARCHAR')).toBeVisible();

    // 샘플 크기 셀렉트 변경 → 50
    await page.getByLabel('샘플 크기').click();
    await page.getByRole('option', { name: '50행' }).click();

    // size=50 으로 재호출되는지 확인
    await expect.poll(() => dataUrls.some((u) => u.searchParams.get('size') === '50')).toBeTruthy();
  });
});
