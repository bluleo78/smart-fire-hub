import { setupNewQueryEditorMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 쿼리 편집기 방언별 삽입·스니펫 E2E (WD-76)
 * - Tibero 테넌트: 스키마 한정·따옴표 없는 테이블 참조, LIMIT 대신 ROWNUM.
 * - PG 테넌트(dialect 없음): 기존 동작(따옴표 식별자, LIMIT 100) 그대로.
 */
const schemaTable = {
  tableName: 'orders',
  datasetName: '주문',
  datasetId: 1,
  columns: [{ columnName: 'region', dataType: 'VARCHAR', displayName: '지역' }],
};

async function openWithSchema(page: import('@playwright/test').Page, extra: object) {
  await setupNewQueryEditorMocks(page);
  // setup 이 건 기본 스키마 모킹을 방언 포함 응답으로 덮어쓴다(나중에 등록된 라우트가 우선)
  await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', { tables: [schemaTable], ...extra });
  await page.goto('/analytics/queries/new');
  await expect(page.getByText('orders', { exact: true })).toBeVisible();
}

const editorText = (page: import('@playwright/test').Page) => page.locator('.cm-content').innerText();

test.describe('쿼리 편집기 방언별 삽입 (WD-76)', () => {
  test('Tibero — 테이블 삽입은 스키마 한정·따옴표 없음', async ({ authenticatedPage: page }) => {
    await openWithSchema(page, { dialect: 'TIBERO', schema: 'genia_t5' });
    await page.getByText('orders', { exact: true }).hover();
    await page.getByTitle('테이블명 삽입').click();
    const text = await editorText(page);
    expect(text).toContain('genia_t5.orders');
    expect(text).not.toContain('"orders"');
  });

  test('Tibero — 빠른 쿼리는 ROWNUM, LIMIT 없음', async ({ authenticatedPage: page }) => {
    await openWithSchema(page, { dialect: 'TIBERO', schema: 'genia_t5' });
    await page.getByText('orders', { exact: true }).hover();
    await page.getByTitle('빠른 쿼리').click();
    await page.getByRole('menuitem').first().click();
    const text = await editorText(page);
    expect(text).toContain('WHERE ROWNUM <= 100');
    expect(text).toContain('genia_t5.orders');
    expect(text).not.toContain('LIMIT');
  });

  test('Tibero — 컬럼 삽입은 따옴표 없음', async ({ authenticatedPage: page }) => {
    await openWithSchema(page, { dialect: 'TIBERO', schema: 'genia_t5' });
    await page.getByText('orders', { exact: true }).click();
    await page.getByText('지역').hover();
    await page.getByTitle('컬럼명 삽입').click();
    const text = await editorText(page);
    expect(text).toContain('region');
    expect(text).not.toContain('"region"');
  });

  test('PG(dialect 없음) — 기존처럼 따옴표 식별자와 LIMIT 100', async ({ authenticatedPage: page }) => {
    await openWithSchema(page, {});
    await page.getByText('orders', { exact: true }).hover();
    await page.getByTitle('테이블명 삽입').click();
    expect(await editorText(page)).toContain('"orders"');

    await page.getByText('orders', { exact: true }).hover();
    await page.getByTitle('빠른 쿼리').click();
    await page.getByRole('menuitem').first().click();
    expect(await editorText(page)).toContain('LIMIT 100');
  });
});
