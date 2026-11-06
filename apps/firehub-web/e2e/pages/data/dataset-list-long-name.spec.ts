import { createDataset, createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 목록 — 띄어쓰기 없는 긴 이름 (WD-517)
 * - 표 셀은 줄바꿈하지 않아(whitespace-nowrap) 100자 이름이 표를 가로로 밀어 보안 등급·태그·생성일 칸이 화면 밖으로 나갔다.
 * - 이제 이름 칸은 폭이 묶이고 이름은 말줄임되며, 전체 이름은 title 로 볼 수 있다.
 */
test.describe('데이터셋 목록 긴 이름 말줄임 (WD-517)', () => {
  const LONG_NAME = '가'.repeat(100);

  test('1280px 에서 100자 이름이 있어도 표가 가로로 넘치지 않고 생성일 칸까지 보인다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await setupDatasetMocks(page);
    // 첫 행만 긴 이름으로 덮어쓴다(나중에 등록한 route 가 우선)
    const [first, ...rest] = createDatasets(3);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([{ ...first, name: LONG_NAME }, ...rest]));
    await page.goto('/data/datasets');

    const link = page.getByRole('link', { name: LONG_NAME });
    await expect(link).toBeVisible();
    // 전체 이름은 title 로 남는다
    await expect(link).toHaveAttribute('title', LONG_NAME);

    // 말줄임 상태: 링크 내용 폭이 보이는 폭보다 크다(잘려 보인다)
    const clipped = await link.evaluate((el) => el.scrollWidth > el.clientWidth);
    expect(clipped).toBe(true);

    // 표 컨테이너가 가로로 넘치지 않는다
    const container = page.locator('[data-slot="table-container"]').first();
    const overflow = await container.evaluate((el) => el.scrollWidth - el.clientWidth);
    expect(overflow).toBeLessThanOrEqual(1);

    // 생성일 머리글이 화면 안에 보인다
    const createdHeader = page.getByRole('columnheader', { name: /생성일/ });
    await expect(createdHeader).toBeInViewport();
  });

  test('짧은 이름은 잘리지 않는다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await setupDatasetMocks(page);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([createDataset({ id: 1, name: '짧은 이름' })]));
    await page.goto('/data/datasets');

    const link = page.getByRole('link', { name: '짧은 이름' });
    await expect(link).toBeVisible();
    const clipped = await link.evaluate((el) => el.scrollWidth > el.clientWidth);
    expect(clipped).toBe(false);
  });
});
