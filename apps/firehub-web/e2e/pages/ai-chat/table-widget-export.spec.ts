/**
 * show_table 챗 위젯 내보내기 정책(S4) E2E
 *
 * AI 표 위젯의 입력(rows)은 LLM 이 조립하므로 서버 플래그를 실을 수 없다 — 위젯이 화면의 SQL 로
 * `POST /analytics/queries/export-check` 를 물어 exportAllowed === true 일 때만 내보내기(보조 다운로드)를 보인다.
 * 응답 전·실패·false 는 모두 숨김(fail-closed). SSE/세션/패널 헬퍼는 dataset-files-widget.spec.ts 패턴을 따른다.
 */

import type { Page } from '@playwright/test';

import { createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const SQL = 'SELECT v FROM t';

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** show_table tool_use 를 담은 assistant 응답 SSE 이벤트. */
const SHOW_TABLE_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'table-widget-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__show_table',
    input: { title: '결과', sql: SQL, columns: ['v'], rows: [{ v: 'x' }] },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__show_table',
    result: JSON.stringify({ displayed: true }),
    status: 'completed',
  }),
  sseEvent({ type: 'done', inputTokens: 120 }),
];

/** /api/v1/ai/chat SSE 모킹 — 어떤 POST든 show_table 응답을 반환한다. */
async function mockChatShowTable(page: Page, events: string[] = SHOW_TABLE_EVENTS) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      await route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' },
        body: events.join(''),
      });
    },
  );
}

/** AI 세션 목록/생성 API 모킹 (dataset-files-widget.spec.ts 패턴). */
async function mockAiSessions(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1,
          sessionId: 'table-widget-session-1',
          title: null,
          createdAt: '2026-07-20T00:00:00Z',
          updatedAt: '2026-07-20T00:00:00Z',
        }),
      });
    },
  );
}

/** AI 사이드 패널 — 페이지 본문의 같은 이름 버튼(SQL 편집기 등)과 구분한다. */
function panel(page: Page) {
  return page.getByTestId('ai-side-panel');
}

/** 챗 패널을 열고 메시지를 보내 표 위젯이 그려질 때까지 기다린다. */
async function showTable(page: Page) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.waitFor({ state: 'visible', timeout: 5000 });
  await chatInput.fill('표로 보여줘');
  await chatInput.press('Enter');
  // 위젯 본문(셀)이 그려졌다 — 이 시점 이후 내보내기 트리거 유무를 본다
  await expect(page.getByRole('cell', { name: 'x', exact: true })).toBeVisible({ timeout: 10_000 });
}

test.describe('AI 표 위젯 내보내기 정책', () => {
  test('export-check 가 false 면 내보내기를 숨기고, 화면의 SQL 로 물었다', async ({ authenticatedPage: page }) => {
    await mockChatShowTable(page);
    await mockAiSessions(page);
    const check = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/export-check',
      { exportAllowed: false },
      { capture: true },
    );
    await showTable(page);
    expect((await check.waitForRequest()).payload).toEqual({ sql: SQL });
    // SQL 보기 버튼은 그대로 — 내보내기만 빠진다(위젯 액션 영역이 렌더됐음을 함께 확인)
    await expect(panel(page).getByRole('button', { name: 'SQL' })).toBeVisible();
    await expect(panel(page).getByRole('button', { name: '내보내기' })).toHaveCount(0);
  });

  test('export-check 가 실패(500)해도 숨긴다 — fail-closed', async ({ authenticatedPage: page }) => {
    await mockChatShowTable(page);
    await mockAiSessions(page);
    const check = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/export-check',
      { message: 'boom' },
      { status: 500, capture: true },
    );
    await showTable(page);
    await check.waitForRequest();
    await expect(panel(page).getByRole('button', { name: 'SQL' })).toBeVisible();
    await expect(panel(page).getByRole('button', { name: '내보내기' })).toHaveCount(0);
  });

  test('export-check 가 true 면 내보내기가 보이고 CSV 다운로드가 동작한다', async ({ authenticatedPage: page }) => {
    await mockChatShowTable(page);
    await mockAiSessions(page);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/export-check', { exportAllowed: true });
    await showTable(page);
    const trigger = panel(page).getByRole('button', { name: '내보내기' });
    await expect(trigger).toBeVisible();
    const download = page.waitForEvent('download');
    await trigger.click();
    await page.getByRole('button', { name: 'CSV 다운로드' }).click();
    expect((await download).suggestedFilename()).toBe('결과.csv');
  });
});

test.describe('AI 표 위젯 — export-check 응답 전 자리 잡기', () => {
  test('판정 응답 전에는 같은 자리에 보이지 않는 자리 표시를 두고, 허용 응답 뒤 실제 버튼으로 바뀐다', async ({
    authenticatedPage: page,
  }) => {
    await mockChatShowTable(page);
    await mockAiSessions(page);
    // 판정 응답을 붙잡아 두었다가 자리 표시를 확인한 뒤 풀어 준다
    let release: () => void = () => {};
    const gate = new Promise<void>((r) => (release = r));
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/export-check',
      async (route) => {
        await gate;
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ exportAllowed: true }) });
      },
    );
    await showTable(page);
    const placeholder = panel(page).getByTestId('export-check-placeholder');
    await expect(placeholder).toBeAttached();
    await expect(placeholder).toBeHidden(); // visibility:hidden — 자리만 차지
    await expect(panel(page).getByRole('button', { name: '내보내기' })).toHaveCount(0);
    const before = await placeholder.boundingBox();
    release();
    const trigger = panel(page).getByRole('button', { name: '내보내기' });
    await expect(trigger).toBeVisible();
    await expect(placeholder).toHaveCount(0);
    const after = await trigger.boundingBox();
    // 자리 표시와 실제 버튼의 폭이 같아 SQL 버튼·제목이 밀리지 않는다(±1px)
    expect(Math.abs((after?.width ?? 0) - (before?.width ?? -10))).toBeLessThanOrEqual(1);
  });
});

/** show_dataset 위젯(AI 데이터셋 카드) — 내보내기는 화면 rows 를 브라우저에서 직렬화하는 보조 다운로드라
 * 상세 응답의 exportAllowed === true 일 때만 보인다(S4, fail-closed). */
const DATASET_ID = 77;
const SHOW_DATASET_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'table-widget-session-1' }),
  sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__show_dataset', input: { datasetId: DATASET_ID }, status: 'started' }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__show_dataset',
    result: JSON.stringify({ displayed: true }),
    status: 'completed',
  }),
  sseEvent({ type: 'done', inputTokens: 120 }),
];

async function showDataset(page: Page, exportAllowed: boolean) {
  await mockChatShowTable(page, SHOW_DATASET_EVENTS);
  await mockAiSessions(page);
  const detail = createDatasetDetail({ id: DATASET_ID, name: '인사_평가_2026', exportAllowed });
  await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
  await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/data`, {
    columns: detail.columns,
    rows: [{ id: 1, name: '항목 1' }],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
  });
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.waitFor({ state: 'visible', timeout: 5000 });
  await chatInput.fill('데이터셋 보여줘');
  await chatInput.press('Enter');
  // 위젯 제목(데이터셋 이름)과 본문 셀이 그려진 뒤 판정한다
  await expect(panel(page).getByText('인사_평가_2026', { exact: true })).toBeVisible({ timeout: 10_000 });
  await expect(panel(page).getByRole('cell', { name: '항목 1', exact: true })).toBeVisible();
}

test.describe('AI 데이터셋 위젯 내보내기 정책', () => {
  test('exportAllowed=false 면 내보내기를 숨긴다', async ({ authenticatedPage: page }) => {
    await showDataset(page, false);
    await expect(panel(page).getByRole('button', { name: '내보내기' })).toHaveCount(0);
  });

  test('exportAllowed=true 면 내보내기가 보이고 CSV 다운로드가 동작한다(양성 대조)', async ({
    authenticatedPage: page,
  }) => {
    await showDataset(page, true);
    const download = page.waitForEvent('download');
    await panel(page).getByRole('button', { name: '내보내기' }).click();
    await panel(page).getByRole('button', { name: 'CSV 다운로드' }).click();
    expect((await download).suggestedFilename()).toBe('인사_평가_2026.csv');
  });
});
