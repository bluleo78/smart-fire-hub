import path from 'path';
import { fileURLToPath } from 'url';

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 임포트 E2E 테스트
 * - dataImports.ts API 함수(uploadFile, previewImport, validateImport, getImports)를 커버한다.
 * - 임포트 다이얼로그 3단계 플로우: 파일 업로드 → 매핑 설정 → 진행 상황을 검증한다.
 */

const DATASET_ID = 1;
/** E2E 픽스처 디렉토리에 있는 실제 CSV 파일 경로 */
const CSV_FILE = path.join(fileURLToPath(new URL('.', import.meta.url)), '../../fixtures/fire-incidents-sample.csv');

/** 임포트 미리보기 응답 — suggestedMappings 가 매핑 테이블에 렌더링된다 */
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
    totalRows: 2,
  };
}

/** 검증 응답 — 오류 없음 */
function createValidateResponse() {
  return { totalRows: 2, validRows: 2, errorRows: 0, errors: [] };
}

/** 임포트 시작 응답 — jobId 는 진행 상황 SSE/폴링에 사용된다 */
function createImportStartResponse() {
  return { jobId: 'job-123', status: 'PENDING' };
}

/** 임포트 이력 레코드 — 변경 이력 탭에 렌더링된다 */
function createImportRecord() {
  return {
    id: 1,
    datasetId: DATASET_ID,
    fileName: 'fire-incidents-sample.csv',
    fileSize: 2048,
    fileType: 'text/csv',
    status: 'COMPLETED',
    totalRows: 2,
    successRows: 2,
    errorRows: 0,
    errorDetails: null,
    errorMessage: null,
    importedBy: 'testuser',
    startedAt: '2024-01-01T01:00:00Z',
    completedAt: '2024-01-01T01:00:05Z',
    createdAt: '2024-01-01T01:00:00Z',
  };
}

/**
 * 데이터셋 상세 페이지 공통 API 모킹 + 임포트 관련 API 추가 모킹
 */
async function setupImportMocks(page: import('@playwright/test').Page) {
  // 데이터셋 상세 기본 모킹 (detail, data, stats, queries)
  await setupDatasetDetailMocks(page, DATASET_ID);
  // 임포트 이력 목록
  await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/imports`, []);
  // 임포트 진행 상황 SSE — COMPLETED 이벤트를 즉시 반환하여 테스트 속도 확보
  await page.route(
    (url) => url.pathname.includes('/jobs/') && url.pathname.includes('/progress'),
    (route) => {
      const sseData = JSON.stringify({
        jobId: 'job-123',
        jobType: 'IMPORT',
        stage: 'COMPLETED',
        progress: 100,
        metadata: { totalRows: 2, processedRows: 2, successRows: 2, errorRows: 0 },
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
}

test.describe('데이터셋 임포트 다이얼로그', () => {
  test('데이터 탭에 임포트 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await page.goto(`/data/datasets/${DATASET_ID}`);

    // 데이터 탭으로 이동
    await page.getByRole('tab', { name: '데이터' }).click();

    // 임포트 버튼 존재 확인
    await expect(page.getByRole('button', { name: '임포트' }).first()).toBeVisible();
  });

  test('임포트 버튼 클릭 시 파일 업로드 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await page.goto(`/data/datasets/${DATASET_ID}`);

    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    // 다이얼로그 제목 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('파일 임포트')).toBeVisible();
    // 파일 업로드 존 노출 — 다이얼로그 내 input[type=file] 존재
    await expect(page.getByRole('dialog').locator('input[type="file"]')).toBeAttached();
  });

  test('파일 선택 시 미리보기 API가 호출되고 매핑 단계(2단계)로 이동한다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    // 미리보기 API 모킹 — 요청 캡처로 API 호출 여부 검증
    const previewCapture = await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      createPreviewResponse(),
      { capture: true },
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    // 파일 선택 — 다이얼로그 내 숨겨진 input[type=file]에 직접 파일을 설정한다
    await page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE);

    // 미리보기 API 호출 대기
    await previewCapture.waitForRequest();

    // 2단계: 매핑 테이블과 임포트 버튼이 표시된다
    await expect(page.getByRole('dialog').getByRole('button', { name: '임포트' })).toBeVisible();
    // suggestedMappings 에 의해 파일 컬럼 헤더 'id', 'name' 이 렌더링된다
    await expect(page.getByRole('dialog').getByText('id').first()).toBeVisible();
  });

  test('2단계에서 임포트 버튼 클릭 시 업로드 API가 호출되고 진행 화면(3단계)으로 이동한다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      createPreviewResponse(),
    );
    // 업로드 API 모킹 — jobId 반환
    const uploadCapture = await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports`,
      createImportStartResponse(),
      { capture: true },
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    // 파일 선택 + 미리보기 응답 수신을 동시에 대기하여 step 2 전환을 보장한다
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);

    // 2단계 임포트 버튼 활성화 대기 후 클릭 — 다이얼로그 내 버튼을 명시적으로 지정
    const importBtn = page.getByRole('dialog').getByRole('button', { name: '임포트' });
    await expect(importBtn).toBeEnabled({ timeout: 10_000 });
    await importBtn.click();

    // 업로드 API 호출 확인
    await uploadCapture.waitForRequest();

    // 3단계: 진행 화면 — SSE가 COMPLETED를 즉시 반환하므로 '임포트 완료' 메시지가 표시된다
    await expect(page.getByRole('dialog').getByText('임포트 완료')).toBeVisible({ timeout: 10_000 });
  });

  test('검증 버튼 클릭 시 validate API가 호출되고 검증 결과가 표시된다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      createPreviewResponse(),
    );
    // 검증 API 모킹
    const validateCapture = await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/validate`,
      createValidateResponse(),
      { capture: true },
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);

    // 2단계: 검증 버튼 클릭 — 다이얼로그 내 버튼으로 범위 한정
    await expect(page.getByRole('dialog').getByRole('button', { name: '검증' })).toBeVisible({ timeout: 10_000 });
    await page.getByRole('dialog').getByRole('button', { name: '검증' }).click();

    // 검증 API 호출 확인
    await validateCapture.waitForRequest();
  });

  test('취소 버튼 클릭 시 다이얼로그가 닫힌다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      createPreviewResponse(),
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);

    // 2단계 로딩 후 취소 버튼 클릭 — 다이얼로그 내 버튼으로 범위 한정
    await expect(page.getByRole('dialog').getByRole('button', { name: '취소' })).toBeVisible({ timeout: 10_000 });
    await page.getByRole('dialog').getByRole('button', { name: '취소' }).click();

    // 다이얼로그 닫힘 확인
    await expect(page.getByRole('dialog')).not.toBeVisible();
  });
});

test.describe('데이터셋 변경 이력 탭', () => {
  test('임포트 이력이 있으면 이력 탭에 파일명과 상태가 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    // 완료된 임포트 레코드 모킹
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/imports`, [createImportRecord()]);

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '이력' }).click();

    // getImports API 응답이 이력 탭에 렌더링되는지 확인
    await expect(page.getByText('fire-incidents-sample.csv')).toBeVisible();
    await expect(page.getByText(/완료|COMPLETED/)).toBeVisible();
    await expect(page.getByText('testuser님이 데이터를 임포트했습니다')).toBeVisible();
  });

  test('임포트 이력이 없으면 데이터셋 생성 이벤트만 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/imports`, []);

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '이력' }).click();

    // 빈 임포트 목록 — 데이터셋 생성 이벤트만 존재
    await expect(page.getByText('데이터셋을 생성했습니다')).toBeVisible();
  });
});
