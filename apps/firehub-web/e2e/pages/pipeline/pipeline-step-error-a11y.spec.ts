/**
 * 파이프라인 편집기 — 스텝 검증 오류 접근성 E2E (WD-560, WCAG 3.3.1 · 4.1.3)
 *
 * 빈 스텝으로 저장하면 「스텝 이름을 입력하세요」/「스크립트를 입력하세요」가 빨갛게 뜨지만
 * 입력에 aria-invalid/aria-describedby 가 없고 오류 문구가 안내(role="alert")되지 않았으며,
 * 포커스가 저장 버튼에 남아 키보드·스크린리더 사용자가 무엇을 고쳐야 하는지 알 수 없었다.
 * 저장 실패 후 오류 필드 연결·안내·첫 오류 필드로의 포커스 이동을 검증한다.
 *
 * WD-633: 하위 설정 칸(API 호출 URL·경로·페이지 크기, AI 분류 프롬프트·출력 컬럼)도 같은 방식으로 연결·포커스한다.
 * 칸별 오류 문구는 role="alert" 가 아니다(스텝 재선택 때 다시 읽히지 않게). 저장 실패는 토스트(polite) 한 번과 첫 오류 칸 포커스가 알리고,
 * 입력 중에 생기는 오류만 미리 그려 둔 polite 영역에 뜬다.
 */

import type { Locator, Page } from '@playwright/test';

import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { clickToolbarAddStep, grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

/** 신규 편집기 진입 모킹 — 데이터셋 목록과(API 호출 스텝용) 선택 가능한 연결 목록 */
async function setupNewEditorMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
  });
  await mockApi(page, 'GET', '/api/v1/api-connections/selectable', []);
}

/** 신규 편집기를 열고 파이프라인 이름을 채운 뒤 스텝 하나를 추가한다(스텝 이름·스크립트는 비어 있다) */
async function openNewEditorWithBlankStep(page: Page) {
  await setupNewEditorMocks(page);
  await page.goto('/pipelines/new');
  await page.getByPlaceholder('파이프라인 이름').fill('접근성 파이프라인');
  await clickToolbarAddStep(page);
  await expect(page.locator('#step-name')).toBeVisible({ timeout: 10000 });
}

/** 스크립트 편집기(CodeMirror)에 입력한다 */
async function typeScript(page: Page, text: string) {
  await page.locator('.cm-content').click();
  await page.keyboard.type(text);
}

/**
 * 편집기 실행 취소/다시 실행 키의 수정자 키(Mod)를 CodeMirror 와 같은 기준으로 고른다.
 * CodeMirror 는 navigator.platform 으로 Mod 를 정하는데, Desktop Chrome 디바이스 에뮬레이션은 Windows 플랫폼을 흉내 내
 * macOS 호스트에서도 Mod=Ctrl 이 된다. 'ControlOrMeta' 는 호스트 OS 기준(Meta)이라 실행 취소가 아예 일어나지 않아
 * 되돌리기 회귀 테스트가 헛돌았다 — 페이지의 플랫폼을 읽어 같은 키를 누른다.
 */
async function editorModKey(page: Page): Promise<'Meta' | 'Control'> {
  return page.evaluate(() => (/Mac/.test(navigator.platform) ? 'Meta' : 'Control'));
}

/** CodeMirror historyKeymap 의 다시 실행 키 — mac 은 Mod-Shift-z, 그 외는 Mod-y */
async function editorRedoKey(page: Page): Promise<string> {
  return (await editorModKey(page)) === 'Meta' ? 'Meta+Shift+z' : 'Control+y';
}

/** 컨트롤의 aria-describedby 가 가리키는 요소를 돌려준다 — 정확히 하나의 id 를 가리킨다고 가정한다 */
async function describedByTarget(page: Page, control: Locator): Promise<Locator> {
  const ids = await control.getAttribute('aria-describedby');
  expect(ids).toBeTruthy();
  return page.locator(`[id="${ids}"]`);
}

const saveButton = (page: Page) => page.getByRole('button', { name: '저장', exact: true });
/** 저장 실패 토스트 문구 */
const SAVE_FAILED_TOAST = '입력 오류를 확인하세요.';

/**
 * 요소를 감싼 가장 가까운 라이브 영역의 종류 — role(alert·status·log) 또는 aria-live 값(polite·assertive), 없으면 null(WD-633).
 * aria-live="off" 는 읽지 않으므로 뺀다.
 */
async function liveRegionKind(locator: Locator): Promise<string | null> {
  return locator.evaluate((el) => {
    const region = el.closest('[role="alert"], [role="status"], [role="log"], [aria-live]:not([aria-live="off"])');
    if (!region) return null;
    const role = region.getAttribute('role');
    if (role) return role;
    return region.getAttribute('aria-live');
  });
}

/** 라이브 영역 안에 있는지 */
async function isInLiveRegion(locator: Locator): Promise<boolean> {
  return (await liveRegionKind(locator)) !== null;
}

/** 지금 선택된 스텝의 이름을 채우고 스텝 유형을 고른다 */
async function nameAndTypeStep(page: Page, stepName: string, typeLabel: 'API 호출' | 'AI 분류') {
  await page.locator('#step-name').fill(stepName);
  await page.getByRole('combobox', { name: '스크립트 타입' }).click();
  await page.getByRole('option', { name: typeLabel, exact: true }).click();
}

/** 새 편집기에서 이름 있는 스텝 하나를 추가하고 스텝 유형을 고른다(WD-633) */
async function openNewEditorWithTypedStep(page: Page, stepName: string, typeLabel: 'API 호출' | 'AI 분류') {
  await openNewEditorWithBlankStep(page);
  await nameAndTypeStep(page, stepName, typeLabel);
}

/** API 호출 스텝의 페이지네이션 절을 펴고 유형을 Offset 으로, 페이지 크기를 value 로 둔다 */
async function setOffsetPageSize(page: Page, value: string) {
  await page.getByRole('button', { name: '페이지네이션' }).click();
  await page.getByRole('combobox', { name: '유형', exact: true }).click();
  await page.getByRole('option', { name: 'Offset' }).click();
  await page.getByLabel('페이지 크기').fill(value);
}

/** 저장을 눌러 실패 토스트가 한 번만, polite 라이브 영역 안에 뜨는지 확인한다 — 저장 시도의 유일한 낭독 안내(WD-633) */
async function saveAndExpectFailureAnnouncedOnce(page: Page) {
  await saveButton(page).click();
  const toastText = page.getByText(SAVE_FAILED_TOAST);
  await expect(toastText).toHaveCount(1);
  expect(await isInLiveRegion(toastText)).toBe(true);
}

const urlInput = (page: Page) => page.getByPlaceholder('https://api.example.com/v1/data');
const promptInput = (page: Page) => page.getByRole('textbox', { name: '프롬프트' });
const PAGE_SIZE_MESSAGE = '페이지 크기는 1~10000 사이의 정수여야 합니다';
const scriptGroup = (page: Page) => page.getByRole('group', { name: '스크립트' });
/** 스크립트 편집기의 실제 입력 요소(CodeMirror .cm-content, role=textbox) — 포커스·오류 속성이 여기에 있어야 한다 */
const scriptEditor = (page: Page) => scriptGroup(page).getByRole('textbox');

test.describe('파이프라인 스텝 검증 오류 접근성 (WD-560)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('이름·스크립트 모두 비면 두 필드가 오류로 연결·안내되고 포커스는 이름 입력으로 간다', async ({
    authenticatedPage: page,
  }) => {
    await openNewEditorWithBlankStep(page);
    const nameInput = page.locator('#step-name');

    // 저장 전에는 오류 표시가 없다
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).not.toHaveAttribute('aria-describedby', /.+/);

    await saveButton(page).click();
    await expect(page.getByText('입력 오류를 확인하세요.')).toBeVisible();

    // 이름: aria-invalid + aria-describedby → 오류 문구(재선택 때 다시 읽히지 않게 role="alert" 없음 — WD-633)
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    const nameError = await describedByTarget(page, nameInput);
    await expect(nameError).toHaveText('스텝 이름을 입력하세요');
    await expect(nameError).not.toHaveAttribute('role', 'alert');

    // 스크립트는 포커스를 받는 실제 입력 요소(textbox)에 연결된다 — role=group 래퍼에는 달지 않는다(ARIA 1.2 미지원)
    const editor = scriptEditor(page);
    await expect(editor).toHaveAttribute('aria-invalid', 'true');
    await expect(scriptGroup(page)).not.toHaveAttribute('aria-invalid');
    const scriptError = await describedByTarget(page, editor);
    await expect(scriptError).toHaveText('스크립트를 입력하세요');
    await expect(scriptError).not.toHaveAttribute('role', 'alert');

    // 포커스는 저장 버튼이 아니라 첫 오류 필드(이름)로 옮겨진다
    await expect(nameInput).toBeFocused();

    // 이름을 입력하면 해당 스텝 오류가 지워져 aria-invalid·aria-describedby 가 사라진다
    await nameInput.fill('추출');
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).not.toHaveAttribute('aria-describedby', /.+/);
    await expect(page.getByText('스텝 이름을 입력하세요')).toHaveCount(0);
  });

  test('스크립트만 비면 스크립트 래퍼만 오류로 표시되고 포커스는 스크립트 편집기로 간다', async ({
    authenticatedPage: page,
  }) => {
    await openNewEditorWithBlankStep(page);
    const nameInput = page.locator('#step-name');
    await nameInput.fill('추출');

    await saveButton(page).click();
    await expect(page.getByText('입력 오류를 확인하세요.')).toBeVisible();

    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
    const editor = scriptEditor(page);
    await expect(editor).toHaveAttribute('aria-invalid', 'true');
    const scriptError = await describedByTarget(page, editor);
    await expect(scriptError).toHaveText('스크립트를 입력하세요');
    await expect(scriptError).not.toHaveAttribute('role', 'alert');

    // 오류 속성이 달린 그 입력 요소에 포커스가 간다
    await expect(editor).toBeFocused();

    // 스크립트를 입력하면 오류 연결이 풀린다
    await typeScript(page, 'SELECT 1');
    await expect(editor).not.toHaveAttribute('aria-invalid', 'true');
    await expect(editor).not.toHaveAttribute('aria-describedby', /.+/);
  });

  test('이름만 비면 이름 입력만 오류로 표시되고 포커스는 이름 입력으로 간다', async ({
    authenticatedPage: page,
  }) => {
    await openNewEditorWithBlankStep(page);
    await typeScript(page, 'SELECT 1');

    await saveButton(page).click();
    await expect(page.getByText('입력 오류를 확인하세요.')).toBeVisible();

    const nameInput = page.locator('#step-name');
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(await describedByTarget(page, nameInput)).toHaveText('스텝 이름을 입력하세요');
    await expect(scriptEditor(page)).not.toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).toBeFocused();
  });

  test('스크립트 칸이 없는 API 호출 스텝도 빈 이름이 오류로 연결되고 포커스를 받는다', async ({
    authenticatedPage: page,
  }) => {
    await openNewEditorWithBlankStep(page);
    // 스텝 유형을 API 호출로 바꾼다 — 스크립트 칸이 사라진다
    await page.getByRole('combobox', { name: '스크립트 타입' }).click();
    await page.getByRole('option', { name: /API/ }).first().click();
    await expect(scriptGroup(page)).toHaveCount(0);

    await saveButton(page).click();
    await expect(page.getByText('입력 오류를 확인하세요.')).toBeVisible();

    const nameInput = page.locator('#step-name');
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(await describedByTarget(page, nameInput)).toHaveText('스텝 이름을 입력하세요');
    await expect(nameInput).toBeFocused();
  });

  test('오류 스텝이 선택돼 있지 않으면 그 스텝을 선택하고 첫 오류 필드로 포커스한다', async ({
    authenticatedPage: page,
  }) => {
    await openNewEditorWithBlankStep(page);
    // 1번 스텝은 비워 두고, 2번 스텝을 추가해 올바르게 채운다 — 저장 시점 선택은 2번이다
    await clickToolbarAddStep(page);
    await expect(page.getByText('스텝 #2')).toBeVisible();
    await page.locator('#step-name').fill('정상 스텝');
    await typeScript(page, 'SELECT 1');

    await saveButton(page).click();
    await expect(page.getByText('입력 오류를 확인하세요.')).toBeVisible();

    // 오류가 있는 1번 스텝 패널로 바뀌고 이름 입력에 포커스가 간다
    await expect(page.getByText('스텝 #1')).toBeVisible();
    const nameInput = page.locator('#step-name');
    await expect(nameInput).toHaveValue('');
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).toBeFocused();
  });

  test('기존 파이프라인에서 스텝을 열지 않고 저장해도 오류 스텝의 스크립트 편집기로 포커스한다', async ({
    authenticatedPage: page,
  }) => {
    // 스크립트가 빈 스텝 하나 — 스텝을 선택하지 않은 채 저장하므로 스크립트 편집기 청크가 아직 로드되지 않았을 수 있다
    const step = createStep({ id: 5, scriptContent: '' });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [step] }));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    // 파이프라인 이름만 바꿔 저장 버튼을 활성화한다
    await page.getByPlaceholder('파이프라인 이름').fill('이름 변경');
    await saveButton(page).click();
    await expect(page.getByText('입력 오류를 확인하세요.')).toBeVisible();

    await expect(page.getByText('스텝 #1')).toBeVisible();
    const editor = scriptEditor(page);
    await expect(editor).toHaveAttribute('aria-invalid', 'true');
    await expect(editor).toBeFocused();
  });

  test('스텝을 바꾼 직후 실행 취소해도 이전 스텝 스크립트가 새 스텝에 들어가지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 편집기는 스텝을 바꿔도 다시 만들어지지 않고 값만 동기화한다 — 그 동기화가 실행 취소 기록에 들어가면
    // Ctrl/Cmd+Z 가 B 편집기에 A 스크립트를 되돌려 넣고 B 의 scriptContent 로 저장된다.
    const stepA = createStep({ id: 1, name: '스텝A', scriptContent: 'SELECT a', stepOrder: 0 });
    const stepB = createStep({ id: 2, name: '스텝B', scriptContent: 'SELECT b', stepOrder: 1 });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [stepA, stepB] }));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await setupNewEditorMocks(page);
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();

    const editor = page.locator('.cm-content');
    await page.locator('.react-flow__node').filter({ hasText: '스텝A' }).click();
    await expect(editor).toHaveText('SELECT a');
    // A 에서 사용자 편집을 하나 남긴다 — 실행 취소 기록이 있어야 동기화가 함께 되돌려지는 문제가 드러난다
    await editor.click();
    await page.keyboard.press(`${await editorModKey(page)}+End`);
    await page.keyboard.type(' X');
    await expect(editor).toHaveText('SELECT a X');
    await page.locator('.react-flow__node').filter({ hasText: '스텝B' }).click();
    await expect(editor).toHaveText('SELECT b');

    // B 편집기에서 실행 취소 — B 에는 되돌릴 사용자 편집이 없으므로 A 의 스크립트가 들어오면 안 된다
    await editor.click();
    await page.keyboard.press(`${await editorModKey(page)}+z`);
    await expect(editor).toHaveText('SELECT b');

    // 저장 payload 에도 B 의 스크립트가 그대로 실린다
    await page.getByPlaceholder('파이프라인 이름').fill('이름 변경');
    await saveButton(page).click();
    const req = await putCapture.waitForRequest();
    const payload = req.payload as { steps: Array<{ name: string; scriptContent: string }> };
    expect(payload.steps.find((s) => s.name === '스텝B')?.scriptContent).toBe('SELECT b');
    expect(payload.steps.find((s) => s.name === '스텝A')?.scriptContent).toBe('SELECT a X');
  });

  test('A 에서 지운 뒤 B 를 선택하고 실행 취소해도 지운 글자가 B 에 들어가지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 동기화를 실행 취소 기록에서 빼도 A 의 기록은 남는다 — 삭제의 역연산(삽입)은 동기화 교체를 거쳐 한 점으로 매핑돼
    // B 스크립트에 끼어들고, externalSync 표시가 없어 UPDATE_STEP 으로 B 에 저장됐다. 동기화 때 기록을 비워야 막힌다.
    const stepA = createStep({ id: 1, name: '스텝A', scriptContent: 'SELECT a', stepOrder: 0 });
    const stepB = createStep({ id: 2, name: '스텝B', scriptContent: 'SELECT b', stepOrder: 1 });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [stepA, stepB] }));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await setupNewEditorMocks(page);
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();

    const editor = page.locator('.cm-content');
    await page.locator('.react-flow__node').filter({ hasText: '스텝A' }).click();
    await expect(editor).toHaveText('SELECT a');
    // A 에서 끝 글자 하나를 지운다 — 되돌릴 삭제 기록을 남긴다
    await editor.click();
    await page.keyboard.press(`${await editorModKey(page)}+End`);
    await page.keyboard.press('Backspace');
    await expect(editor).toHaveText('SELECT ');
    // 실행 취소 키가 실제로 동작하는지 먼저 확인한다 — 키가 안 먹으면 아래 B 검증이 헛돈다
    const mod = await editorModKey(page);
    await page.keyboard.press(`${mod}+z`);
    await expect(editor).toHaveText('SELECT a');
    await page.keyboard.press(await editorRedoKey(page));
    await expect(editor).toHaveText('SELECT ');

    await page.locator('.react-flow__node').filter({ hasText: '스텝B' }).click();
    await expect(editor).toHaveText('SELECT b');

    // B 에는 되돌릴 사용자 편집이 없다 — 실행 취소·다시 실행 모두 B 를 바꾸지 않아야 한다
    await editor.click();
    await page.keyboard.press(`${mod}+z`);
    await expect(editor).toHaveText('SELECT b');
    await page.keyboard.press(await editorRedoKey(page));
    await expect(editor).toHaveText('SELECT b');

    // A 로 돌아가도 A 의 편집 결과는 그대로다
    await page.locator('.react-flow__node').filter({ hasText: '스텝A' }).click();
    await expect(editor).toHaveText('SELECT ');

    // 저장 payload — B 는 원본 그대로, A 는 삭제가 반영된 값
    await page.getByPlaceholder('파이프라인 이름').fill('이름 변경');
    await saveButton(page).click();
    const req = await putCapture.waitForRequest();
    const payload = req.payload as { steps: Array<{ name: string; scriptContent: string }> };
    expect(payload.steps.find((s) => s.name === '스텝B')?.scriptContent).toBe('SELECT b');
    expect(payload.steps.find((s) => s.name === '스텝A')?.scriptContent).toBe('SELECT ');
  });

  // ===== WD-633: 하위 설정 칸 =====

  test('API 호출 URL 이 비면 URL 칸이 오류로 연결되고 포커스를 받는다 (WD-633)', async ({ authenticatedPage: page }) => {
    await openNewEditorWithTypedStep(page, 'API 스텝', 'API 호출');
    const url = urlInput(page);
    await expect(url).not.toHaveAttribute('aria-invalid', 'true');
    await expect(url).not.toHaveAttribute('aria-describedby', /.+/);

    await saveAndExpectFailureAnnouncedOnce(page);

    await expect(url).toHaveAttribute('aria-invalid', 'true');
    const urlError = await describedByTarget(page, url);
    await expect(urlError).toHaveText('URL을 입력하세요');
    expect(await isInLiveRegion(urlError)).toBe(false);
    await expect(url).toBeFocused();

    // URL 을 채우면 연결이 풀린다
    await url.fill('https://api.example.com/v1/data');
    await expect(url).not.toHaveAttribute('aria-invalid', 'true');
    await expect(url).not.toHaveAttribute('aria-describedby', /.+/);
    await expect(page.getByText('URL을 입력하세요')).toHaveCount(0);
  });

  test('API 호출 페이지 크기가 범위 밖이면 접힌 페이지네이션 절을 펴고 그 칸으로 포커스한다 — URL 도 비면 화면 순서상 URL 이 먼저 (WD-633)', async ({
    authenticatedPage: page,
  }) => {
    await openNewEditorWithTypedStep(page, 'API 스텝', 'API 호출');
    // 페이지네이션 Offset(올바른 값으로 먼저 둔다)
    await setOffsetPageSize(page, '100');
    const paginationToggle = page.getByRole('button', { name: '페이지네이션' });
    const pageSize = page.getByLabel('페이지 크기');
    // 입력 중 범위 오류는 polite 라이브 영역에 뜬다 — 영역은 오류가 생기기 전부터 비어 있는 채로 있어야 문구가 생길 때 읽힌다
    const sizeRegion = pageSize.locator('..').locator('[aria-live="polite"]');
    await expect(sizeRegion).toHaveCount(1);
    await expect(sizeRegion).toHaveText('');
    await pageSize.fill('0');
    await expect(sizeRegion).toHaveText(PAGE_SIZE_MESSAGE);
    await expect(sizeRegion).not.toHaveAttribute('role', 'alert');

    // 1) URL 도 비어 있다 — 화면 위쪽인 URL 로 포커스
    await saveAndExpectFailureAnnouncedOnce(page);
    await expect(urlInput(page)).toBeFocused();
    await expect(pageSize).toHaveAttribute('aria-invalid', 'true');

    // 2) URL 을 채우고 페이지네이션 절을 접은 채 다시 저장 — 절이 다시 펴지고 페이지 크기 칸으로 포커스
    await urlInput(page).fill('https://api.example.com/v1/data');
    await paginationToggle.click();
    await expect(page.getByLabel('페이지 크기')).toHaveCount(0);
    await saveButton(page).click();
    await expect(pageSize).toBeVisible();
    await expect(pageSize).toBeFocused();
    await expect(pageSize).toHaveAttribute('aria-invalid', 'true');
    const sizeError = await describedByTarget(page, pageSize);
    await expect(sizeError).toHaveText(PAGE_SIZE_MESSAGE);
    expect(await liveRegionKind(sizeError)).toBe('polite');
  });

  test('저장된 연결을 찾을 수 없는 API 호출 스텝도 빈 추가 경로 칸을 보이고 그 칸으로 포커스한다 (WD-633)', async ({
    authenticatedPage: page,
  }) => {
    // 연결 99 는 선택 목록에 없다(삭제됐거나 선택할 수 없는 연결) — 경로 칸은 그대로 그려져야 한다
    const step = createStep({
      id: 9,
      name: 'API 스텝',
      scriptType: 'API_CALL',
      scriptContent: '',
      apiConnectionId: 99,
      apiConfig: { method: 'GET', dataPath: '$' },
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [step] }));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByPlaceholder('파이프라인 이름').fill('이름 변경');
    await saveAndExpectFailureAnnouncedOnce(page);

    await expect(page.getByText(/^스텝 #1:/)).toBeVisible();
    const pathInput = page.getByLabel('추가 경로');
    await expect(pathInput).toBeFocused();
    await expect(pathInput).toHaveAttribute('aria-invalid', 'true');
    const pathError = await describedByTarget(page, pathInput);
    await expect(pathError).toHaveText('URL을 입력하세요');
    expect(await liveRegionKind(pathError)).toBeNull();
  });

  test('AI 분류 프롬프트·출력 컬럼이 비면 각 칸이 오류로 연결되고 화면 순서대로 포커스한다 (WD-633)', async ({
    authenticatedPage: page,
  }) => {
    await openNewEditorWithTypedStep(page, 'AI 스텝', 'AI 분류');
    const prompt = promptInput(page);
    const addColumn = page.getByRole('button', { name: '컬럼 추가', exact: true });
    await expect(prompt).not.toHaveAttribute('aria-invalid', 'true');

    // 1) 둘 다 비면 위쪽 프롬프트로 포커스, 두 칸 모두 오류 문구와 연결
    await saveAndExpectFailureAnnouncedOnce(page);
    await expect(prompt).toHaveAttribute('aria-invalid', 'true');
    const promptError = await describedByTarget(page, prompt);
    await expect(promptError).toHaveText('프롬프트를 입력하세요');
    expect(await isInLiveRegion(promptError)).toBe(false);
    const columnsError = await describedByTarget(page, addColumn);
    await expect(columnsError).toHaveText('출력 컬럼을 최소 1개 정의하세요');
    expect(await isInLiveRegion(columnsError)).toBe(false);
    await expect(prompt).toBeFocused();

    // 2) 프롬프트만 채우고 다시 저장 — 고칠 방법인 「컬럼 추가」 버튼으로 포커스
    await prompt.fill('감성을 분류하세요');
    await expect(prompt).not.toHaveAttribute('aria-invalid', 'true');
    await saveButton(page).click();
    await expect(page.getByText('출력 컬럼을 최소 1개 정의하세요')).toBeVisible();
    await expect(addColumn).toBeFocused();

    // 3) 컬럼을 추가하고 이름을 비운 채 저장 — 그 행의 이름 칸으로 포커스
    await addColumn.click();
    await saveButton(page).click();
    const colName = page.getByLabel('출력 컬럼명 1');
    await expect(colName).toHaveAttribute('aria-invalid', 'true');
    await expect(await describedByTarget(page, colName)).toHaveText('출력 컬럼 이름을 입력하세요');
    await expect(colName).toBeFocused();
  });

  test('오류가 있는 스텝을 다시 골라도 칸별 오류 문구가 라이브 영역이 아니어서 다시 읽히지 않는다 (WD-633)', async ({
    authenticatedPage: page,
  }) => {
    // 1번: URL 빈 API 호출 + 페이지 크기 범위 밖 / 2번: 프롬프트·출력 컬럼 빈 AI 분류 / 3번: 이름·스크립트 빈 SQL
    await openNewEditorWithTypedStep(page, 'API 스텝', 'API 호출');
    await setOffsetPageSize(page, '-5');
    await clickToolbarAddStep(page);
    await expect(page.getByText(/^스텝 #2:/)).toBeVisible();
    await nameAndTypeStep(page, 'AI 스텝', 'AI 분류');
    await clickToolbarAddStep(page);
    await expect(page.getByText(/^스텝 #3:/)).toBeVisible();

    // 선택된 3번에 오류가 있으므로 3번에 머물고 이름 칸으로 포커스
    await saveAndExpectFailureAnnouncedOnce(page);
    await expect(page.locator('#step-name')).toBeFocused();

    const node = (name: string) => page.locator('.react-flow__node').filter({ hasText: name });
    /** 지금 패널에 보이는 오류 문구들이 모두 라이브 영역 밖인지, 패널에 role=alert 가 하나도 없는지 확인한다 */
    async function expectErrorsNotLive(messages: string[]) {
      for (const message of messages) {
        const el = page.getByText(message, { exact: true });
        await expect(el).toBeVisible();
        expect(await isInLiveRegion(el)).toBe(false);
      }
      await expect(page.getByRole('alert')).toHaveCount(0);
    }

    // 재선택이 저장 실패 안내를 새로 띄우지 않는지 보려고, 저장 때 뜬 토스트가 사라진 뒤 다시 고른다
    await expect(page.getByText(SAVE_FAILED_TOAST)).toHaveCount(0, { timeout: 15000 });

    // 1번 재선택 — 접혀 있던 페이지네이션 절은 오류가 있어 펴진 채로 다시 그려진다
    await node('API 스텝').click();
    await expect(page.getByText(/^스텝 #1:/)).toBeVisible();
    await expectErrorsNotLive(['URL을 입력하세요']);
    // 페이지 크기 범위 오류는 입력 중에 읽히도록 polite 영역에 있다 — 이미 내용이 든 채 새로 마운트된 영역은 읽지 않는다(alert 아님)
    const sizeError = page.getByText(PAGE_SIZE_MESSAGE, { exact: true });
    await expect(sizeError).toBeVisible();
    expect(await liveRegionKind(sizeError)).toBe('polite');
    // 저장하지 않은 재선택은 포커스를 옮기지 않는다
    await expect(urlInput(page)).not.toBeFocused();

    // 2번 재선택
    await node('AI 스텝').click();
    await expect(page.getByText(/^스텝 #2:/)).toBeVisible();
    await expectErrorsNotLive(['프롬프트를 입력하세요', '출력 컬럼을 최소 1개 정의하세요']);

    // 3번 재선택 — WD-560 의 이름·스크립트 오류도 라이브 영역이 아니다
    await page.locator('.react-flow__node').nth(2).click();
    await expect(page.getByText(/^스텝 #3:/)).toBeVisible();
    await expectErrorsNotLive(['스텝 이름을 입력하세요', '스크립트를 입력하세요']);

    // 재선택은 저장 실패 안내(토스트)를 새로 띄우지 않는다
    await expect(page.getByText(SAVE_FAILED_TOAST)).toHaveCount(0);
  });
});
