import type { Page } from '@playwright/test';

import {
  createChart,
  createChartListItem,
  createDashboard,
  createDashboardListItem,
  createQueryResult,
  createSavedQuery,
  createSavedQueryList,
  createSchemaInfo,
} from '../factories/analytics.factory';
import { createPageResponse, mockApi } from './api-mock';

/**
 * 분석(Analytics) 도메인 모킹 헬퍼
 * - 쿼리/차트/대시보드 페이지 테스트에서 공통으로 사용하는 API 모킹 함수를 제공한다.
 * - 백엔드 없이 분석 관련 E2E 테스트를 실행할 수 있도록 지원한다.
 */

/**
 * 쿼리 목록 페이지 API 모킹
 * - 저장된 쿼리 목록, 폴더 목록을 모킹한다.
 * @param count - 목록에 포함할 쿼리 수 (기본값: 5)
 */
export async function setupQueryListMocks(page: Page, count = 5) {
  await mockApi(
    page,
    'GET',
    '/api/v1/analytics/queries',
    createPageResponse(createSavedQueryList(count)),
  );
  await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);
}

/**
 * 쿼리 에디터 페이지 API 모킹 (기존 쿼리 편집)
 * - 저장된 쿼리 상세, 스키마 정보, 폴더 목록을 모킹한다.
 * @param queryId - 모킹할 쿼리 ID (기본값: 1)
 */
export async function setupQueryEditorMocks(page: Page, queryId = 1) {
  const query = createSavedQuery({ id: queryId });
  await mockApi(page, 'GET', `/api/v1/analytics/queries/${queryId}`, query);
  await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', createSchemaInfo());
  await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);
}

/**
 * 새 쿼리 에디터 페이지 API 모킹
 * - 스키마 정보, 폴더 목록만 모킹한다 (새 쿼리이므로 쿼리 상세 불필요).
 */
export async function setupNewQueryEditorMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', createSchemaInfo());
  await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);
}

/**
 * 쿼리 실행 결과 API 모킹
 * - ad-hoc 쿼리 실행 결과를 모킹한다.
 */
export async function setupQueryExecuteMock(page: Page) {
  await mockApi(
    page,
    'POST',
    '/api/v1/analytics/queries/execute',
    createQueryResult(),
  );
}

/**
 * 차트 목록 페이지 API 모킹
 * - 차트 목록을 모킹한다.
 * @param count - 목록에 포함할 차트 수 (기본값: 3)
 */
export async function setupChartListMocks(page: Page, count = 3) {
  const charts = Array.from({ length: count }, (_, i) =>
    createChartListItem({ id: i + 1, name: `테스트 차트 ${i + 1}` }),
  );
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse(charts));
}

/**
 * 차트 빌더 페이지 API 모킹 (기존 차트 편집)
 * - 차트 상세, 쿼리 목록을 모킹한다.
 * @param chartId - 모킹할 차트 ID (기본값: 1)
 */
export async function setupChartBuilderMocks(page: Page, chartId = 1) {
  const chart = createChart({ id: chartId });
  await mockApi(page, 'GET', `/api/v1/analytics/charts/${chartId}`, chart);
  await mockApi(
    page,
    'GET',
    '/api/v1/analytics/queries',
    createPageResponse(createSavedQueryList(3)),
  );
}

/**
 * 새 차트 빌더 페이지 API 모킹
 * - 쿼리 목록만 모킹한다 (새 차트이므로 차트 상세 불필요).
 */
export async function setupNewChartBuilderMocks(page: Page) {
  await mockApi(
    page,
    'GET',
    '/api/v1/analytics/queries',
    createPageResponse(createSavedQueryList(3)),
  );
}

/**
 * 대시보드 목록 페이지 API 모킹
 * - 대시보드 목록을 모킹한다.
 * @param count - 목록에 포함할 대시보드 수 (기본값: 3)
 */
export async function setupDashboardListMocks(page: Page, count = 3) {
  const dashboards = Array.from({ length: count }, (_, i) =>
    createDashboardListItem({ id: i + 1, name: `테스트 대시보드 ${i + 1}` }),
  );
  await mockApi(page, 'GET', '/api/v1/analytics/dashboards', createPageResponse(dashboards));
}

/**
 * 대시보드 에디터 페이지 API 모킹
 * - 대시보드 상세, 대시보드 데이터(위젯 배치), 차트 목록을 모킹한다.
 * @param dashboardId - 모킹할 대시보드 ID (기본값: 1)
 */
export async function setupDashboardEditorMocks(page: Page, dashboardId = 1) {
  const dashboard = createDashboard({ id: dashboardId });
  await mockApi(page, 'GET', `/api/v1/analytics/dashboards/${dashboardId}`, dashboard);
  // 대시보드 배치 데이터 — 위젯별 차트 렌더링에 사용
  await mockApi(page, 'GET', `/api/v1/analytics/dashboards/${dashboardId}/data`, {
    dashboardId,
    widgets: [
      {
        widgetId: 1,
        chartId: 1,
        chartType: 'BAR',
        chartName: '테스트 차트',
        columns: ['name', 'value'],
        rows: [{ name: '항목 A', value: 100 }],
        error: null,
      },
    ],
  });
  // 위젯 추가 다이얼로그에서 사용할 차트 목록
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));
}
