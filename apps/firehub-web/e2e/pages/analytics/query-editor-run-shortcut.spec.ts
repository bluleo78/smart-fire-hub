import { createQueryResult } from '../../factories/analytics.factory';
import { setupQueryEditorMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

// 테스트 브라우저는 Desktop Chrome(Windows UA)이라 CodeMirror 의 Mod 가 Ctrl 이고 안내도 "Ctrl+Enter로 실행" 이다.
// ControlOrMeta 는 실행 호스트(macOS)를 따라 Meta 를 눌러 바인딩에 닿지 않으므로 Control 을 직접 누른다.
const RUN_SHORTCUT = 'Control+Enter';

// 데이터셋 「SQL」(쿼리 실행=data:import)은 권한이 있을 때만 보인다(WD-139) — 데이터셋 SQL 편집기를 열려면 쓰기 권한을 준다.
test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

/**
 * SQL 편집기 실행 단축키(Cmd/Ctrl+Enter) E2E (WD-87)
 * - 편집기 위 안내 "Cmd+Enter로 실행" 대로 누르면 쿼리가 실행돼야 한다.
 * - CodeMirror 기본 키맵의 Mod-Enter(insertBlankLine)가 먼저 처리되면 실행되지 않고 빈 줄만 들어가,
 *   그 빈 줄이 저장 SQL 끝에 남는다 — 줄 수가 그대로인지도 함께 본다.
 */
test.describe('SQL 편집기 실행 단축키 (WD-87)', () => {
  test('분석 쿼리 편집기 — Cmd/Ctrl+Enter 로 실행되고 빈 줄이 들어가지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    const execute = await mockApi(page, 'POST', '/api/v1/analytics/queries/execute', createQueryResult(), {
      capture: true,
    });

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();

    const lines = page.locator('.cm-line');
    const linesBefore = await lines.count();
    await page.locator('.cm-content').click();
    await page.keyboard.press(RUN_SHORTCUT);

    await execute.waitForRequest();
    await expect(page.getByRole('columnheader', { name: 'id' })).toBeVisible();
    await expect(lines).toHaveCount(linesBefore);
  });

  test('데이터셋 SQL 편집기 — Cmd/Ctrl+Enter 로 실행되고 빈 줄이 들어가지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetDetailMocks(page, 1);
    const execute = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/query',
      { queryType: 'SELECT', columns: ['x'], rows: [{ x: 1 }], affectedRows: 1, executionTimeMs: 3, error: null },
      { capture: true },
    );

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: 'SQL' }).click();
    // 안내 문구도 플랫폼에 맞춰 Ctrl 로 보여야 한다(전에는 Cmd 고정)
    await expect(page.getByText('Ctrl+Enter로 실행')).toBeVisible();

    const content = page.locator('.cm-content');
    await content.click();
    await page.keyboard.type('SELECT 1 AS x');
    await page.keyboard.press(RUN_SHORTCUT);

    const request = await execute.waitForRequest();
    expect(request.payload).toMatchObject({ sql: 'SELECT 1 AS x' });
    await expect(page.locator('.cm-line')).toHaveCount(1);
  });

  test('실행 중에 단축키를 다시 눌러도 같은 SQL 을 한 번만 보낸다', async ({ authenticatedPage: page }) => {
    await setupQueryEditorMocks(page, 1);
    // 응답을 붙잡아 두어 "실행 중" 상태를 만든다 — 그동안 들어온 요청 수를 센다
    let calls = 0;
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => (release = resolve));
    await page.route('**/api/v1/analytics/queries/execute', async (route) => {
      calls += 1;
      await held;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createQueryResult()) });
    });

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('테스트 쿼리')).toBeVisible();
    await page.locator('.cm-content').click();
    await page.keyboard.press(RUN_SHORTCUT);
    await expect.poll(() => calls).toBe(1);
    await page.keyboard.press(RUN_SHORTCUT);
    await page.keyboard.press(RUN_SHORTCUT);

    release();
    await expect(page.getByRole('columnheader', { name: 'id' })).toBeVisible();
    expect(calls).toBe(1);
  });
});

/**
 * macOS 사용자가 보는 경로 — 안내가 "Cmd+Enter로 실행" 이고 CodeMirror 의 Mod 가 Meta 다.
 * CodeMirror 는 navigator.platform 으로, 안내 문구는 userAgent 로 macOS 를 판정하므로 둘 다 맞춘다.
 */
test.describe('SQL 편집기 실행 단축키 — macOS (WD-87)', () => {
  test.use({
    userAgent:
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
  });

  test('분석 쿼리 편집기 — Cmd+Enter 안내대로 누르면 실행된다', async ({ authenticatedPage: page }) => {
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'platform', { get: () => 'MacIntel' });
    });
    await setupQueryEditorMocks(page, 1);
    const execute = await mockApi(page, 'POST', '/api/v1/analytics/queries/execute', createQueryResult(), {
      capture: true,
    });

    await page.goto('/analytics/queries/1');
    await expect(page.getByText('Cmd+Enter로 실행')).toBeVisible();

    const lines = page.locator('.cm-line');
    const linesBefore = await lines.count();
    await page.locator('.cm-content').click();
    await page.keyboard.press('Meta+Enter');

    await execute.waitForRequest();
    await expect(page.getByRole('columnheader', { name: 'id' })).toBeVisible();
    await expect(lines).toHaveCount(linesBefore);
  });

  test('데이터셋 SQL 편집기 — 안내가 Cmd+Enter 이고 그대로 누르면 실행된다', async ({ authenticatedPage: page }) => {
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'platform', { get: () => 'MacIntel' });
    });
    await setupDatasetDetailMocks(page, 1);
    const execute = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/query',
      { queryType: 'SELECT', columns: ['x'], rows: [{ x: 1 }], affectedRows: 1, executionTimeMs: 3, error: null },
      { capture: true },
    );

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: 'SQL' }).click();
    await expect(page.getByText('Cmd+Enter로 실행')).toBeVisible();

    await page.locator('.cm-content').click();
    await page.keyboard.type('SELECT 1 AS x');
    await page.keyboard.press('Meta+Enter');

    const request = await execute.waitForRequest();
    expect(request.payload).toMatchObject({ sql: 'SELECT 1 AS x' });
  });
});

