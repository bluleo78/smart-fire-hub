import { createCategories, createDataset } from '../../factories/dataset.factory';
import { createLevelSummary } from '../../factories/security-level.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/** 목록 — 등급 열 + 서버 필터(페이지 내 정렬 한계 회피, 목업 s2). */
test.describe('데이터셋 목록 — 보안 등급', () => {
  test('등급 열이 보이고 필터는 securityLevelId 쿼리로 나간다', async ({ authenticatedPage: page }) => {
    await setupSecurityLevelMocks(page);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    const list = await mockApi(
      page, 'GET', '/api/v1/datasets',
      createPageResponse([
        createDataset({ id: 1, name: '출동_기록', securityLevel: createLevelSummary('내부') }),
        createDataset({ id: 2, name: '인사_평가_2026', securityLevel: createLevelSummary('기밀') }),
      ]),
      { capture: true },
    );
    await page.goto('/data/datasets');
    await expect(page.getByRole('columnheader', { name: '보안 등급' })).toBeVisible();
    await expect(page.getByRole('row').filter({ hasText: '인사_평가_2026' }).getByTestId('security-level-badge')).toHaveText('기밀');
    await page.getByRole('combobox', { name: '보안 등급 필터' }).click();
    await page.getByRole('option', { name: '기밀' }).click();
    await expect.poll(() => list.lastRequest()?.searchParams.get('securityLevelId')).toBe('4');
  });
});
