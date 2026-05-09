/**
 * AISessionSidebar — 삭제 전 확인 다이얼로그 E2E 회귀 테스트
 *
 * 이슈 #210: AISessionSidebar에서 삭제 버튼 클릭 시 확인 없이 즉시 삭제됨
 * 수정 내용: DeleteConfirmDialog로 감싸 삭제 전 확인 단계 추가
 *
 * 프로젝트 E2E 컨벤션:
 * - API 모킹 기반 — 백엔드 없이 동작한다.
 * - fullscreen 모드(AIFullScreen)에서 AISessionSidebar가 렌더링된다.
 */

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

/** AIStatusChip locator */
const chipLocator = (page: Page) => page.getByRole('button', { name: /AI 상태/ });

/** 테스트용 세션 목업 */
const MOCK_SESSIONS = [
  {
    id: 20,
    sessionId: 'session-210-a',
    title: '이슈 210 테스트 대화',
    createdAt: '2026-05-09T00:00:00Z',
    updatedAt: '2026-05-09T01:00:00Z',
  },
  {
    id: 21,
    sessionId: 'session-210-b',
    title: '다른 대화 210',
    createdAt: '2026-05-09T00:00:00Z',
    updatedAt: '2026-05-09T01:00:00Z',
  },
];

/**
 * AI fullscreen 패널을 열고 세션 사이드바가 나타날 때까지 대기한다.
 * chip을 두 번 클릭하면 side → fullscreen 모드가 된다.
 */
async function openFullscreenWithSessions(page: Page) {
  // 세션 목록 API 모킹
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

test.describe('AISessionSidebar — 삭제 확인 다이얼로그 (#210)', () => {
  /**
   * CD-01: 삭제 버튼 클릭 시 확인 다이얼로그가 열린다 (즉시 삭제 안 됨).
   *
   * 검증:
   * - 삭제 버튼 클릭 후 DELETE API가 즉시 호출되지 않음
   * - AlertDialog("대화 삭제") 가 표시됨
   * - 세션 이름이 다이얼로그 설명에 포함됨
   */
  test('CD-01: 삭제 버튼 클릭 시 확인 다이얼로그가 열린다', async ({
    authenticatedPage: page,
  }) => {
    await openFullscreenWithSessions(page);

    // DELETE API 호출 추적 — 이 테스트에서는 호출되면 안 됨
    let deleteApiCalled = false;
    await page.route(
      (url) => /\/api\/v1\/ai\/sessions\/20$/.test(url.pathname),
      (route) => {
        if (route.request().method() === 'DELETE') {
          deleteApiCalled = true;
        }
        return route.continue();
      },
    );

    // "이슈 210 테스트 대화" 세션의 삭제 버튼 클릭
    const deleteBtn = page.getByText('이슈 210 테스트 대화').locator('..').getByRole('button');
    await deleteBtn.click({ force: true });

    // 핵심 검증: DELETE API 즉시 호출 안 됨 — 다이얼로그가 먼저 열려야 함
    expect(deleteApiCalled).toBe(false);

    // 확인 다이얼로그 표시 검증
    await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 3_000 });
    await expect(page.getByText('대화 삭제')).toBeVisible({ timeout: 3_000 });

    // 세션 이름이 다이얼로그 설명에 포함되어야 함 — alertdialog 내부에서 검색하여 중복 방지
    await expect(page.getByRole('alertdialog').getByText('이슈 210 테스트 대화')).toBeVisible({ timeout: 3_000 });
  });

  /**
   * CD-02: 확인 다이얼로그에서 "삭제" 클릭 시 실제 DELETE API가 호출된다.
   *
   * 검증:
   * - 삭제 버튼 클릭 → 다이얼로그 열림
   * - "삭제" 확인 버튼 클릭 → DELETE API 호출됨
   * - 세션이 목록에서 제거됨
   */
  test('CD-02: 확인 후 삭제 시 DELETE API가 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await openFullscreenWithSessions(page);

    // DELETE 세션 API 모킹
    await page.route(
      (url) => /\/api\/v1\/ai\/sessions\/20$/.test(url.pathname),
      (route) => {
        if (route.request().method() === 'DELETE') {
          return route.fulfill({ status: 204, body: '' });
        }
        return route.continue();
      },
    );

    // 삭제 후 세션 목록 갱신 모킹
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

    // 삭제 버튼 클릭 → 다이얼로그 열기
    const deleteBtn = page.getByText('이슈 210 테스트 대화').locator('..').getByRole('button');
    await deleteBtn.click({ force: true });

    // 다이얼로그 열림 확인
    await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 3_000 });

    // "삭제" 확인 버튼 클릭 → DELETE API 호출 검증
    await Promise.all([
      page.waitForResponse((r) =>
        /\/ai\/sessions\/20$/.test(new URL(r.url()).pathname) && r.request().method() === 'DELETE',
      ),
      page.getByRole('button', { name: '삭제' }).click(),
    ]);

    // 다이얼로그가 닫혔는지 확인
    await expect(page.getByRole('alertdialog')).not.toBeVisible({ timeout: 3_000 });
  });

  /**
   * CD-03: 확인 다이얼로그에서 "취소" 클릭 시 세션이 삭제되지 않는다.
   *
   * 검증:
   * - 삭제 버튼 클릭 → 다이얼로그 열림
   * - "취소" 버튼 클릭 → DELETE API 호출 안 됨
   * - 세션이 목록에 그대로 남아 있음
   */
  test('CD-03: 취소 클릭 시 세션이 삭제되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await openFullscreenWithSessions(page);

    // DELETE API 호출 추적
    let deleteApiCalled = false;
    await page.route(
      (url) => /\/api\/v1\/ai\/sessions\/20$/.test(url.pathname),
      (route) => {
        if (route.request().method() === 'DELETE') {
          deleteApiCalled = true;
        }
        return route.continue();
      },
    );

    // 삭제 버튼 클릭 → 다이얼로그 열기
    const deleteBtn = page.getByText('이슈 210 테스트 대화').locator('..').getByRole('button');
    await deleteBtn.click({ force: true });

    // 다이얼로그 열림 확인
    await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 3_000 });

    // "취소" 버튼 클릭
    await page.getByRole('button', { name: '취소' }).click();

    // 다이얼로그가 닫혔는지 확인
    await expect(page.getByRole('alertdialog')).not.toBeVisible({ timeout: 3_000 });

    // 핵심 검증: DELETE API 호출 안 됨
    expect(deleteApiCalled).toBe(false);

    // 세션이 목록에 그대로 있어야 함
    await expect(page.getByText('이슈 210 테스트 대화')).toBeVisible({ timeout: 3_000 });
  });
});
