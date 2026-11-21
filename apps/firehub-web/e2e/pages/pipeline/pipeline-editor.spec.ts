import type { Page } from '@playwright/test';

import { createExecution, createPipelineDetail, createTrigger } from '../../factories/pipeline.factory';
import { createPageResponse,mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { holdApi } from '../../fixtures/hold-api';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/**
 * 파이프라인 에디터 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 에디터 페이지 UI를 검증한다.
 * - DAG 캔버스(@xyflow/react) 내부 상호작용은 테스트하기 어려우므로
 *   페이지 레벨 렌더링과 탭 전환에 집중한다.
 */
test.describe('파이프라인 에디터 페이지', () => {
  // 실행·수정·트리거 편집 버튼은 권한이 있을 때만 보인다(WD-219) — 기본 인증 fixture 는 권한이 비어 있어 모든 쓰기 권한을 준다.
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('기존 파이프라인을 로드하면 탭 메뉴가 표시된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    // 에디터 API 모킹
    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines/1');

    // 파이프라인 ID가 있을 때 탭 목록(개요/트리거/실행 이력)이 표시된다
    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '트리거' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '실행 이력' })).toBeVisible();
  });

  test('개요 탭에서 트리거 탭으로 전환할 수 있다', async ({ authenticatedPage: page }) => {
    // 에디터 API 모킹
    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines/1');

    // 초기에는 개요 탭이 활성화되어 있는지 확인
    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible();

    // 트리거 탭 클릭
    await page.getByRole('tab', { name: '트리거' }).click();

    // 트리거 탭 콘텐츠가 표시된다 (트리거 추가 버튼 확인)
    await expect(page.getByRole('button', { name: /새 트리거/ })).toBeVisible();
  });

  test('실행 이력 탭을 클릭하면 실행 목록이 표시된다', async ({ authenticatedPage: page }) => {
    // 에디터 API 모킹 (실행 이력 2개 포함: COMPLETED id:1, FAILED id:2)
    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines/1');

    // 실행 이력 탭 클릭
    await page.getByRole('tab', { name: '실행 이력' }).click();

    // 실행 이력 테이블 헤더 확인
    await expect(page.getByRole('columnheader', { name: '상태' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '실행자' })).toBeVisible();

    // 헤더 1행 + 데이터 2행 = 총 3행 확인
    await expect(page.getByRole('row')).toHaveCount(3);

    // 첫 번째 실행 행: COMPLETED 또는 '완료' 상태 표시 확인
    const firstRow = page.getByRole('row').nth(1);
    await expect(firstRow.locator('[data-slot="badge"]').filter({ hasText: /COMPLETED|완료/ })).toBeVisible();

    // 두 번째 실행 행: FAILED 또는 '실패' 상태 표시 확인
    const secondRow = page.getByRole('row').nth(2);
    await expect(secondRow.locator('[data-slot="badge"]').filter({ hasText: /FAILED|실패/ })).toBeVisible();

    // 첫 번째 실행의 소요 시간 확인
    // createExecution defaults: startedAt='2024-01-01T00:00:00Z', completedAt='2024-01-01T00:01:00Z'
    // formatDuration(00:00:00 → 00:01:00) = '1분' (초 나머지가 0이면 분만 표기)
    await expect(firstRow.getByText('1분')).toBeVisible();
  });

  test('실행중인(RUNNING) 실행의 소요시간이 KST 파싱 오류 없이 표시된다 (#533)', async ({
    authenticatedPage: page,
  }) => {
    // 회귀 테스트: startedAt이 타임존 표기 없는 UTC 문자열(서버 계약)이고 completedAt이 없는
    // "실행중" 상태에서, new Date(startedAt)로 직접 파싱하면 브라우저가 KST로 오인해
    // Date.now()와 9시간(540분) 오차가 발생했다. parseUtcDate를 거치면 정상적으로 짧은
    // 경과시간(수 초)이 표시되어야 한다.
    const detail = createPipelineDetail({ id: 1 });

    // 고정된 브라우저 시각: 2024-01-01T00:00:05Z (KST로는 09:00:05)
    // setFixedTime은 install({ time })과 달리 실시간으로 흐르지 않고 완전히 고정되므로
    // 병렬 실행 등으로 테스트가 느려져도 경과시간이 항상 정확히 5초로 유지된다(비결정성 방지).
    // eslint-disable-next-line no-restricted-syntax -- 오프셋을 명시한 고정 문자열 — 브라우저 존 해석 오류 없음(#691 대상 아님)
    await page.clock.setFixedTime(new Date('2024-01-01T00:00:05Z'));

    await mockApi(page, 'GET', '/api/v1/pipelines/1', detail);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([
      // startedAt은 타임존 표기 없는 UTC 문자열(백엔드 실측 계약과 동일), completedAt=null(RUNNING)
      createExecution({ id: 1, pipelineId: 1, status: 'RUNNING', startedAt: '2024-01-01T00:00:00', completedAt: null }),
    ]));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();

    const firstRow = page.getByRole('row').nth(1);
    // 버그가 있었다면 9시간(540분)이 표시됨. 수정 후에는 5초 경과로 '5초'가 표시되어야 한다.
    await expect(firstRow.getByText('5초')).toBeVisible();
    await expect(firstRow.getByText(/9시간|540분/)).not.toBeVisible();
  });

  test('실행 이력이 없을 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 실행 이력 없는 에디터 API 모킹
    const detail = createPipelineDetail({ id: 1 });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', detail);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/pipelines/1');

    // 실행 이력 탭 클릭
    await page.getByRole('tab', { name: '실행 이력' }).click();

    // 빈 상태 메시지 확인
    await expect(page.getByText('실행 기록이 없습니다.')).toBeVisible();
    // 다음 행동 안내(WD-884) — 실행 권한이 있으면 헤더 「실행」 버튼과 트리거를 안내한다
    await expect(
      page.getByText('상단의 「실행」 버튼으로 첫 실행을 시작할 수 있습니다. 트리거를 추가하면 자동으로 실행됩니다.'),
    ).toBeVisible();
  });

  test('실행 권한이 없으면 빈 상태가 실행 버튼 대신 기록이 쌓이는 때를 안내한다', async ({
    authenticatedPage: page,
  }) => {
    // 읽기 권한만 부여 — beforeEach 의 전체 권한 모킹을 덮어쓴다(나중에 등록한 route 가 먼저 처리됨)
    await grantPipelinePermissions(page, ['pipeline:read']);
    await setupPipelineEditorMocks(page, 1);
    // 실행 이력만 빈 목록으로 덮어쓴다(helper 기본값은 실행 2건)
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();

    await expect(page.getByText('실행 기록이 없습니다.')).toBeVisible();
    await expect(
      page.getByText('실행 권한이 있는 사용자가 실행하거나 트리거가 실행하면 이곳에 기록이 쌓입니다.'),
    ).toBeVisible();
    // 보이지 않는 「실행」 버튼을 가리키는 문구는 나오면 안 된다
    await expect(page.getByText(/상단의 「실행」 버튼/)).toHaveCount(0);
  });

  test('실행 이력 응답이 오기 전에는 첫 실행 안내 문구를 보이지 않는다 (WD-884)', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    // 실행 이력 요청을 응답 없이 붙잡아 「빈 결과 확인 전」 상태를 만든다 — 실제 기록 유무를 모르는 때다
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1/executions',
      () => new Promise<void>(() => {}),
    );

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();

    await expect(page.getByText('실행 기록이 없습니다.')).toBeVisible();
    await expect(page.getByText(/첫 실행을 시작할 수 있습니다/)).toHaveCount(0);
    await expect(page.getByText(/이곳에 기록이 쌓입니다/)).toHaveCount(0);
  });

  /**
   * 앱 안 이동(SPA)으로 다른 파이프라인을 연다 — 편집 페이지 컴포넌트가 유지된 채 id 만 바뀌는 상황을 만든다(WD-884 리뷰 후속).
   * 실제로 id 가 바뀌어 다시 그려졌는지는 호출한 테스트가 파이프라인 2 고유 이름으로 확인한다.
   */
  async function navigateInApp(page: Page, path: string) {
    await page.evaluate((to) => {
      window.history.pushState({}, '', to);
      window.dispatchEvent(new PopStateEvent('popstate'));
    }, path);
  }

  /**
   * 실행 이력 요청을 page 번호별로 응답하는 모킹 (WD-884 리뷰 후속) — handler 가 돌려준 객체를 본문으로,
   * status 를 주면 그 상태로 응답한다. 받은 page 쿼리 값을 requests 에 쌓아 돌려준다(요청이 page=0 인지 보는 데 쓴다).
   */
  async function mockExecutionPages(
    page: Page,
    pipelineId: number,
    handler: (pageNo: number) => { body: unknown; status?: number },
  ) {
    const requests: number[] = [];
    await page.route(
      (url) => url.pathname === `/api/v1/pipelines/${pipelineId}/executions`,
      (route) => {
        const pageNo = Number(new URL(route.request().url()).searchParams.get('page') ?? 0);
        requests.push(pageNo);
        const { body, status = 200 } = handler(pageNo);
        return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
      },
    );
    return { requests };
  }

  // WD-884 리뷰 후속 1 — 이전 파이프라인의 이전 데이터(placeholder)는 「이 파이프라인이 비었다」는 확인도, 이 파이프라인의 기록도 아니다.
  test('0건이던 파이프라인에서 다른 파이프라인으로 옮기는 동안 첫 실행 안내를 보이지 않는다 (WD-884)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    await setupPipelineEditorMocks(page, 2);
    await mockApi(page, 'GET', '/api/v1/pipelines/2', createPipelineDetail({ id: 2, name: '두번째 파이프라인' }));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
    // 2번 실행 이력 응답을 테스트가 풀 때까지 붙잡는다 — 그 사이 1번의 빈 목록이 placeholder 로 남던 결함을 시간에 기대지 않고 본다
    const held = await holdApi(page, 'GET', '/api/v1/pipelines/2/executions', 200,
      createPageResponse([createExecution({ id: 91, pipelineId: 2 })]));

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();
    const hint = page.getByText(/첫 실행을 시작할 수 있습니다/);
    await expect(hint).toBeVisible();

    await navigateInApp(page, '/pipelines/2');
    // 파이프라인 2 로 실제 다시 그려졌고 이력 요청이 붙잡힌 상태인지 먼저 확인한다(이동이 조용히 무시된 경우를 막는다)
    await expect(page.getByText('두번째 파이프라인').first()).toBeVisible();
    await expect.poll(() => held.requests.length).toBeGreaterThanOrEqual(1);
    // 한 번만 보는 확인 — toHaveCount(0) 은 응답이 올 때까지 재시도해 결함을 가린다
    expect(await hint.count()).toBe(0);

    held.release();
    await expect(page.getByRole('link', { name: '91', exact: true })).toBeVisible();
  });

  test('다른 파이프라인으로 옮기는 동안 이전 파이프라인의 실행 행이 보이지 않는다 (WD-884)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1); // 1번: 실행 #1·#2
    await setupPipelineEditorMocks(page, 2);
    await mockApi(page, 'GET', '/api/v1/pipelines/2', createPipelineDetail({ id: 2, name: '두번째 파이프라인' }));
    const held = await holdApi(page, 'GET', '/api/v1/pipelines/2/executions', 200,
      createPageResponse([createExecution({ id: 91, pipelineId: 2 })]));

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();
    await expect(page.getByRole('link', { name: '1', exact: true })).toBeVisible();

    await navigateInApp(page, '/pipelines/2');
    await expect(page.getByText('두번째 파이프라인').first()).toBeVisible();
    await expect.poll(() => held.requests.length).toBeGreaterThanOrEqual(1);
    // 잘못된 파이프라인의 실행 id 로 이동·취소하지 않도록 1번의 행·페이지 나눔이 남아 있으면 안 된다
    expect(await page.getByRole('link', { name: '1', exact: true }).count()).toBe(0);
    expect(await page.getByRole('link', { name: '2', exact: true }).count()).toBe(0);

    held.release();
    await expect(page.getByRole('link', { name: '91', exact: true })).toBeVisible();
  });

  // WD-884 리뷰 후속 2 — 뒤쪽 페이지를 보다 다른 파이프라인으로 옮기면 새 파이프라인은 첫 페이지부터 받아야 한다.
  test('뒤쪽 페이지를 보다 다른 파이프라인으로 옮기면 첫 페이지(page=0)부터 요청한다 (WD-884)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    await setupPipelineEditorMocks(page, 2);
    await mockApi(page, 'GET', '/api/v1/pipelines/2', createPipelineDetail({ id: 2, name: '두번째 파이프라인' }));
    // 1번: 2페이지짜리 이력 — 요청한 page 에 맞춰 응답한다
    await mockExecutionPages(page, 1, (pageNo) => ({
      body: createPageResponse([createExecution({ id: 10 + pageNo, pipelineId: 1 })], {
        page: pageNo,
        size: 1,
        totalElements: 2,
        totalPages: 2,
      }),
    }));
    const pipeline2 = await mockExecutionPages(page, 2, () => ({
      body: createPageResponse([createExecution({ id: 91, pipelineId: 2 })]),
    }));

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();
    await page.getByRole('button', { name: '다음 페이지' }).click();
    await expect(page.getByRole('link', { name: '11', exact: true })).toBeVisible();

    await navigateInApp(page, '/pipelines/2');
    await expect(page.getByRole('link', { name: '91', exact: true })).toBeVisible();
    expect(pipeline2.requests.length).toBeGreaterThan(0);
    expect(pipeline2.requests.every((p) => p === 0)).toBe(true);
  });

  // WD-884 리뷰 후속 3 — 뒤쪽 페이지가 실패해 표·페이지 나눔이 사라져도 첫 페이지로 나갈 길이 있어야 한다.
  test('뒤쪽 페이지 조회가 실패하면 「첫 페이지로」 로 돌아갈 수 있다 (WD-884)', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    await mockExecutionPages(page, 1, (pageNo) =>
      pageNo > 0
        ? { status: 500, body: { status: 500, error: 'Internal Server Error', message: '서버 오류가 발생했습니다.' } }
        : {
            body: createPageResponse([createExecution({ id: 10, pipelineId: 1 })], {
              page: 0,
              size: 1,
              totalElements: 2,
              totalPages: 2,
            }),
          },
    );

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();
    await page.getByRole('button', { name: '다음 페이지' }).click();

    const alert = page.getByRole('alert').filter({ hasText: '서버 오류가 발생했습니다.' });
    await expect(alert).toBeVisible({ timeout: 15000 });
    await alert.getByRole('button', { name: '첫 페이지로' }).click();
    await expect(page.getByRole('link', { name: '10', exact: true })).toBeVisible();
    await expect(alert).toHaveCount(0);
  });

  // WD-884 리뷰 후속 4 — 파이프라인이 사라져 404 면 트리거 탭(WD-244)처럼 서버 문구를 보이고, 다시 받아도 같으니 다시 시도는 두지 않는다.
  test('실행 이력 조회가 404 면 서버 문구를 보이고 다시 시도 버튼은 두지 않는다 (WD-884)', async ({
    authenticatedPage: page,
  }) => {
    const message = '파이프라인을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.';
    await setupPipelineEditorMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions',
      { status: 404, error: 'Not Found', message }, { status: 404 });

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();

    const alert = page.getByRole('alert').filter({ hasText: message });
    await expect(alert).toBeVisible({ timeout: 15000 });
    await expect(page.getByText('실행 기록이 없습니다.')).toHaveCount(0);
    await expect(alert.getByRole('button', { name: '다시 시도' })).toHaveCount(0);
  });

  // WD-884 리뷰 후속 2 — 트리거를 쓸 수 없는 사용자에게 「트리거를 추가하면」 안내는 따를 수 없는 말이다.
  test('실행 권한만 있고 트리거 쓰기 권한이 없으면 트리거 추가 안내를 빼고 실행 버튼만 안내한다 (WD-884)', async ({
    authenticatedPage: page,
  }) => {
    await grantPipelinePermissions(page, ['pipeline:read', 'pipeline:execute', 'trigger:read']);
    await setupPipelineEditorMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();

    await expect(page.getByText('상단의 「실행」 버튼으로 첫 실행을 시작할 수 있습니다.')).toBeVisible();
    await expect(page.getByText(/트리거를 추가하면/)).toHaveCount(0);
  });

  // WD-884 리뷰 후속 3 — 조회 실패는 「기록 없음」과 구분해 알리고 다시 받을 수 있게 한다(트리거 탭 WD-244 와 같은 role=alert 패턴).
  test('실행 이력 조회가 실패하면 「실행 기록이 없습니다」 대신 오류 안내와 다시 시도 버튼을 보인다 (WD-884)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines/1/executions',
      { status: 500, error: 'Internal Server Error', message: '서버 오류가 발생했습니다.' },
      { status: 500 },
    );

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '실행 이력' }).click();

    const alert = page.getByRole('alert').filter({ hasText: '서버 오류가 발생했습니다.' });
    await expect(alert).toBeVisible({ timeout: 15000 });
    await expect(page.getByText('실행 기록이 없습니다.')).toHaveCount(0);
    await expect(page.getByText(/첫 실행을 시작할 수 있습니다/)).toHaveCount(0);

    // 다시 시도 — 이번에는 성공 응답이라 실행 목록이 보인다
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([
      createExecution({ id: 5, pipelineId: 1 }),
    ]));
    await alert.getByRole('button', { name: '다시 시도' }).click();
    await expect(page.getByRole('link', { name: '5', exact: true })).toBeVisible();
    await expect(alert).toHaveCount(0);
  });

  test('트리거 탭에서 등록된 트리거가 표시된다', async ({ authenticatedPage: page }) => {
    // 스케줄 트리거 1개 포함한 에디터 API 모킹
    const detail = createPipelineDetail({ id: 1 });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', detail);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([
      createExecution({ id: 1, pipelineId: 1 }),
    ]));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', [
      createTrigger({ id: 1, pipelineId: 1, name: '매일 실행', triggerType: 'SCHEDULE' }),
    ]);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/pipelines/1');

    // 트리거 탭 클릭
    await page.getByRole('tab', { name: '트리거' }).click();

    // 트리거 이름이 표시되는지 확인
    await expect(page.getByText('매일 실행')).toBeVisible();

    // 트리거 타입 'SCHEDULE' 또는 '스케줄' 배지가 표시되는지 확인
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: /SCHEDULE|스케줄/ })).toBeVisible();
  });

  test('존재하지 않는 파이프라인(404) 접근 시 에러 상태가 된다', async ({
    authenticatedPage: page,
  }) => {
    // 404 에러 응답으로 모킹
    await mockApi(page, 'GET', '/api/v1/pipelines/999', { message: '파이프라인을 찾을 수 없습니다.' }, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/pipelines/999/executions', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/pipelines/999/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/999/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/pipelines/999');

    // 404 시에도 탭 구조가 렌더링되거나 빈 상태로 표시된다
    // (파이프라인 데이터를 못 가져오면 탭이 렌더링되지 않음)
    await expect(page).toHaveURL('/pipelines/999');
  });

  test('신규 파이프라인 생성 페이지에는 탭이 없다', async ({ authenticatedPage: page }) => {
    // 신규 에디터 페이지 — 파이프라인 ID 없음, 데이터셋 목록만 필요
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/pipelines/new');

    // 신규 생성 모드에서는 개요/트리거/실행이력 탭이 없어야 한다
    await expect(page.getByRole('tab', { name: '트리거' })).not.toBeVisible();
    await expect(page.getByRole('tab', { name: '실행 이력' })).not.toBeVisible();
  });

  /**
   * 회귀 테스트 — 이슈 #19
   * 존재하지 않는 파이프라인 ID 접근 시 빈 에디터 대신 에러 안내가 표시되어야 한다.
   */
  test('존재하지 않는 파이프라인 ID 접근 시 에러 안내와 목록 이동 버튼이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // 파이프라인 API가 404를 반환하도록 모킹 (나머지 API도 필요)
    await mockApi(page, 'GET', '/api/v1/pipelines/99999', { message: 'Not found' }, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/pipelines/99999/executions', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/pipelines/99999/triggers', []);
    await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });

    await page.goto('/pipelines/99999');

    // 에러 안내 메시지가 표시되어야 한다
    await expect(page.getByText('파이프라인을 찾을 수 없습니다.')).toBeVisible();

    // 목록으로 이동 버튼이 표시되어야 한다
    await expect(page.getByRole('button', { name: '목록으로' })).toBeVisible();

    // 빈 에디터 헤더("파이프라인 이름")는 표시되지 않아야 한다 (회귀 방지)
    await expect(page.getByText('파이프라인 이름')).not.toBeVisible();
  });

  test('존재하지 않는 파이프라인에서 목록으로 버튼 클릭 시 파이프라인 목록 페이지로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/pipelines/99999', { message: 'Not found' }, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/pipelines/99999/executions', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/pipelines/99999/triggers', []);
    await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });
    await mockApi(page, 'GET', '/api/v1/pipelines', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    await page.goto('/pipelines/99999');

    await page.getByRole('button', { name: '목록으로' }).click();

    // 파이프라인 목록 페이지로 이동되어야 한다
    await expect(page).toHaveURL('/pipelines');
  });

  /**
   * 회귀 테스트 — 이슈 #544
   * `/pipelines/abc`처럼 숫자로 변환 불가능한 ID 접근 시:
   * - Number('abc')=NaN이 pipelineId로 그대로 쓰이면 NaN이 falsy라서 "새 파이프라인 생성"
   *   모드로 조용히 오분류되고, 상세 조회 API도 호출되지 않아야 한다(비숫자 ID는 조회 시도 X).
   * - `{pipelineId && (...)}`에서 NaN && expr가 NaN 자체를 반환해 화면에 리터럴 "NaN" 텍스트가
   *   렌더링되는 문제도 함께 방지되어야 한다.
   * #543/#545와 동일 원인 클래스(Number(id)→NaN + enabled:!!id)의 수정.
   */
  test('비숫자 ID(/pipelines/abc) 접근 시 신규 생성 폼 대신 찾을 수 없음 안내가 표시되고 NaN 텍스트가 노출되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 핵심 검증: 비숫자 ID이므로 상세 조회 API가 아예 호출되지 않아야 한다
    let pipelineDetailRequested = false;
    await page.route('**/api/v1/pipelines/abc', () => {
      pipelineDetailRequested = true;
    });
    await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });

    await page.goto('/pipelines/abc');

    // 에러 안내 메시지가 표시되어야 한다 (신규 생성 폼 X)
    await expect(page.getByText('파이프라인을 찾을 수 없습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '목록으로' })).toBeVisible();

    // 신규 생성 폼(breadcrumb "새 파이프라인", 저장 버튼)이 표시되지 않아야 한다 (회귀 방지)
    await expect(page.getByText('새 파이프라인')).not.toBeVisible();
    await expect(page.getByRole('button', { name: '저장' })).not.toBeVisible();

    // 화면에 리터럴 "NaN" 텍스트가 노출되지 않아야 한다 (회귀 방지)
    await expect(page.getByText('NaN', { exact: true })).not.toBeVisible();

    expect(pipelineDetailRequested).toBe(false);
  });

  /**
   * 회귀 테스트 — 이슈 WD-568
   * `/pipelines/%20`·`/pipelines/0` 은 `Number(' ')`·`Number('0')` 이 0 이라 #544 의 `Number.isFinite` 검사를 통과했고,
   * pipelineId 0 이 falsy 라 「새 파이프라인」 화면이 열려 저장하면 새 파이프라인이 생겼다.
   * parseRouteId(숫자만·양의 정수만)로 읽어 기존 「찾을 수 없음」 안내를 타야 한다.
   */
  for (const path of ['/pipelines/%20', '/pipelines/0', '/pipelines/0/executions/5']) {
    test(`잘못된 ID(${path}) 접근 시 신규 생성 폼 대신 찾을 수 없음 안내가 표시된다`, async ({
      authenticatedPage: page,
    }) => {
      // 잘못된 id 로 상세(및 하위) API 를 부르지 않아야 한다
      let pipelineDetailRequested = false;
      await page.route(
        (url) => /^\/api\/v1\/pipelines\/(0|%20)(\/|$)/.test(url.pathname),
        (route) => {
          pipelineDetailRequested = true;
          return route.abort();
        },
      );
      await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });

      await page.goto(path);

      await expect(page.getByText('파이프라인을 찾을 수 없습니다.')).toBeVisible();
      await expect(page.getByRole('button', { name: '목록으로' })).toBeVisible();

      // 신규 생성 폼(「새 파이프라인」 제목, 저장 버튼)이 보이지 않아야 한다
      await expect(page.getByRole('heading', { level: 1, name: '새 파이프라인' })).not.toBeAttached();
      await expect(page.getByRole('button', { name: '저장' })).not.toBeVisible();

      expect(pipelineDetailRequested).toBe(false);
    });
  }

  // 회귀 테스트 — WD-568: id 파라미터가 없는 /pipelines/new(쿼리 포함)는 그대로 새 파이프라인 화면이어야 한다
  test('쿼리가 붙은 /pipelines/new 도 새 파이프라인 생성 화면으로 열린다 (WD-568)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });

    await page.goto('/pipelines/new?from=list');

    await expect(page.getByRole('heading', { level: 1, name: '새 파이프라인' })).toBeAttached();
    await expect(page.getByText('파이프라인을 찾을 수 없습니다.')).not.toBeVisible();
  });

  // 회귀 테스트: WCAG 2.4.6 (Headings and Labels) — 페이지 진입 시 h1 헤딩 존재 보장 (issue #63)
  test('신규 생성 페이지에 "새 파이프라인" h1 헤딩이 존재한다', async ({ authenticatedPage: page }) => {
    // 신규 생성은 ID 없이 진입하므로 datasets/pipelines 목록만 모킹
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
    });

    await page.goto('/pipelines/new');

    // 시멘틱 h1 (sr-only) 가 정확히 "새 파이프라인" 텍스트로 존재해야 한다
    await expect(page.getByRole('heading', { level: 1, name: '새 파이프라인' })).toBeAttached();
    // 페이지 전체에 heading 이 0개가 아닌 1개 이상 (회귀 가드)
    const headingCount = await page.locator('h1, h2, h3, h4, h5, h6').count();
    expect(headingCount).toBeGreaterThan(0);
  });

  test('기존 파이프라인 편집 페이지에 파이프라인 이름이 h1 헤딩으로 노출된다', async ({ authenticatedPage: page }) => {
    const detail = createPipelineDetail({ id: 1, name: '월간 매출 ETL' });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', detail);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
    });

    await page.goto('/pipelines/1');

    // 파이프라인 이름이 h1 으로 노출되는지 확인
    await expect(page.getByRole('heading', { level: 1, name: '월간 매출 ETL' })).toBeAttached();
  });
});
