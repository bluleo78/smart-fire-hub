/**
 * 말줄임(truncate)된 설명 요소의 title E2E 테스트 (WD-1003)
 *
 * 긴 설명이 한 줄로 잘려도 마우스를 올리면 전체를 읽을 수 있도록
 * 다섯 화면의 설명 요소에 title 이 설명 전문으로 붙어 있는지 검증한다.
 * 실제로 잘리는 요소인지(scrollWidth > clientWidth)도 함께 단언한다.
 */
import type { Page } from '@playwright/test';

import {
  createChart,
  createChartListItem,
  createDashboardListItem,
} from '../factories/analytics.factory';
import { createActivityFeedResponse, createActivityItem } from '../factories/dashboard.factory';
import { createColumn, createDatasetDetail } from '../factories/dataset.factory';
import { createPipelineDetail, createTrigger } from '../factories/pipeline.factory';
import { setupDashboardEditorMocks } from '../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { mockCronPreview, TRIGGER_CRON_PREVIEW_PATH } from '../fixtures/cron-preview.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../fixtures/dataset.fixture';
import { grantPipelinePermissions } from '../fixtures/pipeline.fixture';
import { expectLongValueTruncated } from '../fixtures/table-layout';

/** 한 줄에 담기지 않는 200자 설명 */
const LONG = '긴 설명 '.repeat(40).trim();

test.describe('말줄임된 설명의 title (WD-1003)', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('대시보드 에디터 헤더 — 말줄임된 설명은 마우스를 올리면 전체가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1, { description: LONG });
    await page.goto('/analytics/dashboards/1');
    await expectLongValueTruncated(page.locator('p.truncate', { hasText: LONG }), LONG);
  });

  test('대시보드 에디터 차트 추가 목록 — 말줄임된 설명은 마우스를 올리면 전체가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts',
      createPageResponse([createChartListItem({ id: 5, name: '긴 설명 차트', description: LONG })]),
    );
    await page.goto('/analytics/dashboards/1');
    await page.getByRole('button', { name: '편집' }).click();
    await page.getByRole('button', { name: '차트 추가' }).click();
    await expectLongValueTruncated(page.getByRole('dialog').locator('div.truncate', { hasText: LONG }), LONG);
  });

  test('파이프라인 트리거 탭 — 말줄임된 설명은 마우스를 올리면 전체가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await grantPipelinePermissions(page);
    await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', [
      createTrigger({ id: 3, name: '긴 설명 트리거', description: LONG }),
    ]);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockCronPreview(page, TRIGGER_CRON_PREVIEW_PATH);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]));
    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '트리거' }).click();
    await expectLongValueTruncated(page.locator('p.truncate', { hasText: LONG }), LONG);
  });

  test('차트 빌더 대시보드 선택 — 말줄임된 설명은 마우스를 올리면 전체가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, chartType: 'BAR' }));
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse([]));
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards',
      createPageResponse([createDashboardListItem({ id: 7, name: '긴 설명 대시보드', description: LONG })]),
    );
    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '대시보드에 추가' }).click();
    await expectLongValueTruncated(page.getByRole('dialog').locator('div.truncate', { hasText: LONG }), LONG);
  });

  test('홈 최근 활동 — 말줄임된 설명은 마우스를 올리면 전체가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/dashboard/activity',
      createActivityFeedResponse([createActivityItem({ id: 1, title: '긴 설명 활동', description: LONG })]),
    );
    await page.goto('/');
    await expectLongValueTruncated(page.locator('p.truncate', { hasText: LONG }), LONG);
  });
});

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** AI 챗에 show_activity 위젯을 그리게 하는 SSE·세션 모킹 (ai-chat/dataset-files-widget.spec.ts 패턴) */
async function mockChatShowActivity(page: Page) {
  const events = [
    sseEvent({ type: 'init', sessionId: 'activity-session-1' }),
    sseEvent({ type: 'tool_use', toolName: 'mcp__firehub__show_activity', input: { size: 5 }, status: 'started' }),
    sseEvent({
      type: 'tool_result',
      toolName: 'mcp__firehub__show_activity',
      result: JSON.stringify({ displayed: true }),
      status: 'completed',
    }),
    sseEvent({ type: 'done', inputTokens: 120 }),
  ];
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      return route.fulfill({
        status: 200,
        headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' },
        body: events.join(''),
      });
    },
  );
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      // 목록 GET 은 기본 모킹(base.fixture)에 맡기고, 첫 메시지가 만드는 세션 POST 만 응답한다
      if (route.request().method() !== 'POST') return route.fallback();
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1,
          sessionId: 'activity-session-1',
          title: null,
          createdAt: '2026-07-20T00:00:00Z',
          updatedAt: '2026-07-20T00:00:00Z',
        }),
      });
    },
  );
}

/** 컬럼 탭 모킹 — 상세 기본 모킹 위에 설명이 긴 컬럼(id 1)과 설명 없는 컬럼(id 2)을 가진 상세 응답을 덮어쓴다 */
async function mockColumnsTabDataset(page: Page) {
  await setupDatasetDetailMocks(page, 5);
  await mockApi(
    page,
    'GET',
    '/api/v1/datasets/5',
    createDatasetDetail({
      id: 5,
      rowCount: 0,
      columns: [
        createColumn({ id: 1, columnName: 'long_col', displayName: '긴 설명 필드', description: LONG, columnOrder: 0 }),
        createColumn({ id: 2, columnName: 'empty_col', displayName: '빈 설명 필드', description: '', columnOrder: 1 }),
      ],
    }),
  );
}

/** 설명 없는 컬럼의 「-」 를 감싼 span.truncate 에는 title 을 달지 않는다 — 조회 전용·편집 가능 두 갈래 공통 */
async function expectDashHasNoTitle(page: Page) {
  const dash = page.locator('span.truncate', { has: page.locator('span.text-muted-foreground', { hasText: /^-$/ }) });
  await expect(dash).toHaveCount(1);
  await expect(dash).not.toHaveAttribute('title');
}

async function openColumnsTab(page: Page) {
  await page.goto('/data/datasets/5');
  await page.getByRole('tab', { name: '필드' }).click();
  await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });
}

test.describe('말줄임된 설명의 title (WD-1027)', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('AI 위젯 최근 활동 — 말줄임된 활동 제목·설명은 마우스를 올리면 전체가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockChatShowActivity(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/dashboard/activity',
      createActivityFeedResponse([createActivityItem({ id: 1, title: LONG, description: `${LONG} 설명` })]),
    );
    await page.goto('/', { waitUntil: 'commit' });
    await page.getByText('AI 어시스턴트').first().click();
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.waitFor({ state: 'visible', timeout: 5000 });
    await chatInput.fill('최근 활동 보여줘');
    await chatInput.press('Enter');

    // 위젯 머리 제목에도 title 이 붙는다(WidgetShell) — 그 머리를 가진 위젯 카드 안에서만 활동 버튼을 찾는다
    // (홈 화면의 최근 활동 목록도 같은 모킹 값을 그리므로 위젯 범위로 좁힌다)
    const widget = page.getByTestId('widget-shell').filter({ has: page.locator('span[title="최근 활동"]') });
    await expect(widget).toHaveCount(1);
    // 위젯 카드 안 활동 버튼의 제목(span.truncate)·설명(p.truncate)
    const card = widget.getByRole('button').filter({ has: page.locator('p.truncate') });
    await expectLongValueTruncated(card.locator('span.truncate'), LONG);
    await expectLongValueTruncated(card.locator('p.truncate'), `${LONG} 설명`);
  });

  test('데이터셋 컬럼 설명(조회 전용) — 말줄임된 설명은 마우스를 올리면 전체가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await grantDatasetPermissions(page, ['dataset:read', 'data:read']);
    await mockColumnsTabDataset(page);
    await openColumnsTab(page);
    await expectLongValueTruncated(page.locator('span.truncate', { hasText: LONG }), LONG);
    await expectDashHasNoTitle(page);
  });

  test('데이터셋 컬럼 설명(편집 가능) — 말줄임된 설명은 전체가 보이고, 설명 없는 「-」 에는 title 이 없다', async ({
    authenticatedPage: page,
  }) => {
    await grantDatasetPermissions(page);
    await mockColumnsTabDataset(page);
    await openColumnsTab(page);
    await expectLongValueTruncated(page.locator('span.truncate', { hasText: LONG }), LONG);
    await expectDashHasNoTitle(page);
  });
});
