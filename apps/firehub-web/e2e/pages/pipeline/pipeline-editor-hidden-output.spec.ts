/**
 * 파이프라인 에디터 — 볼 수 없는 출력 데이터셋의 왕복 보존 (보안 등급 Task 3 B1)
 *
 * 서버는 조회자가 볼 수 없는 출력 데이터셋의 이름만 null 로 주고 id 는 그대로 준다. 데이터셋 목록에도 그 데이터셋이 없고
 * 단건 조회(/datasets/{id})는 404 다. 이 상태에서 다른 필드만 고쳐 저장해도 PUT 페이로드의 outputDatasetId 가
 * 그대로 유지돼야 한다 — 선택 목록에 없는 값이라고 '자동 생성'으로 바뀌면 저장 한 번에 참조가 사라진다.
 */

import type { Page } from '@playwright/test';

import { createDataset } from '../../factories/dataset.factory';
import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const HIDDEN_OUTPUT_ID = 77;

async function setupMocks(page: Page) {
  const step = createStep({
    id: 5,
    name: '숨김 출력 스텝',
    scriptContent: 'SELECT 1 AS v',
    loadStrategy: 'REPLACE',
    outputDatasetId: HIDDEN_OUTPUT_ID,
    outputDatasetName: null,
  });
  await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [step] }));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
  // 목록에는 볼 수 있는 데이터셋만 — 숨김 출력(77)은 없다
  await mockApi(
    page,
    'GET',
    '/api/v1/datasets',
    createPageResponse([createDataset({ id: 1, name: '공개 데이터셋', tableName: 'pub' })]),
  );
  // 숨김 데이터셋 단건 조회는 없는 데이터셋과 같은 404
  await mockApi(
    page,
    'GET',
    `/api/v1/datasets/${HIDDEN_OUTPUT_ID}`,
    { status: 404, error: 'Not Found', message: `Dataset not found: ${HIDDEN_OUTPUT_ID}` },
    { status: 404 },
  );
}

test.describe('파이프라인 에디터 — 볼 수 없는 출력 데이터셋', () => {
  test('다른 필드만 고쳐 저장해도 숨김 출력 id 가 PUT 페이로드에 유지된다', async ({ authenticatedPage: page }) => {
    await setupMocks(page);
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node').first().click();

    // 출력 데이터셋 선택칸은 목록에 없는 값을 '자동 생성'으로 바꾸지 않는다(이름은 비어 보인다 — 원본 이름 비노출)
    const outputSelect = page.getByRole('combobox', { name: '출력 데이터셋' });
    await expect(outputSelect).toBeVisible();
    await expect(outputSelect).not.toHaveText('자동 생성 (임시)');
    await expect(page.getByText('실행 시 스텝 결과에 맞는 임시 데이터셋이 자동 생성됩니다')).toHaveCount(0);

    await outputSelect.scrollIntoViewIfNeeded();
    await page.screenshot({ path: 'test-results/tc/security-level-name-masking/pipeline-hidden-output.png' });

    // 다른 필드(스텝 설명)만 고친다
    const description = page.locator('#step-description');
    await description.fill('설명만 바꾼 저장');

    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await putCapture.waitForRequest();
    const payload = req.payload as { steps: Array<{ outputDatasetId: number | null; description: string | null }> };
    expect(payload.steps[0].description).toBe('설명만 바꾼 저장');
    expect(payload.steps[0].outputDatasetId).toBe(HIDDEN_OUTPUT_ID);
  });
});
