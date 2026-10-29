/**
 * 파이프라인 편집기 — 스텝 연결(의존) 끊기 (WD-346)
 *
 * 과거에는 엣지를 매 렌더 새로 만들면서 onEdgesChange 를 주지 않아 연결선이 선택되지 않았고,
 * 그래서 Backspace·Delete 도, 연결선 위 삭제 수단도 없어 한 번 이은 연결을 끊을 방법이 없었다.
 * 이 스펙은 마우스 선택·키보드 삭제·스텝 설정 패널의 「연결 끊기」 세 경로로 연결이 끊기고,
 * 저장 payload 의 dependsOnStepNames 에서도 빠지는지 검증한다.
 *
 * 픽스처: 「데이터 추출」 → 「데이터 변환」 연결 1개(createPipelineDetail 기본값).
 */
import type { Page } from '@playwright/test';

import { mockApi, type MockApiCapture } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

type SavedPipeline = { steps: Array<{ name: string; dependsOnStepNames: string[] }> };

/** PUT 저장 요청을 잡는다 — setupPipelineEditorMocks 보다 먼저 등록해야 라우트가 앞선다 */
function capturePipelineSave(page: Page) {
  return mockApi(
    page,
    'PUT',
    '/api/v1/pipelines/1',
    { id: 1, name: '테스트 파이프라인', description: '', isActive: true, steps: [], createdBy: 'test', createdAt: '', updatedBy: null, updatedAt: null },
    { capture: true },
  );
}

/** 편집 모드로 들어가 연결선이 그려질 때까지 기다린다 */
async function openEditor(page: Page) {
  await page.goto('/pipelines/1');
  await page.getByRole('button', { name: '수정' }).click();
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
}

/** 저장 후 「데이터 변환」 스텝의 의존 목록이 비었는지 확인한다 */
async function expectSavedWithoutDependency(page: Page, captured: MockApiCapture) {
  const saved = captured.waitForRequest();
  await page.getByRole('button', { name: '저장' }).click();
  const transform = ((await saved).payload as SavedPipeline).steps.find((s) => s.name === '데이터 변환');
  expect(transform?.dependsOnStepNames).toEqual([]);
}

test.describe('파이프라인 편집기 — 연결 끊기 (WD-346)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('연결선을 마우스로 누르면 선택되고 「연결 끊기」로 연결이 사라진다', async ({ authenticatedPage: page }) => {
    const captured = await capturePipelineSave(page);
    await setupPipelineEditorMocks(page, 1);
    await openEditor(page);

    // 가운데에는 「스텝 삽입」 버튼이 있으므로, 선의 1/4 지점을 화면 좌표로 구해 누른다
    const point = await page.locator('.react-flow__edge-path').first().evaluate((el: SVGPathElement) => {
      const p = el.getPointAtLength(el.getTotalLength() * 0.25);
      const m = el.getScreenCTM()!;
      return { x: p.x * m.a + p.y * m.c + m.e, y: p.x * m.b + p.y * m.d + m.f };
    });
    await page.mouse.click(point.x, point.y);

    await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
    await page.getByRole('button', { name: '연결 끊기' }).click();

    await expect(page.locator('.react-flow__edge')).toHaveCount(0);
    await expectSavedWithoutDependency(page, captured);
  });

  test('키보드로 연결선을 선택하고 Delete 를 누르면 연결이 사라진다', async ({ authenticatedPage: page }) => {
    const captured = await capturePipelineSave(page);
    await setupPipelineEditorMocks(page, 1);
    await openEditor(page);

    // 연결선은 포커스할 수 있다 — Enter 로 선택, Delete 로 삭제
    await page.locator('.react-flow__edge').first().focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
    await page.keyboard.press('Delete');

    await expect(page.locator('.react-flow__edge')).toHaveCount(0);
    await expectSavedWithoutDependency(page, captured);
  });

  test('스텝 설정 패널의 의존성 목록에서 「연결 끊기」로 끊을 수 있다', async ({ authenticatedPage: page }) => {
    const captured = await capturePipelineSave(page);
    await setupPipelineEditorMocks(page, 1);
    await openEditor(page);

    await page.locator('.react-flow__node').filter({ hasText: '데이터 변환' }).click();
    const deps = page.getByRole('group', { name: '의존성' });
    await expect(deps).toContainText('데이터 추출');
    await deps.getByRole('button', { name: '데이터 추출 연결 끊기' }).click();

    await expect(deps).toContainText('(없음)');
    await expect(page.locator('.react-flow__edge')).toHaveCount(0);
    await expectSavedWithoutDependency(page, captured);
  });

  test('보기 모드에서는 연결선이 선택되지 않고 끊기 버튼도 없다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);

    await page.locator('.react-flow__node').filter({ hasText: '데이터 변환' }).click();
    await expect(page.getByRole('group', { name: '의존성' })).toContainText('데이터 추출');
    await expect(page.getByRole('button', { name: /연결 끊기/ })).toHaveCount(0);

    // 연결선을 골라 Delete 를 눌러도 선택·삭제되지 않는다
    await page.locator('.react-flow__edge').first().focus();
    await page.keyboard.press('Enter');
    await page.keyboard.press('Delete');
    await expect(page.locator('.react-flow__edge.selected')).toHaveCount(0);
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  });

  test('연결을 끊은 뒤 같은 두 스텝을 다시 이어도 새 연결선은 선택되지 않은 채 남는다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    await openEditor(page);

    await page.locator('.react-flow__edge').first().focus();
    await page.keyboard.press('Enter');
    await page.keyboard.press('Delete');
    await expect(page.locator('.react-flow__edge')).toHaveCount(0);

    // 「데이터 추출」 오른쪽 점 → 「데이터 변환」 왼쪽 점으로 다시 잇는다
    const node = (name: string) => page.locator('.react-flow__node').filter({ has: page.getByText(name, { exact: true }) });
    const source = node('데이터 추출').locator('.react-flow__handle.source');
    const target = node('데이터 변환').locator('.react-flow__handle.target');
    await source.dragTo(target);
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);
    await expect(page.locator('.react-flow__edge.selected')).toHaveCount(0);

    // 옛 선택이 살아 있지 않으므로 캔버스에서 Delete 를 눌러도 새 연결은 그대로다
    await page.locator('.react-flow__pane').focus();
    await page.keyboard.press('Delete');
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  });

  test('설정 패널의 버튼에 포커스가 있을 때 Backspace 는 선택된 연결선을 지우지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    await openEditor(page);

    // 스텝을 열어 설정 패널을 띄운 뒤 연결선을 고른다
    await page.locator('.react-flow__node').filter({ hasText: '데이터 변환' }).click();
    await page.locator('.react-flow__edge').first().focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);

    // 패널(캔버스 밖)의 버튼으로 포커스를 옮겨 Backspace
    await page.getByRole('button', { name: '데이터 추출 연결 끊기' }).focus();
    await page.keyboard.press('Backspace');
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  });
});
