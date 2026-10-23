import type { Page } from '@playwright/test';

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import {
  grantDatasetPermissions,
  setupDatasetDetailMocks,
  setupDatasetMocks,
} from '../../fixtures/dataset.fixture';

/**
 * WD-80: Tibero 저장소가 지원하지 않는 기능(GEOMETRY 타입·행 검색)은 화면에서 숨긴다.
 * GET /data-store/capabilities 응답으로 판단하고, 조회 실패·로딩 중에는 PG 기본값(그대로 노출)이다.
 */
const TIBERO_CAPS = { backend: 'TIBERO', geometry: false, rowSearch: false };
const PG_CAPS = { backend: 'POSTGRESQL', geometry: true, rowSearch: true };

test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

/** 데이터셋 생성 화면에서 첫 컬럼의 데이터 타입 드롭다운을 연다. */
async function openCreateTypeSelect(page: Page) {
  await setupDatasetMocks(page);
  await page.goto('/data/datasets/new');
  await expect(page.getByRole('heading', { name: '데이터셋 생성' })).toBeVisible();
  await page.getByRole('combobox').filter({ hasText: '텍스트' }).first().click();
}

/** 상세 화면 공통 모킹 후 이동한다. */
async function gotoDetail(page: Page, query = '') {
  await setupDatasetDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await page.goto(`/data/datasets/1${query}`);
}

test.describe('Tibero 미지원 기능 숨김 (WD-80)', () => {
  test('tibero: 컬럼 타입 목록에 지오메트리가 없다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', TIBERO_CAPS);
    await openCreateTypeSelect(page);
    await expect(page.getByRole('option', { name: '정수' })).toBeVisible();
    await expect(page.getByRole('option', { name: /지오메트리/ })).toHaveCount(0);
  });

  test('tibero: 필드 추가 대화상자 타입 목록에도 지오메트리가 없다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', TIBERO_CAPS);
    await gotoDetail(page, '?tab=columns');
    await page.getByRole('button', { name: '필드 추가' }).click();
    await page.getByRole('dialog').getByRole('combobox').click();
    await expect(page.getByRole('option', { name: '정수' })).toBeVisible();
    await expect(page.getByRole('option', { name: /지오메트리/ })).toHaveCount(0);
  });

  test('tibero: 데이터셋 상세에 검색 탭이 없다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', TIBERO_CAPS);
    await gotoDetail(page);
    await expect(page.getByRole('tab', { name: '데이터', exact: true })).toBeVisible();
    await expect(page.getByRole('tab', { name: '검색' })).toHaveCount(0);
  });

  test('tibero: ?tab=search 로 들어오면 기본 탭(정보)으로 대체된다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', TIBERO_CAPS);
    await gotoDetail(page, '?tab=search');
    await expect(page.getByRole('tab', { name: '정보' })).toHaveAttribute('data-state', 'active');
    await expect(page.getByRole('tab', { name: '검색' })).toHaveCount(0);
  });

  /**
   * 기능 조회가 데이터셋보다 늦게 끝나는 경우 — 예전에는 로딩 중 기본값(검색 지원)으로 검색 탭을 먼저 띄워
   * GET /search-index 가 나갔고, Tibero 는 422 를 돌려 오류 토스트와 탭 깜빡임이 생겼다.
   * 기능 응답을 데이터셋 로드 뒤로 붙잡아 두고, 그동안 색인 조회가 한 번도 나가지 않는지 본다.
   */
  test('tibero: ?tab=search 는 기능 조회 전에 검색 탭을 띄우지 않는다(색인 조회·오류 토스트 없음)', async ({
    authenticatedPage: page,
  }) => {
    let searchIndexRequests = 0;
    await page.route(
      (url) => url.pathname.startsWith('/api/v1/datasets/1/search-index'),
      (route) => {
        searchIndexRequests += 1;
        return route.fulfill({
          status: 422,
          contentType: 'application/json',
          body: JSON.stringify({ message: '행 검색: Tibero 저장소에서 아직 지원하지 않습니다' }),
        });
      },
    );
    let releaseCapabilities!: () => void;
    const capabilitiesGate = new Promise<void>((resolve) => {
      releaseCapabilities = resolve;
    });
    await page.route(
      (url) => url.pathname === '/api/v1/data-store/capabilities',
      async (route) => {
        await capabilitiesGate;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(TIBERO_CAPS),
        });
      },
    );

    await gotoDetail(page, '?tab=search');
    // 데이터셋은 로드됐지만 기능 조회는 아직 대기 중인 상태.
    await expect(page.getByRole('tab', { name: '정보' })).toBeVisible();

    const capabilitiesResponse = page.waitForResponse(
      (res) => new URL(res.url()).pathname === '/api/v1/data-store/capabilities',
    );
    releaseCapabilities();
    await capabilitiesResponse;

    await expect(page.getByRole('tab', { name: '정보' })).toHaveAttribute('data-state', 'active');
    await expect(page.getByRole('tab', { name: '검색' })).toHaveCount(0);
    expect(searchIndexRequests).toBe(0);
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
  });

  test('postgres: 지오메트리와 검색 탭이 그대로 있다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/data-store/capabilities', PG_CAPS);
    await openCreateTypeSelect(page);
    await expect(page.getByRole('option', { name: /지오메트리/ })).toBeVisible();
    await page.keyboard.press('Escape');

    await gotoDetail(page);
    await expect(page.getByRole('tab', { name: '검색' })).toBeVisible();
  });

  test('capabilities 조회 실패: 지금 화면 그대로', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/data-store/capabilities',
      { message: 'boom' },
      { status: 500 },
    );
    await openCreateTypeSelect(page);
    await expect(page.getByRole('option', { name: /지오메트리/ })).toBeVisible();
    await page.keyboard.press('Escape');

    await gotoDetail(page);
    await expect(page.getByRole('tab', { name: '검색' })).toBeVisible();
  });
});
