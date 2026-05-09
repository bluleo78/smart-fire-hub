/**
 * AI Chat 세션 히스토리 로드 실패 시 에러 피드백 E2E 테스트
 *
 * 이슈 #207: loadSession() catch 블록에서 console.error만 실행하고 toast.error 없음
 * 수정 내용: 세션 히스토리 로드 실패 시 toast.error('대화 이력을 불러오지 못했습니다.') 표시
 *
 * 프로젝트 E2E 컨벤션:
 * - API 모킹 기반 — 백엔드/ai-agent 없이 동작한다.
 * - 로그인은 `auth.fixture.ts`의 `authenticatedPage`를 사용한다.
 */

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

/** AI 세션 목록 API 모킹 — 세션 1개를 포함한 목록 반환 */
async function mockAiSessionsWithOne(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            {
              id: 1,
              sessionId: 'test-session-error-207',
              title: '테스트 대화',
              createdAt: '2026-05-09T00:00:00Z',
              updatedAt: '2026-05-09T00:00:00Z',
            },
          ]),
        });
      }
      return route.continue();
    },
  );
}

/** 세션 메시지 조회 API 500 에러 모킹 */
async function mockSessionMessagesError(page: Page) {
  await page.route(
    (url) => url.pathname.startsWith('/api/v1/ai/sessions/') && url.pathname.endsWith('/messages'),
    (route) => {
      return route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Internal Server Error' }),
      });
    },
  );
}

/**
 * AI 챗 사이드 패널을 열고 입력창이 보일 때까지 대기
 */
async function openChatPanel(page: Page) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  await page
    .getByPlaceholder('메시지를 입력하세요...')
    .waitFor({ state: 'visible', timeout: 5_000 });
}

test.describe('AI 챗 세션 히스토리 에러 피드백 (#207)', () => {
  /**
   * SF-01: 세션 히스토리 로드 실패 시 toast.error 표시 검증
   * - 세션 선택 → /messages API 500 응답
   * - toast에 '대화 이력을 불러오지 못했습니다.' 메시지 표시
   * - 채팅 입력창은 여전히 사용 가능 (에러 후 정상 상태 유지)
   */
  test('SF-01: 세션 히스토리 로드 실패 시 toast 에러 메시지 표시', async ({
    authenticatedPage: page,
  }) => {
    // 1. AI 세션 목록 모킹 (세션 1개 포함)
    await mockAiSessionsWithOne(page);

    // 2. 세션 메시지 조회 500 에러 모킹
    await mockSessionMessagesError(page);

    // 3. AI 패널 열기
    await openChatPanel(page);

    // 4. "대화 선택" 드롭다운 열기
    await page.getByRole('button', { name: '대화 선택' }).click();

    // 5. 세션 아이템 클릭 — 메시지 조회 실패 유도
    await page.getByRole('menuitem', { name: /테스트 대화/ }).first().click();

    // 6. 핵심 검증: toast 에러 메시지가 표시되어야 한다 (이슈 #207 수정 확인)
    await expect(
      page.getByText('대화 이력을 불러오지 못했습니다.'),
    ).toBeVisible({ timeout: 5_000 });

    // 7. 에러 후에도 채팅 입력창은 여전히 사용 가능해야 한다
    await expect(
      page.getByPlaceholder('메시지를 입력하세요...'),
    ).toBeEnabled();
  });
});
