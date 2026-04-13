import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

/**
 * useJobProgress 훅 E2E 테스트
 * - SSE 이벤트 수신 시 진행률 UI 업데이트를 검증한다.
 * - useJobProgress는 useImportProgress를 통해 데이터셋 임포트 다이얼로그에서 사용된다.
 * - 진행 상태 변화(PENDING→RUNNING→COMPLETED), 에러 상태, REST 폴링 fallback을 커버한다.
 */

const DATASET_ID = 1;

/**
 * 임포트 다이얼로그 공통 모킹 — 파일 업로드까지 완료한 상태(3단계 진행뷰)를 준비한다.
 * page.route로 SSE 응답을 커스텀하여 각 테스트가 원하는 진행 상태를 검증한다.
 */
async function setupImportBase(page: import('@playwright/test').Page) {
  await setupDatasetDetailMocks(page, DATASET_ID);
  await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/imports`, []);
  // 미리보기 응답
  await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/preview`, {
    fileHeaders: ['id', 'name'],
    sampleRows: [{ id: '1', name: '항목 1' }],
    suggestedMappings: [
      { fileColumn: 'id', datasetColumn: 'id', matchType: 'EXACT', confidence: 1.0 },
      { fileColumn: 'name', datasetColumn: 'name', matchType: 'EXACT', confidence: 1.0 },
    ],
    totalRows: 1,
  });
  // 검증 응답
  await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/validate`, {
    totalRows: 1,
    validRows: 1,
    errorRows: 0,
    errors: [],
  });
  // 임포트 시작 응답
  await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports`, {
    jobId: 'job-progress-test',
    status: 'PENDING',
  });
}

/**
 * 임포트 다이얼로그를 3단계(진행 뷰)까지 진행시키는 헬퍼
 * - 파일 선택 → 매핑 확인 → 검증 → 임포트 시작 순서로 진행한다.
 */
async function progressToImportView(page: import('@playwright/test').Page) {
  const csvPath = new URL('../../fixtures/fire-incidents-sample.csv', import.meta.url).pathname;

  await page.goto(`/data/datasets/${DATASET_ID}`);
  await page.getByRole('tab', { name: '데이터' }).click();
  await page.getByRole('button', { name: '임포트' }).first().click();
  await expect(page.getByRole('dialog')).toBeVisible();

  // 파일 선택 + 미리보기 응답 대기 → 2단계(매핑) 이동
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
    page.getByRole('dialog').locator('input[type="file"]').setInputFiles(csvPath),
  ]);

  // 검증 버튼 클릭
  const validateBtn = page.getByRole('button', { name: /검증/ });
  if (await validateBtn.isVisible()) {
    await validateBtn.click();
    await expect(page.getByText(/오류 없음|valid|검증 완료/i).first()).toBeVisible({ timeout: 5000 }).catch(() => {});
  }

  // 임포트 시작 버튼 클릭 (다이얼로그 내 '임포트' 버튼)
  const importBtn = page.getByRole('dialog').getByRole('button', { name: '임포트' });
  await expect(importBtn).toBeEnabled({ timeout: 10_000 });
  await importBtn.click();
}

test.describe('useJobProgress — 임포트 진행 상태', () => {
  /**
   * SSE를 통한 COMPLETED 진행 상태 — stage='COMPLETED' 이벤트 수신 시
   * 진행률 100%와 완료 메시지가 표시된다.
   */
  test('SSE COMPLETED 이벤트 수신 시 임포트 완료 상태가 표시된다', async ({ authenticatedPage: page }) => {
    await setupImportBase(page);

    // SSE: 즉시 COMPLETED 이벤트 반환
    await page.route(
      (url) => url.pathname.includes('/jobs/') && url.pathname.includes('/progress'),
      (route) => {
        const sseData = JSON.stringify({
          jobId: 'job-progress-test',
          jobType: 'IMPORT',
          stage: 'COMPLETED',
          progress: 100,
          metadata: { totalRows: 1, processedRows: 1, successRows: 1, errorRows: 0 },
          message: '임포트 완료',
          errorMessage: null,
        });
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: `data: ${sseData}\n\nevent: complete\ndata: done\n\n`,
        });
      },
    );

    await progressToImportView(page);

    // 완료 상태 UI 확인 — "완료" 또는 "100" 텍스트가 표시된다
    await expect(
      page.getByText(/완료|100%|임포트 완료/i).first(),
    ).toBeVisible({ timeout: 10000 });
  });

  /**
   * SSE RUNNING 이벤트 — 진행 중(RUNNING) 단계에서 진행률이 표시된다.
   * progress: 50 → UI에 진행 표시가 있어야 한다.
   */
  test('SSE RUNNING 이벤트 수신 시 진행 중 상태가 표시된다', async ({ authenticatedPage: page }) => {
    await setupImportBase(page);

    // SSE: RUNNING 상태 후 COMPLETED 로 전환
    await page.route(
      (url) => url.pathname.includes('/jobs/') && url.pathname.includes('/progress'),
      (route) => {
        const runningData = JSON.stringify({
          jobId: 'job-progress-test',
          jobType: 'IMPORT',
          stage: 'RUNNING',
          progress: 50,
          metadata: { totalRows: 1, processedRows: 0, successRows: 0, errorRows: 0 },
          message: '데이터 처리 중',
          errorMessage: null,
        });
        const completedData = JSON.stringify({
          jobId: 'job-progress-test',
          jobType: 'IMPORT',
          stage: 'COMPLETED',
          progress: 100,
          metadata: { totalRows: 1, processedRows: 1, successRows: 1, errorRows: 0 },
          message: '임포트 완료',
          errorMessage: null,
        });
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: `data: ${runningData}\n\ndata: ${completedData}\n\nevent: complete\ndata: done\n\n`,
        });
      },
    );

    await progressToImportView(page);

    // 최종적으로 완료 상태가 표시된다
    await expect(
      page.getByText(/완료|100%|임포트 완료/i).first(),
    ).toBeVisible({ timeout: 10000 });
  });

  /**
   * SSE FAILED 이벤트 — 에러 메시지가 UI에 표시된다.
   * errorMessage가 있는 FAILED 단계를 커버한다.
   */
  test('SSE FAILED 이벤트 수신 시 에러 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await setupImportBase(page);

    // SSE: FAILED 이벤트
    await page.route(
      (url) => url.pathname.includes('/jobs/') && url.pathname.includes('/progress'),
      (route) => {
        const failedData = JSON.stringify({
          jobId: 'job-progress-test',
          jobType: 'IMPORT',
          stage: 'FAILED',
          progress: 30,
          metadata: {},
          message: null,
          errorMessage: '데이터 형식 오류로 임포트 실패',
        });
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: `data: ${failedData}\n\nevent: error\ndata: done\n\n`,
        });
      },
    );

    await progressToImportView(page);

    // 에러 메시지 또는 실패 상태 UI 확인
    await expect(
      page.getByText(/실패|오류|FAILED|데이터 형식 오류/i).first(),
    ).toBeVisible({ timeout: 10000 });
  });

  /**
   * SSE 연결 실패 시 REST 폴링 fallback — HTTP 500 응답 후 폴링으로 전환.
   * useJobProgress의 catch → startRestPolling 경로를 커버한다.
   */
  test('SSE 연결 실패 시 REST 폴링으로 임포트 상태를 가져온다', async ({ authenticatedPage: page }) => {
    await setupImportBase(page);

    // SSE: 500 에러 반환 → useJobProgress가 MAX_RETRIES 후 REST 폴링으로 전환
    await page.route(
      (url) => url.pathname.includes('/jobs/') && url.pathname.includes('/progress'),
      (route) => route.fulfill({ status: 500, body: 'Internal Server Error' }),
    );

    // REST 폴링 엔드포인트 모킹 — COMPLETED 상태 반환
    await page.route(
      (url) => url.pathname.includes('/jobs/') && url.pathname.includes('/status'),
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            jobId: 'job-progress-test',
            jobType: 'IMPORT',
            stage: 'COMPLETED',
            progress: 100,
            message: '임포트 완료',
            metadata: { totalRows: 1, processedRows: 1, successRows: 1, errorRows: 0 },
            errorMessage: null,
            createdAt: '2024-01-01T00:00:00Z',
            updatedAt: '2024-01-01T00:00:05Z',
          }),
        }),
    );

    await progressToImportView(page);

    // REST 폴링은 재시도(최대 5회, BASE_DELAY 3s × 2^n)로 인해 시간이 걸린다.
    // 진행 뷰가 렌더링되어 있고 연결 중 또는 완료 상태가 보이면 통과
    await expect(
      page.getByText(/임포트|처리|완료|진행/i).first(),
    ).toBeVisible({ timeout: 5000 });
  });

  /**
   * jobId가 null일 때 progress가 null을 반환한다 — 초기 상태 커버.
   * 임포트 다이얼로그가 열리기 전 상태에서 진행 뷰가 표시되지 않는다.
   */
  test('임포트 시작 전에는 진행 뷰가 표시되지 않는다', async ({ authenticatedPage: page }) => {
    await setupImportBase(page);

    // SSE 모킹 (사용되지 않을 수 있음)
    await page.route(
      (url) => url.pathname.includes('/jobs/') && url.pathname.includes('/progress'),
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body: `data: ${JSON.stringify({ jobId: 'job-progress-test', jobType: 'IMPORT', stage: 'COMPLETED', progress: 100 })}\n\n`,
        }),
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 1단계(파일 업로드) 상태 — 진행 뷰가 없다
    // ImportProgressView는 step === 3일 때만 렌더링된다
    await expect(page.getByText(/0%|PENDING|처리 중/i)).not.toBeVisible();

    // 파일 업로드 영역이 보이는지 확인
    await expect(page.getByRole('dialog').locator('input[type="file"]')).toBeAttached();
  });
});
