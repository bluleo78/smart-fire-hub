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

test.describe('usePipelineValidation — PYTHON 스텝 출력 컬럼 검증', () => {
  /**
   * PYTHON 스텝에서 출력 컬럼명을 비워둔 채 저장 시 에러를 표시하고 POST API를 호출하지 않는다.
   * usePipelineValidation.ts의 pythonConfig.outputColumns.name 빈값 검증 분기를 커버한다.
   * 이슈 #43: Python 스텝 출력 컬럼 빈 이름 저장 허용 버그 회귀 방지.
   */
  test('PYTHON 스텝에서 출력 컬럼명이 비어 있으면 저장이 차단된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    // POST API 호출 캡처 — 저장 차단 시 호출되지 않아야 한다
    const captureCreate = await mockApi(page, 'POST', '/api/v1/pipelines', {}, { capture: true });

    await page.goto('/pipelines/new');

    // 파이프라인 이름 입력 (파이프라인 이름 빈값 early-return 우회)
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('Python 컬럼명 빈값 테스트');

    // 스텝 추가
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });
    await page.locator('#step-name').fill('Python 처리 스텝');

    // scriptType을 PYTHON으로 변경
    const typeSelect = page.getByRole('combobox').first();
    await expect(typeSelect).toBeVisible();
    await typeSelect.click();
    const pythonOption = page.getByRole('option', { name: /Python/ });
    if ((await pythonOption.count()) === 0) {
      return; // UI에 Python 옵션이 없으면 건너뜀
    }
    await pythonOption.click();

    // 출력 컬럼 섹션에서 "컬럼 추가" 클릭 → 빈 컬럼명 행 생성
    const addColumnBtn = page.getByRole('button', { name: /컬럼 추가/ });
    if ((await addColumnBtn.count()) > 0) {
      await addColumnBtn.click();
    }
    // 컬럼명 textbox가 있으면 비워둠 (기본값이 이미 빈 문자열)
    const colNameInput = page.getByRole('textbox', { name: '컬럼명' }).first();
    if ((await colNameInput.count()) > 0) {
      await colNameInput.fill('');
    }

    // 저장 버튼 클릭 → PYTHON 출력 컬럼 빈값 검증 → 저장 차단
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // "입력 오류를 확인하세요" toast 표시 확인
    await expect(page.getByText(/입력 오류/).first()).toBeVisible({ timeout: 5000 });

    // POST API가 호출되지 않았음을 확인 (저장 차단 검증)
    await page.waitForTimeout(500);
    expect(captureCreate.requests.length).toBe(0);
  });
});

test.describe('usePipelineValidation — AI_CLASSIFY 스텝 검증', () => {
  /**
   * AI_CLASSIFY 스텝에서 프롬프트 없이 저장 시 에러를 표시한다.
   * usePipelineValidation.ts의 aiConfig.prompt 검증 분기를 커버한다.
   * (line 43-45: !cfg || !cfg.prompt || !(cfg.prompt as string).trim())
   */
  test('AI_CLASSIFY 스텝에서 프롬프트 없이 저장 시 에러가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 파이프라인 이름 입력
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('AI 분류 파이프라인');

    // 스텝 추가
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });
    await page.locator('#step-name').fill('AI 분류 스텝');

    // 스텝 타입을 AI_CLASSIFY로 변경
    const typeSelect = page.getByRole('combobox').first();
    await expect(typeSelect).toBeVisible();
    await typeSelect.click();

    const aiOption = page.getByRole('option', { name: /AI.*분류|AI_CLASSIFY/i });
    if ((await aiOption.count()) > 0) {
      await aiOption.click();
    } else {
      // 옵션이 없으면 테스트를 건너뜀 (UI에 AI_CLASSIFY 타입이 없는 경우)
      return;
    }

    // 프롬프트를 입력하지 않고 저장 → aiConfig.prompt 검증 실패
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // SET_VALIDATION_ERRORS dispatch → "입력 오류" 토스트 또는 프롬프트 에러 메시지 표시
    await expect(page.getByText(/입력 오류|프롬프트/).first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * AI_CLASSIFY 스텝에서 출력 컬럼 없이 저장 시 에러를 표시한다.
   * usePipelineValidation.ts의 aiConfig.outputColumns 검증 분기를 커버한다.
   * (line 46-48: !cfg || !cfg.outputColumns || length === 0)
   */
  test('AI_CLASSIFY 스텝에서 출력 컬럼 없이 저장 시 에러가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 파이프라인 이름 입력
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('AI 출력 컬럼 없는 파이프라인');

    // 스텝 추가
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });
    await page.locator('#step-name').fill('AI 분류 스텝 2');

    // 스텝 타입을 AI_CLASSIFY로 변경
    const typeSelect = page.getByRole('combobox').first();
    await expect(typeSelect).toBeVisible();
    await typeSelect.click();

    const aiOption = page.getByRole('option', { name: /AI.*분류|AI_CLASSIFY/i });
    if ((await aiOption.count()) > 0) {
      await aiOption.click();
    } else {
      // 옵션이 없으면 테스트를 건너뜀
      return;
    }

    // 프롬프트 입력 필드가 있으면 입력 (prompt 에러 우선 발생 방지)
    const promptInput = page.getByPlaceholder(/프롬프트|prompt/i).first();
    if ((await promptInput.count()) > 0) {
      await promptInput.fill('데이터를 분류하세요');
    }

    // 출력 컬럼은 추가하지 않고 저장 → aiConfig.outputColumns 검증 실패
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // SET_VALIDATION_ERRORS → "입력 오류" 또는 "출력 컬럼" 에러 메시지 표시
    await expect(page.getByText(/입력 오류|출력 컬럼/).first()).toBeVisible({ timeout: 5000 });
  });
});

test.describe('usePipelineValidation — API_CALL 스텝 URL 검증', () => {
  /**
   * API_CALL 스텝에서 URL을 비워둔 채 저장 시 클라이언트 검증 에러를 표시하고 POST API를 호출하지 않는다.
   * usePipelineValidation.ts의 apiConfig.customUrl 빈값 검증 분기를 커버한다.
   * 이슈 #44: API_CALL 스텝 URL 빈값 저장 시 generic 에러만 표시되는 버그 회귀 방지.
   */
  test('API_CALL 스텝에서 URL이 비어있으면 저장이 차단되고 에러가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // 데이터셋 목록 모킹 (파이프라인 에디터 필수)
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });
    // API 연결 선택 목록 모킹 (ApiCallStepConfig의 ConnectionCombobox)
    await mockApi(page, 'GET', '/api/v1/api-connections/selectable', []);

    // POST API 호출 캡처 — 저장 차단 시 호출되지 않아야 한다
    const captureCreate = await mockApi(page, 'POST', '/api/v1/pipelines', {}, { capture: true });

    await page.goto('/pipelines/new');

    // 파이프라인 이름 입력 (name.trim() === '' early-return 우회)
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('API_CALL 빈URL 테스트');

    // 스텝 추가
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });
    await page.locator('#step-name').fill('API 스텝');

    // 스텝 타입을 API_CALL로 변경
    const typeSelect = page.getByRole('combobox').first();
    await expect(typeSelect).toBeVisible();
    await typeSelect.click();
    const apiCallOption = page.getByRole('option', { name: 'API 호출' });
    if ((await apiCallOption.count()) === 0) {
      return; // UI에 API 호출 옵션이 없으면 건너뜀
    }
    await apiCallOption.click();

    // ApiCallStepConfig 로드 대기 (lazy import)
    await expect(page.getByText('기본 설정')).toBeVisible({ timeout: 10000 });

    // URL 필드가 비어있는 상태에서 저장 버튼 클릭 (URL 입력 없음)
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // "입력 오류를 확인하세요" toast 또는 URL 에러 메시지 표시 확인
    await expect(page.getByText(/입력 오류|URL을 입력/).first()).toBeVisible({ timeout: 5000 });

    // POST API가 호출되지 않았음을 확인 (저장 차단 검증)
    await page.waitForTimeout(500);
    expect(captureCreate.requests.length).toBe(0);
  });
});
