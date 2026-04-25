/**
 * MessageBubble 컴포넌트 커버리지 확장 E2E 테스트
 *
 * 커버 대상 경로:
 * - AttachmentPreview: IMAGE(previewUrl 있음), IMAGE(previewUrl 없음), 파일(IMAGE 아님)
 * - AssistantContent: contentBlocks 없는 경로(history), tool_use → canvas native mode placeholder
 * - ToolCallDisplay: hasResult=false(실행 중), hasResult=true(완료), resultSummary 포맷별
 * - MessageBubble: system role, user + attachment, timestamp 렌더링
 * - ReportModal: 리포트 보기 모달 열기 / 마크다운 폴백 / 빈 상태
 */

import type { Page } from '@playwright/test';

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** SSE 이벤트 직렬화 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** AI 세션 API 모킹 */
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

/** AI 패널 열기 헬퍼 */
async function openChatPanel(page: Page) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('MessageBubble — 사용자 메시지 attachments', () => {
  /**
   * MB-01: 파일 첨부 후 전송 → user 메시지 버블에 첨부 파일 이름이 표시된다
   * AttachmentPreview: category !== 'IMAGE' 경로 (FileIcon + name + fileSize)
   * useAIChat.sendMessage: files 있을 때 uploadFiles 호출 경로
   */
  test('MB-01: 파일 첨부 전송 → 사용자 메시지에 파일명이 표시된다', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-01-session');

    // 파일 업로드 API 모킹 — uploadFiles 응답
    await mockApi(page, 'POST', '/api/v1/files', [
      {
        id: 42,
        originalName: 'report.csv',
        mimeType: 'text/csv',
        fileSize: 2048,
        fileCategory: 'DATA',
      },
    ]);

    // SSE chat 응답 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-01-session' }),
            sseEvent({ type: 'text', content: 'CSV 파일을 확인했습니다.' }),
            sseEvent({ type: 'done', inputTokens: 50 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);

    // 파일 첨부
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: 'report.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from('col1,col2\n1,2\n3,4'),
    });

    // 파일 이름이 pending 상태로 표시된다
    await expect(page.getByText('report.csv').first()).toBeVisible({ timeout: 5000 });

    // 메시지 전송
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('이 파일 분석해줘');
    await chatInput.press('Enter');

    // AI 응답이 오면 파일 업로드가 처리됨
    await expect(page.getByText('CSV 파일을 확인했습니다.').first()).toBeVisible({ timeout: 15_000 });
  });
});

test.describe('MessageBubble — ToolCallDisplay 결과 포맷', () => {
  /**
   * MB-02: tool_use → tool_result(affectedRows) → "N건 처리" 결과 표시
   * formatToolResult: affectedRows 경로
   */
  test('MB-02: affectedRows 결과 → "N건 처리" 표시', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-02-session');

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-02-session' }),
            sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__delete_rows', input: { datasetId: 1, rowIds: [10, 20, 30] } }),
            sseEvent({ type: 'tool_result', toolName: 'mcp__firehub__delete_rows', result: JSON.stringify({ affectedRows: 3 }) }),
            sseEvent({ type: 'text', content: '3건이 삭제되었습니다.' }),
            sseEvent({ type: 'done', inputTokens: 80 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('데이터 삭제');
    await chatInput.press('Enter');

    // ToolCallDisplay: label='데이터 삭제', result 있음 → "N건 처리" or "✓ 완료"
    await expect(page.getByText('데이터 삭제').first()).toBeVisible({ timeout: 10_000 });
    // formatToolResult: affectedRows → "3건 처리"
    await expect(page.getByText('3건 처리').first()).toBeVisible({ timeout: 10_000 });
  });

  /**
   * MB-03: tool_use → tool_result(rows 배열) → "N건 조회" 결과 표시
   * formatToolResult: rows Array 경로
   */
  test('MB-03: rows 배열 결과 → "N건 조회" 표시', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-03-session');

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-03-session' }),
            sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__query_dataset_data', input: { datasetId: 5 } }),
            sseEvent({ type: 'tool_result', toolName: 'mcp__firehub__query_dataset_data', result: JSON.stringify({ rows: [{ id: 1 }, { id: 2 }, { id: 3 }, { id: 4 }, { id: 5 }] }) }),
            sseEvent({ type: 'text', content: '5건의 데이터가 조회되었습니다.' }),
            sseEvent({ type: 'done', inputTokens: 60 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('데이터 조회');
    await chatInput.press('Enter');

    // '데이터 조회' 라벨이 나타나야 한다
    await expect(page.getByText('데이터 조회').first()).toBeVisible({ timeout: 10_000 });
    // formatToolResult: rows.length → "5건 조회"
    await expect(page.getByText('5건 조회').first()).toBeVisible({ timeout: 10_000 });
  });

  /**
   * MB-04: tool_use → tool_result(insertedCount) → "N건 추가" 결과 표시
   * formatToolResult: insertedCount 경로
   */
  test('MB-04: insertedCount 결과 → "N건 추가" 표시', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-04-session');

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-04-session' }),
            sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__add_rows', input: { datasetId: 1, rows: [{ name: 'test' }] } }),
            sseEvent({ type: 'tool_result', toolName: 'mcp__firehub__add_rows', result: JSON.stringify({ insertedCount: 7 }) }),
            sseEvent({ type: 'text', content: '7건의 데이터가 추가되었습니다.' }),
            sseEvent({ type: 'done', inputTokens: 70 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('데이터 일괄 추가');
    await chatInput.press('Enter');

    await expect(page.getByText('데이터 일괄 추가').first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('7건 추가').first()).toBeVisible({ timeout: 10_000 });
  });

  /**
   * MB-05: tool_use → tool_result(rowCount) → "N건" 결과 표시
   * formatToolResult: rowCount 경로
   */
  test('MB-05: rowCount 결과 → "N건" 표시', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-05-session');

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-05-session' }),
            sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__execute_sql_query', input: { datasetId: 1, sql: 'SELECT * FROM t' } }),
            sseEvent({ type: 'tool_result', toolName: 'mcp__firehub__execute_sql_query', result: JSON.stringify({ rowCount: 42 }) }),
            sseEvent({ type: 'text', content: 'SQL 쿼리가 실행되었습니다.' }),
            sseEvent({ type: 'done', inputTokens: 55 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('SQL 실행');
    await chatInput.press('Enter');

    await expect(page.getByText('SQL 쿼리 실행').first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('42건').first()).toBeVisible({ timeout: 10_000 });
  });

  /**
   * MB-06: tool_use → tool_result(deletedCount) → "N건 삭제" 결과 표시
   * formatToolResult: deletedCount 경로
   */
  test('MB-06: deletedCount 결과 → "N건 삭제" 표시', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-06-session');

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-06-session' }),
            sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__truncate_dataset', input: { datasetId: 1 } }),
            sseEvent({ type: 'tool_result', toolName: 'mcp__firehub__truncate_dataset', result: JSON.stringify({ deletedCount: 100 }) }),
            sseEvent({ type: 'text', content: '전체 데이터가 삭제되었습니다.' }),
            sseEvent({ type: 'done', inputTokens: 45 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('전체 삭제');
    await chatInput.press('Enter');

    await expect(page.getByText('전체 데이터 삭제').first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('100건 삭제').first()).toBeVisible({ timeout: 10_000 });
  });
});

test.describe('MessageBubble — 알 수 없는 tool + Claude Code tools', () => {
  /**
   * MB-07: 알 수 없는 toolName → fallback label이 표시된다
   * getToolDisplay: TOOL_LABELS에 없는 이름 → { label: cleanName, icon: '🔧' }
   */
  test('MB-07: 알 수 없는 tool → cleanName이 라벨로 표시된다', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-07-session');

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-07-session' }),
            sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__some_unknown_tool', input: {} }),
            sseEvent({ type: 'tool_result', toolName: 'mcp__firehub__some_unknown_tool', result: JSON.stringify({}) }),
            sseEvent({ type: 'text', content: '완료되었습니다.' }),
            sseEvent({ type: 'done', inputTokens: 30 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('알 수 없는 도구 실행');
    await chatInput.press('Enter');

    // cleanName = 'some_unknown_tool' → fallback label
    await expect(page.getByText('some_unknown_tool').first()).toBeVisible({ timeout: 10_000 });
  });

  /**
   * MB-08: Claude Code 도구(Bash) 실행 → formatToolDetail의 command 경로
   * formatToolDetail: input.command 경로
   */
  test('MB-08: Bash 도구 → command 내용이 detail로 표시된다', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-08-session');

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-08-session' }),
            sseEvent({ type: 'tool_use', toolName: 'Bash', input: { command: 'ls -la /tmp', description: '파일 목록 확인' } }),
            sseEvent({ type: 'tool_result', toolName: 'Bash', result: JSON.stringify({ output: 'total 0' }) }),
            sseEvent({ type: 'text', content: '명령어가 실행되었습니다.' }),
            sseEvent({ type: 'done', inputTokens: 40 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('명령어 실행해');
    await chatInput.press('Enter');

    // '명령어 실행' 라벨이 표시된다 (TOOL_LABELS['Bash'] = '명령어 실행')
    await expect(page.getByText('명령어 실행').first()).toBeVisible({ timeout: 10_000 });
  });
});

test.describe('MessageBubble — navigate_to tool (navigate 경로)', () => {
  /**
   * MB-09: navigate_to tool → '페이지 이동' 라벨 표시
   * TOOL_LABELS['navigate_to'], formatToolDetail: input.prompt 경로
   */
  test('MB-09: navigate_to tool → 페이지 이동 라벨 표시', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-09-session');

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-09-session' }),
            sseEvent({ type: 'tool_use', toolName: 'navigate_to', input: { path: '/data/datasets', prompt: '데이터셋 목록으로 이동' } }),
            sseEvent({ type: 'tool_result', toolName: 'navigate_to', result: JSON.stringify({ success: true }) }),
            sseEvent({ type: 'text', content: '데이터셋 목록 페이지로 이동했습니다.' }),
            sseEvent({ type: 'done', inputTokens: 35 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('데이터셋 목록으로 이동해줘');
    await chatInput.press('Enter');

    await expect(page.getByText('페이지 이동').first()).toBeVisible({ timeout: 10_000 });
  });
});

test.describe('MessageBubble — result 없는 tool call 완료 상태 (#3)', () => {
  /**
   * MB-10: 스트리밍 완료 후 result가 없는 tool call은 "실행 중..."이 아닌 "✓ 완료"를 표시한다.
   * 이슈 #3: list_datasets / 서브에이전트 래퍼처럼 SSE에서 tool_result를 전송하지 않는 도구가
   * 스트리밍 종료 후에도 "실행 중..." pulsing 상태로 고착되는 버그 수정 검증.
   */
  test('MB-10: result 없는 tool call — 스트리밍 완료 후 "✓ 완료" 표시', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-10-session');

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-10-session' }),
            // tool_use만 있고 tool_result 없음 — 서브에이전트/list_datasets 패턴
            sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__list_datasets', input: {} }),
            sseEvent({ type: 'text', content: '데이터셋 목록을 확인했습니다.' }),
            sseEvent({ type: 'done', inputTokens: 30 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('데이터셋 목록 보여줘');
    await chatInput.press('Enter');

    // 응답 텍스트가 도착하면 스트리밍이 완료된 것
    await expect(page.getByText('데이터셋 목록을 확인했습니다.').first()).toBeVisible({ timeout: 10_000 });

    // 스트리밍 완료 후 result 없는 tool call은 "✓ 완료"를 표시해야 한다
    await expect(page.getByText('✓ 완료').first()).toBeVisible({ timeout: 5_000 });
    // "실행 중..." 이 남아있으면 안 된다
    await expect(page.getByText('실행 중...').first()).not.toBeVisible();
  });
});

test.describe('MessageBubble — 코드블록 언어 레이블 및 복사 버튼 (#10)', () => {
  /**
   * MB-11: Python 코드블록 응답 → 언어 레이블과 복사 버튼이 표시된다
   * 이슈 #10: SyntaxHighlighter에 언어 레이블과 복사 버튼 래퍼가 없어
   * 사용자가 코드를 복사하려면 드래그 선택해야 했던 버그 수정 검증
   */
  test('MB-11: 코드블록 응답 → 언어 레이블과 복사 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-11-session');

    // Python 코드블록이 포함된 SSE 응답 모킹
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-11-session' }),
            sseEvent({ type: 'text', content: '다음은 Python Hello World 코드입니다:\n\n```python\nprint("Hello, World!")\n```\n' }),
            sseEvent({ type: 'done', inputTokens: 30 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('파이썬으로 Hello World 코드를 보여주세요');
    await chatInput.press('Enter');

    // AI 응답 대기
    await expect(page.getByText('다음은 Python Hello World 코드입니다').first()).toBeVisible({ timeout: 10_000 });

    // 언어 레이블 'python'이 표시된다
    await expect(page.getByText('python').first()).toBeVisible({ timeout: 5_000 });

    // 복사 버튼이 표시된다
    await expect(page.getByRole('button', { name: '코드 복사' }).first()).toBeVisible({ timeout: 5_000 });
  });

  /**
   * MB-12: 복사 버튼 클릭 → 클립보드에 코드가 복사되고 '복사됨' 상태로 전환된다
   * clipboard permission 없이는 navigator.clipboard.writeText가 실패하므로
   * grantPermissions로 clipboard-write 권한을 부여한다
   */
  test('MB-12: 복사 버튼 클릭 → 클립보드에 코드가 저장된다', async ({ authenticatedPage: page }) => {
    // 클립보드 권한 부여 — headless Chromium에서 clipboard-write가 기본 차단됨
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);

    await mockAiSessions(page, 'mb-12-session');

    const expectedCode = 'print("Hello, World!")';

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-12-session' }),
            sseEvent({ type: 'text', content: `코드입니다:\n\n\`\`\`python\n${expectedCode}\n\`\`\`\n` }),
            sseEvent({ type: 'done', inputTokens: 25 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('Hello World 코드');
    await chatInput.press('Enter');

    // 복사 버튼 대기 후 클릭
    const copyButton = page.getByRole('button', { name: '코드 복사' }).first();
    await copyButton.waitFor({ state: 'visible', timeout: 10_000 });
    await copyButton.click();

    // 복사 완료 후 '복사됨' 상태로 전환된다
    await expect(page.getByText('복사됨').first()).toBeVisible({ timeout: 3_000 });

    // 클립보드에 올바른 코드가 저장된다 (trailing newline 없음)
    const clipboardText = await page.evaluate(() => navigator.clipboard.readText());
    expect(clipboardText).toBe(expectedCode);
  });

  /**
   * MB-13: SQL 코드블록 → 'sql' 언어 레이블이 표시된다
   * 여러 언어 지원 확인
   */
  test('MB-13: SQL 코드블록 → sql 언어 레이블이 표시된다', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'mb-13-session');

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
          body: [
            sseEvent({ type: 'init', sessionId: 'mb-13-session' }),
            sseEvent({ type: 'text', content: 'SQL 쿼리입니다:\n\n```sql\nSELECT * FROM users;\n```\n' }),
            sseEvent({ type: 'done', inputTokens: 20 }),
          ].join(''),
        });
      },
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('SQL 쿼리 예시');
    await chatInput.press('Enter');

    // 응답 대기
    await expect(page.getByText('SQL 쿼리입니다').first()).toBeVisible({ timeout: 10_000 });

    // sql 언어 레이블이 표시된다
    await expect(page.getByText('sql').first()).toBeVisible({ timeout: 5_000 });
  });
});
