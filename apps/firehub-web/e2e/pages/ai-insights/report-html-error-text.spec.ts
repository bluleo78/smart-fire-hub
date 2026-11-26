import type { Page } from '@playwright/test';

import { createJob, createJobExecution, createMessage, createReport } from '../../factories/ai-insight.factory';
import { setupJobListMocks, setupNotificationPanelMocks, setupReportViewerMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트 HTML 조회 오류 안내 E2E (WD-1098·WD-1177)
 * - 리포트 뷰어와 같은 규칙(reportHtmlErrorText)을 모달·실행 상세도 쓰는지 검증한다.
 * - 이전에는 모달이 오류를 「리포트가 없습니다.」로 뭉뚱그리고, 실행 상세는 빈 영역이었다.
 * - WD-1177: 등급 차단·권한 403 이 캐시된 HTML·요약·폴백보다 앞서는지, 차단·빈 상태에서 인쇄·PDF 가 잠기는지 본다.
 */

const HTML_MISSING = '이 실행에는 HTML 리포트가 없습니다.';
const LOAD_ERROR = '리포트를 불러올 수 없습니다.';
/** 캐시에 남아 있는 HTML — 재조회가 오류여도 이 본문이 남아 있는 상황(WD-1177 리뷰 후속) 재현용 */
const CACHED_HTML = '<html><body>리포트 본문</body></html>';

/**
 * 뷰어에서 캐시 HTML(200)을 먼저 확보한 뒤 목록으로 나간다 (WD-1177 리뷰 후속 재현 준비).
 * 앱 안 이동이라 캐시가 남아, 재진입 때 재조회를 오류로 바꿔 캐시와 오류가 겹치는 상황을 만들 수 있다.
 */
async function openViewerThenLeave(page: Page, report: ReturnType<typeof createReport>) {
  await mockApi(page, 'GET', '/api/v1/proactive/reports', [report]);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: report.jobId })]);
  await setupReportViewerMocks(page, report.jobId, report.executionId, CACHED_HTML);

  await page.goto('/ai-insights/reports?from=list');
  await page.getByText(report.title).click();
  await expect(page.locator('iframe')).toHaveCount(1);

  await page.getByRole('button', { name: '뒤로' }).click();
  await expect(page).toHaveURL(/\/ai-insights\/reports\?from=list$/);
}

/**
 * 캐시 HTML 을 확보한 뒤 재조회를 status 오류로 바꿔 뷰어로 재진입하고, 오류가 확정될 때까지 기다린다 (WD-1177).
 * 404·500 은 전역 재시도로 한 번 더 부르니 두 오류 응답을 다 받아야 오류 상태가 된다(403 은 재시도 없이 한 번).
 * staleTime(30초) 을 넘기는 가짜 시계는 앱 캐시 설정을 건드리지 않고 재조회만 유도한다.
 */
async function reenterViewerWithHtmlError(
  page: Page,
  report: ReturnType<typeof createReport>,
  status: number,
  message: string,
) {
  const htmlErrors: number[] = [];
  page.on('response', (res) => {
    if (res.url().endsWith('/html') && res.status() >= 400) htmlErrors.push(res.status());
  });
  // 같은 html 경로를 오류로 다시 등록 — 나중에 등록한 목이 이긴다
  await mockApi(
    page,
    'GET',
    `/api/v1/proactive/jobs/${report.jobId}/executions/${report.executionId}/html`,
    { status, error: 'Error', message },
    { status },
  );
  await page.clock.fastForward(31_000);
  await page.getByText(report.title).click();

  // 재시도가 끝날 때까지 가짜 시계를 굴려 오류 상태를 확정한다 — 이 시점부터 오류 블록이 뜰 수 있다
  await expect
    .poll(async () => {
      await page.clock.fastForward(1_500);
      return htmlErrors.length;
    })
    .toBeGreaterThanOrEqual(2);
}

/** 한 화면에 한 상태 — 오류 블록은 없고 캐시 iframe 만 있으며, 본문이 남으니 인쇄·PDF 도 쓸 수 있다 (WD-1177). */
async function expectOnlyCachedIframe(page: Page) {
  await expect(page.locator('iframe')).toHaveCount(1);
  await expect(page.getByTestId('report-not-found')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '인쇄' })).toBeEnabled();
  await expect(page.getByRole('button', { name: 'PDF' })).toBeEnabled();
}

/**
 * 알림 목·작업 목록 목을 건 뒤 알림 패널 → 메시지 → 「리포트 보기」로 모달을 연다.
 * execution·html 응답은 부르는 쪽이 먼저 건다(오류 응답 모양이 시험마다 달라서).
 */
async function openModalViaNotification(page: Page) {
  await setupNotificationPanelMocks(page, [
    createMessage({ jobId: 1, executionId: 3, title: '오류 안내 리포트', read: false }),
  ]);
  await setupJobListMocks(page);
  await page.goto('/ai-insights/jobs');
  await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
  await page.getByRole('button', { name: /오류 안내 리포트/ }).click();
  await page.getByRole('button', { name: '리포트 보기' }).click();
}

/**
 * 리포트 모달을 연다 — execution·html 응답을 먼저 건 뒤 openModalViaNotification 로 모달을 연다.
 * options.htmlBody 를 주면 html 200 본문 자체로 쓴다(캐시 HTML 재조회 재현용),
 * options.code 는 403 응답에 담을 등급 차단 코드다(WD-1177).
 */
async function openReportModal(
  page: Page,
  execution: ReturnType<typeof createJobExecution>,
  htmlStatus: number,
  htmlMessage: string,
  options?: { htmlBody?: unknown; code?: string },
) {
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/3', execution);
  await mockApi(
    page,
    'GET',
    '/api/v1/proactive/jobs/1/executions/3/html',
    options?.htmlBody ?? {
      status: htmlStatus,
      error: 'Error',
      message: htmlMessage,
      ...(options?.code ? { code: options.code } : {}),
    },
    { status: htmlStatus },
  );
  await openModalViaNotification(page);
}

/**
 * 실행 상세 페이지용 목 — execution 과 html 오류 응답을 건다.
 * options.result 로 result 만 바꾼 실행을(기본은 요약 없는 {}), options.execution 으로 통째 실행을 넣을 수 있다(WD-1177).
 */
async function mockDetail(
  page: Page,
  htmlStatus: number,
  htmlMessage: string,
  code?: string,
  options?: { result?: Record<string, unknown> | null; execution?: ReturnType<typeof createJobExecution> },
) {
  const execution =
    options?.execution ??
    createJobExecution({
      id: 5,
      jobId: 1,
      status: 'COMPLETED',
      result: options && 'result' in options ? options.result : {},
    });
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5', execution);
  await mockApi(
    page,
    'GET',
    '/api/v1/proactive/jobs/1/executions/5/html',
    { status: htmlStatus, error: 'Error', message: htmlMessage, ...(code ? { code } : {}) },
    { status: htmlStatus },
  );
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
}

test.describe('리포트 HTML 조회 오류 안내 (WD-1098)', () => {
  test('모달 — 실행 중 실행의 HTML 400 은 서버 문구를 보인다', async ({ authenticatedPage: page }) => {
    const msg = '완료된 실행만 리포트를 열거나 내려받을 수 있습니다.';
    await openReportModal(
      page,
      createJobExecution({ id: 3, jobId: 1, status: 'RUNNING', result: null, completedAt: null }),
      400,
      msg,
    );
    const dialog = page.getByRole('dialog').last();
    await expect(dialog.getByText(msg)).toBeVisible();
    await expect(dialog.getByText('리포트가 없습니다.')).toHaveCount(0);
  });

  test('모달 — HTML 500 은 고정 문구를 보이고 서버 원문은 숨긴다', async ({ authenticatedPage: page }) => {
    await openReportModal(
      page,
      createJobExecution({ id: 3, jobId: 1, status: 'COMPLETED', result: null }),
      500,
      'NullPointerException at X',
    );
    const dialog = page.getByRole('dialog').last();
    await expect(dialog.getByText(LOAD_ERROR)).toBeVisible();
    await expect(page.getByText('NullPointerException')).toHaveCount(0);
  });

  test('모달 — HTML 없음 404 이고 결과가 있으면 마크다운 폴백을 보인다', async ({ authenticatedPage: page }) => {
    await openReportModal(
      page,
      createJobExecution({ id: 3, jobId: 1, status: 'COMPLETED', result: { summary: '이번 주 화재 출동 12건' } }),
      404,
      HTML_MISSING,
    );
    const dialog = page.getByRole('dialog').last();
    await expect(dialog.getByText('이번 주 화재 출동 12건')).toBeVisible();
    await expect(dialog.getByText(HTML_MISSING)).toHaveCount(0);
  });

  test('모달 — HTML 없음 404 이고 결과가 없으면 서버 문구를 보인다', async ({ authenticatedPage: page }) => {
    await openReportModal(
      page,
      createJobExecution({ id: 3, jobId: 1, status: 'COMPLETED', result: null }),
      404,
      HTML_MISSING,
    );
    await expect(page.getByRole('dialog').last().getByText(HTML_MISSING)).toBeVisible();
  });

  test('실행 상세 — 섹션 없는 완료 실행의 HTML 없음 404 는 서버 문구를 보인다', async ({ authenticatedPage: page }) => {
    await mockDetail(page, 404, HTML_MISSING);
    await page.goto('/ai-insights/jobs/1/executions/5');
    await expect(page.getByTestId('execution-report-html-error')).toHaveText(HTML_MISSING);
  });

  test('실행 상세 — HTML 500 이고 섹션이 없으면 고정 문구', async ({ authenticatedPage: page }) => {
    await mockDetail(page, 500, 'NullPointerException at X');
    await page.goto('/ai-insights/jobs/1/executions/5');
    await expect(page.getByTestId('execution-report-html-error')).toHaveText(LOAD_ERROR);
    await expect(page.getByText('NullPointerException')).toHaveCount(0);
  });

  test('모달 — result 가 {} 이고 HTML 500 이어도 빈 화면이 아니라 고정 문구를 보인다', async ({ authenticatedPage: page }) => {
    await openReportModal(
      page,
      createJobExecution({ id: 3, jobId: 1, status: 'COMPLETED', result: {} }),
      500,
      'NullPointerException at X',
    );
    await expect(page.getByRole('dialog').last().getByText(LOAD_ERROR)).toBeVisible();
  });

  test('모달 — HTML 403(등급 코드 없음)은 권한 안내를 보이고 고정 문구는 숨긴다', async ({ authenticatedPage: page }) => {
    await openReportModal(
      page,
      createJobExecution({ id: 3, jobId: 1, status: 'COMPLETED', result: null }),
      403,
      'Forbidden',
    );
    const dialog = page.getByRole('dialog').last();
    await expect(dialog.getByTestId('report-modal-forbidden')).toBeVisible();
    await expect(dialog.getByText(LOAD_ERROR)).toHaveCount(0);
  });

  test('실행 상세 — HTML 403 등급 차단 코드면 등급 안내를 보인다', async ({ authenticatedPage: page }) => {
    await mockDetail(page, 403, '현재 보안 등급으로 열 수 없는 리포트입니다', 'REPORT_SECURITY_LEVEL_BLOCKED');
    await page.goto('/ai-insights/jobs/1/executions/5');
    await expect(page.getByTestId('execution-report-level-blocked')).toBeVisible();
    await expect(page.getByTestId('execution-report-html-error')).toHaveCount(0);
  });

  test('실행 상세 — HTML 403(등급 코드 없음)은 권한 안내를 보인다', async ({ authenticatedPage: page }) => {
    await mockDetail(page, 403, 'Forbidden');
    await page.goto('/ai-insights/jobs/1/executions/5');
    await expect(page.getByTestId('execution-report-forbidden')).toBeVisible();
    await expect(page.getByText(LOAD_ERROR)).toHaveCount(0);
  });

  // ── 리뷰 후속 (PR #1436) ──
  test('모달 — HTML 403(등급 코드 없음)이면 결과가 있어도 마크다운 폴백 없이 권한 안내만 보인다', async ({ authenticatedPage: page }) => {
    await openReportModal(
      page,
      createJobExecution({ id: 3, jobId: 1, status: 'COMPLETED', result: { summary: '이번 주 화재 출동 12건' } }),
      403,
      'Forbidden',
    );
    const dialog = page.getByRole('dialog').last();
    await expect(dialog.getByTestId('report-modal-forbidden')).toBeVisible();
    await expect(dialog.getByText('이번 주 화재 출동 12건')).toHaveCount(0);
  });

  test('모달 — HTML 403 등급 차단이면 결과가 있어도 마크다운 폴백 없이 등급 안내만 보인다', async ({ authenticatedPage: page }) => {
    await openReportModal(
      page,
      createJobExecution({ id: 3, jobId: 1, status: 'COMPLETED', result: { summary: '이번 주 화재 출동 12건' } }),
      403,
      '현재 보안 등급으로 열 수 없는 리포트입니다',
      { code: 'REPORT_SECURITY_LEVEL_BLOCKED' },
    );
    const dialog = page.getByRole('dialog').last();
    await expect(dialog.getByTestId('report-modal-level-blocked')).toBeVisible();
    await expect(dialog.getByText('이번 주 화재 출동 12건')).toHaveCount(0);
  });

  test('모달 — 실행 조회가 500 이면 HTML 없음 404 보다 실행 조회 오류(고정 문구)를 먼저 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1/executions/3',
      { status: 500, error: 'Internal Server Error', message: 'NullPointerException at Y' },
      { status: 500 },
    );
    // HTML 은 없음 404 — 실행(섹션)을 못 읽었으니 「HTML 리포트가 없습니다」로 단정하지 않고 실행 조회 오류를 보여야 한다
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1/executions/3/html',
      { status: 404, error: 'Not Found', message: HTML_MISSING },
      { status: 404 },
    );
    await openModalViaNotification(page);

    const dialog = page.getByRole('dialog').last();
    await expect(dialog.getByText(LOAD_ERROR)).toBeVisible({ timeout: 15000 });
    await expect(dialog.getByText(HTML_MISSING)).toHaveCount(0);
    await expect(page.getByText('NullPointerException')).toHaveCount(0);
  });

  test('실행 상세 — 결과가 없는(result null) 완료 실행도 HTML 을 조회해 서버 문구를 보인다', async ({ authenticatedPage: page }) => {
    // 서버는 결과 없는 실행의 HTML 을 400 「이 실행에는 리포트 결과가 없습니다.」로 거절한다(ProactiveJobController MSG_NO_RESULT)
    const noResult = '이 실행에는 리포트 결과가 없습니다.';
    await mockDetail(page, 400, noResult, undefined, { result: null });
    await page.goto('/ai-insights/jobs/1/executions/5');
    await expect(page.getByTestId('execution-report-html-error')).toHaveText(noResult);
  });

  test('모달 — 실행 조회가 등급 차단이면 HTML 이 읽혀도 리포트 없이 등급 안내만 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1/executions/3',
      { status: 403, error: 'Forbidden', message: '현재 보안 등급으로 열 수 없는 리포트입니다', code: 'REPORT_SECURITY_LEVEL_BLOCKED' },
      { status: 403 },
    );
    // HTML 은 (예: 등급이 낮아지기 전 캐시처럼) 읽힌다 — 등급 차단이 먼저라 리포트 본문을 보이지 않아야 한다
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/3/html', '<html><body>기밀 리포트 본문</body></html>');
    await openModalViaNotification(page);

    const dialog = page.getByRole('dialog').last();
    await expect(dialog.getByTestId('report-modal-level-blocked')).toBeVisible();
    await expect(dialog.locator('iframe')).toHaveCount(0);
  });

  // ── WD-1177 — 차단 403 은 캐시된 요약·폴백보다 앞선다(한 화면에서 한 상태만) ──
  test('실행 상세 — result 에 요약이 있어도 HTML 403 등급 차단이면 안내만 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockDetail(page, 403, '현재 보안 등급으로 열 수 없는 리포트입니다', 'REPORT_SECURITY_LEVEL_BLOCKED', {
      execution: createJobExecution({ id: 5, jobId: 1, status: 'COMPLETED', result: { summary: '이번 주 화재 출동 12건' } }),
    });
    await page.goto('/ai-insights/jobs/1/executions/5');
    await expect(page.getByTestId('execution-report-level-blocked')).toBeVisible();
    await expect(page.getByTestId('execution-summary')).toHaveCount(0);
    await expect(page.getByText('이번 주 화재 출동 12건')).toHaveCount(0);
  });

  test('실행 상세 — result 에 요약이 있어도 HTML 403(등급 코드 없음)이면 권한 안내만 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockDetail(page, 403, 'Forbidden', undefined, {
      execution: createJobExecution({ id: 5, jobId: 1, status: 'COMPLETED', result: { summary: '이번 주 화재 출동 12건' } }),
    });
    await page.goto('/ai-insights/jobs/1/executions/5');
    await expect(page.getByTestId('execution-report-forbidden')).toBeVisible();
    await expect(page.getByTestId('execution-summary')).toHaveCount(0);
    await expect(page.getByText('이번 주 화재 출동 12건')).toHaveCount(0);
  });

  test('실행 상세 — HTML 400 이어도 요약은 그대로 보인다(403 만 내용을 이긴다)', async ({
    authenticatedPage: page,
  }) => {
    await mockDetail(page, 400, '완료된 실행만 리포트를 열거나 내려받을 수 있습니다.', undefined, {
      execution: createJobExecution({ id: 5, jobId: 1, status: 'COMPLETED', result: { summary: '이번 주 화재 출동 12건' } }),
    });
    await page.goto('/ai-insights/jobs/1/executions/5');
    const summaryCard = page.getByTestId('execution-summary');
    await expect(summaryCard).toBeVisible();
    // 요약 카드 안에 실제 텍스트가 있는지 본다 — 폴백 섹션도 같은 문구를 그리므로 카드에 한정한다
    await expect(summaryCard.getByText('이번 주 화재 출동 12건')).toBeVisible();
  });

  test('실행 상세 — HTML 403 차단 때 수치 출처 경고 배너도 안 보인다 (WD-1177)', async ({
    authenticatedPage: page,
  }) => {
    // 배너가 담는 수치·문맥도 result 의 리포트 내용 — 차단 안내만 남겨야 한다(「안내만」)
    await mockDetail(page, 403, '현재 보안 등급으로 열 수 없는 리포트입니다', 'REPORT_SECURITY_LEVEL_BLOCKED', {
      execution: createJobExecution({
        id: 5,
        jobId: 1,
        status: 'COMPLETED',
        result: {
          summary: '이번 주 화재 출동 12건',
          numberCheck: { checked: true, unverifiedTotal: 1, unverified: [{ text: '12건', context: '리포트 요약' }] },
        },
      }),
    });
    await page.goto('/ai-insights/jobs/1/executions/5');
    await expect(page.getByTestId('execution-report-level-blocked')).toBeVisible();
    await expect(page.getByTestId('number-check-banner')).toHaveCount(0);
  });

  // WD-1177 — HTML 로딩 중·조회 꺼짐에서는 「리포트가 없습니다」 카드가 뜨지 않는다(중요)
  test('실행 상세 — HTML 조회가 끝나기 전에는 빈 상태 카드를 보이지 않는다 (WD-1177)', async ({
    authenticatedPage: page,
  }) => {
    // result 없는 완료 실행 + html 응답 지연 — 로딩 중에 「리포트가 없습니다」 가 잠깐이라도 뜨면 안 된다
    await mockDetail(page, 404, HTML_MISSING, undefined, {
      execution: createJobExecution({ id: 5, jobId: 1, status: 'COMPLETED', result: null }),
    });
    await page.route('**/jobs/1/executions/5/html', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 1500));
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ status: 404, error: 'Not Found', message: HTML_MISSING }),
      });
    });
    await page.goto('/ai-insights/jobs/1/executions/5');
    // 실행 메타가 그려진 뒤 = HTML 응답 오기 전 창 — 이 창에 「리포트가 없습니다」 카드가 있으면 안 된다.
    // 메타를 먼저 기다리지 않으면 카드가 아직 안 그려진 시점에 세어 0 으로 통과해(가짜 통과) 회귀를 놓친다(WD-1177 리뷰).
    await expect(page.getByText(`실행 #5`)).toBeVisible();
    await expect(page.getByTestId('execution-report-html-error')).toHaveCount(0);
    // 응답이 온 뒤에는 서버 문구 카드가 뜬다
    await expect(page.getByTestId('execution-report-html-error')).toHaveText(HTML_MISSING, { timeout: 15000 });
  });

  // ── WD-1177 — 캐시된 HTML 뒤 재조회가 403 이면 권한 안내만 — 캐시가 안내를 덮지 않는다 ──
  test('모달 — 캐시된 HTML 뒤 재조회가 403 이면 권한 안내만 보인다 (WD-1177)', async ({ authenticatedPage: page }) => {
    const CACHED_HTML = '<html><body>리포트 본문</body></html>';
    // staleTime(30초) 을 넘겨 재조회를 유도하기 위한 가짜 시계 — 앱 캐시 설정은 건드리지 않는다
    await page.clock.install();
    await openReportModal(
      page,
      createJobExecution({ id: 3, jobId: 1, status: 'COMPLETED', result: null }),
      200,
      '',
      { htmlBody: CACHED_HTML },
    );
    const dialog = page.getByRole('dialog').last();
    // 먼저 HTML 리포트가 캐시돼 iframe 으로 보인다
    await expect(dialog.locator('iframe')).toHaveCount(1);

    // 리포트 창을 닫으면 알림 패널도 함께 닫힌다(WD-422) — 재개는 벨 → 메시지 → 리포트 보기
    await dialog.getByTitle('닫기').click();

    // 같은 html 경로를 403 으로 다시 등록 — 나중에 등록한 목이 이긴다
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1/executions/3/html',
      { status: 403, error: 'Error', message: 'Forbidden' },
      { status: 403 },
    );
    // staleTime 30초를 넘겨 재조회가 403 을 가져오게 한다
    await page.clock.fastForward(31_000);

    await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
    await page.getByRole('button', { name: /오류 안내 리포트/ }).click();
    await page.getByRole('button', { name: '리포트 보기' }).click();
    const modal = page.getByRole('dialog').last();

    await expect(modal.getByTestId('report-modal-forbidden')).toBeVisible();
    // 캐시 HTML 이 권한 안내를 덮지 않는다 — iframe 도 본문 문구도 없다
    await expect(modal.locator('iframe')).toHaveCount(0);
    await expect(modal.getByText('리포트 본문')).toHaveCount(0);
    // 권한이 막혔으니 인쇄·PDF 도 쓸 수 없다
    await expect(modal.getByTitle('인쇄')).toBeDisabled();
    await expect(modal.getByTitle('PDF 다운로드')).toBeDisabled();
  });

  // WD-1177 — 뷰어도 같은 시나리오: 캐시된 HTML 을 갖고 있다가 재조회가 403 이면 안내만 — iframe 은 없다.
  test('뷰어 — 캐시된 HTML 뒤 재조회가 403 이면 권한 안내만 보이고 iframe 이 없다 (WD-1177)', async ({
    authenticatedPage: page,
  }) => {
    const CACHED_HTML = '<html><body>리포트 본문</body></html>';
    const report = createReport({ jobId: 1, executionId: 1 });
    // staleTime(30초) 을 넘겨 재조회를 유도하기 위한 가짜 시계 — 앱 캐시 설정은 건드리지 않는다
    await page.clock.install();
    await mockApi(page, 'GET', '/api/v1/proactive/reports', [report]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: report.jobId })]);
    await setupReportViewerMocks(page, report.jobId, report.executionId, CACHED_HTML);

    // 목록 → 뷰어 앱 안 이동 — 먼저 캐시 HTML 이 iframe 으로 보인다(전제)
    await page.goto('/ai-insights/reports?from=list');
    await page.getByText(report.title).click();
    await expect(page.locator('iframe')).toHaveCount(1);

    // 뒤로 목록으로 나간다 — 앱 안 이동이라 캐시가 남아 재진입 때 재조회가 일어난다(새로 읽기면 캐시가 비어 403 이 첫 응답이 된다)
    await page.getByRole('button', { name: '뒤로' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/reports\?from=list$/);

    // 같은 html 경로를 403 으로 다시 등록 — 나중에 등록한 목이 이긴다
    await mockApi(
      page,
      'GET',
      `/api/v1/proactive/jobs/${report.jobId}/executions/${report.executionId}/html`,
      { status: 403, error: 'Error', message: 'Forbidden' },
      { status: 403 },
    );
    // staleTime 30초를 넘겨 재조회가 403 을 가져오게 한다
    await page.clock.fastForward(31_000);

    await page.getByText(report.title).click();

    // 재조회 403 — 권한 안내만, 캐시 HTML iframe 은 없다(403 안내가 캐시보다 앞선다)
    await expect(page.getByTestId('report-forbidden')).toBeVisible();
    await expect(page.getByTestId('report-level-blocked')).toHaveCount(0);
    await expect(page.locator('iframe')).toHaveCount(0);
  });

  // ── WD-1177 — 차단·빈 상태에서는 인쇄·PDF 를 쓸 수 없다(내용이 있어야 눌린다) ──
  test('모달 — 차단·빈 상태에서 인쇄·PDF 가 비활성이다 (WD-1177)', async ({ authenticatedPage: page }) => {
    // ① 등급 차단 403 + result 요약 — 등급 안내만, 요약·폴백 없음, 두 버튼 잠김
    await openReportModal(
      page,
      createJobExecution({ id: 3, jobId: 1, status: 'COMPLETED', result: { summary: '이번 주 화재 출동 12건' } }),
      403,
      '현재 보안 등급으로 열 수 없는 리포트입니다',
      { code: 'REPORT_SECURITY_LEVEL_BLOCKED' },
    );
    const dialog = page.getByRole('dialog').last();
    await expect(dialog.getByTestId('report-modal-level-blocked')).toBeVisible();
    await expect(dialog.getByText('이번 주 화재 출동 12건')).toHaveCount(0);
    await expect(dialog.getByTitle('인쇄')).toBeDisabled();
    await expect(dialog.getByTitle('PDF 다운로드')).toBeDisabled();
    await dialog.getByTitle('닫기').click();

    // ② HTML 없음 404 + result 없음 — 서버 문구(기존 규칙 유지)와 함께 두 버튼 잠김
    await openReportModal(
      page,
      createJobExecution({ id: 3, jobId: 1, status: 'COMPLETED', result: null }),
      404,
      HTML_MISSING,
    );
    const dialog2 = page.getByRole('dialog').last();
    await expect(dialog2.getByText(HTML_MISSING)).toBeVisible();
    // exact 로 한정한다 — 서버 문구(「…리포트가 없습니다.」)에 같은 부분 문자열이 있어 부분 일치로는 자기 자신과 겹친다
    await expect(dialog2.getByText('리포트가 없습니다.', { exact: true })).toHaveCount(0);
    await expect(dialog2.getByTitle('인쇄')).toBeDisabled();
    await expect(dialog2.getByTitle('PDF 다운로드')).toBeDisabled();
  });

  // ── WD-1177 리뷰 후속 ② — 뷰어 404·500 재조회는 오류 블록 대신 캐시 iframe 만 그린다 ──
  test('뷰어 — 캐시된 HTML 뒤 재조회가 404 면 오류 블록 없이 캐시 iframe 만 보인다 (WD-1177)', async ({
    authenticatedPage: page,
  }) => {
    await page.clock.install();
    const report = createReport({ jobId: 1, executionId: 1 });
    await openViewerThenLeave(page, report);
    await reenterViewerWithHtmlError(page, report, 404, HTML_MISSING);

    // 「403 밖 오류는 캐시 내용이 이김」 — 오류 블록은 캐시 iframe 을 덮지 못한다
    await expectOnlyCachedIframe(page);
    await expect(page.getByText(HTML_MISSING)).toHaveCount(0);
    await expect(page.getByText(LOAD_ERROR)).toHaveCount(0);
  });

  test('뷰어 — 캐시된 HTML 뒤 재조회가 500 이면 오류 블록 없이 캐시 iframe 만 보인다 (WD-1177)', async ({
    authenticatedPage: page,
  }) => {
    await page.clock.install();
    const report = createReport({ jobId: 1, executionId: 1 });
    await openViewerThenLeave(page, report);
    await reenterViewerWithHtmlError(page, report, 500, 'NullPointerException at X');

    await expectOnlyCachedIframe(page);
    // 500 은 고정 문구로도, 서버 원문으로도 오류 블록이 떠선 안 된다 — 캐시 본문만 보인다
    await expect(page.getByText(LOAD_ERROR)).toHaveCount(0);
    await expect(page.getByText('NullPointerException')).toHaveCount(0);
  });
});
