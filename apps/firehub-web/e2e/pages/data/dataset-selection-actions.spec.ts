import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 데이터 탭 — SelectionActionBar 비파괴 액션 (이슈 #83)
 *
 * 다중 선택 후 액션 바에 "선택 해제" / "선택 행 CSV 내보내기" 버튼이 노출되는지,
 * 클릭 시 정상 동작하는지(선택 초기화, CSV 다운로드 트리거) 검증한다.
 */
test.describe('데이터셋 데이터 탭 — 선택 액션 바', () => {
  const datasetDetail = createDatasetDetail({
    id: 1,
    rowCount: 3,
    columns: [
      createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
      createColumn({
        id: 2,
        columnName: 'name',
        displayName: '이름',
        dataType: 'TEXT',
        isPrimaryKey: false,
        isNullable: false,
        columnOrder: 1,
      }),
      createColumn({
        id: 3,
        columnName: 'amount',
        displayName: '금액',
        dataType: 'INTEGER',
        isPrimaryKey: false,
        isNullable: true,
        columnOrder: 2,
      }),
    ],
  });

  async function setupMocks(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/datasets/1', datasetDetail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) => {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: datasetDetail.columns,
            rows: [
              { id: 1, name: 'Alice', amount: 10 },
              { id: 2, name: 'Bob', amount: 20 },
              { id: 3, name: 'Carol, Jr.', amount: null },
            ],
            page: 0,
            size: 50,
            totalElements: 3,
            totalPages: 1,
          }),
        });
      },
    );
  }

  test('선택 후 비파괴 액션 버튼 노출 + 선택 해제 클릭 시 액션 바 사라짐', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/1?tab=data');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByText('Alice')).toBeVisible();

    // 전체 선택 → 액션 바 표시
    await page.getByRole('checkbox', { name: '전체 선택' }).click();
    await expect(page.getByText(/개 행 선택됨/)).toBeVisible({ timeout: 5000 });

    // 비파괴 액션 버튼 노출 검증
    const exportBtn = page.getByRole('button', { name: '선택 행 CSV 내보내기' });
    const clearBtn = page.getByRole('button', { name: '선택 해제' });
    await expect(exportBtn).toBeVisible();
    await expect(clearBtn).toBeVisible();
    // 파괴 액션도 함께 유지
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toBeVisible();

    // 선택 해제 클릭 → 액션 바 사라짐
    await clearBtn.click();
    await expect(page.getByText(/개 행 선택됨/)).not.toBeVisible({ timeout: 3000 });
  });

  test('선택 행 CSV 내보내기 클릭 시 다운로드 트리거 + 헤더와 데이터 행 포함', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/1?tab=data');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByText('Alice')).toBeVisible();

    // 전체 선택
    await page.getByRole('checkbox', { name: '전체 선택' }).click();
    await expect(page.getByText('3개 행 선택됨')).toBeVisible();

    // CSV 다운로드 트리거 (Playwright의 download 이벤트 캡처)
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: '선택 행 CSV 내보내기' }).click();
    const download = await downloadPromise;

    // 파일명: <dataset>_selected_<timestamp>.csv
    expect(download.suggestedFilename()).toMatch(/_selected_.*\.csv$/);

    // 다운로드 내용 검증 — 헤더 + 3개 행, 콤마 escape 확인
    const stream = await download.createReadStream();
    const chunks: Buffer[] = [];
    for await (const chunk of stream) chunks.push(chunk as Buffer);
    const csv = Buffer.concat(chunks).toString('utf-8');

    // 헤더(displayName 사용) + 첫 행
    expect(csv).toContain('ID,이름,금액');
    expect(csv).toContain('1,Alice,10');
    expect(csv).toContain('2,Bob,20');
    // 'Carol, Jr.' 는 콤마 포함 → 쌍따옴표로 감싸야 함
    expect(csv).toContain('"Carol, Jr."');
  });
});
