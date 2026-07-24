import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../fixtures/dataset.fixture';

/**
 * 임포트 사전 샘플 검증 + 2단계(VALIDATING/INSERTING) 잡 진행/실패 E2E
 * - 백엔드는 사전 검증 시 앞부분 최대 200행만 샘플 검사한다(전체 검증 아님).
 * - 실제 임포트 잡은 VALIDATING(전량 fail-fast) → INSERTING(배치) 2단계로 진행하며,
 *   VALIDATING은 분모가 없어 진행률이 고정값(20)으로 온다 — 프론트는 이를 indeterminate로 렌더한다.
 * - 검증 실패 시 실패 단계는 VALIDATING 이어야 하고 INSERTING 은 시작되지 않아야 한다.
 */

const DATASET_ID = 1;

/** 300행 초과 CSV — 사전 검증이 "샘플"만 검사함을 눈으로 확인하기 위한 크기 */
function createLargeCsv(rows = 350): string {
  const header = 'id,name\n';
  const body = Array.from({ length: rows }, (_, i) => `${i + 1},항목 ${i + 1}`).join('\n');
  return header + body;
}

function createPreviewResponse() {
  return {
    fileHeaders: ['id', 'name'],
    sampleRows: [
      { id: '1', name: '항목 1' },
      { id: '2', name: '항목 2' },
    ],
    suggestedMappings: [
      { fileColumn: 'id', datasetColumn: 'id', matchType: 'EXACT', confidence: 1.0 },
      { fileColumn: 'name', datasetColumn: 'name', matchType: 'EXACT', confidence: 1.0 },
    ],
    totalRows: 350,
  };
}

async function setupBaseMocks(page: import('@playwright/test').Page) {
  await setupDatasetDetailMocks(page, DATASET_ID);
  await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/imports`, []);
  await mockApi(
    page,
    'POST',
    `/api/v1/datasets/${DATASET_ID}/imports/preview`,
    createPreviewResponse(),
  );
}

async function openImportDialogWithFile(page: import('@playwright/test').Page, csvContent: string) {
  await page.goto(`/data/datasets/${DATASET_ID}`);
  await page.getByRole('tab', { name: '데이터' }).click();
  await page.getByRole('button', { name: '임포트' }).first().click();

  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
    page.getByRole('dialog').locator('input[type="file"]').setInputFiles({
      name: 'large-sample.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from(csvContent),
    }),
  ]);
}

test.describe('임포트 사전 검증 — 샘플 200행', () => {
  test('300행 초과 CSV 업로드 시 검증 결과에 "샘플 200행" 문구가 노출된다', async ({ authenticatedPage: page }) => {
    await setupBaseMocks(page);
    // 사전 검증은 전량이 아니라 앞부분 200행만 샘플 검사한다 — 파일이 350행이어도 sampleSize=200
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/validate`, {
      sampleSize: 200,
      validRows: 200,
      errorRows: 0,
      sampled: true,
      errors: [],
    });

    await openImportDialogWithFile(page, createLargeCsv());

    const validateBtn = page.getByRole('dialog').getByRole('button', { name: '검증' });
    await expect(validateBtn).toBeVisible({ timeout: 10_000 });
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/validate') && r.status() === 200),
      validateBtn.click(),
    ]);

    await expect(page.getByText('샘플 200행 검사 결과', { exact: false })).toBeVisible();
  });

  test('필수 값 위반 CSV — validRows === 0 이면 임포트 버튼이 비활성화된다', async ({ authenticatedPage: page }) => {
    await setupBaseMocks(page);
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/validate`, {
      sampleSize: 200,
      validRows: 0,
      errorRows: 200,
      sampled: true,
      errors: [
        { rowNumber: 2, columnName: 'id', value: '', error: '필수 값이 누락되었습니다' },
      ],
    });

    await openImportDialogWithFile(page, createLargeCsv());

    const validateBtn = page.getByRole('dialog').getByRole('button', { name: '검증' });
    await expect(validateBtn).toBeVisible({ timeout: 10_000 });
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/validate') && r.status() === 200),
      validateBtn.click(),
    ]);

    const importBtn = page.getByRole('dialog').getByRole('button', { name: '임포트' });
    await expect(importBtn).toBeDisabled({ timeout: 5_000 });
  });
});

test.describe('임포트 잡 실패 — VALIDATING 단계 fail-fast', () => {
  test('중간 불량 행 임포트 실행 시 검증 단계(VALIDATING)가 실패로 표시되고 삽입은 시작되지 않으며 오류 테이블이 렌더된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      createPreviewResponse(),
    );

    const FILE_NAME = 'bad-row.csv';
    // 검증 오류 상세 — 백엔드가 audit_log 메타데이터에 { errors: [...] } 를 JSON 문자열로
    // 이중 인코딩해 저장하는 실제 런타임 형태를 재현한다.
    const errorDetailsJson = JSON.stringify({
      errors: [
        { rowNumber: 187, columnName: 'id', value: 'abc', error: '숫자여야 합니다' },
      ],
    });
    // 임포트 이력 — SSE가 FAILED 단계를 보낼 때까지는 히스토리가 아직 stale 하다(빈 배열)는 것을
    // 재현한다. useImportDialog.ts의 `stage === 'FAILED'` 무효화 라인이 없다면 이 stale 응답이
    // 그대로 남아 오류 테이블이 절대 렌더되지 않는다 — 즉 이 테스트는 그 한 줄을 직접 보호한다.
    const failedImportRecord = {
      id: 1,
      datasetId: DATASET_ID,
      fileName: FILE_NAME,
      fileSize: 4096,
      fileType: 'text/csv',
      status: 'FAILED',
      totalRows: null,
      successRows: null,
      errorRows: 1,
      errorDetails: errorDetailsJson,
      // 백엔드 실패 메시지는 영어 고정 문구다 — 한국어로 단언하지 않는다.
      errorMessage: 'Import validation failed, no rows were loaded (1 error(s) found)',
      importedBy: 'testuser',
      startedAt: '2024-01-01T01:00:00Z',
      completedAt: '2024-01-01T01:00:05Z',
      createdAt: '2024-01-01T01:00:00Z',
    };
    // SSE progress 응답에서 FAILED 스테이지가 전송된 이후에만 true 로 바뀐다.
    let jobHasFailed = false;
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/imports`,
      (route) => {
        if (route.request().method() !== 'GET') {
          return route.fallback();
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          // FAILED 이전에는 이력에 아무 것도 없다(진행 중이라 아직 기록 안 됨) — 이후에만 FAILED 레코드 노출.
          body: JSON.stringify(jobHasFailed ? [failedImportRecord] : []),
        });
      },
    );

    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports`,
      { jobId: 'job-fail-1', status: 'PENDING' },
    );

    // 진행 상황 SSE — VALIDATING 단계에서 실패(FAILED, progress=20 보존) 이벤트를 즉시 반환한다.
    // progress=20 은 INSERTING(40~100)과 구분되는 VALIDATING 고정값이며,
    // 프론트는 이 값으로 "어느 단계에서 실패했는지"를 역산한다.
    await page.route(
      (url) => url.pathname.includes('/jobs/') && url.pathname.includes('/progress'),
      (route) => {
        const sseData = JSON.stringify({
          jobId: 'job-fail-1',
          jobType: 'IMPORT',
          stage: 'FAILED',
          progress: 20,
          metadata: {},
          message: null,
          errorMessage: 'Import validation failed, no rows were loaded (1 error(s) found)',
        });
        // 이 SSE 가 FAILED 를 보낸 뒤부터 히스토리 mock 이 FAILED 레코드를 노출하도록 전환한다 —
        // useImportDialog.ts 의 invalidateQueries(stage==='FAILED') 가 이 리페치를 트리거해야만
        // 오류 테이블이 채워진다.
        jobHasFailed = true;
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: `data: ${sseData}\n\nevent: error\ndata: done\n\n`,
        });
      },
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles({
        name: FILE_NAME,
        mimeType: 'text/csv',
        buffer: Buffer.from('id,name\n1,항목 1\nabc,항목 187'),
      }),
    ]);

    const importBtn = page.getByRole('dialog').getByRole('button', { name: '임포트' });
    await expect(importBtn).toBeEnabled({ timeout: 10_000 });
    await importBtn.click();

    // 3단계 진행 화면 — 실패 카드 노출 대기 (영어 고정 메시지로 단언)
    await expect(
      page.getByText('Import validation failed, no rows were loaded (1 error(s) found)'),
    ).toBeVisible({ timeout: 10_000 });

    // 스텝퍼: "검증" 노드가 실패(빨강) 표시되어야 한다
    const validatingLabel = page.getByRole('dialog').getByText('검증', { exact: true });
    await expect(validatingLabel).toHaveClass(/text-destructive/);

    // 스텝퍼: "삽입" 노드는 아직 시작되지 않은 pending 상태여야 한다 (INSERTING 미진입)
    const insertingLabel = page.getByRole('dialog').getByText('삽입', { exact: true });
    await expect(insertingLabel).toHaveClass(/text-muted-foreground/);
    await expect(insertingLabel).not.toHaveClass(/text-destructive/);
    await expect(insertingLabel).not.toHaveClass(/text-success/);

    // 실패 카드 내부에 4열(행/컬럼/값/오류) 오류 테이블이 렌더된다
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('행', { exact: true })).toBeVisible();
    await expect(dialog.getByText('컬럼', { exact: true })).toBeVisible();
    await expect(dialog.getByText('187')).toBeVisible();
    await expect(dialog.getByText('abc')).toBeVisible();
    await expect(dialog.getByText('숫자여야 합니다')).toBeVisible();
  });
});
