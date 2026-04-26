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

  test('rawHtml이 있으면 인쇄·PDF 버튼이 활성화된다', async ({
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
});
