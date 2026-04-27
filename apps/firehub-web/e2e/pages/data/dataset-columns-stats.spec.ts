import { createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 컬럼 탭 + ColumnStats 통계 패널 E2E 테스트
 * - DatasetColumnsTab: 컬럼 목록 렌더링, 필드 추가 버튼, 통계 확장 패널
 * - ColumnStats: NullProgressBar, Distinct 배지, ColumnExpandedStats 패널
 * - DatasetDetailPage: 이름 표시, 탭 전환 동작
 */

const DATASET_ID = 1;

/** 컬럼 통계 모킹 데이터 — id(INTEGER), name(TEXT) 컬럼 각각 통계 포함 */
function createColumnStats() {
  return [
    {
      columnName: 'id',
      dataType: 'INTEGER',
      totalCount: 100,
      nullCount: 0,
      nullPercent: 0,
      distinctCount: 100,
      minValue: '1',
      maxValue: '100',
      avgValue: 50.5,
      topValues: [
        { value: '1', count: 1 },
        { value: '2', count: 1 },
      ],
      sampled: false,
    },
    {
      columnName: 'name',
      dataType: 'TEXT',
      totalCount: 100,
      nullCount: 5,
      nullPercent: 5.0,
      distinctCount: 95,
      minValue: null,
      maxValue: null,
      avgValue: null,
      topValues: [
        { value: '항목 A', count: 10 },
        { value: '항목 B', count: 8 },
      ],
      sampled: false,
    },
  ];
}

/** 기본 모킹 + 컬럼 통계 API 포함 설정 */
async function setupMocksWithStats(page: import('@playwright/test').Page) {
  // 기본 상세 모킹 (rowCount=100 → hasData=true → 통계 API 활성화)
  await setupDatasetDetailMocks(page, DATASET_ID);
  // 통계 API를 실제 데이터로 덮어씌워 컬럼 통계 패널이 렌더링되도록 함
  await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/stats`, createColumnStats());
}

test.describe('DatasetDetailPage — 탭 전환', () => {
  test('데이터셋 이름이 페이지 제목에 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await page.goto(`/data/datasets/${DATASET_ID}`);

    // createDatasetDetail 기본 name = '테스트 데이터셋'
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
  });

  test('기본 탭은 정보(info) 탭이다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await page.goto(`/data/datasets/${DATASET_ID}`);

    // 정보 탭이 선택된 상태로 렌더링된다
    await expect(page.getByRole('tab', { name: '정보' })).toHaveAttribute('data-state', 'active');
  });

  test('필드 탭 클릭 시 컬럼 목록이 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await page.goto(`/data/datasets/${DATASET_ID}`);

    await page.getByRole('tab', { name: '필드' }).click();

    // DatasetColumnsTab 헤더 확인
    await expect(page.getByText(/필드 목록/)).toBeVisible();
  });

  test('탭 전환: 정보 → 필드 → 데이터 순서로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await page.goto(`/data/datasets/${DATASET_ID}`);

    // 필드 탭으로 이동
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('tab', { name: '필드' })).toHaveAttribute('data-state', 'active');

    // 데이터 탭으로 이동
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page.getByRole('tab', { name: '데이터' })).toHaveAttribute('data-state', 'active');
  });
});

test.describe('DatasetColumnsTab — 컬럼 목록', () => {
  test('컬럼 이름(id, name)이 테이블에 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    // createDatasetDetail의 기본 컬럼: id, name
    await expect(page.getByRole('cell', { name: 'id' }).first()).toBeVisible();
    await expect(page.getByRole('cell', { name: 'name' }).first()).toBeVisible();
  });

  test('필드 추가 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    await expect(page.getByRole('button', { name: '필드 추가' })).toBeVisible();
  });

  test('필드 추가 버튼 클릭 시 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    await page.getByRole('button', { name: '필드 추가' }).click();

    // 다이얼로그 열림 확인
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  test('PK 컬럼에 PK 배지가 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, DATASET_ID);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    // createColumn 기본값: isPrimaryKey=true → PK 표시
    await expect(page.getByText('PK')).toBeVisible();
  });
});

test.describe('DatasetColumnsTab — 컬럼 통계 (ColumnStats)', () => {
  test('통계 데이터가 있을 때 Null% 진행 바가 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupMocksWithStats(page);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    // NullProgressBar: nullPercent=0 → '0.0%' 텍스트 렌더링
    await expect(page.getByText('0.0%')).toBeVisible();
  });

  test('통계 데이터가 있을 때 Distinct 배지가 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupMocksWithStats(page);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    // id 컬럼: distinctCount=100, name 컬럼: distinctCount=95
    await expect(page.getByText('100')).toBeVisible();
    await expect(page.getByText('95')).toBeVisible();
  });

  test('통계가 없을 때(rowCount=0) 확장 버튼이 비활성화된다', async ({ authenticatedPage: page }) => {
    const detail = createDatasetDetail({ id: DATASET_ID, rowCount: 0 });
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
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/queries`, { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    // rowCount=0이면 확장 버튼(chevron)이 disabled 상태
    const expandButtons = page.locator('table button[disabled]');
    await expect(expandButtons.first()).toBeDisabled();
  });

  test('통계 확장 클릭 시 ColumnExpandedStats 패널이 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupMocksWithStats(page);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    // 첫 번째 행의 확장 버튼 클릭 (id 컬럼 — INTEGER)
    const expandBtn = page.locator('table tbody tr').first().locator('button').first();
    await expandBtn.click();

    // ColumnExpandedStats: NULL 요약 텍스트 렌더링
    await expect(page.getByText(/NULL:/)).toBeVisible();
    // INTEGER 컬럼: 최솟값/최댓값/평균값 카드 렌더링
    await expect(page.getByText('최솟값')).toBeVisible();
    await expect(page.getByText('최댓값')).toBeVisible();
    await expect(page.getByText('평균값')).toBeVisible();
  });

  test('통계 API 응답이 확장 패널 값에 정확히 반영된다', async ({ authenticatedPage: page }) => {
    await setupMocksWithStats(page);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    // id 컬럼 확장 — minValue='1', maxValue='100', avgValue=50.5
    const expandBtn = page.locator('table tbody tr').first().locator('button').first();
    await expandBtn.click();

    // ColumnExpandedStats 패널에서 실제 통계 값 확인
    await expect(page.getByText('1').first()).toBeVisible(); // minValue
    await expect(page.getByText('100').first()).toBeVisible(); // maxValue or distinctCount
    // NULL: 0 / 100 형식 렌더링
    await expect(page.getByText(/NULL: 0 \/ 100/)).toBeVisible();
  });
});

test.describe('DatasetColumnsTab — 추가 컬럼 타입 통계 (ColumnStats 미커버 분기)', () => {
  /**
   * TEXT 컬럼 + topValues → TextMiniChart, ColumnExpandedStats 의 isText 분기 커버
   */
  test('TEXT 컬럼 확장 패널에서 상위 빈도 값이 표시된다', async ({ authenticatedPage: page }) => {
    // TEXT 컬럼만 있는 데이터셋 상세 설정
    const { createColumn, createDatasetDetail } = await import('../../factories/dataset.factory');
    const detail = createDatasetDetail({
      id: DATASET_ID,
      columns: [
        createColumn({ id: 1, columnName: 'status', displayName: '상태', dataType: 'TEXT', isPrimaryKey: false, columnOrder: 0 }),
      ],
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/data`, {
      columns: detail.columns,
      rows: [],
      page: 0, size: 20, totalElements: 0, totalPages: 0,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/queries`, { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
    // TEXT 컬럼 통계 — topValues 3개 제공 → TextMiniChart + "상위 빈도 값" 섹션
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/stats`, [
      {
        columnName: 'status',
        dataType: 'TEXT',
        totalCount: 200,
        nullCount: 0,
        nullPercent: 0.0,
        distinctCount: 3,
        minValue: null,
        maxValue: null,
        avgValue: null,
        topValues: [
          { value: 'ACTIVE', count: 100 },
          { value: 'INACTIVE', count: 60 },
          { value: 'PENDING', count: 40 },
        ],
        sampled: false,
      },
    ]);

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    // 확장 버튼 클릭 → ColumnExpandedStats isText 분기
    const expandBtn = page.locator('table tbody tr').first().locator('button').first();
    await expandBtn.click();

    // "상위 빈도 값" 헤더와 topValues 표시
    await expect(page.getByText('상위 빈도 값')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('ACTIVE', { exact: true })).toBeVisible();
  });

  /**
   * BOOLEAN 컬럼 + topValues → BooleanMiniChart, ColumnExpandedStats 의 isBoolean 분기 커버
   */
  test('BOOLEAN 컬럼 확장 패널에서 True/False 비율이 표시된다', async ({ authenticatedPage: page }) => {
    const { createColumn, createDatasetDetail } = await import('../../factories/dataset.factory');
    const detail = createDatasetDetail({
      id: DATASET_ID,
      columns: [
        createColumn({ id: 1, columnName: 'is_active', displayName: '활성', dataType: 'BOOLEAN', isPrimaryKey: false, columnOrder: 0 }),
      ],
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/data`, {
      columns: detail.columns,
      rows: [],
      page: 0, size: 20, totalElements: 0, totalPages: 0,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/queries`, { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
    // BOOLEAN 컬럼 통계 — true/false topValues 제공
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/stats`, [
      {
        columnName: 'is_active',
        dataType: 'BOOLEAN',
        totalCount: 100,
        nullCount: 0,
        nullPercent: 0.0,
        distinctCount: 2,
        minValue: null,
        maxValue: null,
        avgValue: null,
        topValues: [
          { value: 'true', count: 75 },
          { value: 'false', count: 25 },
        ],
        sampled: false,
      },
    ]);

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    // 확장 버튼 클릭 → ColumnExpandedStats isBoolean 분기
    const expandBtn = page.locator('table tbody tr').first().locator('button').first();
    await expandBtn.click();

    // "True / False 비율" 헤더 표시 확인
    await expect(page.getByText('True / False 비율')).toBeVisible({ timeout: 5000 });
    // T:75 / F:25 형태로 표시
    await expect(page.getByText(/T:75 \/ F:25/)).toBeVisible();
  });

  /**
   * DATE 컬럼 + min/max → DateMiniDisplay, ColumnExpandedStats 의 isDate 분기 커버
   */
  test('DATE 컬럼 확장 패널에서 날짜 범위가 표시된다', async ({ authenticatedPage: page }) => {
    const { createColumn, createDatasetDetail } = await import('../../factories/dataset.factory');
    const detail = createDatasetDetail({
      id: DATASET_ID,
      columns: [
        createColumn({ id: 1, columnName: 'created_at', displayName: '생성일', dataType: 'DATE', isPrimaryKey: false, columnOrder: 0 }),
      ],
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/data`, {
      columns: detail.columns,
      rows: [],
      page: 0, size: 20, totalElements: 0, totalPages: 0,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/queries`, { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
    // DATE 컬럼 통계 — minValue, maxValue 제공
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/stats`, [
      {
        columnName: 'created_at',
        dataType: 'DATE',
        totalCount: 500,
        nullCount: 0,
        nullPercent: 0.0,
        distinctCount: 365,
        minValue: '2023-01-01',
        maxValue: '2023-12-31',
        avgValue: null,
        topValues: [],
        sampled: false,
      },
    ]);

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    // 확장 버튼 클릭 → ColumnExpandedStats isDate 분기
    const expandBtn = page.locator('table tbody tr').first().locator('button').first();
    await expandBtn.click();

    // 날짜 범위 표시 확인 (minValue ~ maxValue)
    await expect(page.getByText('2023-01-01')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('2023-12-31')).toBeVisible();
  });

  /**
   * INTEGER 컬럼 + sampled: true → "샘플링된 통계" 안내 문구 표시 커버
   */
  test('샘플링된 통계일 때 안내 문구가 표시된다', async ({ authenticatedPage: page }) => {
    const { createColumn, createDatasetDetail } = await import('../../factories/dataset.factory');
    const detail = createDatasetDetail({
      id: DATASET_ID,
      columns: [
        createColumn({ id: 1, columnName: 'score', displayName: '점수', dataType: 'INTEGER', isPrimaryKey: false, columnOrder: 0 }),
      ],
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/data`, {
      columns: detail.columns,
      rows: [],
      page: 0, size: 20, totalElements: 0, totalPages: 0,
    });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/queries`, { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/stats`, [
      {
        columnName: 'score',
        dataType: 'INTEGER',
        totalCount: 150000,
        nullCount: 0,
        nullPercent: 0.0,
        distinctCount: 1000,
        minValue: '0',
        maxValue: '100',
        avgValue: 55.3,
        topValues: [{ value: '50', count: 300 }, { value: '75', count: 250 }],
        sampled: true, // 10만행 초과 → 샘플링
      },
    ]);

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    const expandBtn = page.locator('table tbody tr').first().locator('button').first();
    await expandBtn.click();

    // sampled: true → "샘플링된 통계" 안내 문구
    await expect(page.getByText(/샘플링된 통계/)).toBeVisible({ timeout: 5000 });
  });

  /**
   * ColumnMiniChart 팝오버 클릭 → ColumnStatsPopoverContent 렌더링 커버
   * ColumnMiniChart 는 DatasetDataTab 의 데이터 테이블 컬럼 헤더에 렌더링된다.
   */
  test('데이터 탭 미니 차트 클릭 시 팝오버 통계 패널이 표시된다', async ({ authenticatedPage: page }) => {
    await setupMocksWithStats(page);
    await page.goto(`/data/datasets/${DATASET_ID}`);

    // 데이터 탭으로 이동 (DatasetDataTab 에 ColumnMiniChart 가 있음)
    await page.getByRole('tab', { name: '데이터' }).click();

    // ColumnMiniChart: SVG를 감싸는 div[style*="height: 20px"][style*="cursor"]
    // DatasetDataTab 컬럼 헤더에 렌더링된 미니 차트 클릭
    const miniChartDiv = page.locator('div[style*="height: 20px"]').first();
    await expect(miniChartDiv).toBeVisible({ timeout: 5000 });
    // PopoverTrigger의 asChild div를 직접 dispatch click
    await miniChartDiv.dispatchEvent('click');

    // ColumnStatsPopoverContent: Total/Null/Distinct 레이블 표시
    await expect(page.getByText('Total')).toBeVisible({ timeout: 3000 });
    await expect(page.getByText('Null')).toBeVisible();
    await expect(page.getByText('Distinct')).toBeVisible();
  });
});

// (#91) 100% NULL 컬럼 시각 강조 — 행 강조 + "비어있음" 배지 + 헤더 카운터
test.describe('DatasetColumnsTab — Null 100% 컬럼 강조 (#91)', () => {
  test('nullPercent=100인 컬럼 행에 강조 + "비어있음" 배지 + 헤더 카운터가 노출된다', async ({
    authenticatedPage: page,
  }) => {
    // 두 번째 컬럼(name)을 100% NULL로 모킹
    const stats = [
      {
        columnName: 'id',
        dataType: 'INTEGER',
        totalCount: 100,
        nullCount: 0,
        nullPercent: 0,
        distinctCount: 100,
        minValue: '1',
        maxValue: '100',
        avgValue: 50.5,
        topValues: [],
        sampled: false,
      },
      {
        columnName: 'name',
        dataType: 'TEXT',
        totalCount: 100,
        nullCount: 100,
        nullPercent: 100,
        distinctCount: 0,
        minValue: null,
        maxValue: null,
        avgValue: null,
        topValues: [],
        sampled: false,
      },
    ];
    await setupDatasetDetailMocks(page, DATASET_ID);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/stats`, stats);

    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    // 헤더 카운터: "이상 1개" 표시
    await expect(page.getByTestId('empty-column-count')).toHaveText('(이상 1개)');

    // 강조된 행이 정확히 1개 존재 (data-testid 마커)
    const emptyRows = page.getByTestId('empty-column-row');
    await expect(emptyRows).toHaveCount(1);

    // "비어있음" 배지 노출
    await expect(page.getByText('비어있음')).toBeVisible();
  });

  test('100% NULL 컬럼이 없으면 카운터/배지/강조가 모두 노출되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupMocksWithStats(page);
    await page.goto(`/data/datasets/${DATASET_ID}`);
    await page.getByRole('tab', { name: '필드' }).click();

    await expect(page.getByTestId('empty-column-count')).toHaveCount(0);
    await expect(page.getByTestId('empty-column-row')).toHaveCount(0);
    await expect(page.getByText('비어있음')).toHaveCount(0);
  });
});
