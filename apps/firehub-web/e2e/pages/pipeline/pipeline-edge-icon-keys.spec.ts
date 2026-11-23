import type { Page } from '@playwright/test';

import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/**
 * 연결선 가운데 아이콘 버튼(「+」·「연결 끊기」) 키보드 회귀 (WD-1009).
 * 버튼에서 누른 Enter/Space 가 EdgeWrapper.onKeyDown 까지 번져 다른 연결선 선택을 바꾸던 문제를 막는다.
 */

// 3 스텝 체인: 추출 -> 변환 -> 적재 (연결선 2개)
async function openEditor(page: Page) {
  await setupPipelineEditorMocks(page, 1);
  await mockApi(
    page,
    'GET',
    '/api/v1/pipelines/1',
    createPipelineDetail({
      steps: [
        createStep(),
        createStep({ id: 2, name: '데이터 변환', stepOrder: 1, dependsOnStepNames: ['데이터 추출'] }),
        createStep({ id: 3, name: '데이터 적재', stepOrder: 2, dependsOnStepNames: ['데이터 변환'] }),
      ],
    }),
  );
  await page.goto('/pipelines/1');
  await page.getByRole('button', { name: '수정' }).click();
  await expect(page.locator('.react-flow__edge')).toHaveCount(2);
}

const edge = (page: Page, i: number) => page.locator('.react-flow__edge').nth(i);
const plus = (page: Page, i: number) => edge(page, i).getByRole('button', { name: '스텝 삽입' });
const cut = (page: Page, i: number) => edge(page, i).getByRole('button', { name: '연결 끊기' });

// xyflow 기본 multiSelectionKeyCode: 맥은 Meta, 그 밖은 Control
async function multiKey(page: Page): Promise<'Meta' | 'Control'> {
  const isMac = await page.evaluate(() => /Mac|iPhone|iPad/i.test(navigator.userAgent));
  return isMac ? 'Meta' : 'Control';
}

// 다중 선택 키를 누른 채 연결선 i 를 클릭해 선택에 더한다.
// 맥 호스트에서 Control+마우스는 click 대신 contextmenu 가 되므로 click 이벤트를 직접 보낸다
// (xyflow 의 다중 선택 상태는 키 눌림만 보고, 이벤트의 ctrlKey 는 보지 않는다).
async function addEdgeToSelection(page: Page, i: number) {
  const key = await multiKey(page);
  await page.keyboard.down(key);
  await edge(page, i).locator('.react-flow__edge-path').dispatchEvent('click');
  await page.keyboard.up(key);
}

// 연결선을 키보드(Enter)로 선택한다
async function selectEdge(page: Page, i: number) {
  await edge(page, i).focus();
  await page.keyboard.press('Enter');
  await expect(edge(page, i)).toHaveClass(/selected/);
}

/**
 * 키의 브라우저 기본 동작(버튼 click 합성)만 막는다 — 전파는 막지 않는다.
 * 삽입·끊기가 일어나면 선택이 비워져 최종 상태로 전/후를 가를 수 없으므로, 동작을 없애고 선택 처리만 남긴다.
 * Enter 는 keydown, Space 는 keyup 에서 click 이 합성된다 — 두 단계 모두 막는다(Space keydown 의 스크롤도 함께 막힌다).
 */
async function blockDefault(page: Page, key: 'Enter' | 'Space') {
  await page.evaluate((k) => {
    const target = k === 'Enter' ? 'Enter' : ' ';
    for (const type of ['keydown', 'keyup']) {
      document.addEventListener(
        type,
        (e) => {
          if ((e as KeyboardEvent).key === target) e.preventDefault();
        },
        true,
      );
    }
  }, key);
}

test.describe('연결선 아이콘 버튼 키보드 (WD-1009)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  for (const key of ['Enter', 'Space'] as const) {
    test(`「+」 ${key} 가 다른 연결선 선택을 바꾸지 않는다`, async ({ authenticatedPage: page }) => {
      await openEditor(page);
      await selectEdge(page, 1);
      await blockDefault(page, key);
      await plus(page, 0).focus();
      await page.keyboard.press(key);

      await expect(edge(page, 1)).toHaveClass(/selected/);
      await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
      await expect(page.locator('.react-flow__node')).toHaveCount(3);
    });

    test(`「연결 끊기」 ${key} 도 선택 처리로 번지지 않는다`, async ({ authenticatedPage: page }) => {
      await openEditor(page);
      await selectEdge(page, 0);
      // 끊기 버튼은 자기 연결선이 선택돼야 보인다. 키가 번져도 edge 0 선택은 같으므로,
      // 번지면 풀려 버리는 두 번째 선택(edge 1)을 다중 선택 키 클릭으로 더해 둔다.
      await addEdgeToSelection(page, 1);
      await expect(edge(page, 1)).toHaveClass(/selected/);
      await expect(edge(page, 0)).toHaveClass(/selected/);
      await blockDefault(page, key);
      await cut(page, 0).focus();
      await page.keyboard.press(key);

      await expect(edge(page, 0)).toHaveClass(/selected/);
      await expect(edge(page, 1)).toHaveClass(/selected/);
      await expect(page.locator('.react-flow__edge')).toHaveCount(2);
    });
  }

  test('실제 Enter 는 스텝을 삽입한다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await plus(page, 0).focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('.react-flow__node')).toHaveCount(4);
  });

  test('실제 Space 는 연결을 끊는다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await selectEdge(page, 0);
    await cut(page, 0).focus();
    await page.keyboard.press('Space');
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  });

  test('연결선 자체 Enter 는 그대로 선택한다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await edge(page, 0).focus();
    await page.keyboard.press('Enter');
    await expect(edge(page, 0)).toHaveClass(/selected/);
  });

  test('「+」 Escape 는 다른 선택을 바꾸지 않는다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await selectEdge(page, 1);
    await plus(page, 0).focus();
    await page.keyboard.press('Escape');
    await expect(edge(page, 1)).toHaveClass(/selected/);
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
  });

  test('「연결 끊기」 Escape 는 그 연결선의 선택을 푼다', async ({ authenticatedPage: page }) => {
    await openEditor(page);
    await selectEdge(page, 0);
    await cut(page, 0).focus();
    await page.keyboard.press('Escape');
    await expect(edge(page, 0)).not.toHaveClass(/selected/);
    await expect(page.locator('.react-flow__edge')).toHaveCount(2);
  });
});
