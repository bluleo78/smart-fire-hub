import type { Page } from '@playwright/test';

import { createDocuments } from '../../factories/document.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';
import { setupDocumentDatasetMocks } from '../../fixtures/document.fixture';

/**
 * WD-724: 별도 벡터 저장소(Qdrant)가 잠깐 내려가 있으면 서버는 검색에 빈 목록(200)을 주고, 기능 응답의
 * vectorStoreAvailable=false 로 그 사실을 알린다. 문서 검색 화면은 「일시 중단」 안내를 띄우고 검색 버튼을 막는다.
 * 설치본이 지원하지 않는 경우(WD-495 Tibero 안내)와 달리 모드 탭·검색창은 그대로 두고, 복구되면 안내가 사라진다.
 */
const CAPS_PATH = '/api/v1/platform-db/capabilities';
const DATASET_ID = 1;
const PAUSED_BANNER = '검색 저장소가 일시 중단되어 지금은 문서 검색을 쓸 수 없습니다.';

const UP = { vectorSearch: true, trigramSearch: true, vectorStoreAvailable: true };
const DOWN = { vectorSearch: true, trigramSearch: true, vectorStoreAvailable: false };

/** 문서 데이터셋 상세의 문서 탭으로 이동한다. 검색 요청을 캡처해 돌려준다. */
async function gotoDocumentsTab(page: Page) {
  await setupDocumentDatasetMocks(page, DATASET_ID, createDocuments(1));
  const search = await mockApi(page, 'POST', '/api/v1/documents/search', [], { capture: true });
  await page.goto(`/data/datasets/${DATASET_ID}?tab=documents`);
  await expect(page.getByRole('heading', { name: '문서 검색' })).toBeVisible();
  return search;
}

function pausedBanner(page: Page) {
  return page.locator('[data-slot="inline-banner"][data-variant="warning"]', { hasText: PAUSED_BANNER });
}

test.describe('문서 검색 — 벡터 저장소 일시 중단 (WD-724)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
  });

  test('저장소가 내려가 있으면 안내를 띄우고 검색을 막는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', CAPS_PATH, DOWN);
    const search = await gotoDocumentsTab(page);

    await expect(pausedBanner(page)).toBeVisible();
    // 지원 여부는 그대로라 모드 탭·검색창은 남는다(설치본 꺼짐 안내와 다르다)
    await expect(page.getByRole('tab', { name: '하이브리드' })).toBeVisible();
    const input = page.getByPlaceholder('검색어를 입력하세요');
    await input.fill('소화 방식');
    await expect(page.getByRole('button', { name: '검색' })).toBeDisabled();
    await input.press('Enter');
    expect(search.requests).toHaveLength(0);
  });

  test('저장소가 정상이면 안내 없이 검색한다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', CAPS_PATH, UP);
    const search = await gotoDocumentsTab(page);

    await expect(page.getByText(PAUSED_BANNER)).toHaveCount(0);
    await page.getByPlaceholder('검색어를 입력하세요').fill('소화 방식');
    await page.getByRole('button', { name: '검색' }).click();
    const req = await search.waitForRequest();
    expect(req.payload).toMatchObject({ query: '소화 방식', mode: 'HYBRID' });
  });

  test('옛 서버(필드 없음)는 켜짐으로 본다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', CAPS_PATH, { vectorSearch: true, trigramSearch: true });
    await gotoDocumentsTab(page);

    await expect(page.getByRole('heading', { name: '문서 검색' })).toBeVisible();
    await expect(page.getByText(PAUSED_BANNER)).toHaveCount(0);
    await page.getByPlaceholder('검색어를 입력하세요').fill('소화 방식');
    await expect(page.getByRole('button', { name: '검색' })).toBeEnabled();
  });

  test('검색 중 저장소가 내려가 빈 결과가 오면 기능 상태를 다시 물어 안내를 띄운다', async ({
    authenticatedPage: page,
  }) => {
    // 화면을 열 때는 정상 — 그 뒤 저장소가 내려가 서버가 빈 목록을 준 경우다.
    await mockApi(page, 'GET', CAPS_PATH, UP);
    const search = await gotoDocumentsTab(page);
    await expect(page.getByText(PAUSED_BANNER)).toHaveCount(0);

    const caps = await mockApi(page, 'GET', CAPS_PATH, DOWN, { capture: true });
    await page.getByPlaceholder('검색어를 입력하세요').fill('소화 방식');
    await page.getByRole('button', { name: '검색' }).click();
    await search.waitForRequest();

    await caps.waitForRequest();
    await expect(pausedBanner(page)).toBeVisible();
    await expect(page.getByRole('button', { name: '검색' })).toBeDisabled();
  });
});
