import { createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 목록 — 호버 '내보내기' 버튼이 ExportDialog 를 연다 (#95) E2E 테스트.
 *
 * 이전에는 navigate 로 상세 페이지로만 이동했으나, 라벨/아이콘과 동작 일치를 위해
 * ExportDialog 를 직접 트리거하도록 수정.
 */
test.describe('데이터셋 목록 — 호버 내보내기 액션', () => {
  const datasets = [
    createDataset({ id: 1, name: '고객 데이터셋', storageType: 'TABLE', originType: 'SOURCE', status: 'CERTIFIED' }),
  ];

  async function setupCommon(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(datasets));
    // ExportDialog 가 estimate API 를 호출
    await mockApi(page, 'GET', '/api/v1/datasets/1/export/estimate', {
      rowCount: 100,
      async: false,
      hasGeometryColumn: false,
      columns: [
        { columnName: 'id', displayName: 'ID', isGeometry: false },
        { columnName: 'name', displayName: '이름', isGeometry: false },
      ],
    });
  }

  test('호버 내보내기 버튼 클릭 시 ExportDialog 가 열린다 (#95)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: /데이터셋/ })).toBeVisible();

    // 호버 액션의 '내보내기' 버튼 — opacity-0 group-hover 라 force click
    const exportBtn = page.getByRole('button', { name: '내보내기' }).first();
    await exportBtn.click({ force: true });

    // 상세 페이지로 navigate 하지 않고 목록 URL 유지
    await expect(page).toHaveURL(/\/data\/datasets($|\?)/);

    // ExportDialog 의 타이틀 노출
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('데이터 내보내기')).toBeVisible();
  });

  test('대용량(비동기) 내보내기는 목록에서 다이얼로그가 닫힌 뒤에도 완료되면 파일을 내려받는다 (WD-157)', async ({
    authenticatedPage: page,
  }) => {
    await setupCommon(page);
    // 5만 행 초과 — 비동기 경로(나중에 등록한 route 가 먼저 처리된다)
    await mockApi(page, 'GET', '/api/v1/datasets/1/export/estimate', {
      rowCount: 50010,
      async: true,
      hasGeometryColumn: false,
      columns: [
        { columnName: 'id', displayName: 'ID', isGeometry: false },
        { columnName: 'name', displayName: '이름', isGeometry: false },
      ],
    });
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/export',
      (route) =>
        route.request().method() === 'POST'
          ? route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ jobId: 'job-list-157' }) })
          : route.continue(),
    );
    let statusCalls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/jobs/job-list-157/status',
      (route) => {
        statusCalls++;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ stage: 'COMPLETED', progress: 100, metadata: { filename: '고객_데이터셋_export.csv' } }),
        });
      },
    );
    await page.route(
      (url) => url.pathname === '/api/v1/exports/job-list-157/file',
      (route) => route.fulfill({ status: 200, contentType: 'text/csv', body: 'id,name\n1,Alice' }),
    );

    await page.goto('/data/datasets');
    await page.getByRole('button', { name: '내보내기' }).first().click({ force: true });
    await expect(page.getByText(/비동기 내보내기/)).toBeVisible();

    const downloadPromise = page.waitForEvent('download', { timeout: 15000 });
    await page.getByRole('dialog').getByRole('button', { name: '내보내기' }).click();
    // 목록은 닫힐 때 exportDatasetId 를 비워 다이얼로그를 언마운트한다 — 그래도 추적은 이어져야 한다
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 5000 });

    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe('고객_데이터셋_export.csv');
    expect(statusCalls).toBe(1);
    await expect(page.getByText('파일이 다운로드되었습니다.')).toBeVisible();
  });
});
