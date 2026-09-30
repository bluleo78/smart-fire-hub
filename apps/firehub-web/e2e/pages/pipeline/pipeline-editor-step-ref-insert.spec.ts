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
});
