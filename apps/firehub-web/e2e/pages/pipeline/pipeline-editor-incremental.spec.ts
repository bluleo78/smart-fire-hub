/**
 * 파이프라인 에디터 — SQL 스텝 증분 처리(MERGE 로드 전략 + {{last_run_at}} 책갈피) E2E 테스트
 *
 * Task 7이 노출한 PipelineStepResponse.lastRunAt/fullRebuildPending/warnings/fullRebuildMode와
 * POST·DELETE /api/v1/pipelines/{id}/steps/{stepId}/full-rebuild 를 StepConfigPanel +
 * IncrementalProcessingSection이 실제로 구동하는지 검증한다.
 */

import { createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const SQL = 'SELECT code, name FROM src WHERE _updated_at >= {{last_run_at}}';
const OUTPUT_DATASET_ID = 10;

/** 파이프라인 상세/실행 이력/트리거/데이터셋 목록을 모킹한다 — setupPipelineEditorMocks와 달리 스텝을 직접 지정할 수 있다. */
async function setupMocks(
  page: import('@playwright/test').Page,
  step: ReturnType<typeof createStep>,
  datasetColumns: ReturnType<typeof createColumn>[],
) {
  const detail = createPipelineDetail({ id: 1, steps: [step] });
  await mockApi(page, 'GET', '/api/v1/pipelines/1', detail);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
  });
  await mockApi(
    page,
    'GET',
    `/api/v1/datasets/${OUTPUT_DATASET_ID}`,
    createDatasetDetail({ id: OUTPUT_DATASET_ID, name: '출력 데이터셋', columns: datasetColumns }),
  );
}

test.describe('파이프라인 SQL 스텝 증분 처리', () => {
  test('PK가 있는 출력이면 MERGE를 고를 수 있고 PK가 표시되며 저장 payload에 반영된다', async ({
    authenticatedPage: page,
  }) => {
    const step = createStep({
      id: 5,
      scriptContent: SQL,
      loadStrategy: 'APPEND',
      outputDatasetId: OUTPUT_DATASET_ID,
      outputDatasetName: '출력 데이터셋',
    });
    await setupMocks(page, step, [
      createColumn({ id: 1, columnName: 'code', isPrimaryKey: true, columnOrder: 0 }),
      createColumn({ id: 2, columnName: 'name', isPrimaryKey: false, columnOrder: 1 }),
    ]);

    // PUT 저장 payload 캡처 — MERGE 값이 실제로 전달되는지 검증한다.
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node').first().click();

    const loadStrategySelect = page.getByRole('combobox', { name: '로드 전략' });
    await expect(loadStrategySelect).toBeVisible();
    await loadStrategySelect.click();
    const mergeOption = page.getByRole('option', { name: '병합 (Merge)' });
    await expect(mergeOption).toBeEnabled();
    await mergeOption.click();

    // PK 컬럼 안내가 표시된다
    await expect(page.getByText('PK: code')).toBeVisible();

    // 저장 → payload에 MERGE가 실제로 담겨야 한다 (값이 화면에만 보이고 안 나가는 결함 방지)
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await putCapture.waitForRequest();
    const payload = req.payload as { steps: Array<{ loadStrategy: string }> };
    expect(payload.steps[0].loadStrategy).toBe('MERGE');
  });

  test('PK가 없는 출력이면 MERGE가 비활성화된다', async ({ authenticatedPage: page }) => {
    const step = createStep({
      id: 5,
      scriptContent: SQL,
      loadStrategy: 'APPEND',
      outputDatasetId: OUTPUT_DATASET_ID,
      outputDatasetName: '출력 데이터셋',
    });
    await setupMocks(page, step, [
      createColumn({ id: 1, columnName: 'code', isPrimaryKey: false, columnOrder: 0 }),
      createColumn({ id: 2, columnName: 'name', isPrimaryKey: false, columnOrder: 1 }),
    ]);

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node').first().click();

    await expect(page.getByText('출력 데이터셋에 PK 컬럼을 지정해야 병합을 쓸 수 있습니다')).toBeVisible();

    const loadStrategySelect = page.getByRole('combobox', { name: '로드 전략' });
    await loadStrategySelect.click();
    await expect(page.getByRole('option', { name: '병합 (Merge)' })).toBeDisabled();
  });

  /**
   * 회귀 테스트: MERGE PK 안내 문구는 MERGE가 실제로 적용 가능한 스텝(SQL + 출력 데이터셋 지정)에서만
   * 떠야 한다. 이전에는 hasPk가 useDataset(0) 비활성 조회로 인해 무조건 false가 되어, PYTHON/API_CALL/
   * AI_CLASSIFY 스텝이나 SQL의 자동 임시 데이터셋 스텝에서도 이 문구가 무의미하게 노출됐다.
   */
  test('MERGE가 적용되지 않는 스텝(출력 데이터셋 미지정)에는 PK 안내 문구가 뜨지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const step = createStep({
      id: 5,
      scriptType: 'PYTHON',
      scriptContent: 'df = df',
      loadStrategy: 'APPEND',
      outputDatasetId: undefined,
      outputDatasetName: undefined,
    });
    const detail = createPipelineDetail({ id: 1, steps: [step] });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', detail);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
    });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node').first().click();

    const loadStrategySelect = page.getByRole('combobox', { name: '로드 전략' });
    await expect(loadStrategySelect).toBeVisible();

    // 출력 데이터셋이 없는 스텝이므로 MERGE는 여전히 비활성이지만(PK를 정할 곳이 없음),
    // MERGE 전용 PK 안내 문구는 뜨면 안 된다 — 이 스텝에는 MERGE 자체가 무관하다.
    await expect(
      page.getByText('출력 데이터셋에 PK 컬럼을 지정해야 병합을 쓸 수 있습니다'),
    ).not.toBeVisible();

    await loadStrategySelect.click();
    await expect(page.getByRole('option', { name: '병합 (Merge)' })).toBeDisabled();
  });

  test('증분 스텝은 마지막 처리 시점을 보여주고 재생성을 예약·취소할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    const step = createStep({
      id: 5,
      scriptContent: SQL,
      loadStrategy: 'MERGE',
      outputDatasetId: OUTPUT_DATASET_ID,
      outputDatasetName: '출력 데이터셋',
      lastRunAt: '2026-09-19T01:00:00Z',
      fullRebuildPending: false,
      fullRebuildMode: 'REBUILD_OUTPUT',
    });
    await setupMocks(page, step, [
      createColumn({ id: 1, columnName: 'code', isPrimaryKey: true, columnOrder: 0 }),
    ]);

    const reservePost = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines/1/steps/5/full-rebuild',
      {},
      { status: 204, capture: true },
    );
    const cancelDelete = await mockApi(
      page,
      'DELETE',
      '/api/v1/pipelines/1/steps/5/full-rebuild',
      {},
      { status: 204, capture: true },
    );

    // 상세 조회는 서버처럼 "예약 상태"를 따라가야 한다 — 예약 POST가 취소 DELETE보다 많이 온 동안에만
    // fullRebuildPending: true 를 돌려준다. 예전에는 POST를 기다린 **뒤에** pending 응답을 재모킹했는데,
    // 그 사이에 mutation onSuccess의 invalidate 재조회가 먼저 나가 옛 응답(pending=false)을 받으면
    // 다시 조회할 계기가 없어 "재생성 예정" 문구가 끝내 안 떴다(병렬 부하에서 간헐 실패, #729).
    // capture는 응답을 돌려주기 전에 요청을 기록하므로, POST 응답 이후에 나가는 재조회는 항상 pending을 본다.
    const pendingDetail = createPipelineDetail({
      id: 1,
      steps: [{ ...step, fullRebuildPending: true }],
    });
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        // 예약 전·취소 후에는 setupMocks가 깐 기본 상세(pending=false)로 넘긴다.
        if (reservePost.requests.length <= cancelDelete.requests.length) return route.fallback();
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(pendingDetail),
        });
      },
    );

    await page.goto('/pipelines/1');
    // 예약은 조회 모드에서도 가능한 운영 동작이라 "수정" 클릭 없이 스텝만 선택한다.
    await page.locator('.react-flow__node').first().click();

    await expect(page.getByText(/마지막 처리 시점:/)).toBeVisible();

    await page.getByRole('button', { name: '처음부터 다시 만들기' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText(/모든 행을 지우고/)).toBeVisible();
    await dialog.getByRole('button', { name: '예약' }).click();

    const postReq = await reservePost.waitForRequest();
    expect(postReq.url.pathname).toBe('/api/v1/pipelines/1/steps/5/full-rebuild');

    // 예약 후 재조회(invalidate)는 위 상태형 모킹이 pending=true 로 답한다.
    await expect(page.getByText('다음 실행 시 전체 재생성 예정')).toBeVisible();
    const cancelButton = page.getByRole('button', { name: '예약 취소' });
    await expect(cancelButton).toBeVisible();

    await cancelButton.click();
    const deleteReq = await cancelDelete.waitForRequest();
    expect(deleteReq.url.pathname).toBe('/api/v1/pipelines/1/steps/5/full-rebuild');
  });

  test('경고가 있으면 표시한다', async ({ authenticatedPage: page }) => {
    const step = createStep({
      id: 5,
      scriptContent: SQL,
      loadStrategy: 'MERGE',
      outputDatasetId: OUTPUT_DATASET_ID,
      outputDatasetName: '출력 데이터셋',
      warnings: ['집계 함수가 포함된 SQL은 증분 처리 시 부분 집계가 될 수 있습니다'],
    });
    await setupMocks(page, step, [
      createColumn({ id: 1, columnName: 'code', isPrimaryKey: true, columnOrder: 0 }),
    ]);

    await page.goto('/pipelines/1');
    await page.locator('.react-flow__node').first().click();

    await expect(
      page.getByText('집계 함수가 포함된 SQL은 증분 처리 시 부분 집계가 될 수 있습니다'),
    ).toBeVisible();
  });

  test('fullRebuildMode가 null이면 재생성 예약 컨트롤을 제공하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const step = createStep({
      id: 5,
      scriptContent: SQL,
      loadStrategy: 'MERGE',
      outputDatasetId: OUTPUT_DATASET_ID,
      outputDatasetName: '출력 데이터셋',
      lastRunAt: null,
      fullRebuildPending: false,
      fullRebuildMode: null,
    });
    await setupMocks(page, step, [
      createColumn({ id: 1, columnName: 'code', isPrimaryKey: true, columnOrder: 0 }),
    ]);

    await page.goto('/pipelines/1');
    await page.locator('.react-flow__node').first().click();

    // 증분 처리 섹션 자체는 뜨지만({{last_run_at}} 사용), 예약 버튼은 없어야 한다
    await expect(page.getByText('증분 처리', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '처음부터 다시 만들기' })).not.toBeVisible();
    await expect(page.getByRole('button', { name: '원천 전체 다시 읽기' })).not.toBeVisible();
  });

  /**
   * fullRebuildMode === 'READ_ALL' 전용 회귀 테스트.
   * 사용자 DML 스텝은 예약해도 출력을 지우지 않고 원천만 다시 읽는다 — REBUILD_OUTPUT과
   * 라벨·다이얼로그 문구가 다르며, 이 문구가 "출력이 지워지지 않는다"를 정확히 전달하지
   * 못하면 DML 스텝 사용자에게 거짓 약속(출력 재생성)을 하게 된다. 라벨이 REBUILD_OUTPUT의
   * "처음부터 다시 만들기"/"모든 행을 지우고"로 바뀌면 이 테스트가 실패해야 한다.
   */
  test('fullRebuildMode가 READ_ALL이면 원천만 다시 읽고 출력은 지우지 않는다는 문구가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    const step = createStep({
      id: 5,
      scriptContent: SQL,
      loadStrategy: 'MERGE',
      outputDatasetId: OUTPUT_DATASET_ID,
      outputDatasetName: '출력 데이터셋',
      lastRunAt: '2026-09-19T01:00:00Z',
      fullRebuildPending: false,
      fullRebuildMode: 'READ_ALL',
    });
    await setupMocks(page, step, [
      createColumn({ id: 1, columnName: 'code', isPrimaryKey: true, columnOrder: 0 }),
    ]);

    await page.goto('/pipelines/1');
    await page.locator('.react-flow__node').first().click();

    // REBUILD_OUTPUT 라벨("처음부터 다시 만들기")이 아니라 READ_ALL 전용 라벨이어야 한다
    const readAllButton = page.getByRole('button', { name: '원천 전체 다시 읽기' });
    await expect(readAllButton).toBeVisible();
    await expect(page.getByRole('button', { name: '처음부터 다시 만들기' })).not.toBeVisible();

    await readAllButton.click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    // 출력이 지워지지 않는다는 문구가 있어야 하고, REBUILD_OUTPUT의 "모든 행을 지우고" 약속이 섞여 들어오면 안 된다
    await expect(dialog.getByText(/지워지지 않으며/)).toBeVisible();
    await expect(dialog.getByText(/모든 행을 지우고/)).not.toBeVisible();
  });
});
