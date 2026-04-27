import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

/**
 * 이슈 #98: 데이터셋 → 차트/쿼리 단축 액션 부재 — 워크플로우 진입점 누락
 *
 * 데이터셋 상세 헤더에 "쿼리 작성" / "차트 만들기" 버튼이 있고,
 * 클릭 시 해당 에디터로 datasetId/sql 파라미터를 prefill하여 navigate되는지 검증한다.
 */
test.describe('데이터셋 상세 — 차트/쿼리 단축 액션 (#98)', () => {
  test('"쿼리 작성" 버튼 클릭 시 datasetId/sql 파라미터로 쿼리 에디터로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    const queryButton = page.getByRole('button', { name: '쿼리 작성' });
    await expect(queryButton).toBeVisible();
    await queryButton.click();

    await expect(page).toHaveURL(/\/analytics\/queries\/new\?datasetId=1&sql=/, {
      timeout: 5000,
    });
    const url = new URL(page.url());
    expect(url.searchParams.get('datasetId')).toBe('1');
    expect(decodeURIComponent(url.searchParams.get('sql') ?? '')).toContain('test_dataset');
  });

  test('"차트 만들기" 버튼 클릭 시 datasetId/sql 파라미터로 차트 빌더로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    const chartButton = page.getByRole('button', { name: '차트 만들기' });
    await expect(chartButton).toBeVisible();
    await chartButton.click();

    await expect(page).toHaveURL(/\/analytics\/charts\/new\?queryId=adhoc/, {
      timeout: 5000,
    });
    const url = new URL(page.url());
    expect(url.searchParams.get('queryId')).toBe('adhoc');
    expect(url.searchParams.get('datasetId')).toBe('1');
    expect(decodeURIComponent(url.searchParams.get('sql') ?? '')).toContain('test_dataset');
  });
});
