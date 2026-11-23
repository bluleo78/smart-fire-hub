/**
 * 파이프라인 편집기 — 스텝 삭제 확인 창이 캔버스 이벤트 트리 밖에 있는지 검증 (WD-952)
 *
 * 확인 창이 StepNode 안에 있으면 포털로 DOM 은 밖에 그려져도, React 합성 이벤트는 React 트리를 따라
 * React Flow 의 노드 래퍼와 PipelineCanvas 의 onKeyDown div 로 올라간다. 그래서 창 안에서 누른
 * Backspace·Delete 가 선택된 연결선을 지우고, 배경 클릭이 스텝을 선택하는 사고가 있었다.
 * 창을 캔버스 div 의 형제로 옮긴 뒤에는 이 이벤트들이 캔버스에 닿지 않아야 한다.
 *
 * 픽스처: 「데이터 추출」 → 「데이터 변환」 연결 1개(createPipelineDetail 기본값).
 */
import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/** 편집 모드로 들어가 연결선이 그려질 때까지 기다린다 */
async function openEditor(page: Page) {
  await setupPipelineEditorMocks(page, 1);
  await page.goto('/pipelines/1');
  await page.getByRole('button', { name: '수정' }).click();
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
}

/** 연결선을 키보드로 선택한다 — 포커스 후 Enter */
async function selectEdge(page: Page) {
  await page.locator('.react-flow__edge').first().focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
}

/** 지정한 스텝 노드(기본 「데이터 변환」)의 X 버튼으로 삭제 확인 창을 연다 */
async function openDeleteDialog(page: Page, stepName = '데이터 변환') {
  await stepXButton(page, stepName).click();
  await expect(page.getByRole('alertdialog')).toBeVisible();
}

/**
 * 이름으로 스텝 노드를 찾는다. hasText 부분 일치는 「SQL로 원본 데이터 추출」 같은 설명에도 걸려
 * 노드 둘이 잡히므로, 이름 요소를 exact 로 가진 노드만 고른다.
 */
function stepNode(page: Page, stepName: string) {
  return page.locator('.react-flow__node').filter({ has: page.getByText(stepName, { exact: true }) });
}

/** 스텝 노드 안의 X(스텝 삭제) 버튼 */
function stepXButton(page: Page, stepName: string) {
  return stepNode(page, stepName).getByRole('button', { name: '스텝 삭제' });
}

/** 확인 창 안의 버튼 */
function dialogButton(page: Page, name: '취소' | '삭제') {
  return page.getByRole('alertdialog').getByRole('button', { name });
}

/**
 * 창 안의 키·배경 클릭이 캔버스에 닿지 않았음을 단언한다.
 * 「없음」 단언은 이벤트가 처리되기 전에도 통과하므로, 창을 마우스로 닫은 뒤 같은 값을 한 번 더 확인해
 * 이벤트가 처리될 시간이 지난 뒤에도 그대로임을 보장한다.
 */
async function expectCanvasUntouched(page: Page) {
  const check = async () => {
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);
    await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);
    await expect(page.locator('#step-name')).toHaveCount(0);
  };
  await expect(page.getByRole('alertdialog')).toBeVisible();
  await check();
  await dialogButton(page, '취소').click();
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  await check();
}

/** 순서를 시간이 아니라 약속으로 정하기 위한 문 — release() 를 부를 때까지 promise 가 기다린다 */
function createGate() {
  let release!: () => void;
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

test.describe('파이프라인 편집기 — 스텝 삭제 확인 창 (WD-952)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  for (const key of ['Backspace', 'Delete']) {
    test(`창이 열린 채 ${key} 를 눌러도 선택된 연결선은 지워지지 않는다`, async ({ authenticatedPage: page }) => {
      await openEditor(page);
      await selectEdge(page);
      await openDeleteDialog(page);
      // 초점이 「취소」에 있어야 키가 창 안에서 눌린 것이다 — 다른 곳이면 이 시험이 캔버스 키 처리를 보지 못한다
      await expect(dialogButton(page, '취소')).toBeFocused();

      await page.keyboard.press(key);
      await expectCanvasUntouched(page);
    });
  }

  test('창 뒤 배경을 눌러도 스텝이 선택되지 않고 연결선 선택도 유지된다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await selectEdge(page);
    await openDeleteDialog(page);

    // 창 밖(왼쪽 위 모서리) 좌표 — 배경(overlay)을 누른다
    await page.mouse.click(5, 5);
    await expectCanvasUntouched(page);
  });

  test('창 뒤 배경을 더블클릭해도 스텝이 선택되지 않고 연결선 선택도 유지된다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await selectEdge(page);
    await openDeleteDialog(page);

    await page.mouse.dblclick(5, 5);
    await expectCanvasUntouched(page);
  });

  // 키보드로 창을 닫으면 캔버스는 그대로이고 초점은 창을 연 X 버튼으로 돌아와야 한다(WCAG 2.4.3)
  for (const key of ['Enter', 'Space', 'Escape']) {
    test(`${key} 로 창을 닫으면 캔버스는 그대로이고 초점이 X 버튼으로 돌아온다`, async ({ authenticatedPage: page }) => {
      await openEditor(page);
      await selectEdge(page);
      await openDeleteDialog(page);

      // Enter·Space 는 초점이 있는 「취소」를 누르고, Escape 는 창 자체를 닫는다
      await page.keyboard.press(key);

      await expect(page.getByRole('alertdialog')).toHaveCount(0);
      await expect(page.locator('.react-flow__node')).toHaveCount(2);
      await expect(page.locator('.react-flow__edge')).toHaveCount(1);
      await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
      await expect(stepXButton(page, '데이터 변환')).toBeFocused();
    });
  }

  test('Tab → Enter 로 삭제하면 스텝이 지워지고 초점이 body 로 떨어지지 않는다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await openDeleteDialog(page);

    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');

    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.locator('.react-flow__node')).toHaveCount(1);
    await expect(page.locator('.react-flow__node').first()).toContainText('데이터 추출');
    await expect
      .poll(() => page.evaluate(() => document.activeElement?.tagName))
      .not.toBe('BODY');
  });

  test('다른 스텝이 선택된 채 다른 스텝의 창 뒤 배경을 눌러도 선택된 스텝이 바뀌지 않는다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await stepNode(page, '데이터 추출').click();
    await expect(page.locator('#step-name')).toHaveValue('데이터 추출');

    await openDeleteDialog(page);
    // AlertDialog 는 배경 클릭으로 닫히지 않는다 — 창은 그대로이고 편집 패널의 선택도 그대로여야 한다
    await page.mouse.click(5, 5);
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.locator('#step-name')).toHaveValue('데이터 추출');

    // 이벤트가 처리될 시간이 지난 뒤에도 같은지 창을 닫고 한 번 더 확인한다
    await dialogButton(page, '취소').click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.locator('#step-name')).toHaveValue('데이터 추출');
  });

  test('취소한 뒤 다른 스텝을 삭제하면 처음 눌렀던 스텝이 아니라 새로 누른 스텝이 지워진다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await openDeleteDialog(page, '데이터 변환');
    await dialogButton(page, '취소').click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    await openDeleteDialog(page, '데이터 추출');
    await dialogButton(page, '삭제').click();

    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.locator('.react-flow__node')).toHaveCount(1);
    await expect(page.locator('.react-flow__node').first()).toContainText('데이터 변환');
  });

  test('스텝을 모두 지운 뒤 다시 추가해도 확인 창이 남지 않는다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    for (const name of ['데이터 변환', '데이터 추출']) {
      await openDeleteDialog(page, name);
      await dialogButton(page, '삭제').click();
      await expect(page.getByRole('alertdialog')).toHaveCount(0);
    }
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).toBeVisible();

    await page.getByRole('button', { name: '스텝 추가' }).click();

    await expect(page.locator('.react-flow__node')).toHaveCount(1);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });

  // 저장 중에는 편집이 잠겨(WD-953) 노드 X 가 없으므로 삭제 창을 열 수 없다 — 열린 채 저장이 끝나는 경주 자체가 사라졌다
  test('저장이 도는 동안에는 스텝 삭제 버튼이 없어 삭제 창을 열 수 없다 (WD-953)', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    const putStarted = createGate();
    const releasePut = createGate();
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1',
      async (route) => {
        if (route.request().method() !== 'PUT') return route.fallback();
        putStarted.release();
        await releasePut.promise;
        return route.fulfill({ json: { updatedAt: '2025-01-01T00:00:00Z' } });
      },
    );
    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(2);
    await page.locator('header input').first().fill('바뀐 이름');
    // 저장 전에 노드 X 가 있었음을 먼저 보아 '없음' 단언이 헛통과하지 않게 한다
    await page.locator('.react-flow__node').first().hover();
    await expect(page.getByRole('button', { name: '스텝 삭제' }).first()).toBeVisible();
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await putStarted.promise;

    await expect(page.getByRole('button', { name: '스텝 삭제' })).toHaveCount(0);
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    releasePut.release();
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
  });
});

// X 버튼에서 누른 Enter·Space 가 React Flow NodeWrapper 의 키 선택 처리로 번지면 노드가 선택되며 연결선 선택이 풀린다
test.describe('파이프라인 편집기 — 스텝 X 버튼 키보드 (WD-955)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  for (const key of ['Enter', 'Space']) {
    test(`X 버튼에서 ${key} 를 눌러도 선택된 연결선이 유지된다 (WD-955)`, async ({ authenticatedPage: page }) => {
      await openEditor(page);
      await selectEdge(page);
      await stepXButton(page, '데이터 변환').focus();
      await page.keyboard.press(key);
      // 확인 창이 열리되 keydown·click 이 둘 다 처리돼 두 번 열리지 않았는지 확인
      await expect(page.getByRole('alertdialog')).toHaveCount(1);
      await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
      await dialogButton(page, '취소').click();
      await expect(page.getByRole('alertdialog')).toHaveCount(0);
      await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
    });
  }

  test('X 에서 Enter 로 연 창을 Escape 로 닫아도 연결선 선택이 유지되고 초점이 X 로 돌아온다 (WD-955)', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await selectEdge(page);
    await stepXButton(page, '데이터 변환').focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
    await expect(stepXButton(page, '데이터 변환')).toBeFocused();
  });

  // 회귀 경계: 노드 자체의 키 선택 처리는 막지 않았음을 확인한다
  test('스텝 노드 자체에서 Enter 를 누르면 React Flow 키 선택 처리가 그대로 돌아 연결선 선택이 풀린다 (WD-955 회귀)', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await selectEdge(page);
    await stepNode(page, '데이터 변환').focus();
    await page.keyboard.press('Enter');
    // nodes 가 제어 모드(onNodesChange 없음)라 노드에 .selected 클래스가 붙지 않는다.
    // 대신 React Flow 의 키 선택 처리가 살아 있음을 「다른 선택(연결선)이 해제됨」으로 본다 —
    // X 버튼에서만 이 전파를 막았으므로 노드 자체에서는 여전히 해제돼야 한다.
    await expect(page.locator('.react-flow__edge.selected')).toHaveCount(0);
  });
});
