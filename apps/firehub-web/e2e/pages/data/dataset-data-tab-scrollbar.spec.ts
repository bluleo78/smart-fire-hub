import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';

// (WD-977) 클래식(공간을 차지하는) 세로 스크롤바 환경 — Playwright headless 는 기본으로 스크롤바를 숨겨(--hide-scrollbars)
// 폭 계산에서 스크롤바 폭이 빠져도 통과해 버리므로, 이 블록만 그 기본 인자를 빼고 스크롤바를 실제로 그린다.
// launchOptions 는 describe 안에서 못 쓰므로(새 worker 필요) 파일 최상위에 둔다 — 이 파일은 그래서 따로 분리했다.
test.use({ launchOptions: { ignoreDefaultArgs: ['--hide-scrollbars'] } });

test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

test.describe('데이터셋 상세 — 세로 스크롤바가 보일 때 열 폭 (WD-977)', () => {
  test('행이 많아 세로 스크롤바가 생겨도 표가 가로로 넘치지 않는다', async ({ authenticatedPage: page }) => {
    const columns = ['id', 'name', 'amount', 'price'].map((columnName, i) =>
      createColumn({ id: i + 1, columnName, displayName: columnName, dataType: i === 1 ? 'TEXT' : 'INTEGER', isPrimaryKey: i === 0, columnOrder: i }),
    );
    const detail = createDatasetDetail({ id: 1, rowCount: 50, columns });
    await mockApi(page, 'GET', '/api/v1/datasets/1', detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns,
            rows: Array.from({ length: 50 }, (_, i) => ({ id: i + 1, name: `n${i}`, amount: i, price: i })),
            page: 0,
            size: 50,
            totalElements: 50,
            totalPages: 1,
          }),
        }),
    );

    await page.setViewportSize({ width: 1283, height: 600 });
    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByRole('button', { name: '행 1 수정' })).toBeVisible();

    const scroller = page.locator('table').first().locator('xpath=..').locator('xpath=self::div');
    // 스크롤바가 실제로 폭을 차지하는지 먼저 확인 — 아니면 이 시험이 의미가 없다
    expect(await scroller.evaluate((el) => (el as HTMLElement).offsetWidth - el.clientWidth)).toBeGreaterThan(0);
    await expect.poll(() => scroller.evaluate((el) => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(1);
  });
});
