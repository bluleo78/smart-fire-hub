import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 신규 차트 타입 렌더링 E2E 테스트
 * - ChartBuilderPage에서 HISTOGRAM, TREEMAP, FUNNEL, WATERFALL 타입을 선택하고
 *   차트 타입 버튼이 활성화(data-variant="default")되는 것을 검증한다.
 * - 쿼리 실행 → 차트 타입 선택 → 선택 상태 확인까지 입력→처리→출력 파이프라인 검증.
 */

/** 신규 차트 타입 테스트에 사용할 쿼리 실행 결과 — 범용 컬럼 구성 */
const queryResult = createQueryResult({
  columns: ['category', 'value'],
  rows: [
    { category: 'A', value: 10 },
    { category: 'B', value: 20 },
    { category: 'C', value: 30 },
    { category: 'D', value: 40 },
  ],
  totalRows: 4,
});

/**
 * 차트 빌더에서 쿼리를 선택·실행한 뒤 지정 차트 타입 버튼을 클릭하는 헬퍼
 * - 이미 setupNewChartBuilderMocks + POST execute 모킹이 완료된 상태에서 호출한다.
 */
async function selectChartType(
  page: import('@playwright/test').Page,
  chartTypeLabel: string,
) {
  // 쿼리 선택 드롭다운에서 '저장 쿼리 1' 선택
  await page.getByRole('combobox').click();
  await page.getByRole('option', { name: '저장 쿼리 1' }).click();

  // 쿼리 실행 버튼 클릭 → executeQuery 호출
  await page.getByRole('button', { name: '쿼리 실행' }).click();

  // 실행 결과가 로드될 때까지 대기 (컬럼/행 요약 표시)
  await expect(page.getByText('2개 컬럼, 4개 행 로드됨')).toBeVisible();

  // 목표 차트 타입 버튼 클릭
  await page.getByRole('button', { name: chartTypeLabel }).click();
}

test.describe('신규 차트 타입 선택 — ChartBuilderPage', () => {
  test('HISTOGRAM: 히스토그램 타입 선택 시 버튼이 활성 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');

    await selectChartType(page, '히스토그램');

    // 히스토그램 버튼이 활성화(default variant)되어야 함
    await expect(page.getByRole('button', { name: '히스토그램' })).toHaveAttribute(
      'data-variant',
      'default',
    );

    // 이전에 활성이었던 BAR(막대 차트)는 outline으로 변경되어야 함
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute(
      'data-variant',
      'outline',
    );
  });

  test('TREEMAP: 트리맵 타입 선택 시 버튼이 활성 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');

    await selectChartType(page, '트리맵');

    // 트리맵 버튼이 활성화되어야 함
    await expect(page.getByRole('button', { name: '트리맵' })).toHaveAttribute(
      'data-variant',
      'default',
    );

    // BAR는 비활성 상태여야 함
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute(
      'data-variant',
      'outline',
    );
  });

  test('FUNNEL: 퍼널 타입 선택 시 버튼이 활성 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');

    await selectChartType(page, '퍼널');

    // 퍼널 버튼이 활성화되어야 함
    await expect(page.getByRole('button', { name: '퍼널' })).toHaveAttribute(
      'data-variant',
      'default',
    );

    // BAR는 비활성 상태여야 함
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute(
      'data-variant',
      'outline',
    );
  });

  test('WATERFALL: 폭포 차트 타입 선택 시 버튼이 활성 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');

    await selectChartType(page, '폭포 차트');

    // 폭포 차트 버튼이 활성화되어야 함
    await expect(page.getByRole('button', { name: '폭포 차트' })).toHaveAttribute(
      'data-variant',
      'default',
    );

    // BAR는 비활성 상태여야 함
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute(
      'data-variant',
      'outline',
    );
  });

  test('BOXPLOT: 박스 플롯 타입 선택 시 버튼이 활성 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');
    await selectChartType(page, '박스 플롯');

    await expect(page.getByRole('button', { name: '박스 플롯' })).toHaveAttribute('data-variant', 'default');
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute('data-variant', 'outline');
  });

  test('HEATMAP: 히트맵 타입 선택 시 버튼이 활성 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');
    await selectChartType(page, '히트맵');

    await expect(page.getByRole('button', { name: '히트맵' })).toHaveAttribute('data-variant', 'default');
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute('data-variant', 'outline');
  });

  test('RADAR: 레이더 타입 선택 시 버튼이 활성 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');
    await selectChartType(page, '레이더');

    await expect(page.getByRole('button', { name: '레이더' })).toHaveAttribute('data-variant', 'default');
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute('data-variant', 'outline');
  });

  test('GAUGE: 게이지 타입 선택 시 버튼이 활성 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');
    await selectChartType(page, '게이지');

    await expect(page.getByRole('button', { name: '게이지' })).toHaveAttribute('data-variant', 'default');
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute('data-variant', 'outline');
  });

  test('CANDLESTICK: 캔들스틱 타입 선택 시 버튼이 활성 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');
    await selectChartType(page, '캔들스틱');

    await expect(page.getByRole('button', { name: '캔들스틱' })).toHaveAttribute('data-variant', 'default');
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute('data-variant', 'outline');
  });

  test('SCATTER: 산점도 타입 선택 시 버튼이 활성 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');
    await selectChartType(page, '산점도');

    await expect(page.getByRole('button', { name: '산점도' })).toHaveAttribute('data-variant', 'default');
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute('data-variant', 'outline');
  });

  test('AREA: 영역 차트 타입 선택 시 버튼이 활성 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');
    await selectChartType(page, '영역 차트');

    await expect(page.getByRole('button', { name: '영역 차트' })).toHaveAttribute('data-variant', 'default');
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute('data-variant', 'outline');
  });
});
