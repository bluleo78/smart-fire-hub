/**
 * 파이프라인 상세 — 보기 모드에서 스텝 패널을 연 뒤 `수정`으로 전환했을 때 스크립트 입력 반영 E2E (#740)
 *
 * ScriptEditor(CodeMirror)는 한 번만 마운트되고 readOnly 는 compartment 재구성으로 바뀐다.
 * 과거에는 updateListener 가 마운트 시점의 readOnly=true 를 클로저로 붙잡아,
 * 수정 모드로 바꾼 뒤 타이핑해도 onChange 가 호출되지 않고 저장 시 옛 스크립트가 나갔다.
 * 이 테스트는 실제 CodeMirror 에 타이핑해 "입력 → 편집 상태 → 저장 payload" 전체 경로를 검증한다.
 */

import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 파이프라인 상세 화면 진입에 필요한 API 를 모킹한다 — 스텝을 직접 지정한다. */
async function setupMocks(page: import('@playwright/test').Page, step: ReturnType<typeof createStep>) {
  await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [step] }));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
  });
}

test.describe('파이프라인 상세 — 스텝 패널을 연 뒤 수정 모드로 전환한 스크립트 편집 (#740)', () => {
  for (const scriptType of ['SQL', 'PYTHON'] as const) {
    test(`${scriptType} 스텝: 보기 모드에서 패널을 열고 수정으로 바꿔 입력한 스크립트가 저장 payload 에 담긴다`, async ({
      authenticatedPage: page,
    }) => {
      const original = scriptType === 'SQL' ? 'SELECT code FROM src' : 'print("hi")';
      const step = createStep({ id: 5, scriptType, scriptContent: original });
      await setupMocks(page, step);
      const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });

      await page.goto('/pipelines/1');

      // 1) 보기 모드에서 먼저 스텝을 선택 — ScriptEditor 가 readOnly=true 로 마운트된다
      await page.locator('.react-flow__node').first().click();
      const editor = page.locator('.cm-content');
      await expect(editor).toHaveAttribute('contenteditable', 'false');

      // 2) 패널을 연 채로 수정 모드 전환 — 에디터는 재마운트되지 않고 편집 가능해진다
      await page.getByRole('button', { name: '수정' }).click();
      await expect(editor).toHaveAttribute('contenteditable', 'true');

      // 3) 실제 CodeMirror 에 타이핑
      const saveButton = page.getByRole('button', { name: '저장', exact: true });
      await expect(saveButton).toBeDisabled();
      await editor.click();
      await page.keyboard.press('ControlOrMeta+End');
      await page.keyboard.type(' -- edited');

      // 편집 상태에 반영되면 저장 버튼이 활성화된다(isDirty)
      await expect(saveButton).toBeEnabled();

      // 4) 저장 payload 에 입력한 스크립트가 실제로 담겨야 한다(조용한 유실 방지)
      await saveButton.click();
      const req = await putCapture.waitForRequest();
      const payload = req.payload as { steps: Array<{ scriptContent: string }> };
      expect(payload.steps[0].scriptContent).toBe(`${original} -- edited`);
    });
  }
});
