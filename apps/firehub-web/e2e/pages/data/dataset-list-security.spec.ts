import { createCategories, createDataset } from '../../factories/dataset.factory';
import { createLevelSummary } from '../../factories/security-level.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/** 등급이 다른 두 데이터셋(내부·기밀) 목록과 필터에 필요한 모킹. 목록 요청을 캡처해 돌려준다. */
async function setupTwoLevelList(page: import('@playwright/test').Page) {
  await setupSecurityLevelMocks(page);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  return mockApi(
    page, 'GET', '/api/v1/datasets',
    createPageResponse([
      createDataset({ id: 1, name: '출동_기록', securityLevel: createLevelSummary('내부') }),
      createDataset({ id: 2, name: '인사_평가_2026', securityLevel: createLevelSummary('기밀') }),
    ]),
    { capture: true },
  );
}

/** 목록 — 등급 열 + 서버 필터(페이지 내 정렬 한계 회피, 목업 s2). */
test.describe('데이터셋 목록 — 보안 등급', () => {
  test('등급 열이 보이고 필터는 securityLevelId 쿼리로 나간다', async ({ authenticatedPage: page }) => {
    const list = await setupTwoLevelList(page);
    await page.goto('/data/datasets');
    await expect(page.getByRole('columnheader', { name: '보안 등급' })).toBeVisible();
    await expect(page.getByRole('row').filter({ hasText: '인사_평가_2026' }).getByTestId('security-level-badge')).toHaveText('기밀');
    await page.getByRole('combobox', { name: '보안 등급 필터' }).click();
    await page.getByRole('option', { name: '기밀' }).click();
    await expect.poll(() => list.lastRequest()?.searchParams.get('securityLevelId')).toBe('4');
  });

  test('보안 등급 필터는 다른 필터처럼 「전체 보안 등급」으로 보이고, 고르면 등급 이름만 보인다 (WD-1119)', async ({ authenticatedPage: page }) => {
    const list = await setupTwoLevelList(page);
    await page.goto('/data/datasets');
    const trigger = page.getByRole('combobox', { name: '보안 등급 필터' });
    await expect(trigger).toHaveText('전체 보안 등급');
    await trigger.click();
    await expect(page.getByRole('option').first()).toHaveText('전체 보안 등급');
    await page.getByRole('option', { name: '기밀' }).click();
    await expect(trigger).toHaveText('기밀');
    await expect.poll(() => list.lastRequest()?.searchParams.get('securityLevelId')).toBe('4');
    await trigger.click();
    await page.getByRole('option', { name: '전체 보안 등급' }).click();
    await expect(trigger).toHaveText('전체 보안 등급');
    // 전체로 돌리면 캐시된 목록(staleTime 30s)을 써 새 요청이 없다 — 주소와 두 행으로 확인한다
    await expect(page).not.toHaveURL(/securityLevel=/);
    await expect(page.getByRole('row').filter({ hasText: '출동_기록' })).toBeVisible();
    await expect(page.getByRole('row').filter({ hasText: '인사_평가_2026' })).toBeVisible();
  });
});
