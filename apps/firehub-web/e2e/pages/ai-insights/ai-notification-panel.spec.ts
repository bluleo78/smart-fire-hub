import { createMessage } from '../../factories/ai-insight.factory';
import { setupNotificationPanelMocks } from '../../fixtures/ai-insight.fixture';
import { setupJobListMocks } from '../../fixtures/ai-insight.fixture';
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
});
