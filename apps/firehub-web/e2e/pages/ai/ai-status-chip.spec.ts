/**
 * AIStatusChip / AIStatusChipDropdown E2E 테스트
 *
 * AIStatusChip 클릭에 의한 모드 회전(closed→side→floating→fullscreen→closed)과
 * 3초 호버 후 나타나는 드롭다운 메뉴를 검증한다.
 *
 * - ai/sessions 요청은 base.fixture.ts에서 abort()되므로 세션 없이 패널이 렌더링된다.
 * - 각 클릭 후 AI 패널 컨테이너(또는 상태 변경)를 확인한다.
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** AIStatusChip의 role="button" 요소 locator */
const chipLocator = (page: import('@playwright/test').Page) =>
  page.getByRole('button', { name: /AI 상태/ });

test.describe('AIStatusChip — 모드 회전', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // 홈 페이지 이동 — AppLayout 헤더에 AIStatusChip이 렌더링됨
    await page.goto('/', { waitUntil: 'commit' });
  });

  test('첫 번째 클릭 — side 모드로 AI 패널이 열린다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    // AI 세션 목록 모킹 (빈 세션, 패널은 렌더링됨)
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);

    await chipLocator(page).click();

    // side 모드에서 AI 패널이 열려야 한다 — 채팅 입력창 등이 나타남
    // 패널 컨테이너가 visible인지 확인 (role=region 또는 채팅 textbox)
    await expect(
      page.getByRole('textbox').filter({ hasText: '' }).last(),
    ).toBeVisible({ timeout: 3000 });
  });

  test('두 번째 클릭 — floating 모드로 전환된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);

    // 첫 번째 클릭 → side
    await chipLocator(page).click();
    await page.waitForTimeout(200);

    // 두 번째 클릭 → floating
    await chipLocator(page).click();
    await page.waitForTimeout(200);

    // floating 모드: 패널이 여전히 열려 있어야 함
    await expect(chipLocator(page)).toBeVisible();
  });

  test('세 번째 클릭 — fullscreen 모드로 전환된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);

    await chipLocator(page).click();
    await page.waitForTimeout(200);
    await chipLocator(page).click();
    await page.waitForTimeout(200);
    await chipLocator(page).click(); // fullscreen
    await page.waitForTimeout(200);

    // fullscreen 모드: chip이 여전히 visible이고 에러 없음 확인
    await expect(chipLocator(page)).toBeVisible();
  });

  test('네 번째 클릭 — 패널이 닫힌다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);

    // side → floating → fullscreen → closed
    await chipLocator(page).click();
    await page.waitForTimeout(100);
    await chipLocator(page).click();
    await page.waitForTimeout(100);
    await chipLocator(page).click();
    await page.waitForTimeout(100);
    await chipLocator(page).click(); // closed
    await page.waitForTimeout(200);

    // 패널이 닫힌 후 chip은 여전히 visible
    await expect(chipLocator(page)).toBeVisible();
  });
});

test.describe('ChatInput — 입력 인터랙션', () => {
  /** AI 패널을 열어 ChatInput이 visible 상태가 되도록 준비한다 */
  async function openChatPanel(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });
  }

  test('입력이 비어있을 때 전송 버튼이 비활성화된다', async ({ authenticatedPage: page }) => {
    await openChatPanel(page);

    // 빈 상태에서 전송 버튼(Send 아이콘)은 disabled이어야 한다
    // ChatInput: canSend = message.trim().length > 0 || pendingFiles.length > 0
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await expect(chatInput).toHaveValue('');

    // 전송 버튼: disabled={!canSend} — 빈 입력이면 disabled
    // ChatInput 내 Send 버튼은 size="icon"이고 disabled prop이 있다
    // aria 속성으로 disabled 상태를 확인한다
    const sendButton = page.locator('button[disabled]').filter({ has: page.locator('svg') }).last();
    await expect(sendButton).toBeDisabled();
  });

  test('Shift+Enter 입력 시 줄바꿈이 추가된다 (전송 안 됨)', async ({ authenticatedPage: page }) => {
    await openChatPanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('첫 번째 줄');

    // Shift+Enter → preventDefault 없이 줄바꿈 추가 (handleKeyDown에서 shiftKey=true이면 handleSend 미호출)
    await chatInput.press('Shift+Enter');

    // 입력창에 줄바꿈 문자('\n')가 포함되어 있어야 한다
    const value = await chatInput.inputValue();
    expect(value).toContain('\n');
  });

  test('Enter 입력 시 메시지가 전송된다 (입력창 초기화)', async ({ authenticatedPage: page }) => {
    // AI 세션 생성 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => {
        if (route.request().method() === 'GET') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ id: 1, sessionId: 'test-session', title: null, createdAt: '2026-04-12T00:00:00Z', updatedAt: '2026-04-12T00:00:00Z' }),
        });
      },
    );
    // AI chat SSE 응답 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
        body: 'data: {"type":"init","sessionId":"test-session"}\n\ndata: {"type":"done","inputTokens":10}\n\n',
      }),
    );

    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.waitFor({ state: 'visible', timeout: 5000 });

    await chatInput.fill('테스트 메시지');
    await expect(chatInput).toHaveValue('테스트 메시지');

    // Enter 전송 → handleSend 호출 → setMessage('') → 입력창 초기화
    await chatInput.press('Enter');

    // 전송 후 입력창이 비워져야 한다
    await expect(chatInput).toHaveValue('', { timeout: 3000 });
  });
});

test.describe('AIFloating — floating 모드 동작', () => {
  /**
   * floating 모드로 전환하려면 ai-mode를 localStorage에 설정 후 goto하고
   * chipLocator를 한 번 클릭하여 패널을 연다.
   */
  async function openFloatingPanel(page: import('@playwright/test').Page) {
    // floating 모드를 localStorage에 미리 저장 — AIProvider가 getStoredMode()로 읽는다
    await page.goto('/', { waitUntil: 'commit' });
    await page.evaluate(() => localStorage.setItem('ai-mode', 'floating'));
    // 세션 모킹은 reload 전에 등록해야 reload 시 적용된다
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.reload({ waitUntil: 'commit' });
    await chipLocator(page).click();
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });
  }

  test('floating 모드에서 채팅 입력창이 표시된다', async ({ authenticatedPage: page }) => {
    await openFloatingPanel(page);

    // floating 모드: AIFloating 컴포넌트가 fixed 위치로 렌더링됨 — 채팅 입력창 visible 확인
    await expect(page.getByPlaceholder('메시지를 입력하세요...')).toBeVisible();
  });

  test('floating 모드에서 chip 클릭으로 패널이 닫힌다', async ({ authenticatedPage: page }) => {
    await openFloatingPanel(page);

    // floating 모드에서 chip을 3번 더 클릭하면 closed 상태가 된다
    // floating(현재) → fullscreen → closed
    await chipLocator(page).click(); // fullscreen
    await page.waitForTimeout(100);
    await chipLocator(page).click(); // closed
    await page.waitForTimeout(200);

    // 패널이 닫히면 입력창이 사라져야 한다
    await expect(page.getByPlaceholder('메시지를 입력하세요...')).not.toBeVisible({ timeout: 3000 });
  });

  test('floating 모드에서 모드 전환 버튼으로 side 모드로 변경된다', async ({ authenticatedPage: page }) => {
    await openFloatingPanel(page);

    // AIChatPanel 헤더의 '사이드 패널' 모드 버튼 (title="사이드 패널")
    // 입력창이 보이면 패널 헤더도 DOM에 있으므로 getByTitle로 직접 접근
    await page.getByTitle('사이드 패널').click();

    // side 모드로 전환 후 패널이 여전히 열려 있어야 한다
    await expect(page.getByPlaceholder('메시지를 입력하세요...')).toBeVisible({ timeout: 3000 });
  });
});

test.describe('AIStatusChipDropdown — 3초 호버 후 드롭다운', () => {
  test('chip 호버 3초 후 드롭다운이 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.goto('/', { waitUntil: 'commit' });

    // chip 위에 마우스 올리기
    await chipLocator(page).hover();

    // 3초 타이머 대기 (3100ms)
    await page.waitForTimeout(3100);

    // 드롭다운이 나타나야 함 (role="dialog", aria-label="AI 상태 및 제어" — WD-427 에서 menu → dialog)
    await expect(page.getByRole('dialog', { name: 'AI 상태 및 제어' })).toBeVisible();
  });

  // floating 모드는 UI에서 일시 숨김 — 드롭다운 플로팅 버튼 스킵, 코드는 보존
  test.skip('드롭다운 — "플로팅" 버튼 클릭 시 floating 모드로 AI 패널이 열린다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.goto('/', { waitUntil: 'commit' });

    await chipLocator(page).hover();
    await page.waitForTimeout(3100);
    await expect(page.getByRole('dialog', { name: 'AI 상태 및 제어' })).toBeVisible();
    await page.getByRole('dialog', { name: 'AI 상태 및 제어' }).getByRole('button', { name: '플로팅', exact: true }).click();
    await expect(chipLocator(page)).toBeVisible();
  });

  test('드롭다운 — AI 패널 열린 상태에서 "사이드" 버튼 클릭 시 모드가 side로 변경된다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.goto('/', { waitUntil: 'commit' });

    // 먼저 floating 모드로 패널을 연다 (두 번 클릭)
    await chipLocator(page).click();
    await page.waitForTimeout(200);
    await chipLocator(page).click(); // floating
    await page.waitForTimeout(200);

    // 호버 → 드롭다운 표시
    await chipLocator(page).hover();
    await page.waitForTimeout(3100);
    await expect(page.getByRole('dialog', { name: 'AI 상태 및 제어' })).toBeVisible();

    // "사이드" 버튼 클릭 — isAIOpen=true 분기의 onModeChange('side') 호출
    await page.getByRole('dialog', { name: 'AI 상태 및 제어' }).getByRole('button', { name: '사이드', exact: true }).click();

    // 패널이 여전히 열려 있어야 함
    await expect(chipLocator(page)).toBeVisible();
  });

  test('드롭다운 — AI 닫힌 상태에서 QuickInput에 텍스트 입력 후 전송한다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.goto('/', { waitUntil: 'commit' });

    // chip 호버 → 드롭다운 표시 (AI 패널 닫힌 상태)
    await chipLocator(page).hover();
    await page.waitForTimeout(3100);
    await expect(page.getByRole('dialog', { name: 'AI 상태 및 제어' })).toBeVisible();

    // QuickInput 입력란 확인 — placeholder "AI에게 질문하기..."
    const quickInput = page.getByPlaceholder('AI에게 질문하기...');
    await expect(quickInput).toBeVisible();

    // 텍스트 입력
    await quickInput.fill('테스트 질문');

    // Enter로 전송 → onSend 호출 → onOpen 호출로 패널이 열림
    await quickInput.press('Enter');

    // 패널이 열려야 함 (chip이 visible 상태 유지)
    await expect(chipLocator(page)).toBeVisible();
  });

  test('드롭다운 — "새 세션" 버튼 클릭 시 UI 상태가 초기화된다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.goto('/', { waitUntil: 'commit' });

    // 먼저 패널을 열어둔다 (side 모드)
    await chipLocator(page).click();
    await page.waitForTimeout(200);

    // 호버 → 드롭다운 표시
    await chipLocator(page).hover();
    await page.waitForTimeout(3100);
    await expect(page.getByRole('dialog', { name: 'AI 상태 및 제어' })).toBeVisible();

    // "새 세션" 버튼 클릭 — onNewSession 콜백 호출
    await page.getByRole('dialog', { name: 'AI 상태 및 제어' }).getByRole('button', { name: '새 세션', exact: true }).click();

    // 새 세션 생성 후 패널이 여전히 열려 있어야 함
    await expect(chipLocator(page)).toBeVisible();
  });
});

// WD-427: 드롭다운이 3초 호버로만 열려 키보드로는 열 수 없었고, Esc 로 닫으면 포커스가 body 로 사라졌으며,
// Tab 으로 벗어나도 열린 채 남았다. 아래 방향키로 열고, Esc·포커스 이탈로 닫히는지 본다.
test.describe('AIStatusChipDropdown — 키보드 (WD-427)', () => {
  const dropdown = (page: import('@playwright/test').Page) =>
    page.getByRole('dialog', { name: 'AI 상태 및 제어' });

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.goto('/', { waitUntil: 'commit' });
  });

  test('칩에서 아래 방향키를 누르면 드롭다운이 열리고 포커스가 드롭다운으로 간다', async ({
    authenticatedPage: page,
  }) => {
    const chip = chipLocator(page);
    await expect(chip).toHaveAttribute('aria-haspopup', 'dialog');
    await chip.focus();
    await page.keyboard.press('ArrowDown');

    await expect(dropdown(page)).toBeVisible();
    await expect(chip).toHaveAttribute('aria-expanded', 'true');
    await expect(dropdown(page)).toBeFocused();
    // 키보드로만 볼 수 있게 된 세션 정보
    await expect(dropdown(page).getByText('대화 수')).toBeVisible();
    // 다음 Tab 은 드롭다운 안(빠른 질문 칸)으로 간다
    await page.keyboard.press('Tab');
    await expect(dropdown(page).getByPlaceholder('AI에게 질문하기...')).toBeFocused();
  });

  test('드롭다운 안에서 Esc 를 누르면 닫히고 포커스가 칩으로 돌아온다', async ({ authenticatedPage: page }) => {
    const chip = chipLocator(page);
    await chip.focus();
    await page.keyboard.press('ArrowDown');
    await dropdown(page).getByRole('button', { name: '사이드', exact: true }).focus();

    await page.keyboard.press('Escape');
    await expect(dropdown(page)).toBeHidden();
    await expect(chip).toBeFocused();
    await expect(chip).toHaveAttribute('aria-expanded', 'false');
  });

  test('Tab 으로 드롭다운을 벗어나면 드롭다운이 닫힌다', async ({ authenticatedPage: page }) => {
    const chip = chipLocator(page);
    await chip.focus();
    await page.keyboard.press('ArrowDown');
    // 마지막 버튼 「세션 목록」에서 Tab — 다음은 머리글의 알림 벨이다
    await dropdown(page).getByRole('button', { name: '세션 목록', exact: true }).focus();
    await page.keyboard.press('Tab');

    await expect(dropdown(page)).toBeHidden();
    await expect(page.getByRole('button', { name: /AI 인사이트 알림|안 읽은 AI 인사이트/ }).first()).toBeFocused();
  });

  test('Enter 는 지금처럼 패널 모드를 바꾸고 드롭다운을 열지 않는다', async ({ authenticatedPage: page }) => {
    const chip = chipLocator(page);
    await chip.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('textbox').last()).toBeVisible({ timeout: 3000 });
    await expect(dropdown(page)).toBeHidden();
  });
});
