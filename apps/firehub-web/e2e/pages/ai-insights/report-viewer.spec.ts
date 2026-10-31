import { createJob, createReport } from '../../factories/ai-insight.factory';
import { setupReportViewerMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
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
  // 회귀 가드(WD-343): 리포트 iframe 은 스크립트를 막는 sandbox 지만 allow-modals 가 있어야 부모가 부른 print() 가
  // 무시되지 않는다. 수정 전에는 콘솔에 「Ignored call to 'print()'… 'allow-modals' keyword is not set」만 찍히고 인쇄 창이 뜨지 않았다.
  test('인쇄를 누르면 리포트 iframe 의 print() 가 무시되지 않는다 (WD-343)', async ({ authenticatedPage: page }) => {
    const ignored: string[] = [];
    page.on('console', (m) => {
      if (m.text().includes("Ignored call to 'print()'")) ignored.push(m.text());
    });
    await setupReportViewerMocks(page, 1, 1, '<html><body><h1>리포트 본문</h1></body></html>');

    await page.goto('/ai-insights/jobs/1/executions/1/report');
    const frame = page.locator('iframe[title="리포트"]');
    await expect(frame.contentFrame().getByRole('heading', { name: '리포트 본문' })).toBeVisible();
    // 스크립트는 계속 막고(allow-scripts 없음) 인쇄만 허용한다
    const sandbox = await frame.getAttribute('sandbox');
    expect(sandbox?.split(' ').sort()).toEqual(['allow-modals', 'allow-same-origin']);

    // iframe 문서의 print 호출을 기록한다(헤드리스에서도 실제 인쇄 창 대신 호출 여부로 확인)
    await frame.evaluate((el: HTMLIFrameElement) => {
      const win = el.contentWindow as Window & { __printed?: number };
      const original = win.print.bind(win);
      win.__printed = 0;
      // 실제 print 를 먼저 불러야 sandbox 가 막을 때 콘솔 경고가 남는다 — 그 뒤 횟수를 센다
      win.print = () => {
        original();
        win.__printed = (win.__printed ?? 0) + 1;
      };
    });
    await page.getByRole('button', { name: '인쇄' }).click();

    await expect
      .poll(() => frame.evaluate((el: HTMLIFrameElement) => (el.contentWindow as Window & { __printed?: number }).__printed))
      .toBe(1);
    expect(ignored).toEqual([]);
  });

  // 회귀 가드(WD-347): 이메일 「웹에서 보기」·리포트 창 「새 탭에서 열기」는 새 탭으로 열려 돌아갈 기록이 없다.
  // 예전엔 「뒤로」가 navigate(-1) 뿐이라 눌러도 무반응(빈 탭에서 연 경우 about:blank 로 앱 이탈)이었다.
  test('새 탭처럼 바로 연 리포트에서는 「리포트 목록」 버튼이 리포트 목록으로 보낸다 (WD-347)', async ({
    authenticatedPage: page,
  }) => {
    await setupReportViewerMocks(page, 1, 1);
    await mockApi(page, 'GET', '/api/v1/proactive/reports', [createReport()]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 3 })]);

    await page.goto('/ai-insights/jobs/1/executions/1/report');

    // 돌아갈 기록이 없으니 「뒤로」 대신 어디로 가는지 이름에 드러낸다
    await expect(page.getByRole('button', { name: '뒤로' })).toHaveCount(0);
    await page.getByRole('button', { name: '리포트 목록' }).click();

    await expect(page).toHaveURL(/\/ai-insights\/reports$/);
    await expect(page.getByText('8월 화재 통계 리포트')).toBeVisible();
  });

  test('앱 안에서 들어온 리포트의 「뒤로」는 들어온 화면으로 돌아간다 (WD-347)', async ({
    authenticatedPage: page,
  }) => {
    const report = createReport();
    await mockApi(page, 'GET', '/api/v1/proactive/reports', [report]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: report.jobId })]);
    await setupReportViewerMocks(page, report.jobId, report.executionId);

    // 리포트 목록 → 행을 눌러 뷰어 진입(앱 안 이동이라 기록이 있다)
    await page.goto('/ai-insights/reports?from=list');
    await page.getByText(report.title).click();
    await expect(page).toHaveURL(
      new RegExp(`/ai-insights/jobs/${report.jobId}/executions/${report.executionId}/report$`),
    );

    await page.getByRole('button', { name: '뒤로' }).click();
    // 목록 경로로 새로 가는 게 아니라 기록 한 칸 뒤 — 들어온 주소(쿼리 포함) 그대로다
    await expect(page).toHaveURL(/\/ai-insights\/reports\?from=list$/);
  });
});
