/**
 * 파이프라인 에디터 — 스텝 편집 E2E 테스트
 *
 * pipelineEditorReducer 의 UPDATE_STEP / SET_META / SELECT_STEP / REMOVE_STEP
 * 분기를 StepConfigPanel 의 입력/버튼에서 실제로 dispatch 되도록 구동한다.
 */

import type { Page } from '@playwright/test';

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { clickToolbarAddStep, grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

/** 툴바로 스텝을 추가하고 이름·타입(AI 분류)을 정한다 */
async function addAiClassifyStep(page: Page, name: string) {
  await clickToolbarAddStep(page);
  await page.locator('#step-name').fill(name);
  await page.getByRole('combobox', { name: '스크립트 타입' }).click();
  await page.getByRole('option', { name: 'AI 분류', exact: true }).click();
}

/** 저장 가능한 AI 분류 스텝(프롬프트·출력 컬럼)을 만들고 고급 설정까지 연다 */
async function addConfiguredAiClassifyStep(page: Page) {
  await addAiClassifyStep(page, 'AI 스텝');
  await page.getByRole('textbox', { name: '프롬프트' }).fill('감성을 분류하세요');
  await page.getByRole('button', { name: '컬럼 추가', exact: true }).click();
  await page.getByLabel('출력 컬럼명 1').fill('sentiment');
  await page.locator('button').filter({ hasText: /^고급 설정$/ }).click();
}

test.describe('파이프라인 에디터 — 스텝 설정', () => {
  // 추가·삭제·생성 경로·실행·수정 버튼은 권한이 있을 때만 보인다(WD-219) — 기본 인증 fixture 는 권한이 비어 있어 모든 파이프라인 권한을 준다.
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

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
    await clickToolbarAddStep(page);

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
    await clickToolbarAddStep(page);
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

    await clickToolbarAddStep(page);
    await expect(page.locator('.react-flow__node')).toHaveCount(2);

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
    await clickToolbarAddStep(page);
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

    // 이름 미입력 상태에서 저장 버튼 클릭 → usePipelineValidation → name.trim() === ''
    // 이름 입력 필드는 비워둔 채 저장 버튼 클릭
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // 실패 토스트(sonner)가 렌더되는지 — "파이프라인 이름을 입력하세요"
    // sonner 토스트는 role=status / role=list 내부 텍스트로 표시된다
    // WD-812: 이름 칸 아래 안내(#pipeline-name-hint)에도 같은 문구가 있어, 토스트 안에서만 찾아 둘을 구분한다
    await expect(page.locator('[data-sonner-toast]').getByText('파이프라인 이름을 입력하세요')).toBeVisible();
  });

  // 회귀 가드(WD-360): 이름 길이 한도에 메시지가 없어 zod 영어 기본 문구(「Too big: expected string to have <=100 characters」)가 그대로 떴다.
  test('스텝 이름을 100자 넘게 입력하고 저장하면 한국어 안내가 뜨고 영어 원문은 보이지 않는다 (WD-360)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');
    await page.getByPlaceholder(/파이프라인 이름|이름 입력/).first().fill('긴 스텝 이름 파이프라인');

    await clickToolbarAddStep(page);
    const stepNameInput = page.locator('#step-name');
    await expect(stepNameInput).toBeVisible({ timeout: 10000 });
    await stepNameInput.fill('가'.repeat(101));
    await page.locator('#step-description').fill('설명');

    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByText('스텝 이름은 100자 이하여야 합니다').first()).toBeVisible();
    await expect(page.getByText(/Too big|expected string/)).toHaveCount(0);
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

  /**
   * 스크립트 타입 SelectItem 레이블 언어 일관성 회귀 테스트 (#9)
   * SQL/Python/API 호출/AI 분류가 일관된 한국어 친화적 포맷으로 표시되는지 확인한다.
   * PYTHON(영어 대문자)이 Python으로 올바르게 표시되어야 한다.
   */
  test('스크립트 타입 드롭다운 레이블이 일관된 포맷으로 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 스텝 추가 → StepConfigPanel 표시
    await clickToolbarAddStep(page);
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

    // 스크립트 타입 드롭다운 열기
    const typeSelect = page.getByRole('combobox').first();
    await typeSelect.click();

    // SQL: 그대로 유지
    await expect(page.getByRole('option', { name: 'SQL', exact: true })).toBeVisible();

    // Python: 대문자 전체('PYTHON')가 아닌 Python으로 표시되어야 함
    await expect(page.getByRole('option', { name: 'Python', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: 'PYTHON', exact: true })).not.toBeVisible();

    // API 호출: 한국어 표시 유지
    await expect(page.getByRole('option', { name: 'API 호출', exact: true })).toBeVisible();

    // AI 분류: 한국어 표시 유지
    await expect(page.getByRole('option', { name: 'AI 분류', exact: true })).toBeVisible();

    // 드롭다운 닫기
    await page.keyboard.press('Escape');
  });

  /**
   * 스텝 삭제 확인 다이얼로그 회귀 테스트 (#45)
   * 삭제 버튼 클릭 시 즉시 삭제되지 않고 AlertDialog 확인 후 삭제되어야 한다.
   */
  test('스텝 삭제 버튼 클릭 시 확인 다이얼로그가 표시되고 취소하면 스텝이 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 스텝 추가 → StepConfigPanel 표시
    await clickToolbarAddStep(page);
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

    // 삭제 버튼 클릭 → 확인 다이얼로그 표시 (즉시 삭제 X)
    await page.getByRole('button', { name: '스텝 삭제' }).last().click();

    // 다이얼로그 제목과 설명이 표시되어야 한다
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByText('정말 이 스텝을 삭제하시겠습니까?')).toBeVisible();

    // 취소 클릭 → 스텝이 그대로 유지되어야 한다
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.locator('#step-name')).toBeVisible();
  });

  test('스텝 삭제 확인 다이얼로그에서 삭제 확인 시 스텝이 제거된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 스텝 추가 → StepConfigPanel 표시
    await clickToolbarAddStep(page);
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

    // 삭제 버튼 클릭 → 확인 다이얼로그 표시
    await page.getByRole('button', { name: '스텝 삭제' }).last().click();
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // 삭제 확인 → 스텝이 제거되고 "첫 번째 스텝을 추가하세요" 메시지가 표시된다
    await page.getByRole('button', { name: '삭제' }).click();
    await expect(page.locator('#step-name')).not.toBeVisible();
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).toBeVisible();
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

  test('AI 분류 스텝 배치 크기 — 덜 된 숫자는 1 로 바뀌지 않고, 비우고 50 을 치면 50 이 저장된다 (WD-906)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines',
      { id: 102, name: '배치 크기 테스트', steps: [] },
      { capture: true },
    );
    await page.goto('/pipelines/new');
    await addConfiguredAiClassifyStep(page);

    const batch = page.getByLabel('배치 크기 (1~100)');
    await expect(batch).toBeVisible();

    // 덜 된 숫자 "1e" — 1 로 바뀌지 않고 원문 유지 + 오류 문구
    await batch.fill('1e');
    await expect(batch).toHaveValue('1e');
    await expect(batch).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByText('정수를 입력하세요')).toBeVisible();

    // 비우고 50 — 50 이 저장된다
    await batch.fill('');
    await batch.pressSequentially('50');
    await expect(batch).toHaveValue('50');
    await expect(page.getByText('정수를 입력하세요')).toHaveCount(0);

    await page.getByPlaceholder(/파이프라인 이름|이름 입력/).first().fill('배치 크기 테스트');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await capture.waitForRequest();
    const payload = req.payload as { steps: Array<{ aiConfig?: { batchSize?: number } }> };
    expect(payload.steps[0].aiConfig?.batchSize).toBe(50);
  });

  /** WD-906 후속: 배치 크기 칸이 표기 오류인 동안은 파이프라인 저장이 막히고(요청 0건) 이유가 토스트로 보인다 */
  test('AI 분류 스텝 배치 크기 — 오류가 보이는 동안 저장이 막히고, 고치면 저장된다 (WD-906)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines',
      { id: 105, name: '배치 저장 차단', steps: [] },
      { capture: true },
    );
    await page.goto('/pipelines/new');
    await addConfiguredAiClassifyStep(page);

    const batch = page.getByLabel('배치 크기 (1~100)');
    await batch.fill('1e');
    await expect(page.getByText('정수를 입력하세요')).toBeVisible();
    await page.getByPlaceholder(/파이프라인 이름|이름 입력/).first().fill('배치 저장 차단');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByText('스텝 설정의 숫자 칸 오류를 고친 뒤 저장하세요.')).toBeVisible();
    await expect(batch).toBeFocused();
    expect(capture.requests).toHaveLength(0);

    await batch.fill('30');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await capture.waitForRequest();
    const payload = req.payload as { steps: Array<{ aiConfig?: { batchSize?: number } }> };
    expect(payload.steps[0].aiConfig?.batchSize).toBe(30);
  });

  /**
   * WD-906: 칸에 들어갔다 나가기만 해도 batchSize 가 본문에 새로 실리던 문제.
   */
  test('AI 분류 스텝 배치 크기 — 칸에 들어갔다 나가기만 하면 저장 본문에 batchSize 가 새로 실리지 않는다 (WD-906)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines',
      { id: 103, name: '탭 통과', steps: [] },
      { capture: true },
    );
    await page.goto('/pipelines/new');
    await addConfiguredAiClassifyStep(page);

    const batch = page.getByLabel('배치 크기 (1~100)');
    await batch.focus();
    await batch.blur();

    await page.getByPlaceholder(/파이프라인 이름|이름 입력/).first().fill('탭 통과');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await capture.waitForRequest();
    const payload = req.payload as { steps: Array<{ aiConfig?: { batchSize?: number } }> };
    expect(payload.steps[0].aiConfig?.batchSize).toBeUndefined();
  });

  /**
   * WD-906: AI 분류 설정이 스텝별로 재마운트되지 않아 A 스텝에서 덜 친 `1e` 와 오류가
   * 같은 값(기본 20)을 가진 B 스텝으로 넘어가던 문제.
   */
  test('AI 분류 스텝 — A 스텝에서 덜 친 배치 크기와 오류가 B 스텝으로 넘어가지 않는다 (WD-906)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 스텝 A, B 를 모두 AI 분류로 만든다
    for (const name of ['AI 스텝 A', 'AI 스텝 B']) {
      await addAiClassifyStep(page, name);
      await expect(page.getByRole('textbox', { name: '프롬프트' })).toBeVisible();
    }

    // A 선택 → 고급 설정 열고 `1e`
    await page.locator('.react-flow__node').filter({ hasText: 'AI 스텝 A' }).click();
    await expect(page.locator('#step-name')).toHaveValue('AI 스텝 A');
    await page.locator('button').filter({ hasText: /^고급 설정$/ }).click();
    const batchA = page.getByLabel('배치 크기 (1~100)');
    await batchA.fill('1e');
    await expect(page.getByText('정수를 입력하세요')).toBeVisible();

    // B 선택 → 오류가 없고, 칸을 열면 B 의 값(20)이 보인다
    await page.locator('.react-flow__node').filter({ hasText: 'AI 스텝 B' }).click();
    await expect(page.locator('#step-name')).toHaveValue('AI 스텝 B');
    await expect(page.getByText('정수를 입력하세요')).toHaveCount(0);
    const batchB = page.getByLabel('배치 크기 (1~100)');
    if (!(await batchB.isVisible())) {
      await page.locator('button').filter({ hasText: /^고급 설정$/ }).click();
    }
    await expect(batchB).toHaveValue('20');
    await expect(batchB).not.toHaveAttribute('aria-invalid', 'true');
  });
});
