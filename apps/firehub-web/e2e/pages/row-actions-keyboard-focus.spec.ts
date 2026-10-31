import type { Locator, Page } from '@playwright/test';

import {
  setupChartListMocks,
  setupDashboardListMocks,
  setupNewQueryEditorMocks,
  setupQueryListMocks,
} from '../fixtures/analytics.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupDatasetMocks } from '../fixtures/dataset.fixture';

/**
 * WD-412: 마우스를 올려야 보이는 행 작업 버튼(opacity-0 group-hover:opacity-100)이 Tab 순서에는 있어,
 * 키보드로 가면 포커스가 투명한 버튼에 올라가 아무것도 보이지 않았다.
 * 이제 포커스가 버튼(또는 버튼 묶음 안)에 들어오면 보인다 — 조상까지 곱한 실제 불투명도로 확인한다.
 */

/** 요소와 모든 조상의 opacity 를 곱한 값 — 화면에 실제로 보이는 정도 */
function effectiveOpacity(locator: Locator) {
  return locator.evaluate((el) => {
    let value = 1;
    for (let node: Element | null = el; node; node = node.parentElement) {
      value *= parseFloat(getComputedStyle(node).opacity);
    }
    return value;
  });
}

/** 마우스를 화면 구석에 두고(hover 없음) 키보드 포커스를 준 뒤, 버튼이 완전히 보이는지 본다 */
async function expectVisibleOnKeyboardFocus(page: Page, button: Locator) {
  await page.mouse.move(0, 0);
  // 앞서 준 포커스가 같은 묶음에 남아 있지 않게 걷어낸다
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  // 포커스 전에는 숨어 있다 — hover 로만 보이던 원래 의도는 그대로다
  await expect.poll(() => effectiveOpacity(button)).toBe(0);
  await button.focus();
  await expect(button).toBeFocused();
  // transition-opacity 가 끝날 때까지 기다린다
  await expect.poll(() => effectiveOpacity(button)).toBe(1);
}

test.describe('행 작업 버튼 — 키보드 포커스가 오면 보인다 (WD-412)', () => {
  test('쿼리 목록의 실행·편집·삭제', async ({ authenticatedPage: page }) => {
    await setupQueryListMocks(page, 2);
    await page.goto('/analytics/queries');
    const row = page.getByRole('row').nth(1);
    await expectVisibleOnKeyboardFocus(page, row.getByRole('button', { name: '편집' }));
    // 같은 묶음의 다른 버튼도 함께 보인다(묶음 안 포커스)
    expect(await effectiveOpacity(row.getByRole('button', { name: '실행' }))).toBe(1);
  });

  test('차트 목록의 편집·삭제', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 2);
    await page.goto('/analytics/charts');
    const row = page.getByRole('row').nth(1);
    await expectVisibleOnKeyboardFocus(page, row.getByRole('button', { name: '편집' }));
  });

  test('대시보드 목록의 편집·설정·삭제', async ({ authenticatedPage: page }) => {
    await setupDashboardListMocks(page, 2);
    await page.goto('/analytics/dashboards');
    const row = page.getByRole('row').nth(1);
    await expectVisibleOnKeyboardFocus(page, row.getByRole('button', { name: '설정' }));
  });

  test('데이터셋 목록의 미리보기·프로파일', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');
    const button = page.getByRole('button', { name: '미리보기' }).first();
    await expectVisibleOnKeyboardFocus(page, button);
  });

  test('쿼리 편집기 테이블 목록의 「테이블명 삽입」·「빠른 쿼리」', async ({ authenticatedPage: page }) => {
    await setupNewQueryEditorMocks(page);
    await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', {
      tables: [
        {
          tableName: 'orders',
          datasetName: '주문',
          datasetId: 1,
          columns: [{ columnName: 'region', dataType: 'VARCHAR', displayName: '지역' }],
        },
      ],
    });
    await page.goto('/analytics/queries/new');
    await expect(page.getByText('orders', { exact: true })).toBeVisible();
    await expectVisibleOnKeyboardFocus(page, page.getByTitle('테이블명 삽입'));
    await expectVisibleOnKeyboardFocus(page, page.getByTitle('빠른 쿼리'));
  });
});
