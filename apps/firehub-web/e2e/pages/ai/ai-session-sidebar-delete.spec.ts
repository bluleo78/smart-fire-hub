/**
 * AISessionSidebar — 현재 활성 세션 삭제 후 채팅 UI 초기화 E2E 회귀 테스트
 *
 * 이슈 #211: 현재 활성 세션 삭제 시 startNewSession() 미호출로 채팅 UI가 삭제된 세션 ID를 유지함
 * 수정 내용: handleDelete에서 삭제 대상 sessionId === currentSessionId 이면 onSuccess 시 startNewSession() 호출
 *
 * 프로젝트 E2E 컨벤션:
 * - API 모킹 기반 — 백엔드 없이 동작한다.
 * - fullscreen 모드(AIFullScreen)에서 AISessionSidebar가 렌더링된다.
 * - chip 두 번 클릭으로 fullscreen 모드 진입.
 */

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

/** AIStatusChip locator */
const chipLocator = (page: Page) => page.getByRole('button', { name: /AI 상태/ });

/** 테스트용 세션 목업 */
const MOCK_SESSIONS = [
  {
    id: 10,
    sessionId: 'active-session-211',
    title: '활성 대화 테스트',
    createdAt: '2026-05-09T00:00:00Z',
    updatedAt: '2026-05-09T01:00:00Z',
  },
  {
    id: 11,
    sessionId: 'other-session-211',
    title: '다른 대화',
    createdAt: '2026-05-09T00:00:00Z',
    updatedAt: '2026-05-09T01:00:00Z',
  },
];

/** 활성 세션의 메시지 히스토리 */
const MOCK_MESSAGES = [
  {
    id: 'user-211',
    role: 'user',
    content: '이슈 211 테스트 메시지',
    timestamp: '2026-05-09T00:30:00Z',
  },
  {
    id: 'assistant-211',
    role: 'assistant',
    content: '응답 메시지입니다.',
    timestamp: '2026-05-09T00:30:05Z',
  },
];

/**
 * AI fullscreen 패널을 열고 세션 사이드바가 나타날 때까지 대기한다.
 * chip을 두 번 클릭하면 side → fullscreen 모드가 된다.
 */
async function openFullscreenWithSessions(page: Page) {
  // 세션 목록 API 모킹 — 두 개의 세션 반환
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_SESSIONS),
        });
      }
      return route.continue();
    },
  );

  await page.goto('/', { waitUntil: 'commit' });

  // chip 첫 번째 클릭 → side 모드
  await chipLocator(page).click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5_000 });

  // chip 두 번째 클릭 → fullscreen 모드 (AISessionSidebar 표시)
  await chipLocator(page).click();

  // "새 대화" 버튼이 AISessionSidebar 안에 있음 — 표시될 때까지 대기
  await page.getByRole('button', { name: '새 대화' }).waitFor({ state: 'visible', timeout: 5_000 });
}

test.describe('AISessionSidebar — 현재 활성 세션 삭제 후 UI 초기화 (#211)', () => {
  /**
   * SD-01: 현재 활성 세션을 삭제하면 채팅 UI가 새 세션 상태로 초기화된다.
   *
   * 검증:
   * - 세션 로드 후 메시지가 표시됨
   * - 해당 세션 삭제 → onSuccess에서 startNewSession() 호출
   * - 채팅 영역: 히스토리 메시지가 사라지고 빈 상태가 됨
   * - currentSessionId: null로 리셋됨 (새 대화 상태)
   */
  test('SD-01: 현재 활성 세션 삭제 시 채팅 히스토리가 초기화된다', async ({
    authenticatedPage: page,
  }) => {
    await openFullscreenWithSessions(page);

    // 세션 메시지 로딩 API 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions/active-session-211/messages',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_MESSAGES),
      }),
    );

    // DELETE 세션 API 모킹 (id=10)
    await page.route(
      (url) => /\/api\/v1\/ai\/sessions\/10$/.test(url.pathname),
      (route) => {
        if (route.request().method() === 'DELETE') {
          return route.fulfill({ status: 204, body: '' });
        }
        return route.continue();
      },
    );

    // 삭제 후 갱신된 세션 목록 (active 세션 제거됨)
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => {
        if (route.request().method() === 'GET') {
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify([MOCK_SESSIONS[1]]),
          });
        }
        return route.continue();
      },
    );

    // "활성 대화 테스트" 세션 클릭 → loadSession
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/sessions/active-session-211/messages') && r.status() === 200),
      page.getByText('활성 대화 테스트').click(),
    ]);

    // 메시지 히스토리 표시 확인
    await expect(page.getByText('이슈 211 테스트 메시지')).toBeVisible({ timeout: 3_000 });
    await expect(page.getByText('응답 메시지입니다.')).toBeVisible({ timeout: 3_000 });

    // 현재 활성 세션의 삭제 버튼 클릭 → handleDelete(e, 'active-session-211', 10)
    // → deleteSession.mutate(10, { onSuccess: () => startNewSession() })
    const deleteBtn = page.getByText('활성 대화 테스트').locator('..').getByRole('button');
    await Promise.all([
      page.waitForResponse((r) => /\/ai\/sessions\/10$/.test(new URL(r.url()).pathname) && r.request().method() === 'DELETE'),
      deleteBtn.click({ force: true }),
    ]);

    // 핵심 검증: 채팅 히스토리가 초기화되어야 함 (#211 fix)
    await expect(page.getByText('이슈 211 테스트 메시지')).not.toBeVisible({ timeout: 3_000 });
    await expect(page.getByText('응답 메시지입니다.')).not.toBeVisible({ timeout: 3_000 });

    // 입력창은 여전히 활성화 상태여야 함
    await expect(page.getByPlaceholder('메시지를 입력하세요...')).toBeEnabled({ timeout: 3_000 });
  });

  /**
   * SD-02: 현재 활성이 아닌 다른 세션을 삭제하면 현재 채팅 UI가 유지된다.
   *
   * 검증:
   * - 세션 A 로드 후 메시지 표시됨
   * - 세션 B 삭제 → startNewSession() 미호출
   * - 채팅 영역: 세션 A의 히스토리 메시지가 그대로 유지됨
   */
  test('SD-02: 비활성 세션 삭제 시 현재 채팅 UI가 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await openFullscreenWithSessions(page);

    // 세션 A(active) 메시지 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions/active-session-211/messages',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_MESSAGES),
      }),
    );

    // DELETE 세션 B API 모킹 (id=11)
    await page.route(
      (url) => /\/api\/v1\/ai\/sessions\/11$/.test(url.pathname),
      (route) => {
        if (route.request().method() === 'DELETE') {
          return route.fulfill({ status: 204, body: '' });
        }
        return route.continue();
      },
    );

    // 삭제 후 갱신된 세션 목록 (세션 B만 제거됨)
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => {
        if (route.request().method() === 'GET') {
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify([MOCK_SESSIONS[0]]),
          });
        }
        return route.continue();
      },
    );

    // 세션 A 로드
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/sessions/active-session-211/messages') && r.status() === 200),
      page.getByText('활성 대화 테스트').click(),
    ]);

    // 메시지 히스토리 표시 확인
    await expect(page.getByText('이슈 211 테스트 메시지')).toBeVisible({ timeout: 3_000 });

    // 세션 B("다른 대화") 삭제 버튼 클릭
    const otherDeleteBtn = page.getByText('다른 대화').locator('..').getByRole('button');
    await Promise.all([
      page.waitForResponse((r) => /\/ai\/sessions\/11$/.test(new URL(r.url()).pathname) && r.request().method() === 'DELETE'),
      otherDeleteBtn.click({ force: true }),
    ]);

    // 핵심 검증: 세션 A의 채팅 히스토리가 그대로 유지되어야 함
    await expect(page.getByText('이슈 211 테스트 메시지')).toBeVisible({ timeout: 3_000 });
    await expect(page.getByText('응답 메시지입니다.')).toBeVisible({ timeout: 3_000 });
  });
});
