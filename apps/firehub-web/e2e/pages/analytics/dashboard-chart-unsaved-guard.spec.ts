import type { Page } from '@playwright/test';

import { setupChartBuilderMocks, setupDashboardEditorMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 편집 모드·차트 편집의 미저장 변경 이탈 확인 (WD-323)
 * - 배치·크기는 「완료」를 눌러야 서버에 가고, 차트 설정은 「저장」을 눌러야 간다. 그 전에 사이드바로 나가면 확인 없이 변경이 사라졌다.
 */

/** 사이드바(앱 안 링크)로 이동 — 화면 안 버튼이 아니라 <a href> 경로를 탄다 */
const sidebarLink = (page: Page, href: string) => page.locator(`a[href="${href}"]`).first();

/** 편집 모드에서 위젯 오른쪽 아래 핸들을 끌어 폭을 줄인다 — dashboard-editor-widget.spec 의 #529 리사이즈와 같은 방식(WD-468) */
async function shrinkWidget(page: Page) {
  const gridItem = page.locator('.react-grid-item', { hasText: '테스트 차트' });
  const handle = gridItem.locator('.react-resizable-handle-se');
  await expect(handle).toBeVisible({ timeout: 5000 });
  await handle.hover();
  const box = (await handle.boundingBox())!;
  const initialWidth = (await gridItem.boundingBox())!.width;
  const renderedWidth = async () => (await gridItem.boundingBox())?.width ?? initialWidth;
  const startX = box.x + box.width / 2;
  const startY = box.y + box.height / 2;
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  for (let offset = 30; offset <= 300; offset += 30) {
    await page.mouse.move(startX - offset, startY, { steps: 3 });
  }
  await expect.poll(renderedWidth).toBeLessThan(initialWidth - 100);
  await page.mouse.up();
  await expect(gridItem).not.toHaveClass(/\bresizing\b/);
}

test.describe('대시보드 편집 — 미저장 배치 이탈 확인 (WD-323)', () => {
  test('배치를 바꾼 채 사이드바로 나가면 확인 창이 뜨고, 취소하면 편집 상태로 머문다', async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    const putCapture = await mockApi(page, 'PUT', '/api/v1/analytics/dashboards/1/widgets/1', {}, { capture: true });
    await page.goto('/analytics/dashboards/1');
    await page.getByRole('button', { name: '편집' }).click();
    await shrinkWidget(page);

    await sidebarLink(page, '/analytics/charts').click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?')).toBeVisible();

    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(page).toHaveURL('/analytics/dashboards/1');
    // 편집 상태·바꾼 배치가 그대로라 「완료」로 저장할 수 있다
    await page.getByRole('button', { name: '완료' }).click();
    const payload = (await putCapture.waitForRequest()).payload as { width: number };
    expect(payload.width).toBeLessThan(6);
  });

  test('확인 창에서 「이탈」을 누르면 저장 없이 이동한다', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    const putCapture = await mockApi(page, 'PUT', '/api/v1/analytics/dashboards/1/widgets/1', {}, { capture: true });
    await page.goto('/analytics/dashboards/1');
    await page.getByRole('button', { name: '편집' }).click();
    await shrinkWidget(page);

    await sidebarLink(page, '/analytics/charts').click();
    await page.getByRole('alertdialog').getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL('/analytics/charts');
    expect(putCapture.lastRequest()).toBeUndefined();
  });

  test('화면 안 「←」도 바꾼 배치가 있으면 확인 창을 거친다', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 1);
    await page.goto('/analytics/dashboards/1');
    await page.getByRole('button', { name: '편집' }).click();
    await shrinkWidget(page);

    // 툴바 첫 버튼(아이콘만 있는 ←)
    await page.locator('button:has(svg.lucide-arrow-left)').first().click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page).toHaveURL('/analytics/dashboards/1');
  });

  test('편집 모드여도 배치를 바꾸지 않았거나 「완료」로 저장했으면 확인 없이 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    await mockApi(page, 'PUT', '/api/v1/analytics/dashboards/1/widgets/1', {});
    await page.goto('/analytics/dashboards/1');

    // 바꾼 것 없이 편집 모드만 켠 채 이동
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.locator('.react-resizable-handle-se').first()).toBeVisible();
    await sidebarLink(page, '/analytics/charts').click();
    await expect(page).toHaveURL('/analytics/charts');
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    // 바꾼 뒤 「완료」 → 편집이 끝났으니 이동에 확인이 없다
    await page.goto('/analytics/dashboards/1');
    await page.getByRole('button', { name: '편집' }).click();
    await shrinkWidget(page);
    await page.getByRole('button', { name: '완료' }).click();
    await sidebarLink(page, '/analytics/charts').click();
    await expect(page).toHaveURL('/analytics/charts');
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });
});

test.describe('차트 편집 — 사이드바 이동 이탈 확인 (WD-323)', () => {
  test('차트 타입을 바꾼 채 사이드바로 나가면 확인 창이 뜬다', async ({ authenticatedPage: page }) => {
    await setupChartBuilderMocks(page, 1);
    await page.goto('/analytics/charts/1');
    await expect(page.getByText('테스트 차트')).toBeVisible();

    await page.getByRole('button', { name: '선 차트' }).click();
    await expect(page.getByText('미저장 변경사항')).toBeVisible();

    await sidebarLink(page, '/analytics/dashboards').click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?')).toBeVisible();
    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(page).toHaveURL('/analytics/charts/1');
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
  });
});
