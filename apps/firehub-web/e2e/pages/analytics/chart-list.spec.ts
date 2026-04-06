import { createChartListItem } from '../../factories/analytics.factory';
import { setupChartListMocks } from '../../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 차트 목록 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 목록 페이지 UI를 검증한다.
 */
test.describe('차트 목록 페이지', () => {
  test('차트 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 3개 차트 목록을 모킹한 후 목록 페이지 접근
    await setupChartListMocks(page, 3);
    await page.goto('/analytics/charts');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '차트' })).toBeVisible();

    // 테이블 헤더 컬럼 확인
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '타입' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '쿼리' })).toBeVisible();

    // 차트 행이 3개 렌더링되는지 확인 (fixture에서 생성한 이름 패턴)
    // 셀 내부는 복합 구조이므로 getByText로 확인
    await expect(page.getByText('테스트 차트 1')).toBeVisible();
    await expect(page.getByText('테스트 차트 3')).toBeVisible();

    // 행 수 확인: 헤더 1개 + 데이터 3개 = 총 4개 행
    await expect(page.getByRole('row')).toHaveCount(4);

    // 첫 번째 데이터 행에 BAR 타입 뱃지 '막대'가 표시되는지 확인
    // setupChartListMocks는 기본적으로 chartType: 'BAR'로 생성하므로 '막대' 뱃지 검증
    const firstRow = page.getByRole('row').nth(1);
    await expect(firstRow.locator('[data-slot="badge"]').filter({ hasText: '막대' })).toBeVisible();

    // 첫 번째 데이터 행에 쿼리 이름 '테스트 쿼리'가 표시되는지 확인
    await expect(firstRow.getByText('테스트 쿼리')).toBeVisible();
  });

  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 빈 페이지 응답으로 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));

    await page.goto('/analytics/charts');

    // 빈 상태 메시지 확인
    await expect(page.getByText('차트가 없습니다.')).toBeVisible();
  });

  test('탭 — "내 차트"/"공유됨" 탭이 표시된다', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 2);
    await page.goto('/analytics/charts');

    // 탭 목록 확인
    await expect(page.getByRole('tab', { name: '내 차트' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '공유됨' })).toBeVisible();
  });

  test('차트 타입 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    // 막대(BAR) 차트 1개 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts',
      createPageResponse([
        createChartListItem({ id: 1, name: '막대 차트', chartType: 'BAR' }),
      ]),
    );

    await page.goto('/analytics/charts');

    // "막대" 뱃지 확인 (CHART_TYPE_LABELS 기준)
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '막대' })).toBeVisible();
  });

  test('삭제 버튼 클릭 시 확인 다이얼로그가 열리고 DELETE API가 호출된다', async ({ authenticatedPage: page }) => {
    await setupChartListMocks(page, 2);
    await page.goto('/analytics/charts');

    // DELETE /api/v1/analytics/charts/1 API 캡처 모킹
    const deleteCapture = await mockApi(
      page,
      'DELETE',
      '/api/v1/analytics/charts/1',
      {},
      { capture: true },
    );

    // 첫 번째 행의 삭제 버튼 클릭 (aria-label="삭제")
    const deleteButtons = page.getByRole('button', { name: '삭제' });
    await deleteButtons.first().click();

    // 삭제 확인 다이얼로그가 열리는지 확인
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // 다이얼로그의 확인(삭제) 버튼 클릭
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    // DELETE API가 실제로 호출되었는지 검증
    const captured = await deleteCapture.waitForRequest();
    expect(captured).toBeDefined();
  });
});
