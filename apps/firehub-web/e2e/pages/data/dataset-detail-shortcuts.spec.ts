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

  /**
   * WD-76: Tibero 테넌트는 스키마 한정 이름이 필수이고 LIMIT 이 없다 —
   * 스키마 응답의 dialect 에 따라 바로가기 SQL 이 달라져야 한다. PG 는 기존 문자열 그대로.
   */
  async function openDetail(
    page: import('@playwright/test').Page,
    schemaInfo: Record<string, unknown>,
  ) {
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', schemaInfo);
    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
  }

  test('Tibero: "쿼리 작성"은 스키마 한정 이름 + ROWNUM 으로 prefill 한다', async ({
    authenticatedPage: page,
  }) => {
    await openDetail(page, { tables: [], dialect: 'TIBERO', schema: 'genia_t5' });
    await page.getByRole('button', { name: '쿼리 작성' }).click();
    await expect(page).toHaveURL(/\/analytics\/queries\/new\?datasetId=1&sql=/);
    const sql = new URL(page.url()).searchParams.get('sql') ?? '';
    expect(sql).toBe('SELECT *\nFROM genia_t5.test_dataset\nWHERE ROWNUM <= 100');
    expect(sql).not.toContain('LIMIT');
  });

  test('Tibero: "차트 만들기"도 Tibero 문법으로 prefill 한다', async ({
    authenticatedPage: page,
  }) => {
    await openDetail(page, { tables: [], dialect: 'TIBERO', schema: 'genia_t5' });
    await page.getByRole('button', { name: '차트 만들기' }).click();
    await expect(page).toHaveURL(/\/analytics\/charts\/new\?queryId=adhoc/);
    const sql = new URL(page.url()).searchParams.get('sql') ?? '';
    expect(sql).toBe('SELECT *\nFROM genia_t5.test_dataset\nWHERE ROWNUM <= 100');
  });

  test('PG(dialect 없음): 기존 SQL 문자열을 그대로 유지한다', async ({
    authenticatedPage: page,
  }) => {
    await openDetail(page, { tables: [] });
    await page.getByRole('button', { name: '쿼리 작성' }).click();
    await expect(page).toHaveURL(/\/analytics\/queries\/new\?datasetId=1&sql=/);
    expect(new URL(page.url()).searchParams.get('sql')).toBe(
      'SELECT * FROM test_dataset LIMIT 100',
    );
  });
});
