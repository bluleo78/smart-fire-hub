import type { Page } from '@playwright/test';

import { createRole, createUser } from '../../factories/auth.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { expectDialogWithinViewportHeight, expectNoOuterScroll, wheelUntilInViewport } from '../../fixtures/dialog.fixture';

const DEFAULT_ROLES = [
  createRole({ id: 1, name: 'USER', description: '일반 사용자', isSystem: true }),
  createRole({ id: 2, name: 'ADMIN', description: '시스템 관리자', isSystem: true }),
];

/** 역할·사용자 목록을 모킹하고 /admin/users 에서 「멤버 추가」 대화상자를 열어 돌려준다 (WD-1140). 뷰포트는 호출 전에 정한다. */
async function openAddMemberDialog(page: Page, opts: { roles?: ReturnType<typeof createRole>[] } = {}) {
  await setupAdminAuth(page);
  await mockApi(page, 'GET', '/api/v1/roles', opts.roles ?? DEFAULT_ROLES);
  await mockApi(
    page,
    'GET',
    '/api/v1/users',
    createPageResponse([createUser({ id: 1, name: '양동희', username: 'owner@acme.io', email: 'owner@acme.io' })]),
  );
  await page.goto('/admin/users');
  await page.getByRole('button', { name: '멤버 추가' }).click();
  const dialog = page.getByRole('dialog', { name: '멤버 추가' });
  await expect(dialog).toBeVisible();
  return dialog;
}

/**
 * WD-1123: 대화상자 높이가 보이는 뷰포트(dvh) 안에 머문다.
 * - 호출처가 덮어쓴 max-h-[90vh] 는 모바일 주소창에 가려진 높이까지 포함해 대화상자가 잘렸다.
 * - 안쪽 영역이 스크롤하는 대화상자는 바깥도 overflow-y-auto 라 스크롤 창이 이중으로 생겼다.
 */
test.describe('대화상자 높이 — 보이는 뷰포트 기준 (WD-1123)', () => {
  test('낮은 뷰포트에서 멤버 추가 대화상자는 바깥이 아니라 안쪽 영역만 스크롤한다 (WD-1123)', async ({
    authenticatedPage: page,
  }) => {
    // WD-1140: 안쪽 영역의 min-h-24 바닥 때문에 420 같은 높이에서는 바깥이 스크롤하므로, 바닥이 필요 없는 520 으로 둔다.
    await page.setViewportSize({ width: 375, height: 520 });
    const dialog = await openAddMemberDialog(page);

    await expectDialogWithinViewportHeight(page, dialog);

    // 바깥은 스크롤 없이 하단 주 버튼이 이미 보이고, 안쪽 영역만 스크롤한다.
    await expect(dialog.locator('button[type="submit"]')).toBeInViewport({ ratio: 1 });
    const inner = await dialog
      .getByTestId('add-member-dialog-scroll')
      .evaluate((el) => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight }));
    expect(inner.scrollHeight).toBeGreaterThan(inner.clientHeight);
  });
  test('아주 낮은 높이(568×320)에서 멤버 추가 대화상자 하단 버튼에 바깥 스크롤로 닿는다 (WD-1140)', async ({
    authenticatedPage: page,
  }) => {
    // sm(640) 미만이라 하단 버튼이 세로로 쌓인다 — 640 폭에서는 버튼이 이미 보여 버그가 재현되지 않는다.
    await page.setViewportSize({ width: 568, height: 320 });
    const dialog = await openAddMemberDialog(page);

    // 안쪽 스크롤 영역은 min-h-24(96px) 아래로 접히지 않는다 — 열림 애니메이션 scale 을 피해 clientHeight 로 잰다.
    const scrollHeight = await dialog.getByTestId('add-member-dialog-scroll').evaluate((el) => el.clientHeight);
    expect(scrollHeight).toBeGreaterThanOrEqual(96);

    // 하단 버튼은 처음엔 뷰포트 밖이다 — 이 사전 단언이 없으면 시험이 공회전으로 통과할 수 있다.
    // 푸터는 sm 미만에서 flex-col-reverse 라 「취소」가 맨 아래에 놓인다 — 그 버튼이 대상이다.
    const primary = dialog.getByRole('button', { name: '취소' });
    await expect(primary).not.toBeInViewport();

    await wheelUntilInViewport(page, dialog, primary);
  });

  test('SQL 보기 창은 긴 SQL 이어도 뷰포트 높이의 60% 를 넘지 않는다 (WD-1140)', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const longSql = Array.from({ length: 200 }, (_, i) => `SELECT col_${i} FROM t_${i}`).join('\n');
    const sse = (d: Record<string, unknown>) => `data: ${JSON.stringify(d)}\n\n`;
    const events = [
      sse({ type: 'init', sessionId: 'sql-dialog-session-1' }),
      sse({
        type: 'tool_use',
        toolName: 'mcp__firehub__show_chart',
        input: {
          title: '지역별 합계',
          sql: longSql,
          chartType: 'BAR',
          config: { xAxis: 'area', yAxis: ['sum'] },
          columns: ['area', 'sum'],
          rows: [{ area: '서울', sum: 120 }],
        },
        status: 'started',
      }),
      sse({
        type: 'tool_result',
        toolName: 'mcp__firehub__show_chart',
        result: JSON.stringify({ displayed: true }),
        status: 'completed',
      }),
      sse({ type: 'done', inputTokens: 120 }),
    ];
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        return route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: events.join(''),
        });
      },
    );
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => {
        if (route.request().method() === 'GET') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 1,
            sessionId: 'sql-dialog-session-1',
            title: null,
            createdAt: '2026-10-20T00:00:00Z',
            updatedAt: '2026-10-20T00:00:00Z',
          }),
        });
      },
    );

    await page.goto('/', { waitUntil: 'commit' });
    await page.getByText('AI 어시스턴트').first().click();
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.waitFor({ state: 'visible', timeout: 5000 });
    await chatInput.fill('지역별 합계 차트로 보여줘');
    await chatInput.press('Enter');
    await page.getByTestId('inline-chart-sql-toggle').click();

    const content = page.getByTestId('inline-chart-sql-dialog-content');
    await expect(content).toBeVisible();
    const box = await content.boundingBox();
    expect(box!.height).toBeLessThanOrEqual(900 * 0.6 + 1);

    await expectNoOuterScroll(page.getByRole('dialog', { name: '실행 SQL' }));
  });
  test('보통 높이(1440×900)에서 멤버 추가 대화상자 바깥은 숨은 체크박스 때문에 스크롤하지 않는다 (WD-1140)', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    // 역할을 많이 두어 안쪽 목록이 실제로 스크롤하게 한다 — 숨은 input 이 클립을 벗어나는 경우를 잡기 위함.
    const roles = Array.from({ length: 30 }, (_, i) =>
      createRole({ id: i + 1, name: `ROLE_${i + 1}`, description: `역할 ${i + 1}`, isSystem: i < 2 }),
    );
    const dialog = await openAddMemberDialog(page, { roles });

    const inner = await dialog
      .getByTestId('add-member-dialog-scroll')
      .evaluate((el) => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight }));
    // 안쪽 목록이 실제로 넘쳐야 시험이 의미 있다.
    expect(inner.scrollHeight).toBeGreaterThan(inner.clientHeight);
    // 숨은 input(absolute)이 안쪽 영역에 클립되도록 영역이 relative 여야 바깥 scrollHeight 가 부풀지 않는다.
    await expectNoOuterScroll(dialog);
  });
  test('375×420 에서도 멤버 추가 대화상자 하단 버튼에 휠 스크롤로 닿는다 (WD-1140)', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 375, height: 420 });
    const dialog = await openAddMemberDialog(page);

    // 이 높이에서는 하단 버튼이 이미 보일 수 있어 사전 not.toBeInViewport 는 두지 않는다 — 닿는지만 본다.
    const bottom = dialog.getByRole('button', { name: '취소' });
    await wheelUntilInViewport(page, dialog, bottom);
  });
});
