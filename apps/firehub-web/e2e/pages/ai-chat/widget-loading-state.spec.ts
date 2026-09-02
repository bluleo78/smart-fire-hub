import type { Page } from '@playwright/test';

import { createDatasetDetail } from '../../factories/dataset.factory';
import { createPipelineDetail } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * AI 위젯 로딩 상태 E2E 회귀 테스트 (#434)
 *
 * 무엇: 위젯 본문이 로딩 중일 때 정적 "로딩 중..." 텍스트가 아니라 문서가 지정한 두 처방
 *   (06-feedback-states §A.4 Loader2 스피너 / §Skeleton 크기 규칙의 행 스켈레톤)이 실제로
 *   렌더되는지 검증한다.
 *
 * 왜 이렇게 단언하나: "'로딩 중...' 텍스트가 사라졌다"만 확인하면 로딩 표시를 통째로 지워도
 *   통과하는 공허한 테스트가 된다. 그래서 모션 요소가 DOM 에 실제로 있는지를 본다 —
 *   스피너는 `animate-spin`, 스켈레톤은 `animate-pulse` 로 클래스가 다르므로 한쪽 단언을
 *   양쪽에 재사용하면 안 된다.
 *
 * 로딩 상태 재현: 대상 API 응답을 `route.fulfill` 직전에 지연시켜 로딩 창을 열어 둔다.
 */

const DATASET_ID = 77;
const PIPELINE_ID = 88;

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** 지정한 위젯 도구를 호출하는 assistant 응답 SSE 이벤트 시퀀스. */
function widgetEvents(toolName: string, input: Record<string, unknown>): string[] {
  return [
    sseEvent({ type: 'init', sessionId: 'widget-loading-session-1' }),
    sseEvent({ type: 'tool_use', toolName, input, status: 'started' }),
    sseEvent({
      type: 'tool_result',
      toolName,
      result: JSON.stringify({ displayed: true, ...input }),
      status: 'completed',
    }),
    sseEvent({ type: 'done', inputTokens: 100 }),
  ];
}

/** /api/v1/ai/chat SSE 모킹 — 어떤 POST 든 지정 위젯 응답을 반환한다. */
async function mockChatWidget(page: Page, toolName: string, input: Record<string, unknown>) {
  const body = widgetEvents(toolName, input).join('');
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      await route.fulfill({
        status: 200,
        headers: {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
          Connection: 'keep-alive',
        },
        body,
      });
    },
  );
}

/** AI 세션 목록/생성 API 모킹. */
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
          sessionId: 'widget-loading-session-1',
          title: null,
          createdAt: '2026-07-20T00:00:00Z',
          updatedAt: '2026-07-20T00:00:00Z',
        }),
      });
    },
  );
}

/** AI 챗 사이드 패널을 열고 메시지를 보낸다. */
async function askInChat(page: Page, message: string) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.waitFor({ state: 'visible', timeout: 5000 });
  await chatInput.fill(message);
  await chatInput.press('Enter');
}

/**
 * 지정 경로 응답을 `delayMs` 만큼 늦춘다 — 로딩 창을 단언 가능한 길이로 벌린다.
 * 지연은 fulfill 직전에만 넣어 응답 본문 자체는 정상값을 유지한다.
 */
async function mockDelayed(
  page: Page,
  pathname: string,
  bodyJson: unknown,
  delayMs: number,
) {
  await page.route(
    (url) => url.pathname === pathname,
    async (route) => {
      await new Promise((r) => setTimeout(r, delayMs));
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(bodyJson),
      });
    },
  );
}

test.describe('AI 위젯 로딩 상태 (#434)', () => {
  test('본문 전체 로딩인 파이프라인 위젯은 Loader2 스피너를 렌더한다', async ({
    authenticatedPage: page,
  }) => {
    await mockChatWidget(page, 'mcp__firehub__show_pipeline', { pipelineId: PIPELINE_ID });
    await mockAiSessions(page);

    // 두 조회 중 상세만 지연시켜도 위젯 isLoading 이 유지된다(pipelineLoading || execLoading).
    // 홈 화면이 건드리지 않는 경로라 패널을 여는 동안 로딩 창이 소모되지 않는다.
    await mockDelayed(
      page,
      `/api/v1/pipelines/${PIPELINE_ID}`,
      createPipelineDetail({ id: PIPELINE_ID, name: '야간 적재' }),
      3000,
    );
    await mockApi(page, 'GET', `/api/v1/pipelines/${PIPELINE_ID}/executions`, []);

    await askInChat(page, '파이프라인 상태 보여줘');

    // 위젯 본문에 스피너(animate-spin)가 실제로 렌더된다 — 텍스트 부재만으로는 공허하다.
    const spinner = page.locator('[role="status"] .animate-spin');
    await expect(spinner.first()).toBeVisible({ timeout: 10_000 });
    // 시각 텍스트는 없애되 스크린리더 안내는 남아 있어야 한다.
    await expect(
      page.getByRole('status').filter({ hasText: '파이프라인 불러오는 중' }),
    ).toHaveCount(1);
    // 회귀 대상이던 정적 텍스트는 더 이상 없다.
    await expect(page.getByText('로딩 중...', { exact: true })).toHaveCount(0);

    // 지연이 끝나면 로딩 표시가 사라진다(로딩 상태에 갇히지 않음).
    await expect(spinner).toHaveCount(0, { timeout: 10_000 });
  });

  test('행 목록인 파일 위젯은 animate-pulse 스켈레톤을 렌더한다', async ({
    authenticatedPage: page,
  }) => {
    await mockChatWidget(page, 'mcp__firehub__show_dataset_files', { datasetId: DATASET_ID });
    await mockAiSessions(page);
    await mockApi(
      page,
      'GET',
      `/api/v1/datasets/${DATASET_ID}`,
      createDatasetDetail({
        id: DATASET_ID,
        name: '장비 학습 데이터',
        storageType: 'FILE',
        originType: 'SOURCE',
        columns: [],
        rowCount: null,
      }),
    );
    await mockDelayed(
      page,
      `/api/v1/datasets/${DATASET_ID}/objects`,
      { objects: [], nextToken: null, hasMore: false },
      3000,
    );

    await askInChat(page, '장비 학습 데이터 파일 목록 보여줘');

    // 스켈레톤은 스피너와 클래스가 다르다(animate-pulse). 문서 처방대로 h-10 행이 쌓인다.
    const skeletonRows = page.locator('[role="status"] [data-slot="skeleton"]');
    await expect(skeletonRows.first()).toBeVisible({ timeout: 10_000 });
    await expect(skeletonRows).toHaveCount(5);
    await expect(skeletonRows.first()).toHaveClass(/animate-pulse/);
    // 회귀 대상이던 정적 텍스트는 더 이상 없다.
    await expect(page.getByText('불러오는 중...', { exact: true })).toHaveCount(0);

    // 지연이 끝나면 로딩 표시가 사라진다(로딩 상태에 갇히지 않음).
    await expect(skeletonRows).toHaveCount(0, { timeout: 10_000 });
  });
});
