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

    // 현재 활성 세션의 삭제 버튼 클릭 → DeleteConfirmDialog 오픈 (#210)
    // → 확인 버튼 클릭 → deleteSession.mutate(10, { onSuccess: () => startNewSession() })
    const deleteBtn = page.getByRole('button', { name: '활성 대화 테스트 삭제' });
    await deleteBtn.click({ force: true });

    // 확인 다이얼로그가 열렸는지 검증 — "대화 삭제" 제목이 보여야 함 (#210)
    await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 3_000 });
    await expect(page.getByText('대화 삭제')).toBeVisible({ timeout: 3_000 });

    // "삭제" 버튼 클릭으로 확인 → 실제 DELETE API 호출
    await Promise.all([
      page.waitForResponse((r) => /\/ai\/sessions\/10$/.test(new URL(r.url()).pathname) && r.request().method() === 'DELETE'),
      page.getByRole('button', { name: '삭제' }).click(),
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

    // 세션 B("다른 대화") 삭제 버튼 클릭 → DeleteConfirmDialog 오픈 (#210)
    const otherDeleteBtn = page.getByRole('button', { name: '다른 대화 삭제' });
    await otherDeleteBtn.click({ force: true });

    // 확인 다이얼로그 확인 후 삭제 확정
    await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 3_000 });
    await Promise.all([
      page.waitForResponse((r) => /\/ai\/sessions\/11$/.test(new URL(r.url()).pathname) && r.request().method() === 'DELETE'),
      page.getByRole('button', { name: '삭제' }).click(),
    ]);

    // 핵심 검증: 세션 A의 채팅 히스토리가 그대로 유지되어야 함
    await expect(page.getByText('이슈 211 테스트 메시지')).toBeVisible({ timeout: 3_000 });
    await expect(page.getByText('응답 메시지입니다.')).toBeVisible({ timeout: 3_000 });
  });
});

// WD-973: 삭제 응답을 기다리는 줄의 휴지통을 다시 누르면 같은 id 로 DELETE 가 또 나갔다(두 번째는 404 → 실패 토스트).
test.describe('삭제 중 다시 지우기 (WD-973)', () => {
  /**
   * 전체 화면 목록을 열고 DELETE(id=10) 응답을 시험이 쥔다.
   * - failDelete: true 면 풀어 줄 때 500 으로 실패시킨다(false 면 204)
   * - 돌려주는 deletes 는 서버에 닿은 DELETE 요청 수, release 는 붙잡은 응답을 푼다
   */
  async function openWithHeldDelete(page: Page, failDelete: boolean) {
    const deletes: string[] = [];
    let release: () => void = () => {};
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) =>
        route.request().method() === 'GET'
          ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MOCK_SESSIONS) })
          : route.continue(),
    );
    await page.route(
      (url) => /\/api\/v1\/ai\/sessions\/\d+$/.test(url.pathname),
      async (route) => {
        if (route.request().method() !== 'DELETE') return route.continue();
        deletes.push(new URL(route.request().url()).pathname.split('/').pop() ?? '');
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        return failDelete
          ? route.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"fail"}' })
          : route.fulfill({ status: 204, body: '' });
      },
    );
    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5_000 });
    await chipLocator(page).click();
    await page.getByRole('button', { name: '새 대화' }).waitFor({ state: 'visible', timeout: 5_000 });
    return { deletes, release: () => release() };
  }

  /** 휴지통을 눌러 확인 창을 열고 「삭제」로 확정한다 */
  async function confirmDelete(page: Page, title: string) {
    await page.getByRole('button', { name: `${title} 삭제` }).click({ force: true });
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제', exact: true }).click();
  }

  test('확인 뒤 응답 전에 같은 줄 휴지통을 눌러도 확인 창이 열리지 않고 DELETE 는 1건', async ({
    authenticatedPage: page,
  }) => {
    const req = await openWithHeldDelete(page, false);
    await confirmDelete(page, '활성 대화 테스트');
    await expect.poll(() => req.deletes).toEqual(['10']);

    const trash = page.getByRole('button', { name: '활성 대화 테스트 삭제' });
    await expect(trash).toHaveAttribute('aria-disabled', 'true');
    // 마우스를 목록 밖으로 치운 안 가리킨 상태에서도 흐리게(50%) 보여 바쁜 줄임을 알린다 — 투명(0)이 아니다
    await page.mouse.move(1, 1);
    await expect(trash).toHaveCSS('opacity', '0.5');

    await trash.click({ force: true });
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    // 다른 줄은 영향이 없다
    await expect(page.getByRole('button', { name: '다른 대화 삭제' })).not.toHaveAttribute('aria-disabled', 'true');
    expect(req.deletes).toEqual(['10']);
    // 붙잡은 204 를 풀고 성공 안내까지 기다린다 — 시험이 끝나기 전에 응답이 처리된 것을 확인한다
    // (목록 GET 모킹은 늘 같은 목록을 돌려 재조회 뒤 줄이 다시 보이므로 줄 사라짐으로는 기다리지 않는다)
    req.release();
    await expect(page.getByText('대화가 삭제되었습니다.')).toBeVisible();
  });

  test('실패(500) 뒤에는 다시 지울 수 있다', async ({ authenticatedPage: page }) => {
    const req = await openWithHeldDelete(page, true);
    await confirmDelete(page, '활성 대화 테스트');
    await expect.poll(() => req.deletes).toEqual(['10']);
    req.release();

    const trash = page.getByRole('button', { name: '활성 대화 테스트 삭제' });
    await expect(page.getByText('대화 삭제에 실패했습니다.')).toBeVisible();
    await expect(trash).not.toHaveAttribute('aria-disabled', 'true');
    await trash.click({ force: true });
    await expect(page.getByRole('alertdialog')).toBeVisible();
    // 다시 확정하면 두 번째 DELETE 가 실제로 나간다 — 조용히 막힌 채로 남지 않는다
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제', exact: true }).click();
    await expect.poll(() => req.deletes).toEqual(['10', '10']);
    await expect(trash).toHaveAttribute('aria-disabled', 'true');
    req.release();
    // 두 번째 실패 응답이 처리되면 다시 누를 수 있는 상태로 돌아온다
    await expect(trash).not.toHaveAttribute('aria-disabled', 'true');
  });

  // WD-989: 다른 탭·기기에서 이미 지운 대화를 지우면 서버는 404 — 실패가 아니라 이미 지운 것으로 다룬다
  test('이미 삭제된 열린 대화의 삭제(404)는 줄을 지우고 안내하며 새 대화로 바뀐다 (WD-989)', async ({
    authenticatedPage: page,
  }) => {
    // 목록은 서버 상태처럼 바뀐다 — 404 뒤 재조회에서 그 대화가 빠져 있다
    let sessions = MOCK_SESSIONS;
    const deletes: string[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) =>
        route.request().method() === 'GET'
          ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sessions) })
          : route.continue(),
    );
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions/active-session-211/messages',
      (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MOCK_MESSAGES) }),
    );
    await page.route(
      (url) => /\/api\/v1\/ai\/sessions\/\d+$/.test(url.pathname),
      (route) => {
        if (route.request().method() !== 'DELETE') return route.continue();
        deletes.push(new URL(route.request().url()).pathname.split('/').pop() ?? '');
        sessions = [MOCK_SESSIONS[1]];
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{"message":"not found"}' });
      },
    );
    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5_000 });
    await chipLocator(page).click();
    await page.getByRole('button', { name: '새 대화' }).waitFor({ state: 'visible', timeout: 5_000 });

    await page.getByText('활성 대화 테스트').click();
    await expect(page.getByText('이슈 211 테스트 메시지')).toBeVisible();

    await confirmDelete(page, '활성 대화 테스트');
    await expect.poll(() => deletes).toEqual(['10']);

    await expect(page.getByText('이미 삭제된 대화입니다.')).toBeVisible();
    await expect(page.getByText('대화 삭제에 실패했습니다.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '활성 대화 테스트 삭제' })).toHaveCount(0);
    await expect(page.getByText('이슈 211 테스트 메시지')).toHaveCount(0);
    await expect(page.getByPlaceholder('메시지를 입력하세요...')).toBeEnabled();
  });
});
