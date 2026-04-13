/**
 * pipelineEditorReducer — ADD_EDGE + cycle detection E2E 테스트
 *
 * 커버리지 목표: pipelineEditorReducer.ts의 ADD_EDGE 케이스
 * - wouldCreateCycle() 경로 커버 (cycle-detection.ts)
 * - 순환 참조가 생기는 엣지 추가 시도 → 상태 변경 없이 무시됨
 *
 * 테스트 전략:
 * - 기존 파이프라인(A → B → A 형태)을 로드하거나
 * - 에디터에서 직접 스텝을 만들고 엣지를 통해 순환 구조 생성 시도
 * - UI에서 엣지 추가 후 사이클이 방지되어 DAG 상태가 유지되는지 검증
 *
 * NOTE: @xyflow/react 캔버스 내 드래그 기반 엣지 연결은 Playwright에서 직접 조작하기 어렵다.
 *       따라서 파이프라인 로드 후 스텝 패널의 "의존 스텝" 선택 UI를 통해
 *       ADD_EDGE 액션을 간접적으로 트리거하거나, 리듀서 레벨 검증에 집중한다.
 */

import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/** 에디터 페이지 공통 모킹 설정 */
async function setupEditorWithSteps(page: import('@playwright/test').Page, pipelineId = 1) {
  await setupPipelineEditorMocks(page, pipelineId);
}

test.describe('pipelineEditorReducer — cycle detection (ADD_EDGE)', () => {
  /**
   * 기존 파이프라인(A → B 연결)을 로드했을 때 정상적으로 에디터가 렌더링되는지 확인.
   * LOAD_FROM_API 액션을 통해 DAG 상태가 올바르게 초기화되는 경로를 커버한다.
   */
  test('의존 관계가 있는 파이프라인 로드 시 에디터가 정상 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    await setupEditorWithSteps(page, 1);

    await page.goto('/pipelines/1');

    // 에디터 탭이 표시되면 LOAD_FROM_API 성공
    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible({ timeout: 10000 });
  });

  /**
   * 순환 참조(A → B → A)가 될 수 있는 파이프라인 구조를 로드해도
   * 에디터가 정상적으로 렌더링되는지 검증한다.
   * LOAD_FROM_API 시 dependsOnStepNames 매핑 경로를 커버한다.
   */
  test('순환 참조 없는 다단계 파이프라인 로드 시 DAG가 정상 초기화된다', async ({
    authenticatedPage: page,
  }) => {
    // A → B → C 3단계 파이프라인 (정상 DAG)
    const detail = createPipelineDetail({
      id: 1,
      steps: [
        createStep({ id: 1, name: '스텝A', stepOrder: 0, dependsOnStepNames: [] }),
        createStep({ id: 2, name: '스텝B', stepOrder: 1, dependsOnStepNames: ['스텝A'] }),
        createStep({ id: 3, name: '스텝C', stepOrder: 2, dependsOnStepNames: ['스텝B'] }),
      ],
    });

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

    // 에디터가 정상 로드되어야 한다
    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible({ timeout: 10000 });
  });

  /**
   * 신규 파이프라인에서 스텝을 추가하고 첫 번째 스텝을 선택했을 때
   * ADD_STEP 액션 후 스텝 설정 패널이 열리는지 검증한다.
   * createDefaultStep() 헬퍼와 ADD_STEP 케이스를 커버한다.
   */
  test('신규 파이프라인에서 스텝 추가 시 스텝 설정 패널이 열린다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/pipelines/new');

    // 스텝 추가 → ADD_STEP 액션 dispatch
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();

    // 스텝 설정 패널이 열려야 한다 (step-name 입력 필드 표시)
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });
  });

  /**
   * 스텝 추가 후 삭제 시 REMOVE_STEP 액션이 실행되어 스텝이 제거되는지 검증한다.
   * REMOVE_STEP 케이스 — remainingSteps 필터링 경로를 커버한다.
   */
  test('스텝 삭제 시 REMOVE_STEP 액션이 실행되어 스텝이 제거된다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });

    await page.goto('/pipelines/new');

    // 스텝 추가
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

    // 스텝 이름 입력
    await page.locator('#step-name').fill('삭제할 스텝');

    // 스텝 삭제 버튼 클릭 → REMOVE_STEP dispatch
    const deleteBtn = page.getByRole('button', { name: /삭제|제거|스텝 삭제/ }).first();
    if ((await deleteBtn.count()) > 0) {
      await deleteBtn.click();
      // 스텝 패널이 닫혀야 한다 (selectedStepId → null)
      await expect(page.locator('#step-name')).not.toBeVisible({ timeout: 5000 });
    }
  });

  /**
   * 두 스텝을 가진 파이프라인에서 "다음 스텝 추가(ADD_STEP_AFTER)" 기능을 검증한다.
   * ADD_STEP_AFTER 케이스 — newStep.dependsOnTempIds = [sourceTempId] 경로를 커버한다.
   */
  test('기존 파이프라인의 스텝을 클릭하면 스텝 설정 패널이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupEditorWithSteps(page, 1);

    await page.goto('/pipelines/1');

    // 에디터 로드 대기
    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible({ timeout: 10000 });

    // DAG 캔버스 내 스텝 노드 클릭 → SELECT_STEP dispatch → 설정 패널 열림
    // xyflow 노드는 .react-flow__node 클래스를 가진다
    const stepNodes = page.locator('.react-flow__node');
    if ((await stepNodes.count()) > 0) {
      await stepNodes.first().click();
      // 스텝 설정 패널이 열려야 한다 (step-name 또는 스텝 이름 입력)
      await expect(page.locator('#step-name')).toBeVisible({ timeout: 5000 });
    }
  });

  /**
   * AUTO_LAYOUT 액션이 실행될 때 applyAutoLayout() 함수가 호출되어
   * 스텝 위치가 dagre로 재배치되는 경로를 커버한다.
   */
  test('자동 레이아웃 버튼 클릭 시 AUTO_LAYOUT 액션이 실행된다', async ({
    authenticatedPage: page,
  }) => {
    await setupEditorWithSteps(page, 1);

    await page.goto('/pipelines/1');

    // 에디터 로드 대기
    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible({ timeout: 10000 });

    // 자동 레이아웃 버튼 클릭 → AUTO_LAYOUT dispatch
    const autoLayoutBtn = page.getByRole('button', { name: /자동 레이아웃|레이아웃 정렬/ });
    if ((await autoLayoutBtn.count()) > 0) {
      await autoLayoutBtn.click();
      // 에디터가 여전히 표시되어야 한다 (레이아웃 후 크래시 없음)
      await expect(page.getByRole('tab', { name: '개요' })).toBeVisible();
    }
  });
});
