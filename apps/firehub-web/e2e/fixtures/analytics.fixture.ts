import type { Page } from '@playwright/test';

import type { Dashboard } from '../../src/types/analytics';
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
 * - 대시보드 상세, 위젯 차트의 단건 메타·데이터, 차트 목록을 모킹한다.
 * - 대시보드 화면은 위젯마다 `/charts/{id}/data` 로 표시 데이터를 가져온다(#778 — 일괄
 *   `/dashboards/{id}/data` 는 호출하지 않는다). 응답은 실제 API 계약(`{chart, queryResult}`)과 같은 형태다.
 * @param dashboardId - 모킹할 대시보드 ID (기본값: 1)
 * @param dashboardOverrides - 대시보드 상세 응답에 덮어쓸 필드 (예: isShared)
 */
export async function setupDashboardEditorMocks(
  page: Page,
  dashboardId = 1,
  dashboardOverrides?: Partial<Dashboard>,
) {
  const dashboard = createDashboard({ id: dashboardId, ...dashboardOverrides });
  await mockApi(page, 'GET', `/api/v1/analytics/dashboards/${dashboardId}`, dashboard);
  // 위젯 차트(기본 위젯 chartId=1)의 메타와 단건 데이터 — 실제 ChartDataResponse 형태
  const chart = createChart({ id: 1, name: '테스트 차트' });
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chart);
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', {
    chart,
    queryResult: createQueryResult({
      columns: ['name', 'value'],
      rows: [{ name: '항목 A', value: 100 }],
      totalRows: 1,
    }),
    // S4: 조회자 기준 내보내기 가능 — 대시보드 PDF 버튼 노출 조건(기본 허용)
    exportAllowed: true,
  });
  // 위젯 추가 다이얼로그에서 사용할 차트 목록
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));
}
