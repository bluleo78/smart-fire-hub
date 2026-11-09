import type { Page } from '@playwright/test';

import type { DataStoreCapabilities } from '../../../src/types/dataset';
import { createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks, setupDatasetMocks } from '../../fixtures/dataset.fixture';

/**
 * WD-636: 문자열(VARCHAR) 길이 입력 상한은 저장소마다 다르다(PostgreSQL 10000, Tibero 4000).
 * GET /data-store/capabilities 의 maxVarcharLength 를 데이터셋 생성 폼(SchemaBuilder)·필드 추가 대화상자(ColumnDialog)가
 * 상한으로 쓴다. 예전에는 Tibero 에서도 10000 까지 폼을 통과해 서버 400 토스트로만 알 수 있었다.
 * 조회 전·실패 시에는 PG 기본값 10000 이다(서버가 최종 판정).
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

test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

/** 생성 폼에서 첫 칼럼을 VARCHAR(length) 로 채운다. */
async function fillCreateFormWithVarchar(page: Page, length: string) {
  await setupDatasetMocks(page);
  await page.goto('/data/datasets/new');
  await expect(page.getByRole('heading', { name: '새 데이터셋' })).toBeVisible();
  await page.getByLabel('데이터셋 이름').fill('길이 상한');
  await page.getByLabel('테이블명').fill('varchar_limit');
  await page.getByPlaceholder('예: user_id').fill('title');
  await page.getByRole('combobox').filter({ hasText: '텍스트' }).click();
  await page.getByRole('option', { name: '문자열(크기지정)' }).click();
  await page.getByLabel('최대 길이 *').fill(length);
}

/** 상세 화면 필드 탭에서 필드 추가 대화상자를 열고 VARCHAR(length) 를 채운다. */
async function fillAddColumnDialogWithVarchar(page: Page, length: string) {
  await setupDatasetDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await page.goto('/data/datasets/1?tab=columns');
  await page.getByRole('button', { name: '필드 추가' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('필드명 *').fill('memo');
  await dialog.getByRole('combobox').click();
  await page.getByRole('option', { name: '문자열(크기지정)' }).click();
  await dialog.getByLabel('최대 길이 *').fill(length);
  return dialog;
}

test.describe('VARCHAR 길이 상한은 저장소 기능 응답을 따른다 (WD-636)', () => {
  test('tibero: 생성 폼에서 5000 은 「최대 4000」으로 막고 생성 요청을 보내지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', TIBERO_CAPS);
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99 }, { capture: true });
    await fillCreateFormWithVarchar(page, '5000');

    // 입력 칸의 max 속성도 저장소 상한이다(스피너·브라우저 범위 판정).
    await expect(page.getByLabel('최대 길이 *')).toHaveAttribute('max', '4000');
    await page.getByRole('button', { name: '만들기' }).click();

    await expect(page.getByText('최대 4000', { exact: true })).toBeVisible();
    expect(capture.requests).toHaveLength(0);
  });

  test('tibero: 생성 폼에서 4000 은 통과해 그대로 전송한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', TIBERO_CAPS);
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99 }, { capture: true });
    await fillCreateFormWithVarchar(page, '4000');
    await page.getByRole('button', { name: '만들기' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      columns: [
        expect.objectContaining({
          columnName: 'title',
          dataType: 'VARCHAR',
          maxLength: 4000,
        }),
      ],
    });
  });

  test('postgres: 생성 폼에서 10000 은 통과해 그대로 전송한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', PG_CAPS);
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99 }, { capture: true });
    await fillCreateFormWithVarchar(page, '10000');

    await expect(page.getByLabel('최대 길이 *')).toHaveAttribute('max', '10000');
    await page.getByRole('button', { name: '만들기' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      columns: [expect.objectContaining({ dataType: 'VARCHAR', maxLength: 10000 })],
    });
  });

  test('capabilities 조회 실패: PG 기본 상한 10000 — 10001 은 「최대 10000」으로 막는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', { message: 'boom' }, { status: 500 });
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99 }, { capture: true });
    await fillCreateFormWithVarchar(page, '10001');
    await page.getByRole('button', { name: '만들기' }).click();

    await expect(page.getByText('최대 10000', { exact: true })).toBeVisible();
    expect(capture.requests).toHaveLength(0);
  });

  test('tibero: 필드 추가 대화상자에서 5000 은 「최대 4000」으로 막고 추가 요청을 보내지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', TIBERO_CAPS);
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/columns',
      createDatasetDetail({ id: 1 }).columns[0],
      { capture: true },
    );
    const dialog = await fillAddColumnDialogWithVarchar(page, '5000');

    await expect(dialog.getByLabel('최대 길이 *')).toHaveAttribute('max', '4000');
    await dialog.getByRole('button', { name: '추가' }).click();

    await expect(dialog.getByText('최대 4000', { exact: true })).toBeVisible();
    expect(capture.requests).toHaveLength(0);
  });

  test('postgres: 필드 추가 대화상자에서 5000 은 통과해 그대로 전송한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', PG_CAPS);
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/columns',
      createDatasetDetail({ id: 1 }).columns[0],
      { capture: true },
    );
    const dialog = await fillAddColumnDialogWithVarchar(page, '5000');
    await dialog.getByRole('button', { name: '추가' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      columnName: 'memo',
      dataType: 'VARCHAR',
      maxLength: 5000,
    });
  });
});
