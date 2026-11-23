/**
 * `size="sm"` 버튼의 최소 클릭 영역 회귀 시험 (WD-960)
 *
 * 결함: 버튼이 `size="sm"`(buttonVariants: `h-8` = 32px)을 쓰면서 `className` 으로 `h-7`(28px)을
 * 덮어써, 디자인 시스템 최소 클릭 영역 32×32px(WD-914 → WD-926 → WD-951)에 못 미쳤다(WCAG 2.5.8 의
 * 24×24 보다 높은 우리 규칙). 세 곳의 대상 버튼을 화면에 실제로 그려진 폭·높이
 * (`getBoundingClientRect()`)로 재어 전부 32px 이상인지 본다.
 *
 * - 대화 선택 머리(SessionSwitcher 트리거·새 대화)
 * - 데이터셋 상세 머리(쿼리 작성·차트 만들기·복제·상태 변경)
 * - 파이프라인 API_CALL 스텝 설정(추가·매핑 추가)·미리보기(원본 JSON 보기)
 *
 * 고치기 전에는 `h-7` 덕에 28px 로 그려져 먼저 레드였고, `h-7` 을 걷어내는 수정(앞 PR #1249)이
 * 반영돼 지금은 그린이다 — 이 시험의 몫은 그린을 지키는 것으로, `h-7` 이 다시 들어오면 레드로 떨어진다.
 * 각 테스트는 단언 전에 캡처를 남긴다 — 단언이 실패해도 그 시점까지 찍혔으므로 증거가 남는다.
 */

import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Locator, Page } from '@playwright/test';

import { API_FULL_URL_PLACEHOLDER } from '../../../src/lib/api-connection-form';
import type { AISession } from '../../../src/types/ai';
import type { ApiConnectionSelectable } from '../../../src/types/api-connection';
import { createAISession } from '../../factories/ai-session.factory';
import { createCategories } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';
import { clickToolbarAddStep, grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

// ESM 환경에서는 `__dirname`이 없으므로 `import.meta.url`로 현재 파일 디렉토리 경로를 계산한다
const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** 최소 클릭 영역 한 변(px) — `size="sm"` 이 칠해야 하는 32×32 */
const MIN = 32;

/**
 * 캡처 폴더 — `apps/firehub-web/test-results/tc/<suite>/` 규약을 따른다(루트 CLAUDE.md).
 * `__dirname` 기준이라 실행 위치(cwd)와 무관하게 항상 그 폴더에 남고, 재실행하면 같은 자리에 덮어쓴다.
 * 전/후 화면을 나눠 두려면 `WD960_SHOT_DIR` 로 하위 폴더를 지정한다 — 비어 있으면 스위트 폴더 바로 아래다
 * (이제 수정이 반영된 화면이라 「전」이 아니다).
 */
const SHOT_DIR = path.resolve(
  __dirname,
  '..',
  '..',
  '..',
  'test-results',
  'tc',
  'WD-960',
  process.env.WD960_SHOT_DIR ?? '',
);

/**
 * 단언 전 캡처 — 단언이 실패해도 이 시점까지 찍혔으므로 증거는 남는다. 폴더는 없으면 만든다.
 * @param page - 캡처할 페이지
 * @param fileName - 저장할 파일 이름(session-switcher.png 등)
 */
async function shot(page: Page, fileName: string) {
  mkdirSync(SHOT_DIR, { recursive: true });
  await page.screenshot({ path: path.join(SHOT_DIR, fileName), fullPage: false });
}

/**
 * 영역 안에서 접근 가능한 이름으로 찾은 버튼들의 폭·높이를 재어 전부 `MIN` 이상인지 본다.
 *
 * - 영역(`scope`)을 받는 이유: 페이지 전체에서 이름으로만 찾으면 같은 이름의 다른 버튼(숨은 패널·
 *   이 시험이 보지 않는 자리)까지 재어 범위 밖 실패를 낼 수 있다 — 이 시험이 세운 화면 줄·패널 안의
 *   대상 버튼만 재도록 범위를 좁힌다.
 * - `exact` 를 쓰는 이유: 「추가」 같은 짧은 이름을 부분 매칭하면 캔버스의 「다음 스텝 추가」(h-5 = 20px)까지
 *   잡아, 고쳐야 할 28px 가 아니라 엉뚱한 버튼으로 실패한다.
 * - 보이는 버튼만 센다: 보이지 않는 요소는 측정 대상이 아니고, `display:none` 이면 `getBoundingClientRect()`
 *   가 0 이라 32px 미달로 잡힌다. 한 개도 보이지 않으면 아래 `first()` 대기에서 실패해
 *   0건으로 공허하게 통과하지 않는다.
 *
 * @param scope - 대상 버튼이 들어 있는 영역(줄·헤더·패널)
 * @param names - 측정할 버튼의 접근 가능한 이름들(하나씩 순회)
 */
async function expectMinClickArea(scope: Locator, names: string[]) {
  for (const name of names) {
    const buttons = scope.getByRole('button', { name, exact: true }).filter({ visible: true });
    await expect(buttons.first()).toBeVisible();
    const sizes = await buttons.evaluateAll((els) =>
      els.map((el) => {
        const rect = el.getBoundingClientRect();
        return { width: rect.width, height: rect.height };
      }),
    );
    // `first()` 대기와 이 재해석 사이에 DOM 이 바뀌어 빈 배열이 되면 그대로 지나가지 않게 한다(0건 공허 통과 방지)
    expect(sizes.length, `${name} 측정 결과 0건`).toBeGreaterThan(0);
    for (const { width, height } of sizes) {
      expect(height, `${name} 높이 ${height}px < ${MIN}px`).toBeGreaterThanOrEqual(MIN);
      expect(width, `${name} 너비 ${width}px < ${MIN}px`).toBeGreaterThanOrEqual(MIN);
    }
  }
}

test.describe('size="sm" 버튼 최소 클릭 영역 (WD-960)', () => {
  test('대화 선택 머리 — 대화 전환 트리거·새 대화', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    // 대화 2개를 모킹해 「대화 이력 없음」 대신 세션 전환 트리거가 그려지게 한다
    const sessions: AISession[] = [
      createAISession({ id: 1, sessionId: 'wd960-1', title: '첫째 대화', updatedAt: '2026-11-23T03:00:00Z' }),
      createAISession({ id: 2, sessionId: 'wd960-2', title: '둘째 대화', updatedAt: '2026-11-23T02:00:00Z' }),
    ];
    await mockApi(page, 'GET', '/api/v1/ai/sessions', sessions);
    // 대화를 고르지 않아도 패널이 여는 중 메시지를 물을 수 있어 방어적으로 비운다
    await page.route(
      (url) => /^\/api\/v1\/ai\/sessions\/[^/]+\/messages$/.test(url.pathname),
      (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
    );
    await page.goto('/', { waitUntil: 'commit' });
    await page.getByRole('button', { name: /AI 상태/ }).click();
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5_000 });
    // 트리거는 aria-label 없이 안쪽 글자로 이름을 만든다 — 현재 대화가 없어 접근 가능한 이름은 「대화 선택」이다
    const trigger = page.getByRole('button', { name: '대화 선택', exact: true });
    await trigger.waitFor();
    // 트리거와 새 대화가 함께 있는 SessionSwitcher 줄(트리거의 바로 위 단) — 그 줄만 재는 영역으로 삼는다
    const switcherRow = trigger.locator('xpath=..');

    await shot(page, 'session-switcher.png');
    await expectMinClickArea(switcherRow, ['대화 선택', '새 대화']);
  });

  test('데이터셋 상세 머리 — 쿼리 작성·차트 만들기·복제·상태 변경', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    // 쓰기 버튼(복제·상태 변경)은 dataset:write 권한이 있을 때만 그려진다
    await grantDatasetPermissions(page);
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await page.goto('/data/datasets/1');
    // 네 버튼이 나란히 있는 머리글 동작 줄(쿼리 작성의 바로 위 단)를 재는 영역으로 삼는다 —
    // 다른 화면의 같은 이름 버튼(예: 목록의 「복제」)은 범위 밖이다
    const queryButton = page.getByRole('button', { name: '쿼리 작성', exact: true });
    await queryButton.waitFor();
    const headerActions = queryButton.locator('xpath=..');

    await shot(page, 'dataset-detail-header.png');
    await expectMinClickArea(headerActions, ['쿼리 작성', '차트 만들기', '복제', '상태 변경']);
  });

  test('파이프라인 API_CALL 스텝 — 추가·매핑 추가·원본 JSON 보기', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await grantPipelinePermissions(page);
    await setupEditorMocks(page);
    // 테스트 호출 성공 응답 — ApiCallPreview(「원본 JSON 보기」 포함)는 성공일 때만 그려진다
    await mockApi(page, 'POST', '/api/v1/pipelines/api-call/preview', {
      success: true,
      rawJson: '{"items":[{"id":1,"name":"테스트"}]}',
      rows: [{ id: '1', name: '테스트' }],
      columns: ['id', 'name'],
      totalExtractedRows: 1,
      errorMessage: null,
      resolvedUrl: 'https://example.com/api',
    });
    await addAndOpenApiCallStep(page);

    // 헤더 섹션은 기본이 접힘 — KvEditor 의 「추가」 버튼을 드러내기 위해 연다
    await page.locator('button').filter({ hasText: /^헤더$/ }).click();
    await page.getByRole('button', { name: '추가', exact: true }).waitFor();

    // URL 을 채워 테스트 호출을 실행하면 미리보기 결과와 「원본 JSON 보기」 가 나온다
    await page.getByPlaceholder(API_FULL_URL_PLACEHOLDER, { exact: true }).fill('https://example.com/api');
    await page.getByRole('button', { name: '테스트 호출' }).click();
    await page.getByRole('button', { name: '원본 JSON 보기', exact: true }).waitFor();

    await shot(page, 'api-call-step.png');
    // 설정 패널 내용 상자(#step-name 의 두 단 위) — 「추가」·「매핑 추가」가 들어 있는 자리다.
    // 그 안에는 미리보기(「원본 JSON 보기」)까지 들어오지만 이름이 달라 겹치지 않고,
    // 캔버스(「다음 스텝 추가」)는 밖이다 — 페이지 전체에서 찾지 않으므로 다른 자리의 같은 이름은 재지 않는다
    const configPanel = page.locator('#step-name').locator('xpath=../..');
    await expectMinClickArea(configPanel, ['추가', '매핑 추가']);
    // 「원본 JSON 보기」 는 미리보기 카드의 토글 줄 — 그 줄만 재는 영역으로 삼는다
    const jsonToggleRow = page.getByRole('button', { name: '원본 JSON 보기', exact: true }).locator('xpath=..');
    await expectMinClickArea(jsonToggleRow, ['원본 JSON 보기']);
  });
});

/** API 연결 선택 목록 — ApiConnectionSelectable 타입으로 스펙 정합성 보장 */
const MOCK_CONNECTIONS: ApiConnectionSelectable[] = [
  { id: 1, name: '공공 데이터 API', authType: 'API_KEY', baseUrl: 'https://api.data.go.kr' },
  { id: 2, name: '내부 서비스 API', authType: 'BEARER', baseUrl: 'https://internal.example.com' },
];

/**
 * 파이프라인 에디터 진입에 필요한 기본 API 모킹
 * (pipeline-editor-api-call-step.spec.ts 의 setupEditorMocks 와 같은 절차)
 * @param page - 대상 페이지
 */
async function setupEditorMocks(page: Page) {
  // 데이터셋 목록 (파이프라인 에디터 필수)
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [],
    page: 0,
    size: 1000,
    totalElements: 0,
    totalPages: 0,
  });
  // API 연결 선택 목록 (ApiCallStepConfig 의 ConnectionCombobox)
  await mockApi(page, 'GET', '/api/v1/api-connections/selectable', MOCK_CONNECTIONS);
}

/**
 * API_CALL 타입 스텝을 추가하고 설정 패널까지 여는 공통 절차
 * (pipeline-editor-api-call-step.spec.ts 의 addAndOpenApiCallStep 과 같은 절차)
 * 1. /pipelines/new 진입 → 스텝 추가 → 노드 클릭으로 StepConfigPanel 열기
 * 2. 스텝 타입 Select 에서 API 호출 선택
 * 3. Suspense lazy-load 완료 대기 — "기본 설정" 섹션 제목이 나타날 때까지
 * @param page - 대상 페이지
 */
async function addAndOpenApiCallStep(page: Page) {
  await page.goto('/pipelines/new');

  // 스텝 추가 → ADD_STEP dispatch (툴바만 정확히 집는다 — 노드의 20px 「다음 스텝 추가」 를 못 잡게)
  await clickToolbarAddStep(page);
  await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

  // ReactFlow 노드 클릭 → StepConfigPanel 열기
  await page.locator('.react-flow__node').first().click();
  await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

  // 스텝 타입 Select 클릭 — StepConfigPanel 의 "스크립트 타입" 셼렉트
  const typeSelect = page.getByRole('combobox').first();
  await expect(typeSelect).toBeVisible();
  await typeSelect.click();

  // API_CALL 옵션 선택 — SelectItem value="API_CALL", 표시 텍스트 "API 호출"
  const apiCallOption = page.getByRole('option', { name: 'API 호출' });
  await expect(apiCallOption).toBeVisible({ timeout: 5000 });
  await apiCallOption.click();

  // Suspense lazy-load 완료 대기 — ApiCallStepConfig 의 "기본 설정" 텍스트
  await expect(page.getByText('기본 설정')).toBeVisible({ timeout: 10000 });
}
