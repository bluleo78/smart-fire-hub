import type { Page } from '@playwright/test';

import { createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createSearchIndexStatus } from '../../factories/search-index.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 상세 "검색" 탭 E2E — 검색 대상 필드 선택·저장(payload)·상태 표시·재색인·오류.
 * 백엔드 없이 mockApi(page.route)로 모킹한다(경로는 /api/v1 프리픽스 포함 pathname).
 */
const DATASET_ID = 1;
const URL = `/data/datasets/${DATASET_ID}?tab=search`;

async function setupDataset(page: Page) {
  await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
  await mockApi(
    page,
    'GET',
    `/api/v1/datasets/${DATASET_ID}`,
    createDatasetDetail({
      id: DATASET_ID,
      columns: [
        createColumn({ id: 1, columnName: 'title', displayName: '제목', dataType: 'TEXT', columnOrder: 0 }),
        createColumn({ id: 2, columnName: 'content', displayName: '내용', dataType: 'TEXT', columnOrder: 1 }),
        createColumn({ id: 3, columnName: 'created', displayName: '접수일', dataType: 'DATE', columnOrder: 2 }),
      ],
    }),
  );
}

test.describe('데이터셋 검색 탭', () => {
  test('필드를 선택해 저장하면 fields 를 PUT 하고 색인 중 상태를 보여준다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupDataset(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/search-index`, createSearchIndexStatus());
    const put = await mockApi(
      page,
      'PUT',
      `/api/v1/datasets/${DATASET_ID}/search-index`,
      createSearchIndexStatus({ enabled: true, fields: ['title', 'content'], status: 'SYNCING', totalRows: 10 }),
      { capture: true },
    );
    await page.goto(URL);

    await expect(page.getByTestId('search-tab')).toBeVisible();
    await expect(page.getByTestId('search-index-status')).toHaveText(/꺼짐/);
    // DATE 필드는 선택 불가
    await expect(page.getByRole('checkbox', { name: '접수일' })).toBeDisabled();

    await page.getByRole('checkbox', { name: '제목' }).check();
    await page.getByRole('checkbox', { name: '내용' }).check();
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('button', { name: '저장하고 다시 색인' }).click(); // 확인 창

    const req = await put.waitForRequest();
    expect(req.payload).toEqual({ fields: ['title', 'content'] });
    await expect(page.getByTestId('search-index-status')).toHaveText(/색인 중/);
  });

  test('완료 상태는 진행률과 모델을 표시하고 다시 색인을 요청한다', async ({ authenticatedPage: page }) => {
    await setupDataset(page);
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}/search-index`,
      createSearchIndexStatus({ enabled: true, fields: ['content'], status: 'IDLE', indexedRows: 10000, totalRows: 10000, embeddingModel: 'bge-m3', lastSyncedAt: new Date().toISOString() }),
    );
    const reindex = await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/search-index/reindex`, null, { status: 202, capture: true });
    await page.goto(URL);

    await expect(page.getByTestId('search-index-status')).toHaveText(/사용 가능/);
    await expect(page.getByText('10,000 / 10,000행')).toBeVisible();
    await expect(page.getByText(/bge-m3/)).toBeVisible();
    await expect(page.getByRole('checkbox', { name: '내용' })).toBeChecked();

    await page.getByRole('button', { name: '다시 색인' }).click();
    await reindex.waitForRequest();
  });

  test('오류 상태는 메시지를 보여준다', async ({ authenticatedPage: page }) => {
    await setupDataset(page);
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}/search-index`,
      createSearchIndexStatus({ enabled: true, fields: ['content'], status: 'ERROR', lastError: '임베딩 서버 연결 실패' }),
    );
    await page.goto(URL);

    await expect(page.getByTestId('search-index-status')).toHaveText(/오류/);
    await expect(page.getByText('임베딩 서버 연결 실패')).toBeVisible();
  });

  test('DOCUMENT 데이터셋에는 검색 탭이 없다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, createDatasetDetail({ id: DATASET_ID, storageType: 'DOCUMENT' }));
    await page.goto(URL);

    // 페이지가 실제로 렌더링된 뒤에 부재를 단언한다(로드 실패로 인한 공허한 통과 방지)
    await expect(page.getByRole('tab', { name: '문서' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '검색' })).toHaveCount(0);
  });

  test('색인 중 폴링이 편집 중인 체크를 지우지 않는다', async ({ authenticatedPage: page }) => {
    await setupDataset(page);
    let calls = 0;
    await page.route(`**/api/v1/datasets/${DATASET_ID}/search-index`, (route) => {
      calls += 1; // 폴링마다 진행률만 바뀐 응답
      return route.fulfill({
        json: createSearchIndexStatus({ enabled: true, fields: ['content'], status: 'SYNCING', indexedRows: calls, totalRows: 10 }),
      });
    });
    await page.goto(URL);

    await page.getByRole('checkbox', { name: '제목' }).check();
    await expect.poll(() => calls, { timeout: 12_000 }).toBeGreaterThan(1);

    await expect(page.getByRole('checkbox', { name: '제목' })).toBeChecked();
  });

  test('저장 실패(400)는 서버 메시지를 토스트로 보여준다', async ({ authenticatedPage: page }) => {
    await setupDataset(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/search-index`, createSearchIndexStatus());
    await mockApi(page, 'PUT', `/api/v1/datasets/${DATASET_ID}/search-index`, { status: 400, message: '검색 대상은 TEXT/VARCHAR 필드만 가능합니다: x' }, { status: 400 });
    await page.goto(URL);

    await page.getByRole('checkbox', { name: '제목' }).check();
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('button', { name: '저장하고 다시 색인' }).click();

    await expect(page.getByText('검색 대상은 TEXT/VARCHAR 필드만 가능합니다: x')).toBeVisible();
  });
});
