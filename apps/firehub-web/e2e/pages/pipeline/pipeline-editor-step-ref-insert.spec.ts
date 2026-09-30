/**
 * 파이프라인 스텝 편집기 — `스텝 참조:` 버튼으로 {{#N}} 을 삽입한 뒤 이어서 타이핑하는 E2E (#743)
 *
 * 과거 ScriptEditor 의 insertText 는 changes 만 dispatch 하고 selection 을 지정하지 않아,
 * CodeMirror 가 커서를 삽입 앞에 매핑했다. 그래서 참조 삽입 직후 타이핑한 글자가 {{#N}} 앞에 들어갔다.
 * 이 테스트는 실제 CodeMirror 에 "타이핑 → 버튼 클릭 → 타이핑" 후 저장 payload 로 최종 스크립트를 검증한다.
 */

import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('파이프라인 스텝 편집기 — 스텝 참조 삽입 후 커서 위치 (#743)', () => {
  test('참조 버튼으로 {{#1}} 을 넣고 이어서 입력하면 글자가 참조 뒤에 붙는다', async ({
    authenticatedPage: page,
  }) => {
    // sql1 → sql_new 의존 관계 — sql_new 편집 시 sql1 참조 버튼이 노출된다
    const sql1 = createStep({ id: 1, name: 'sql1', scriptContent: 'SELECT code FROM src', stepOrder: 0 });
    const sqlNew = createStep({
      id: 2,
      name: 'sql_new',
      scriptContent: 'SELECT * FROM t',
      outputDatasetId: 2,
      dependsOnStepNames: ['sql1'],
      stepOrder: 1,
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [sql1, sqlNew] }));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
    });
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 'sql_new' }).click();

    const editor = page.locator('.cm-content');
    await expect(editor).toHaveAttribute('contenteditable', 'true');

    // 스크립트 끝으로 이동해 서브쿼리 앞부분을 입력
    await editor.click();
    await page.keyboard.press('ControlOrMeta+End');
    await page.keyboard.type(' WHERE code IN (SELECT code FROM ');

    // 참조 버튼 클릭 후 곧바로 이어서 타이핑 — 포커스·커서가 삽입 끝에 있어야 한다
    await page.getByRole('button', { name: /\{\{#1\}\} sql1/ }).click();
    await page.keyboard.type(')');

    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await putCapture.waitForRequest();
    const payload = req.payload as { steps: Array<{ name: string; scriptContent: string }> };
    const saved = payload.steps.find((s) => s.name === 'sql_new');
    expect(saved?.scriptContent).toBe('SELECT * FROM t WHERE code IN (SELECT code FROM {{#1}})');
  });

  test('보기 모드에서는 참조 버튼이 없고, 수정·입력·저장 payload 에 유령 {{#1}} 이 섞이지 않는다 (#751)', async ({
    authenticatedPage: page,
  }) => {
    // sql1 → py_new 의존 관계 — 과거엔 보기 모드에서도 sql1 참조 버튼이 노출·동작해
    // 읽기 전용 에디터 화면에만 {{#1}} 이 들어가고, 수정 후 저장하면 그대로 저장됐다(PYTHON 은 조용히 204).
    const sql1 = createStep({ id: 1, name: 'sql1', scriptContent: 'SELECT 1 AS code', stepOrder: 0 });
    const pyNew = createStep({
      id: 2,
      name: 'py_new',
      scriptType: 'PYTHON',
      scriptContent: 'x = 1',
      outputDatasetId: 2,
      dependsOnStepNames: ['sql1'],
      stepOrder: 1,
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [sql1, pyNew] }));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
    });
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });

    // 1) 보기 모드에서 py_new 선택 — 에디터는 읽기 전용, 참조 버튼(편집 전용 컨트롤)은 없어야 한다
    await page.goto('/pipelines/1');
    await page.locator('.react-flow__node', { hasText: 'py_new' }).click();
    const editor = page.locator('.cm-content');
    await expect(editor).toHaveAttribute('contenteditable', 'false');
    await expect(editor).toHaveText('x = 1');
    const refButton = page.getByRole('button', { name: /\{\{#1\}\} sql1/ });
    await expect(refButton).toHaveCount(0);

    // 2) 수정 모드로 전환하면 참조 버튼이 나타나고, 에디터 문서는 원본 그대로여야 한다
    await page.getByRole('button', { name: '수정' }).click();
    await expect(editor).toHaveAttribute('contenteditable', 'true');
    await expect(refButton).toBeVisible();
    await expect(editor).toHaveText('x = 1');

    // 3) 끝에 입력 후 저장 — payload 스크립트에 의도하지 않은 {{#1}} 이 없어야 한다
    await editor.click();
    await page.keyboard.press('ControlOrMeta+End');
    await page.keyboard.type('  # ok');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const req = await putCapture.waitForRequest();
    const payload = req.payload as { steps: Array<{ name: string; scriptContent: string }> };
    const saved = payload.steps.find((s) => s.name === 'py_new');
    expect(saved?.scriptContent).toBe('x = 1  # ok');
  });
});
