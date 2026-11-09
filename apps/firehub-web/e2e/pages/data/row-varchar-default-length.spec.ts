import type { Page } from '@playwright/test';

import type { DataStoreCapabilities } from '../../../src/types/dataset';
import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';

/**
 * WD-659: 길이를 정하지 않은 문자열(VARCHAR) 필드의 실제 길이는 저장소마다 다르다(PostgreSQL 255, Tibero 4000).
 * 행 추가 폼은 GET /data-store/capabilities 의 defaultVarcharLength 로 길이를 검사한다 — 예전에는 255 고정이라
 * Tibero 테넌트에서 저장소가 받는 300자 값을 폼이 막았다. 단일 출처는 서버 DataStoreBackend.defaultVarcharLength.
 */
const TIBERO_CAPS: DataStoreCapabilities = {
  backend: 'TIBERO',
  geometry: false,
  rowSearch: false,
  maxVarcharLength: 4000,
  defaultVarcharLength: 4000,
};
const PG_CAPS: DataStoreCapabilities = {
  backend: 'POSTGRESQL',
  geometry: true,
  rowSearch: true,
  maxVarcharLength: 10000,
  defaultVarcharLength: 255,
};

/** 길이 미지정 VARCHAR 필드 하나(memo)를 가진 데이터셋. */
const datasetDetail = createDatasetDetail({
  id: 1,
  rowCount: 0,
  columns: [
    createColumn({
      id: 1,
      columnName: 'memo',
      displayName: '메모',
      dataType: 'VARCHAR',
      maxLength: null,
      isPrimaryKey: false,
      isNullable: true,
      columnOrder: 0,
    }),
  ],
});

const VALUE_300 = '가'.repeat(300);

test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

/** 상세 화면 데이터 탭에서 행 추가 대화상자를 열고 memo 칸에 값을 넣는다. */
async function openAddRowAndFill(page: Page, caps: DataStoreCapabilities, value: string) {
  await mockApi(page, 'GET', '/api/v1/data-store/capabilities', caps);
  await mockApi(page, 'GET', '/api/v1/datasets/1', datasetDetail);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);
  await page.route(
    (url) => url.pathname === '/api/v1/datasets/1/data',
    (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          columns: datasetDetail.columns,
          rows: [],
          page: 0,
          size: 50,
          totalElements: 0,
          totalPages: 0,
        }),
      }),
  );

  // 저장소 기능 응답을 받은 뒤 폼을 열어야 그 값으로 스키마가 만들어진다.
  const capsLoaded = page.waitForResponse((res) => res.url().includes('/api/v1/data-store/capabilities'));
  await page.goto('/data/datasets/1');
  await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
  await capsLoaded;
  await page.getByRole('tab', { name: '데이터' }).click();
  await page.getByRole('button', { name: /행 추가/ }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: '행 추가' })).toBeVisible();
  await page.locator('#add-memo').fill(value);
  return dialog;
}

test.describe('행 추가 — 길이 미지정 VARCHAR 는 저장소 기본 길이를 따른다 (WD-659)', () => {
  test('tibero(기본 4000): 300자를 통과시켜 그대로 전송한다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/data/rows',
      { id: 1, data: { memo: VALUE_300 }, createdAt: null },
      { capture: true },
    );
    const dialog = await openAddRowAndFill(page, TIBERO_CAPS, VALUE_300);
    await dialog.getByRole('button', { name: '추가' }).click();

    const req = await capture.waitForRequest();
    expect((req.payload as { data: Record<string, unknown> }).data).toEqual({ memo: VALUE_300 });
    await expect(dialog.getByText('최대 255자', { exact: true })).toHaveCount(0);
  });

  test('postgres(기본 255): 300자는 「최대 255자」로 막고 전송하지 않는다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'POST', '/api/v1/datasets/1/data/rows', { id: 1 }, { capture: true });
    const dialog = await openAddRowAndFill(page, PG_CAPS, VALUE_300);
    await dialog.getByRole('button', { name: '추가' }).click();

    await expect(dialog.getByText('최대 255자', { exact: true })).toBeVisible();
    expect(capture.requests).toHaveLength(0);
  });
});
