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
      // (#674) nullable DECIMAL 컬럼 — 빈 값 저장 시 NULL 대신 0으로 저장되는 회귀 검증용
      createColumn({
        id: 4,
        columnName: 'price',
        displayName: '가격',
        dataType: 'DECIMAL',
        isPrimaryKey: false,
        isNullable: true,
        columnOrder: 3,
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
              { id: 1, name: 'Alice', amount: 10, price: 12.5 },
              { id: 2, name: 'Bob', amount: 20, price: 20.75 },
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

  test('행 추가 다이얼로그에서 입력 후 POST /data/rows 호출된다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    const addCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/data/rows',
      {
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

    // 다이얼로그 스크롤 영역은 overscroll-contain이어야 한다 — 없으면 다이얼로그 끝까지 스크롤했을 때
    // 뒤 페이지로 스크롤이 전파된다(scroll chaining). 앱 전체에서 이 속성 사용처가 0건이었다.
    const overscroll = await page
      .getByRole('dialog')
      .evaluate((el) => getComputedStyle(el).overscrollBehaviorY);
    expect(overscroll).toBe('contain');

    // (#673) PK(id) 컬럼도 자동생성 옵션이 없으므로 입력 필드로 노출되고, "(기본 키)" 뱃지가 표시된다.
    const pkLabel = page.locator('label[for="add-id"]');
    await expect(pkLabel).toContainText('기본 키');
    await page.locator('#add-id').fill('3');

    // 필드 입력 (add- prefix, editable columns = name, amount)
    await page.locator('#add-name').fill('Carol');
    await page.locator('#add-amount').fill('30');

    // 제출 (다이얼로그 내 "추가" 버튼)
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    // POST payload 검증 — { data: { id: '3', name: 'Carol', amount: '30' } }
    const captured = await addCapture.waitForRequest();
    const payload = captured.payload as { data: Record<string, unknown> };
    expect(payload.data).toMatchObject({
      id: '3', // (#673) PK도 사용자가 입력한 값이 그대로 페이로드에 포함되어야 한다
      name: 'Carol',
      amount: '30', // (WD-98) INTEGER 는 입력 문자열 그대로 보낸다(JS 숫자는 2^53 초과 정수를 깎는다)
    });
  });

  test('행 더블클릭 → EditRowDialog 표시 및 PUT /data/rows/101 호출된다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/1/data/rows/1',
      {
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

    // EditRowDialog — "행 편집 (ID: 1)" 제목 확인
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /행 편집 \(ID: 1\)/ })).toBeVisible();

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
      amount: '99',
    });
  });

  test('(#674) nullable INTEGER/DECIMAL 필드를 비우고 저장하면 0이 아닌 NULL로 전송된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/1/data/rows/1',
      { id: 1, name: 'Alice', amount: null, price: null },
      { capture: true },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    const aliceCell = page.getByRole('cell', { name: 'Alice' });
    await expect(aliceCell).toBeVisible();
    await aliceCell.dblclick();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /행 편집 \(ID: 1\)/ })).toBeVisible();

    // 기본값 확인 — amount(INTEGER)=10, price(DECIMAL)=12.5
    await expect(page.locator('#edit-amount')).toHaveValue('10');
    await expect(page.locator('#edit-price')).toHaveValue('12.5');

    // 두 필드를 전부 비운다 (커서를 끝으로 이동 후 Backspace 반복 — 실제 사용자 조작 재현)
    await page.locator('#edit-amount').fill('');
    await page.locator('#edit-price').fill('');

    await dialog.getByRole('button', { name: '저장' }).click();

    // PUT payload 검증 — 0이 아니라 null 이어야 한다
    const captured = await updateCapture.waitForRequest();
    const payload = captured.payload as { data: Record<string, unknown> };
    expect(payload.data.amount).toBeNull();
    expect(payload.data.price).toBeNull();
    // 회귀 방지: falsy-0 함정에 빠지면 0 이 전송되므로 명시적으로 0이 아님을 재확인
    expect(payload.data.amount).not.toBe(0);
    expect(payload.data.price).not.toBe(0);

    // 성공 토스트까지 확인 — 저장 자체는 정상 완료되어야 한다(값만 올바르게 NULL)
    await expect(page.getByText('행이 수정되었습니다.')).toBeVisible();
  });

  test('(#674) NOT NULL INTEGER/DECIMAL 컬럼은 비운 채 저장 시도하면 여전히 유효성 에러가 뜬다', async ({
    authenticatedPage: page,
  }) => {
    // nullable 스키마 수정(z.preprocess)이 NOT NULL 숫자 컬럼의 필수 검증까지 깨뜨리지 않는지
    // 확인하는 회귀 테스트 — NOT NULL 컬럼은 preprocess 분기를 타지 않으므로 원래 z.coerce.number()가
    // 그대로 적용되어 빈 문자열은 여전히 검증 실패해야 한다.
    const notNullDataset = createDatasetDetail({
      id: 8,
      rowCount: 1,
      columns: [
        createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
        createColumn({
          id: 2,
          columnName: 'score',
          displayName: '점수',
          dataType: 'INTEGER',
          isPrimaryKey: false,
          isNullable: false,
          columnOrder: 1,
        }),
        createColumn({
          id: 3,
          columnName: 'ratio',
          displayName: '비율',
          dataType: 'DECIMAL',
          isPrimaryKey: false,
          isNullable: false,
          columnOrder: 2,
        }),
      ],
    });

    await mockApi(page, 'GET', '/api/v1/datasets/8', notNullDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/8/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/8/stats', []);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/8/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: notNullDataset.columns,
            rows: [{ id: 1, score: 90, ratio: 1.5 }],
            page: 0,
            size: 50,
            totalElements: 1,
            totalPages: 1,
          }),
        }),
    );

    // PUT이 실제로 호출되는지까지 캡처해 명시적으로 확인한다 (다이얼로그가 닫히지 않는 것만으로는
    // "요청이 아예 안 갔다"를 증명하지 못한다 — 성공 응답 후 UI 갱신 실패로 우연히 열려있을 수도 있음)
    let putCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/8/data/rows/1' && url.search === '',
      (route) => {
        if (route.request().method() === 'PUT') putCalled = true;
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      },
    );

    await page.goto('/data/datasets/8');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    const scoreCell = page.getByRole('cell', { name: '90' });
    await expect(scoreCell).toBeVisible();
    await scoreCell.dblclick();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /행 편집 \(ID: 1\)/ })).toBeVisible();

    // NOT NULL INTEGER/DECIMAL 필드를 비우고 저장 시도
    await page.locator('#edit-score').fill('');
    await page.locator('#edit-ratio').fill('');
    await dialog.getByRole('button', { name: '저장' }).click();

    // 유효성 에러로 제출이 막혀 다이얼로그가 여전히 열려 있어야 한다 (0으로 조용히 저장되면 안 됨)
    await expect(dialog).toBeVisible();
    // 잠깐 대기해도 PUT 요청 자체가 나가지 않아야 한다 — falsy-0 함정이면 score/ratio가 0으로
    // "유효한" 값이 되어 여기서 PUT이 호출돼 버린다.
    await page.waitForTimeout(300);
    expect(putCalled).toBe(false);
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

  test('전체 선택 체크박스 클릭 시 모든 행이 선택된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByText('Alice')).toBeVisible();

    // 헤더 전체 선택 체크박스 (aria-label="전체 선택")
    const selectAllCheckbox = page.getByRole('checkbox', { name: '전체 선택' });
    await expect(selectAllCheckbox).toBeVisible();
    await selectAllCheckbox.click();

    // 모든 행 체크박스가 선택된다 — SelectionActionBar 가 표시됨 ("N개 행 선택됨" 텍스트 등장)
    await expect(page.getByText(/개 행 선택됨/)).toBeVisible({ timeout: 5000 });
  });

  test('BOOLEAN 컬럼(NULL 허용)이 있는 행 추가 — tri-state Select로 값이 전송된다', async ({
    authenticatedPage: page,
  }) => {
    // BOOLEAN 컬럼 포함 데이터셋
    const boolDataset = createDatasetDetail({
      id: 2,
      rowCount: 0,
      columns: [
        createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
        createColumn({
          id: 2,
          columnName: 'is_active',
          displayName: '활성화',
          dataType: 'BOOLEAN',
          isPrimaryKey: false,
          isNullable: true,
          columnOrder: 1,
        }),
        createColumn({
          id: 3,
          columnName: 'label',
          displayName: '라벨',
          dataType: 'TEXT',
          isPrimaryKey: false,
          isNullable: false,
          columnOrder: 2,
        }),
      ],
    });

    await mockApi(page, 'GET', '/api/v1/datasets/2', boolDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/2/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/2/stats', []);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/2/data',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ columns: boolDataset.columns, rows: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
      }),
    );

    const addCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/2/data/rows',
      { id: 1, is_active: true, label: '테스트' },
      { capture: true },
    );

    await page.goto('/data/datasets/2');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    await page.getByRole('button', { name: /행 추가/ }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '행 추가' })).toBeVisible();

    // (#673) PK(id) 컬럼도 입력 필드로 노출되므로 값 채움
    await page.locator('#add-id').fill('1');

    // BOOLEAN 컬럼(NULL 허용) — tri-state Select 렌더링 확인
    const boolSelect = page.locator('#add-is_active');
    await expect(boolSelect).toBeVisible();
    // 기본값은 "(비어있음)" = NULL — "예"를 선택하면 true로 변경
    await expect(boolSelect).toHaveText('(비어있음)');
    await boolSelect.click();
    await page.getByRole('option', { name: '예' }).click();

    // TEXT 컬럼 입력
    await page.locator('#add-label').fill('테스트');

    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    // payload에 is_active=true 포함 확인
    const captured = await addCapture.waitForRequest();
    const payload = captured.payload as { data: Record<string, unknown> };
    expect(payload.data.is_active).toBe(true);
    expect(payload.data.label).toBe('테스트');
  });

  test('BOOLEAN 컬럼(NULL 허용) 행 추가 시 값을 선택하지 않으면 NULL로 전송된다', async ({
    authenticatedPage: page,
  }) => {
    // (#670) 행 추가 다이얼로그는 NULL 허용 BOOLEAN 컬럼을 애초에 NULL로 초기화해야 하며,
    // 사용자가 아무것도 선택하지 않고 제출해도 NULL이 그대로 전송되어야 한다.
    const boolDataset = createDatasetDetail({
      id: 4,
      rowCount: 0,
      columns: [
        createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
        createColumn({
          id: 2,
          columnName: 'is_active',
          displayName: '활성화',
          dataType: 'BOOLEAN',
          isPrimaryKey: false,
          isNullable: true,
          columnOrder: 1,
        }),
        createColumn({
          id: 3,
          columnName: 'label',
          displayName: '라벨',
          dataType: 'TEXT',
          isPrimaryKey: false,
          isNullable: false,
          columnOrder: 2,
        }),
      ],
    });

    await mockApi(page, 'GET', '/api/v1/datasets/4', boolDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/4/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/4/stats', []);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/4/data',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ columns: boolDataset.columns, rows: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
      }),
    );

    const addCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/4/data/rows',
      { id: 1, is_active: null, label: '테스트' },
      { capture: true },
    );

    await page.goto('/data/datasets/4');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    await page.getByRole('button', { name: /행 추가/ }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '행 추가' })).toBeVisible();

    // (#673) PK(id) 컬럼도 입력 필드로 노출되므로 값 채움
    await page.locator('#add-id').fill('1');

    // BOOLEAN(NULL 허용) 기본값은 "(비어있음)" — 건드리지 않고 제출
    await expect(page.locator('#add-is_active')).toHaveText('(비어있음)');
    await page.locator('#add-label').fill('테스트');
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    const captured = await addCapture.waitForRequest();
    const payload = captured.payload as { data: Record<string, unknown> };
    expect(payload.data.is_active).toBeNull();
  });

  test('NULL인 NULL 허용 BOOLEAN 행을 편집 다이얼로그에서 그대로 저장하면 NULL이 유지된다', async ({
    authenticatedPage: page,
  }) => {
    // (#670) 회귀 재현 테스트 — 편집 다이얼로그를 열고 아무것도 바꾸지 않고 저장해도
    // NULL이 false로 손상되면 안 된다.
    const boolDataset = createDatasetDetail({
      id: 5,
      rowCount: 1,
      columns: [
        createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
        createColumn({
          id: 2,
          columnName: 'is_active',
          displayName: '활성화',
          dataType: 'BOOLEAN',
          isPrimaryKey: false,
          isNullable: true,
          columnOrder: 1,
        }),
        createColumn({
          id: 3,
          columnName: 'label',
          displayName: '라벨',
          dataType: 'TEXT',
          isPrimaryKey: false,
          isNullable: false,
          columnOrder: 2,
        }),
      ],
    });

    await mockApi(page, 'GET', '/api/v1/datasets/5', boolDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/5/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/5/stats', []);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/5/data',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          columns: boolDataset.columns,
          rows: [{ id: 1, is_active: null, label: '기존값' }],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        }),
      }),
    );

    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/5/data/rows/1',
      { id: 1, is_active: null, label: '기존값' },
      { capture: true },
    );

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    const labelCell = page.getByRole('cell', { name: '기존값' });
    await expect(labelCell).toBeVisible();
    await labelCell.dblclick();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /행 편집 \(ID: 1\)/ })).toBeVisible();

    // 기존 값이 NULL이었으므로 "(비어있음)"으로 표시되어야 한다 — false로 왜곡되면 안 됨
    await expect(page.locator('#edit-is_active')).toHaveText('(비어있음)');

    // 아무것도 바꾸지 않고 저장
    await dialog.getByRole('button', { name: '저장' }).click();

    // (#772) 바뀐 칸만 전송한다 — 아무것도 바꾸지 않았으므로 is_active 는 페이로드에 없고(서버가 기존
    // NULL 을 유지), 무엇보다 false 로 왜곡된 값이 전송되지 않아야 한다.
    const captured = await updateCapture.waitForRequest();
    const payload = captured.payload as { data: Record<string, unknown> };
    expect(payload.data).toEqual({});
  });

  test('NOT NULL BOOLEAN 컬럼은 기존과 동일하게 이진 Switch로 동작한다', async ({
    authenticatedPage: page,
  }) => {
    // (#670) NOT NULL 컬럼은 NULL 상태 자체가 불가능하므로 회귀 없이 기존 Switch UI를 유지해야 한다.
    const boolDataset = createDatasetDetail({
      id: 6,
      rowCount: 0,
      columns: [
        createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
        createColumn({
          id: 2,
          columnName: 'is_active',
          displayName: '활성화',
          dataType: 'BOOLEAN',
          isPrimaryKey: false,
          isNullable: false,
          columnOrder: 1,
        }),
        createColumn({
          id: 3,
          columnName: 'label',
          displayName: '라벨',
          dataType: 'TEXT',
          isPrimaryKey: false,
          isNullable: false,
          columnOrder: 2,
        }),
      ],
    });

    await mockApi(page, 'GET', '/api/v1/datasets/6', boolDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/6/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/6/stats', []);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/6/data',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ columns: boolDataset.columns, rows: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
      }),
    );

    const addCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/6/data/rows',
      { id: 1, is_active: true, label: '테스트' },
      { capture: true },
    );

    await page.goto('/data/datasets/6');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    await page.getByRole('button', { name: /행 추가/ }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '행 추가' })).toBeVisible();

    // (#673) PK(id) 컬럼도 입력 필드로 노출되므로 값 채움
    await page.locator('#add-id').fill('1');

    // NOT NULL BOOLEAN — Switch 렌더링(Select 아님), 기본값 false
    const boolSwitch = page.locator('#add-is_active');
    await expect(boolSwitch).toBeVisible();
    await expect(boolSwitch).toHaveAttribute('role', 'switch');
    await boolSwitch.click();

    await page.locator('#add-label').fill('테스트');
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    const captured = await addCapture.waitForRequest();
    const payload = captured.payload as { data: Record<string, unknown> };
    expect(payload.data.is_active).toBe(true);
  });

  test('GEOMETRY 컬럼에 잘못된 값 입력 → 클라이언트 GeoJSON 유효성 에러 표시', async ({
    authenticatedPage: page,
  }) => {
    // GEOMETRY 컬럼 포함 데이터셋 (nullable — 선택 입력)
    const geoDataset = createDatasetDetail({
      id: 3,
      rowCount: 0,
      columns: [
        createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
        createColumn({
          id: 2,
          columnName: 'label',
          displayName: '이름',
          dataType: 'TEXT',
          isPrimaryKey: false,
          isNullable: false,
          columnOrder: 1,
        }),
        createColumn({
          id: 3,
          columnName: 'geom',
          displayName: '위치 (지오메트리)',
          dataType: 'GEOMETRY',
          isPrimaryKey: false,
          isNullable: true,
          columnOrder: 2,
        }),
      ],
    });

    await mockApi(page, 'GET', '/api/v1/datasets/3', geoDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/3/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/3/stats', []);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/3/data',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ columns: geoDataset.columns, rows: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
      }),
    );

    await page.goto('/data/datasets/3');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    await page.getByRole('button', { name: /행 추가/ }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '행 추가' })).toBeVisible();

    // (#673) PK(id) 컬럼도 입력 필드로 노출되므로 값 채움
    await page.locator('#add-id').fill('1');

    // 필수 필드 입력
    await page.locator('#add-label').fill('테스트');

    // GEOMETRY 컬럼에 잘못된 값 입력 (GeoJSON 아님)
    await page.locator('#add-geom').fill('잘못된값');

    // 제출 시도
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    // 클라이언트 측 Zod 에러 표시 확인 — 서버로 보내지 않고 다이얼로그가 유지됨
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('GeoJSON 형식으로 입력하세요')).toBeVisible();
  });

  test('GEOMETRY 컬럼에 유효한 GeoJSON 입력 → 정상 제출된다', async ({
    authenticatedPage: page,
  }) => {
    const geoDataset = createDatasetDetail({
      id: 3,
      rowCount: 0,
      columns: [
        createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
        createColumn({
          id: 2,
          columnName: 'label',
          displayName: '이름',
          dataType: 'TEXT',
          isPrimaryKey: false,
          isNullable: false,
          columnOrder: 1,
        }),
        createColumn({
          id: 3,
          columnName: 'geom',
          displayName: '위치 (지오메트리)',
          dataType: 'GEOMETRY',
          isPrimaryKey: false,
          isNullable: true,
          columnOrder: 2,
        }),
      ],
    });

    await mockApi(page, 'GET', '/api/v1/datasets/3', geoDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/3/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/3/stats', []);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/3/data',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ columns: geoDataset.columns, rows: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
      }),
    );

    const addCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/3/data/rows',
      { id: 1, label: '테스트', geom: '{"type":"Point","coordinates":[126.97,37.56]}' },
      { capture: true },
    );

    await page.goto('/data/datasets/3');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    await page.getByRole('button', { name: /행 추가/ }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '행 추가' })).toBeVisible();

    // (#673) PK(id) 컬럼도 입력 필드로 노출되므로 값 채움
    await page.locator('#add-id').fill('1');

    await page.locator('#add-label').fill('테스트');

    // 유효한 GeoJSON Point 입력
    await page.locator('#add-geom').fill('{"type":"Point","coordinates":[126.97,37.56]}');

    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    // 정상 제출 확인 — API payload에 geom 포함
    const captured = await addCapture.waitForRequest();
    const payload = captured.payload as { data: Record<string, unknown> };
    expect(payload.data).toMatchObject({
      id: '1',
      label: '테스트',
      geom: '{"type":"Point","coordinates":[126.97,37.56]}',
    });
  });

  test('사용자 정의 PK(NOT NULL) 컬럼이 있는 데이터셋 — 편집 시 PK는 읽기 전용으로 표시되고 값이 유지된 채 저장된다', async ({
    authenticatedPage: page,
  }) => {
    // (#672) 회귀 재현 테스트 — 이전에는 EditRowDialog가 isPrimaryKey 컬럼을 폼에서 완전히
    // 제외해 PUT 페이로드에 값이 아예 안 담겼고, 백엔드가 NOT NULL 검증에 실패해 400을 반환했다.
    // 이제는 PK 컬럼이 읽기 전용 입력으로 표시되고, 값이 폼 상태/페이로드에 그대로 유지되어야 한다.
    const pkDataset = createDatasetDetail({
      id: 7,
      rowCount: 1,
      columns: [
        createColumn({
          id: 1,
          columnName: 'row_key',
          displayName: '행 키',
          dataType: 'INTEGER',
          isPrimaryKey: true,
          isNullable: false,
          columnOrder: 0,
        }),
        createColumn({
          id: 2,
          columnName: 'label',
          displayName: '라벨',
          dataType: 'TEXT',
          isPrimaryKey: false,
          isNullable: false,
          columnOrder: 1,
        }),
      ],
    });

    await mockApi(page, 'GET', '/api/v1/datasets/7', pkDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/7/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/7/stats', []);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/7/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: pkDataset.columns,
            rows: [{ id: 1, row_key: 42, label: '기존라벨' }],
            page: 0,
            size: 50,
            totalElements: 1,
            totalPages: 1,
          }),
        }),
    );

    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/7/data/rows/1',
      { id: 1, row_key: 42, label: '새라벨' },
      { capture: true },
    );

    await page.goto('/data/datasets/7');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    const labelCell = page.getByRole('cell', { name: '기존라벨' });
    await expect(labelCell).toBeVisible();
    await labelCell.dblclick();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /행 편집 \(ID: 1\)/ })).toBeVisible();

    // PK 컬럼은 읽기 전용 입력으로 표시되고 기존 값(42)을 그대로 보여준다
    const pkInput = page.locator('#edit-pk-row_key');
    await expect(pkInput).toBeVisible();
    await expect(pkInput).toHaveValue('42');
    await expect(pkInput).toBeDisabled();
    // 일반 편집 필드(label)와 달리 PK 컬럼용 입력 id(#edit-row_key)는 렌더링되지 않는다
    await expect(page.locator('#edit-row_key')).toHaveCount(0);

    // 일반 컬럼(label)만 수정하고 저장 — PK는 건드리지 않음
    await page.locator('#edit-label').fill('새라벨');
    await dialog.getByRole('button', { name: '저장' }).click();

    // (#772) 바뀐 칸(label)만 전송한다 — PK(row_key)는 페이로드에 없고 서버 부분 업데이트(#672)가
    // 기존 값을 유지한다.
    const captured = await updateCapture.waitForRequest();
    const payload = captured.payload as { data: Record<string, unknown> };
    expect(payload.data).toEqual({ label: '새라벨' });
  });

  test('사용자 정의 PK(NOT NULL) 컬럼이 있는 데이터셋 — 행 추가 시 PK 입력 필드가 노출되고 값이 페이로드에 포함된다', async ({
    authenticatedPage: page,
  }) => {
    // (#673) 회귀 재현 테스트 — 이전에는 AddRowDialog/RowFormFields/row-form-utils가 PK 컬럼을
    // 무조건 폼에서 제외해, auto-increment 옵션이 없는 이 앱에서는 PK가 있는 테이블에 UI로
    // 행을 단 하나도 추가할 수 없었다("Column '...' cannot be null" 서버 400만 반복 노출).
    // 이제는 PK를 다른 필수 컬럼과 동일하게 입력 필드로 노출하고, 사용자가 입력한 값이
    // POST 페이로드에 포함되어야 한다.
    const pkDataset = createDatasetDetail({
      id: 8,
      rowCount: 0,
      columns: [
        createColumn({
          id: 1,
          columnName: 'row_key',
          displayName: '행 키',
          dataType: 'INTEGER',
          isPrimaryKey: true,
          isNullable: false,
          columnOrder: 0,
        }),
        createColumn({
          id: 2,
          columnName: 'label',
          displayName: '라벨',
          dataType: 'TEXT',
          isPrimaryKey: false,
          isNullable: false,
          columnOrder: 1,
        }),
      ],
    });

    await mockApi(page, 'GET', '/api/v1/datasets/8', pkDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/8/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/8/stats', []);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/8/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ columns: pkDataset.columns, rows: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
        }),
    );

    const addCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/8/data/rows',
      { id: 1, row_key: 99, label: '새행' },
      { capture: true },
    );

    await page.goto('/data/datasets/8');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();

    await page.getByRole('button', { name: /행 추가/ }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '행 추가' })).toBeVisible();

    // PK 컬럼(row_key)이 활성화된 입력 필드로 노출되고 "(기본 키)" 뱃지가 표시된다
    const pkInput = page.locator('#add-row_key');
    await expect(pkInput).toBeVisible();
    await expect(pkInput).toBeEnabled();
    const pkLabel = page.locator('label[for="add-row_key"]');
    await expect(pkLabel).toContainText('기본 키');

    // PK 값을 포함해 정상 입력 후 제출
    await page.locator('#add-label').fill('새행');
    await pkInput.fill('99');
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    const captured = await addCapture.waitForRequest();
    const payload = captured.payload as { data: Record<string, unknown> };
    expect(payload.data.row_key).toBe('99');
    expect(payload.data.label).toBe('새행');
  });

  test('EditRowDialog — X 버튼 클릭 시 다이얼로그가 닫힌다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByText('Alice')).toBeVisible();

    // 첫 번째 행(Alice) 더블클릭 → EditRowDialog 오픈
    const aliceCell = page.getByRole('cell', { name: 'Alice' });
    await aliceCell.dblclick();

    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('dialog').getByRole('heading', { name: /행 편집/ })).toBeVisible();

    // Escape 키로 다이얼로그 닫기 (EditRowDialog 에는 취소 버튼이 없으므로 Escape 사용)
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 3000 });
  });
});

/**
 * (#772) 행 편집 다이얼로그 — DATE/TIMESTAMP 기존 값 보존 회귀 테스트.
 *
 * 수정 전 결함:
 * 1. 데이터 탭 API 는 TIMESTAMP 를 오프셋 붙은 ISO(`…+00:00`)로 주는데 폼이 그 문자열을 그대로
 *    datetime-local 입력에 넣어 빈칸으로 보이고, 손대지 않으면 그 문자열이 그대로 PUT 되어 서버가 400.
 * 2. 빈칸으로 보이는 날짜 칸을 클릭/Tab 으로 지나가기만 해도 DOM 값 '' 이 폼에 들어가 NULL 로 저장.
 * 3. date 입력이 담을 수 없는 값(`infinity`, BC 등)도 같은 경로로 NULL 로 덮였다.
 * 수정 후: 바뀐 칸만 PUT 하고, 표현 불가 값은 원문 텍스트 입력으로 보여준다.
 * 브라우저 시간대에 따라 datetime-local 값이 달라지므로 시간대를 고정한다.
 */
test.describe('데이터셋 상세 — 행 편집 날짜/시각 값 보존 (#772)', () => {
  test.use({ timezoneId: 'Asia/Seoul' });

  const temporalDataset = createDatasetDetail({
    id: 9,
    rowCount: 3,
    columns: [
      createColumn({ id: 1, columnName: 'label', displayName: 'label', dataType: 'TEXT', isPrimaryKey: false, isNullable: true, columnOrder: 0 }),
      createColumn({ id: 2, columnName: 'd', displayName: 'd', dataType: 'DATE', isPrimaryKey: false, isNullable: true, columnOrder: 1 }),
      createColumn({ id: 3, columnName: 'ts', displayName: 'ts', dataType: 'TIMESTAMP', isPrimaryKey: false, isNullable: true, columnOrder: 2 }),
    ],
  });

  // 실제 데이터 탭 API 응답 모양 — 정상 TIMESTAMP 는 UTC ISO(오프셋 포함), 범위 밖 값은 PG 원문(#769)
  const rows = [
    { id: 1, label: 'n002', d: '2020-01-03', ts: '2020-01-03T01:15:30.500+00:00' },
    { id: 2, label: 'x', d: 'infinity', ts: null },
    { id: 3, label: 'bc', d: '0044-03-15 BC', ts: '0044-03-15 10:00:00 BC' },
  ];

  async function openEditDialog(page: import('@playwright/test').Page, rowId: number, cellText: string) {
    await mockApi(page, 'GET', '/api/v1/datasets/9', temporalDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/9/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/9/stats', []);
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/9/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ columns: temporalDataset.columns, rows, page: 0, size: 50, totalElements: 3, totalPages: 1 }),
        }),
    );
    const capture = await mockApi(page, 'PUT', `/api/v1/datasets/9/data/rows/${rowId}`, {}, { capture: true });

    await page.goto('/data/datasets/9');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('cell', { name: cellText, exact: true }).dblclick();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: new RegExp(`행 편집 \\(ID: ${rowId}\\)`) })).toBeVisible();
    return { dialog, capture };
  }

  test('정상 TIMESTAMP 행 — 기존 시각이 입력란에 담기고, label 만 바꿔 저장하면 날짜·시각 칸은 전송되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const { dialog, capture } = await openEditDialog(page, 1, 'n002');

    // UTC 01:15:30.5 = 서울 10:15:30.5 — 데이터 탭 셀과 같은 벽시계 시각(브라우저가 .500 → .5 로 정규화)
    await expect(page.locator('#edit-ts')).toHaveValue(/^2020-01-03T10:15:30\.50*$/);
    await expect(page.locator('#edit-d')).toHaveValue('2020-01-03');

    await page.locator('#edit-label').fill('n002_e');
    await dialog.getByRole('button', { name: '저장' }).click();

    const payload = (await capture.waitForRequest()).payload as { data: Record<string, unknown> };
    expect(payload.data).toEqual({ label: 'n002_e' });
    await expect(page.getByText('행이 수정되었습니다.')).toBeVisible();
  });

  test('날짜·시각 칸을 클릭 후 Tab 으로 지나가기만 하면 값이 바뀌지 않고 전송되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const { dialog, capture } = await openEditDialog(page, 1, 'n002');

    await page.locator('#edit-d').click();
    await page.keyboard.press('Tab');
    await page.locator('#edit-ts').click();
    await page.keyboard.press('Tab');
    await page.locator('#edit-label').fill('n002_t');
    await dialog.getByRole('button', { name: '저장' }).click();

    const payload = (await capture.waitForRequest()).payload as { data: Record<string, unknown> };
    expect(payload.data).toEqual({ label: 'n002_t' });
  });

  test('TIMESTAMP 를 직접 바꾸면 오프셋 없는 로컬 날짜시각(서버 LocalDateTime 형식)으로 전송된다', async ({
    authenticatedPage: page,
  }) => {
    const { dialog, capture } = await openEditDialog(page, 1, 'n002');

    await page.locator('#edit-ts').fill('2021-06-01T09:30');
    await dialog.getByRole('button', { name: '저장' }).click();

    const payload = (await capture.waitForRequest()).payload as { data: Record<string, unknown> };
    expect(Object.keys(payload.data)).toEqual(['ts']);
    expect(payload.data.ts).toMatch(/^2021-06-01T09:30(:00(\.0+)?)?$/);
  });

  test('date 입력이 담을 수 없는 값(infinity)은 원문 텍스트로 보이고, 지나가기만 하면 전송되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const { dialog, capture } = await openEditDialog(page, 2, 'x');

    const d = page.locator('#edit-d');
    await expect(d).toHaveAttribute('type', 'text');
    await expect(d).toHaveValue('infinity');

    await d.click();
    await page.keyboard.press('Tab');
    await page.locator('#edit-label').fill('x_e');
    await dialog.getByRole('button', { name: '저장' }).click();

    const payload = (await capture.waitForRequest()).payload as { data: Record<string, unknown> };
    expect(payload.data).toEqual({ label: 'x_e' });
  });

  test('BC 값 칸을 사용자가 명시적으로 지운 경우에만 NULL 로 전송된다(손대지 않은 BC 시각은 미전송)', async ({
    authenticatedPage: page,
  }) => {
    const { dialog, capture } = await openEditDialog(page, 3, 'bc');

    await expect(page.locator('#edit-d')).toHaveValue('0044-03-15 BC');
    await expect(page.locator('#edit-ts')).toHaveValue('0044-03-15 10:00:00 BC');

    await page.locator('#edit-d').fill('');
    await dialog.getByRole('button', { name: '저장' }).click();

    const payload = (await capture.waitForRequest()).payload as { data: Record<string, unknown> };
    expect(payload.data).toEqual({ d: null });
  });
});

/**
 * (#774) 브라우저 시간대에 존재하지 않는 벽시계 시각(서울 서머타임 시작 공백 `1988-05-08 02:30`,
 * 표준시 전환 `1908-04-01 00:00`) 회귀 테스트.
 *
 * 수정 전 결함: API(#769)는 이런 값을 PG 원문 `1988-05-08 02:30:00` 으로 주는데, 데이터 탭 셀과 행 편집
 * 다이얼로그가 `new Date(str)`(브라우저 로컬)로 해석하면서 시각이 밀려 `오전 3:30`·`03:30` 으로 보였다.
 * 수정 후: 해석 결과가 원문 날짜·시각 성분과 다르면 셀은 원문을, 편집 폼은 원문 텍스트 입력을 쓴다.
 * 공백 여부는 브라우저 시간대에 달려 있어 시간대를 서울로 고정한다.
 */
test.describe('데이터셋 상세 — 시간대에 없는 벽시계 시각 표시 (#774)', () => {
  test.use({ timezoneId: 'Asia/Seoul' });

  const dstDataset = createDatasetDetail({
    id: 10,
    rowCount: 3,
    columns: [
      createColumn({ id: 1, columnName: 'label', displayName: 'label', dataType: 'TEXT', isPrimaryKey: false, isNullable: true, columnOrder: 0 }),
      createColumn({ id: 2, columnName: 'ts', displayName: 'ts', dataType: 'TIMESTAMP', isPrimaryKey: false, isNullable: true, columnOrder: 1 }),
    ],
  });

  // 실제 데이터 탭 API 응답 모양 — 정상 값은 UTC ISO, 다른 시각으로 바뀌는 값은 PG 원문(#769)
  const rows = [
    { id: 1, label: 'dst', ts: '1988-05-08 02:30:00' },
    { id: 2, label: 'std', ts: '1908-04-01 00:00:00' },
    { id: 3, label: 'normal', ts: '2024-01-02T01:15:00.000+00:00' },
  ];

  async function openDataTab(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/datasets/10', dstDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/10/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/10/stats', []);
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/10/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ columns: dstDataset.columns, rows, page: 0, size: 50, totalElements: 3, totalPages: 1 }),
        }),
    );
    await page.goto('/data/datasets/10');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();
  }

  test('DST 공백·표준시 전환 시각은 셀에 원문 그대로, 정상 값은 기존처럼 로컬 포맷으로 보인다', async ({
    authenticatedPage: page,
  }) => {
    await openDataTab(page);

    // 수정 전에는 '1988. 5. 8. 오전 3:30' / '1908. 4. 1. 오전 12:02' 로 보였다
    await expect(page.getByRole('row', { name: /dst/ }).getByRole('cell', { name: '1988-05-08 02:30:00', exact: true })).toBeVisible();
    await expect(page.getByRole('row', { name: /std/ }).getByRole('cell', { name: '1908-04-01 00:00:00', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: /오전 3:30/ })).toHaveCount(0);
    // 정상 값(UTC 01:15 = 서울 10:15)은 기존 포맷 그대로
    await expect(page.getByRole('row', { name: /normal/ }).getByRole('cell', { name: '2024. 1. 2. 오전 10:15', exact: true })).toBeVisible();
  });

  test('행 편집 다이얼로그는 DST 공백 시각을 원문 텍스트로 보이고, 손대지 않으면 전송하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await openDataTab(page);
    const capture = await mockApi(page, 'PUT', '/api/v1/datasets/10/data/rows/1', {}, { capture: true });

    await page.getByRole('cell', { name: 'dst', exact: true }).dblclick();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /행 편집 \(ID: 1\)/ })).toBeVisible();

    // 수정 전에는 datetime-local 입력에 '1988-05-08T03:30' 이 담겼다(다른 시각)
    const ts = page.locator('#edit-ts');
    await expect(ts).toHaveAttribute('type', 'text');
    await expect(ts).toHaveValue('1988-05-08 02:30:00');

    await ts.click();
    await page.keyboard.press('Tab');
    await page.locator('#edit-label').fill('dst_e');
    await dialog.getByRole('button', { name: '저장' }).click();

    const payload = (await capture.waitForRequest()).payload as { data: Record<string, unknown> };
    expect(payload.data).toEqual({ label: 'dst_e' });
  });
});

/**
 * (WD-98) 행 추가·편집의 물리 타입 한도 — DECIMAL=NUMERIC(18,6)(정수부 12자리·소수 6자리), VARCHAR 는 문자 수.
 *
 * 수정 전에는 정수부 13자리가 서버까지 가서 영문 「Data integrity violation」으로 실패했고, 소수 7자리는
 * 「행이 추가되었습니다.」 뒤 반올림된 다른 값(0.000000)이 저장됐다. 이제 입력 단계에서 안내하고 요청을 보내지 않는다.
 */
test.describe('데이터셋 상세 — 행 추가/편집 소수 자릿수·글자 수 한도 (WD-98)', () => {
  const limitDataset = createDatasetDetail({
    id: 7,
    rowCount: 1,
    columns: [
      createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
      createColumn({
        id: 2,
        columnName: 'price',
        displayName: '가격',
        dataType: 'DECIMAL',
        isPrimaryKey: false,
        isNullable: true,
        columnOrder: 1,
      }),
      createColumn({
        id: 3,
        columnName: 'code',
        displayName: '코드',
        dataType: 'VARCHAR',
        maxLength: 5,
        isPrimaryKey: false,
        isNullable: true,
        columnOrder: 2,
      }),
    ],
  });

  /**
   * 상세·데이터 조회를 모킹하고, 행 쓰기(POST/PUT) 요청을 모두 기록한다 — 막혀야 할 때 0건인지 확인하기 위함.
   * priceLiteral 은 데이터 응답 JSON 에 그대로 넣는 DECIMAL 리터럴 — 서버처럼 JSON 숫자로 보내야 브라우저의 double 변환이 재현된다.
   */
  async function setup(page: import('@playwright/test').Page, priceLiteral = '12.5') {
    await mockApi(page, 'GET', '/api/v1/datasets/7', limitDataset);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/7/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/7/stats', []);
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/7/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: limitDataset.columns,
            rows: [{ id: 1, price: '__PRICE__', code: 'abc' }],
            page: 0,
            size: 50,
            totalElements: 1,
            totalPages: 1,
          }).replace('"__PRICE__"', priceLiteral),
        }),
    );

    const writes: { method: string; body: { data: Record<string, unknown> } }[] = [];
    await page.route(
      (url) => url.pathname.startsWith('/api/v1/datasets/7/data/rows'),
      (route) => {
        const req = route.request();
        if (req.method() === 'GET') return route.fallback();
        writes.push({ method: req.method(), body: req.postDataJSON() });
        return route.fulfill({
          status: req.method() === 'POST' ? 201 : 200,
          contentType: 'application/json',
          body: JSON.stringify({ id: 2, data: {}, createdAt: null }),
        });
      },
    );

    await page.goto('/data/datasets/7');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '데이터' }).click();
    return writes;
  }

  async function openAddDialog(page: import('@playwright/test').Page) {
    await page.getByRole('button', { name: /행 추가/ }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '행 추가' })).toBeVisible();
    await page.locator('#add-id').fill('2');
  }

  for (const { value, message } of [
    { value: '12345678901234', message: '정수부는 최대 12자리까지 입력할 수 있습니다.' },
    { value: '0.0000001', message: '소수점 아래는 최대 6자리까지 입력할 수 있습니다.' },
  ]) {
    test(`소수 ${value} 입력 시 「${message}」 안내가 뜨고 요청을 보내지 않는다`, async ({
      authenticatedPage: page,
    }) => {
      const writes = await setup(page);
      await openAddDialog(page);

      await page.locator('#add-price').fill(value);
      await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

      await expect(page.getByRole('dialog').getByText(message)).toBeVisible();
      await expect(page.getByRole('dialog')).toBeVisible();
      await expect(page.getByText('행이 추가되었습니다.')).toHaveCount(0);
      expect(writes).toHaveLength(0);
    });
  }

  test('숫자는 double 로 깎이지 않고 입력 문자열 그대로, 이모지 5자(VARCHAR(5))도 그대로 전송된다', async ({
    authenticatedPage: page,
  }) => {
    const writes = await setup(page);
    await page.getByRole('button', { name: /행 추가/ }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '행 추가' })).toBeVisible();

    // JS 숫자로 보내면 9007199254740992 · 123456789012.12346 이 된다
    await page.locator('#add-id').fill('9007199254740993');
    await page.locator('#add-price').fill('123456789012.123456');
    await page.locator('#add-code').fill('😀😀😀😀😀');
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    await expect(page.getByText('행이 추가되었습니다.')).toBeVisible();
    expect(writes).toHaveLength(1);
    expect(writes[0].method).toBe('POST');
    expect(writes[0].body.data).toEqual({
      id: '9007199254740993',
      price: '123456789012.123456',
      code: '😀😀😀😀😀',
    });
  });

  test('저장값이 double 표시로 13자리처럼 보여도(999999999999.999999) 손대지 않으면 다른 칸 편집이 저장된다', async ({
    authenticatedPage: page,
  }) => {
    const writes = await setup(page, '999999999999.999999');

    await page.getByRole('cell', { name: 'abc' }).dblclick();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /행 편집 \(ID: 1\)/ })).toBeVisible();
    // 브라우저가 JSON 숫자를 double 로 읽어 정수부 13자리로 보인다 — 이 칸은 건드리지 않는다
    await expect(page.locator('#edit-price')).toHaveValue('1000000000000');

    await page.locator('#edit-code').fill('xyz');
    await dialog.getByRole('button', { name: '저장' }).click();

    await expect(page.getByText('행이 수정되었습니다.')).toBeVisible();
    expect(writes).toHaveLength(1);
    expect(writes[0].method).toBe('PUT');
    // 손대지 않은 price 는 전송하지 않는다(#772) — 서버 값이 그대로 남는다
    expect(writes[0].body.data).toEqual({ code: 'xyz' });
  });

  test('행 편집에서도 정수부 13자리는 안내하고 PUT 을 보내지 않는다', async ({ authenticatedPage: page }) => {
    const writes = await setup(page);

    await page.getByRole('cell', { name: 'abc' }).dblclick();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /행 편집 \(ID: 1\)/ })).toBeVisible();

    await page.locator('#edit-price').fill('1234567890123');
    await dialog.getByRole('button', { name: '저장' }).click();

    await expect(dialog.getByText('정수부는 최대 12자리까지 입력할 수 있습니다.')).toBeVisible();
    await expect(page.getByText('행이 수정되었습니다.')).toHaveCount(0);
    expect(writes).toHaveLength(0);
  });
});
