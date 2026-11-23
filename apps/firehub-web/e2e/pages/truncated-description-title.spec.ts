/**
 * 말줄임(truncate)된 설명 요소의 title E2E 테스트 (WD-1003)
 *
 * 긴 설명이 한 줄로 잘려도 마우스를 올리면 전체를 읽을 수 있도록
 * 다섯 화면의 설명 요소에 title 이 설명 전문으로 붙어 있는지 검증한다.
 * 실제로 잘리는 요소인지(scrollWidth > clientWidth)도 함께 단언한다.
 */
import {
  createChart,
  createChartListItem,
  createDashboardListItem,
} from '../factories/analytics.factory';
import { createActivityFeedResponse, createActivityItem } from '../factories/dashboard.factory';
import { createPipelineDetail, createTrigger } from '../factories/pipeline.factory';
import { setupDashboardEditorMocks } from '../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../fixtures/api-mock';
import { test } from '../fixtures/auth.fixture';
import { mockCronPreview, TRIGGER_CRON_PREVIEW_PATH } from '../fixtures/cron-preview.fixture';
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
