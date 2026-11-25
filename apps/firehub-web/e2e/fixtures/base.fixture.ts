import { type Locator, type Page, test as base } from '@playwright/test';

import { mockApi } from './api-mock';

/**
 * 한글 조합이 끝나는 순간 maxlength 를 넘긴 값이 들어오는 상황을 흉내 낸다 (WD-1131).
 * Chromium fill() 은 maxlength 를 지키므로, 조합 시작 → 값 직접 주입 → input → compositionend 순으로
 * 이벤트를 보내 조합이 끝날 때 maxlength 를 지키지 않는 브라우저를 재현한다.
 */
export async function simulateOverlongCompositionEnd(locator: Locator, text: string) {
  await locator.evaluate((el: HTMLInputElement, value: string) => {
    el.focus();
    el.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    setter.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: value.slice(0, 1) }));
  }, text);
}

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
    pipelineHealth: {
      total: 5,
      healthy: 3,
      failing: 1,
      running: 0,
      disabled: 1,
      trend: [1, 3, 2, 4, 1, 2, 3],
    },
    datasetHealth: {
      total: 10,
      fresh: 8,
      stale: 1,
      empty: 1,
      trend: [2, 1, 3, 2, 4, 1, 2],
    },
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

  // AI 세션 목록 — AppLayout이 항상 조회하므로 abort()로 proxy ECONNREFUSED 경고 억제
  // mockApi로 성공 응답을 주면 AppLayout 리렌더링이 발생해 다른 테스트가 불안정해질 수 있음
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => route.abort(),
  );

  // 알림 SSE 스트림 — 백엔드 없이 테스트 시 proxy 에러 방지
  // abort()로 처리하면 EventSource가 지수 백오프로 재연결(빠른 루프 없음)
  await page.route(
    (url) => url.pathname === '/api/v1/notifications/stream',
    (route) => route.abort(),
  );
}

export { base };
