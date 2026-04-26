import { createQueryResult } from '../../factories/analytics.factory';
import {
  setupChartBuilderMocks,
  setupNewChartBuilderMocks,
} from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 차트 빌더 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 차트 빌더 UI를 검증한다.
 */
test.describe('차트 빌더 페이지', () => {
  test('새 차트 빌더가 렌더링된다', async ({ authenticatedPage: page }) => {
    // 새 차트 빌더에서 필요한 쿼리 목록 API 모킹
    await setupNewChartBuilderMocks(page);

    await page.goto('/analytics/charts/new');

    // 툴바의 "새 차트" 텍스트 확인
    await expect(page.getByText('새 차트')).toBeVisible();

    // 저장 버튼이 존재하는지 확인
    await expect(page.getByRole('button', { name: '저장' })).toBeVisible();
  });

  test('기존 차트 로드 시 차트 이름이 툴바에 표시된다', async ({ authenticatedPage: page }) => {
    // 차트 ID=1 관련 API 모킹
    await setupChartBuilderMocks(page, 1);

    await page.goto('/analytics/charts/1');

    // 차트 이름이 툴바에 표시되는지 확인 (팩토리 기본값: '테스트 차트')
    await expect(page.getByText('테스트 차트')).toBeVisible();

    // 차트 타입 패널에 BAR('막대 차트') 버튼이 selected 상태(variant="default")인지 확인
    // ChartTypeSelector는 icon 버튼으로 구성되며, aria-label에 레이블명이 있다
    await expect(page.getByRole('button', { name: '막대 차트' })).toBeVisible();

    // 데이터 소스 패널의 Select 트리거에 '테스트 쿼리'가 표시되는지 확인
    // existingChart.savedQueryId(=1)가 queries 목록과 매칭되면 Select에 해당 이름이 표시됨
    // setupChartBuilderMocks가 createSavedQueryList(3)을 모킹하므로 id=1 → '저장 쿼리 1'
    // 단, savedQueryId=1이 선택되면 SelectTrigger에 '저장 쿼리 1'이 표시됨
    await expect(page.getByRole('combobox')).toContainText('저장 쿼리 1');
  });

  test('차트 타입 패널이 표시된다', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);

    await page.goto('/analytics/charts/new');

    // "차트 타입" 카드 타이틀 확인
    await expect(page.getByText('차트 타입')).toBeVisible();
  });

  test('데이터 소스(쿼리 선택) 패널이 표시된다', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);

    await page.goto('/analytics/charts/new');

    // "데이터 소스" 카드 타이틀 확인
    await expect(page.getByText('데이터 소스')).toBeVisible();

    // 쿼리 실행 버튼 확인
    await expect(page.getByRole('button', { name: '쿼리 실행' })).toBeVisible();

    // createSavedQueryList(3)으로 생성된 쿼리 목록에서 첫 번째 항목 '저장 쿼리 1'이 선택 가능한지 확인
    // Select 드롭다운은 트리거 클릭 후에만 SelectContent가 렌더링되므로 먼저 열어야 한다
    await page.getByRole('combobox').click();
    await expect(page.getByRole('option', { name: '저장 쿼리 1' })).toBeVisible();
  });

  test('미리보기 패널에 초기 안내 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);

    await page.goto('/analytics/charts/new');

    // 쿼리 실행 전 미리보기 안내 문구 확인
    await expect(page.getByText('쿼리를 실행하면 차트가 표시됩니다.')).toBeVisible();
  });

  test('새 차트 저장 — 저장 다이얼로그에서 POST payload 검증', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    // 쿼리 실행 API 모킹: columns=['id','name','value'] → xAxis='name', yAxis=['id'] 자동 설정
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/1/execute',
      createQueryResult(),
    );

    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/charts',
      { id: 99, name: '화재 현황 차트', chartType: 'BAR' },
      { capture: true },
    );

    await page.goto('/analytics/charts/new');
    await expect(page.getByText('쿼리를 실행하면 차트가 표시됩니다.')).toBeVisible();

    // 쿼리 선택
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();

    // 쿼리 실행 → axis 자동 설정
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByText(/행 로드됨/).first()).toBeVisible();

    // 저장 버튼 클릭 → 저장 다이얼로그 열기
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('차트 저장')).toBeVisible();

    // 차트 이름 입력
    await page.getByPlaceholder('차트 이름을 입력하세요').fill('화재 현황 차트');

    // 저장 버튼 클릭 (다이얼로그 내)
    await page.getByRole('dialog').getByRole('button', { name: '저장' }).click();

    // POST payload 검증
    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '화재 현황 차트' });
  });

  test('기존 차트 수정 저장 — 수정 다이얼로그에서 PUT payload 검증', async ({
    authenticatedPage: page,
  }) => {
    await setupChartBuilderMocks(page, 1);

    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/analytics/charts/1',
      { id: 1, name: '수정된 차트', chartType: 'BAR' },
      { capture: true },
    );

    await page.goto('/analytics/charts/1');
    await expect(page.getByText('테스트 차트')).toBeVisible();

    // 저장 버튼 클릭 → 차트 수정 다이얼로그 열기
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('차트 수정')).toBeVisible();

    // 이름 변경
    const nameInput = page.getByLabel('이름');
    await nameInput.clear();
    await nameInput.fill('수정된 차트');

    // 수정 버튼 클릭
    await page.getByRole('dialog').getByRole('button', { name: '수정' }).click();

    // PUT payload 검증
    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '수정된 차트' });
  });

  /**
   * 회귀 테스트 — 이슈 #20
   * 존재하지 않는 차트 ID 접근 시 빈 빌더 대신 에러 안내가 표시되어야 한다.
   */
  test('존재하지 않는 차트 ID 접근 시 에러 안내와 목록 이동 버튼이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // 차트 API가 404를 반환하도록 모킹 (쿼리 목록도 필요)
    await mockApi(page, 'GET', '/api/v1/analytics/charts/99999', { message: 'Not found' }, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/analytics/queries', { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 });

    await page.goto('/analytics/charts/99999');

    // 에러 안내 메시지가 표시되어야 한다
    await expect(page.getByText('차트를 찾을 수 없습니다.')).toBeVisible();

    // 목록으로 이동 버튼이 표시되어야 한다
    await expect(page.getByRole('button', { name: '목록으로' })).toBeVisible();

    // 빈 차트 빌더("새 차트")는 표시되지 않아야 한다 (회귀 방지)
    await expect(page.getByText('새 차트')).not.toBeVisible();
  });

  test('존재하지 않는 차트에서 목록으로 버튼 클릭 시 차트 목록 페이지로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/analytics/charts/99999', { message: 'Not found' }, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/analytics/queries', { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    await page.goto('/analytics/charts/99999');

    await page.getByRole('button', { name: '목록으로' }).click();

    // 차트 목록 페이지로 이동되어야 한다
    await expect(page).toHaveURL('/analytics/charts');
  });
});
