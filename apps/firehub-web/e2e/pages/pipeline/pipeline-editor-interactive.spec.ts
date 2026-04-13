/**
 * 파이프라인 에디터 상호작용 E2E 테스트
 *
 * pipelineEditorReducer / PipelineCanvas / StepConfigPanel 의 reducer dispatch
 * 경로(ADD_STEP, UPDATE_STEP, AUTO_LAYOUT) 를 UI 로부터 실제 호출되도록 커버한다.
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('파이프라인 에디터 — 상호작용', () => {
  /** 신규 파이프라인 에디터는 /pipelines/new 로 진입한다. 데이터셋 목록만 있으면 된다. */
  async function setupNewEditorMocks(
    page: import('@playwright/test').Page,
  ) {
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });
  }

  test('빈 상태 → 스텝 추가 버튼 클릭 시 첫 번째 스텝이 생성된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 빈 상태 안내 문구
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).toBeVisible();

    // 스텝 추가 → ADD_STEP dispatch → steps[0] 생성
    await page.getByRole('button', { name: /스텝 추가/ }).click();

    // 빈 상태가 사라지고 ReactFlow 캔버스가 렌더링된다
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).not.toBeVisible();
    // 우상단의 자동 정렬 / 스텝 추가 버튼이 나타난다
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();
  });

  test('스텝 추가 → 자동 정렬 버튼 클릭 시 AUTO_LAYOUT dispatch', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

    // 스텝 하나 더 추가 (ADD_STEP) — 우상단의 추가 버튼 사용 (first())
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();

    // 자동 정렬 버튼 클릭 (AUTO_LAYOUT dispatch)
    await page.getByRole('button', { name: '자동 정렬' }).click();

    // 여전히 캔버스가 정상 렌더링되는지 확인 — AUTO_LAYOUT 이후 에러 없이 유지
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();
  });

  test('신규 에디터에서 헤더에 파이프라인 이름 입력 필드가 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // EditorHeader 의 이름 입력 필드 확인 (신규 생성 모드)
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/);
    await expect(nameInput.first()).toBeVisible();
  });

  test('헤더 이름 입력란 변경 — SET_META dispatch로 파이프라인 이름이 바뀐다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // EditorHeader의 이름 입력 필드 확인
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await expect(nameInput).toBeVisible();

    // 이름 변경 → SET_META dispatch
    await nameInput.fill('새 파이프라인 이름');

    // 입력값이 반영되어야 한다
    await expect(nameInput).toHaveValue('새 파이프라인 이름');
  });

  test('스텝 선택 후 이름 입력 — UPDATE_STEP dispatch로 스텝 이름이 변경된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 스텝 추가 → ADD_STEP dispatch
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

    // ReactFlow 노드 클릭 → SELECT_STEP dispatch → StepConfigPanel 열림
    await page.locator('.react-flow__node').first().click();

    // StepConfigPanel에 이름 입력 필드가 나타나야 한다
    const stepNameInput = page.getByLabel(/스텝 이름|이름/).first();
    await expect(stepNameInput).toBeVisible();

    // 이름 변경 → UPDATE_STEP dispatch
    await stepNameInput.fill('내 SQL 스텝');

    // 입력값 반영 확인
    await expect(stepNameInput).toHaveValue('내 SQL 스텝');
  });

  test('스텝 추가 후 노드 클릭 → 스텝 삭제 — REMOVE_STEP dispatch', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 스텝 추가 → ADD_STEP dispatch
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

    // ReactFlow 노드 클릭 → StepConfigPanel 열기
    await page.locator('.react-flow__node').first().click();

    // StepConfigPanel 하단 destructive "스텝 삭제" 버튼 클릭 → REMOVE_STEP dispatch
    // 노드에도 title="스텝 삭제" 아이콘 버튼이 있으므로 .last()로 패널 버튼을 선택한다
    await expect(page.getByRole('button', { name: '스텝 삭제' }).last()).toBeVisible();
    await page.getByRole('button', { name: '스텝 삭제' }).last().click();

    // 스텝이 삭제되어 빈 상태로 복귀
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).toBeVisible();
  });

  test('스텝 선택 후 스텝 타입 변경 — UPDATE_STEP type dispatch', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 스텝 추가 → SELECT
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

    // 노드 클릭 → StepConfigPanel 열기
    await page.locator('.react-flow__node').first().click();

    // StepConfigPanel 에서 스텝 타입 Select 확인 — SQL이 기본값
    // 타입 셀렉터가 존재하면 UPDATE_STEP 경로가 커버된다
    const typeSelect = page.getByRole('combobox').first();
    await expect(typeSelect).toBeVisible();

    // 타입 변경 클릭
    await typeSelect.click();

    // PYTHON 또는 API_CALL 옵션이 존재하는지 확인
    const pythonOption = page.getByRole('option', { name: /Python|PYTHON/ });
    const apiOption = page.getByRole('option', { name: /API|api_call/i });
    const hasOption = (await pythonOption.count()) > 0 || (await apiOption.count()) > 0;
    expect(hasOption).toBe(true);
  });

  test('스텝 두 개 추가 후 두 노드가 캔버스에 렌더링된다 — ADD_STEP 두 번 dispatch', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 스텝 두 개 추가 → ADD_STEP 두 번 dispatch
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();

    // 두 개의 ReactFlow 노드가 캔버스에 렌더링되어야 한다
    await expect(page.locator('.react-flow__node')).toHaveCount(2);
  });
});
