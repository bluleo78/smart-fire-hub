/**
 * AI 채팅 화면 컨텍스트에 상세 대상 이름 싣기 E2E (WD-759)
 *
 * 결함: 데이터셋 상세 화면에서 보낸 질문의 screenContext 가 `데이터셋 상세 (ID: 19)` 뿐이라 에이전트가
 * 질문이 지금 보는 데이터셋과 관련 있는지 알 수 없었다.
 * 수정: 상세 화면이 이미 불러온 TanStack Query 캐시에서 이름을 읽어(새 요청 없이)
 * `데이터셋 상세 (ID: 19, 이름: 「…」)` 로 인용해 싣는다. 이름은 사용자 입력이라 줄바꿈·대괄호·인용 괄호를 지운다.
 * 이 spec 은 브라우저 → 채팅 요청 본문 연결만 본다 — 이름 정리(주입 방어) 경우는 screen-context.test.ts 단위 테스트가 맡는다.
 *
 * 프로젝트 E2E 컨벤션: API 모킹 기반, 로그인은 auth.fixture 의 authenticatedPage 사용.
 */

import type { Page } from '@playwright/test';

import { createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

const DATASET_ID = 19;

/** SSE 이벤트 직렬화 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** AI 세션 API 모킹 — 목록은 비우고 새 세션 저장은 성공시킨다(tool-call-policy-blocked.spec.ts 와 같은 구현). */
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
          createdAt: '2026-11-16T00:00:00Z',
          updatedAt: '2026-11-16T00:00:00Z',
        }),
      });
    },
  );
}

/**
 * POST /api/v1/ai/chat 을 가로채 요청 본문을 붙잡고 짧은 응답으로 끝낸다.
 * 붙잡은 본문은 돌려준 배열에 쌓인다.
 */
async function captureChatRequests(page: Page): Promise<Record<string, unknown>[]> {
  const bodies: Record<string, unknown>[] = [];
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      bodies.push(route.request().postDataJSON() as Record<string, unknown>);
      await route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
        body: [
          sseEvent({ type: 'init', sessionId: 'wd-759' }),
          sseEvent({ type: 'text', content: '확인했습니다.' }),
          sseEvent({ type: 'done', inputTokens: 10 }),
        ].join(''),
      });
    },
  );
  return bodies;
}

/** 데이터셋 상세 화면을 이름을 정해 모킹하고 연다 — 상세 모킹 뒤에 등록한 상세 응답이 이긴다. */
async function gotoDatasetDetail(page: Page, name: string) {
  await setupDatasetDetailMocks(page, DATASET_ID);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, createDatasetDetail({ id: DATASET_ID, name }));
  await page.goto(`/data/datasets/${DATASET_ID}`);
}

/** 현재 화면에서 AI 패널을 열고 메시지를 보낸 뒤 응답이 그려질 때까지 기다린다. */
async function sendFromCurrentScreen(page: Page, text: string) {
  await page.getByText('AI 어시스턴트').first().click();
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.waitFor({ state: 'visible', timeout: 5_000 });
  await chatInput.fill(text);
  await chatInput.press('Enter');
  await expect(page.getByText('확인했습니다.').first()).toBeVisible({ timeout: 10_000 });
}

test.describe('AI 채팅 화면 컨텍스트 — 상세 대상 이름 (WD-759)', () => {
  test('데이터셋 상세에서 보낸 질문의 screenContext 에 ID 와 인용된 이름이 실린다', async ({
    authenticatedPage: page,
  }) => {
    await mockAiSessions(page, 'wd-759');
    const bodies = await captureChatRequests(page);
    await gotoDatasetDetail(page, '티베로 회사 연혁');
    // 상세 데이터가 화면에 그려진 뒤(=캐시에 들어온 뒤) 질문한다
    await expect(page.getByRole('heading', { name: '티베로 회사 연혁' })).toBeVisible();

    await sendFromCurrentScreen(page, '설립 연도가 언제야?');

    expect(bodies).toHaveLength(1);
    expect(bodies[0].message).toBe('설립 연도가 언제야?');
    expect(bodies[0].screenContext).toBe(
      `[현재 화면] 데이터셋 상세 (ID: ${DATASET_ID}, 이름: 「티베로 회사 연혁」)`,
    );
  });
});
