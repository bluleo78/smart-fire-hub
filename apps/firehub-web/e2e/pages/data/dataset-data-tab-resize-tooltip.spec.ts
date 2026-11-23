import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';

// (WD-977 리뷰 후속) 클래식 스크롤바 환경에서 열 폭·행 수정 버튼 툴팁을 검증한다.
test.use({ launchOptions: { ignoreDefaultArgs: ['--hide-scrollbars'] } });

test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

async function setup(page: import('@playwright/test').Page, names: string[]) {
  const columns = names.map((columnName, i) =>
    createColumn({ id: i + 1, columnName, displayName: columnName, dataType: 'TEXT', isPrimaryKey: i === 0, columnOrder: i }),
  );
  await mockApi(page, 'GET', '/api/v1/datasets/1', createDatasetDetail({ id: 1, rowCount: 3, columns }));
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);
  await page.route(
    (url) => url.pathname === '/api/v1/datasets/1/data',
    (route) => {
      const search = new URL(route.request().url()).searchParams.get('search') ?? '';
      const rows = search === 'zzz' ? [] : Array.from({ length: 3 }, (_, i) => Object.fromEntries(names.map((n) => [n, `${n}${i}`])));
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ columns, rows, page: 0, size: 50, totalElements: rows.length, totalPages: 1 }),
      });
    },
  );
  await page.setViewportSize({ width: 1283, height: 700 });
  await page.goto('/data/datasets/1');
  await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
  await page.getByRole('tab', { name: '데이터' }).click();
  await expect(page.getByRole('button', { name: '행 1 수정' })).toBeVisible();
}

// 열 폭 합 + 고정 열이 스크롤 영역 폭을 채우는지(넘침·빈 공간 없음, 1px 오차)
async function expectFilled(page: import('@playwright/test').Page) {
  const scroller = page.locator('table').first().locator('xpath=..').locator('xpath=self::div');
  await expect
    .poll(() => scroller.evaluate((el) => Math.abs(el.scrollWidth - el.clientWidth) <= 1 && Math.abs((el.querySelector('table') as HTMLElement).offsetWidth - el.clientWidth) <= 1))
    .toBe(true);
  // colgroup 폭 합이 clientWidth 와 같아야 빈 공간이 없다(열 최소폭 120 에 걸리지 않는 넓은 화면 기준).
  // 열마다 floor 로 최대 1px 버려지므로 허용 오차는 열 수만큼이다.
  const { sum, cw, cols } = await scroller.evaluate((el) => {
    const colEls = Array.from(el.querySelectorAll('col'));
    return { sum: colEls.reduce((a, c) => a + parseFloat((c as HTMLElement).style.width), 0), cw: el.clientWidth, cols: colEls.length };
  });
  expect(Math.abs(sum - cw)).toBeLessThanOrEqual(cols);
}

test.describe('데이터셋 데이터 탭 — WD-977 리뷰 후속', () => {
  test('검색 0건으로 표가 사라졌다 돌아와도 열 폭이 새 스크롤 영역을 채운다', async ({ authenticatedPage: page }) => {
    await setup(page, ['id', 'name', 'amount']);
    await expectFilled(page);
    const search = page.getByPlaceholder('데이터 검색...');
    await search.fill('zzz');
    await expect(page.getByText('검색 결과가 없습니다.')).toBeVisible();
    // 표가 없는 사이 창 폭을 바꿔, 새 요소를 관찰하지 않으면 옛 폭이 남게 한다
    await page.setViewportSize({ width: 1000, height: 700 });
    await search.fill('');
    await expect(page.getByRole('button', { name: '행 1 수정' })).toBeVisible();
    await expectFilled(page);
    // 이후 창 폭 변화도 계속 따라간다
    await page.setViewportSize({ width: 1400, height: 700 });
    await expectFilled(page);
  });

  test('다른 탭에 갔다 데이터 탭으로 돌아와도 열 폭이 창 폭을 계속 따라간다', async ({ authenticatedPage: page }) => {
    await setup(page, ['id', 'name', 'amount']);
    await expectFilled(page);
    // 데이터가 캐시돼 있어 돌아오면 표가 첫 렌더부터 붙는다 — StrictMode 의 정리·재실행 뒤에도 관찰이 살아 있어야 한다
    await page.getByRole('tab', { name: '필드' }).click();
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByRole('button', { name: '행 1 수정' })).toBeVisible();
    await page.setViewportSize({ width: 1000, height: 700 });
    await expectFilled(page);
  });

  test("이름에 '|' 가 든 열도 열 폭이 맞게 잡힌다", async ({ authenticatedPage: page }) => {
    await setup(page, ['id', 'a|b', 'c']);
    await expect(page.locator('colgroup col')).toHaveCount(5);
    await expectFilled(page);
  });

  test('행 수정 창을 닫으면 초점은 버튼으로 돌아오되 툴팁은 열리지 않고, Tab 으로 다시 오면 열린다', async ({ authenticatedPage: page }) => {
    await setup(page, ['id', 'name']);
    const btn = page.getByRole('button', { name: '행 1 수정' });
    await btn.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(btn).toBeFocused();
    // Radix 툴팁은 focus 때 바로 열리므로, 초점이 돌아온 뒤 트리거가 닫힘 상태면 억제된 것이다
    await expect(btn).toHaveAttribute('data-state', 'closed');
    await expect(page.getByRole('tooltip')).toHaveCount(0);
    await page.keyboard.press('Tab');
    await page.keyboard.press('Shift+Tab');
    await expect(btn).toBeFocused();
    await expect(page.getByRole('tooltip').first()).toBeVisible();
  });

  test('더블클릭으로 연 창을 닫은 뒤 곧바로 Tab 으로 수정 버튼에 오면 툴팁이 열린다', async ({ authenticatedPage: page }) => {
    await setup(page, ['id', 'name']);
    await page.getByRole('cell', { name: 'name0' }).dblclick();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    // 1초 억제 창 안에서 키보드로 버튼에 온다 — 버튼으로 연 창이 아니므로 억제하지 않아야 한다
    await page.getByRole('button', { name: '행 1 수정' }).focus();
    await expect(page.getByRole('tooltip').first()).toBeVisible();
  });
});
