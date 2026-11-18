import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/**
 * WD-812 — 파이프라인 에디터에서 이름이 비어 있을 때 이름 칸 아래 안내를 보이고 칸에 잇는다.
 * 머리글(56px)이 넘치지 않게 한 줄에 그린다 — 편집 중이면 「미저장 변경사항」 뒤에 안내를 함께, 아니면 해당하는 것만 보인다.
 */
const HINT = '파이프라인 이름을 입력하세요.';

test.describe('파이프라인 에디터 이름 안내 (WD-812)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  async function mockNewEditor(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });
  }

  test('새 파이프라인: 이름이 비어 있으면 안내가 보이고 이름 칸에 이어진다', async ({ authenticatedPage: page }) => {
    await mockNewEditor(page);
    await page.goto('/pipelines/new');

    await expect(page.getByText(HINT)).toBeVisible();
    await expect(page.getByRole('textbox', { name: '파이프라인 이름' })).toHaveAttribute(
      'aria-describedby',
      /pipeline-name-hint/,
    );
    // 아직 손대지 않아 변경이 없으므로 안내만 보이고 미저장 표시는 없다
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);
  });

  test('이름을 입력하면 안내가 사라지고 미저장 변경사항 줄이 보인다', async ({ authenticatedPage: page }) => {
    await mockNewEditor(page);
    await page.goto('/pipelines/new');

    await page.getByRole('textbox', { name: '파이프라인 이름' }).fill('일일 집계');

    await expect(page.getByText(HINT)).toHaveCount(0);
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeEnabled();
  });

  test('공백만 넣으면 안내가 다시 보이고 미저장 변경사항도 함께 보인다', async ({ authenticatedPage: page }) => {
    await mockNewEditor(page);
    await page.goto('/pipelines/new');

    await page.getByRole('textbox', { name: '파이프라인 이름' }).fill('   ');

    await expect(page.getByText(HINT)).toBeVisible();
    // 이름이 비어도 편집 중이라는 표시는 잃지 않는다
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
  });

  test('기존 파이프라인 보기 모드에는 안내가 없다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible();
    await expect(page.getByText(HINT)).toHaveCount(0);
  });

  test('기존 파이프라인 수정 모드: 이름을 지우면 안내가 보이고 칸에 이어지며 저장은 켜져 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    await page.getByRole('button', { name: '수정', exact: true }).click();
    const nameInput = page.getByRole('textbox', { name: '파이프라인 이름' });
    await nameInput.fill('');

    await expect(page.getByText(HINT)).toBeVisible();
    await expect(nameInput).toHaveAttribute('aria-describedby', /pipeline-name-hint/);
    // 이름을 지운 것도 변경이므로 미저장 표시가 안내와 함께 보인다
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeEnabled();
  });
});
