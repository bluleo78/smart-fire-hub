/**
 * api-connection-manager 서브에이전트 E2E 테스트
 * API 연결 생성/삭제 확인 흐름의 AI 채팅 인터랙션을 검증한다.
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

// ESM 환경에서는 `__dirname`이 없으므로 `import.meta.url`로 현재 파일 디렉토리 경로를 계산한다.
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// SSE 이벤트 생성 헬퍼 (기존 패턴 동일)
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** ACM-01: API 연결 생성 요청에 대한 SSE 응답 이벤트 */
const CREATE_CONNECTION_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'acm-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__create_api_connection',
    input: {
      name: '공공데이터포털',
      authType: 'API_KEY',
      authConfig: { apiKey: '***', headerName: 'Authorization' },
    },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__create_api_connection',
    result: JSON.stringify({ id: 5, name: '공공데이터포털', authType: 'API_KEY' }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      "'공공데이터포털' 연결이 등록되었습니다 (ID: 5, 인증방식: API_KEY).\n파이프라인 API_CALL 스텝에서 이 연결을 사용할 수 있습니다.",
  }),
  sseEvent({ type: 'done' }),
];

/** ACM-02: 연결 삭제 요청에 대한 SSE 응답 이벤트 */
const DELETE_CONNECTION_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'acm-session-2' }),
  sseEvent({
    type: 'text',
    content:
      "'공공데이터포털' 연결(ID: 5)을 삭제하면 이 연결을 사용하는 파이프라인이 동작하지 않습니다. 계속할까요?",
  }),
  sseEvent({ type: 'done' }),
];

/** AI 세션 목록/생성 API 모킹 */
async function mockAiSessions(page: Page, sessionId: string) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([]),
        });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1,
          sessionId,
          title: null,
          createdAt: '2026-04-12T00:00:00Z',
          updatedAt: '2026-04-12T00:00:00Z',
        }),
      });
    },
  );
}

/**
 * AI 챗 사이드 패널을 연다.
 * 레이아웃 헤더의 "AI 어시스턴트" 칩 클릭으로 AIProvider의 사이드 패널이 열린다.
 * (칩 label과 sr-only 텍스트 2개가 매치되므로 first()를 사용한다)
 */
async function openChatPanel(page: Page) {
  await page.getByText('AI 어시스턴트').first().click();
  await page
    .getByPlaceholder('메시지를 입력하세요...')
    .waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('AI 챗 api-connection-manager', () => {
  /**
   * ACM-01: API 연결 생성 요청 → payload에 '공공데이터포털' 포함 + 응답에 등록 완료 메시지 확인
   * 연결이 등록되었습니다|API_KEY|ID: 5 중 하나 이상이 응답에 포함되어야 한다.
   */
  test('ACM-01: API 연결 생성 요청 → 연결 등록 완료 응답 확인', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'acm-session-1');

    // POST body 캡처 — 사용자 메시지가 AI 챗 API에 정확히 전달되는지 검증한다.
    let capturedPayload: Record<string, unknown> | null = null;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
        await route.fulfill({
          status: 200,
          headers: {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
          },
          body: CREATE_CONNECTION_EVENTS.join(''),
        });
      },
    );

    // 1. 패널 열기
    await openChatPanel(page);

    // 2. 연결 생성 요청 전송
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('공공데이터포털 API 키 연결 등록해줘');
    await chatInput.press('Enter');

    // 3. API payload 검증 — message 필드에 '공공데이터포털'이 포함되어야 한다.
    await expect
      .poll(() => capturedPayload, { timeout: 5000 })
      .not.toBeNull();
    expect(String((capturedPayload as unknown as Record<string, unknown>)?.message ?? '')).toMatch(
      /공공데이터포털/,
    );

    // 4. 응답에 등록 완료 관련 텍스트 확인 — 연결이 등록되었습니다|API_KEY|ID: 5 중 하나 이상
    await expect(
      page.getByText(/연결이 등록되었습니다|API_KEY|ID: 5/).first(),
    ).toBeVisible({ timeout: 30_000 });

    // 5. 스크린샷 — 연결 등록 완료 단계
    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..',
        '..',
        '..',
        '..',
        '..',
        'snapshots',
        'api-connection-manager-create.png',
      ),
      fullPage: true,
    });
  });

  /**
   * ACM-02: 연결 삭제 요청 → payload에 '삭제' 포함 + 응답에 확인 메시지 확인
   * 계속할까요|동작하지 않습니다 중 하나 이상이 응답에 포함되어야 한다.
   */
  test('ACM-02: 연결 삭제 요청 → 삭제 확인 응답 확인', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'acm-session-2');

    // POST body 캡처 — 사용자 메시지가 AI 챗 API에 정확히 전달되는지 검증한다.
    let capturedPayload: Record<string, unknown> | null = null;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
        await route.fulfill({
          status: 200,
          headers: {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
          },
          body: DELETE_CONNECTION_EVENTS.join(''),
        });
      },
    );

    // 1. 패널 열기
    await openChatPanel(page);

    // 2. 연결 삭제 요청 전송
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('공공데이터포털 연결 삭제해줘');
    await chatInput.press('Enter');

    // 3. API payload 검증 — message 필드에 '삭제'가 포함되어야 한다.
    await expect
      .poll(() => capturedPayload, { timeout: 5000 })
      .not.toBeNull();
    expect(String((capturedPayload as unknown as Record<string, unknown>)?.message ?? '')).toMatch(/삭제/);

    // 4. 응답에 삭제 확인 관련 텍스트 확인 — 계속할까요|동작하지 않습니다 중 하나 이상
    await expect(
      page.getByText(/계속할까요|동작하지 않습니다/).first(),
    ).toBeVisible({ timeout: 30_000 });

    // 5. 스크린샷 — 삭제 확인 단계
    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..',
        '..',
        '..',
        '..',
        '..',
        'snapshots',
        'api-connection-manager-delete.png',
      ),
      fullPage: true,
    });
  });
});
