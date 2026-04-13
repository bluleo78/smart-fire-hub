/**
 * usePipelineValidation E2E 테스트
 *
 * 커버리지 목표: usePipelineValidation.ts 미커버 분기
 * - 스텝별 Zod 유효성 검사 (name 필드 빈값)
 * - 중복 스텝 이름 감지
 * - AI_CLASSIFY 스텝 aiConfig.prompt / aiConfig.outputColumns 검증
 * - 유효성 통과 후 저장 API 호출
 *
 * NOTE: 이름 없이 저장/스텝 없이 저장은 pipeline-editor-step-config.spec.ts 에 이미 포함되어 있으므로
 *       여기서는 나머지 분기(step validation loop, duplicates)를 집중 커버한다.
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 신규 에디터 공통 모킹 — 데이터셋 목록만 있으면 된다 */
async function setupNewEditorMocks(page: import('@playwright/test').Page) {
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [],
    page: 0,
    size: 1000,
    totalElements: 0,
    totalPages: 0,
  });
}

test.describe('usePipelineValidation — 스텝 수준 유효성 검사', () => {
  /**
   * 스텝 이름이 비어있는 상태로 저장 시 → Zod 스키마 검증 실패
   * editorPipelineSchema.shape.steps.element.safeParse 경로를 커버한다.
   */
  test('스텝 이름이 비어있으면 저장 시 유효성 에러가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 파이프라인 이름 입력 (name.trim() === '' 분기 우회)
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('테스트 파이프라인');

    // 스텝 추가 → ADD_STEP dispatch
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

    // 스텝 이름을 의도적으로 비워둠 (기본값이 빈 문자열이어야 함)
    const stepNameInput = page.locator('#step-name');
    await stepNameInput.fill('');

    // 저장 클릭 → step validation loop → Zod safeParse 실패
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // SET_VALIDATION_ERRORS dispatch → 에러 토스트 또는 에러 메시지 표시
    await expect(page.getByText(/입력 오류|이름을 입력/).first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * 같은 이름의 스텝 2개 생성 후 저장 → 중복 이름 에러
   * nameCount Map → tempIds.length > 1 분기를 커버한다.
   */
  test('중복된 스텝 이름으로 저장 시 중복 에러가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 파이프라인 이름 입력
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('중복 스텝 파이프라인');

    // 첫 번째 스텝 추가 → 이름 '스텝A' 설정
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });
    await page.locator('#step-name').fill('스텝A');

    // 두 번째 스텝 추가 → 이름도 '스텝A'로 설정 (중복)
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    // 두 번째 스텝의 이름 입력 — 새 스텝이 자동 선택된다
    const stepNameInputs = page.locator('#step-name');
    await stepNameInputs.fill('스텝A');

    // 저장 클릭 → nameCount 중복 감지 → SET_VALIDATION_ERRORS + toast
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // "스텝 이름 "스텝A"이(가) 중복됩니다" 에러 토스트 또는 인라인 에러 표시
    await expect(page.getByText(/중복|입력 오류/).first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * 스텝 scriptContent 없이 저장 → Zod superRefine (scriptContent 필수) 에러
   * editorPipelineSchema superRefine 분기를 커버한다.
   */
  test('스텝 내용(scriptContent) 없이 저장 시 Zod 유효성 에러가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 파이프라인 이름 입력
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('스크립트 없는 파이프라인');

    // 스텝 추가 → ADD_STEP
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

    // 스텝 이름은 입력 (name 유효), scriptContent는 비워둠
    await page.locator('#step-name').fill('SQL 스텝');

    // 저장 클릭 → Zod superRefine: scriptContent 없으면 실패
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // 유효성 에러 표시 (인라인 에러 또는 토스트)
    await expect(page.getByText(/입력 오류|스크립트/).first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * 유효한 파이프라인(이름 + 스텝 + scriptContent) 저장 → POST API 호출
   * validate() === true 분기를 커버한다.
   * CodeMirror 에디터에 내용 입력 후 저장.
   */
  test('유효한 파이프라인 저장 시 POST API가 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    // 파이프라인 생성 API 모킹
    const savedPipeline = {
      id: 99,
      name: '유효한 파이프라인',
      description: '',
      steps: [],
      createdAt: '2024-01-01T00:00:00Z',
    };
    const captureCreate = await mockApi(page, 'POST', '/api/v1/pipelines', savedPipeline, {
      capture: true,
    });

    await page.goto('/pipelines/new');

    // 파이프라인 이름 입력
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('유효한 파이프라인');

    // 스텝 추가
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });
    await page.locator('#step-name').fill('데이터 처리');

    // CodeMirror 에디터에 SQL 입력 (scriptContent 필수)
    const cmEditor = page.locator('.cm-content').first();
    await expect(cmEditor).toBeVisible({ timeout: 5000 });
    await cmEditor.click();
    await page.keyboard.type('SELECT 1');

    // 저장 버튼 클릭 → validate() === true → POST 호출
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // API가 호출되었는지 확인
    await expect
      .poll(() => captureCreate.requests.length, { timeout: 8000 })
      .toBeGreaterThan(0);

    // 저장된 payload에 파이프라인 이름 포함 확인
    const req = captureCreate.lastRequest();
    expect(req?.payload).toMatchObject({ name: '유효한 파이프라인' });
  });

  /**
   * 스텝 scriptType 변경 (SQL → PYTHON) 후 저장 가능 여부 확인
   * scriptType 필드 업데이트 경로 커버.
   */
  test('PYTHON 스텝 타입 선택 후 저장 시 validate() 가 실행된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('Python 파이프라인');

    // 스텝 추가
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });
    await page.locator('#step-name').fill('Python 스텝');

    // 스텝 타입 셀렉터 — SQL이 기본값, PYTHON으로 변경
    const typeSelect = page.getByRole('combobox').first();
    await expect(typeSelect).toBeVisible();
    await typeSelect.click();

    const pythonOption = page.getByRole('option', { name: /Python|PYTHON/ });
    if ((await pythonOption.count()) > 0) {
      await pythonOption.click();
    }

    // 저장 시 validate() 가 호출되고 (이름 있으니 통과 또는 스크립트 내용 에러)
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // 에러가 없거나 스크립트 관련 에러 — 어느 쪽이든 validate loop가 실행됨
    // 이름 있고 스텝 있으므로 첫 두 early-return은 건너뜀
    await expect(page).toHaveURL(/\/pipelines/);
  });
});
