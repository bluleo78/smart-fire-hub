import type { Page } from '@playwright/test';

import { createJobExecution, createMessage } from '../../factories/ai-insight.factory';
import { setupNotificationPanelMocks } from '../../fixtures/ai-insight.fixture';
import { setupJobListMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * AI 알림 패널(AINotificationPanel) E2E 테스트
 * - 알림 벨 클릭 → 패널 열기 → 메시지 선택 → DetailView 내 "리포트 보기" 버튼 노출 검증
 * - Bug #177: message.content.jobId 대신 message.jobId(최상위 필드)로 읽도록 수정된 동작 회귀 방지
 */

test.describe('AI 알림 패널', () => {
  /**
   * jobId + executionId가 있는 메시지를 선택하면 "리포트 보기" 버튼이 노출된다.
   * (Bug #177: message.content.jobId → message.jobId 수정 후 동작 검증)
   */
  test('jobId/executionId 있는 메시지 선택 시 "리포트 보기" 버튼이 표시된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    // jobId=1, executionId=1 포함된 메시지 모킹
    await setupNotificationPanelMocks(page, [
      createMessage({ jobId: 1, executionId: 1, title: '현황 리포트 완료', read: false }),
    ]);
    await setupJobListMocks(page);

    await page.goto('/ai-insights/jobs');

    // 알림 벨 클릭하여 패널 열기
    await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();

    // 패널이 열리고 메시지가 표시되는지 확인
    await expect(page.getByRole('dialog', { name: 'AI 인사이트 알림' })).toBeVisible();
    await expect(page.getByText('현황 리포트 완료')).toBeVisible();

    // 메시지 클릭하여 DetailView 진입
    await page.getByRole('button', { name: /현황 리포트 완료/ }).click();

    // "리포트 보기" 버튼이 표시되어야 한다
    await expect(page.getByRole('button', { name: '리포트 보기' })).toBeVisible();
  });

  /**
   * jobId 또는 executionId가 null인 메시지는 "리포트 보기" 버튼을 표시하지 않는다.
   */
  test('jobId/executionId 없는 메시지 선택 시 "리포트 보기" 버튼이 숨겨진다', async ({ authenticatedPage: page }) => {
    // jobId=null, executionId=null 메시지 모킹
    await setupNotificationPanelMocks(page, [
      createMessage({ jobId: null, executionId: null, title: '채널 설정 안내', read: false }),
    ]);
    await setupJobListMocks(page);

    await page.goto('/ai-insights/jobs');

    await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
    await expect(page.getByRole('dialog', { name: 'AI 인사이트 알림' })).toBeVisible();
    await page.getByRole('button', { name: /채널 설정 안내/ }).click();

    // "리포트 보기" 버튼이 없어야 한다
    await expect(page.getByRole('button', { name: '리포트 보기' })).toHaveCount(0);

    // "AI에게 물어보기" 버튼은 항상 표시되어야 한다
    await expect(page.getByRole('button', { name: 'AI에게 물어보기' })).toBeVisible();
  });

  /** WD-198: 리포트 등급 차단(403 + 코드)이면 리포트 모달이 「리포트가 없습니다」 대신 등급 안내를 보인다. */
  test('리포트 등급 차단이면 리포트 모달에 등급 안내가 보인다', async ({ authenticatedPage: page }) => {
    await setupNotificationPanelMocks(page, [
      createMessage({ jobId: 1, executionId: 9, title: '등급 리포트', read: false }),
    ]);
    await setupJobListMocks(page);
    const blocked = {
      status: 403,
      error: 'Forbidden',
      code: 'REPORT_SECURITY_LEVEL_BLOCKED',
      message: '현재 보안 등급으로 열 수 없는 리포트입니다',
    };
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/9', blocked, { status: 403 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/9/html', blocked, { status: 403 });

    await page.goto('/ai-insights/jobs');
    await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
    await page.getByRole('button', { name: /등급 리포트/ }).click();
    await page.getByRole('button', { name: '리포트 보기' }).click();

    await expect(page.getByTestId('report-modal-level-blocked')).toHaveText(
      '현재 보안 등급으로 열 수 없는 리포트입니다',
    );
    await expect(page.getByText('리포트가 없습니다.')).toHaveCount(0);
  });

  /**
   * WD-198: 연결된 리포트의 등급이 내 현재 보안 등급보다 높으면 서버가 요약을 비우고 reportLevelBlocked=true 로 준다.
   * 목록 미리보기·상세 모두 「내용이 없습니다」가 아니라 등급 안내를 보이고, 리포트 보기·작성자 전용 안내는 보이지 않는다.
   */
  test('리포트 등급 때문에 요약이 비워진 알림은 등급 안내를 보인다', async ({ authenticatedPage: page }) => {
    await setupNotificationPanelMocks(page, [
      createMessage({
        id: 7,
        jobId: 1,
        executionId: 9,
        title: '민감 리포트 알림',
        content: { title: '민감 리포트 알림', executionId: '9', jobId: '1' },
        canViewReport: false,
        reportLevelBlocked: true,
      }),
    ]);
    await setupJobListMocks(page);

    await page.goto('/ai-insights/jobs');
    await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
    const item = page.getByRole('button', { name: /민감 리포트 알림/ });
    await expect(item).toContainText('현재 보안 등급으로 열 수 없는 리포트입니다');
    await item.click();

    await expect(page.getByTestId('message-level-blocked')).toHaveText('현재 보안 등급으로 열 수 없는 리포트입니다');
    await expect(page.getByText('내용이 없습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '리포트 보기' })).toHaveCount(0);
    await expect(page.getByTestId('report-owner-only-notice')).toHaveCount(0);
  });
  /**
   * WD-350: 리포트 창은 포털로 패널 밖(body 아래)에 그려진다. 예전에는 창 안의 mousedown 을 패널 바깥 클릭으로 봐
   * 패널과 창이 함께 닫혀, 마우스로 누른 PDF·새 탭·실행 상세 보기가 실행되지 않았다(키보드 Enter 만 동작).
   */
  test.describe('알림 패널에서 연 리포트 창 — 마우스 조작 (WD-350)', () => {
    async function openReportModal(page: Page) {
      await setupNotificationPanelMocks(page, [
        createMessage({ jobId: 1, executionId: 3, title: '마크다운 리포트', read: false }),
      ]);
      await setupJobListMocks(page);
      await mockApi(
        page,
        'GET',
        '/api/v1/proactive/jobs/1/executions/3',
        createJobExecution({ id: 3, jobId: 1, status: 'COMPLETED', result: { summary: '이번 주 화재 출동 12건' } }),
      );
      await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/3/html', null);

      await page.goto('/ai-insights/jobs');
      await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
      await page.getByRole('button', { name: /마크다운 리포트/ }).click();
      await page.getByRole('button', { name: '리포트 보기' }).click();
      const modal = page.getByRole('dialog', { name: /리포트 #3/ });
      await expect(modal.getByText('이번 주 화재 출동 12건')).toBeVisible();
      return modal;
    }

    test('창 안 여백을 눌러도 창과 알림 패널이 그대로 남는다', async ({ authenticatedPage: page }) => {
      const modal = await openReportModal(page);
      await modal.getByText('이번 주 화재 출동 12건').click();

      await expect(modal).toBeVisible();
      // 리포트 창이 열린 동안 패널은 aria-hidden(모달 밖)이라 역할로 찾지 않고 화면에 남았는지만 본다
      await expect(page.locator('[role="dialog"][aria-label="AI 인사이트 알림"]')).toBeVisible();
    });

    test('창 밖(패널도 아닌 곳)을 누르면 알림 패널이 닫힌다 — 바깥 클릭 닫기는 그대로', async ({ authenticatedPage: page }) => {
      await setupNotificationPanelMocks(page, [createMessage({ jobId: 1, executionId: 3, title: '마크다운 리포트', read: false })]);
      await setupJobListMocks(page);
      await page.goto('/ai-insights/jobs');
      await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
      const panel = page.getByRole('dialog', { name: 'AI 인사이트 알림' });
      await expect(panel).toBeVisible();

      await page.mouse.click(5, 400);

      await expect(panel).toHaveCount(0);
    });

    test('「PDF 다운로드」를 마우스로 누르면 PDF 를 요청하고 창은 열린 채다', async ({ authenticatedPage: page }) => {
      const modal = await openReportModal(page);
      const pdf = await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/3/pdf', '%PDF-1.4', {
        capture: true,
        headers: { 'Content-Type': 'application/pdf' },
      });
      const download = page.waitForEvent('download');

      await modal.getByRole('button', { name: 'PDF 다운로드' }).click();

      // 요청만이 아니라 실제 내려받기까지 일어난다
      expect((await download).suggestedFilename()).toBe('report-3.pdf');
      expect(pdf.requests).toHaveLength(1);
      await expect(modal).toBeVisible();
    });

    test('「새 탭에서 열기」를 마우스로 누르면 리포트 뷰어가 새 탭으로 열린다', async ({ authenticatedPage: page }) => {
      const modal = await openReportModal(page);
      const popup = page.context().waitForEvent('page');

      await modal.getByRole('link', { name: '새 탭에서 열기' }).click();

      const tab = await popup;
      expect(new URL(tab.url()).pathname).toBe('/ai-insights/jobs/1/executions/3/report');
      await tab.close();
    });

    test('「실행 상세 보기」를 마우스로 누르면 실행 상세로 이동한다', async ({ authenticatedPage: page }) => {
      const modal = await openReportModal(page);

      await modal.getByRole('button', { name: /실행 상세 보기/ }).click();

      await expect(page).toHaveURL(/\/ai-insights\/jobs\/1\/executions\/3$/);
    });

    /**
     * WD-343: HTML 리포트가 없는 실행(마크다운 폴백)의 리포트 창에서 「인쇄」는 앱 화면 전체(window.print)가 아니라
     * 리포트 본문만 담은 임시 문서를 인쇄한다. WD-350 으로 창 안 마우스 클릭이 동작하므로 마우스로 누른다.
     */
    test('마크다운 폴백 리포트의 인쇄는 앱 화면이 아니라 본문만 인쇄한다 (WD-343)', async ({ authenticatedPage: page }) => {
      // 모든 프레임의 print 를 가로채 기록한다(실제 인쇄 창은 띄우지 않는다) — 최상위 창이면 앱 화면 인쇄,
      // 하위 프레임(인쇄용 임시 iframe)이면 그 문서 본문을 최상위 창에 남긴다. addInitScript 는 srcdoc 프레임에도 실행된다.
      await page.addInitScript(() => {
        type Rec = Window & { __windowPrinted?: number; __printDocs?: string[] };
        const top = window.top as Rec;
        if (window === window.top) {
          top.__windowPrinted = 0;
          top.__printDocs = [];
        }
        window.print = () => {
          if (window === window.top) {
            top.__windowPrinted! += 1;
          } else {
            const el = window.frameElement;
            top.__printDocs?.push(`${el?.getAttribute('sandbox')}|${document.body?.innerHTML ?? ''}`);
          }
        };
      });
      const modal = await openReportModal(page);

      await modal.getByRole('button', { name: '인쇄' }).click();

      type PrintRec = Window & { __windowPrinted?: number; __printDocs?: string[] };
      await expect.poll(() => page.evaluate(() => (window as PrintRec).__printDocs ?? [])).toHaveLength(1);
      const { docs, windowPrinted } = await page.evaluate(() => {
        const w = window as PrintRec;
        return { docs: w.__printDocs ?? [], windowPrinted: w.__windowPrinted };
      });
      const [doc] = docs;
      // 스크립트 차단 sandbox + 리포트 본문만(사이드바·알림 패널 문구 없음)
      expect(doc.startsWith('allow-same-origin allow-modals|')).toBe(true);
      expect(doc).toContain('이번 주 화재 출동 12건');
      expect(doc).not.toContain('AI 인사이트 알림');
      expect(windowPrinted).toBe(0);
      await expect(modal).toBeVisible();
    });
  });
});
