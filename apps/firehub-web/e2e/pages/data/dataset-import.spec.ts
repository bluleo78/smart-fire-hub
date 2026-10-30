import path from 'path';
import { fileURLToPath } from 'url';

import type { ImportPreviewResponse, ImportSheetInfo } from '../../../src/types/dataImport';
import { createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

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
 * @param completedMetadata 진행 SSE 의 COMPLETED 이벤트 metadata — 결과 요약(전체/성공/오류/빈 행) 표시를 바꿔 볼 때 넘긴다
 */
async function setupImportMocks(
  page: import('@playwright/test').Page,
  completedMetadata: Record<string, number> = { totalRows: 2, processedRows: 2, successRows: 2, errorRows: 0 },
) {
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
        metadata: completedMetadata,
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

// 쓰기 진입점은 서버 권한이 있을 때만 보인다(WD-139) — 이 파일은 편집·적재 흐름을 검증하므로 데이터셋 쓰기 권한을 준다.
// 개별 테스트가 다른 권한을 주면 그쪽이 나중에 등록돼 이긴다.
test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

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

  // WD-255: 서버가 건너뛴 빈 행 수(skippedRows)를 결과에 보내면 완료 요약에 「빈 행 건너뜀」 줄이 나오고, 0 이면 나오지 않는다
  for (const { skippedRows, shown } of [
    { skippedRows: 3, shown: true },
    { skippedRows: 0, shown: false },
  ]) {
    test(`임포트 완료 요약 — 건너뛴 빈 행 ${skippedRows}개${shown ? '를 보여 준다' : '면 줄을 숨긴다'}`, async ({
      authenticatedPage: page,
    }) => {
      await setupImportMocks(page, { totalRows: 2, processedRows: 2, successRows: 2, errorRows: 0, skippedRows });
      await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/preview`, createPreviewResponse());
      await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports`, createImportStartResponse());

      await page.goto(`/data/datasets/${DATASET_ID}`);
      await page.getByRole('tab', { name: '데이터' }).click();
      await page.getByRole('button', { name: '임포트' }).first().click();
      await Promise.all([
        page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
        page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
      ]);
      const importBtn = page.getByRole('dialog').getByRole('button', { name: '임포트' });
      await expect(importBtn).toBeEnabled({ timeout: 10_000 });
      await importBtn.click();

      const dialog = page.getByRole('dialog');
      await expect(dialog.getByText('임포트 완료')).toBeVisible({ timeout: 10_000 });
      // 전체·성공은 데이터 행 기준 그대로
      await expect(dialog.getByText('전체: 2행')).toBeVisible();
      if (shown) {
        await expect(dialog.getByText('빈 행 건너뜀: 3행')).toBeVisible();
      } else {
        await expect(dialog.getByText(/빈 행 건너뜀/)).toHaveCount(0);
      }
    });
  }

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
   * "CSV, XLSX, XLS 또는 XLSB 파일만 지원합니다" 토스트가 뜨고, 파일이 선택되지 않음을 검증한다.
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
    await expect(page.getByText('CSV, XLSX, XLS 또는 XLSB 파일만 지원합니다')).toBeVisible({ timeout: 5000 });

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

    await expect(page.getByText('CSV, XLSX, XLS 또는 XLSB 파일만 지원합니다')).toBeVisible({ timeout: 5000 });
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
    await expect(page.getByText('CSV, XLSX, XLS 또는 XLSB 파일만 지원합니다')).not.toBeVisible();
    await expect(page.getByRole('dialog').getByRole('button', { name: '임포트' })).toBeVisible({ timeout: 10_000 });
  });
});

test.describe('임포트 에러 처리 — useImportDialog 분기', () => {
  test('진행 조회가 404 면 재시도 없이 「진행 상황 확인 불가」와 서버 문구를 보인다 (WD-203)', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/preview`, createPreviewResponse());
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports`, createImportStartResponse());
    // 나중에 등록한 route 가 이긴다 — 진행 SSE·상태 조회 모두 404 로 덮어쓴다
    let jobRequests = 0;
    await page.route(
      (url) => url.pathname.startsWith('/api/v1/jobs/job-123/'),
      (route) => {
        jobRequests += 1;
        return route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ status: 404, message: '작업을 찾을 수 없습니다. (작업 ID: job-123)' }),
        });
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
    await importBtn.click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('진행 상황 확인 불가')).toBeVisible({ timeout: 10_000 });
    await expect(dialog.getByText('작업을 찾을 수 없습니다. (작업 ID: job-123)')).toBeVisible();
    // 임포트 자체가 실패했다고 단정하지 않는다 — 실패 문구도, 단계 스텝퍼(실패 단계 표시)도 없다
    await expect(dialog.getByText('임포트 실패')).toHaveCount(0);
    await expect(dialog.getByText('검증', { exact: true })).toHaveCount(0);

    // 재연결 백오프 첫 주기(3초)를 넘겨도 추가 요청이 없어야 한다
    await page.waitForTimeout(4000);
    expect(jobRequests).toBe(1);
  });

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

  /**
   * WD-204 — 엑셀이 아니거나 손상된 파일: 서버가 400 + 한국어 안내를 돌려주면 그 문구가 토스트로 보이고,
   * 다이얼로그는 1단계(파일 선택 영역)에 머문다(빈 2단계로 넘어가지 않는다).
   */
  test('미리보기 400(읽을 수 없는 엑셀) — 서버 한국어 안내가 토스트로 보이고 1단계에 머문다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    const message = '엑셀 파일을 읽을 수 없습니다. 파일이 손상되었거나 엑셀 형식이 아닙니다.';
    await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      { status: 400, error: 'Bad Request', message, timestamp: '2024-01-01T00:00:00Z', path: `/api/v1/datasets/${DATASET_ID}/imports/preview` },
      { status: 400 },
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    const dialog = page.getByRole('dialog');
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 400),
      dialog.locator('input[type="file"]').setInputFiles({
        name: 'fake.xlsx',
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer: Buffer.from('not an excel file'),
      }),
    ]);

    await expect(page.getByText(message)).toBeVisible({ timeout: 5000 });
    // 1단계 파일 선택 영역(선택한 파일 이름)이 그대로 보이고, 2단계(임포트 버튼)로 넘어가지 않았다
    await expect(dialog.getByText(/fake\.xlsx/)).toBeVisible();
    await expect(dialog.getByRole('button', { name: '임포트' })).toHaveCount(0);
  });

  /** WD-204 — 서버가 성공 상태로 빈 본문을 돌려줘도 빈 2단계 대신 오류 안내를 보이고 1단계에 머문다(방어). */
  test('미리보기 200 빈 본문 — 오류 토스트가 보이고 빈 2단계로 넘어가지 않는다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      (route) => route.fulfill({ status: 200, body: '' }),
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    const dialog = page.getByRole('dialog');
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      dialog.locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);

    await expect(page.getByText('파일 내용을 읽지 못했습니다. 파일을 확인한 뒤 다시 올려 주세요.')).toBeVisible({ timeout: 5000 });
    await expect(dialog.getByText(/fire-incidents-sample\.csv/)).toBeVisible();
    await expect(dialog.getByRole('button', { name: '임포트' })).toHaveCount(0);
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

/**
 * #653 회귀 방지 — 컬럼이 하나도 매핑되지 않아도 검증 통과·임포트 성공으로 표시되던 문제.
 * NOT NULL(필수) 컬럼이 없는(nullable-only) 데이터셋에서는 hasUnmappedRequired가 항상 false가
 * 되어 매핑 0개 상태를 차단하지 못했다 — hasAnyMapping을 도입해 이 구멍을 별도로 막는다.
 */
test.describe('#653 회귀 방지 — 매핑 0개 시 검증/임포트 차단', () => {
  /**
   * NOT NULL 컬럼이 하나도 없는 데이터셋 모킹 — 재현 조건의 핵심.
   * hasUnmappedRequired만으로는 매핑 0개를 잡아낼 수 없는 유일한 경로를 재현한다.
   */
  async function setupNullableOnlyDatasetMocks(page: import('@playwright/test').Page) {
    const detail = createDatasetDetail({
      id: DATASET_ID,
      columns: [createColumn({ id: 1, columnName: 'record_id', displayName: 'record_id', isPrimaryKey: false, isNullable: true })],
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/data`, {
      columns: detail.columns,
      rows: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/stats`, []);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/queries`, createPageResponse([]));
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/imports`, []);
  }

  /** 파일 컬럼명이 데이터셋 컬럼명과 전혀 달라 전부 미매핑(NONE)으로 제안되는 미리보기 응답 */
  function createAllUnmatchedPreviewResponse() {
    return {
      fileHeaders: ['wrong_col1', 'wrong_col2'],
      sampleRows: [
        { wrong_col1: 'a', wrong_col2: 'b' },
        { wrong_col1: 'c', wrong_col2: 'd' },
      ],
      suggestedMappings: [
        { fileColumn: 'wrong_col1', datasetColumn: null, matchType: 'NONE', confidence: 0 },
        { fileColumn: 'wrong_col2', datasetColumn: null, matchType: 'NONE', confidence: 0 },
      ],
      totalRows: 2,
    };
  }

  test('nullable 컬럼만 있는 데이터셋에서 전부 미매핑이면 검증/임포트 버튼이 비활성화되고 경고가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNullableOnlyDatasetMocks(page);
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/preview`, createAllUnmatchedPreviewResponse());
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

    const dialog = page.getByRole('dialog');
    // 매핑 테이블 상단에 "매핑된 컬럼이 없습니다" 경고 배너가 표시된다 — 필수 필드 배너와
    // 무관하게(이 데이터셋엔 필수 필드가 없음) 항상 노출되어야 한다.
    await expect(dialog.getByText('매핑된 컬럼이 없습니다:')).toBeVisible();

    // 검증 버튼이 비활성화되어 서버 검증(행 단위 값 검사)만으로 "통과"를 오인시키는
    // 경로 자체가 원천 차단된다.
    const validateBtn = dialog.getByRole('button', { name: '검증' });
    await expect(validateBtn).toBeDisabled();

    // 임포트 버튼도 비활성화된다 — 매핑 0개인 채로 전부 NULL 행이 생성되는 것을 방지.
    const importBtn = dialog.getByRole('button', { name: '임포트' });
    await expect(importBtn).toBeDisabled();

    // 검증 API 자체가 호출되지 않았어야 한다(클라이언트에서 선차단).
    expect(validateCapture.requests.length).toBe(0);
  });

  test('일부 컬럼을 매핑하면 경고가 사라지고 검증·임포트가 정상 동작한다 (회귀 없음)', async ({
    authenticatedPage: page,
  }) => {
    await setupNullableOnlyDatasetMocks(page);
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/preview`, createAllUnmatchedPreviewResponse());
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/validate`, createValidateResponse());

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('매핑된 컬럼이 없습니다:')).toBeVisible();

    // wrong_col1을 record_id에 매핑 — 매핑이 1개라도 생기면 차단이 풀려야 한다.
    await dialog.getByRole('combobox', { name: 'wrong_col1 컬럼을 매핑할 데이터셋 컬럼' }).click();
    await page.getByRole('option', { name: 'record_id' }).click();

    await expect(dialog.getByText('매핑된 컬럼이 없습니다:')).not.toBeVisible();
    const validateBtn = dialog.getByRole('button', { name: '검증' });
    await expect(validateBtn).toBeEnabled();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/validate') && r.status() === 200),
      validateBtn.click(),
    ]);

    const importBtn = dialog.getByRole('button', { name: '임포트' });
    await expect(importBtn).toBeEnabled();
  });
});

test.describe('WD-205 — 같은 이름 열 헤더 거절', () => {
  /**
   * WD-205: 헤더에 같은 이름의 열이 있는 파일은 서버가 미리보기 단계에서 400 + 한국어 사유로 거절한다.
   * 웹은 별도 분기 없이 기존 handleApiError 경로로 서버 문구를 토스트에 그대로 보여야 하고, 매핑 단계로 넘어가면 안 된다.
   */
  test('WD-205 중복 헤더 파일 — 미리보기 400 사유가 토스트로 보이고 매핑 단계로 가지 않는다', async ({ authenticatedPage: page }) => {
    const message = '파일에 같은 이름의 열이 있습니다: region (1·2번째 열). 열 이름을 고친 뒤 다시 올려 주세요.';
    await setupImportMocks(page);
    const previewCapture = await mockApi(
      page,
      'POST',
      `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      { status: 400, error: 'Bad Request', message, errors: null },
      { status: 400, capture: true },
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();
    await page.getByRole('dialog').locator('input[type="file"]').setInputFiles({
      name: 'dup-header.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from('region,region,cnt\n서울,부산,1\n', 'utf-8'),
    });

    await previewCapture.waitForRequest();
    // 서버 문구 그대로 토스트 노출 (일반 실패 문구로 바뀌지 않음)
    await expect(page.getByText(message)).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('미리보기 로드에 실패했습니다.')).not.toBeVisible();
    // 매핑 단계(검증 버튼)로 넘어가지 않는다
    await expect(page.getByRole('dialog').getByRole('button', { name: '검증' })).not.toBeVisible();
  });
});

/**
 * WD-369 — 이름이 빈 열이 여럿인 파일.
 * 서버는 행을 「열 이름 → 값」으로 읽어 빈 이름 열 값이 서로 덮어써지므로, 빈 이름 열이 둘 이상이면 그 열들은 매핑할 수 없다.
 * 화면은 그 열들의 선택 상자를 잠그고 안내를 보이며, 매핑 상태·React 키를 위치로 다뤄 한 행을 바꿔도 다른 빈 열이 따라 바뀌지 않아야 한다.
 */
test.describe('WD-369 — 이름 없는 열이 여럿인 파일', () => {
  const HINT = '이름 없는 열이 여러 개라 그 열들은 가져오지 않습니다. 가져오려면 열 이름을 넣은 뒤 다시 올려 주세요.';

  /** fileHeaders 그대로 미리보기를 돌려준다(추천 매핑은 모두 미매핑) */
  function createBlankHeaderPreview(fileHeaders: string[]): ImportPreviewResponse {
    return {
      fileHeaders,
      sampleRows: [Object.fromEntries(fileHeaders.map((h, i) => [h, `v${i + 1}`]))],
      suggestedMappings: fileHeaders.map((h) => ({ fileColumn: h, datasetColumn: null, matchType: 'NONE', confidence: 0 })),
      totalRows: 1,
    } as ImportPreviewResponse;
  }

  /** 검증 요청의 mappings 필드(JSON)를 읽는다 */
  function readMappingsField(request: import('@playwright/test').Request): { fileColumn: string; datasetColumn: string | null }[] {
    const body = request.postDataBuffer()?.toString('utf-8') ?? '';
    const match = body.match(/name="mappings"\r\n\r\n([^\r]*)\r\n/);
    return match ? JSON.parse(match[1]) : [];
  }

  /** 필수(NOT NULL) 컬럼이 없는 데이터셋 — 한 열만 매핑해도 검증이 열리고, 선택지에 필수 표시(*)가 붙지 않는다 */
  async function setupNullableDatasetMocks(page: import('@playwright/test').Page) {
    const detail = createDatasetDetail({
      id: DATASET_ID,
      columns: [
        createColumn({ id: 1, columnName: 'region', displayName: '지역', isPrimaryKey: false, isNullable: true }),
        createColumn({ id: 2, columnName: 'cnt', displayName: '건수', isPrimaryKey: false, isNullable: true, columnOrder: 1 }),
      ],
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/data`, {
      columns: detail.columns,
      rows: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/stats`, []);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/queries`, createPageResponse([]));
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/imports`, []);
  }

  async function openWithPreview(page: import('@playwright/test').Page, fileHeaders: string[]) {
    await setupNullableDatasetMocks(page);
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/preview`, createBlankHeaderPreview(fileHeaders));
    const sentMappings: { fileColumn: string; datasetColumn: string | null }[][] = [];
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/imports/validate`,
      (route) => {
        sentMappings.push(readMappingsField(route.request()));
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createValidateResponse()) });
      },
    );
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);
    return sentMappings;
  }

  test('빈 이름 열이 둘이면 그 열들의 선택 상자가 잠기고 안내가 보이며, 이름 있는 열은 매핑할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    const sentMappings = await openWithPreview(page, ['', '', 'amount']);
    const dialog = page.getByRole('dialog');

    await expect(dialog.getByText(HINT)).toBeVisible();
    const blank1 = dialog.getByRole('combobox', { name: '(이름 없음) 1번째 열 컬럼을 매핑할 데이터셋 컬럼' });
    const blank2 = dialog.getByRole('combobox', { name: '(이름 없음) 2번째 열 컬럼을 매핑할 데이터셋 컬럼' });
    await expect(blank1).toBeDisabled();
    await expect(blank2).toBeDisabled();

    // 이름 있는 열은 그대로 매핑할 수 있다
    const amount = dialog.getByRole('combobox', { name: 'amount 컬럼을 매핑할 데이터셋 컬럼' });
    await expect(amount).toBeEnabled();
    await amount.click();
    await page.getByRole('option', { name: '건수' }).click();
    await expect(amount).toHaveText('건수');
    // 다른 행 매핑이 빈 열 행으로 번지지 않는다
    await expect(blank1).toHaveText('매핑 안 함');
    await expect(blank2).toHaveText('매핑 안 함');

    // 검증 요청에 빈 이름 열은 미매핑으로만 실린다 — 서버가 400 으로 거절할 매핑을 보내지 않는다
    await dialog.getByRole('button', { name: '검증' }).click();
    await expect.poll(() => sentMappings.length).toBe(1);
    expect(sentMappings[0]).toEqual([
      { fileColumn: '', datasetColumn: null },
      { fileColumn: '', datasetColumn: null },
      { fileColumn: 'amount', datasetColumn: 'cnt' },
    ]);
  });

  test('빈 이름 열이 하나뿐이면 잠기지 않고 매핑할 수 있다', async ({ authenticatedPage: page }) => {
    const sentMappings = await openWithPreview(page, ['amount', '']);
    const dialog = page.getByRole('dialog');

    await expect(dialog.getByText(HINT)).not.toBeVisible();
    const blank = dialog.getByRole('combobox', { name: '(이름 없음) 2번째 열 컬럼을 매핑할 데이터셋 컬럼' });
    await expect(blank).toBeEnabled();
    await blank.click();
    await page.getByRole('option', { name: '건수' }).click();
    await expect(blank).toHaveText('건수');
    // 같은 데이터셋 컬럼은 다른 행 선택지에서 빠진다 — 위치 기준 비교라 빈 이름 행의 선택이 amount 행에 「자기 것」으로 보이지 않는다
    const amount = dialog.getByRole('combobox', { name: 'amount 컬럼을 매핑할 데이터셋 컬럼' });
    await expect(amount).toHaveText('매핑 안 함');
    await amount.click();
    await expect(page.getByRole('option', { name: '지역' })).toBeVisible();
    await expect(page.getByRole('option', { name: '건수' })).toHaveCount(0);
    await page.keyboard.press('Escape');

    await dialog.getByRole('button', { name: '검증' }).click();
    await expect.poll(() => sentMappings.length).toBe(1);
    expect(sentMappings[0]).toEqual([
      { fileColumn: 'amount', datasetColumn: null },
      { fileColumn: '', datasetColumn: 'cnt' },
    ]);
  });
});

/**
 * WD-253 — 엑셀 시트 선택.
 * 서버는 기본으로 첫 번째 「보이는」 시트를 읽고, 미리보기 응답의 sheetName/sheets 로 무엇을 읽었는지 알려 준다.
 * 화면은 시트가 여럿이면 「가져올 시트」 선택 상자를, 하나면 읽기 전용 한 줄을 보이고,
 * 고른 시트를 미리보기·검증·임포트 요청의 멀티파트 필드 sheetName 으로 보낸다.
 */
test.describe('엑셀 시트 선택 (WD-253)', () => {
  /** 숨김 시트가 앞에 있는 통합 문서 — 서버 기본 선택은 두 번째 「데이터」 시트 */
  const MULTI_SHEETS: ImportSheetInfo[] = [
    { name: '숨김시트', hidden: true },
    { name: '데이터', hidden: false },
  ];

  /** 엑셀 미리보기 응답 — headers 로 시트마다 다른 파일 컬럼을 돌려준다 */
  function createExcelPreviewResponse(
    sheetName: string,
    sheets: ImportSheetInfo[],
    headers: string[] = ['id', 'name'],
  ): ImportPreviewResponse {
    return {
      fileHeaders: headers,
      sampleRows: [Object.fromEntries(headers.map((h) => [h, `${sheetName}-${h}`]))],
      suggestedMappings: headers.map((h) => ({
        fileColumn: h,
        datasetColumn: h === 'id' || h === 'name' ? h : null,
        matchType: h === 'id' || h === 'name' ? 'EXACT' : 'NONE',
        confidence: h === 'id' || h === 'name' ? 1.0 : 0,
      })),
      totalRows: 1,
      sheetName,
      sheets,
    };
  }

  /** 멀티파트 본문에서 sheetName 필드 값을 꺼낸다 — 없으면 null. 한글 값이라 버퍼를 UTF-8 로 읽는다 */
  function readSheetNameField(request: import('@playwright/test').Request): string | null {
    const body = request.postDataBuffer()?.toString('utf-8') ?? '';
    const match = body.match(/name="sheetName"\r\n\r\n([^\r]*)\r\n/);
    return match ? match[1] : null;
  }

  /**
   * 미리보기 라우트 — 요청의 sheetName 필드에 따라 다른 응답을 준다(지정 없음 = 서버 기본 시트).
   * 요청마다 보낸 sheetName 을 기록해 재호출 여부·payload 를 검증한다.
   */
  async function routeExcelPreview(
    page: import('@playwright/test').Page,
    respond: (sheetName: string | null) => { status: number; body: unknown },
  ) {
    const sentSheetNames: (string | null)[] = [];
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      (route) => {
        const sheetName = readSheetNameField(route.request());
        sentSheetNames.push(sheetName);
        const { status, body } = respond(sheetName);
        return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
      },
    );
    return sentSheetNames;
  }

  /** 다중 시트 기본 라우트 — 지정 없음/「데이터」는 id·name, 「숨김시트」는 id·name·memo 컬럼 */
  function multiSheetResponder(sheetName: string | null) {
    if (sheetName === '숨김시트') {
      return { status: 200, body: createExcelPreviewResponse('숨김시트', MULTI_SHEETS, ['id', 'name', 'memo']) };
    }
    return { status: 200, body: createExcelPreviewResponse('데이터', MULTI_SHEETS) };
  }

  /** 엑셀 파일을 올려 2단계까지 진입한다 (백엔드는 모킹이므로 내용은 아무 바이트나 된다) */
  async function uploadExcel(page: import('@playwright/test').Page) {
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview')),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles({
        name: 'multi.xlsx',
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer: Buffer.from('fake xlsx content'),
      }),
    ]);
  }

  test('시트가 여럿이면 「가져올 시트」 선택 상자에 서버가 고른 시트가 선택되고 숨김 시트는 (숨김)으로 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupImportMocks(page);
    const sent = await routeExcelPreview(page, multiSheetResponder);
    await uploadExcel(page);

    // 처음 올릴 때는 시트를 지정하지 않는다 — 서버 기본(첫 번째 보이는 시트)에 맡긴다
    expect(sent).toEqual([null]);

    const sheetSelect = page.getByRole('dialog').getByRole('combobox', { name: '가져올 시트' });
    await expect(sheetSelect).toBeVisible();
    await expect(sheetSelect).toHaveText('데이터');

    // 목록(포털로 렌더링)은 문서 순서대로, 숨김 시트에 「(숨김)」 꼬리표
    await sheetSelect.click();
    await expect(page.getByRole('option', { name: '숨김시트 (숨김)' })).toBeVisible();
    await expect(page.getByRole('option', { name: '데이터', exact: true })).toBeVisible();
  });

  test('다른 시트를 고르면 sheetName 필드로 미리보기를 다시 받고 매핑·샘플이 새 시트 내용으로 바뀐다', async ({
    authenticatedPage: page,
  }) => {
    await setupImportMocks(page);
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/validate`, createValidateResponse());
    const sent = await routeExcelPreview(page, multiSheetResponder);
    await uploadExcel(page);

    const dialog = page.getByRole('dialog');
    // 첫 시트(「데이터」)에는 memo 컬럼이 없다
    await expect(dialog.getByRole('combobox', { name: 'id 컬럼을 매핑할 데이터셋 컬럼' })).toBeVisible();
    await expect(dialog.getByRole('combobox', { name: 'memo 컬럼을 매핑할 데이터셋 컬럼' })).toHaveCount(0);

    // 검증 결과는 시트를 바꾸면 지워져야 한다 — 먼저 검증해 결과를 띄워 둔다
    await dialog.getByRole('button', { name: '검증' }).click();
    await expect(dialog.getByText(/샘플 2행 검사 결과/)).toBeVisible();

    const sheetSelect = dialog.getByRole('combobox', { name: '가져올 시트' });
    await sheetSelect.click();
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview')),
      page.getByRole('option', { name: '숨김시트 (숨김)' }).click(),
    ]);

    // 같은 파일로 다시 미리보기를 요청하며 멀티파트 필드 sheetName=숨김시트 를 보낸다
    expect(sent).toEqual([null, '숨김시트']);
    // 2단계에 머문 채 선택값·매핑 표·샘플이 두 번째 응답으로 바뀐다
    await expect(sheetSelect).toHaveText('숨김시트 (숨김)');
    await expect(dialog.getByRole('combobox', { name: 'memo 컬럼을 매핑할 데이터셋 컬럼' })).toBeVisible();
    await expect(dialog.getByText('숨김시트-memo')).toBeVisible();
    // 이전 시트 기준 검증 결과는 사라진다
    await expect(dialog.getByText(/샘플 2행 검사 결과/)).toHaveCount(0);
  });

  test('시트 재미리보기가 실패하면 서버 문구를 보이고 이전 시트가 선택된 채 남는다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    const message = '시트를 읽을 수 없습니다: 숨김시트';
    await routeExcelPreview(page, (sheetName) =>
      sheetName === '숨김시트'
        ? { status: 400, body: { status: 400, error: 'Bad Request', message, errors: null } }
        : multiSheetResponder(sheetName),
    );
    await uploadExcel(page);

    const dialog = page.getByRole('dialog');
    const sheetSelect = dialog.getByRole('combobox', { name: '가져올 시트' });
    await sheetSelect.click();
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 400),
      page.getByRole('option', { name: '숨김시트 (숨김)' }).click(),
    ]);

    await expect(page.getByText(message)).toBeVisible({ timeout: 5000 });
    // 이전 시트·매핑 그대로, 2단계 유지
    await expect(sheetSelect).toHaveText('데이터');
    await expect(dialog.getByRole('combobox', { name: 'memo 컬럼을 매핑할 데이터셋 컬럼' })).toHaveCount(0);
    await expect(dialog.getByRole('button', { name: '임포트' })).toBeVisible();
  });

  test('검증·임포트 요청에 마지막으로 고른 시트가 sheetName 필드로 실린다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await routeExcelPreview(page, multiSheetResponder);
    // 검증·임포트 요청의 sheetName 필드를 기록한다
    const validateSheetNames: (string | null)[] = [];
    const importSheetNames: (string | null)[] = [];
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/imports/validate`,
      (route) => {
        validateSheetNames.push(readSheetNameField(route.request()));
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createValidateResponse()) });
      },
    );
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/imports`,
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        importSheetNames.push(readSheetNameField(route.request()));
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createImportStartResponse()) });
      },
    );
    await uploadExcel(page);

    const dialog = page.getByRole('dialog');
    await dialog.getByRole('combobox', { name: '가져올 시트' }).click();
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview')),
      page.getByRole('option', { name: '숨김시트 (숨김)' }).click(),
    ]);
    await expect(dialog.getByRole('combobox', { name: '가져올 시트' })).toHaveText('숨김시트 (숨김)');

    await dialog.getByRole('button', { name: '검증' }).click();
    await expect(dialog.getByText(/샘플 2행 검사 결과/)).toBeVisible();
    expect(validateSheetNames).toEqual(['숨김시트']);

    const importBtn = dialog.getByRole('button', { name: '임포트' });
    await expect(importBtn).toBeEnabled();
    await importBtn.click();
    await expect(dialog.getByText('임포트 완료')).toBeVisible({ timeout: 10_000 });
    expect(importSheetNames).toEqual(['숨김시트']);
  });

  test('시트를 바꿔 미리보기를 다시 받는 동안 검증·임포트 버튼이 비활성화된다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    // 「숨김시트」 미리보기 응답을 테스트가 풀어 줄 때까지 붙잡아 둔다 — 재미리보기 진행 중 상태를 관찰하기 위해서다
    let releaseHidden: () => void = () => {};
    const hiddenGate = new Promise<void>((resolve) => {
      releaseHidden = resolve;
    });
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/imports/preview`,
      async (route) => {
        const { status, body } = multiSheetResponder(readSheetNameField(route.request()));
        if (readSheetNameField(route.request()) === '숨김시트') await hiddenGate;
        return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
      },
    );
    await uploadExcel(page);

    const dialog = page.getByRole('dialog');
    const validateBtn = dialog.getByRole('button', { name: '검증' });
    const importBtn = dialog.getByRole('button', { name: '임포트' });
    await expect(validateBtn).toBeEnabled();
    await expect(importBtn).toBeEnabled();

    await dialog.getByRole('combobox', { name: '가져올 시트' }).click();
    await page.getByRole('option', { name: '숨김시트 (숨김)' }).click();

    // 재미리보기 중: 바뀌기 전 시트·매핑으로 검증·임포트하지 못하게 막는다
    await expect(validateBtn).toBeDisabled();
    await expect(importBtn).toBeDisabled();

    releaseHidden();
    await expect(dialog.getByRole('combobox', { name: '가져올 시트' })).toHaveText('숨김시트 (숨김)');
    await expect(validateBtn).toBeEnabled();
    await expect(importBtn).toBeEnabled();
  });

  test('검증 중에는 시트 선택 상자를 잠가 이전 시트의 검증 결과가 새 시트 화면에 섞이지 않게 한다', async ({
    authenticatedPage: page,
  }) => {
    await setupImportMocks(page);
    await routeExcelPreview(page, multiSheetResponder);
    // 검증 응답을 붙잡아 두어 「검증 중」 상태를 유지한다
    let releaseValidate: () => void = () => {};
    const validateGate = new Promise<void>((resolve) => {
      releaseValidate = resolve;
    });
    let validateCalls = 0;
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/imports/validate`,
      async (route) => {
        validateCalls++;
        await validateGate;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createValidateResponse()) });
      },
    );
    await uploadExcel(page);

    const dialog = page.getByRole('dialog');
    const selector = dialog.getByRole('combobox', { name: '가져올 시트' });
    await expect(selector).toBeEnabled();
    await dialog.getByRole('button', { name: '검증' }).click();
    await expect.poll(() => validateCalls).toBe(1);

    // 검증 중: 시트를 바꿀 수 없다
    await expect(selector).toBeDisabled();

    // 검증이 끝나면 다시 고를 수 있고, 결과는 그대로 「데이터」 시트 화면에 보인다
    await Promise.all([page.waitForResponse((r) => r.url().includes('/imports/validate')), releaseValidate()]);
    await expect(selector).toBeEnabled();
    await expect(selector).toHaveText('데이터');
    await expect(dialog.getByText(/샘플 2행 검사 결과/)).toBeVisible();
  });

  test('시트가 하나면 선택 상자 없이 「가져올 시트: Sheet1」 한 줄만 보인다', async ({ authenticatedPage: page }) => {
    await setupImportMocks(page);
    await routeExcelPreview(page, () => ({
      status: 200,
      body: createExcelPreviewResponse('Sheet1', [{ name: 'Sheet1', hidden: false }]),
    }));
    await uploadExcel(page);

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('가져올 시트: Sheet1')).toBeVisible();
    await expect(dialog.getByRole('combobox', { name: '가져올 시트' })).toHaveCount(0);
  });

  test('CSV(시트 정보 없음)는 시트 표시가 없고 검증·임포트 요청에 sheetName 필드가 없다', async ({
    authenticatedPage: page,
  }) => {
    await setupImportMocks(page);
    await mockApi(page, 'POST', `/api/v1/datasets/${DATASET_ID}/imports/preview`, {
      ...createPreviewResponse(),
      sheetName: null,
      sheets: null,
    });
    const importSheetNames: (string | null)[] = [];
    let importBody = '';
    await page.route(
      (url) => url.pathname === `/api/v1/datasets/${DATASET_ID}/imports`,
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        importBody = route.request().postDataBuffer()?.toString('utf-8') ?? '';
        importSheetNames.push(readSheetNameField(route.request()));
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createImportStartResponse()) });
      },
    );

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '임포트' }).first().click();
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/imports/preview') && r.status() === 200),
      page.getByRole('dialog').locator('input[type="file"]').setInputFiles(CSV_FILE),
    ]);

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: '컬럼 매핑' })).toBeVisible();
    await expect(dialog.getByText(/가져올 시트/)).toHaveCount(0);

    await dialog.getByRole('button', { name: '임포트' }).click();
    await expect(dialog.getByText('임포트 완료')).toBeVisible({ timeout: 10_000 });
    expect(importSheetNames).toEqual([null]);
    expect(importBody).not.toContain('name="sheetName"');
  });
});
