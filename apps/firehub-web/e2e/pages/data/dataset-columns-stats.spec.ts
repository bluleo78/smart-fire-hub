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
