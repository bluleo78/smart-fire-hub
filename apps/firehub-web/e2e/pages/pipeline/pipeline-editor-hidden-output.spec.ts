/**
 * 파이프라인 에디터 — 볼 수 없는 출력·입력 데이터셋 (보안 등급 Task 3 B1·WD-21, 디자인 검토 2-1~2-4)
 *
 * 서버는 조회자가 볼 수 없는 출력 데이터셋의 이름만 null 로 주고 id 는 그대로 준다. 데이터셋 목록에도 그 데이터셋이 없고
 * 단건 조회(/datasets/{id})는 404 다. 편집기는 "존재는 알리고 이름은 숨긴다": 출력 Select·입력 칩에 잠금 + "열람 권한 없음",
 * 오류색·토스트 없음. 다른 필드만 고쳐 저장해도 숨김 출력·입력 id 가 PUT 페이로드에 그대로 남아야 한다.
 */

import type { Page } from '@playwright/test';

import { createDataset } from '../../factories/dataset.factory';
import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const HIDDEN_OUTPUT_ID = 77;
/** 목록에 없는(볼 수 없는) 입력 데이터셋 — 입력 선택칸이 모르는 id 를 조용히 버리면 저장 한 번에 입력 참조가 사라진다. */
const HIDDEN_INPUT_ID = 78;
/** 스크린샷 경로(디자이너 검토용) */
const SCREENSHOT_DIR = 'test-results/tc/security-level-name-masking';

async function setupMocks(
  page: Page,
  stepOverrides: Parameters<typeof createStep>[0] = {},
  options: { delayDatasetsMs?: number } = {},
) {
  const step = createStep({
    id: 5,
    name: '숨김 출력 스텝',
    scriptContent: 'SELECT 1 AS v',
    loadStrategy: 'REPLACE',
    outputDatasetId: HIDDEN_OUTPUT_ID,
    outputDatasetName: null,
    inputDatasetIds: [HIDDEN_INPUT_ID, 1],
    ...stepOverrides,
  });
  await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [step] }));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
  // 목록에는 볼 수 있는 데이터셋만 — 숨김 출력(77)·입력(78)은 없다
  const list = createPageResponse([
    createDataset({ id: 1, name: '공개 데이터셋', tableName: 'pub' }),
    createDataset({ id: 2, name: '다른 공개 데이터셋', tableName: 'pub2' }),
  ]);
  if (options.delayDatasetsMs) {
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      async (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        await new Promise((r) => setTimeout(r, options.delayDatasetsMs));
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(list) });
      },
    );
  } else {
    await mockApi(page, 'GET', '/api/v1/datasets', list);
  }
  // 숨김 데이터셋 단건 조회는 없는 데이터셋과 같은 404
  for (const id of [HIDDEN_OUTPUT_ID, HIDDEN_INPUT_ID]) {
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${id}`,
      { status: 404, error: 'Not Found', message: `Dataset not found: ${id}` },
      { status: 404 },
    );
  }
}

async function openStep(page: Page) {
  await page.goto('/pipelines/1');
  await page.getByRole('button', { name: '수정' }).click();
  await page.locator('.react-flow__node').first().click();
}

/** 권한 부족은 오류가 아니다 — 토스트·alert 없음 */
async function expectNoErrorSurface(page: Page) {
  await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
  await expect(page.getByRole('alert')).toHaveCount(0);
}

type PutPayload = {
  steps: Array<{
    outputDatasetId: number | null;
    inputDatasetIds: number[];
    description: string | null;
    loadStrategy: string;
  }>;
};

test.describe('파이프라인 에디터 — 볼 수 없는 출력·입력 데이터셋', () => {
  test('출력 Select·입력 칩이 잠금 표시되고, 다른 필드만 고쳐 저장해도 숨김 id 가 PUT 에 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });
    await openStep(page);

    // 2-1: 출력 Select 트리거는 빈 칸·자동 생성 대신 "열람 권한 없음"
    const outputSelect = page.getByRole('combobox', { name: '출력 데이터셋' });
    await expect(outputSelect).toHaveText('열람 권한 없음');
    await expect(page.getByText('실행 시 스텝 결과에 맞는 임시 데이터셋이 자동 생성됩니다')).toHaveCount(0);
    // 안내문 + aria-describedby 연결
    const notice = page.getByText(
      '이 출력 데이터셋을 볼 수 있는 권한이 없습니다. 다른 데이터셋으로 바꾸면 다시 선택할 수 없습니다.',
    );
    await expect(notice).toBeVisible();
    const noticeId = await notice.getAttribute('id');
    expect(noticeId).toBeTruthy();
    expect(await outputSelect.getAttribute('aria-describedby')).toContain(noticeId!);
    // 검증 오류가 아니다
    await expect(outputSelect).not.toHaveAttribute('aria-invalid', 'true');

    // 2-3: 입력 콤보박스 — 보이는 칩(공개 데이터셋) + 잠금 칩 1개, 잠금 칩에는 X 없음
    const inputCombo = page.getByRole('combobox', { name: '입력 데이터셋' });
    const lockChip = inputCombo.getByTestId('dataset-restricted');
    await expect(lockChip).toHaveText('열람 권한 없음');
    await expect(lockChip.locator('[role="button"]')).toHaveCount(0);
    await expect(inputCombo).toContainText('공개 데이터셋');

    await outputSelect.scrollIntoViewIfNeeded();
    await page.screenshot({ path: `${SCREENSHOT_DIR}/pipeline-hidden-output.png` });
    await inputCombo.scrollIntoViewIfNeeded();
    await page.screenshot({ path: `${SCREENSHOT_DIR}/pipeline-hidden-input-chip.png` });

    // 보이는 입력 칩을 X 로 제거해도 숨김 입력 id 는 남는다
    await inputCombo.getByRole('button').first().click();
    await expect(inputCombo).not.toContainText('공개 데이터셋');
    await expect(lockChip).toBeVisible(); // placeholder 대신 잠금 칩

    // 다른 필드(스텝 설명)만 고친다
    await page.locator('#step-description').fill('설명만 바꾼 저장');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await putCapture.waitForRequest();
    const payload = req.payload as PutPayload;
    expect(payload.steps[0].description).toBe('설명만 바꾼 저장');
    expect(payload.steps[0].outputDatasetId).toBe(HIDDEN_OUTPUT_ID);
    expect(payload.steps[0].inputDatasetIds).toEqual([HIDDEN_INPUT_ID]);
    await expectNoErrorSurface(page);
    await expect(page.locator('[data-testid="dataset-restricted"].text-destructive')).toHaveCount(0);
  });

  test('저장된 MERGE 스텝의 숨김 출력 — MERGE 가 유지되고 "PK 지정" 대신 확인 불가 안내', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page, { loadStrategy: 'MERGE' });
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });
    await openStep(page);

    const strategy = page.getByRole('combobox', { name: '로드 전략' });
    await expect(strategy).toHaveText('병합 (Merge)');
    await expect(page.getByText('출력 데이터셋을 볼 수 없어 PK를 확인할 수 없습니다')).toBeVisible();
    await expect(page.getByText('출력 데이터셋에 PK 컬럼을 지정해야 병합을 쓸 수 있습니다')).toHaveCount(0);
    // MERGE 항목은 현재 값이라 비활성화되지 않는다
    await strategy.click();
    await expect(page.getByRole('option', { name: '병합 (Merge)' })).not.toHaveAttribute('data-disabled', '');
    await page.keyboard.press('Escape');

    await page.locator('#step-description').fill('MERGE 유지 저장');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const payload = (await putCapture.waitForRequest()).payload as PutPayload;
    expect(payload.steps[0].loadStrategy).toBe('MERGE');
    expect(payload.steps[0].outputDatasetId).toBe(HIDDEN_OUTPUT_ID);
    await expectNoErrorSurface(page);
  });

  test('데이터셋 목록 로딩 중에는 숨김으로 판정하지 않는다(잠금 표시 깜빡임 없음)', async ({ authenticatedPage: page }) => {
    await setupMocks(page, {}, { delayDatasetsMs: 1500 });
    await openStep(page);
    // 목록이 아직 오지 않았다 — 잠금 표시·안내문이 없어야 한다
    await expect(page.getByRole('combobox', { name: '출력 데이터셋' })).toBeVisible();
    await expect(page.getByTestId('dataset-restricted')).toHaveCount(0);
    await expect(page.getByText('이 출력 데이터셋을 볼 수 있는 권한이 없습니다.', { exact: false })).toHaveCount(0);
    // 로드 후에는 잠금 표시
    await expect(page.getByRole('combobox', { name: '입력 데이터셋' }).getByTestId('dataset-restricted')).toBeVisible();
    await expect(page.getByRole('combobox', { name: '출력 데이터셋' })).toHaveText('열람 권한 없음');
  });

  test('숨김 입력이 여럿이면 잠금 칩 하나에 개수를 합산한다', async ({ authenticatedPage: page }) => {
    await setupMocks(page, { inputDatasetIds: [HIDDEN_INPUT_ID, 79] });
    await openStep(page);
    const inputCombo = page.getByRole('combobox', { name: '입력 데이터셋' });
    await expect(inputCombo.getByTestId('dataset-restricted')).toHaveText('열람 권한 없음 2개');
    await expect(inputCombo).not.toContainText('데이터셋 선택'); // placeholder 대신 잠금 칩
  });

  test('AI 분류 스텝의 첫 입력이 숨김이면 "컬럼이 없습니다" 대신 볼 수 없음 안내', async ({ authenticatedPage: page }) => {
    await setupMocks(page, {
      scriptType: 'AI_CLASSIFY',
      scriptContent: '',
      outputDatasetId: 1,
      outputDatasetName: '공개 데이터셋',
      inputDatasetIds: [HIDDEN_INPUT_ID],
      aiConfig: { prompt: '분류', outputColumns: [{ name: 'label', type: 'TEXT' }], inputColumns: ['secret_col'] },
    });
    await openStep(page);
    // 입력 컬럼 섹션을 펼친다
    await page.getByRole('button', { name: /입력 컬럼/ }).click();
    await expect(page.getByTestId('ai-classify-input-restricted')).toHaveText(
      '입력 데이터셋을 볼 수 없어 컬럼을 표시할 수 없습니다',
    );
    await expect(page.getByText('데이터셋에 컬럼이 없습니다')).toHaveCount(0);
    await expectNoErrorSurface(page);
  });
});
