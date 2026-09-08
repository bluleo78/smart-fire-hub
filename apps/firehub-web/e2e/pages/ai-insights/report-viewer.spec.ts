import { setupReportViewerMocks } from '../../fixtures/ai-insight.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * ReportViewerPage E2E 테스트
 * - 인쇄/PDF 버튼의 disabled 상태를 rawHtml 유무에 따라 검증한다.
 * - #39: rawHtml=null 상태에서 버튼이 활성화되던 버그의 회귀 방지.
 */
test.describe('리포트 뷰어 페이지', () => {
  test('rawHtml이 null이면 인쇄·PDF 버튼이 비활성화된다 (#39 회귀)', async ({
    authenticatedPage: page,
  }) => {
    // rawHtml=null 상태 모킹 — API가 null을 반환해 "리포트가 없습니다" 상태를 재현한다
    await setupReportViewerMocks(page, 1, 1, null);

    await page.goto('/ai-insights/jobs/1/executions/1/report');

    // "리포트가 없습니다." 메시지가 표시되어야 한다
    await expect(page.getByText('리포트가 없습니다.')).toBeVisible();

    // 인쇄 버튼이 비활성화되어 있어야 한다 — 빈 리포트 인쇄 방지
    const printButton = page.getByRole('button', { name: '인쇄' });
    await expect(printButton).toBeDisabled();

    // PDF 버튼이 비활성화되어 있어야 한다 — 빈 리포트 다운로드 방지
    const pdfButton = page.getByRole('button', { name: 'PDF' });
    await expect(pdfButton).toBeDisabled();
  });

  test('rawHtml이 있으면 인쇄·PDF 버튼이 활성화된다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    // 정상 HTML 리포트 모킹 — 버튼이 활성화되어야 한다
    await setupReportViewerMocks(page, 1, 1, '<html><body><h1>리포트</h1></body></html>');

    await page.goto('/ai-insights/jobs/1/executions/1/report');

    // 인쇄 버튼이 활성화되어 있어야 한다
    const printButton = page.getByRole('button', { name: '인쇄' });
    await expect(printButton).toBeEnabled();

    // PDF 버튼이 활성화되어 있어야 한다
    const pdfButton = page.getByRole('button', { name: 'PDF' });
    await expect(pdfButton).toBeEnabled();
  });

  test('jobId/executionId가 숫자가 아니면 잘못된 요청 상태로 분기하고 깨진 링크를 노출하지 않는다 (#542 회귀)', async ({
    authenticatedPage: page,
  }) => {
    // NaN 파라미터 경로로 직접 접근 — 이 경우 executionHtml API는 호출되지 않아야 한다 (enabled: false)
    let apiCalled = false;
    await page.route('**/api/v1/proactive/jobs/*/executions/*/html', (route) => {
      apiCalled = true;
      return route.continue();
    });

    await page.goto('/ai-insights/jobs/abc/executions/xyz/report');

    // 헤더에 NaN이 노출되지 않아야 한다
    await expect(page.getByRole('heading', { name: '리포트' })).toBeVisible();
    await expect(page.getByRole('heading', { name: /NaN/ })).toHaveCount(0);

    // "리포트가 없습니다" 같은 일반 빈 상태가 아니라 명시적인 잘못된 요청 메시지가 표시되어야 한다
    await expect(page.getByText('잘못된 요청입니다.')).toBeVisible();
    await expect(page.getByText('리포트가 없습니다.')).toHaveCount(0);

    // "작업 상세 보기" 같은 깨진 링크(예: /ai-insights/jobs/NaN)를 노출하지 않아야 한다
    await expect(page.getByRole('link', { name: '작업 상세 보기' })).toHaveCount(0);
    await expect(page.locator('a[href*="NaN"]')).toHaveCount(0);

    // 뒤로가기 버튼만 제공되어야 한다
    await expect(page.getByRole('button', { name: '돌아가기' })).toBeVisible();

    // 잘못된 파라미터이므로 리포트 조회 API가 호출되지 않아야 한다
    expect(apiCalled).toBe(false);
  });
});
