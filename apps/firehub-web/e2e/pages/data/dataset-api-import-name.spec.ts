import type { Page } from '@playwright/test';

import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * API 가져오기 마법사 — 기본 파이프라인 이름 E2E (WD-31④)
 *
 * 파이프라인·트리거 목록은 데이터셋 보안 등급 판정을 거치지 않는다. 기본 이름에 데이터셋 이름을 넣으면
 * 그 데이터셋을 볼 수 없는 사용자에게 이름이 새어 나가므로, 기본값은 데이터셋 id 만 쓴다(`API Import #<id>`).
 */
test.describe('데이터셋 상세 — API 가져오기 기본 이름 (WD-31④)', () => {
  /** 데이터셋 이름이 요청 본문에 새는지 확인하려고 눈에 띄는 이름을 쓴다 */
  const datasetDetail = createDatasetDetail({
    id: 1,
    name: '인사 급여',
    rowCount: 0,
    columns: [
      createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
      createColumn({ id: 2, columnName: 'name', displayName: '이름', dataType: 'TEXT', columnOrder: 1 }),
    ],
  });

  /** 데이터 탭 진입에 필요한 모킹 — dataset-data-tab.spec.ts 의 공통 모킹을 줄여 옮겼다 */
  async function setupDataTabMocks(page: Page) {
    await mockApi(page, 'GET', '/api/v1/datasets/1', datasetDetail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/data', {
      columns: datasetDetail.columns,
      rows: [],
      page: 0,
      size: 50,
      totalElements: 0,
      totalPages: 0,
    });
  }

  test('기본 파이프라인 이름은 데이터셋 id 만 쓰고 요청에 데이터셋 이름이 없다', async ({
    authenticatedPage: page,
  }) => {
    await setupDataTabMocks(page);
    const create = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/api-import',
      { pipelineId: 11, triggerId: null, executionId: null },
      { capture: true },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '인사 급여' })).toBeVisible();
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: 'API 가져오기' }).click();

    const dialog = page.getByRole('dialog');
    // 1단계: URL — 2단계: 매핑 1개(0개면 다음으로 못 간다) — 3단계: 미리보기 — 4단계: 실행 옵션
    await dialog.getByPlaceholder('https://api.example.com/v1/data').fill('https://api.example.com/v1/data');
    await dialog.getByRole('button', { name: '다음' }).click();
    await dialog.getByRole('button', { name: '매핑 추가' }).click();
    await dialog.getByRole('button', { name: '다음' }).click();
    await dialog.getByRole('button', { name: '다음' }).click();

    const nameInput = dialog.getByLabel('파이프라인 이름');
    await expect(nameInput).toHaveValue('API Import #1');
    await expect(nameInput).toHaveAttribute('placeholder', 'API Import #1');

    // 이름을 지워도 같은 기본값으로 보낸다
    await nameInput.fill('');
    await dialog.getByRole('button', { name: '완료' }).click();
    const req = await create.waitForRequest();
    expect(req.payload).toMatchObject({ pipelineName: 'API Import #1' });
    expect(JSON.stringify(req.payload)).not.toContain('인사 급여');
  });
});
