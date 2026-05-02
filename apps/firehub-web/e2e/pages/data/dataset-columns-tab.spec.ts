import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 데이터셋 상세 — 컬럼 탭(필드 목록) E2E 테스트
 *
 * DatasetColumnsTab / ColumnStats(NullProgressBar/ColumnExpandedStats) /
 * useColumnManager / DescriptionCell / DataTypeBadge 의 렌더링 및 상호작용을
 * 커버한다. 확장 행, 필드 삭제 다이얼로그, 필드 추가 다이얼로그 오픈까지.
 */
test.describe('데이터셋 상세 — 컬럼 탭', () => {
  const datasetDetail = createDatasetDetail({
    id: 5,
    rowCount: 100,
    columns: [
      createColumn({
        id: 1,
        columnName: 'id',
        displayName: 'ID',
        dataType: 'INTEGER',
        isPrimaryKey: true,
        isNullable: false,
        columnOrder: 0,
      }),
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
    await mockApi(page, 'GET', '/api/v1/datasets/5', datasetDetail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/5/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    // 컬럼 탭이 필요로 하는 stats — hasData=true 일 때 호출
    await mockApi(page, 'GET', '/api/v1/datasets/5/stats', [
      {
        columnName: 'name',
        dataType: 'TEXT',
        totalCount: 100,
        nullCount: 5,
        nullPercent: 5,
        distinctCount: 95,
        minValue: null,
        maxValue: null,
        avgValue: null,
        topValues: [
          { value: 'Alice', count: 20 },
          { value: 'Bob', count: 15 },
        ],
        sampled: false,
      },
      {
        columnName: 'amount',
        dataType: 'INTEGER',
        totalCount: 100,
        nullCount: 0,
        nullPercent: 0,
        distinctCount: 50,
        minValue: '10',
        maxValue: '1000',
        avgValue: 305.5,
        topValues: [],
        sampled: false,
      },
    ]);
  }

  test('필드 목록이 렌더링되고 PK / 데이터 타입 / null 허용 여부가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });

    // "필드" 탭 전환
    await page.getByRole('tab', { name: '필드' }).click();

    // 컬럼 탭에서 필드 목록 헤더 확인
    await expect(page.getByRole('heading', { name: /필드 목록 \(3개\)/ })).toBeVisible();

    // PK 배지 — "PK" 텍스트
    await expect(page.getByText('PK', { exact: true })).toBeVisible();

    // null 허용/불허 텍스트
    await expect(page.getByText('불허').first()).toBeVisible();
    await expect(page.getByText('허용').first()).toBeVisible();

    // NullProgressBar 는 stats 가 있을 때 렌더 — "5%" 또는 "0%" 텍스트
    // Distinct 카운트 Badge — 95, 50
    await expect(page.getByText('95')).toBeVisible();
    await expect(page.getByText('50')).toBeVisible();
  });

  test('필드 추가 버튼 클릭 시 ColumnDialog(add) 가 열린다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    await page.getByRole('button', { name: '필드 추가' }).click();

    // ColumnDialog 가 열리면 어떤 dialog role 이 등장한다
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  test('행 확장 버튼 클릭 시 ColumnExpandedStats 가 렌더된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    // 첫 번째 expand-chevron 버튼 — 각 행의 expand 토글
    // name 컬럼 row 의 expand 버튼: 테이블 내 icon 버튼
    // PK 컬럼(id) 은 stats 가 없어 확장해도 렌더 안 됨 → name(2번째) 행의 버튼을 클릭
    // 간단히: stats 가 있는 "name" 을 포함한 row 의 chevron 을 클릭
    const rows = page.getByRole('row');
    // 2번째 행(헤더 제외 1번째 데이터)을 찾지 말고, 테이블 내 첫 번째 rowbox 의 ghost 버튼들 중 특정 row 의 것을 클릭
    // 더 간단: stats 있는 컬럼들의 확장 버튼이 enabled 이므로, "PK" 텍스트가 포함된 id 컬럼 row 의 버튼은 disabled 여도 가능
    // 여기서는 2번째 (name) 컬럼 확장
    const nameRow = rows.filter({ hasText: 'name' }).first();
    await nameRow.getByRole('button').first().click();

    // 확장 후 ColumnExpandedStats 내부의 topValues 나 nullPercent 등 렌더 확인
    // TextStats 의 경우 top values 표시 (Alice 등장 횟수 20)
    await expect(page.getByText('Alice').first()).toBeVisible();
  });

  test('숫자 컬럼 확장 시 최솟값/최댓값/평균값이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    // amount 컬럼(INTEGER, stats 있음) 행의 확장 버튼 클릭
    const rows = page.getByRole('row');
    const amountRow = rows.filter({ hasText: 'amount' }).first();
    await amountRow.getByRole('button').first().click();

    // ColumnExpandedStats — 숫자 컬럼의 최솟값/최댓값/평균값 카드 확인
    await expect(page.getByText('최솟값').first()).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('최댓값').first()).toBeVisible();
    await expect(page.getByText('평균값').first()).toBeVisible();

    // stats mock: minValue='10', maxValue='1000', avgValue=305.5
    await expect(page.getByText('10').first()).toBeVisible();
    await expect(page.getByText('1000').first()).toBeVisible();
    await expect(page.getByText('305.50').first()).toBeVisible();
  });

  test('NullProgressBar — nullPercent 가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    // name 컬럼 stats: nullPercent=5 → "5.0%" 텍스트가 NullProgressBar 에 표시된다
    await expect(page.getByText('5.0%')).toBeVisible({ timeout: 5000 });
    // amount 컬럼 stats: nullPercent=0 → "0.0%"
    await expect(page.getByText('0.0%').first()).toBeVisible();
  });

  test('필드 순서 위로 이동 버튼 클릭 시 reorder API 가 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    // 컬럼 순서 변경 API 캡처 모킹
    const reorderCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/5/columns/reorder',
      {},
      { capture: true },
    );

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    // "name" 행(index=1) 의 위로 이동 버튼(ChevronUp) — 행 내 두 번째 버튼
    // 버튼 순서: expand(0), ChevronUp(1), ChevronDown(2), Pencil(3), Trash2(4)
    const nameRow = page.getByRole('row').filter({ hasText: 'name' }).first();
    const upBtn = nameRow.getByRole('button').nth(1); // ChevronUp
    await upBtn.click();

    // reorder PUT 이 호출되는지 검증
    const req = await reorderCapture.waitForRequest();
    expect(req.url.pathname).toBe('/api/v1/datasets/5/columns/reorder');
  });

  test('필드 삭제 버튼 클릭 시 삭제 확인 다이얼로그가 열린다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    // "amount" 행(index=2, ChevronDown 비활성화)의 Trash2 버튼
    // 버튼 순서: expand(0), ChevronUp(1), ChevronDown(2), Pencil(3), Trash2(4)
    const amountRow = page.getByRole('row').filter({ hasText: 'amount' }).first();
    const deleteBtn = amountRow.getByRole('button').nth(4); // Trash2
    await deleteBtn.click();

    // 삭제 확인 AlertDialog 가 열린다 (role="alertdialog")
    await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('필드 삭제')).toBeVisible();
  });

  test('필드 추가 다이얼로그 — VARCHAR 타입 선택 시 최대 길이 입력 필드가 나타난다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    await page.getByRole('button', { name: '필드 추가' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 기본 타입(TEXT)에서는 최대 길이 필드가 없다
    await expect(page.getByLabel('최대 길이 *')).not.toBeVisible();

    // ColumnTypeSelect — combobox 클릭 후 VARCHAR 선택
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '문자열(크기지정)' }).click();

    // VARCHAR 선택 후 최대 길이 필드가 나타난다
    await expect(page.getByLabel('최대 길이 *')).toBeVisible();
  });

  test('필드 추가 — POST payload 검증 (필드명·타입·nullable)', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    // POST /api/v1/datasets/5/columns 캡처 모킹
    const addCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/5/columns',
      { id: 10, columnName: 'score', displayName: '점수', dataType: 'INTEGER',
        maxLength: null, isNullable: true, isIndexed: false, isPrimaryKey: false,
        description: null, columnOrder: 3 },
      { capture: true },
    );

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    await page.getByRole('button', { name: '필드 추가' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 필드명 입력
    await page.getByLabel('필드명 *').fill('score');
    // 표시명 입력
    await page.getByLabel('표시명').fill('점수');
    // 타입 변경 — INTEGER 선택
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '정수' }).click();

    // 추가 버튼 클릭
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    // POST payload 검증
    const req = await addCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      columnName: 'score',
      displayName: '점수',
      dataType: 'INTEGER',
    });
  });

  test('필드 추가 — 예약 컬럼명(id) 제출 시 에러 토스트가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    // POST → 400 예약어 에러 모킹 (백엔드 #5 수정 후 실제로 반환하는 응답)
    await page.route('**/api/v1/datasets/5/columns', (route) => {
      if (route.request().method() === 'POST') {
        return route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ message: "컬럼명 'id'은 시스템 예약어입니다. (예약어: id, import_id, created_at)" }),
        });
      }
      return route.continue();
    });

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    await page.getByRole('button', { name: '필드 추가' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 예약어 "id" 입력 후 추가
    await page.getByLabel('필드명 *').fill('id');
    await page.getByRole('dialog').getByRole('button', { name: '추가' }).click();

    // 에러 토스트가 표시된다 (handleApiError → toast.error)
    await expect(page.getByText(/예약어/).first()).toBeVisible({ timeout: 5000 });
    // 다이얼로그가 닫히지 않는다
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  test('필드 편집 버튼 클릭 시 ColumnDialog(edit) 가 열리고 기존 값이 채워진다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    // "name" 행의 Pencil(편집) 버튼 — 버튼 순서: expand(0), Up(1), Down(2), Pencil(3), Trash(4)
    const nameRow = page.getByRole('row').filter({ hasText: 'name' }).first();
    const editBtn = nameRow.getByRole('button').nth(3); // Pencil
    await editBtn.click();

    // 편집 다이얼로그가 열린다
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('dialog').getByRole('heading', { name: '필드 수정' })).toBeVisible();

    // 기존 필드명이 입력 필드에 채워져 있다
    await expect(page.getByLabel('필드명 *')).toHaveValue('name');
  });

  // 데이터가 있어도 PK 체크박스를 토글할 수 있어야 한다 (#117).
  // 이전 동작: hasData=true 일 때 PK 체크박스가 disabled.
  // 현 동작: 백엔드가 NOT NULL · 유일성을 검증하므로 UI 잠금을 해제.
  test('데이터 보유 데이터셋에서도 PK 체크박스가 활성화되어 있다 (#117)', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    // 비-PK 컬럼(name) 편집 다이얼로그 열기
    const nameRow = page.getByRole('row').filter({ hasText: 'name' }).first();
    await nameRow.getByRole('button', { name: '컬럼 편집' }).click();
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 5000 });

    // PK 체크박스가 enabled — rowCount=100(데이터 있음)이어도 잠겨 있지 않다
    const pkCheckbox = page.getByRole('dialog').getByRole('checkbox', { name: /기본 키/ });
    await expect(pkCheckbox).toBeEnabled();
    await expect(pkCheckbox).not.toBeChecked();
  });

  // PK 변경 PUT 요청에 isPrimaryKey=true 가 포함되어 전송된다 (#117).
  test('데이터 보유 데이터셋에서 PK 토글 후 저장 시 isPrimaryKey 가 payload 에 담긴다 (#117)', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    // PUT /api/v1/datasets/5/columns/2 캡처 모킹
    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/5/columns/2',
      { id: 2, columnName: 'name', displayName: '이름', dataType: 'TEXT',
        maxLength: null, isNullable: false, isIndexed: false, isPrimaryKey: true,
        description: null, columnOrder: 1 },
      { capture: true },
    );

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    // name 행의 편집 → PK 체크 → 저장
    const nameRow = page.getByRole('row').filter({ hasText: 'name' }).first();
    await nameRow.getByRole('button', { name: '컬럼 편집' }).click();
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 5000 });

    await page.getByRole('dialog').getByRole('checkbox', { name: /기본 키/ }).click();
    await page.getByRole('dialog').getByRole('button', { name: '수정' }).click();

    // PUT payload 에 isPrimaryKey=true 가 담겨 전송되었는지 검증
    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ isPrimaryKey: true });
  });

  // 기본 키 일괄 설정 다이얼로그 — 복합 PK 변경 (#117)
  test('"기본 키 설정" 버튼 클릭 시 PrimaryKeysDialog 가 열리고 현재 PK 가 체크되어 있다 (#117)', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    await page.getByRole('button', { name: /기본 키 설정/ }).click();
    await expect(page.getByRole('dialog', { name: /기본 키 일괄 설정/ })).toBeVisible({ timeout: 5000 });

    // id 컬럼은 현재 PK 라 체크되어 있어야 한다
    await expect(page.getByRole('checkbox', { name: /id 기본 키 토글/ })).toBeChecked();
    // name 컬럼은 NOT NULL 이므로 토글 가능
    await expect(page.getByRole('checkbox', { name: /name 기본 키 토글/ })).toBeEnabled();
    await expect(page.getByRole('checkbox', { name: /name 기본 키 토글/ })).not.toBeChecked();
    // amount 컬럼은 NULL 허용이라 비활성화
    await expect(page.getByRole('checkbox', { name: /amount 기본 키 토글/ })).toBeDisabled();
  });

  test('PK 일괄 설정 — 복합 키(id+name) 적용 시 PUT payload 에 두 컬럼 ID 가 담긴다 (#117)', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/5/primary-keys',
      {},
      { capture: true },
    );

    await page.goto('/data/datasets/5');
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    await page.getByRole('button', { name: /기본 키 설정/ }).click();
    await expect(page.getByRole('dialog', { name: /기본 키 일괄 설정/ })).toBeVisible({ timeout: 5000 });

    // name 추가 체크 (id 는 이미 체크) → 복합키
    await page.getByRole('checkbox', { name: /name 기본 키 토글/ }).click();
    await page.getByRole('button', { name: '적용' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ columnIds: [1, 2] });
  });

  test('행 액션 아이콘 4개에 aria-label이 부여된다 (위로/아래로/편집/삭제)', async ({
    authenticatedPage: page,
  }) => {
    await setupMocks(page);

    await page.goto('/data/datasets/5');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });

    // "name" 행의 4개 액션 버튼이 모두 접근 가능 이름(aria-label)을 가지는지 검증
    const nameRow = page.getByRole('row').filter({ hasText: 'name' }).first();
    await expect(nameRow.getByRole('button', { name: '컬럼 위로 이동' })).toBeVisible();
    await expect(nameRow.getByRole('button', { name: '컬럼 아래로 이동' })).toBeVisible();
    await expect(nameRow.getByRole('button', { name: '컬럼 편집' })).toBeVisible();
    await expect(nameRow.getByRole('button', { name: '컬럼 삭제' })).toBeVisible();
  });
});
