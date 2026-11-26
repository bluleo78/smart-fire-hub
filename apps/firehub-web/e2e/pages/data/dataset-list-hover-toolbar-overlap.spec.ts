import type { Page } from '@playwright/test';

import { createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';

/**
 * WD-1117: 데이터셋 목록 — 행 호버 툴바(미리보기·프로파일·내보내기)가 「삭제」 버튼을 덮지 않는다.
 * 툴바가 `absolute right-10` 이라 1440 폭에서 삭제 버튼과 12px 겹쳐, 삭제 버튼 왼쪽 끝을 누르면 내보내기가 열렸다.
 */
test.describe('데이터셋 목록 — 호버 툴바와 삭제 버튼 겹침 (WD-1117)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([createDataset({ id: 1, name: '고객 데이터셋' })]));
    await mockApi(page, 'GET', '/api/v1/datasets/1/references', {
      pipelines: [], dashboards: [], hiddenDashboardCount: 0, proactiveJobs: [], triggers: [],
    });
    await mockApi(page, 'GET', '/api/v1/datasets/1/export/estimate', {
      rowCount: 1, async: false, hasGeometryColumn: false, columns: [],
    });
  });

  async function hoverRow(page: Page) {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/data/datasets');
    const row = page.getByRole('row', { name: /고객 데이터셋/ });
    await expect(row).toBeVisible();
    await row.hover();
    return row;
  }

  test('툴바 오른쪽 끝이 삭제 버튼 왼쪽 끝을 넘지 않는다', async ({ authenticatedPage: page }) => {
    const row = await hoverRow(page);
    const toolbar = row.getByRole('button', { name: '내보내기' }).locator('xpath=..');
    await expect(toolbar).toHaveCSS('opacity', '1');
    const t = await toolbar.boundingBox();
    const d = await row.getByRole('button', { name: '삭제' }).boundingBox();
    expect(t && d).toBeTruthy();
    expect(t!.x + t!.width).toBeLessThanOrEqual(d!.x);
  });

  test('삭제 버튼 왼쪽 끝(x=2)을 눌러도 삭제 확인이 열린다', async ({ authenticatedPage: page }) => {
    const row = await hoverRow(page);
    await row.getByRole('button', { name: '삭제' }).click({ position: { x: 2, y: 16 } });
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByText('데이터 내보내기')).toHaveCount(0);
  });
});

/**
 * WD-1117 리뷰 후속: 삭제 권한이 없으면 「삭제」 버튼이 없어 묶음 폭이 0 이 된다.
 * 이때 툴바가 right-full 기준이면 생성일 칸을 통째로 덮고, 표 스크롤 상자 밖으로 잘릴 수도 있다.
 */
test.describe('데이터셋 목록 — 삭제 권한 없는 사용자의 호버 툴바 (WD-1117 후속)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page, ['dataset:read']);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([createDataset({ id: 1, name: '고객 데이터셋' })]));
  });

  async function hoverRow(page: Page) {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/data/datasets');
    const row = page.getByRole('row', { name: /고객 데이터셋/ });
    await expect(row).toBeVisible();
    await row.hover();
    return row;
  }

  test('삭제 버튼은 없고 툴바는 호버하면 보인다', async ({ authenticatedPage: page }) => {
    const row = await hoverRow(page);
    await expect(row.getByRole('button', { name: '삭제' })).toHaveCount(0);
    const toolbar = row.getByRole('button', { name: '프로파일' }).locator('xpath=..');
    await expect(toolbar).toHaveCSS('opacity', '1');
  });

  test('툴바가 표 스크롤 상자 안에 완전히 들어 있고 생성일 칸을 통째로 덮지 않는다', async ({ authenticatedPage: page }) => {
    const row = await hoverRow(page);
    const toolbar = row.getByRole('button', { name: '프로파일' }).locator('xpath=..');
    await expect(toolbar).toHaveCSS('opacity', '1');
    const t = await toolbar.boundingBox();
    const box = await page.locator('[data-slot=table-container]').boundingBox();
    const created = await row.getByRole('cell').nth(6).boundingBox();
    expect(t && box && created).toBeTruthy();
    expect(t!.x).toBeGreaterThanOrEqual(box!.x);
    expect(t!.x + t!.width).toBeLessThanOrEqual(box!.x + box!.width);
    // 칸 오른쪽 끝에 붙여 띄우므로 생성일 칸을 통째로 덮지 않는다(날짜 앞부분이 보인다) — 예전 right-10 과 비슷한 위치
    expect(t!.x).toBeGreaterThanOrEqual(created!.x + 32);
  });

  test('툴바 버튼 가운데를 누르면 그 버튼이 눌린다', async ({ authenticatedPage: page }) => {
    const row = await hoverRow(page);
    const btn = row.getByRole('button', { name: '프로파일' });
    await expect(btn).toBeVisible();
    const b = await btn.boundingBox();
    const hit = await page.evaluate(
      ([x, y]) => document.elementFromPoint(x, y)?.closest('button')?.getAttribute('aria-label'),
      [b!.x + b!.width / 2, b!.y + b!.height / 2],
    );
    expect(hit).toBe('프로파일');
  });
});

/** WD-1117 리뷰 후속: 호버 묶음 래퍼(inline-flex)가 행 높이를 키우지 않는다. */
test('삭제 권한이 있어도 행 높이는 PR #1446 이전 값과 같다', async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([createDataset({ id: 1, name: '고객 데이터셋' })]));
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/data/datasets');
  const row = page.getByRole('row', { name: /고객 데이터셋/ });
  await expect(row).toBeVisible();
  const b = await row.boundingBox();
  expect(Math.abs(b!.height - ROW_HEIGHT_BEFORE)).toBeLessThanOrEqual(1);
});

// 측정값(PR #1446 이전, 1440×900, 삭제 권한 있음)
const ROW_HEIGHT_BEFORE = 48.5;
