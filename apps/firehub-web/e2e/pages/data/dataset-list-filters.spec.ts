import { createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 목록 — 필터/미리보기 E2E 테스트
 *
 * DatasetListPage 의 datasetType / statusFilter / favoriteOnly / preview 모달
 * 분기와 useDatasets 훅의 파라미터 전달 경로를 커버한다. (기존 dataset-list.spec.ts
 * 에서는 기본 렌더링/삭제/페이지네이션만 다룬다.)
 */
test.describe('데이터셋 목록 — 필터 및 미리보기', () => {
  const datasets = [
    createDataset({
      id: 1,
      name: '원본 데이터셋 — 인증',
      datasetType: 'SOURCE',
      status: 'CERTIFIED',
      isFavorite: true,
      tags: ['공공', '화재', '2026', '중요', '정기'],
    }),
    createDataset({
      id: 2,
      name: '파생 데이터셋 — 사용중단',
      datasetType: 'DERIVED',
      status: 'DEPRECATED',
      isFavorite: false,
      tags: [],
    }),
    createDataset({
      id: 3,
      name: '임시 데이터셋',
      datasetType: 'TEMP',
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

  test('CERTIFIED/DEPRECATED 뱃지가 각각 표시되고 datasetType 뱃지도 렌더된다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    await captureListRequests(page);

    await page.goto('/data/datasets');

    // CERTIFIED → "Certified" 뱃지
    await expect(page.getByText(/Certified/)).toBeVisible();
    // DEPRECATED → "Deprecated" 뱃지
    await expect(page.getByText(/Deprecated/)).toBeVisible();
    // datasetType: SOURCE → "원본", DERIVED → "파생", TEMP → "임시"
    await expect(page.getByText('원본', { exact: true })).toBeVisible();
    await expect(page.getByText('파생', { exact: true })).toBeVisible();
    // "임시"는 tag 뱃지와 datasetType 뱃지 양쪽에 존재 가능 — 최소 1개 이상
    await expect(page.getByText('임시', { exact: true }).first()).toBeVisible();

    // 태그가 3개 초과면 "+N" 뱃지
    await expect(page.getByText('+2')).toBeVisible();
  });

  test('datasetType SELECT 변경 시 API 에 type 파라미터가 전달된다', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    const urls = await captureListRequests(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // datasetType SELECT — 첫 번째 콤보박스 (전체 유형)
    const typeCombobox = page.getByRole('combobox').filter({ hasText: /전체 유형|원본|파생|임시/ }).first();
    await typeCombobox.click();
    await page.getByRole('option', { name: '원본' }).click();

    // API 가 datasetType=SOURCE 로 재호출되었는지 확인
    await expect.poll(() => urls.some((u) => u.searchParams.get('datasetType') === 'SOURCE')).toBeTruthy();
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
});
