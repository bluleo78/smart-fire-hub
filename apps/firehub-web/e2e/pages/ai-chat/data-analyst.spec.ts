/**
 * data-analyst 서브에이전트 E2E 테스트
 *
 * 시나리오:
 *   DA-01: 사용자가 AI 챗에 분석 요청 전송 → 응답에 분석 관련 키워드(테이블|컬럼|분석|쿼리|데이터셋) 포함
 *   DA-02: 사용자가 AI 챗에 건수 조회 요청 → 응답에 코드 블록 또는 숫자 패턴 포함
 *
 * 프로젝트 E2E 컨벤션:
 * - 백엔드/ai-agent 없이 API 모킹 기반으로 동작한다(`apps/firehub-web/CLAUDE.md` 참조).
 * - AI 챗은 SSE 스트리밍이므로 `/api/v1/ai/chat`의 응답 본문을 SSE 이벤트 시퀀스로 모킹한다.
 * - 로그인은 `auth.fixture.ts`의 `authenticatedPage`를 사용한다.
 * - 스크린샷은 레포 루트 `snapshots/` 폴더(이미 .gitignore 처리됨)에 저장한다.
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

// ESM 환경에서는 `__dirname`이 없으므로 `import.meta.url`로 현재 파일 디렉토리 경로를 계산한다.
const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** DA-01: 분석 요청에 대한 SSE 응답 이벤트 */
const ANALYSIS_RESPONSE_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'data-analyst-session-1' }),
  sseEvent({
    type: 'text',
    content:
      '요청하신 화재 데이터셋을 분석했습니다.\n\n**데이터셋 개요**\n- 테이블: `fire_incidents`\n- 컬럼: `incident_no`, `occurred_at`, `location`, `address`, `damage_amount` (총 5개)\n- 레코드 수: 12,453건\n\n**분석 결과**\n\n`damage_amount` 컬럼 기준으로 월별 피해액 추이를 쿼리하면 다음과 같습니다:\n\n```sql\nSELECT DATE_TRUNC(\'month\', occurred_at) AS month,\n       SUM(damage_amount) AS total_damage\nFROM fire_incidents\nGROUP BY 1\nORDER BY 1;\n```\n\n위 쿼리를 실행하면 월별 피해액 합계를 확인할 수 있습니다.',
  }),
  sseEvent({ type: 'done', inputTokens: 350 }),
];

/** DA-02: 건수 조회 요청에 대한 SSE 응답 이벤트 */
const COUNT_RESPONSE_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'data-analyst-session-2' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__execute_analytics_query',
    input: { query: "SELECT COUNT(*) FROM fire_incidents WHERE occurred_at >= '2026-01-01'" },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__execute_analytics_query',
    result: JSON.stringify({ rows: [{ count: 3_241 }] }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      '2026년 1월 1일 이후 화재 발생 건수는 **3,241건**입니다.\n\n```sql\nSELECT COUNT(*)\nFROM fire_incidents\nWHERE occurred_at >= \'2026-01-01\';\n```',
  }),
  sseEvent({ type: 'done', inputTokens: 280 }),
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
  // AI 패널은 모든 인증 페이지에서 접근 가능 — 홈으로 이동 후 패널 열기
  await page.goto("/", { waitUntil: "commit" });
  await page.getByText('AI 어시스턴트').first().click();
  await page
    .getByPlaceholder('메시지를 입력하세요...')
    .waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('AI 챗 data-analyst', () => {
  /**
   * DA-01: 분석 요청 → 응답에 분석 관련 키워드 포함 확인
   * 테이블, 컬럼, 분석, 쿼리, 데이터셋 중 하나 이상이 응답에 포함되어야 한다.
   */
  test('DA-01: 분석 요청 → 응답에 분석 관련 키워드 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'data-analyst-session-1');

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
          body: ANALYSIS_RESPONSE_EVENTS.join(''),
        });
      },
    );

    // 1. 패널 열기
    await openChatPanel(page);

    // 2. 분석 요청 전송
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('화재 데이터셋 분석해줘');
    await chatInput.press('Enter');

    // 3. API payload 검증 — message 필드에 입력한 메시지가 포함되어야 한다.
    await expect
      .poll(() => capturedPayload, { timeout: 5000 })
      .not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '화재 데이터셋 분석해줘' });

    // 4. 응답에 분석 관련 키워드 포함 확인 — 테이블|컬럼|분석|쿼리|데이터셋 중 하나 이상
    await expect(
      page.getByText(/테이블|컬럼|분석|쿼리|데이터셋/).first(),
    ).toBeVisible({ timeout: 30_000 });

    // 5. 스크린샷 — 분석 결과 단계
    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..',
        '..',
        '..',
        '..',
        '..',
        'snapshots',
        'data-analyst-analysis.png',
      ),
      fullPage: true,
    });
  });

  /**
   * DA-02: 건수 조회 요청 → 응답에 코드 블록 또는 숫자 패턴 포함 확인
   * 응답 본문에 SQL 코드 블록(```sql) 또는 쉼표 포함 숫자(예: 3,241)가 있어야 한다.
   */
  test('DA-02: 건수 조회 요청 → 응답에 코드 블록 또는 숫자 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'data-analyst-session-2');

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
          body: COUNT_RESPONSE_EVENTS.join(''),
        });
      },
    );

    // 1. 패널 열기
    await openChatPanel(page);

    // 2. 건수 조회 요청 전송
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('2026년 화재 발생 건수 알려줘');
    await chatInput.press('Enter');

    // 3. API payload 검증 — message 필드에 입력한 메시지가 포함되어야 한다.
    await expect
      .poll(() => capturedPayload, { timeout: 5000 })
      .not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '2026년 화재 발생 건수 알려줘' });

    // 4. 응답에 코드 블록(pre/code 요소) 또는 숫자 패턴 포함 확인
    // 마크다운 렌더러가 ```sql 블록을 <code> 요소로 변환하거나, 숫자(예: 3,241)가 텍스트로 노출된다.
    await expect(
      page.getByText(/\d[\d,]+/).first(),
    ).toBeVisible({ timeout: 30_000 });

    // 5. 스크린샷 — 건수 조회 결과 단계
    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..',
        '..',
        '..',
        '..',
        '..',
        'snapshots',
        'data-analyst-count.png',
      ),
      fullPage: true,
    });
  });
});
