import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 차트 빌더 — 차트 이미지 다운로드(PNG/SVG) E2E 테스트 (이슈 #74)
 *
 * 검증 포인트
 * 1) 쿼리 실행 전: 다운로드 버튼이 비활성 (disabled)
 * 2) 쿼리 실행 후: 드롭다운이 열리고 PNG / SVG 메뉴가 노출
 * 3) SVG 항목 클릭 → 브라우저 다운로드 이벤트 발생 + 파일명이 .svg 로 끝남
 * 4) PNG 항목 클릭 → 브라우저 다운로드 이벤트 발생 + 파일명이 .png 로 끝남
 *
 * 다운로드는 Playwright의 page.waitForEvent('download') 로 캡처해 검증한다.
 */
test.describe('차트 빌더 — 차트 이미지 다운로드', () => {
  const queryResult = createQueryResult({
    columns: ['category', 'amount'],
    rows: [
      { category: 'A', amount: 10 },
      { category: 'B', amount: 20 },
      { category: 'C', amount: 30 },
    ],
    totalRows: 3,
  });

  test('쿼리 실행 전에는 다운로드 버튼이 비활성이다', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);

    await page.goto('/analytics/charts/new');

    const downloadBtn = page.getByRole('button', { name: '차트 다운로드' });
    await expect(downloadBtn).toBeVisible();
    await expect(downloadBtn).toBeDisabled();
  });

  test('쿼리 실행 후 SVG 메뉴 항목 클릭 시 .svg 파일이 다운로드된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');

    // 쿼리 선택 + 실행
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByText('2개 컬럼, 3개 행 로드됨')).toBeVisible();

    // 미리보기 SVG가 실제로 DOM에 들어왔는지 확인 (recharts 렌더 대기)
    await expect(page.locator('.recharts-surface').first()).toBeVisible();

    // 다운로드 캡처 — click 직전에 waitForEvent 시작 (Playwright 표준 패턴)
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: '차트 다운로드' }).click();
    await page.getByRole('menuitem', { name: 'SVG 벡터로 저장' }).click();

    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/\.svg$/);
  });

  test('쿼리 실행 후 PNG 메뉴 항목 클릭 시 .png 파일이 다운로드된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewChartBuilderMocks(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', queryResult);

    await page.goto('/analytics/charts/new');

    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByText('2개 컬럼, 3개 행 로드됨')).toBeVisible();
    await expect(page.locator('.recharts-surface').first()).toBeVisible();

    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: '차트 다운로드' }).click();
    await page.getByRole('menuitem', { name: 'PNG 이미지로 저장' }).click();

    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/\.png$/);
  });
});
