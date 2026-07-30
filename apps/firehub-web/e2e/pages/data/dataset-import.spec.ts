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

/** 검증 응답 — 오류 없음 (샘플 검증) */
function createValidateResponse() {
  return { sampleSize: 2, validRows: 2, errorRows: 0, sampled: true, errors: [] };
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
  test('데이터 탭에 임포트 버튼이 표시된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
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

  test('파일 선택 시 미리보기 API가 호출되고 매핑 단계(2단계)로 이동한다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
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

  /**
   * #40 회귀 방지 — 2단계(컬럼 매핑) 진입 시 취소/임포트 버튼이 뷰포트 내에 보여야 한다.
   * sticky footer 패턴이 올바르게 적용되어 버튼이 스크롤 밖으로 밀리지 않음을 검증한다.
   */
  test('#40 회귀 방지 — 2단계에서 취소/임포트 버튼이 뷰포트 내에 표시된다', async ({ authenticatedPage: page }) => {
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

    // 파일 선택 후 2단계 진입
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);

    const cancelBtn = page.getByRole('dialog').getByRole('button', { name: '취소' });
    const importBtn = page.getByRole('dialog').getByRole('button', { name: '임포트' });

    // 버튼이 뷰포트 내에 보여야 한다 (isIntersectingViewport 기준)
    await expect(cancelBtn).toBeVisible({ timeout: 10_000 });
    await expect(importBtn).toBeVisible({ timeout: 10_000 });

    // 버튼이 실제로 뷰포트 안에 위치하는지 확인 (스크롤 없이 접근 가능)
    const cancelInViewport = await cancelBtn.evaluate((el) => {
      const rect = el.getBoundingClientRect();
      return rect.top >= 0 && rect.bottom <= window.innerHeight;
    });
    const importInViewport = await importBtn.evaluate((el) => {
      const rect = el.getBoundingClientRect();
      return rect.top >= 0 && rect.bottom <= window.innerHeight;
    });

    expect(cancelInViewport).toBe(true);
    expect(importInViewport).toBe(true);
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

test.describe('FileUploadZone 파일 형식 검증 — #24 회귀 방지', () => {
  /**
   * 드래그 앤 드롭으로 지원하지 않는 파일을 떨어뜨렸을 때
   * "CSV, XLSX, 또는 XLSB 파일만 지원합니다" 토스트가 뜨고, 파일이 선택되지 않음을 검증한다.
   */
  test('드래그 앤 드롭으로 .txt 파일 투하 시 에러 토스트가 표시되고 파일이 선택되지 않는다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    // 다이얼로그가 열렸는지 확인
    await expect(page.getByRole('dialog')).toBeVisible();

    // DataTransfer를 사용한 synthetic drop 이벤트 — 브라우저 input[accept] 가 적용되지 않는 경로를 재현
    await page.evaluate(() => {
      const dt = new DataTransfer();
      const file = new File(['invalid content'], 'document.txt', { type: 'text/plain' });
      dt.items.add(file);
      const zone = document.querySelector('[role="dialog"] .border-dashed') as HTMLElement;
      if (!zone) throw new Error('drop zone not found');
      zone.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
    });

    // 에러 토스트가 표시된다
    await expect(page.getByText('CSV, XLSX, 또는 XLSB 파일만 지원합니다')).toBeVisible({ timeout: 5000 });

    // 잘못된 파일명이 드롭존에 표시되지 않는다 (setSelectedFile 이 호출되어선 안 됨)
    await expect(page.getByRole('dialog').getByText('document.txt')).not.toBeVisible();
  });

  test('드래그 앤 드롭으로 .pdf 파일 투하 시 에러 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    await expect(page.getByRole('dialog')).toBeVisible();

    await page.evaluate(() => {
      const dt = new DataTransfer();
      const file = new File(['%PDF-1.4'], 'report.pdf', { type: 'application/pdf' });
      dt.items.add(file);
      const zone = document.querySelector('[role="dialog"] .border-dashed') as HTMLElement;
      if (!zone) throw new Error('drop zone not found');
      zone.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
    });

    await expect(page.getByText('CSV, XLSX, 또는 XLSB 파일만 지원합니다')).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('dialog').getByText('report.pdf')).not.toBeVisible();
  });

  test('드래그 앤 드롭으로 .csv 파일 투하 시 에러 없이 파일이 선택된다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    // 미리보기 API 모킹 — CSV 파일 투하 시 preview 가 호출됨
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/preview`, createPreviewResponse());

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    await expect(page.getByRole('dialog')).toBeVisible();

    // 유효한 CSV 파일 드롭 — input[type=file]을 통해 처리 경로를 검증
    await page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE);

    // 에러 토스트 없이 미리보기 단계(2단계)로 진행돼야 함
    await expect(page.getByText('CSV, XLSX, 또는 XLSB 파일만 지원합니다')).not.toBeVisible();
    await expect(page.getByRole('dialog').getByRole('button', { name: '임포트' })).toBeVisible({ timeout: 10_000 });
  });
});

test.describe('임포트 에러 처리 — useImportDialog 분기', () => {
  test('임포트 API 409 충돌 — "이미 진행 중인 임포트가 있습니다." 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/preview`, createPreviewResponse());

    // 임포트 시작 API → 409 Conflict 반환
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/imports` && !url.pathname.includes('/preview') && !url.pathname.includes('/validate'),
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ message: 'Import already in progress' }) });
        }
        return route.continue();
      },
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);

    const importBtn = page.getByRole('dialog').getByRole('button', { name: '임포트' });
    await expect(importBtn).toBeEnabled({ timeout: 10_000 });

    // 409 응답 수신 후 토스트 노출을 대기한다
    await Promise.all([
      page.waitForResponse((r) => r.url().includes(`/datasets/${DATASET_ID}/imports`) && r.status() === 409),
      importBtn.click(),
    ]);

    // handleImport catch → axios.isAxiosError && status === 409 → toast.error
    await expect(page.getByText('이미 진행 중인 임포트가 있습니다.')).toBeVisible({ timeout: 5000 });
  });

  test('검증 결과 errorRows > 0 — 경고 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/preview`, createPreviewResponse());
    // 검증 API → errorRows: 2 반환
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/validate`,
      { sampleSize: 2, validRows: 0, errorRows: 2, sampled: true, errors: [{ row: 1, message: '형식 오류' }] },
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);

    const validateBtn = page.getByRole('dialog').getByRole('button', { name: '검증' });
    await expect(validateBtn).toBeVisible({ timeout: 10_000 });

    // 검증 API 응답 후 경고 토스트를 대기한다
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/validate') && r.status() === 200),
      validateBtn.click(),
    ]);

    // handleValidate → result.errorRows > 0 → toast.warning (샘플 기준 문구)
    await expect(page.getByText(/샘플 2행 중 2개 오류/)).toBeVisible({ timeout: 5000 });
  });
});

/**
 * #128 회귀 방지 — 0행 CSV(헤더만 있는 파일) 임포트 시 임포트 버튼 비활성화
 * 검증 결과 validRows === 0 이면 임포트 버튼이 disabled 상태여야 한다.
 */
test.describe('#128 회귀 방지 — 0행 데이터 CSV 임포트 버튼 비활성화', () => {
  /**
   * 헤더만 있는 파일 미리보기 응답 — 데이터 행 없음.
   * suggestedMappings에 필수 컬럼(id)을 포함해야 hasUnmappedRequired === false 가 되어
   * 검증 버튼이 활성화된다.
   */
  function createHeaderOnlyPreviewResponse() {
    return {
      fileHeaders: ['id', 'name'],
      sampleRows: [],
      suggestedMappings: [
        { fileColumn: 'id', datasetColumn: 'id', matchType: 'EXACT', confidence: 1.0 },
        { fileColumn: 'name', datasetColumn: 'name', matchType: 'EXACT', confidence: 1.0 },
      ],
      totalRows: 0,
    };
  }

  /** 0행 검증 응답 — validRows === 0 (샘플 검증) */
  function createZeroRowValidateResponse() {
    return { sampleSize: 0, validRows: 0, errorRows: 0, sampled: true, errors: [] };
  }

  test('검증 후 validRows === 0 이면 임포트 버튼이 비활성화된다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      createHeaderOnlyPreviewResponse(),
    );
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/validate`,
      createZeroRowValidateResponse(),
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    // 파일 선택 → 미리보기 응답 수신 후 2단계 진입
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);

    // 검증 버튼 클릭 → 0행 검증 결과 수신
    const validateBtn = page.getByRole('dialog').getByRole('button', { name: '검증' });
    await expect(validateBtn).toBeVisible({ timeout: 10_000 });
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/validate') && r.status() === 200),
      validateBtn.click(),
    ]);

    // 검증 완료 후 임포트 버튼이 disabled 상태여야 한다
    const importBtn = page.getByRole('dialog').getByRole('button', { name: '임포트' });
    await expect(importBtn).toBeDisabled({ timeout: 5_000 });
  });

  test('validRows > 0 이면 임포트 버튼이 활성화된다 (정상 케이스 회귀 방지)', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      createPreviewResponse(),
    );
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/validate`,
      createValidateResponse(),
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);

    const validateBtn = page.getByRole('dialog').getByRole('button', { name: '검증' });
    await expect(validateBtn).toBeVisible({ timeout: 10_000 });
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/validate') && r.status() === 200),
      validateBtn.click(),
    ]);

    // 정상 케이스: validRows === 2 → 임포트 버튼 활성화
    const importBtn = page.getByRole('dialog').getByRole('button', { name: '임포트' });
    await expect(importBtn).toBeEnabled({ timeout: 5_000 });
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

/**
 * 대용량 CSV 미리보기 슬라이스 회귀 방지.
 * 400MB CSV 미리보기가 413(Entity Too Large)으로 실패하던 문제를 막기 위해, 프론트는 CSV의
 * 앞부분(최대 2MB)만 잘라 전송하고 ?partial=true를 붙인다. (dataImports.ts previewImport)
 */
test.describe('대용량 CSV 미리보기 슬라이스', () => {
  const PREVIEW_MAX_BYTES = 2 * 1024 * 1024;

  /** minBytes 이상이 되도록 유효한 CSV 버퍼를 생성한다 (라인 배열 후 1회 join — O(n)) */
  function makeCsvBuffer(minBytes: number): Buffer {
    const lines: string[] = ['id,name'];
    let bytes = 8;
    let i = 0;
    while (bytes < minBytes) {
      const line = `${i},row-value-${i}-padding-abcdefghij`;
      lines.push(line);
      bytes += line.length + 1;
      i++;
    }
    return Buffer.from(lines.join('\n') + '\n', 'utf-8');
  }

  /** 미리보기 요청의 URL·본문 크기를 캡처하는 라우트를 설치한다 */
  async function capturePreviewRequest(page: import('@playwright/test').Page) {
    const cap = { url: '', bodySize: -1 };
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      (route) => {
        cap.url = route.request().url();
        // 대용량 멀티파트 본문에서 postDataBuffer가 실패하더라도 응답 fulfill은 반드시 수행한다
        try {
          cap.bodySize = route.request().postDataBuffer()?.length ?? -1;
        } catch {
          cap.bodySize = -1;
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPreviewResponse()),
        });
      },
    );
    return cap;
  }

  async function openImportDialog(page: import('@playwright/test').Page) {
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();
  }

  test('대용량 CSV는 앞부분만 잘라 partial=true로 전송된다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    const cap = await capturePreviewRequest(page);
    await openImportDialog(page);

    // 3MB CSV 투하 → 미리보기 호출
    const largeCsv = makeCsvBuffer(3 * 1024 * 1024);
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles({
        name: 'large.csv',
        mimeType: 'text/csv',
        buffer: largeCsv,
      }),
    ]);

    // partial 플래그가 붙는다
    expect(cap.url).toContain('partial=true');
    // 전송 본문은 원본(3MB)보다 작고, 슬라이스 한도(2MB) + 멀티파트 오버헤드 수준이다
    expect(cap.bodySize).toBeGreaterThan(1 * 1024 * 1024);
    expect(cap.bodySize).toBeLessThan(PREVIEW_MAX_BYTES + 64 * 1024);
    // 2단계(매핑)로 진입
    await expect(page.getByRole('dialog').getByRole('button', { name: '임포트' })).toBeVisible();
  });

  test('소용량 CSV는 전체가 전송되고 partial 플래그가 없다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    const cap = await capturePreviewRequest(page);
    await openImportDialog(page);

    const smallCsv = Buffer.from('id,name\n1,항목 1\n2,항목 2\n', 'utf-8');
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles({
        name: 'small.csv',
        mimeType: 'text/csv',
        buffer: smallCsv,
      }),
    ]);

    // 슬라이스하지 않았으므로 partial 없음, 본문에 원본 바이트가 모두 포함된다
    expect(cap.url).not.toContain('partial=true');
    expect(cap.bodySize).toBeGreaterThanOrEqual(smallCsv.length);
  });
});

/**
 * #331 / #330 회귀: 임포트 다이얼로그의 접근성 배선.
 * - #331 컬럼 매핑 Select에 "어느 파일 컬럼의 매핑인지"를 담은 접근 가능한 이름
 * - #330 단계 전환 고지(포커스 이동) + 검증 진행·결과 live region
 */
test.describe('임포트 다이얼로그 접근성 (#330 #331)', () => {
  /** 파일 업로드 → 2단계(컬럼 매핑) 진입까지 공통 절차 */
  async function openMappingStep(page: import('@playwright/test').Page) {
    await setupImportMocks(page);
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/preview`, createPreviewResponse());

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);
  }

  test('#331 컬럼 매핑 Select가 파일 컬럼명을 포함한 접근 가능한 이름을 가진다', async ({
    authenticatedPage: page,
  }) => {
    await openMappingStep(page);

    // 이름이 없으면 스크린리더에는 선택된 값("id")만 읽혀 어느 행인지 구별되지 않는다.
    // createPreviewResponse()의 파일 컬럼 id/name 각각에 대해 이름으로 조회 가능해야 한다.
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('combobox', { name: 'id 컬럼을 매핑할 데이터셋 컬럼' })).toBeVisible();
    await expect(dialog.getByRole('combobox', { name: 'name 컬럼을 매핑할 데이터셋 컬럼' })).toBeVisible();
    // 이름 없는 combobox가 남아 있지 않은지도 확인한다(같은 표의 다른 행 누락 방지).
    const unnamed = await dialog.locator('[role=combobox]').evaluateAll((els) =>
      els.filter((el) => !el.getAttribute('aria-label') && !el.getAttribute('aria-labelledby')).length,
    );
    expect(unnamed).toBe(0);
  });

  test('#330 2단계 진입 시 포커스가 "컬럼 매핑" 제목으로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await openMappingStep(page);

    // 수정 전에는 다이얼로그 컨테이너 <div>에 포커스가 남아 내용 교체가 고지되지 않았다.
    const heading = page.getByRole('dialog').getByRole('heading', { name: '컬럼 매핑' });
    await expect(heading).toBeFocused();
  });

  test('#330 검증 진행·결과가 live region 텍스트로 고지된다', async ({ authenticatedPage: page }) => {
    await openMappingStep(page);
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/validate`,
      createValidateResponse(),
    );

    const dialog = page.getByRole('dialog');
    const status = dialog.getByTestId('import-validation-status');
    // live region은 결과와 함께 생기면 낭독되지 않으므로, 검증 전부터 비어 있는 채로 존재해야 한다.
    await expect(status).toHaveAttribute('aria-live', 'polite');
    await expect(status).toHaveText('');

    await dialog.getByRole('button', { name: '검증' }).click();

    // 검증 성공 결과가 카드뿐 아니라 live region 텍스트로도 전달된다(유효 행 수 포함).
    await expect(status).toHaveText('검증 성공, 유효 2행');
  });

  test('#330 검증 오류가 발견되면 오류 건수까지 live region에 실린다', async ({
    authenticatedPage: page,
  }) => {
    await openMappingStep(page);
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/validate`, {
      sampleSize: 2,
      validRows: 1,
      errorRows: 1,
      sampled: true,
      errors: [{ rowNumber: 2, columnName: 'id', value: 'x', message: '숫자가 아닙니다' }],
    });

    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', { name: '검증' }).click();

    await expect(dialog.getByTestId('import-validation-status')).toHaveText(
      '검증 오류 발견, 유효 1행, 오류 1건',
    );
  });
});
