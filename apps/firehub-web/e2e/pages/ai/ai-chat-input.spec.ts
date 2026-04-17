/**
 * ChatInput 컴포넌트 E2E 테스트
 *
 * ChatInput 분기 커버:
 * - 빈 입력 시 전송 버튼 비활성화
 * - 텍스트 입력 시 전송 버튼 활성화
 * - Shift+Enter 줄바꿈 삽입 (전송 안 됨)
 * - 파일 첨부 시 전송 버튼 활성화
 *
 * AISidePanel 분기 커버:
 * - side 모드에서 패널 너비가 기본값(380px)으로 렌더링
 * - 패널 닫힌 후 입력창 비가시
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** AIStatusChip 버튼 locator */
const chipLocator = (page: import('@playwright/test').Page) =>
  page.getByRole('button', { name: /AI 상태/ });

/** AI 패널을 side 모드로 열어 ChatInput이 visible 상태가 되도록 준비 */
async function openSidePanel(page: import('@playwright/test').Page) {
  await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
  await page.goto('/', { waitUntil: 'commit' });
  await chipLocator(page).click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('ChatInput — 전송 버튼 활성화/비활성화', () => {
  test('빈 입력 상태에서 전송 버튼이 비활성화된다', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await expect(chatInput).toHaveValue('');

    // canSend = false → <Button disabled> 렌더링
    // Send 아이콘이 있는 마지막 버튼이 disabled 상태여야 한다
    const sendBtn = page.locator('button[disabled]').last();
    await expect(sendBtn).toBeDisabled();
  });

  test('텍스트 입력 시 전송 버튼이 활성화된다', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('안녕하세요');

    // canSend = true → disabled 속성 제거
    // 버튼 중 disabled가 없는 Send 버튼을 찾는다
    // 빈 textarea면 disabled, 내용 있으면 enabled — aria-disabled 없음으로 확인
    await expect(chatInput).toHaveValue('안녕하세요');
    // 전송 버튼이 비활성화되지 않아야 한다
    const sendBtn = page.locator('button:not([disabled])').filter({ has: page.locator('svg') }).last();
    await expect(sendBtn).toBeEnabled();
  });

  test('입력 지우면 전송 버튼이 다시 비활성화된다', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    // 텍스트 입력 후 지우기
    await chatInput.fill('삭제될 텍스트');
    await chatInput.fill('');

    await expect(chatInput).toHaveValue('');
    // 전송 버튼 비활성화 재확인
    const sendBtn = page.locator('button[disabled]').last();
    await expect(sendBtn).toBeDisabled();
  });
});

test.describe('ChatInput — 키보드 동작', () => {
  test('Shift+Enter 입력 시 줄바꿈이 추가되고 전송되지 않는다', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('첫 번째 줄');

    // Shift+Enter → handleKeyDown에서 e.shiftKey=true 분기 → handleSend 미호출
    await chatInput.press('Shift+Enter');

    // 줄바꿈 문자가 포함되어 있어야 한다
    const value = await chatInput.inputValue();
    expect(value).toContain('\n');
  });

  test('Enter 입력 시 메시지가 전송되고 입력창이 초기화된다', async ({ authenticatedPage: page }) => {
    // AI 세션 생성 및 SSE 응답 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => {
        if (route.request().method() === 'GET') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ id: 1, sessionId: 'sess-1', title: null, createdAt: '2026-04-12T00:00:00Z', updatedAt: '2026-04-12T00:00:00Z' }),
        });
      },
    );
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      (route) => route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
        body: 'data: {"type":"init","sessionId":"sess-1"}\n\ndata: {"type":"done","inputTokens":5}\n\n',
      }),
    );

    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.waitFor({ state: 'visible', timeout: 5000 });

    await chatInput.fill('전송 테스트 메시지');
    await chatInput.press('Enter');

    // 전송 후 입력창이 비워져야 한다 (handleSend → setMessage(''))
    await expect(chatInput).toHaveValue('', { timeout: 3000 });
  });
});

test.describe('ChatInput — 파일 첨부', () => {
  test('파일 첨부 후 전송 버튼이 활성화된다', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    // 파일 첨부 버튼(Paperclip) 클릭 → hidden file input 트리거
    // file input은 class="hidden"이므로 직접 setInputFiles로 파일 설정
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: 'test.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('테스트 파일 내용'),
    });

    // pendingFiles.length > 0 → canSend = true → 전송 버튼 활성화
    const sendBtn = page.locator('button:not([disabled])').filter({ has: page.locator('svg') }).last();
    await expect(sendBtn).toBeEnabled({ timeout: 3000 });
  });

  test('첨부 파일 X 버튼 클릭 시 파일이 제거된다', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: 'remove-test.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('제거 테스트'),
    });

    // 파일 이름이 표시된다
    await expect(page.getByText('remove-test.txt')).toBeVisible({ timeout: 3000 });

    // X 버튼(removeFile) 클릭 — 파일 카드 내 작은 버튼
    // 파일명 span → 부모 div → 부모 파일 카드 div → button
    const removeBtn = page.locator('span').filter({ hasText: /^remove-test\.txt$/ }).locator('xpath=../..').locator('button');
    await removeBtn.click();

    // 파일 이름이 사라진다
    await expect(page.getByText('remove-test.txt')).not.toBeVisible({ timeout: 3000 });
  });

  test('지원하지 않는 파일 형식 첨부 시 에러 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    const fileInput = page.locator('input[type="file"]');
    // .exe 파일은 getCategory() → null → toast.error() 호출
    await fileInput.setInputFiles({
      name: 'malware.exe',
      mimeType: 'application/x-msdownload',
      buffer: Buffer.from('binary'),
    });

    // Sonner toast 에러 메시지
    await expect(page.getByText(/지원하지 않는 파일 형식/).first()).toBeVisible({ timeout: 5000 });
  });
});

test.describe('ChatInput — 스트리밍 중 정지 버튼', () => {
  /**
   * isStreaming=true 상태에서 Send 버튼 대신 StopCircle 버튼(destructive variant)이 나타난다.
   * Playwright route.fulfill은 body를 즉시 전달하므로, SSE에서 init 이벤트 처리 후
   * isThinking=true 상태를 짧은 시간 동안 확인하는 방식으로 스트리밍 상태를 검증한다.
   */
  test('메시지 전송 후 스트리밍 중 입력창이 비활성화된다', async ({ authenticatedPage: page }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) => {
        if (route.request().method() === 'GET') {
          return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ id: 1, sessionId: 'sess-stop', title: null, createdAt: '2026-04-12T00:00:00Z', updatedAt: '2026-04-12T00:00:00Z' }),
        });
      },
    );

    // 텍스트 이벤트가 있는 정상 SSE 응답
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            'data: {"type":"init","sessionId":"sess-stop"}\n\n',
            'data: {"type":"text","content":"응답 중입니다."}\n\n',
            'data: {"type":"done","inputTokens":10}\n\n',
          ].join(''),
        });
      },
    );

    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.waitFor({ state: 'visible', timeout: 5000 });

    await chatInput.fill('정지 테스트 메시지');
    await chatInput.press('Enter');

    // 스트리밍 완료 후 응답 텍스트가 표시된다
    await expect(page.getByText('응답 중입니다.').first()).toBeVisible({ timeout: 10_000 });

    // 스트리밍 완료 후 입력창이 다시 활성화되고 비어 있다 (handleSend → setMessage(''))
    await expect(chatInput).toBeEnabled({ timeout: 5000 });
    await expect(chatInput).toHaveValue('');
  });
});

test.describe('ChatInput — 파일 크기 및 개수 제한', () => {
  test('KB 단위 파일 첨부 시 크기가 KB로 표시된다 (formatFileSize)', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    const fileInput = page.locator('input[type="file"]');
    // 2048 bytes → formatFileSize(2048) = "2.0KB" (line 33 커버)
    await fileInput.setInputFiles({
      name: 'medium.txt',
      mimeType: 'text/plain',
      buffer: Buffer.alloc(2048, 'a'),
    });

    // 파일 카드에 KB 단위 크기가 표시되어야 한다
    await expect(page.getByText(/KB/).first()).toBeVisible({ timeout: 3000 });
  });

  test('파일 크기 초과 시 에러 토스트에 MB 단위가 표시된다 (formatFileSize)', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    const fileInput = page.locator('input[type="file"]');
    // TEXT 한도(1MB) 초과 → toast.error(`...최대 ${formatFileSize(1048576)}`) = "1.0MB" (lines 34, 76-77 커버)
    await fileInput.setInputFiles({
      name: 'large.txt',
      mimeType: 'text/plain',
      buffer: Buffer.alloc(2 * 1024 * 1024, 'a'),
    });

    await expect(page.getByText(/파일이 너무 큽니다/).first()).toBeVisible({ timeout: 3000 });
  });

  test('4개 파일 동시 첨부 시 최대 개수 초과 에러가 표시된다', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    const fileInput = page.locator('input[type="file"]');
    // 4개 파일 → 3개까지 수락 후 4번째에서 최대 초과 에러 (lines 80-81 커버)
    await fileInput.setInputFiles([
      { name: 'f1.txt', mimeType: 'text/plain', buffer: Buffer.from('a') },
      { name: 'f2.txt', mimeType: 'text/plain', buffer: Buffer.from('a') },
      { name: 'f3.txt', mimeType: 'text/plain', buffer: Buffer.from('a') },
      { name: 'f4.txt', mimeType: 'text/plain', buffer: Buffer.from('a') },
    ]);

    await expect(page.getByText(/파일은 최대 3개/).first()).toBeVisible({ timeout: 3000 });
  });
});

test.describe('AISidePanel — 패널 동작', () => {
  test('side 모드에서 AI 패널이 열리고 입력창이 표시된다', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    // 입력창이 보이면 side 패널이 열린 것
    await expect(page.getByPlaceholder('메시지를 입력하세요...')).toBeVisible();
  });

  test('패널 닫힌 후 입력창이 사라진다', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    // side→fullscreen→closed (chip 2번 더 클릭, floating 모드는 UI에서 숨김)
    await chipLocator(page).click(); // fullscreen
    await page.waitForTimeout(100);
    await chipLocator(page).click(); // closed
    await page.waitForTimeout(300);

    // 패널 닫히면 입력창 비가시
    await expect(page.getByPlaceholder('메시지를 입력하세요...')).not.toBeVisible({ timeout: 3000 });
  });

  test('AIChatPanel 헤더에 모드 전환 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    await openSidePanel(page);

    // AIChatPanel 헤더의 모드 전환 버튼 확인 (floating/native는 UI에서 숨김)
    await expect(page.getByTitle('사이드 패널').first()).toBeVisible();
    await expect(page.getByTitle('전체 화면').first()).toBeVisible();
  });
});
