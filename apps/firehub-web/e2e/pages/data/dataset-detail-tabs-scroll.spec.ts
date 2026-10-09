import type { Page } from '@playwright/test';

import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 상세 탭 바 — 가로 넘침 발견성(WD-42 최종 수정).
 *
 * - 좁은 화면(<sm, 390px)은 tabs.tsx 기본대로 줄바꿈해 모든 탭이 한 화면에 보인다(#345 Reflow) — 가로·세로 스크롤러가 되지 않는다.
 *   (100fe9b2 의 overflow-x-auto 가 overflow-y 를 auto 로 만들어 탭 바가 1px 세로 스크롤러가 됐던 것도 함께 고정)
 * - sm 이상에서 탭이 본문 폭을 넘으면(지도 탭이 있는 표 데이터셋을 640px 에서 열 때 실측 610 > 592px) 가로 스크롤하고,
 *   잘린 쪽에만 페이드 + 화살표 배지를 띄우며, 활성 탭은 스크롤 밖에 있으면 끌어온다.
 */

/** 지도 탭까지 8개 탭이 생기는 표 데이터셋(GEOMETRY 컬럼 포함) + 상세 페이지 부가 호출 모킹. */
async function setupGeometryDetail(page: Page) {
  await setupDatasetDetailMocks(page, 1);
  await mockApi(
    page,
    'GET',
    '/api/v1/datasets/1',
    createDatasetDetail({
      id: 1,
      columns: [
        createColumn({ id: 1, columnName: 'name', displayName: '이름', dataType: 'TEXT', columnOrder: 0 }),
        createColumn({
          id: 2,
          columnName: 'geom',
          displayName: '공간',
          dataType: 'GEOMETRY',
          isPrimaryKey: false,
          columnOrder: 1,
        }),
      ],
    }),
  );
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
}

/** 탭 목록의 스크롤 치수. */
function listMetrics(page: Page) {
  return page.getByRole('tablist').first().evaluate((el) => ({
    scrollWidth: el.scrollWidth,
    clientWidth: el.clientWidth,
    scrollHeight: el.scrollHeight,
    clientHeight: el.clientHeight,
    scrollLeft: el.scrollLeft,
  }));
}

/** 활성 탭이 탭 목록의 보이는 폭 안에 온전히 들어와 있는지(스크롤 컨테이너의 잘림 기준). */
function activeTabFullyInsideList(page: Page) {
  return page.getByRole('tablist').first().evaluate((list) => {
    const active = list.querySelector('[role="tab"][data-state="active"]');
    if (!active) return false;
    const l = list.getBoundingClientRect();
    const a = active.getBoundingClientRect();
    return a.left >= l.left - 0.5 && a.right <= l.right + 0.5;
  });
}

/** 페이지(문서·본문 스크롤러)의 세로 스크롤 위치 합 — 활성 탭 끌어오기가 세로로 본문을 움직이지 않았는지 본다. */
function verticalScroll(page: Page) {
  return page.evaluate(() => {
    let sum = window.scrollY;
    document.querySelectorAll('*').forEach((el) => {
      sum += (el as HTMLElement).scrollTop;
    });
    return sum;
  });
}

test.describe('데이터셋 상세 탭 바 — 가로 넘침 발견성', () => {
  test('640px 에서 ?tab=history 로 열면 마지막 탭이 끌어와져 보이고 왼쪽 페이드만 남는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 640, height: 900 });
    await setupGeometryDetail(page);
    await page.goto('/data/datasets/1?tab=history');

    const history = page.getByRole('tab', { name: '이력' });
    await expect(history).toHaveAttribute('data-state', 'active');
    // 재현 조건 — 탭이 실제로 가로로 넘친다(공허한 통과 방지).
    const m = await listMetrics(page);
    expect(m.scrollWidth).toBeGreaterThan(m.clientWidth);
    // 활성 탭이 스크롤 밖에 남지 않는다(scrollIntoView 끌어오기).
    await expect.poll(() => activeTabFullyInsideList(page)).toBe(true);
    await expect(history).toBeInViewport({ ratio: 1 });
    // 끝까지 밀렸으므로 오른쪽 단서는 없고, 앞쪽 탭이 있다는 왼쪽 단서만 있다.
    await expect(page.locator('[data-slot="tabs-fade-left"]')).toBeVisible();
    await expect(page.locator('[data-slot="tabs-fade-right"]')).toHaveCount(0);
    // 끌어오기는 가로로만 — 페이지 세로 스크롤은 그대로다.
    expect(await verticalScroll(page)).toBe(0);
  });

  test('640px 첫 진입(정보 탭)은 오른쪽 페이드로 뒤쪽 탭을 알리고, 화살표를 누르면 넘어가며 왼쪽 페이드가 생긴다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 640, height: 900 });
    await setupGeometryDetail(page);
    await page.goto('/data/datasets/1');

    await expect(page.getByRole('tab', { name: '정보' })).toHaveAttribute('data-state', 'active');
    await expect(page.locator('[data-slot="tabs-fade-right"]')).toBeVisible();
    await expect(page.locator('[data-slot="tabs-fade-left"]')).toHaveCount(0);

    await page.locator('[data-slot="tabs-scroll-right"]').click();
    await expect.poll(async () => (await listMetrics(page)).scrollLeft).toBeGreaterThan(0);
    await expect(page.locator('[data-slot="tabs-fade-left"]')).toBeVisible();
    // 끝까지 넘기면 마지막 탭이 보이고 오른쪽 단서가 사라진다.
    await expect(page.locator('[data-slot="tabs-fade-right"]')).toHaveCount(0);
    await expect(page.getByRole('tab', { name: '이력' })).toBeInViewport({ ratio: 1 });
  });

  test('390px 에서는 탭이 줄바꿈되어 마지막 탭까지 모두 보이고 탭 바가 스크롤러가 아니다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await setupGeometryDetail(page);
    await page.goto('/data/datasets/1?tab=history');

    await expect(page.getByRole('tab', { name: '이력' })).toHaveAttribute('data-state', 'active');
    for (const name of ['정보', '보안', '필드', '데이터', '검색', '지도', '매핑', '이력']) {
      await expect(page.getByRole('tab', { name })).toBeInViewport({ ratio: 1 });
    }
    const m = await listMetrics(page);
    expect(m.scrollWidth).toBeLessThanOrEqual(m.clientWidth);
    // overflow-x-auto 만 두면 overflow-y 가 auto 가 되어 1px 세로 스크롤러가 된다(실측 71 > 70).
    expect(m.scrollHeight).toBeLessThanOrEqual(m.clientHeight);
    await expect(page.locator('[data-slot="tabs-fade-left"]')).toHaveCount(0);
    await expect(page.locator('[data-slot="tabs-fade-right"]')).toHaveCount(0);
  });

  test('1440px 에서는 넘침이 없어 페이드가 없고 탭 바가 세로 스크롤러가 아니다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await setupGeometryDetail(page);
    await page.goto('/data/datasets/1?tab=history');

    await expect(page.getByRole('tab', { name: '이력' })).toHaveAttribute('data-state', 'active');
    const m = await listMetrics(page);
    expect(m.scrollWidth).toBeLessThanOrEqual(m.clientWidth);
    expect(m.scrollHeight).toBeLessThanOrEqual(m.clientHeight);
    await expect(page.locator('[data-slot="tabs-fade-left"]')).toHaveCount(0);
    await expect(page.locator('[data-slot="tabs-fade-right"]')).toHaveCount(0);
  });
});
