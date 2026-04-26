/**
 * 파이프라인 실행 상세(ExecutionStepPanel) E2E 테스트
 *
 * ExecutionStepPanel 컴포넌트의 주요 렌더링 경로를 검증한다:
 * - 스텝 미선택 시 ExecutionSummary (실행 전체 정보) 표시
 * - 스텝 클릭 시 StepDetails (스텝별 상세 정보) 표시
 * - FAILED 스텝의 에러 메시지 표시 (사용자 친화적 안내 + "오류 상세" 제목)
 * - 로그가 있는 스텝의 로그 표시
 */

import { createExecutionDetail, createStepExecution } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

test.describe('파이프라인 실행 상세 — ExecutionStepPanel', () => {
  test('실행 탭에서 실행 클릭 시 ExecutionSummary가 표시된다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);

    // 실행 상세 API 모킹 (id=1)
    const detail = createExecutionDetail({
      id: 1,
      pipelineId: 1,
      status: 'COMPLETED',
      executedBy: 'testuser',
      stepExecutions: [
        createStepExecution({ id: 1, stepName: '데이터 추출', status: 'COMPLETED', outputRows: 100 }),
        createStepExecution({ id: 2, stepId: 2, stepName: '데이터 변환', status: 'COMPLETED', outputRows: 95 }),
      ],
      startedAt: '2024-01-01T00:00:00Z',
      completedAt: '2024-01-01T00:01:00Z',
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', detail);

    await page.goto('/pipelines/1');

    // 실행 이력 탭으로 이동
    await page.getByRole('tab', { name: /실행|이력/ }).click();

    // 실행 행 클릭
    const rows = page.getByRole('row');
    await rows.nth(1).click();

    // ExecutionStepPanel이 열리고 "실행 정보" 헤더 표시 (selectedStepName 없음)
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });

    // ExecutionSummary: 실행자, 스텝 현황, 안내 문구 확인
    await expect(page.getByText('testuser')).toBeVisible();
    await expect(page.getByText(/DAG에서 스텝을 클릭/)).toBeVisible();
  });

  test('완료된 실행의 스텝 현황 요약이 표시된다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);

    // COMPLETED 스텝 2개를 가진 실행 상세
    const detail = createExecutionDetail({
      id: 1,
      status: 'COMPLETED',
      stepExecutions: [
        createStepExecution({ id: 1, stepName: '데이터 추출', status: 'COMPLETED' }),
        createStepExecution({ id: 2, stepId: 2, stepName: '데이터 변환', status: 'COMPLETED' }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();

    const rows = page.getByRole('row');
    await rows.nth(1).click();

    // "실행 정보" 패널 열림 확인
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });

    // 스텝 현황 섹션 확인 — "완료 2/2" 형식
    await expect(page.getByText(/완료 \d+\/\d+/)).toBeVisible();
  });

  test('FAILED 실행의 스텝 목록에 실패 스텝이 있으면 실패 현황이 표시된다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);

    // FAILED 상태 실행, 1개 스텝 실패 포함
    const detail = createExecutionDetail({
      id: 2,
      pipelineId: 1,
      status: 'FAILED',
      stepExecutions: [
        createStepExecution({ id: 1, stepName: '데이터 추출', status: 'COMPLETED' }),
        createStepExecution({
          id: 2,
          stepId: 2,
          stepName: '데이터 변환',
          status: 'FAILED',
          errorMessage: '데이터 변환 오류: 타입 불일치',
          completedAt: '2024-01-01T00:00:45Z',
        }),
      ],
      startedAt: '2024-01-01T00:00:00Z',
      completedAt: '2024-01-01T00:00:45Z',
    });
    // setupPipelineEditorMocks는 id=1,2 실행을 모킹하므로 id=2 실행 상세를 추가 모킹
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/2', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();

    // 두 번째 행(id=2, FAILED) 클릭
    const rows = page.getByRole('row');
    await rows.nth(2).click();

    // ExecutionSummary 패널 열림 확인
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });

    // FAILED 스텝이 있으므로 실패 현황이 표시되어야 한다
    await expect(page.getByText(/실패 \d+/)).toBeVisible();
  });

  test('FAILED 스텝 선택 시 오류 상세 제목과 사용자 친화적 안내 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    // 기술적 오류 메시지가 "오류 상세" 섹션 제목 + 안내 메시지와 함께 표시되는지 검증 (이슈 #51)
    await setupPipelineEditorMocks(page, 1);

    // FAILED 스텝 포함 실행 상세
    const technicalErrorMsg = 'Invalid name: ?column?. Must match [a-z][a-z0-9_]*';
    const detail = createExecutionDetail({
      id: 1,
      pipelineId: 1,
      status: 'FAILED',
      stepExecutions: [
        createStepExecution({
          id: 1,
          stepName: '데이터 추출',
          status: 'FAILED',
          errorMessage: technicalErrorMsg,
          completedAt: '2024-01-01T00:00:10Z',
        }),
      ],
      startedAt: '2024-01-01T00:00:00Z',
      completedAt: '2024-01-01T00:00:10Z',
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();

    // 실행 행 클릭 → 실행 상세뷰(DAG + 사이드패널) 진입
    const rows = page.getByRole('row');
    await rows.nth(1).click();
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });

    // DAG 노드 클릭 → StepDetails 패널로 전환
    const stepNode = page.locator('.react-flow__node').first();
    await stepNode.click();

    // "오류 상세" 섹션 제목이 표시되어야 한다 (이전: "에러")
    await expect(page.getByText('오류 상세')).toBeVisible({ timeout: 5000 });

    // 사용자 친화적 안내 메시지가 표시되어야 한다
    await expect(page.getByText(/스텝 실행 중 오류가 발생했습니다/)).toBeVisible();

    // 기술적 원문은 그대로 표시되어야 한다 (개발자 디버깅용, 숨기지 않음)
    await expect(page.getByText(technicalErrorMsg)).toBeVisible();
  });
});
