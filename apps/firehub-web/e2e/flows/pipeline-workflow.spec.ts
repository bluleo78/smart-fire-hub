import { createPipeline } from '../factories/pipeline.factory';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupPipelineEditorMocks, setupPipelineMocks } from '../fixtures/pipeline.fixture';

/**
 * 파이프라인 워크플로우 E2E 플로우 테스트
 * - 목록 → 에디터 이동, 실행 플로우 등 페이지 간 흐름을 통합 검증한다.
 */
test.describe('파이프라인 워크플로우', () => {
  test('목록 페이지에서 행 클릭 시 에디터 페이지로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    // 목록 API 모킹 (파이프라인 5개)
    await setupPipelineMocks(page, 5);

    // 에디터 페이지 API 모킹 (목록에서 클릭 후 이동할 페이지)
    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines');

    // 첫 번째 파이프라인 행 클릭 (파이프라인 이름 셀)
    await page.getByRole('cell', { name: '파이프라인 1', exact: true }).click();

    // 에디터 페이지(/pipelines/1)로 이동 확인
    await expect(page).toHaveURL(/\/pipelines\/1/);
  });

  test('파이프라인 추가 버튼 클릭 시 신규 에디터 페이지로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    // 목록 API 모킹
    await setupPipelineMocks(page, 3);

    // 신규 파이프라인 에디터는 데이터셋 목록만 필요
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/pipelines');

    // "파이프라인 추가" 링크 버튼 클릭
    await page.getByRole('link', { name: /파이프라인 추가/ }).click();

    // /pipelines/new 페이지로 이동 확인
    await expect(page).toHaveURL('/pipelines/new');
  });

  test('에디터 페이지에서 실행 버튼 클릭 시 실행이 시작된다', async ({
    authenticatedPage: page,
  }) => {
    // 에디터 API 모킹
    await setupPipelineEditorMocks(page, 1);

    // 파이프라인 실행 API 모킹 (실행 성공 응답)
    await mockApi(page, 'POST', '/api/v1/pipelines/1/execute', {
      id: 10,
      pipelineId: 1,
      status: 'RUNNING',
      executedBy: 'testuser',
      triggeredBy: 'MANUAL',
      triggerName: null,
      startedAt: '2024-01-01T00:00:00Z',
      completedAt: null,
      createdAt: '2024-01-01T00:00:00Z',
    });

    await page.goto('/pipelines/1');

    // 에디터 헤더의 실행 버튼 확인 (조회 모드)
    const runButton = page.getByRole('button', { name: '실행' });
    await expect(runButton).toBeVisible();

    // 실행 버튼 클릭
    await runButton.click();

    // 성공 토스트 메시지 확인
    await expect(page.getByText('파이프라인 실행이 시작되었습니다')).toBeVisible();
  });

  test('목록 페이지에서 삭제 버튼 클릭 시 확인 다이얼로그가 열린다', async ({
    authenticatedPage: page,
  }) => {
    // 삭제할 파이프라인 포함한 목록 모킹
    await mockApi(page, 'GET', '/api/v1/pipelines', {
      content: [createPipeline({ id: 1, name: '삭제할 파이프라인' })],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    });

    await page.goto('/pipelines');

    // 삭제 버튼 클릭 (aria-label="삭제")
    await page.getByRole('button', { name: '삭제' }).first().click();

    // 삭제 확인 다이얼로그가 열리는지 확인
    await expect(page.getByRole('alertdialog')).toBeVisible();
  });
});
