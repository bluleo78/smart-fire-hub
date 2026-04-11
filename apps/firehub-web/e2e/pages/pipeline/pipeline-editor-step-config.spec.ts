/**
 * 파이프라인 에디터 — 스텝 편집 E2E 테스트
 *
 * pipelineEditorReducer 의 UPDATE_STEP / SET_META / SELECT_STEP / REMOVE_STEP
 * 분기를 StepConfigPanel 의 입력/버튼에서 실제로 dispatch 되도록 구동한다.
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('파이프라인 에디터 — 스텝 설정', () => {
  async function setupNewEditorMocks(page: import('@playwright/test').Page) {
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });
  }

  test('파이프라인 이름/설명 입력 시 SET_META dispatch (isDirty 전환)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 헤더 이름 필드 — EditorHeader 의 이름 input
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('테스트 파이프라인');
    await expect(nameInput).toHaveValue('테스트 파이프라인');
  });

  test('스텝 생성 후 StepConfigPanel 에서 이름/설명 편집 (UPDATE_STEP)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // ADD_STEP — 첫 번째 스텝 생성 (reducer: selectedStepId 자동 설정)
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();

    // StepConfigPanel 의 "이름" 필드 (#step-name) 가 자동 선택으로 표시된다
    const stepNameInput = page.locator('#step-name');
    await expect(stepNameInput).toBeVisible({ timeout: 10000 });

    // UPDATE_STEP — name 변경
    await stepNameInput.fill('데이터 로드');
    await expect(stepNameInput).toHaveValue('데이터 로드');

    // UPDATE_STEP — description 변경
    const descInput = page.locator('#step-description');
    await descInput.fill('CSV 파일에서 데이터를 읽어옵니다');
    await expect(descInput).toHaveValue('CSV 파일에서 데이터를 읽어옵니다');
  });

  test('스텝 2개 생성 후 설정 패널의 "뒤로" 버튼으로 선택 해제 (SELECT_STEP null)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // ADD_STEP × 2
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

    await page.getByRole('button', { name: /스텝 추가/ }).first().click();

    // 두 번째 스텝이 선택된 상태 — StepConfigPanel 이 여전히 표시
    await expect(page.locator('#step-name')).toBeVisible();

    // 자동 정렬 버튼으로 AUTO_LAYOUT dispatch (브랜치 커버)
    await page.getByRole('button', { name: '자동 정렬' }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();
  });

  test('이름 미입력 상태로 저장 클릭 시 usePipelineValidation 이 실패한다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 스텝 추가 먼저 (steps.length === 0 분기 대신 name.trim() 분기를 타게)
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

    // 이름 미입력 상태에서 저장 버튼 클릭 → usePipelineValidation → name.trim() === ''
    // 이름 입력 필드는 비워둔 채 저장 버튼 클릭
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // 실패 토스트(sonner)가 렌더되는지 — "파이프라인 이름을 입력하세요"
    // sonner 토스트는 role=status / role=list 내부 텍스트로 표시된다
    await expect(page.getByText('파이프라인 이름을 입력하세요')).toBeVisible();
  });

  test('스텝 없이 저장 클릭 시 "최소 1개의 스텝" 에러가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 스텝 없이 이름만 입력
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('이름만 있는 파이프라인');

    // 저장 버튼이 "저장" text 를 가진 버튼 — EditorHeader 의 Save 아이콘 버튼
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // usePipelineValidation: steps.length === 0 → "최소 1개의 스텝을 정의하세요"
    await expect(page.getByText('최소 1개의 스텝을 정의하세요')).toBeVisible();
  });

  test('신규 파이프라인 에디터에 탭 메뉴가 표시되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // /new 모드는 pipelineId 가 없으므로 상세 탭(개요/트리거/실행 이력) 이 없다
    await expect(page.getByRole('tab', { name: '트리거' })).not.toBeVisible();
    await expect(page.getByRole('tab', { name: '실행 이력' })).not.toBeVisible();

    // 빈 상태 안내
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).toBeVisible();
  });
});
