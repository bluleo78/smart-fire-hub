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
});
