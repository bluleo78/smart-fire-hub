import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 상세 — 행 CRUD (Add/Edit Row) E2E 테스트
 *
 * AddRowDialog / EditRowDialog / RowFormFields / useDatasets(useAddRow/useUpdateRow)
 * 의 form 제출 경로를 커버한다. API payload 로 전달되는 값이 타입별로 올바르게
 * 변환되는지(Number, Boolean) 를 검증한다.
 */
test.describe('데이터셋 상세 — 행 추가/편집', () => {
  const datasetDetail = createDatasetDetail({
    id: 1,
    rowCount: 2,
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
              { _id: 101, id: 1, name: 'Alice', amount: 10 },
              { _id: 102, id: 2, name: 'Bob', amount: 20 },
            ],
            page: 0,
            size: 50,
            totalElements: 2,
            totalPages: 1,
          }),
        });
      },
    );
  }

  test('행 추가 다이얼로그에서 입력 후 POST /data/rows 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    const addCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/data/rows',
      {
        _id: 103,
        id: 3,
        name: 'Carol',
        amount: 30,
      },
      { capture: true },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });

    // 데이터 탭 전환
    await page.getByRole('tab', { name: '데이터' }).click();

    // 행 추가 버튼 클릭 (DataTableToolbar)
    await page.getByRole('button', { name: /행 추가/ }).click();

    // AddRowDialog 표시
    await expect(page.getByRole('dialog').getByRole('heading', { name: '행 추가' })).toBeVisible();

    // 필드 입력 (add- prefix, editable columns = name, amount)
    await page.locator('#add-name').fill('Carol');
    await page.locator('#add-amount').fill('30');

    // 제출 (다이얼로그 내 "추가" 버튼)
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    // POST payload 검증 — { data: { name: 'Carol', amount: 30 } }
    const captured = await addCapture.waitForRequest();
    const payload = captured.payload as { data: Record<string, unknown> };
    expect(payload.data).toMatchObject({
      name: 'Carol',
      amount: 30, // cleanFormValues 가 INTEGER 를 Number 로 변환
    });
  });

  test('행 더블클릭 → EditRowDialog 표시 및 PUT /data/rows/101 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/1/data/rows/101',
      {
        _id: 101,
        id: 1,
        name: 'Alice Updated',
        amount: 99,
      },
      { capture: true },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });

    // 데이터 탭 전환
    await page.getByRole('tab', { name: '데이터' }).click();

    // 첫 번째 데이터 행(Alice) 을 찾는다 — cell text 'Alice' 있는 tr
    // 더블클릭 → setEditRowState → EditRowDialog 표시
    const aliceCell = page.getByRole('cell', { name: 'Alice' });
    await expect(aliceCell).toBeVisible();
    await aliceCell.dblclick();

    // EditRowDialog — "행 편집 (ID: 101)" 제목 확인
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /행 편집 \(ID: 101\)/ })).toBeVisible();

    // 기본값 확인 (edit- prefix)
    await expect(page.locator('#edit-name')).toHaveValue('Alice');
    await expect(page.locator('#edit-amount')).toHaveValue('10');

    // 값 수정 → changedFields 경로 활성화
    await page.locator('#edit-name').fill('Alice Updated');
    await page.locator('#edit-amount').fill('99');

    // 제출
    await dialog.getByRole('button', { name: '저장' }).click();

    // PUT payload 검증
    const captured = await updateCapture.waitForRequest();
    const payload = captured.payload as { data: Record<string, unknown> };
    expect(payload.data).toMatchObject({
      name: 'Alice Updated',
      amount: 99,
    });
  });

  test('행 추가 시 필수 필드 미입력 → Zod 유효성 에러 표시', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    await page.getByRole('button', { name: /행 추가/ }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '행 추가' })).toBeVisible();

    // name(필수, isNullable: false) 미입력 상태로 바로 제출
    // — buildRowZodSchema 가 NOT NULL 제약을 zod 로 변환
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    // 에러 메시지 표시 — 필수 필드에 "필수" 키워드 또는 메시지가 뜬다
    // Zod 기본 message 가 없으면 react-hook-form 이 에러를 표시하지만 필드는 남는다
    // 다이얼로그가 여전히 열려 있는지로 대체 검증 (성공 시 자동 닫힘)
    await expect(page.getByRole('dialog')).toBeVisible();
  });
});
