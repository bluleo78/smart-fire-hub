import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 차트 빌더 페이지 인터랙티브 E2E 테스트
 * - 쿼리 실행 → 차트 타입 전환 → 저장 다이얼로그 → API payload 검증까지 전체 흐름 커버.
 * - ChartBuilderPage.tsx 의 useEffect·handleRunQuery·handleSave 분기를 커버하여
 *   라인 커버리지를 최대한 끌어올린다.
 */
test.describe('차트 빌더 인터랙티브 흐름', () => {
  /** 쿼리 실행 결과 — 기본 카테고리/숫자 2컬럼으로 BAR 추천 분기에 닿도록 구성 */
  const queryResult = createQueryResult({
    columns: ['category', 'amount'],
    rows: [
      { category: 'A', amount: 10 },
      { category: 'B', amount: 20 },
      { category: 'C', amount: 30 },
      { category: 'D', amount: 40 },
      { category: 'E', amount: 50 },
      { category: 'F', amount: 60 },
    ],
    totalRows: 6,
  });

  test('새 차트에서 쿼리 실행 후 미리보기 패널이 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    // 쿼리 실행 API — SavedQuery execute 엔드포인트
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/1/execute',
      queryResult,
    );

    await page.goto('/analytics/charts/new');

    // 쿼리 선택 드롭다운에서 '저장 쿼리 1' 선택
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();

    // 쿼리 실행 버튼 클릭 → executeQuery.mutateAsync 호출
    await page.getByRole('button', { name: '쿼리 실행' }).click();

    // 쿼리 실행 후 컬럼/행 카운트 요약이 데이터 소스 카드에 표시됨
    await expect(page.getByText('2개 컬럼, 6개 행 로드됨')).toBeVisible();

    // 쿼리 실행 전 안내 문구는 사라져야 함 (미리보기가 ChartRenderer로 대체)
    await expect(
      page.getByText('쿼리를 실행하면 차트가 표시됩니다.'),
    ).not.toBeVisible();

    // "6행 기준" 라벨이 미리보기 헤더에 노출됨
    await expect(page.getByText('6행 기준')).toBeVisible();
  });

  test('차트 타입 전환 시 선택 상태가 변경된다', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByText('2개 컬럼, 6개 행 로드됨')).toBeVisible();

    // 초기 추천 차트는 BAR (categoryCols=1, numericCols=1, unique=6 → BAR)
    // LINE으로 수동 전환 — variant 는 data-variant 속성으로 노출됨
    await page.getByRole('button', { name: '선 차트' }).click();
    await expect(page.getByRole('button', { name: '선 차트' })).toHaveAttribute(
      'data-variant',
      'default',
    );
    // 이전 BAR 버튼은 outline 으로 변경되어 있어야 함
    await expect(page.getByRole('button', { name: '막대 차트' })).toHaveAttribute(
      'data-variant',
      'outline',
    );

    // PIE로 전환 → yAxis 1개로 축소되는 경로 커버
    await page.getByRole('button', { name: '파이 차트' }).click();
    await expect(page.getByRole('button', { name: '파이 차트' })).toHaveAttribute(
      'data-variant',
      'default',
    );
  });

  test('새 차트 저장 시 POST /analytics/charts 에 올바른 payload가 전송된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    // 차트 생성 API 모킹 + payload 캡처
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/charts',
      {
        id: 42,
        name: '저장된 새 차트',
        description: null,
        savedQueryId: 1,
        savedQueryName: '저장 쿼리 1',
        chartType: 'BAR',
        config: { xAxis: 'category', yAxis: ['amount'] },
        isShared: false,
        createdByName: '테스트 사용자',
        createdBy: 1,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
      { capture: true },
    );

    // 저장 후 리다이렉트되는 /analytics/charts/42 에서 차트 상세 API
    await mockApi(page, 'GET', '/api/v1/analytics/charts/42', {
      id: 42,
      name: '저장된 새 차트',
      description: null,
      savedQueryId: 1,
      savedQueryName: '저장 쿼리 1',
      chartType: 'BAR',
      config: { xAxis: 'category', yAxis: ['amount'] },
      isShared: false,
      createdByName: '테스트 사용자',
      createdBy: 1,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    });

    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByText('2개 컬럼, 6개 행 로드됨')).toBeVisible();

    // 툴바의 "저장" 버튼 클릭 → SaveDialog 열림
    await page.getByRole('button', { name: '저장' }).click();
    // 다이얼로그 제목
    await expect(page.getByRole('heading', { name: '차트 저장' })).toBeVisible();

    // 이름 입력
    const nameInput = page.getByLabel('이름 *');
    await nameInput.fill('저장된 새 차트');

    // 다이얼로그 내부의 "저장" 버튼 클릭 → handleSave
    await page.getByRole('button', { name: '저장', exact: true }).last().click();

    // POST payload 검증
    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '저장된 새 차트',
      savedQueryId: 1,
      chartType: 'BAR',
      isShared: false,
    });
  });
});
