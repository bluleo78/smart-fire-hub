/**
 * AI 채팅 도구 호출 — 정책 차단("차단됨") 상태 E2E (S3 §5-4, WD-39)
 *
 * ai-agent 는 api 의 403 POLICY_BLOCKED 를 도구 결과 텍스트 첫 줄의 JSON 표식으로 싣는다
 * (firehub-ai-agent `mcp/policy-blocked.ts` policyBlockedResultText). 웹은 그 표식을 읽어
 * 실패(빨강)가 아닌 세 번째 상태 "차단됨"과 등급 사유 한 줄을 그린다. 일반 오류는 그대로 "✗ 실패".
 *
 * POLICY_BLOCKED_SHOT_DIR 환경변수가 있으면 라이트·다크 스크린샷을 그 디렉터리에 남긴다(디자인 검토용).
 */

import path from 'node:path';

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

/** SSE 이벤트 직렬화 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** AI 세션 API 모킹 — message-bubble-coverage.spec.ts 와 같은 구현(그 파일에서 export 되지 않음) */
async function mockAiSessions(page: Page, sessionId: string) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1,
          sessionId,
          title: null,
          createdAt: '2026-10-09T00:00:00Z',
          updatedAt: '2026-10-09T00:00:00Z',
        }),
      });
    },
  );
}

/** AI 패널 열기 헬퍼 — message-bubble-coverage.spec.ts 와 같은 구현 */
async function openChatPanel(page: Page) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });
}

/** ai-agent policyBlockedResultText 와 같은 키·순서의 표식. 뒤에 힌트 블록을 덧붙여 파서가 첫 줄만 읽는지 방어적으로 확인한다(실제 차단 결과에는 힌트가 붙지 않는다). */
function policyBlockedResult(action: 'AI' | 'SHARE', levelName: string): string {
  const marker = JSON.stringify({
    policyBlocked: true,
    code: 'POLICY_BLOCKED',
    action,
    levelName,
    policyKey: action === 'AI' ? 'ai_policy' : 'share_policy',
    message: `'${levelName}' 등급 데이터는 현재 AI 공급자로 보낼 수 없습니다`,
  });
  return `${marker}\n\n[힌트] 같은 데이터셋을 다시 조회해도 결과는 같습니다`;
}

/** 도구 호출 하나(tool_use → tool_result)로 끝나는 채팅 응답을 모킹하고 메시지를 보낸다. */
async function sendWithToolResult(page: Page, sessionId: string, result: string, isError: boolean) {
  await mockAiSessions(page, sessionId);
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      await route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
        body: [
          sseEvent({ type: 'init', sessionId }),
          sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__list_datasets', input: {} }),
          sseEvent({ type: 'tool_result', toolName: 'mcp__firehub__list_datasets', result, isError }),
          sseEvent({ type: 'text', content: '응답을 마쳤습니다.' }),
          sseEvent({ type: 'done', inputTokens: 10 }),
        ].join(''),
      });
    },
  );
  await openChatPanel(page);
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.fill('데이터셋 보여줘');
  await chatInput.press('Enter');
  await expect(page.getByText('응답을 마쳤습니다.').first()).toBeVisible({ timeout: 10_000 });
}

test.describe('AI 채팅 — 정책 차단 도구 호출 (S3 §5-4)', () => {
  test('PB-01: AI 정책 차단 결과는 "차단됨"과 등급 사유를 보이고 "실패"·"완료"로 보이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await sendWithToolResult(page, 'pb-01', policyBlockedResult('AI', '민감'), true);

    const row = page.getByTestId('tool-call');
    await expect(row).toHaveCount(1);
    await expect(row).toContainText('데이터셋 목록');
    await expect(row).toContainText('차단됨');
    await expect(row).toContainText("'민감' 등급 — 현재 AI 공급자로 보낼 수 없음");
    await expect(row).not.toContainText('✗ 실패');
    await expect(row).not.toContainText('✓ 완료');
    // 차단은 정책 상태다 — 오류색이 아니라 경고색으로 그린다(색 의미 구분)
    await expect(row.getByText('차단됨')).toHaveClass(/text-warning/);
    // 재시도 버튼을 두지 않는다
    await expect(row.getByRole('button')).toHaveCount(0);

    const shotDir = process.env.POLICY_BLOCKED_SHOT_DIR;
    if (shotDir) {
      await page.emulateMedia({ colorScheme: 'light' });
      await row.scrollIntoViewIfNeeded();
      await page.screenshot({ path: path.join(shotDir, 'policy-blocked-light.png') });
      await row.screenshot({ path: path.join(shotDir, 'policy-blocked-row-light.png') });
    }
  });

  test('PB-02: 공유 정책 차단은 공유·외부 발송 불가 사유를 보이고, isError 가 없어도 차단으로 그린다', async ({
    authenticatedPage: page,
  }) => {
    // 런타임(cli·opencode)에 따라 isError 가 전달되지 않을 수 있다 — 표식만으로 판정해야 한다
    await sendWithToolResult(page, 'pb-02', policyBlockedResult('SHARE', '기밀'), false);

    const row = page.getByTestId('tool-call');
    await expect(row).toContainText('차단됨');
    await expect(row).toContainText("'기밀' 등급 — 공유·외부 발송 불가");
    await expect(row).not.toContainText('✓ 완료');
  });

  test('PB-03: 다크 모드에서도 "차단됨"과 사유가 보인다', async ({ authenticatedPage: page }) => {
    await page.emulateMedia({ colorScheme: 'dark' });
    await sendWithToolResult(page, 'pb-03', policyBlockedResult('AI', '민감'), true);

    await expect(page.locator('html')).toHaveClass(/dark/);
    const row = page.getByTestId('tool-call');
    await expect(row.getByText('차단됨')).toBeVisible();
    await expect(row).toContainText("'민감' 등급 — 현재 AI 공급자로 보낼 수 없음");

    const shotDir = process.env.POLICY_BLOCKED_SHOT_DIR;
    if (shotDir) {
      await row.scrollIntoViewIfNeeded();
      await page.screenshot({ path: path.join(shotDir, 'policy-blocked-dark.png') });
      await row.screenshot({ path: path.join(shotDir, 'policy-blocked-row-dark.png') });
    }
  });

  test('PB-04: 일반 오류는 여전히 "✗ 실패"로 보이고 "차단됨"은 없다(대조군)', async ({ authenticatedPage: page }) => {
    await sendWithToolResult(page, 'pb-04', 'API 오류 (500): boom', true);

    const row = page.getByTestId('tool-call');
    await expect(row).toContainText('✗ 실패');
    await expect(row).not.toContainText('차단됨');
    await expect(row).not.toContainText('등급 —');
  });
});
