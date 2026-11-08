import { createJobExecution, createMessage } from '../../factories/ai-insight.factory';
import { setupJobListMocks, setupNotificationPanelMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 닫힌 리포트 모달은 조회하지 않는다 (WD-667)
 *
 * 리포트 모달은 「리포트 보기」 버튼 옆에 늘 마운트돼 있다. 예전엔 HTML 조회만 open 으로 막고 실행 조회(useExecution)는
 * 닫혀 있어도 돌아, 실행 중(RUNNING)이면 5초마다 폴링했다 — 리포트 메시지 N개가 N개의 요청을 계속 보냈다.
 */
test.describe('리포트 모달 조회 시점 (WD-667)', () => {
  test('메시지 상세를 열어도 모달을 열기 전에는 실행·HTML 을 조회하지 않고, 열면 조회한다', async ({
    authenticatedPage: page,
  }) => {
    await setupNotificationPanelMocks(page, [
      createMessage({ jobId: 1, executionId: 3, title: '실행 중 리포트', read: false }),
    ]);
    await setupJobListMocks(page);
    const execution = await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1/executions/3',
      createJobExecution({ id: 3, jobId: 1, status: 'RUNNING', result: { summary: '집계 중' } }),
      { capture: true },
    );
    const html = await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/3/html', null, { capture: true });

    await page.goto('/ai-insights/jobs');
    await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
    await page.getByRole('button', { name: /실행 중 리포트/ }).click();
    const openButton = page.getByRole('button', { name: '리포트 보기' });
    await expect(openButton).toBeVisible();

    // 닫힌 모달 — 폴링 한 주기(5초)를 넘겨 기다려도 요청이 없어야 한다
    await page.waitForTimeout(6_000);
    expect(execution.requests).toHaveLength(0);
    expect(html.requests).toHaveLength(0);

    // 열면 이 메시지의 실행·HTML 을 부른다
    await openButton.click();
    await expect(page.getByRole('dialog', { name: /리포트 #3/ })).toBeVisible();
    await expect.poll(() => execution.requests.length).toBeGreaterThan(0);
    await expect.poll(() => html.requests.length).toBeGreaterThan(0);
  });
});
