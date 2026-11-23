/**
 * 분석·AI 폼 필수 칸 접근성 E2E (WD-964)
 *
 * 대시보드 만들기·설정, 차트 빌더 저장, AI 인라인 차트 저장 대화상자의 이름 칸이 별표 글자 대신
 * aria-required="true" 로 필수를 알리고, 별표는 aria-hidden 으로 접근 이름에서 빠지는지(+ 기존 색 유지) 고정한다.
 * native required 는 쓰지 않는다(WD-908·928).
 */
import type { Page } from '@playwright/test';

import { createDashboardListItem } from '../../factories/analytics.factory';
import { setupDashboardListMocks, setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { expectRequiredField } from '../../fixtures/required-a11y';

test.describe('분석·AI 폼 필수 칸 aria-required (WD-964)', () => {
  test('대시보드 만들기 이름 칸', async ({ authenticatedPage: page }) => {
    await setupDashboardListMocks(page, 1);
    await page.goto('/analytics/dashboards');
    await page.getByRole('button', { name: '새 대시보드' }).click();
    const dialog = page.getByRole('dialog');
    await expectRequiredField(dialog.getByRole('textbox', { name: '이름', exact: true }), '이름');
    // 선택 칸(설명)에는 주지 않는다
    await expect(dialog.getByRole('textbox', { name: '설명' })).not.toHaveAttribute('aria-required', 'true');
  });

  test('대시보드 설정(수정) 이름 칸', async ({ authenticatedPage: page }) => {
    const dashboard = createDashboardListItem({ id: 1, name: '수정 대상' });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse([dashboard]));
    await page.goto('/analytics/dashboards');
    await page.getByRole('row', { name: /수정 대상/ }).getByLabel('설정').click();
    const dialog = page.getByRole('dialog', { name: '대시보드 설정' });
    await expectRequiredField(dialog.getByRole('textbox', { name: '이름', exact: true }), '이름');
  });

  test('차트 빌더 저장 대화상자 이름 칸', async ({ authenticatedPage: page }) => {
    await setupNewChartBuilderMocks(page);
    await page.goto('/analytics/charts/new');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expectRequiredField(dialog.getByRole('textbox', { name: '이름', exact: true }), '이름');
  });

  test('AI 인라인 차트 저장 칸 — 별표는 aria-hidden 이고 빨간색이 유지된다', async ({ authenticatedPage: page }) => {
    await openInlineChartSaveDialog(page);
    const dialog = page.getByRole('dialog');
    const input = dialog.getByRole('textbox', { name: '차트 이름', exact: true });
    await expectRequiredField(input, '차트 이름');
    // 기존 text-destructive 색이 RequiredMark 로 옮겨도 그대로다 — 라벨 글자색과 달라야 한다
    const colors = await dialog.locator('label[for="chart-name"]').evaluate((label) => {
      const star = label.querySelector('[aria-hidden="true"]') as HTMLElement;
      return { star: getComputedStyle(star).color, label: getComputedStyle(label).color };
    });
    expect(colors.star).not.toBe(colors.label);
  });
});

/** SSE 이벤트 직렬화 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** 챗으로 인라인 차트를 그리고 「차트로 저장」 대화상자를 연다(chart-widget-save-config.spec.ts 와 같은 모킹) */
async function openInlineChartSaveDialog(page: Page) {
  const events = [
    sseEvent({ type: 'init', sessionId: 'req-mark-session' }),
    sseEvent({
      type: 'tool_use',
      toolName: 'mcp__firehub__show_chart',
      input: {
        title: '지역별 합계',
        sql: 'SELECT area, sum FROM t',
        chartType: 'BAR',
        config: { xAxis: 'area', yAxis: ['sum'] },
        columns: ['area', 'sum'],
        rows: [{ area: '서울', sum: 120 }],
      },
      status: 'started',
    }),
    sseEvent({ type: 'tool_result', toolName: 'mcp__firehub__show_chart', result: JSON.stringify({ displayed: true }), status: 'completed' }),
    sseEvent({ type: 'done', inputTokens: 1 }),
  ];
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    (route) =>
      route.request().method() !== 'POST'
        ? route.fallback()
        : route.fulfill({ status: 200, headers: { 'Content-Type': 'text/event-stream' }, body: events.join('') }),
  );
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) =>
      route.request().method() === 'GET'
        ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
        : route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ id: 1, sessionId: 'req-mark-session', title: null, createdAt: '2026-10-20T00:00:00Z', updatedAt: '2026-10-20T00:00:00Z' }),
          }),
  );
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.waitFor({ state: 'visible', timeout: 5000 });
  await chatInput.fill('지역별 합계 차트로 보여줘');
  await chatInput.press('Enter');
  await expect(page.getByTestId('inline-chart-title')).toHaveText('지역별 합계', { timeout: 10_000 });
  await page.getByTestId('ai-side-panel').getByRole('button', { name: '차트로 저장' }).click();
}
