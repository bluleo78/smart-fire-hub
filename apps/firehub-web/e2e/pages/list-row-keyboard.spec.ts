import { setupAdminAuth, setupApiConnectionListMocks, setupAuditLogMocks } from '../fixtures/admin.fixture';
import { createPageResponse, mockApi, type MockApiCapture } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetMocks } from '../fixtures/dataset.fixture';
import { grantPipelinePermissions, setupPipelineMocks } from '../fixtures/pipeline.fixture';

/**
 * 목록 화면 키보드 경로 회귀 테스트(WD-396).
 *
 * 데이터셋·파이프라인·API 연결 목록의 행, 데이터셋 「최근 접근」 카드·카테고리 칩, 감사 로그 행은 마우스 클릭(onClick)으로만
 * 열려 Tab 이 건너뛰었다 — 키보드·스크린리더 사용자는 목록에서 상세를 열거나 카테고리로 거를 수 없었다.
 * 이름을 상세 링크(감사 로그는 일시 칸을 상세 버튼)로, 칩을 button 으로 두어 Tab 으로 닿고 Enter 로 동작하는지 본다.
 */

test.describe('데이터셋 목록 — 키보드 경로', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
  });

  test('데이터셋 이름은 상세 링크라 포커스한 뒤 Enter 로 상세를 연다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    const link = page.getByRole('link', { name: '데이터셋 2', exact: true });
    await expect(link).toHaveAttribute('href', '/data/datasets/2');
    await link.focus();
    await expect(link).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/data\/datasets\/2$/);
  });

  test('「최근 접근」 카드는 링크라 Tab 으로 닿고 Enter 로 상세를 연다', async ({ authenticatedPage: page }) => {
    await page.addInitScript(() => {
      localStorage.setItem(
        'sfh-recent-datasets:1:1',
        JSON.stringify([{ id: 77, name: '최근 본 데이터셋', tableName: 'recent_77', accessedAt: new Date().toISOString() }]),
      );
    });
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    const card = page.getByRole('link', { name: /최근 본 데이터셋/ });
    await expect(card).toHaveAttribute('href', '/data/datasets/77');
    await card.focus();
    await expect(card).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/data\/datasets\/77$/);
  });

  test('카테고리 칩은 「카테고리」 묶음의 버튼이라 Enter 로 거르고 고른 칩을 aria-pressed 로 알린다', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');
    await expect(page.getByRole('link', { name: '데이터셋 1', exact: true })).toBeVisible();

    const capture: MockApiCapture = await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]), {
      capture: true,
    });
    const group = page.getByRole('group', { name: '카테고리' });
    const all = group.getByRole('button', { name: '전체' });
    const chip = group.getByRole('button', { name: '통계 데이터' });
    await expect(all).toHaveAttribute('aria-pressed', 'true');
    await expect(chip).toHaveAttribute('aria-pressed', 'false');

    await chip.focus();
    await page.keyboard.press('Enter');

    // 키보드로 고른 칩이 목록 조회 파라미터(categoryId=2)로 실리고, 선택 상태가 칩으로 옮겨 간다
    await expect.poll(() => capture.requests.map((r) => r.searchParams.get('categoryId'))).toContain('2');
    await expect(chip).toHaveAttribute('aria-pressed', 'true');
    await expect(all).toHaveAttribute('aria-pressed', 'false');
    await expect(page).toHaveURL(/categoryId=2/);
  });
});

test.describe('파이프라인 목록 — 키보드 경로', () => {
  test('파이프라인 이름은 상세 링크라 포커스한 뒤 Enter 로 상세를 연다', async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
    await setupPipelineMocks(page, 3);
    await page.goto('/pipelines');

    const link = page.getByRole('link', { name: '파이프라인 3', exact: true });
    await expect(link).toHaveAttribute('href', '/pipelines/3');
    await link.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/pipelines\/3$/);
  });
});

test.describe('API 연결 목록 — 키보드 경로', () => {
  test('연결 이름은 상세 링크이고, 휴지통 버튼은 어느 연결의 삭제인지 이름으로 읽힌다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupApiConnectionListMocks(page);
    await page.goto('/admin/api-connections');

    const link = page.getByRole('link', { name: '내부 서비스 API', exact: true });
    await expect(link).toHaveAttribute('href', '/admin/api-connections/2');
    // 아이콘뿐인 삭제 버튼에 연결 이름이 실린 접근 이름이 있다(예전엔 이름이 비어 「버튼」으로만 읽혔다)
    await expect(page.getByRole('button', { name: '공공 데이터 API 삭제' })).toBeVisible();
    await expect(page.getByRole('button', { name: '내부 서비스 API 삭제' })).toBeVisible();

    await link.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/admin\/api-connections\/2$/);
  });
});

test.describe('감사 로그 — 키보드 경로', () => {
  test('일시 칸의 상세 버튼을 Enter 로 눌러 상세 창을 열고, 마우스 행 클릭도 그대로 한 번 연다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupAuditLogMocks(page, 3);
    await page.goto('/admin/audit-logs');

    const detailButtons = page.getByRole('button', { name: /상세 보기$/ });
    await expect(detailButtons).toHaveCount(3);
    const first = detailButtons.first();
    await expect(first).toHaveAttribute('aria-haspopup', 'dialog');

    await first.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('감사 로그 상세')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);

    // 마우스로 행을 누르는 기존 경로도 유지된다
    await page.getByRole('row').nth(2).click();
    await expect(page.getByRole('dialog')).toBeVisible();
  });
});
