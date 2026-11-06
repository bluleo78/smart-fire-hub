import { setupNewQueryEditorMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 쿼리 편집기 왼쪽 테이블 목록 — 키보드 Tab 정지 (WD-526)
 * - 예전에는 테이블마다 「테이블 이름」「테이블명 삽입」「빠른 쿼리」 3개가 모두 Tab 을 받아, 테이블이 많으면
 *   SQL 편집기까지 Tab 을 수백 번 눌러야 했다.
 * - 이제 목록은 Tab 정지 하나(활성 테이블 한 줄)이고, 테이블 사이는 위·아래 방향키·Home·End 로 옮긴다.
 */
test.describe('쿼리 편집기 테이블 목록 키보드 이동 (WD-526)', () => {
  const TABLE_COUNT = 40;
  const tables = Array.from({ length: TABLE_COUNT }, (_, i) => ({
    tableName: `t_${String(i + 1).padStart(2, '0')}`,
    datasetName: `데이터셋 ${i + 1}`,
    datasetId: i + 1,
    columns: [{ columnName: 'region', dataType: 'VARCHAR', displayName: '지역' }],
  }));

  async function open(page: import('@playwright/test').Page) {
    await setupNewQueryEditorMocks(page);
    await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', { tables });
    await page.goto('/analytics/queries/new');
    await expect(page.getByRole('button', { name: 't_01', exact: true })).toBeVisible();
  }

  test('테이블이 40개여도 목록이 Tab 순서에 남기는 것은 활성 줄 3개뿐이다', async ({ authenticatedPage: page }) => {
    await open(page);
    const list = page.getByRole('group', { name: /테이블 목록/ });
    // 목록 안에서 Tab 으로 닿는(tabindex 가 -1 이 아닌) 컨트롤 수 — 예전에는 테이블 수 × 3 = 120개였다
    const tabbable = await list.evaluate(
      (el) =>
        Array.from(el.querySelectorAll<HTMLElement>('button, [href], input, [tabindex]')).filter(
          (n) => n.tabIndex >= 0 && !(n as HTMLButtonElement).disabled,
        ).length,
    );
    expect(tabbable).toBe(3);
  });

  test('검색 칸에서 Tab 한 번에 첫 테이블, 이어서 그 줄의 삽입·빠른 쿼리, 그다음은 목록을 벗어난다', async ({
    authenticatedPage: page,
  }) => {
    await open(page);
    await page.getByPlaceholder('테이블 · 컬럼 검색...').focus();

    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: 't_01', exact: true })).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: 't_01 테이블명 삽입' })).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: 't_01 빠른 쿼리' })).toBeFocused();
    await page.keyboard.press('Tab');
    // 둘째 테이블로 가지 않고 목록 밖으로 나간다
    const leftList = await page.evaluate(
      () => !document.activeElement?.closest('[role="group"][aria-label^="테이블 목록"]'),
    );
    expect(leftList).toBe(true);
  });

  test('방향키·Home·End 로 테이블을 옮기고, 옮긴 줄이 Tab 정지가 된다', async ({ authenticatedPage: page }) => {
    await open(page);
    const first = page.getByRole('button', { name: 't_01', exact: true });
    await first.focus();

    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('ArrowDown');
    const third = page.getByRole('button', { name: 't_03', exact: true });
    await expect(third).toBeFocused();
    await expect(third).toHaveAttribute('tabindex', '0');
    await expect(first).toHaveAttribute('tabindex', '-1');
    // 활성 줄의 삽입 버튼으로 Tab 이 이어진다
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: 't_03 테이블명 삽입' })).toBeFocused();

    await third.focus();
    await page.keyboard.press('End');
    await expect(page.getByRole('button', { name: 't_40', exact: true })).toBeFocused();
    await page.keyboard.press('Home');
    await expect(first).toBeFocused();
    await page.keyboard.press('ArrowUp');
    await expect(first).toBeFocused();
  });

  test('Enter 로 펼치면 aria-expanded 가 바뀌고 컬럼 삽입 버튼에 Tab 으로 닿는다', async ({ authenticatedPage: page }) => {
    await open(page);
    const second = page.getByRole('button', { name: 't_02', exact: true });
    await page.getByRole('button', { name: 't_01', exact: true }).focus();
    await page.keyboard.press('ArrowDown');
    await expect(second).toHaveAttribute('aria-expanded', 'false');
    await page.keyboard.press('Enter');
    await expect(second).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByText('지역')).toBeVisible();
    // 활성 줄의 삽입 → 빠른 쿼리 → 펼친 컬럼의 「컬럼명 삽입」 순으로 Tab 이 이어진다
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await expect(page.getByTitle('컬럼명 삽입')).toBeFocused();
  });

  test('검색으로 활성 테이블이 사라지면 남은 첫 테이블이 Tab 정지가 된다', async ({ authenticatedPage: page }) => {
    await open(page);
    await page.getByRole('button', { name: 't_01', exact: true }).focus();
    await page.keyboard.press('End');
    await page.getByPlaceholder('테이블 · 컬럼 검색...').fill('t_1');
    await expect(page.getByRole('button', { name: 't_10', exact: true })).toHaveAttribute('tabindex', '0');
    await expect(page.getByRole('button', { name: 't_11', exact: true })).toHaveAttribute('tabindex', '-1');
  });
});
