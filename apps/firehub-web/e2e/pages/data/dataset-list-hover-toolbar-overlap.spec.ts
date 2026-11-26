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
