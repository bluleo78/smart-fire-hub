/**
 * AI 세션 관리 E2E 테스트
 *
 * useAIChat.ts의 세션 로딩/전환/삭제 분기를 커버한다.
 * 기존 테스트는 항상 빈 세션([])만 사용하므로,
 * 이 파일에서 기존 세션이 있을 때의 분기를 검증한다.
 *
 * - SessionSwitcher 드롭다운 렌더링 (세션 목록 있을 때)
 * - 세션 클릭 → /ai/sessions/{id}/messages API 호출 및 히스토리 렌더링
 * - 세션 삭제 → DELETE /ai/sessions/{id} API 호출
 * - 새 대화 버튼 → 입력창 초기화 확인
 */

import { expect, test } from '../../fixtures/auth.fixture';

/** AIStatusChip locator */
const chipLocator = (page: import('@playwright/test').Page) =>
  page.getByRole('button', { name: /AI 상태/ });

/** 세션 목록 목업 데이터 */
const MOCK_SESSIONS = [
  {
    id: 1,
    sessionId: 'session-1',
    title: '이전 대화 1',
    createdAt: '2026-04-12T00:00:00Z',
    updatedAt: '2026-04-12T01:00:00Z',
  },
  {
    id: 2,
    sessionId: 'session-2',
    title: '이전 대화 2',
    createdAt: '2026-04-11T00:00:00Z',
    updatedAt: '2026-04-11T01:00:00Z',
  },
];

/** 세션 메시지 히스토리 목업 */
const MOCK_MESSAGES = [
  {
    id: 'user-001',
    role: 'user',
    content: '안녕하세요',
    timestamp: '2026-04-12T00:30:00Z',
  },
  {
    id: 'assistant-001',
    role: 'assistant',
    content: '안녕하세요! 무엇을 도와드릴까요?',
    timestamp: '2026-04-12T00:30:05Z',
  },
];

/**
 * AI 패널을 열고 세션 목록이 로드될 때까지 대기한다.
 * base.fixture.ts의 abort() 라우트를 덮어쓰기 위해 route를 먼저 등록한다.
 */
async function openPanelWithSessions(page: import('@playwright/test').Page) {
  // base.fixture의 abort() 보다 먼저 등록하여 세션 목록을 성공 응답으로 대체
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
  // chip 클릭 → side 패널 오픈
  await chipLocator(page).click();
  // 채팅 입력창이 보일 때까지 대기
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('AI 세션 관리 — SessionSwitcher', () => {
  test('세션 목록이 있을 때 SessionSwitcher 드롭다운 트리거가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await openPanelWithSessions(page);

    // SessionSwitcher: sessions.length > 0 분기 → DropdownMenuTrigger 렌더링
    // 트리거 버튼에 MessageSquare 아이콘 + 세션 제목 또는 "새 대화" 텍스트
    // 현재 세션 없으면 triggerLabel = '새 대화'이지만 드롭다운 트리거 자체는 존재
    const sessionTrigger = page.getByRole('button', { name: /새 대화|이전 대화/ });
    await expect(sessionTrigger.first()).toBeVisible({ timeout: 3000 });
  });

  test('세션이 없을 때 "대화 이력 없음" 텍스트가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // 빈 세션 목록으로 오버라이드
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    );
    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });

    // sessions.length === 0 분기 → "대화 이력 없음" span 렌더링
    await expect(page.getByText('대화 이력 없음')).toBeVisible({ timeout: 3000 });
  });

  test('세션 드롭다운을 열면 기존 세션 목록이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await openPanelWithSessions(page);

    // DropdownMenuTrigger 클릭 → 세션 목록 열기
    // 트리거 버튼: sessions이 있으면 현재 세션 title 또는 "새 대화"
    const trigger = page.getByRole('button', { name: /새 대화|이전 대화/ }).first();
    await trigger.click();

    // 드롭다운 안에서 세션 제목 표시 확인
    await expect(page.getByRole('menuitem', { name: /이전 대화 1/ })).toBeVisible({ timeout: 3000 });
    await expect(page.getByRole('menuitem', { name: /이전 대화 2/ })).toBeVisible({ timeout: 3000 });
  });
});

test.describe('AI 세션 관리 — 세션 전환 (loadSession)', () => {
  test('세션 클릭 시 /ai/sessions/{sessionId}/messages API를 호출한다', async ({
    authenticatedPage: page,
  }) => {
    await openPanelWithSessions(page);

    // 세션 메시지 로딩 API 모킹 — session-1의 메시지 히스토리
    let messagesApiCalled = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions/session-1/messages',
      (route) => {
        messagesApiCalled = true;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_MESSAGES),
        });
      },
    );

    // 드롭다운 열기
    const trigger = page.getByRole('button', { name: /새 대화|이전 대화/ }).first();
    await trigger.click();

    // "이전 대화 1" 세션 클릭 → onSelectSession → loadSession('session-1')
    // messages API 응답을 대기하여 API 호출이 완료된 후 검증한다
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/sessions/session-1/messages') && r.status() === 200),
      page.getByRole('menuitem', { name: /이전 대화 1/ }).click(),
    ]);
    expect(messagesApiCalled).toBe(true);
  });

  test('세션 전환 후 히스토리 메시지가 채팅 패널에 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    await openPanelWithSessions(page);

    // session-1 메시지 히스토리 응답
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions/session-1/messages',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_MESSAGES),
      }),
    );

    // 드롭다운 → 세션 선택
    const trigger = page.getByRole('button', { name: /새 대화|이전 대화/ }).first();
    await trigger.click();
    await page.getByRole('menuitem', { name: /이전 대화 1/ }).click();

    // 히스토리 메시지가 화면에 표시되어야 함
    await expect(page.getByText('안녕하세요', { exact: true })).toBeVisible({ timeout: 3000 });
    await expect(page.getByText('안녕하세요! 무엇을 도와드릴까요?')).toBeVisible({ timeout: 3000 });
  });

  test('세션 전환 후 SessionSwitcher 트리거가 선택된 세션 제목으로 업데이트된다', async ({
    authenticatedPage: page,
  }) => {
    await openPanelWithSessions(page);

    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions/session-1/messages',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_MESSAGES),
      }),
    );

    const trigger = page.getByRole('button', { name: /새 대화|이전 대화/ }).first();
    await trigger.click();
    await page.getByRole('menuitem', { name: /이전 대화 1/ }).click();

    // 현재 세션이 session-1로 바뀌었으므로 트리거 레이블이 '이전 대화 1'로 업데이트됨
    // currentSession = sessions.find(s => s.sessionId === currentSessionId)
    await expect(
      page.getByRole('button', { name: /이전 대화 1/ }),
    ).toBeVisible({ timeout: 3000 });
  });
});

test.describe('AI 세션 관리 — 세션 삭제 (useDeleteAISession)', () => {
  test('삭제 버튼 클릭 시 DELETE /ai/sessions/{id} API가 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await openPanelWithSessions(page);

    // DELETE 세션 API 모킹
    let deletedId: number | null = null;
    await page.route(
      (url) => /\/api\/v1\/ai\/sessions\/\d+$/.test(url.pathname),
      (route) => {
        if (route.request().method() === 'DELETE') {
          // URL에서 ID 추출
          const match = route.request().url().match(/\/ai\/sessions\/(\d+)$/);
          if (match) deletedId = Number(match[1]);
          return route.fulfill({ status: 204, body: '' });
        }
        return route.continue();
      },
    );

    // 세션 목록 갱신 모킹 (삭제 후 invalidateQueries 트리거)
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([MOCK_SESSIONS[1]]), // session-1 삭제 후 1개만 반환
      }),
    );

    // 드롭다운 열기
    const trigger = page.getByRole('button', { name: /새 대화|이전 대화/ }).first();
    await trigger.click();

    // "이전 대화 1" 행의 삭제 버튼 클릭 (Trash2 아이콘 버튼, stopPropagation)
    const menuItem1 = page.getByRole('menuitem', { name: /이전 대화 1/ });
    await expect(menuItem1).toBeVisible({ timeout: 3000 });
    // 삭제 버튼: menuitem 내 size="icon" 버튼
    const deleteBtn = menuItem1.getByRole('button');
    // DELETE API 응답을 대기하여 호출 완료 후 검증한다
    await Promise.all([
      page.waitForResponse((r) => /\/ai\/sessions\/\d+$/.test(new URL(r.url()).pathname) && r.request().method() === 'DELETE'),
      deleteBtn.click(),
    ]);
    expect(deletedId).toBe(1); // MOCK_SESSIONS[0].id === 1
  });
});

test.describe('AI 세션 관리 — 새 대화 (startNewSession)', () => {
  test('"새 대화" 버튼 클릭 시 메시지 목록이 초기화된다', async ({
    authenticatedPage: page,
  }) => {
    await openPanelWithSessions(page);

    // 먼저 세션을 로드하여 메시지를 채운다
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions/session-1/messages',
      (route) => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_MESSAGES),
      }),
    );

    const trigger = page.getByRole('button', { name: /새 대화|이전 대화/ }).first();
    await trigger.click();
    await page.getByRole('menuitem', { name: /이전 대화 1/ }).click();
    await expect(page.getByText('안녕하세요', { exact: true })).toBeVisible({ timeout: 3000 });

    // 세션 선택 후 드롭다운 트리거는 "이전 대화 1"로 바뀌므로 "새 대화" 버튼은 독립 버튼 1개만 존재
    const newSessionBtn = page.getByRole('button', { name: '새 대화' }).first();
    await newSessionBtn.click();

    // 메시지가 사라져야 함 (새 빈 세션 상태)
    await expect(page.getByText('안녕하세요')).not.toBeVisible({ timeout: 3000 });
  });

  test('"새 대화" 버튼 클릭 후 채팅 입력창이 활성화된다', async ({
    authenticatedPage: page,
  }) => {
    await openPanelWithSessions(page);

    // 세션 없는 초기 상태: 드롭다운 트리거가 "새 대화"이고 독립 버튼도 "새 대화"로 2개 존재 → nth(1)
    // 세션이 있는 상태에서는 트리거가 세션 제목으로 바뀌므로 "새 대화"는 1개 → first()
    const newSessionBtn = page.getByRole('button', { name: '새 대화' }).first();
    await newSessionBtn.click();

    // 새 세션 상태에서 입력창이 여전히 활성화되어야 함
    await expect(page.getByPlaceholder('메시지를 입력하세요...')).toBeVisible({ timeout: 3000 });
  });
});
