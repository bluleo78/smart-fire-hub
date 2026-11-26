import type { Page } from '@playwright/test';

import { createJobExecution, createMessage } from '../../factories/ai-insight.factory';
import { setupJobListMocks, setupNotificationPanelMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트 HTML 조회 오류 안내 E2E (WD-1098)
 * - 리포트 뷰어와 같은 규칙(reportHtmlErrorText)을 모달·실행 상세도 쓰는지 검증한다.
 * - 이전에는 모달이 오류를 「리포트가 없습니다.」로 뭉뚱그리고, 실행 상세는 빈 영역이었다.
 */

const HTML_MISSING = '이 실행에는 HTML 리포트가 없습니다.';
const LOAD_ERROR = '리포트를 불러올 수 없습니다.';

/** 알림 패널 → 메시지 → 「리포트 보기」로 리포트 모달을 연다. execution·html 응답은 인자로 정한다. */
async function openReportModal(
  page: Page,
  execution: ReturnType<typeof createJobExecution>,
  htmlStatus: number,
  htmlMessage: string,
) {
  await setupNotificationPanelMocks(page, [
    createMessage({ jobId: 1, executionId: 3, title: '오류 안내 리포트', read: false }),
  ]);
  await setupJobListMocks(page);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/3', execution);
  await mockApi(
    page,
    'GET',
    '/api/v1/proactive/jobs/1/executions/3/html',
    { status: htmlStatus, error: 'Error', message: htmlMessage },
    { status: htmlStatus },
  );

  await page.goto('/ai-insights/jobs');
  await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
  await page.getByRole('button', { name: /오류 안내 리포트/ }).click();
  await page.getByRole('button', { name: '리포트 보기' }).click();
}

/** 실행 상세 페이지용 목 — execution 과 html 오류 응답을 건다. */
async function mockDetail(
  page: Page,
  htmlStatus: number,
  htmlMessage: string,
  code?: string,
) {
  await mockApi(
    page,
    'GET',
    '/api/v1/proactive/jobs/1/executions/5',
    createJobExecution({ id: 5, jobId: 1, status: 'COMPLETED', result: {} }),
  );
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
});
