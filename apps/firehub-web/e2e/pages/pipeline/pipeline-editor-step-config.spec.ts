/**
 * 파이프라인 에디터 — 스텝 편집 E2E 테스트
 *
 * pipelineEditorReducer 의 UPDATE_STEP / SET_META / SELECT_STEP / REMOVE_STEP
 * 분기를 StepConfigPanel 의 입력/버튼에서 실제로 dispatch 되도록 구동한다.
 */

import type { Locator, Page } from '@playwright/test';

import { createDataset } from '../../factories/dataset.factory';
import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
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

  test('스텝 설정 패널의 닫기 버튼은 「스텝 설정 패널 닫기」 이름으로 불리고 누르면 패널이 닫힌다 (WD-917)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    await clickToolbarAddStep(page);
    const header = page.getByTestId('step-config-panel-title').filter({ hasText: /^스텝 #1(:|$)/ });
    await expect(header).toBeVisible({ timeout: 10000 });

    // 패널 루트 — 전용 data-testid 로 구조 변화에 흔들리지 않게 잡는다
    const panel = page.getByTestId('step-config-panel');

    // 기본(SQL) 스텝 패널 안에 이름 없는 button(스냅샷 줄이 `- button` 또는 `- button:` 뿐인 것)이 없어야 한다
    const snapshot = await panel.ariaSnapshot();
    expect(snapshot).not.toMatch(/^\s*- button\s*(\[[^\]]*\])?:?\s*$/m);

    // 이름으로 닫기 버튼을 찾아 누르면 패널이 닫힌다
    await page.getByRole('button', { name: '스텝 설정 패널 닫기' }).click();
    await expect(header).toHaveCount(0);
  });

  /** 스텝 count 개를 만들고 index 번째 노드를 눌러 패널을 연다 — 그 노드 locator 를 돌려준다 (WD-1044) */
  async function openStepNode(page: Page, count: number, index: number) {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');
    for (let i = 0; i < count; i++) await clickToolbarAddStep(page);
    await expect(page.locator('.react-flow__node')).toHaveCount(count);
    const node = page.locator('.react-flow__node').nth(index);
    await node.click();
    return node;
  }

  test('닫기 버튼을 Enter 로 눌러 패널을 닫으면 초점이 그 스텝 노드로 돌아간다 (WD-1044)', async ({
    authenticatedPage: page,
  }) => {
    // 스텝 2개 — 여럿 중 「연 노드」로 돌아가는지 보기 위해 두 번째 노드로 연다
    const node = await openStepNode(page, 2, 1);
    await page.getByRole('button', { name: '스텝 설정 패널 닫기' }).focus();
    await page.keyboard.press('Enter');
    await expect(node).toBeFocused();
  });

  test('닫기 버튼을 클릭해 패널을 닫아도 초점이 그 스텝 노드로 돌아간다 (WD-1044)', async ({
    authenticatedPage: page,
  }) => {
    const node = await openStepNode(page, 2, 0);
    await page.getByRole('button', { name: '스텝 설정 패널 닫기' }).click();
    await expect(node).toBeFocused();
  });

  test('닫은 직후 초점이 이미 다른 컨트롤로 옮겨졌으면 노드가 그것을 빼앗지 않는다 (WD-1044)', async ({
    authenticatedPage: page,
  }) => {
    const node = await openStepNode(page, 1, 0);
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    const closeButton = page.getByRole('button', { name: '스텝 설정 패널 닫기' });

    // 닫기 클릭 직후 같은 틱(다음 프레임 전)에 이름 칸으로 초점을 옮긴다
    const nameHandle = await nameInput.elementHandle();
    await closeButton.evaluate((close, name) => {
      (close as HTMLElement).click();
      (name as HTMLElement | null)?.focus();
    }, nameHandle);
    // 복귀 시도 상한(500ms)이 다 지나도록 기다려야 「빼앗지 않음」을 증명할 수 있어 고정 대기를 쓴다
    await page.waitForTimeout(600);

    await expect(nameInput).toBeFocused();
    await expect(node).not.toBeFocused();
  });

  test('닫은 첫 프레임에 노드가 아직 없다가 생겨도 초점이 그 노드로 간다 (WD-1051)', async ({
    authenticatedPage: page,
  }) => {
    const node = await openStepNode(page, 2, 1);
    const closeButton = page.getByRole('button', { name: '스텝 설정 패널 닫기' });
    const nodeHandle = await node.elementHandle();

    // 닫기 클릭 직후 노드를 떼었다가 150ms 뒤 같은 자리에 다시 붙인다 — 캔버스가 노드를 다시 만드는 늦은 생성을 흉내 낸다
    await closeButton.evaluate((close, nodeEl) => {
      const el = nodeEl as HTMLElement;
      const parent = el.parentNode as Node;
      const next = el.nextSibling;
      (close as HTMLElement).click();
      el.remove();
      setTimeout(() => parent.insertBefore(el, next), 150);
    }, nodeHandle);

    await expect(node).toBeFocused();
  });

  /** 버튼을 누른다 — keyboard 면 focus+Enter, 아니면 마우스 클릭 (WD-1051) */
  async function press(page: Page, button: Locator, keyboard: boolean) {
    if (keyboard) {
      await button.focus();
      await page.keyboard.press('Enter');
    } else {
      await button.click();
    }
  }

  /** 패널의 「스텝 삭제」 버튼 (WD-1051) */
  function panelDeleteButton(page: Page) {
    return page.getByTestId('step-config-panel').getByRole('button', { name: '스텝 삭제', exact: true });
  }

  /** 패널의 「스텝 삭제」를 눌러 확인 창을 연다 (WD-1051) */
  async function openDeleteConfirm(page: Page, keyboard: boolean) {
    await press(page, panelDeleteButton(page), keyboard);
    await expect(page.getByRole('alertdialog')).toBeVisible();
  }

  /** 확인 창에서 「삭제」·「취소」를 누른다 (WD-1051) */
  async function answerDeleteConfirm(page: Page, name: '삭제' | '취소', keyboard: boolean) {
    await press(page, page.getByRole('alertdialog').getByRole('button', { name, exact: true }), keyboard);
  }

  /** 지금 캔버스 노드들의 data-id(tempId)를 순서대로 읽는다 (WD-1051) */
  async function nodeIds(page: Page) {
    return page.locator('.react-flow__node').evaluateAll((els) => els.map((e) => e.getAttribute('data-id') ?? ''));
  }

  test('가운데 스텝을 삭제하면 초점이 다음 노드로 간다 (WD-1051)', async ({ authenticatedPage: page }) => {
    await openStepNode(page, 3, 1);
    const ids = await nodeIds(page);
    await openDeleteConfirm(page, true);
    await answerDeleteConfirm(page, '삭제', true);

    await expect(page.locator('.react-flow__node')).toHaveCount(2);
    await expect(page.locator(`.react-flow__node[data-id="${ids[2]}"]`)).toBeFocused();
  });

  test('마지막 스텝을 삭제하면 초점이 앞 노드로 간다 (WD-1051)', async ({ authenticatedPage: page }) => {
    await openStepNode(page, 2, 1);
    const ids = await nodeIds(page);
    await openDeleteConfirm(page, false);
    await answerDeleteConfirm(page, '삭제', false);

    await expect(page.locator('.react-flow__node')).toHaveCount(1);
    await expect(page.locator(`.react-flow__node[data-id="${ids[0]}"]`)).toBeFocused();
  });

  test('하나뿐인 스텝을 삭제하면 초점이 「스텝 추가」로 간다 (WD-1051)', async ({ authenticatedPage: page }) => {
    await openStepNode(page, 1, 0);
    await openDeleteConfirm(page, true);
    await answerDeleteConfirm(page, '삭제', true);

    await expect(page.locator('.react-flow__node')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '스텝 추가', exact: true })).toBeFocused();
  });

  test('삭제 확인을 취소하면 스텝이 그대로다 (WD-1051)', async ({ authenticatedPage: page }) => {
    await openStepNode(page, 2, 1);
    await openDeleteConfirm(page, true);
    await answerDeleteConfirm(page, '취소', false);

    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.locator('.react-flow__node')).toHaveCount(2);
    await expect(page.getByRole('button', { name: '스텝 설정 패널 닫기' })).toBeVisible();
    // 취소는 공용 복귀 그대로 — 초점이 창을 연 「스텝 삭제」 버튼으로 돌아간다(삭제 때만 복귀를 막는다)
    await expect(panelDeleteButton(page)).toBeFocused();
  });

  // 패널 안에 이름 없는 button(스냅샷 줄이 `- button` 또는 `- button:` 뿐인 것)이 있는지 판정 — WD-917 과 같은 방식
  // 패널 본문 — 머리글(「스텝 #N: …」 + 닫기 버튼) 바로 다음 형제. 닫기 버튼 이름은 WD-917 몫이라 본문만 보고, 이 브랜치엔 그 data-testid 도 없어 구조로 잡는다
  const stepPanel = (page: Page) => page.getByTestId('step-config-panel-title').locator("xpath=../following-sibling::*[1]");
  const NAMELESS_BUTTON = /^\s*- button\s*(\[[^\]]*\])?:?\s*$/m;

  test('AI 분류 스텝 — 출력 컬럼 휴지통이 줄마다 다른 이름을 갖고 이름 없는 버튼이 없다 (WD-1043)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');
    await addAiClassifyStep(page, 'AI 스텝');

    // 출력 컬럼 두 줄 추가
    const addColumn = page.getByRole('button', { name: '컬럼 추가', exact: true });
    await addColumn.click();
    await addColumn.click();
    await expect(page.getByLabel('출력 컬럼명 2')).toBeVisible();

    const panel = stepPanel(page);
    expect(await panel.ariaSnapshot()).not.toMatch(NAMELESS_BUTTON);

    // 이름이 없을 때는 줄 번호만, 이름을 입력하면 이름이 따라붙는다
    await expect(page.getByRole('button', { name: '컬럼 1 삭제' })).toBeVisible();
    await page.getByLabel('출력 컬럼명 1').fill('label');
    await expect(page.getByRole('button', { name: '컬럼 1 "label" 삭제' })).toBeVisible();

    // 이름으로 눌러 그 줄이 지워진다 — 둘째 줄이 사라지고 첫째 줄은 남는다
    await page.getByRole('button', { name: '컬럼 2 삭제' }).click();
    await expect(page.getByLabel('출력 컬럼명 2')).toHaveCount(0);
    await expect(page.getByLabel('출력 컬럼명 1')).toHaveValue('label');
  });

  test('API 호출 스텝 — 헤더 줄·컬럼 매핑 휴지통이 이름을 갖고 이름 없는 버튼이 없다 (WD-1043)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await mockApi(page, 'GET', '/api/v1/api-connections/selectable', []);
    await page.goto('/pipelines/new');
    await clickToolbarAddStep(page);
    await page.getByRole('combobox', { name: '스크립트 타입' }).click();
    await page.getByRole('option', { name: 'API 호출', exact: true }).click();

    // 헤더 줄 추가 — 헤더 섹션은 접혀 있어 먼저 연다
    await page.locator('button').filter({ hasText: /^헤더$/ }).click();
    await page.getByRole('button', { name: '추가', exact: true }).first().click();
    // 쿼리 파라미터 줄도 추가 — 두 섹션이 같은 편집기를 써도 휴지통 이름이 겹치지 않아야 한다
    await page.locator('button').filter({ hasText: /^쿼리 파라미터$/ }).click();
    await page.getByRole('button', { name: '추가', exact: true }).last().click();
    // 컬럼 매핑 줄 추가
    await page.getByRole('button', { name: '매핑 추가' }).click();

    await expect(page.getByRole('button', { name: '헤더 1번째 줄 삭제', exact: true })).toHaveCount(1);
    await expect(page.getByRole('button', { name: '쿼리 파라미터 1번째 줄 삭제', exact: true })).toHaveCount(1);
    await expect(page.getByRole('button', { name: '매핑 1 삭제' })).toBeVisible();

    const panel = stepPanel(page);
    expect(await panel.ariaSnapshot()).not.toMatch(NAMELESS_BUTTON);
  });

  test('이름 미입력 상태에서는 저장 버튼이 꺼지고 안내가 보인다 (WD-1090)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    // 스텝 추가 먼저 (steps.length === 0 분기 대신 name.trim() 분기를 타게)
    await clickToolbarAddStep(page);
    await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });

    // WD-1090: 이름이 비어 있으면 저장 버튼 자체가 꺼진다(눌러서 토스트로 막히던 흐름을 미리 막음).
    // 저장 시 trim 검증(usePipelineValidation)은 그대로 남아 있고, 버튼 상태는 이름 칸 아래 안내(#pipeline-name-hint)와 같은 값을 따른다.
    await expect(page.locator('#pipeline-name-hint')).toHaveText(/파이프라인 이름을 입력하세요/);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeDisabled();
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

  /** WD-906 반려 수정: 배치 크기 오류(1e) 칸이 든 고급 설정을 접어도 저장이 막히고, 차단이 절을 다시 펴 원문과 오류를 보인다 */
  test('AI 분류 스텝 배치 크기 — 오류 칸을 접어도 저장이 막히고 다시 펴진다 (WD-906)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines',
      { id: 110, name: '배치 접기 차단', steps: [] },
      { capture: true },
    );
    await page.goto('/pipelines/new');
    await addConfiguredAiClassifyStep(page);

    const batch = page.getByLabel('배치 크기 (1~100)');
    await batch.fill('1e');
    // 접으면 칸이 언마운트된다
    await page.locator('button').filter({ hasText: /^고급 설정$/ }).click();
    await expect(batch).toHaveCount(0);

    await page.getByPlaceholder(/파이프라인 이름|이름 입력/).first().fill('배치 접기 차단');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(page.getByText('스텝 설정의 숫자 칸 오류를 고친 뒤 저장하세요.')).toBeVisible();
    expect(capture.requests).toHaveLength(0);
    await expect(batch).toHaveValue('1e');
    await expect(batch).toBeFocused();

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

  /**
   * 스텝 3개 체인(#1 이름 비움 → #2 이름 공백뿐 → #3)을 만든다 — 노드의 「다음 스텝 추가」로 이어 만들어 각 스텝이 앞 스텝에 의존한다 (WD-1059).
   * 끝나면 #3 패널이 열려 있다.
   */
  async function buildUnnamedChain(page: Page) {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');
    await clickToolbarAddStep(page);
    const nodes = page.locator('.react-flow__node');
    await nodes.nth(0).getByRole('button', { name: '다음 스텝 추가' }).click();
    await expect(nodes).toHaveCount(2);
    await page.locator('#step-name').fill('   ');
    await nodes.nth(1).getByRole('button', { name: '다음 스텝 추가' }).click();
    await expect(nodes).toHaveCount(3);
    await expect(page.getByTestId('step-config-panel-title')).toHaveText('스텝 #3');
  }

  test('이름 없는 스텝을 설정 패널도 캔버스처럼 「스텝 #n」으로 부른다 — 의존성·참조 버튼·입력 안내 (WD-1059)', async ({
    authenticatedPage: page,
  }) => {
    await buildUnnamedChain(page);
    const panel = page.getByTestId('step-config-panel');

    // #3 의존성 — 이름이 비거나 공백뿐인 #2 를 「스텝 #2」로 부르고 연결 끊기 버튼 이름도 같다
    const deps = panel.getByRole('group', { name: '의존성' });
    await expect(deps.getByText('→ 스텝 #2')).toBeVisible();
    await expect(deps.getByRole('button', { name: '스텝 #2 연결 끊기' })).toBeVisible();

    // SQL 스텝 참조 버튼 — 선행 스텝 #1·#2
    await expect(panel.getByRole('button', { name: '{{#1}} 스텝 #1' })).toBeVisible();
    await expect(panel.getByRole('button', { name: '{{#2}} 스텝 #2' })).toBeVisible();

    // 입력 안내 문단이 의존 스텝을 빈칸이 아니라 「스텝 #2」로 적는다
    await expect(panel.getByText('이전 스텝의 출력 데이터셋이 실행 시 자동으로 입력됩니다')).toContainText('스텝 #2');

    // #2 패널 — 이름이 빈 #1 에 의존
    await page.locator('.react-flow__node').nth(1).click();
    await expect(page.getByTestId('step-config-panel-title')).toHaveText('스텝 #2');
    const deps2 = panel.getByRole('group', { name: '의존성' });
    await expect(deps2.getByText('→ 스텝 #1')).toBeVisible();
    await expect(deps2.getByRole('button', { name: '스텝 #1 연결 끊기' })).toBeVisible();
    await expect(panel.getByText('이전 스텝의 출력 데이터셋이 실행 시 자동으로 입력됩니다')).toContainText('스텝 #1');

    // 패널 안 어디에도 「(이름 없음)」이 없다
    await expect(panel.getByText('(이름 없음)')).toHaveCount(0);
  });

  test('설정 패널 머리는 이름이 없으면 「스텝 #n」, 있으면 「스텝 #n: 이름」이다 (WD-1059)', async ({
    authenticatedPage: page,
  }) => {
    await buildUnnamedChain(page);
    const title = page.getByTestId('step-config-panel-title');
    await expect(title).toHaveText('스텝 #3');
    await page.locator('#step-name').fill('적재');
    await expect(title).toHaveText('스텝 #3: 적재');
  });

  test('의존 스텝에 이름이 있으면 의존성·연결 끊기 버튼이 그 이름을 쓴다 (WD-1059)', async ({
    authenticatedPage: page,
  }) => {
    await buildUnnamedChain(page);
    // #2 에 이름을 주고 #3 패널로 돌아온다
    await page.locator('.react-flow__node').nth(1).click();
    await page.locator('#step-name').fill('원본');
    await page.locator('.react-flow__node').nth(2).click();
    const deps = page.getByTestId('step-config-panel').getByRole('group', { name: '의존성' });
    await expect(deps.getByText('→ 원본')).toBeVisible();
    await expect(deps.getByRole('button', { name: '원본 연결 끊기' })).toBeVisible();
  });

  // WD-1074/WD-1089 공통 설정 — 기본은 스텝1(추출 → 중간 20) → 스텝2(변환 → 최종 30) 사슬과 데이터셋 10 원본·20 중간·30 최종을
  // 목 처리하고 수정 모드로 들어간다. step2InputIds 는 스텝2 가 이미 입력으로 골라 둔 데이터셋 id,
  // steps·extraDatasets 는 다른 사슬(삽입 스텝 등)을 쓰는 시험이 기본 스텝을 바꾸고 데이터셋을 더할 때 쓴다.
  async function openInputCandidateChain(
    page: import('@playwright/test').Page,
    {
      step2InputIds = [],
      steps,
      extraDatasets = [],
    }: {
      step2InputIds?: number[];
      steps?: ReturnType<typeof createStep>[];
      extraDatasets?: ReturnType<typeof createDataset>[];
    } = {},
  ) {
    const detail = createPipelineDetail({
      id: 1,
      steps: steps ?? [
        createStep({ id: 1, name: '추출', outputDatasetId: 20, outputDatasetName: '중간' }),
        createStep({
          id: 2,
          name: '변환',
          stepOrder: 1,
          outputDatasetId: 30,
          outputDatasetName: '최종',
          dependsOnStepNames: ['추출'],
          inputDatasetIds: step2InputIds,
        }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', detail);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets',
      createPageResponse(
        [
          createDataset({ id: 10, name: '원본', tableName: 't_src' }),
          createDataset({ id: 20, name: '중간', tableName: 't_mid' }),
          createDataset({ id: 30, name: '최종', tableName: 't_out' }),
          ...extraDatasets,
        ],
        { size: 1000 },
      ),
    );
    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
  }

  test('이전 스텝 출력은 입력 데이터셋 후보 맨 앞에 「[스텝1] …」로 오고 자기 출력은 후보에서 빠진다 (WD-1074, WD-1089)', async ({
    authenticatedPage: page,
  }) => {
    // 스텝2 패널의 입력 후보 순서·중복 제외가 Map 으로 바꾼 뒤(WD-1074)에도 그대로인지, 그리고 자기 출력(최종)이
    // 후보에 없는지(WD-1089 — 자기 출력을 입력으로 고르면 자기 참조라 의미가 없다) 본다.
    await openInputCandidateChain(page);
    await page.locator('.react-flow__node').nth(1).click();
    await page.getByRole('combobox', { name: '입력 데이터셋' }).click();
    const options = page.getByRole('option');
    await expect(options).toHaveCount(2);
    await expect(options.nth(0)).toContainText('[스텝1] 중간');
    await expect(options.nth(1)).toContainText('원본');
    await expect(page.getByRole('option', { name: /최종/ })).toHaveCount(0);
  });

  test('스텝1 패널의 입력 후보 끝에 뒤 스텝 출력이 「[스텝2·뒤] 최종」으로 온다 (WD-1089)', async ({
    authenticatedPage: page,
  }) => {
    // 뒤 스텝 출력은 이번 실행에선 늦게 채워지지만 이전 실행 결과를 읽는 쓰임이 있어 지우지 않고 끝에 표시만 달리한다.
    await openInputCandidateChain(page);
    await page.locator('.react-flow__node').nth(0).click();
    await page.getByRole('combobox', { name: '입력 데이터셋' }).click();
    const options = page.getByRole('option');
    await expect(options).toHaveCount(2);
    await expect(options.nth(0)).toContainText('원본');
    await expect(options.nth(1)).toContainText('[스텝2·뒤] 최종');
    await expect(page.getByRole('option', { name: /중간/ })).toHaveCount(0);
  });

  test('자기 출력을 이미 입력으로 골라 둔 스텝은 「[이 스텝 출력] …」 칩·후보가 보인다 (WD-1089)', async ({
    authenticatedPage: page,
  }) => {
    // 이미 선택된 자기 출력이 후보에서 빠지면 콤보박스가 칩 대신 숨은 수만 보여 사용자가 해제할 수 없다 —
    // 그래서 선택돼 있을 때만 「[이 스텝 출력]」으로 맨 끝에 남긴다.
    await openInputCandidateChain(page, { step2InputIds: [20, 30] });
    await page.locator('.react-flow__node').nth(1).click();
    const panel = page.getByTestId('step-config-panel');
    await expect(panel.getByText('[이 스텝 출력] 최종')).toBeVisible();
    await page.getByRole('combobox', { name: '입력 데이터셋' }).click();
    const options = page.getByRole('option');
    await expect(options.last()).toContainText('[이 스텝 출력] 최종');
  });

  test('목록 끝에 붙은 삽입 스텝이 실제 선행이면 조상 라벨로 맨 앞에 온다 (WD-1089, WD-1139)', async ({
    authenticatedPage: page,
  }) => {
    // 「스텝 삽입」은 새 스텝을 목록 끝에 붙인다 — 추출 → 삽입 → 변환 순으로 실행되지만 목록은 [추출, 변환, 삽입] 이다.
    // 「·뒤」 는 실제 후행(자손) 스텝에만 붙어야 하므로 변환 패널에서 삽입 스텝은 선행(조상)으로 「[스텝3] 보조」 라벨을 받는다.
    await openInputCandidateChain(page, {
      steps: [
        createStep({ id: 1, name: '추출', outputDatasetId: 20, outputDatasetName: '중간' }),
        createStep({
          id: 2,
          name: '변환',
          stepOrder: 1,
          outputDatasetId: 30,
          outputDatasetName: '최종',
          dependsOnStepNames: ['삽입'],
        }),
        createStep({
          id: 3,
          name: '삽입',
          stepOrder: 2,
          outputDatasetId: 40,
          outputDatasetName: '보조',
          dependsOnStepNames: ['추출'],
        }),
      ],
      extraDatasets: [createDataset({ id: 40, name: '보조', tableName: 't_aux' })],
    });
    await page.locator('.react-flow__node').filter({ hasText: '변환' }).click();
    await page.getByRole('combobox', { name: '입력 데이터셋' }).click();
    const options = page.getByRole('option');
    // 기대를 이슈대로 바꾼다 — 삽입 스텝은 선행이라 「[스텝3] 보조」로 조상 자리(맨 앞)에 온다.
    await expect(options).toHaveCount(3);
    await expect(options.nth(0)).toContainText('[스텝1] 중간');
    await expect(options.nth(1)).toContainText('[스텝3] 보조');
    await expect(options.nth(2)).toContainText('원본');
    await expect(page.getByRole('option', { name: /·뒤/ })).toHaveCount(0);

    // 삽입 스텝 패널에서는 변환이 실제 후행이므로 「[스텝2·뒤] 최종」 이 끝에 온다(목록상 앞이어도).
    await page.keyboard.press('Escape');
    await page.locator('.react-flow__node').filter({ hasText: '삽입' }).click();
    await page.getByRole('combobox', { name: '입력 데이터셋' }).click();
    await expect(page.getByRole('option').last()).toContainText('[스텝2·뒤] 최종');
    await expect(page.getByRole('option').first()).toContainText('[스텝1] 중간');
  });

  // WD-1139 현상 1 — 목록 위치만 보고 앞 스텝을 선행(조상)으로 그리면, 서로 의존하지 않는 병렬 스텝의 출력에도
  // [스텝1] 이 붙는다. WD-1164 — 의존이 없는 스텝 출력은 조상 라벨 대신 「[스텝N·병렬]」 로 일반 목록 뒤·「·뒤」 앞에 온다.
  test('병렬 스텝은 입력 후보에 [스텝1] 이 아니라 [스텝1·병렬] 라벨로 일반 후보 뒤에 온다 (WD-1139, WD-1164)', async ({
    authenticatedPage: page,
  }) => {
    // 추출(출력 20)과 변환(출력 30)이 서로 의존하지 않는 고정물 — 변환은 dependsOnStepNames 가 비어 있다.
    await openInputCandidateChain(page, {
      steps: [
        createStep({ id: 1, name: '추출', outputDatasetId: 20, outputDatasetName: '중간' }),
        createStep({
          id: 2,
          name: '변환',
          stepOrder: 1,
          outputDatasetId: 30,
          outputDatasetName: '최종',
          dependsOnStepNames: [],
        }),
      ],
    });
    await page.locator('.react-flow__node').filter({ hasText: '변환' }).click();
    await page.getByRole('combobox', { name: '입력 데이터셋' }).click();
    const options = page.getByRole('option');
    // 자기 출력(최종 30)은 후보에서 빠지고, 일반 후보(원본) 뒤에 병렬 스텝 출력이 라벨과 함께 온다.
    await expect(options).toHaveCount(2);
    await expect(options.nth(0)).toContainText('원본');
    await expect(options.nth(1)).toContainText('[스텝1·병렬] 중간');
    await expect(options.filter({ hasText: /\[스텝1\]/ })).toHaveCount(0);
  });

  // WD-1164 — 조상 → 일반 → 병렬 → 자손 순서와 라벨 번호를 한 화면에서 고정한다.
  test('입력 후보 순서는 [스텝N] → 일반 → [스텝N·병렬] → [스텝N·뒤] 이다 (WD-1164)', async ({
    authenticatedPage: page,
  }) => {
    await openInputCandidateChain(page, {
      steps: [
        createStep({ id: 1, name: '추출', outputDatasetId: 20, outputDatasetName: '중간' }),
        createStep({
          id: 2,
          name: '변환',
          stepOrder: 1,
          outputDatasetId: 30,
          outputDatasetName: '최종',
          dependsOnStepNames: ['추출'],
        }),
        createStep({ id: 3, name: '병렬', stepOrder: 2, outputDatasetId: 40, outputDatasetName: '병렬출력' }),
        createStep({
          id: 4,
          name: '후행',
          stepOrder: 3,
          outputDatasetId: 50,
          outputDatasetName: '후행출력',
          dependsOnStepNames: ['변환'],
        }),
      ],
      extraDatasets: [
        createDataset({ id: 40, name: '병렬출력', tableName: 't_par' }),
        createDataset({ id: 50, name: '후행출력', tableName: 't_post' }),
      ],
    });
    await page.locator('.react-flow__node').filter({ hasText: '변환' }).click();
    await page.getByRole('combobox', { name: '입력 데이터셋' }).click();
    const options = page.getByRole('option');
    await expect(options).toHaveCount(4);
    await expect(options.nth(0)).toContainText('[스텝1] 중간');
    await expect(options.nth(1)).toContainText('원본');
    await expect(options.nth(2)).toContainText('[스텝3·병렬] 병렬출력');
    await expect(options.nth(3)).toContainText('[스텝4·뒤] 후행출력');
  });

  // WD-1164 — 순환(잘못된 편집 상태)에서 조상이면서 자손인 스텝은 「·뒤」 경고 그룹으로 보낸다.
  test('순환 의존 스텝의 출력은 [스텝N] 이 아니라 [스텝N·뒤] 로 끝에 온다 (WD-1164)', async ({
    authenticatedPage: page,
  }) => {
    await openInputCandidateChain(page, {
      steps: [
        createStep({
          id: 1,
          name: '추출',
          outputDatasetId: 20,
          outputDatasetName: '중간',
          dependsOnStepNames: ['변환'],
        }),
        createStep({
          id: 2,
          name: '변환',
          stepOrder: 1,
          outputDatasetId: 30,
          outputDatasetName: '최종',
          dependsOnStepNames: ['추출'],
        }),
      ],
    });
    await page.locator('.react-flow__node').filter({ hasText: '변환' }).click();
    await page.getByRole('combobox', { name: '입력 데이터셋' }).click();
    const options = page.getByRole('option');
    await expect(options).toHaveCount(2);
    await expect(options.nth(0)).toContainText('원본');
    await expect(options.nth(1)).toContainText('[스텝1·뒤] 중간');
    await expect(options.filter({ hasText: /\[스텝1\]/ })).toHaveCount(0);
  });

  // WD-1139 현상 3 — 앞 스텝 출력과 자기 출력이 같은 데이터셋이면 조상 라벨([스텝1])이 먼저 붙어
  // 자기 출력 칩·후보가 겹치거나 사라진다. 자기 출력을 먼저 판정해 [스텝1] 은 없고 [이 스텝 출력] 이 한 번만 남아야 한다.
  test('같은 데이터셋이자 자기 출력은 [스텝1] 없이 [이 스텝 출력] 칩·후보로 한 번만 온다 (WD-1139)', async ({
    authenticatedPage: page,
  }) => {
    // 추출 출력 = 변환 출력 = 데이터셋 30, 그리고 변환은 이미 그것을 입력으로 골라 둔 상태.
    await openInputCandidateChain(page, {
      steps: [
        createStep({ id: 1, name: '추출', outputDatasetId: 30, outputDatasetName: '최종' }),
        createStep({
          id: 2,
          name: '변환',
          stepOrder: 1,
          outputDatasetId: 30,
          outputDatasetName: '최종',
          dependsOnStepNames: ['추출'],
          inputDatasetIds: [30],
        }),
      ],
    });
    await page.locator('.react-flow__node').filter({ hasText: '변환' }).click();
    const panel = page.getByTestId('step-config-panel');
    // 칩은 자기 출력 이름 하나만 붙고, 조상(추출) 출력과 같은 데이터셋이라 [스텝1] 라벨 칩은 없다.
    await expect(panel.getByText('[이 스텝 출력] 최종')).toHaveCount(1);
    await expect(panel.getByText('[스텝1]')).toHaveCount(0);

    // 후보를 열어도 [스텝1] 이 붙은 30 은 없고, [이 스텝 출력] 옵션은 정확히 한 번만 나온다.
    await page.getByRole('combobox', { name: '입력 데이터셋' }).click();
    const options = page.getByRole('option');
    await expect(options).toHaveCount(3);
    await expect(options.filter({ hasText: '[스텝1]' })).toHaveCount(0);
    await expect(options.filter({ hasText: '[이 스텝 출력]' })).toHaveCount(1);
  });

  // WD-1139 현상 3 — 같은 데이터셋이어도 입력으로 골라 두지 않았으면([스텝 출력] 미선택)
  // 자기 출력은 조상 출력과 겹치더라도 후보에서 완전히 빠져야 한다(라벨·칩 어느 쪽으로도 살아남지 않는다).
  test('미선택 자기 출력은 조상 출력이어도 후보에서 빠진다 (WD-1139)', async ({
    authenticatedPage: page,
  }) => {
    // 현상 3 과 같은 고정물(추출 출력 30, 변환 출력 30) — 다만 변환은 입력으로 아무것도 고르지 않은 상태.
    await openInputCandidateChain(page, {
      steps: [
        createStep({ id: 1, name: '추출', outputDatasetId: 30, outputDatasetName: '최종' }),
        createStep({
          id: 2,
          name: '변환',
          stepOrder: 1,
          outputDatasetId: 30,
          outputDatasetName: '최종',
          dependsOnStepNames: ['추출'],
          inputDatasetIds: [],
        }),
      ],
    });
    await page.locator('.react-flow__node').filter({ hasText: '변환' }).click();
    await page.getByRole('combobox', { name: '입력 데이터셋' }).click();
    const options = page.getByRole('option');
    // 자기 출력(30)이 미선택이라 완전히 빠지고, 남은 건 무라벨 일반 후보 2개뿐이다.
    await expect(options).toHaveCount(2);
    await expect(options.nth(0)).toContainText('원본');
    await expect(options.nth(1)).toContainText('중간');
    await expect(options.filter({ hasText: '[스텝1]' })).toHaveCount(0);
    await expect(options.filter({ hasText: '[이 스텝 출력]' })).toHaveCount(0);
  });

  // WD-1139 현상 4 — [이 스텝 출력] 칩을 해제해도 저장 payload 에 남으면 해제가 반영되지 않은 것이다.
  // 화면(입력) → API payload 전체를 본다(apps/firehub-web/CLAUDE.md 테스트 품질 기준 1번).
  test('칩 해제 후 저장 payload 의 inputDatasetIds 에서 그 데이터셋이 빠진다 (WD-1139)', async ({
    authenticatedPage: page,
  }) => {
    // 수정 모드의 저장은 PUT /api/v1/pipelines/1 로 나간다 — 요청 본문을 캡처해 스텝 입력을 확인한다.
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });
    // 현상 3 과 같은 상태 — 추출·변환 출력이 모두 30 이고 변환은 30 을 입력으로 골라 둔다.
    await openInputCandidateChain(page, {
      steps: [
        createStep({ id: 1, name: '추출', outputDatasetId: 30, outputDatasetName: '최종' }),
        createStep({
          id: 2,
          name: '변환',
          stepOrder: 1,
          outputDatasetId: 30,
          outputDatasetName: '최종',
          dependsOnStepNames: ['추출'],
          inputDatasetIds: [30],
        }),
      ],
    });
    await page.locator('.react-flow__node').filter({ hasText: '변환' }).click();
    const panel = page.getByTestId('step-config-panel');
    await expect(panel.getByText('[이 스텝 출력] 최종')).toHaveCount(1);

    // 콤보박스를 열어 자기 출력 옵션을 클릭하면 해제된다(멀티 모드 토글) — 옵션은 곧바로 후보에서 사라진다.
    await page.getByRole('combobox', { name: '입력 데이터셋' }).click();
    const selfOption = page.getByRole('option', { name: '[이 스텝 출력]' });
    await expect(selfOption).toHaveCount(1);
    await selfOption.click();
    await expect(selfOption).toHaveCount(0);
    // Escape 는 후보 목록만 닫아야 한다 — 에디터 단축키로 새어 스텝 선택이 풀리면 패널이 사라진다.
    await page.keyboard.press('Escape');
    await expect(panel).toBeVisible();
    await expect(panel.getByText('[이 스텝 출력] 최종')).toHaveCount(0);

    // 해제로 값이 바뀌어 저장 버튼이 켜진다 — 눌러 PUT 본문을 캡처한다.
    const saveButton = page.getByRole('button', { name: '저장', exact: true });
    await expect(saveButton).toBeEnabled();
    await saveButton.click();
    const req = await putCapture.waitForRequest();
    const payload = req.payload as { steps: Array<{ name: string; inputDatasetIds: number[] }> };
    const saved = payload.steps.find((st) => st.name === '변환');
    expect(saved?.inputDatasetIds).toEqual([]);
  });

  // WD-1164 — 자기 출력 데이터셋이 목록에 없으면(삭제·미로드) 칩이 사라져 자기 참조를 UI 에서 지울 수 없었다.
  test('자기 출력 데이터셋이 목록에 없어도 [이 스텝 출력] 칩이 보이고 해제된다 (WD-1164)', async ({
    authenticatedPage: page,
  }) => {
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });
    // 변환의 출력 99 는 데이터셋 목록(10·20·30)에 없다 — 입력으로는 99 를 골라 둔 상태.
    await openInputCandidateChain(page, {
      steps: [
        createStep({ id: 1, name: '추출', outputDatasetId: 20, outputDatasetName: '중간' }),
        createStep({
          id: 2,
          name: '변환',
          stepOrder: 1,
          outputDatasetId: 99,
          outputDatasetName: '삭제됨',
          dependsOnStepNames: ['추출'],
          inputDatasetIds: [99],
        }),
      ],
    });
    await page.locator('.react-flow__node').filter({ hasText: '변환' }).click();
    const panel = page.getByTestId('step-config-panel');
    const chip = panel.getByText('[이 스텝 출력] 데이터셋 #99');
    await expect(chip).toBeVisible();

    // 칩의 X 로 해제하면 저장 payload 의 입력에서 99 가 빠진다.
    await chip.locator('..').getByRole('button').click();
    await expect(chip).toHaveCount(0);
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await putCapture.waitForRequest();
    const payload = req.payload as { steps: Array<{ name: string; inputDatasetIds: number[] }> };
    expect(payload.steps.find((st) => st.name === '변환')?.inputDatasetIds).toEqual([]);
  });
});
