import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * DatasetDataTab(데이터 탭) E2E 테스트
 * - 데이터 그리드 렌더링, 검색, 정렬, 행 선택 등 전체 상호작용 커버.
 * - DatasetDataTab.tsx / DataTableToolbar / SelectionActionBar 라인 커버리지 증가가 목표.
 */
test.describe('데이터셋 상세 — 데이터 탭', () => {
  /** 데이터셋 상세 응답 — 숫자/텍스트/불리언 컬럼 혼합으로 구성 */
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
        columnOrder: 1,
      }),
      createColumn({
        id: 3,
        columnName: 'amount',
        displayName: '금액',
        dataType: 'INTEGER',
        isPrimaryKey: false,
        columnOrder: 2,
      }),
    ],
  });

  /**
   * 데이터 탭 공통 모킹 — infinite 쿼리 + 통계 + 카테고리 + 태그.
   */
  async function setupDataTabMocks(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/datasets/1', datasetDetail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    // 통계 응답 — 비어있어도 데이터 탭 렌더링 자체는 가능
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', [
      {
        columnName: 'name',
        dataType: 'TEXT',
        totalCount: 3,
        nullCount: 0,
        nullPercent: 0,
        distinctCount: 3,
        minValue: null,
        maxValue: null,
        avgValue: null,
        topValues: [
          { value: 'Alice', count: 1 },
          { value: 'Bob', count: 1 },
          { value: 'Carol', count: 1 },
        ],
        sampled: false,
      },
      {
        columnName: 'amount',
        dataType: 'INTEGER',
        totalCount: 3,
        nullCount: 0,
        nullPercent: 0,
        distinctCount: 3,
        minValue: '10',
        maxValue: '30',
        avgValue: 20,
        topValues: [
          { value: '10', count: 1 },
          { value: '20', count: 1 },
          { value: '30', count: 1 },
        ],
        sampled: false,
      },
    ]);
  }

  test('데이터 탭 전환 시 행이 렌더링되고 행 수가 헤더에 표시된다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await setupDataTabMocks(page);

    // /data 엔드포인트는 infinite query로 호출됨 — page=0만 모킹해도 totalPages=1이면 추가 호출 없음
    let dataCallCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) => {
        dataCallCount += 1;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: datasetDetail.columns,
            rows: [
              { _id: 101, id: 1, name: 'Alice', amount: 10 },
              { _id: 102, id: 2, name: 'Bob', amount: 20 },
              { _id: 103, id: 3, name: 'Carol', amount: 30 },
            ],
            page: 0,
            size: 50,
            totalElements: 3,
            totalPages: 1,
          }),
        });
      },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // "데이터" 탭 클릭
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByRole('tab', { name: '데이터' })).toHaveAttribute('data-state', 'active');

    // 데이터 탭 헤더: "데이터 (3행)"
    await expect(page.getByRole('heading', { name: /데이터 \(3행\)/ })).toBeVisible();

    // 데이터 검색 입력, SQL 버튼, 행 추가 버튼 등 툴바 엘리먼트 표시
    await expect(page.getByPlaceholder('데이터 검색...')).toBeVisible();
    await expect(page.getByRole('button', { name: /SQL/ })).toBeVisible();
    await expect(page.getByRole('button', { name: '행 추가' })).toBeVisible();
    await expect(page.getByRole('button', { name: '임포트' })).toBeVisible();
    await expect(page.getByRole('button', { name: '내보내기' })).toBeVisible();

    // 행 렌더링 확인 — Alice 셀이 화면에 표시되어야 함
    await expect(page.getByText('Alice')).toBeVisible();
    await expect(page.getByText('Bob')).toBeVisible();
    await expect(page.getByText('Carol')).toBeVisible();

    // 최소 1회는 /data API 호출
    expect(dataCallCount).toBeGreaterThanOrEqual(1);
  });

  test('데이터 검색 입력 시 /data 에 search 파라미터가 전달된다', async ({
    authenticatedPage: page,
  }) => {
    await setupDataTabMocks(page);

    const searchCalls: string[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) => {
        const url = new URL(route.request().url());
        const search = url.searchParams.get('search') ?? '';
        searchCalls.push(search);
        const rows =
          search === 'Alice'
            ? [{ _id: 101, id: 1, name: 'Alice', amount: 10 }]
            : [
                { _id: 101, id: 1, name: 'Alice', amount: 10 },
                { _id: 102, id: 2, name: 'Bob', amount: 20 },
                { _id: 103, id: 3, name: 'Carol', amount: 30 },
              ];
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: datasetDetail.columns,
            rows,
            page: 0,
            size: 50,
            totalElements: rows.length,
            totalPages: 1,
          }),
        });
      },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByText('Alice')).toBeVisible();

    // 검색어 입력 — 300ms 디바운스 후 API 호출
    await page.getByPlaceholder('데이터 검색...').fill('Alice');

    // 디바운스 이후 검색 결과로 좁혀진다
    await expect(page.getByText('Bob')).not.toBeVisible();
    await expect(page.getByText('Alice')).toBeVisible();

    // 최소 1번은 search=Alice 로 호출되어야 함
    expect(searchCalls.some((s) => s === 'Alice')).toBe(true);
  });

  test('빈 데이터일 때 빈 상태 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await setupDataTabMocks(page);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: datasetDetail.columns,
            rows: [],
            page: 0,
            size: 50,
            totalElements: 0,
            totalPages: 0,
          }),
        }),
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
    await page.getByRole('tab', { name: '데이터' }).click();

    // rowCount=3 이므로 기존 heading "데이터 (3행)"이 아닌 totalElements=0 기반으로 렌더링
    await expect(page.getByText('데이터가 없습니다.')).toBeVisible();
  });

  test('컬럼 헤더 미니 차트 클릭 시 ColumnStats Popover 가 열린다', async ({
    authenticatedPage: page,
  }) => {
    await setupDataTabMocks(page);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: datasetDetail.columns,
            rows: [
              { _id: 101, id: 1, name: 'Alice', amount: 10 },
              { _id: 102, id: 2, name: 'Bob', amount: 20 },
              { _id: 103, id: 3, name: 'Carol', amount: 30 },
            ],
            page: 0,
            size: 50,
            totalElements: 3,
            totalPages: 1,
          }),
        }),
    );

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByText('Alice')).toBeVisible();

    // 컬럼 헤더의 ColumnMiniChart 는 th 내부 svg(title="클릭하여 통계 보기")를 통해 클릭 가능.
    // Playwright 의 getByTitle 로 첫 미니차트(name 컬럼 TEXT)를 찾아 클릭한다.
    await page.getByTitle('클릭하여 통계 보기').first().click();

    // Popover 내용: 컬럼명 + Top values 라벨이 렌더링된다
    await expect(page.getByText('Top values')).toBeVisible();
    // Top value 중 Alice/Bob/Carol 중 최소 하나가 렌더링됨
    await expect(
      page.getByRole('dialog').getByText(/Alice|Bob|Carol/).first(),
    ).toBeVisible();

    // Escape 키로 닫기
    await page.keyboard.press('Escape');
    await expect(page.getByText('Top values')).not.toBeVisible();
  });

  test('SQL 버튼 토글 시 SqlQueryEditor가 표시된다', async ({ authenticatedPage: page }) => {
    await setupDataTabMocks(page);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: datasetDetail.columns,
            rows: [{ _id: 101, id: 1, name: 'Alice', amount: 10 }],
            page: 0,
            size: 50,
            totalElements: 1,
            totalPages: 1,
          }),
        }),
    );

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByText('Alice')).toBeVisible();

    // SQL 버튼 클릭 → SqlQueryEditor 토글
    await page.getByRole('button', { name: /SQL/ }).click();
    // SqlQueryEditor 내부에는 쿼리 입력 area / 실행 버튼이 들어있음 — "쿼리 실행" 버튼 기준으로 확인
    await expect(page.getByRole('button', { name: /실행|Run/ }).first()).toBeVisible();
  });

  test('행 선택 후 SelectionActionBar 의 삭제 버튼 클릭 시 AlertDialog 가 열린다', async ({
    authenticatedPage: page,
  }) => {
    await setupDataTabMocks(page);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) =>
        route.fulfill({
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
        }),
    );

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByText('Alice')).toBeVisible();

    // 첫 번째 행의 체크박스 선택 (aria-label="행 1 선택")
    await page.getByRole('checkbox', { name: '행 1 선택' }).click();

    // SelectionActionBar — "N개 행 선택됨" 텍스트와 함께 "삭제" 버튼이 표시된다
    await expect(page.getByText(/개 행 선택됨/)).toBeVisible({ timeout: 5000 });
    const deleteBtn = page.getByText(/개 행 선택됨/).locator('..').getByRole('button', { name: '삭제' });
    await expect(deleteBtn).toBeVisible();

    // 삭제 버튼 클릭 → AlertDialog 열림
    await deleteBtn.click();
    await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('행 삭제 확인')).toBeVisible();
  });

  test('컬럼 헤더 정렬 버튼 클릭 시 sortBy 파라미터가 API 에 전달된다', async ({
    authenticatedPage: page,
  }) => {
    await setupDataTabMocks(page);

    const sortCalls: { sortBy: string | null; sortDir: string | null }[] = [];

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) => {
        const url = new URL(route.request().url());
        sortCalls.push({
          sortBy: url.searchParams.get('sortBy'),
          sortDir: url.searchParams.get('sortDir'),
        });
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

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByText('Alice')).toBeVisible();

    // 컬럼 헤더 버튼(name 컬럼 정렬) 클릭 — th 안의 button
    // "이름" 컬럼 헤더 버튼을 클릭한다 (displayName='이름')
    await page.getByRole('button', { name: /이름/ }).first().click();

    // sortBy=name 으로 API 가 재호출되어야 한다
    await page.waitForTimeout(300);
    const sortedCall = sortCalls.find((c) => c.sortBy === 'name');
    expect(sortedCall).toBeDefined();
    expect(sortedCall?.sortDir).toBe('ASC');
  });

  /**
   * 회귀 테스트(#82): 검색 필터 변경 시 선택된 행이 초기화되어야 한다.
   * - 보이지 않는 행이 선택 상태로 남아 있으면 "삭제" 클릭 시 의도치 않은 행까지 삭제될 수 있다.
   * - 안전한 기본 동작: debouncedSearch 변경 시 selectedRowIds 를 비운다.
   */
  test('검색 필터를 변경하면 이전에 선택된 행이 자동으로 해제된다 (refs #82)', async ({
    authenticatedPage: page,
  }) => {
    await setupDataTabMocks(page);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) => {
        const reqUrl = new URL(route.request().url());
        const search = reqUrl.searchParams.get('search') ?? '';
        const allRowsData = [
          { _id: 101, id: 1, name: 'Alice', amount: 10 },
          { _id: 102, id: 2, name: 'Bob', amount: 20 },
          { _id: 103, id: 3, name: 'Carol', amount: 30 },
        ];
        const rows = search
          ? allRowsData.filter((r) => r.name.includes(search))
          : allRowsData;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: datasetDetail.columns,
            rows,
            page: 0,
            size: 50,
            totalElements: rows.length,
            totalPages: 1,
          }),
        });
      },
    );

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByText('Alice')).toBeVisible();

    // 1) 행 1, 2 선택 → 액션바에 "2개 행 선택됨" 표시
    await page.getByRole('checkbox', { name: '행 1 선택' }).click();
    await page.getByRole('checkbox', { name: '행 2 선택' }).click();
    await expect(page.getByText('2개 행 선택됨')).toBeVisible({ timeout: 5000 });

    // 2) 검색어 입력 → debounce(300ms) 후 결과가 1행으로 줄어듦
    await page.getByPlaceholder('데이터 검색...').fill('Carol');
    await expect(page.getByText('Carol')).toBeVisible();
    await expect(page.getByText('Alice')).not.toBeVisible();

    // 3) 핵심 검증: 선택된 행이 모두 초기화되어 액션바가 사라져야 한다.
    //    (이전 동작: "2개 행 선택됨"이 그대로 유지되어 보이지 않는 행 삭제 위험)
    await expect(page.getByText(/개 행 선택됨/)).not.toBeVisible();

    // 4) 모든 체크박스가 unchecked 상태인지 확인
    const carolCheckbox = page.getByRole('checkbox', { name: '행 1 선택' });
    await expect(carolCheckbox).not.toBeChecked();
  });

  /**
   * 회귀 테스트: null DB 값이 'NULL' 텍스트 대신 dash('-')로 렌더링되어야 한다 (refs #13)
   */
  test('null 값은 NULL 텍스트가 아닌 dash(-)로 표시된다', async ({ authenticatedPage: page }) => {
    await setupDataTabMocks(page);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            columns: datasetDetail.columns,
            rows: [
              // null 값이 포함된 행 — amount가 null인 경우
              { _id: 101, id: 1, name: 'Alice', amount: null },
              // name이 null인 경우
              { _id: 102, id: 2, name: null, amount: 20 },
            ],
            page: 0,
            size: 50,
            totalElements: 2,
            totalPages: 1,
          }),
        }),
    );

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();

    // null 값이 있는 행 렌더링 대기
    await expect(page.getByText('Alice')).toBeVisible();

    // null 값 셀에 'NULL' 텍스트가 없어야 한다 (회귀 검증)
    await expect(page.getByText('NULL')).not.toBeVisible();

    // null 값은 dash('-')로 표시되어야 한다
    const dashCells = page.locator('span.italic.text-xs').filter({ hasText: '-' });
    await expect(dashCells.first()).toBeVisible();
  });
  // (#770) DATE/TIMESTAMP 컬럼에 JS Date 가 해석 못 하는 값(±infinity, BC, 연도 10000 등)이 한 행만
  // 있어도 formatCellValue 가 RangeError 를 던져 데이터 탭 전체가 PageErrorBoundary 에러 화면이 됐다.
  // 지금 API 가 주는 문자열과 #769 이후 PG 원문 문자열을 모두 넣어, 탭이 살아 있고 원문이 그대로
  // 보이며 정상 행은 여전히 포맷되고 SQL 편집기로 고칠 수 있는지 검증한다.
  test('±infinity·BC 등 파싱 불가 날짜가 있어도 탭이 렌더되고 원문이 표시된다 (#770)', async ({
    authenticatedPage: page,
  }) => {
    await setupDataTabMocks(page);
    const dateDetail = createDatasetDetail({
      id: 1,
      rowCount: 4,
      columns: [
        createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
        createColumn({ id: 2, columnName: 'label', displayName: '라벨', dataType: 'TEXT', columnOrder: 1 }),
        createColumn({ id: 3, columnName: 'ts', displayName: '시각', dataType: 'TIMESTAMP', columnOrder: 2 }),
        createColumn({ id: 4, columnName: 'd', displayName: '일자', dataType: 'DATE', columnOrder: 3 }),
      ],
    });
    // 나중에 등록한 라우트가 우선 — 날짜 컬럼이 있는 상세로 덮어쓴다
    await mockApi(page, 'GET', '/api/v1/datasets/1', dateDetail);

    const rows = [
      { _id: 101, id: 1, label: 'normal', ts: '2024-05-01T01:00:00.000+00:00', d: '2024-05-01' },
      // 현재 API(#769 미수정) 응답 형태
      { _id: 102, id: 2, label: 'cur-neg-inf', ts: '-292269054-12-02T23:00:00.000+00:00', d: '0000-01-01' },
      { _id: 103, id: 3, label: 'cur-bc', ts: '-0043-03-15T01:00:00.000+00:00', d: '2024-02-30' },
      // #769 이후 예상되는 PG 원문 형태
      { _id: 104, id: 4, label: 'pg-raw', ts: '0044-03-15 10:00:00 BC', d: 'infinity' },
    ];
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1/data',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ columns: dateDetail.columns, rows, page: 0, size: 50, totalElements: 4, totalPages: 1 }),
        }),
    );

    await page.goto('/data/datasets/1');
    await page.getByRole('tab', { name: '데이터' }).click();

    // 탭 전체 에러 화면이 아니어야 한다
    await expect(page.getByRole('heading', { name: /데이터 \(4행\)/ })).toBeVisible();
    await expect(page.getByRole('heading', { name: '페이지를 불러오는 중 문제가 발생했습니다' })).toHaveCount(0);

    // 파싱 불가 값은 원문 그대로 표시
    const table = page.locator('table');
    for (const raw of [
      '-292269054-12-02T23:00:00.000+00:00',
      '0000-01-01',
      '-0043-03-15T01:00:00.000+00:00',
      '2024-02-30',
      '0044-03-15 10:00:00 BC',
      'infinity',
    ]) {
      await expect(table.getByRole('cell', { name: raw, exact: true })).toBeVisible();
    }

    // 정상 행은 기존처럼 로케일 포맷(원문 ISO 문자열이 아님)
    const normalRow = table.getByRole('row').filter({ hasText: 'normal' });
    await expect(normalRow).toContainText('2024. 5. 1.');
    await expect(normalRow).not.toContainText('2024-05-01');

    // SQL 편집기로 문제 행을 고칠 수 있어야 한다
    await page.getByRole('button', { name: /SQL/ }).click();
    await expect(page.getByRole('button', { name: /실행|Run/ }).first()).toBeVisible();
  });
});
