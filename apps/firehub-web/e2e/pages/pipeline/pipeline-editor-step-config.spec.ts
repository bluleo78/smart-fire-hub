/**
 * 파이프라인 에디터 — 스텝 편집 E2E 테스트
 *
 * pipelineEditorReducer 의 UPDATE_STEP / SET_META / SELECT_STEP / REMOVE_STEP
 * 분기를 StepConfigPanel 의 입력/버튼에서 실제로 dispatch 되도록 구동한다.
 */

import type { Locator, Page } from '@playwright/test';

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

  test('스텝 설정 패널의 닫기 버튼은 「스텝 설정 패널 닫기」 이름으로 불리고 누르면 패널이 닫힌다 (WD-917)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);
    await page.goto('/pipelines/new');

    await clickToolbarAddStep(page);
    const header = page.getByText(/^스텝 #1:/);
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
  const stepPanel = (page: Page) => page.getByText(/^스텝 #1:/).locator("xpath=../following-sibling::*[1]");
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
});
