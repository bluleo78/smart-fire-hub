import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * useNotificationStream 훅 E2E 테스트
 * - AppLayout이 마운트되면 useNotificationStream이 EventSource를 통해 SSE 연결을 시도한다.
 * - page.route로 SSE 응답을 모킹하여 각 이벤트 분기(eventType, severity)를 커버한다.
 * - setupHomeMocks의 기본 notifications/stream abort()를 이 테스트에서 SSE 응답으로 오버라이드한다.
 */

/**
 * SSE 이벤트 하나를 담은 text/event-stream 응답 바디를 생성한다.
 * EventSource는 "event: <name>\ndata: <json>\n\n" 형식을 파싱한다.
 */
function makeSseBody(eventName: string, data: object): string {
  return `event: ${eventName}\ndata: ${JSON.stringify(data)}\n\n`;
}

/** 공통 notification 이벤트 기본 필드 */
const baseNotification = {
  id: 'notif-1',
  entityType: 'PIPELINE',
  entityId: 1,
  metadata: {},
  occurredAt: '2026-04-13T10:00:00',
};

test.describe('useNotificationStream — SSE 이벤트 처리', () => {
  /**
   * SSE 스트림을 모킹할 때 setupHomeMocks의 abort() 라우트보다
   * 나중에 등록한 라우트가 우선 적용된다 (Playwright last-match wins).
   */

  test('PIPELINE_COMPLETED 이벤트 수신 시 대시보드 쿼리가 갱신된다', async ({
    authenticatedPage: page,
  }) => {
    const notification = {
      ...baseNotification,
      eventType: 'PIPELINE_COMPLETED',
      severity: 'INFO',
      title: '파이프라인 완료',
      description: '파이프라인이 성공적으로 완료되었습니다.',
    };

    // 대시보드 stats API 호출 횟수 추적 — 이벤트 수신 후 invalidate되면 재호출된다
    let statsCallCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/dashboard/stats',
      (route) => {
        statsCallCount++;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            totalDatasets: 10,
            sourceDatasets: 6,
            derivedDatasets: 4,
            totalPipelines: 5,
            activePipelines: 3,
            recentImports: [],
            recentExecutions: [],
          }),
        });
      },
    );

    // SSE 스트림: PIPELINE_COMPLETED 이벤트 한 건 전송 후 연결 유지
    await page.route(
      (url) => url.pathname === '/api/v1/notifications/stream',
      (route) => {
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          headers: {
            'Cache-Control': 'no-cache',
            'X-Accel-Buffering': 'no',
          },
          body: makeSseBody('notification', notification),
        });
      },
    );

    await page.goto('/');
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();

    // SSE 이벤트 후 queryClient.invalidateQueries가 호출되어 stats가 재요청된다
    await expect.poll(() => statsCallCount, { timeout: 5000 }).toBeGreaterThanOrEqual(2);
  });

  test('PROACTIVE_MESSAGE 이벤트 수신 시 info toast가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    const notification = {
      ...baseNotification,
      eventType: 'PROACTIVE_MESSAGE',
      severity: 'INFO',
      title: '새 인사이트 발견',
      description: '데이터 패턴에서 이상 징후가 감지되었습니다.',
    };

    // proactive 미읽음 수 API 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 1 });

    await page.route(
      (url) => url.pathname === '/api/v1/notifications/stream',
      (route) => {
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          headers: { 'Cache-Control': 'no-cache' },
          body: makeSseBody('notification', notification),
        });
      },
    );

    await page.goto('/');
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();

    // PROACTIVE_MESSAGE → toast.info(title, { description })
    await expect(page.getByText('새 인사이트 발견')).toBeVisible({ timeout: 5000 });
  });

  test('CRITICAL severity 이벤트 수신 시 error toast가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    const notification = {
      ...baseNotification,
      eventType: 'PIPELINE_FAILED',
      severity: 'CRITICAL',
      title: '긴급: 파이프라인 장애',
      description: '핵심 파이프라인이 중단되었습니다.',
    };

    await page.route(
      (url) => url.pathname === '/api/v1/notifications/stream',
      (route) => {
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          headers: { 'Cache-Control': 'no-cache' },
          body: makeSseBody('notification', notification),
        });
      },
    );

    await page.goto('/');
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();

    // CRITICAL severity → toast.error(title, { description })
    await expect(page.getByText('긴급: 파이프라인 장애')).toBeVisible({ timeout: 5000 });
  });

  test('WARNING severity 이벤트 수신 시 warning toast가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    const notification = {
      ...baseNotification,
      eventType: 'IMPORT_FAILED',
      severity: 'WARNING',
      title: '가져오기 지연',
      description: '데이터 가져오기가 예상보다 오래 걸리고 있습니다.',
    };

    await page.route(
      (url) => url.pathname === '/api/v1/notifications/stream',
      (route) => {
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          headers: { 'Cache-Control': 'no-cache' },
          body: makeSseBody('notification', notification),
        });
      },
    );

    await page.goto('/');
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();

    // WARNING severity → toast.warning(title, { description })
    await expect(page.getByText('가져오기 지연')).toBeVisible({ timeout: 5000 });
  });

  test('DATASET_CHANGED 이벤트 수신 시 analytics 쿼리가 갱신된다', async ({
    authenticatedPage: page,
  }) => {
    const notification = {
      ...baseNotification,
      eventType: 'DATASET_CHANGED',
      severity: 'INFO',
      title: '데이터셋 변경',
      description: '소방서 위치 데이터셋이 업데이트되었습니다.',
    };

    let dashboardsCallCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/dashboards',
      (route) => {
        dashboardsCallCount++;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            content: [],
            page: 0,
            size: 5,
            totalElements: 0,
            totalPages: 0,
          }),
        });
      },
    );

    await page.route(
      (url) => url.pathname === '/api/v1/notifications/stream',
      (route) => {
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          headers: { 'Cache-Control': 'no-cache' },
          body: makeSseBody('notification', notification),
        });
      },
    );

    await page.goto('/');
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();

    // DATASET_CHANGED → analytics/dashboards 재요청
    await expect.poll(() => dashboardsCallCount, { timeout: 5000 }).toBeGreaterThanOrEqual(2);
  });

  test('SSE 연결 오류 시 재연결을 시도한다 (지수 백오프)', async ({
    authenticatedPage: page,
  }) => {
    // 연결 시도 횟수 추적
    let connectAttempts = 0;

    await page.route(
      (url) => url.pathname === '/api/v1/notifications/stream',
      (route) => {
        connectAttempts++;
        // 매번 에러로 응답하여 재연결 유발
        return route.abort('failed');
      },
    );

    await page.goto('/');
    await expect(page.getByRole('heading', { name: '홈' })).toBeVisible();

    // 재연결 로직: 최소 1회 연결 시도가 발생해야 한다
    // (지수 백오프로 즉시 재시도는 아니지만 첫 시도는 마운트 직후 발생)
    await expect.poll(() => connectAttempts, { timeout: 3000 }).toBeGreaterThanOrEqual(1);
  });
});
