/**
 * 파이프라인 에디터 — API_CALL 스텝 설정 E2E 테스트
 *
 * 검증 대상:
 * - ApiCallStepConfig: Base URL / Path 분리 필드 표시
 * - ConnectionCombobox: 검색 가능 Combobox로 연결 선택
 * - ApiCallPreview: 테스트 호출 시 resolvedUrl 표시
 * - 저장 시 올바른 payload 전송
 */

import type { ApiConnectionSelectable } from '../../../src/types/api-connection';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 모킹용 API 연결 목록 — ApiConnectionSelectable 타입으로 스펙 정합성 보장 */
const MOCK_CONNECTIONS: ApiConnectionSelectable[] = [
  {
    id: 1,
    name: '공공 데이터 API',
    authType: 'API_KEY',
    baseUrl: 'https://api.data.go.kr',
  },
  {
    id: 2,
    name: '내부 서비스 API',
    authType: 'BEARER',
    baseUrl: 'https://internal.example.com',
  },
];

/** 공통 기본 모킹 설정 — 파이프라인 에디터 진입에 필요한 API */
async function setupEditorMocks(page: import('@playwright/test').Page) {
  // 데이터셋 목록 (파이프라인 에디터 필수)
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [],
    page: 0,
    size: 1000,
    totalElements: 0,
    totalPages: 0,
  });
  // API 연결 선택 목록 (ApiCallStepConfig의 ConnectionCombobox)
  await mockApi(page, 'GET', '/api/v1/api-connections/selectable', MOCK_CONNECTIONS);
}

/**
 * ConnectionCombobox 트리거를 클릭하는 헬퍼.
 * 스냅샷 확인 결과 트리거는 role="combobox"를 가진 버튼이며 내부에 "직접 입력" 텍스트를 포함한다.
 * accessible name이 "직접 입력"이 아닌 경우 :has-text() CSS 셀렉터로 접근한다.
 */
async function clickConnectionCombobox(page: import('@playwright/test').Page) {
  // ConnectionCombobox PopoverTrigger — 내부에 "직접 입력" 텍스트를 포함하는 combobox
  await page.locator('[role="combobox"]:has-text("직접 입력")').click();
}

/**
 * 파이프라인 에디터에서 API_CALL 타입 스텝을 추가하고 설정 패널까지 여는 공통 헬퍼.
 * 1. /pipelines/new 진입
 * 2. 스텝 추가 → 노드 클릭으로 StepConfigPanel 열기
 * 3. 스텝 타입 Select에서 API_CALL 선택
 * 4. Suspense lazy-load 완료 대기 — "기본 설정" 섹션 제목이 나타날 때까지
 */
async function addAndOpenApiCallStep(page: import('@playwright/test').Page) {
  await page.goto('/pipelines/new');

  // 스텝 추가 → ADD_STEP dispatch
  await page.getByRole('button', { name: /스텝 추가/ }).first().click();
  await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

  // ReactFlow 노드 클릭 → StepConfigPanel 열기
  await page.locator('.react-flow__node').first().click();
  await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

  // 스텝 타입 Select 클릭 — StepConfigPanel의 "스크립트 타입" 셀렉트
  const typeSelect = page.getByRole('combobox').first();
  await expect(typeSelect).toBeVisible();
  await typeSelect.click();

  // API_CALL 옵션 선택 — SelectItem value="API_CALL", 표시 텍스트 "API 호출"
  const apiCallOption = page.getByRole('option', { name: 'API 호출' });
  await expect(apiCallOption).toBeVisible({ timeout: 5000 });
  await apiCallOption.click();

  // Suspense lazy-load 완료 대기 — ApiCallStepConfig의 "기본 설정" 텍스트
  await expect(page.getByText('기본 설정')).toBeVisible({ timeout: 10000 });
  // ConnectionCombobox 트리거도 렌더링 완료 확인
  await expect(page.locator('[role="combobox"]:has-text("직접 입력")')).toBeVisible({ timeout: 5000 });
}

test.describe('파이프라인 에디터 — API_CALL 스텝 설정', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupEditorMocks(page);
  });

  /**
   * TC-1: API_CALL 스텝 추가 후 설정 패널 기본 요소 표시
   * - 기본 설정 섹션에 API 연결 Combobox, URL 필드가 나타나야 한다
   */
  test('API_CALL 스텝 추가 후 설정 패널에 기본 설정 요소가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await addAndOpenApiCallStep(page);

    // ConnectionCombobox 트리거 — "직접 입력" 텍스트를 포함하는 combobox
    await expect(page.locator('[role="combobox"]:has-text("직접 입력")')).toBeVisible();

    // inline 모드(직접 입력) 기본 상태: URL * 입력 필드 (placeholder로 확인)
    await expect(page.getByPlaceholder('https://api.example.com/v1/data')).toBeVisible();

    // "테스트 호출" 버튼
    await expect(page.getByRole('button', { name: '테스트 호출' })).toBeVisible();
  });

  /**
   * TC-2: API 연결 Combobox에 저장된 연결 목록이 표시되고 검색어로 필터링된다
   * - Combobox 열기 → 모킹 연결 2개 표시
   * - "공공" 입력 시 '공공 데이터 API'만 남아야 한다
   */
  test('API 연결 Combobox에서 연결 목록이 표시되고 검색어로 필터링된다', async ({
    authenticatedPage: page,
  }) => {
    await addAndOpenApiCallStep(page);

    // ConnectionCombobox 트리거 클릭
    await clickConnectionCombobox(page);

    // Popover 내 Command 패널 열림 — 두 연결 항목이 표시
    await expect(page.getByText('공공 데이터 API')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('내부 서비스 API')).toBeVisible();

    // 검색어 입력 → 필터링
    await page.getByPlaceholder('연결 검색...').fill('공공');

    // '공공 데이터 API'는 보이고, '내부 서비스 API'는 사라져야 한다
    await expect(page.getByText('공공 데이터 API')).toBeVisible();
    await expect(page.getByText('내부 서비스 API')).not.toBeVisible();
  });

  /**
   * TC-3: API 연결 선택 시 baseUrl이 읽기전용 필드에 자동 표시된다
   * - '공공 데이터 API' 선택 → URL 필드에 'https://api.data.go.kr' 표시 (disabled)
   */
  test('API 연결 선택 시 해당 baseUrl이 읽기전용으로 자동 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await addAndOpenApiCallStep(page);

    // Combobox 열기 → 연결 선택
    await clickConnectionCombobox(page);
    await expect(page.getByText('공공 데이터 API')).toBeVisible({ timeout: 5000 });
    await page.getByRole('option', { name: /공공 데이터 API/ }).click();

    // 선택 후 Combobox 트리거에 선택된 연결명이 표시된다 (여러 매칭 중 first)
    await expect(page.getByText(/공공 데이터 API/).first()).toBeVisible();

    // saved 모드: baseUrl이 disabled Input으로 표시된다
    // value가 'https://api.data.go.kr'인 disabled input 확인
    const baseUrlInput = page.locator('input[value="https://api.data.go.kr"]');
    await expect(baseUrlInput).toBeVisible();
    await expect(baseUrlInput).toBeDisabled();
  });

  /**
   * TC-4: saved 모드에서 추가 경로(Path) 필드에 값 입력 가능
   * - 연결 선택 후 추가 경로 입력 필드가 나타나고 입력 가능
   */
  test('API 연결 선택 후 추가 경로 필드에 값을 입력할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await addAndOpenApiCallStep(page);

    // Combobox 열기 → 연결 선택
    await clickConnectionCombobox(page);
    await expect(page.getByText('공공 데이터 API')).toBeVisible({ timeout: 5000 });
    await page.getByRole('option', { name: /공공 데이터 API/ }).click();

    // '추가 경로' placeholder "/v1/data (선택)"인 입력 필드 확인
    const pathInput = page.getByPlaceholder('/v1/data (선택)');
    await expect(pathInput).toBeVisible();
    await expect(pathInput).toBeEnabled();

    // 경로 입력
    await pathInput.fill('/v1/data/items');
    await expect(pathInput).toHaveValue('/v1/data/items');
  });

  /**
   * TC-5: 테스트 호출 시 resolvedUrl(baseUrl + path)이 UI에 표시된다
   * - POST /api/v1/pipelines/api-call/preview 모킹
   * - resolvedUrl을 포함한 응답 → "호출 URL" 섹션에 URL 표시
   */
  test('테스트 호출 후 미리보기 결과에 실제 호출 URL이 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // 미리보기 API 모킹 — resolvedUrl 포함
    await mockApi(page, 'POST', '/api/v1/pipelines/api-call/preview', {
      success: true,
      rawJson: '{"items":[{"id":1,"name":"테스트"}]}',
      rows: [{ id: '1', name: '테스트' }],
      columns: ['id', 'name'],
      totalExtractedRows: 1,
      errorMessage: null,
      resolvedUrl: 'https://api.data.go.kr/v1/data/items',
    });

    await addAndOpenApiCallStep(page);

    // 연결 선택
    await clickConnectionCombobox(page);
    await expect(page.getByText('공공 데이터 API')).toBeVisible({ timeout: 5000 });
    await page.getByRole('option', { name: /공공 데이터 API/ }).click();

    // 경로 입력
    const pathInput = page.getByPlaceholder('/v1/data (선택)');
    await pathInput.fill('/v1/data/items');

    // 테스트 호출 버튼 클릭
    await page.getByRole('button', { name: '테스트 호출' }).click();

    // ApiCallPreview 컴포넌트의 "호출 URL" 섹션 표시 확인
    await expect(page.getByText('호출 URL')).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('https://api.data.go.kr/v1/data/items')).toBeVisible();

    // 추출 행 수 표시 확인
    await expect(page.getByText(/1.*개 행/)).toBeVisible();
  });

  /**
   * TC-6: 테스트 호출 실패 시 에러 메시지가 표시된다
   * - 서버 500 응답 → "호출 실패" 메시지 렌더링
   */
  test('테스트 호출 실패 시 에러 메시지가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // 미리보기 실패 모킹
    await mockApi(
      page,
      'POST',
      '/api/v1/pipelines/api-call/preview',
      { message: '연결 시간이 초과되었습니다' },
      { status: 500 },
    );

    await addAndOpenApiCallStep(page);

    // inline 모드에서 직접 URL 입력 (placeholder로 접근)
    const urlInput = page.getByPlaceholder('https://api.example.com/v1/data');
    await urlInput.fill('https://api.example.com/v1/data');

    // 테스트 호출
    await page.getByRole('button', { name: '테스트 호출' }).click();

    // ApiCallPreview: 실패 시 "호출 실패" 텍스트 표시
    await expect(page.getByText('호출 실패')).toBeVisible({ timeout: 10000 });
  });

  /**
   * TC-7: API_CALL 스텝이 올바른 payload로 저장된다
   * - saved 연결 선택 + path 입력 후 파이프라인 저장
   * - POST /api/v1/pipelines payload에 apiConnectionId, path가 포함되어야 한다
   */
  test('API_CALL 스텝 저장 시 올바른 payload가 전송된다', async ({
    authenticatedPage: page,
  }) => {
    // 파이프라인 저장 API 캡처 설정
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines',
      { id: 99, name: 'API 파이프라인', steps: [] },
      { capture: true },
    );

    await addAndOpenApiCallStep(page);

    // 연결 선택
    await clickConnectionCombobox(page);
    await expect(page.getByText('공공 데이터 API')).toBeVisible({ timeout: 5000 });
    await page.getByRole('option', { name: /공공 데이터 API/ }).click();

    // path 입력
    const pathInput = page.getByPlaceholder('/v1/data (선택)');
    await pathInput.fill('/v1/data');

    // 스텝 이름 입력 (저장 validation 필수)
    const stepNameInput = page.locator('#step-name');
    await stepNameInput.fill('API 호출 스텝');

    // 파이프라인 이름 입력 (저장 필수 조건)
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await nameInput.fill('API 파이프라인');

    // 저장 버튼 클릭
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // 저장 API payload 검증
    const req = await capture.waitForRequest();
    const payload = req.payload as {
      name: string;
      steps: Array<{ scriptType: string; apiConnectionId?: number; apiConfig?: { path?: string } }>;
    };

    expect(payload.name).toBe('API 파이프라인');
    expect(payload.steps).toHaveLength(1);

    const step = payload.steps[0];
    // PipelineStepRequest.scriptType — 백엔드 DTO 필드명
    expect(step.scriptType).toBe('API_CALL');
    // saved 모드: apiConnectionId가 설정되어야 한다
    expect(step.apiConnectionId).toBe(1);
    // path 필드가 apiConfig에 포함되어야 한다
    expect(step.apiConfig?.path).toBe('/v1/data');
  });
});
