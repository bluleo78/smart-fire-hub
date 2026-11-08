/**
 * 파이프라인 편집기 — 스텝 검증 오류 접근성 E2E (WD-560, WCAG 3.3.1 · 4.1.3)
 *
 * 빈 스텝으로 저장하면 「스텝 이름을 입력하세요」/「스크립트를 입력하세요」가 빨갛게 뜨지만
 * 입력에 aria-invalid/aria-describedby 가 없고 오류 문구가 안내(role="alert")되지 않았으며,
 * 포커스가 저장 버튼에 남아 키보드·스크린리더 사용자가 무엇을 고쳐야 하는지 알 수 없었다.
 * 저장 실패 후 오류 필드 연결·안내·첫 오류 필드로의 포커스 이동을 검증한다.
 */

import type { Locator, Page } from '@playwright/test';

import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { clickToolbarAddStep, grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

/** 신규 편집기 진입 모킹 — 데이터셋 목록만 있으면 된다 */
async function setupNewEditorMocks(page: Page) {
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
  });
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

    // 이름: aria-invalid + aria-describedby → role="alert" 오류 문구
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    const nameError = await describedByTarget(page, nameInput);
    await expect(nameError).toHaveText('스텝 이름을 입력하세요');
    await expect(nameError).toHaveAttribute('role', 'alert');

    // 스크립트는 포커스를 받는 실제 입력 요소(textbox)에 연결된다 — role=group 래퍼에는 달지 않는다(ARIA 1.2 미지원)
    const editor = scriptEditor(page);
    await expect(editor).toHaveAttribute('aria-invalid', 'true');
    await expect(scriptGroup(page)).not.toHaveAttribute('aria-invalid');
    const scriptError = await describedByTarget(page, editor);
    await expect(scriptError).toHaveText('스크립트를 입력하세요');
    await expect(scriptError).toHaveAttribute('role', 'alert');

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
    await expect(scriptError).toHaveAttribute('role', 'alert');

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
    await expect(await describedByTarget(page, nameInput)).toHaveAttribute('role', 'alert');
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
});
