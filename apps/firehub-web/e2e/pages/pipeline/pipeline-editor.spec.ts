import { createExecution, createPipelineDetail, createTrigger } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/**
 * 파이프라인 에디터 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 에디터 페이지 UI를 검증한다.
 * - DAG 캔버스(@xyflow/react) 내부 상호작용은 테스트하기 어려우므로
 *   페이지 레벨 렌더링과 탭 전환에 집중한다.
 */
test.describe('파이프라인 에디터 페이지', () => {
  test('기존 파이프라인을 로드하면 탭 메뉴가 표시된다', async ({ authenticatedPage: page }) => {
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
    await expect(page.getByRole('button', { name: /트리거 추가/ })).toBeVisible();
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
    // formatDuration(00:00:00 → 00:01:00) = '1m 0s'
    await expect(firstRow.getByText('1m 0s')).toBeVisible();
  });

  test('실행 이력이 없을 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 실행 이력 없는 에디터 API 모킹
    const detail = createPipelineDetail({ id: 1 });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', detail);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
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
  });

  test('트리거 탭에서 등록된 트리거가 표시된다', async ({ authenticatedPage: page }) => {
    // 스케줄 트리거 1개 포함한 에디터 API 모킹
    const detail = createPipelineDetail({ id: 1 });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', detail);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', [
      createExecution({ id: 1, pipelineId: 1 }),
    ]);
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
    await mockApi(page, 'GET', '/api/v1/pipelines/999/executions', []);
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
    await mockApi(page, 'GET', '/api/v1/pipelines/99999/executions', []);
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
    await mockApi(page, 'GET', '/api/v1/pipelines/99999/executions', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/99999/triggers', []);
    await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });
    await mockApi(page, 'GET', '/api/v1/pipelines', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    await page.goto('/pipelines/99999');

    await page.getByRole('button', { name: '목록으로' }).click();

    // 파이프라인 목록 페이지로 이동되어야 한다
    await expect(page).toHaveURL('/pipelines');
  });
});
