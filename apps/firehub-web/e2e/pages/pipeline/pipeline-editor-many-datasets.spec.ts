/**
 * 파이프라인 에디터 — 데이터셋이 서버 목록 상한(100)을 넘을 때의 선택 목록 E2E 테스트 (#732)
 *
 * 서버는 GET /datasets 의 size 를 100 으로 조용히 자른다. 예전 에디터는 size=1000 한 번만 조회해
 * 101 번째 이후 데이터셋이 목록에서 빠졌고, 그 데이터셋을 이미 출력/입력으로 쓰는 스텝은 빈 칸으로 보였다.
 * 이 모킹은 서버의 상한·페이지 분할 동작을 그대로 흉내 내어, 두 번째 페이지에만 있는 데이터셋이
 * 편집기에 표시·선택되는지(=전 페이지 순회) 검증한다.
 */

import type { Page } from '@playwright/test';

import { createColumn, createDataset, createDatasetDetail } from '../../factories/dataset.factory';
import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { createPageResponse,mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

/** 서버 목록 조회 상한 — DatasetController 의 Math.min(size, 100) 과 같게 둔다. */
const SERVER_MAX_SIZE = 100;
/** 전체 데이터셋 102개 — 마지막 두 개(id 101, 102)는 두 번째 페이지에만 있다. */
const ALL_DATASETS = Array.from({ length: 102 }, (_, i) =>
  createDataset({ id: i + 1, name: `데이터셋 ${i + 1}`, tableName: `ds_${i + 1}` }),
);
const OUTPUT_ID = 102;
const INPUT_ID = 101;

/**
 * 서버처럼 size 를 100 으로 자르고 page 에 맞는 조각을 돌려주는 데이터셋 목록 모킹.
 * WD-754: 서버처럼 includeTemp=true 가 없으면 임시(TEMP) 데이터셋을 빼고(건수·페이지도 뺀 결과 기준) 돌려준다.
 * 요청마다 includeTemp 값을 남겨, 선택 목록이 임시까지 받으려 하는지 확인할 수 있게 한다.
 */
async function mockPagedDatasets(page: Page, datasets = ALL_DATASETS) {
  const requestedSizes: number[] = [];
  const includeTempValues: Array<string | null> = [];
  await page.route(
    (url) => url.pathname === '/api/v1/datasets',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const params = new URL(route.request().url()).searchParams;
      const reqSize = Number(params.get('size') ?? 20);
      requestedSizes.push(reqSize);
      includeTempValues.push(params.get('includeTemp'));
      const visible =
        params.get('includeTemp') === 'true' ? datasets : datasets.filter((d) => d.originType !== 'TEMP');
      const size = Math.max(1, Math.min(reqSize, SERVER_MAX_SIZE));
      const pageNo = Math.max(0, Number(params.get('page') ?? 0));
      const content = visible.slice(pageNo * size, pageNo * size + size);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content,
          page: pageNo,
          size,
          totalElements: visible.length,
          totalPages: Math.ceil(visible.length / size),
        }),
      });
    },
  );
  return { requestedSizes, includeTempValues };
}

async function setupMocks(page: Page, datasets = ALL_DATASETS) {
  const step = createStep({
    id: 5,
    scriptContent: 'SELECT 1 AS v',
    loadStrategy: 'REPLACE',
    outputDatasetId: OUTPUT_ID,
    outputDatasetName: '데이터셋 102',
    inputDatasetIds: [INPUT_ID],
  });
  await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [step] }));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
  await mockApi(
    page,
    'GET',
    `/api/v1/datasets/${OUTPUT_ID}`,
    createDatasetDetail({
      id: OUTPUT_ID,
      name: '데이터셋 102',
      columns: [createColumn({ id: 1, columnName: 'v', columnOrder: 0 })],
    }),
  );
  return mockPagedDatasets(page, datasets);
}

test.describe('파이프라인 에디터 — 데이터셋 100개 초과 (#732)', () => {
  // 실행·수정·트리거 편집 버튼은 권한이 있을 때만 보인다(WD-219) — 기본 인증 fixture 는 권한이 비어 있어 모든 쓰기 권한을 준다.
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('두 번째 페이지에만 있는 출력/입력 데이터셋이 스텝에 이름으로 표시된다', async ({
    authenticatedPage: page,
  }) => {
    const { requestedSizes } = await setupMocks(page);

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node').first().click();

    // 이미 지정된 출력 데이터셋(두 번째 페이지)이 빈 칸이 아니라 이름으로 보여야 한다
    await expect(page.getByRole('combobox', { name: '출력 데이터셋' })).toHaveText('데이터셋 102');
    // 입력 데이터셋 콤보박스도 선택된 데이터셋(두 번째 페이지)을 이름으로 보여야 한다
    await expect(page.getByRole('combobox', { name: '입력 데이터셋' })).toContainText('데이터셋 101');

    // 서버 상한을 넘는 size 로 한 번에 받으려 하지 않는다 (잘리는 것을 전제로 페이지를 순회)
    expect(requestedSizes.every((s) => s <= SERVER_MAX_SIZE)).toBe(true);
  });

  test('출력 데이터셋 목록에 101번째 이후 데이터셋도 포함되어 고를 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node').first().click();

    const outputSelect = page.getByRole('combobox', { name: '출력 데이터셋' });
    await outputSelect.click();
    // 첫 페이지 항목 + 두 번째 페이지 항목이 모두 옵션으로 있어야 한다
    await expect(page.getByRole('option', { name: '데이터셋 1', exact: true })).toBeVisible();
    await page.getByRole('option', { name: '데이터셋 101', exact: true }).click();
    await expect(outputSelect).toHaveText('데이터셋 101');

    // 선택이 저장 payload 로 실제 전달되는지 확인
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await putCapture.waitForRequest();
    const payload = req.payload as { steps: Array<{ outputDatasetId: number | null }> };
    expect(payload.steps[0].outputDatasetId).toBe(101);
  });

  /**
   * WD-754: 서버가 목록에서 임시(TEMP) 데이터셋을 기본으로 숨기게 됐다. 파이프라인은 다른 단계의 임시 결과를
   * 입력으로 읽으므로 선택 목록(useAllDatasets)은 includeTemp=true 로 임시까지 받아야 한다 — 빠지면 임시 입력이 빈 칸이 된다.
   */
  test('입력이 임시(TEMP) 데이터셋이어도 선택 목록이 includeTemp=true 로 받아 이름으로 표시한다 (WD-754)', async ({
    authenticatedPage: page,
  }) => {
    // 입력 데이터셋(101, 두 번째 페이지)을 앞 단계가 만든 임시 결과로 둔다
    const withTempInput = ALL_DATASETS.map((d) =>
      d.id === INPUT_ID ? { ...d, name: '임시 결과 101', originType: 'TEMP' as const } : d,
    );
    const requests = await setupMocks(page, withTempInput);

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node').first().click();

    await expect(page.getByRole('combobox', { name: '입력 데이터셋' })).toContainText('임시 결과 101');
    // 목록 요청(페이지 순회 전부)이 includeTemp=true 를 실었다
    expect(requests.includeTempValues.length).toBeGreaterThan(0);
    expect(requests.includeTempValues.every((v) => v === 'true')).toBe(true);
  });
});
