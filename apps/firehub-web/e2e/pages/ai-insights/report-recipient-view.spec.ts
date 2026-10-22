import path from 'node:path';

import type { Page } from '@playwright/test';

import { createJobExecution, createMessage } from '../../factories/ai-insight.factory';
import { setupJobListMocks, setupNotificationPanelMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 리포트를 받은 사람(작업 작성자가 아님)의 화면 E2E (WD-162).
 * - 서버는 리포트 전문(실행 상세·html)을 작업 작성자에게만 열어 준다. 예전 화면은 받은 사람에게도 「리포트 보기」를 그려
 *   눌러도 「리포트가 없습니다」만 보였다(리포트는 실제로 있음).
 * - 이제 메시지 응답의 canViewReport=false 면 버튼 대신 요약과 「이 리포트 전문은 작업을 만든 사람만 열 수 있습니다」 안내를 보인다.
 * - 작성자(canViewReport=true)는 지금처럼 「리포트 보기」로 리포트를 연다(회귀 금지).
 * - 뷰어 페이지를 주소로 직접 열어 404 를 받으면, 서버가 존재 여부를 숨기므로 두 경우를 함께 말하는 안내를 보인다.
 * - WD162_SHOT_DIR 을 주면 화면 시안 스크린샷을 그 폴더에 남긴다(평소 실행에서는 찍지 않는다).
 */

const OWNER_ONLY_NOTICE = '이 리포트 전문은 작업을 만든 사람만 열 수 있습니다.';
const REPORT_NOT_FOUND = '리포트를 찾을 수 없습니다. 리포트 전문은 작업을 만든 사람만 열 수 있습니다.';
const EXECUTION_NOT_FOUND = '실행 정보를 찾을 수 없습니다. 실행 결과는 작업을 만든 사람만 열 수 있습니다.';
const NOT_FOUND_BODY = { status: 404, error: 'Not Found', message: 'Proactive Job을 찾을 수 없습니다: 7' };
const SERVER_ERROR_BODY = { status: 500, error: 'Internal Server Error', message: '서버 오류' };

const SUMMARY = '이번 주 화재 출동은 지난주보다 12% 늘었습니다.';

/** 환경 변수로 지정한 폴더에만 스크린샷을 남긴다 — 평소 E2E 실행이 저장소 밖 경로에 의존하지 않게 한다. */
async function shot(page: Page, name: string) {
  const dir = process.env.WD162_SHOT_DIR;
  if (!dir) return;
  // 모달 열림 애니메이션 도중에 찍으면 반투명 화면이 남는다 — 끝이 있는 애니메이션이 끝난 뒤 찍는다(무한 반복 표시등은 제외)
  await page.waitForFunction(() =>
    document
      .getAnimations()
      .every((a) => a.playState !== 'running' || a.effect?.getComputedTiming().endTime === Infinity),
  );
  await page.screenshot({ path: path.join(dir, name) });
}

/** 알림 벨 → 패널 → 메시지 상세로 들어간다. */
async function openMessageDetail(page: Page, title: string) {
  await page.goto('/ai-insights/jobs');
  await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
  await expect(page.getByRole('dialog', { name: 'AI 인사이트 알림' })).toBeVisible();
  await page.getByRole('button', { name: new RegExp(title) }).click();
}

test.describe('리포트를 받은 사람의 화면 (WD-162)', () => {
  test('받은 사람(canViewReport=false)은 「리포트 보기」 대신 요약과 안내 문구를 본다', async ({
    authenticatedPage: page,
  }) => {
    await setupNotificationPanelMocks(page, [
      createMessage({
        jobId: 7,
        executionId: 3,
        title: '주간 화재 현황',
        jobName: 'writer_주간현황',
        content: { summary: SUMMARY, jobId: '7', executionId: '3' },
        canViewReport: false,
      }),
    ]);
    await setupJobListMocks(page);
    // 받은 사람 화면은 리포트 전문을 부르지 않아야 한다 — 부르면 서버가 404 를 준다
    const html = await mockApi(page, 'GET', '/api/v1/proactive/jobs/7/executions/3/html', NOT_FOUND_BODY, {
      status: 404,
      capture: true,
    });

    await openMessageDetail(page, '주간 화재 현황');

    const panel = page.getByRole('dialog', { name: 'AI 인사이트 알림' });
    // 요약은 그대로 보인다
    await expect(panel.getByText(SUMMARY)).toBeVisible();
    // 전문을 열 수 없다는 사실을 알린다
    await expect(panel.getByTestId('report-owner-only-notice')).toHaveText(OWNER_ONLY_NOTICE);
    // 눌러도 「리포트가 없습니다」만 보이던 버튼은 없다
    await expect(panel.getByRole('button', { name: '리포트 보기' })).toHaveCount(0);
    // 후속 질문 경로는 그대로 남는다
    await expect(panel.getByRole('button', { name: 'AI에게 물어보기' })).toBeVisible();
    await expect(page.getByText('리포트가 없습니다.')).toHaveCount(0);
    expect(html.requests).toHaveLength(0);

    await shot(page, 'after-recipient-notification-panel.png');
  });

  test('작성자(canViewReport=true)는 「리포트 보기」로 리포트를 연다', async ({ authenticatedPage: page }) => {
    await setupNotificationPanelMocks(page, [
      createMessage({
        jobId: 7,
        executionId: 3,
        title: '주간 화재 현황',
        jobName: 'writer_주간현황',
        content: { summary: SUMMARY, jobId: '7', executionId: '3' },
        canViewReport: true,
      }),
    ]);
    await setupJobListMocks(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/7/executions/3',
      createJobExecution({ id: 3, jobId: 7, status: 'COMPLETED' }),
    );
    const html = await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/7/executions/3/html',
      '<html><body><h1>주간 화재 현황 본문</h1></body></html>',
      { capture: true },
    );

    await openMessageDetail(page, '주간 화재 현황');

    const panel = page.getByRole('dialog', { name: 'AI 인사이트 알림' });
    // 작성자에게는 안내를 보이지 않는다
    await expect(panel.getByTestId('report-owner-only-notice')).toHaveCount(0);
    await shot(page, 'after-owner-notification-panel.png');

    await panel.getByRole('button', { name: '리포트 보기' }).click();

    // 모달이 이 메시지의 작업·실행 리포트를 불러와 본문을 그린다
    const modal = page.getByRole('dialog', { name: '리포트 #3' });
    await expect(modal).toBeVisible();
    await expect(modal.frameLocator('iframe[title="리포트"]').getByRole('heading', { name: '주간 화재 현황 본문' })).toBeVisible();
    await expect(modal.getByText('리포트가 없습니다.')).toHaveCount(0);
    expect(html.requests.length).toBeGreaterThan(0);

    await shot(page, 'after-owner-report-modal.png');
  });

  test('뷰어 페이지를 받은 사람이 주소로 열어 404 면 두 경우를 함께 말하는 안내를 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/7/executions/3/html', NOT_FOUND_BODY, { status: 404 });

    await page.goto('/ai-insights/jobs/7/executions/3/report');

    await expect(page.getByTestId('report-not-found')).toContainText(REPORT_NOT_FOUND);
    // 일시 오류처럼 읽히는 문구·빈 상태 문구는 보이지 않는다
    await expect(page.getByText('리포트를 불러올 수 없습니다.')).toHaveCount(0);
    await expect(page.getByText('리포트가 없습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '돌아가기' })).toBeVisible();
    await expect(page.getByRole('button', { name: '인쇄' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'PDF' })).toBeDisabled();

    await shot(page, 'after-report-viewer-404.png');
  });

  test('뷰어 페이지가 404 가 아닌 오류(500)면 기존 「불러올 수 없습니다」를 유지한다(대조군)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/7/executions/3/html', SERVER_ERROR_BODY, { status: 500 });

    await page.goto('/ai-insights/jobs/7/executions/3/report');

    await expect(page.getByText('리포트를 불러올 수 없습니다.')).toBeVisible();
    await expect(page.getByTestId('report-not-found')).toHaveCount(0);
  });

  test('실행 상세 페이지도 404 면 같은 이유를 함께 말한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/7/executions/3', NOT_FOUND_BODY, { status: 404 });

    await page.goto('/ai-insights/jobs/7/executions/3');

    await expect(page.getByTestId('execution-not-found')).toHaveText(EXECUTION_NOT_FOUND);

    await shot(page, 'after-execution-detail-404.png');
  });
});
