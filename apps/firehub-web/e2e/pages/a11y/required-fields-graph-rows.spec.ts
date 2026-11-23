/**
 * 그래프 매핑·행 편집·임포트 표 필수 칸 접근성 E2E (WD-964)
 *
 * 엔티티·관계 매핑 대화상자의 Select, 온톨로지 연결 Select, 행 추가 폼(NOT NULL 칼럼만), 임포트 매핑 표의
 * NOT NULL 표시가 별표 글자 대신 aria-required="true"·aria-hidden 별표·sr-only "필수" 로 알려지는지 고정한다.
 * native required 는 쓰지 않는다(WD-908·928).
 */
import type { Page } from '@playwright/test';
import path from 'path';
import { fileURLToPath } from 'url';

import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import {
  createBinding,
  createMappingDataset,
  createMappingResponse,
  createOntologySummaries,
  MAPPING_DATASET_ID,
  MAPPING_ONTOLOGY_ID,
} from '../../factories/mapping.factory';
import { createOntologySchema } from '../../factories/ontology.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';
import { expectAriaRequired, expectRequiredField } from '../../fixtures/required-a11y';

const MAPPING_URL = `/data/datasets/${MAPPING_DATASET_ID}?tab=mapping`;
const CSV_FILE = path.join(fileURLToPath(new URL('.', import.meta.url)), '../../fixtures/fire-incidents-sample.csv');

/** 매핑 탭 공통 목 — 쓰기 권한 사용자 */
async function setupMappingMocks(page: Page, bound: boolean) {
  await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['dataset:read', 'dataset:write']);
  await mockApi(page, 'GET', '/api/v1/categories', []);
  await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}`, createMappingDataset());
  await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/ontology`, createBinding(bound ? MAPPING_ONTOLOGY_ID : null));
  await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
  await mockApi(page, 'GET', `/api/v1/ontology/${MAPPING_ONTOLOGY_ID}`, createOntologySchema());
  await mockApi(page, 'GET', `/api/v1/datasets/${MAPPING_DATASET_ID}/mapping`, createMappingResponse({ status: 'draft' }));
}

test.describe('그래프 매핑·행 편집·임포트 표 필수 칸 aria-required (WD-964)', () => {
  test('엔티티 매핑 대화상자 — 엔티티 타입·이름 컬럼', async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page, true);
    await page.goto(MAPPING_URL);
    await page.getByRole('button', { name: '엔티티 매핑 추가' }).click();
    const dialog = page.getByTestId('entity-mapping-dialog');
    await expectRequiredField(dialog.getByRole('combobox', { name: '엔티티 타입', exact: true }), '엔티티 타입');
    await expectRequiredField(dialog.getByRole('combobox', { name: '이름 컬럼', exact: true }), '이름 컬럼');
  });

  test('관계 매핑 대화상자 — 주어·목적어·관계', async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page, true);
    await page.goto(MAPPING_URL);
    await page.getByRole('button', { name: '관계 매핑 추가' }).click();
    const dialog = page.getByTestId('relation-mapping-dialog');
    await expectRequiredField(dialog.getByRole('combobox', { name: '주어 엔티티', exact: true }), '주어 엔티티');
    await expectRequiredField(dialog.getByRole('combobox', { name: '목적어 엔티티', exact: true }), '목적어 엔티티');
    await expectRequiredField(dialog.getByRole('combobox', { name: '관계', exact: true }), '관계');
  });

  test('온톨로지 연결 카드 — 온톨로지 선택', async ({ authenticatedPage: page }) => {
    await setupMappingMocks(page, false);
    await page.goto(MAPPING_URL);
    const card = page.getByTestId('ontology-binding-card');
    await expectRequiredField(card.getByRole('combobox', { name: '온톨로지', exact: true }), '온톨로지');
  });

  test('행 추가 폼 — NOT NULL 칼럼에만 aria-required', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
    const detail = createDatasetDetail({
      id: 1,
      rowCount: 0,
      columns: [
        createColumn({ id: 1, columnName: 'title', displayName: '제목', dataType: 'VARCHAR', isPrimaryKey: false, isNullable: false, columnOrder: 0 }),
        createColumn({ id: 2, columnName: 'memo', displayName: '메모', dataType: 'VARCHAR', isPrimaryKey: false, isNullable: true, columnOrder: 1 }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', {
      backend: 'POSTGRESQL', geometry: true, rowSearch: true, maxVarcharLength: 10000, defaultVarcharLength: 255,
    });
    await mockApi(page, 'GET', '/api/v1/datasets/1', detail);
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
          body: JSON.stringify({ columns: detail.columns, rows: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
        }),
    );
    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: /행 추가/ }).click();
    const dialog = page.getByRole('dialog');

    // NOT NULL — 필수로 알려지고 라벨의 별표는 aria-hidden 이며 기존 빨간색이 유지된다
    await expectAriaRequired(dialog.locator('#add-title'));
    const star = dialog.locator('label[for="add-title"] span[aria-hidden="true"]');
    await expect(star).toHaveText('*');
    await expect(star).toHaveClass(/text-destructive/);
    // NULL 허용 — 주지 않는다
    await expect(dialog.locator('#add-memo')).not.toHaveAttribute('aria-required', 'true');
  });

  test('임포트 매핑 표 — NOT NULL 컬럼 옵션의 별표는 aria-hidden, 뜻은 sr-only 로 전달', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/datasets/1/imports', createPageResponse([]));
    await mockApi(page, 'POST', '/api/v1/datasets/1/imports/preview', {
      fileHeaders: ['id'],
      sampleRows: [{ id: '1' }],
      suggestedMappings: [{ fileColumn: 'id', datasetColumn: null, matchType: 'NONE', confidence: 0 }],
      totalRows: 1,
    });
    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();
    await page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE);
    await page.getByRole('combobox', { name: /컬럼을 매핑할 데이터셋 컬럼/ }).first().click();
    // 기본 데이터셋의 id 컬럼은 NOT NULL — 옵션에 별표(aria-hidden)와 sr-only 필수 안내가 있다
    const option = page.getByRole('option', { name: /ID/ }).first();
    await expect(option.locator('span[aria-hidden="true"]')).toHaveText('*');
    await expect(option.locator('span.sr-only')).toHaveText(/필수/);
    await expect(option).toHaveAccessibleName(/필수/);
  });
});
