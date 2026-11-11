import type { Page } from '@playwright/test';

import { createDocuments } from '../../factories/document.factory';
import { setupAdminAuth, setupEmbeddingMocks, setupSettingsMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';
import { setupDocumentDatasetMocks } from '../../fixtures/document.fixture';

/**
 * WD-495: 플랫폼 DB 가 벡터·트라이그램 검색을 끈 설치본(Tibero)에서는 문서 검색·임베딩 설정이 동작하지 않는다.
 * GET /platform-db/capabilities 응답으로 판단해 꺼진 모드 탭을 숨기고, 다 꺼지면 안내 배너만 둔다.
 * 조회 중·실패에는 켜짐(PG 기본값)으로 취급해 지금 화면을 그대로 둔다.
 */
const CAPS_PATH = '/api/v1/platform-db/capabilities';
const DATASET_ID = 1;
const DOC_BANNER = '이 설치본(Tibero)에서는 문서 검색을 쓸 수 없습니다.';
const EMBEDDING_BANNER = '이 설치본(Tibero)에서는 AI 벡터 검색이 꺼져 있어 임베딩 설정을 쓰지 않습니다.';

/** 문서 데이터셋 상세의 문서 탭으로 이동한다. 검색 요청을 캡처해 돌려준다. */
async function gotoDocumentsTab(page: Page) {
  await setupDocumentDatasetMocks(page, DATASET_ID, createDocuments(1));
  const search = await mockApi(page, 'POST', '/api/v1/documents/search', [], { capture: true });
  await page.goto(`/data/datasets/${DATASET_ID}?tab=documents`);
  await expect(page.getByRole('heading', { name: '문서 검색' })).toBeVisible();
  return search;
}

/** 검색 모드 탭 목록(이름)을 읽는다 — 데이터셋 상세의 상위 탭과 섞이지 않게 문서 검색 영역 안에서만 찾는다. */
function modeTabs(page: Page) {
  return page.locator('section', { has: page.getByRole('heading', { name: '문서 검색' }) }).getByRole('tab');
}

test.describe('문서 검색 — 플랫폼 DB 검색 꺼짐 (WD-495)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
  });

  test('둘 다 꺼지면 검색창 대신 안내 배너를 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', CAPS_PATH, { vectorSearch: false, trigramSearch: false });
    const search = await gotoDocumentsTab(page);

    const banner = page.locator('[data-slot="inline-banner"][data-variant="info"]', { hasText: DOC_BANNER });
    await expect(banner).toBeVisible();
    await expect(page.getByPlaceholder('검색어를 입력하세요')).toHaveCount(0);
    await expect(modeTabs(page)).toHaveCount(0);
    // 문서 목록 등 나머지 탭 내용은 그대로다
    await expect(page.getByText('document_1.pdf')).toBeVisible();
    expect(search.requests).toHaveLength(0);
  });

  test('벡터만 꺼지면 의미·하이브리드를 숨기고 키워드로 검색한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', CAPS_PATH, { vectorSearch: false, trigramSearch: true });
    const search = await gotoDocumentsTab(page);

    await expect(modeTabs(page)).toHaveText(['키워드']);
    await expect(page.getByRole('tab', { name: '키워드' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByText(DOC_BANNER)).toHaveCount(0);

    await page.getByPlaceholder('검색어를 입력하세요').fill('소화 방식');
    await page.getByRole('button', { name: '검색' }).click();
    const req = await search.waitForRequest();
    expect(req.payload).toMatchObject({ query: '소화 방식', mode: 'KEYWORD' });
  });

  test('트라이그램만 꺼지면 키워드·하이브리드를 숨기고 의미로 검색한다', async ({ authenticatedPage: page }) => {
    // 하이브리드는 벡터·트라이그램 둘 다 필요하다 — 하나라도 꺼지면 숨긴다(ai-agent 와 같은 규칙).
    await mockApi(page, 'GET', CAPS_PATH, { vectorSearch: true, trigramSearch: false });
    const search = await gotoDocumentsTab(page);

    await expect(modeTabs(page)).toHaveText(['의미']);
    await expect(page.getByRole('tab', { name: '의미' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByText(DOC_BANNER)).toHaveCount(0);

    await page.getByPlaceholder('검색어를 입력하세요').fill('소화 방식');
    await page.getByRole('button', { name: '검색' }).click();
    const req = await search.waitForRequest();
    expect(req.payload).toMatchObject({ query: '소화 방식', mode: 'SEMANTIC' });
  });

  test('PG(둘 다 켜짐)는 세 모드를 그대로 보인다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', CAPS_PATH, { vectorSearch: true, trigramSearch: true });
    await gotoDocumentsTab(page);

    await expect(modeTabs(page)).toHaveText(['하이브리드', '의미', '키워드']);
    await expect(page.getByText(DOC_BANNER)).toHaveCount(0);
  });

  test('capabilities 조회가 실패하면 켜짐으로 보고 세 모드를 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', CAPS_PATH, { message: 'boom' }, { status: 500 });
    await gotoDocumentsTab(page);

    await expect(modeTabs(page)).toHaveText(['하이브리드', '의미', '키워드']);
    await expect(page.getByText(DOC_BANNER)).toHaveCount(0);
  });

  test('capabilities 조회 중에는 배너를 띄우지 않고 세 모드를 보인다', async ({ authenticatedPage: page }) => {
    // 응답이 늦는 동안 PG 설치본에서 배너가 번쩍이지 않아야 한다 — 응답은 꺼짐이지만 도착 전 화면을 본다.
    await mockApi(page, 'GET', CAPS_PATH, { vectorSearch: false, trigramSearch: false }, { delayMs: 3000 });
    await gotoDocumentsTab(page);

    await expect(modeTabs(page)).toHaveText(['하이브리드', '의미', '키워드']);
    await expect(page.getByText(DOC_BANNER)).toHaveCount(0);
    // 응답이 오면 그때 안내로 바뀐다
    await expect(page.getByText(DOC_BANNER)).toBeVisible({ timeout: 10_000 });
  });
});

test.describe('관리 › 임베딩 설정 — 플랫폼 DB 벡터 꺼짐 (WD-495)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSettingsMocks(page);
    await setupEmbeddingMocks(page);
  });

  test('벡터가 꺼지면 설정 폼·재임베딩 대신 안내 배너를 보이고 다른 탭은 그대로다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', CAPS_PATH, { vectorSearch: false, trigramSearch: false });
    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();

    await expect(
      page.locator('[data-slot="inline-banner"][data-variant="info"]', { hasText: EMBEDDING_BANNER }),
    ).toBeVisible();
    await expect(page.getByText('임베딩 provider 설정')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '전체 재임베딩 실행' })).toHaveCount(0);
    await expect(page.getByText('데이터셋 카탈로그')).toHaveCount(0);
    await expect(page.getByText('임베딩이 설정되지 않았습니다')).toHaveCount(0);

    // 다른 설정 탭으로 옮겨도 정상 동작한다(임베딩 폼 상태는 페이지 소유 — 숨김이 다른 탭을 깨지 않는다)
    await page.getByRole('tab', { name: '이메일' }).click();
    await expect(page.getByText(EMBEDDING_BANNER)).toHaveCount(0);
    await expect(page.getByRole('tab', { name: '이메일' })).toHaveAttribute('aria-selected', 'true');
  });

  test('PG(벡터 켜짐)는 설정 폼과 재임베딩을 그대로 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', CAPS_PATH, { vectorSearch: true, trigramSearch: true });
    await page.goto('/admin/settings');
    await page.getByRole('tab', { name: '임베딩' }).click();

    await expect(page.getByText('임베딩 provider 설정')).toBeVisible();
    await expect(page.getByRole('button', { name: '전체 재임베딩 실행' })).toBeVisible();
    await expect(page.getByText(EMBEDDING_BANNER)).toHaveCount(0);
  });
});
