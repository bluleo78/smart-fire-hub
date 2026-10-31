import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * ExportDialog E2E 테스트
 *
 * 내보내기 다이얼로그의 성공/실패 흐름을 검증한다.
 * - 500 에러 시 에러 토스트 표시 (issue #8 회귀 방지)
 * - 성공 시 다이얼로그 닫힘
 */
test.describe('데이터셋 상세 — 내보내기 다이얼로그', () => {
  const datasetDetail = createDatasetDetail({
    id: 1,
    rowCount: 5,
    columns: [
      createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
      createColumn({ id: 2, columnName: 'name', displayName: '이름', dataType: 'TEXT', isPrimaryKey: false, columnOrder: 1 }),
    ],
  });

  const estimateResponse = {
    rowCount: 5,
    async: false,
    hasGeometryColumn: false,
    columns: [
      { columnName: 'id', displayName: 'ID', isGeometry: false },
      { columnName: 'name', displayName: '이름', isGeometry: false },
    ],
  };

  async function setupMocks(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/datasets/1', datasetDetail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/export/estimate', estimateResponse);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          columns: datasetDetail.columns,
          rows: [
            { id: 1, name: 'Alice' },
            { id: 2, name: 'Bob' },
          ],
          page: 0,
          size: 50,
          totalElements: 2,
          totalPages: 1,
        }),
      }),
    );
  }

  test('내보내기 500 에러 시 에러 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await setupMocks(page);

    // 내보내기 POST → 500 에러
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/export',
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ message: 'cannot execute INSERT in a read-only transaction' }),
          });
        }
        return route.continue();
      },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    // 내보내기 버튼 클릭
    await page.getByRole('button', { name: '내보내기' }).click();

    // 다이얼로그 열림 확인
    await expect(page.getByRole('dialog').getByRole('heading', { name: '데이터 내보내기' })).toBeVisible();

    // "내보내기" 실행
    await page.getByRole('dialog').getByRole('button', { name: '내보내기' }).click();

    // 에러 토스트 표시 확인 — 서버 에러 메시지 또는 폴백 메시지 중 하나가 표시된다
    await expect(
      page.getByText(/cannot execute INSERT|내보내기에 실패했습니다/)
    ).toBeVisible({ timeout: 5000 });

    // 다이얼로그는 여전히 열려있어야 한다
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  test('비동기 내보내기: 요청 후 다이얼로그가 닫히고 진행 중 토스트가 표시된다 (이슈 #171 회귀 방지)', async ({ authenticatedPage: page }) => {
    // issue #171 회귀 방지 — 비동기 내보내기 후 다이얼로그 닫힘 + 폴링 토스트 동작 검증
    // 이 테스트는 독립적으로 모든 라우트를 설정한다 (setupMocks와 conflict 방지)
    await mockApi(page, 'GET', '/api/v1/datasets/1', datasetDetail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);

    // async=true 인 estimate 응답 — 비동기 내보내기 경로를 유도
    await mockApi(page, 'GET', '/api/v1/datasets/1/export/estimate', {
      ...estimateResponse,
      async: true,
    });

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          columns: datasetDetail.columns,
          rows: [{ id: 1, name: 'Alice' }],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        }),
      }),
    );

    // 비동기 내보내기 POST → jobId 반환
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/export',
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({
            status: 202,
            contentType: 'application/json',
            body: JSON.stringify({ jobId: 'job-async-171' }),
          });
        }
        return route.continue();
      },
    );

    // 잡 상태 폴링: RUNNING → COMPLETED 순으로 응답
    let pollCallCount = 0;
    await page.route(
      (url) => url.pathname.includes('/api/v1/jobs/job-async-171'),
      (route) => {
        pollCallCount++;
        if (pollCallCount >= 2) {
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ stage: 'COMPLETED', progress: 100, message: '완료', metadata: { filename: 'test_export.csv' } }),
          });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ stage: 'RUNNING', progress: 50, message: '처리 중' }),
        });
      },
    );

    // COMPLETED 후 다운로드 파일 mock
    await page.route(
      (url) => url.pathname === '/api/v1/exports/job-async-171/file',
      (route) => route.fulfill({
        status: 200,
        contentType: 'text/csv',
        body: 'id,name\n1,Alice',
      }),
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    // 내보내기 다이얼로그 열기
    await page.getByRole('button', { name: '내보내기' }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '데이터 내보내기' })).toBeVisible();
    // 대용량 비동기 안내 표시 확인
    await expect(page.getByText(/비동기 내보내기/)).toBeVisible();

    const downloadPromise = page.waitForEvent('download', { timeout: 15000 });
    await page.getByRole('dialog').getByRole('button', { name: '내보내기' }).click();

    // 비동기 내보내기 후 다이얼로그가 닫혀야 한다
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 5000 });

    // 폴링 진행 중 로딩 토스트가 표시되어야 한다
    await expect(page.getByText(/내보내기 준비 중|내보내기 중/)).toBeVisible({ timeout: 8000 });

    // WD-157 회귀 방지: 다이얼로그가 닫혀 언마운트돼도 폴링이 이어져, 완료되면 서버가 정한 이름으로 파일이 내려받아진다.
    // (예전에는 언마운트 정리가 첫 폴링 전에 interval 을 지워 상태 확인 0건·다운로드 0건이었다)
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe('test_export.csv');
    expect(pollCallCount).toBeGreaterThanOrEqual(2);
    await expect(page.getByText('파일이 다운로드되었습니다.')).toBeVisible();
    await expect(page.getByText(/내보내기 준비 중|내보내기 중/)).toHaveCount(0);
  });

  test('비동기 내보내기: 서버 작업이 실패하면 진행 토스트가 실패 사유로 바뀐다 (WD-157)', async ({ authenticatedPage: page }) => {
    await setupMocks(page);
    // setupMocks 의 estimate 를 async=true 로 덮어쓴다(나중에 등록한 route 가 먼저 처리된다)
    await mockApi(page, 'GET', '/api/v1/datasets/1/export/estimate', { ...estimateResponse, async: true });
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/export',
      (route) =>
        route.request().method() === 'POST'
          ? route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ jobId: 'job-fail-157' }) })
          : route.continue(),
    );
    await page.route(
      (url) => url.pathname === '/api/v1/jobs/job-fail-157/status',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ stage: 'FAILED', progress: 0, errorMessage: '디스크 공간 부족' }),
        }),
    );

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '내보내기' }).click();
    await page.getByRole('dialog').getByRole('button', { name: '내보내기' }).click();
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 5000 });

    await expect(page.getByText('내보내기 실패: 디스크 공간 부족')).toBeVisible({ timeout: 8000 });
    await expect(page.getByText(/내보내기 준비 중/)).toHaveCount(0);
  });

  test('내보내기 성공 시 다이얼로그가 닫힌다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupMocks(page);

    // 내보내기 POST → 성공 (CSV blob)
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/export',
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({
            status: 200,
            contentType: 'text/csv',
            body: 'id,name\n1,Alice\n2,Bob',
          });
        }
        return route.continue();
      },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    await page.getByRole('button', { name: '내보내기' }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '데이터 내보내기' })).toBeVisible();

    await page.getByRole('dialog').getByRole('button', { name: '내보내기' }).click();

    // 성공 토스트 + 다이얼로그 닫힘
    await expect(page.getByText(/파일이 다운로드되었습니다/)).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 3000 });
  });
});

/**
 * 회귀 가드(WD-383): 동기 내보내기 파일 이름의 날짜가 toISOString(UTC)이라, 한국 시간 0~9시에 받으면 전날 날짜가 붙었다.
 * 브라우저 시간대를 Asia/Seoul, 시각을 KST 2026-10-29 03:15(UTC 10-28 18:15)로 고정해 현지 날짜가 붙는지 본다.
 */
test.describe('데이터셋 상세 — 내보내기 파일 이름 날짜 (WD-383)', () => {
  test.use({ timezoneId: 'Asia/Seoul' });

  test('한국 시간 새벽에 내보내도 파일 이름에 오늘(현지) 날짜가 붙는다', async ({ authenticatedPage: page }) => {
    // UTC 2026-10-28 18:15 = KST 10-29 03:15 (epoch ms — 서버 날짜 문자열이 아니다)
    await page.clock.setFixedTime(Date.UTC(2026, 9, 28, 18, 15));
    const detail = createDatasetDetail({
      id: 1,
      rowCount: 2,
      columns: [createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true })],
    });
    await mockApi(page, 'GET', '/api/v1/datasets/1', detail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/data', {
      columns: detail.columns, rows: [{ id: 1 }], page: 0, size: 50, totalElements: 1, totalPages: 1,
    });
    await mockApi(page, 'GET', '/api/v1/datasets/1/export/estimate', {
      rowCount: 1, async: false, hasGeometryColumn: false,
      columns: [{ columnName: 'id', displayName: 'ID', isGeometry: false }],
    });
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/export',
      (route) => route.fulfill({ status: 200, contentType: 'text/csv', body: 'id\n1' }),
    );

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: '내보내기' }).click();
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('dialog').getByRole('button', { name: '내보내기' }).click();
    const download = await downloadPromise;
    // 예전엔 UTC 날짜 20261028 이 붙었다
    expect(download.suggestedFilename()).toBe(`${detail.name}_export_20261029.csv`);
  });
});
