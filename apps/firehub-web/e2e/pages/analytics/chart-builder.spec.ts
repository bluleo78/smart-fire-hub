import {
  setupChartBuilderMocks,
  setupNewChartBuilderMocks,
} from '../../fixtures/analytics.fixture';
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
  });

  test('미리보기 패널에 초기 안내 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);

    await page.goto('/analytics/charts/new');

    // 쿼리 실행 전 미리보기 안내 문구 확인
    await expect(page.getByText('쿼리를 실행하면 차트가 표시됩니다.')).toBeVisible();
  });
});
