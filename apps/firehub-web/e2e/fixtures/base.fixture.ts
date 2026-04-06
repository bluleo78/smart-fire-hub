import { type Page,test as base } from '@playwright/test';

import { mockApi } from './api-mock';

/**
 * 홈 페이지(대시보드) API 모킹 헬퍼
 * - authenticatedPage로 로그인 후 홈('/')으로 리다이렉트될 때
 *   대시보드가 호출하는 API들을 미리 모킹해 둔다.
 * - 이 함수를 호출하지 않으면 대시보드 API 요청이 실패하여 테스트가 불안정해진다.
 */
export async function setupHomeMocks(page: Page) {
  // 대시보드 통계 — 전체 데이터셋/파이프라인 요약 수치
  await mockApi(page, 'GET', '/api/v1/dashboard/stats', {
    totalDatasets: 10,
    sourceDatasets: 6,
    derivedDatasets: 4,
    totalPipelines: 5,
    activePipelines: 3,
    recentImports: [],
    recentExecutions: [],
  });

  // 대시보드 헬스 — 파이프라인/데이터셋 상태 요약
  await mockApi(page, 'GET', '/api/v1/dashboard/health', {
    pipelineHealth: { total: 5, healthy: 3, failing: 1, running: 0, disabled: 1 },
    datasetHealth: { total: 10, fresh: 8, stale: 1, empty: 1 },
  });

  // 주의 필요 항목 목록 (빈 배열로 모킹)
  await mockApi(page, 'GET', '/api/v1/dashboard/attention', []);

  // 최근 활동 로그 (빈 목록으로 모킹)
  await mockApi(page, 'GET', '/api/v1/dashboard/activity', {
    items: [],
    totalCount: 0,
    hasMore: false,
  });

  // 분석 대시보드 목록 (빈 페이지 응답으로 모킹)
  await mockApi(page, 'GET', '/api/v1/analytics/dashboards', {
    content: [],
    page: 0,
    size: 5,
    totalElements: 0,
    totalPages: 0,
  });

  // 데이터셋 목록 (빈 페이지 응답으로 모킹)
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [],
    page: 0,
    size: 5,
    totalElements: 0,
    totalPages: 0,
  });

  // 읽지 않은 AI 프로액티브 메시지 수 (0으로 모킹)
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
}

export { base };
