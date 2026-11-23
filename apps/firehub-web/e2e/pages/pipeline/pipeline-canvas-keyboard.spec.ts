/**
 * 파이프라인 캔버스 — 노드 키보드 조작 (WD-1046)
 *
 * 노드에 초점을 두고 Enter/Space 를 누르면 마우스 클릭과 같이 스텝 설정(수정 모드)·
 * 실행 결과(보기 모드) 패널이 열려야 한다. xyflow 는 Enter/Space 에서 내부 선택만 하고
 * onNodeClick 을 부르지 않아 키보드 사용자가 스텝 설정에 닿지 못했다.
 * 설정을 연 스텝은 화살표(5px)·Shift+화살표(20px)로 옮기고 한국어 이동 알림이 나온다.
 */
import type { Locator, Page } from '@playwright/test';

import { createExecutionDetail, createStepExecution } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/** 이름으로 스텝 노드를 찾는다 */
function stepNode(page: Page, name: string) {
  return page.locator('.react-flow__node').filter({ hasText: name });
}

/** 노드의 현재 좌표 — style.transform 의 translate(Xpx, Ypx) 를 읽는다 */
async function nodeXY(node: Locator) {
  const transform = (await node.evaluate((el) => (el as HTMLElement).style.transform)) ?? '';
  const m = /translate\((-?[\d.]+)px,\s*(-?[\d.]+)px\)/.exec(transform);
  if (!m) throw new Error(`translate 를 읽지 못함: ${transform}`);
  return { x: Number(m[1]), y: Number(m[2]) };
}

/** 픽스처 파이프라인(「데이터 추출」→「데이터 변환」)을 수정 모드로 연다 */
async function gotoEditMode(page: Page) {
  await page.goto('/pipelines/1');
  await page.getByRole('button', { name: '수정' }).click();
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
}

test.describe('파이프라인 캔버스 — 노드 Enter/Space (WD-1046)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
    await setupPipelineEditorMocks(page, 1);
  });

  test('수정 모드에서 노드에 초점을 두고 Enter 를 누르면 그 스텝 설정 패널이 열린다', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);
    await expect(page.locator('#step-name')).toHaveCount(0);

    await stepNode(page, '데이터 변환').focus();
    await page.keyboard.press('Enter');

    await expect(page.locator('#step-name')).toHaveValue('데이터 변환');
  });

  test('Space 로도 열리고 페이지가 스크롤되지 않는다', async ({ authenticatedPage: page }) => {
    // 문서가 실제로 스크롤 가능해야 scrollY 비교가 의미 있다 — 뷰포트를 낮춰 먼저 확인한다
    await page.setViewportSize({ width: 1280, height: 400 });
    await gotoEditMode(page);
    await page.evaluate(() => {
      const spacer = document.createElement('div');
      spacer.style.height = '2000px';
      document.body.appendChild(spacer);
    });
    expect(
      await page.evaluate(() => document.scrollingElement!.scrollHeight > window.innerHeight),
    ).toBe(true);
    const before = await page.evaluate(() => window.scrollY);

    await stepNode(page, '데이터 변환').focus();
    await page.keyboard.press('Space');

    await expect(page.locator('#step-name')).toHaveValue('데이터 변환');
    expect(await page.evaluate(() => window.scrollY)).toBe(before);
  });

  test('보기 모드에서 Enter 를 누르면 실행 결과 패널이 열린다', async ({ authenticatedPage: page }) => {
    // 실행 결과 패널은 실행 상세(보기 모드)에서만 있다 — 마우스 클릭 때와 같이 「스텝: <이름>」 상세가 열린다
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines/1/executions/1',
      createExecutionDetail({
        id: 1,
        pipelineId: 1,
        status: 'COMPLETED',
        stepExecutions: [
          createStepExecution({ id: 1, stepName: '데이터 추출', status: 'COMPLETED', outputRows: 100 }),
          createStepExecution({ id: 2, stepId: 2, stepName: '데이터 변환', status: 'COMPLETED', outputRows: 95 }),
        ],
      }),
    );
    await page.goto('/pipelines/1/executions/1');
    await expect(page.locator('.react-flow__node')).toHaveCount(2);
    await expect(page.getByText('실행 정보')).toBeVisible();
    await expect(page.getByText('스텝: 데이터 변환')).toHaveCount(0);

    await stepNode(page, '데이터 변환').focus();
    await page.keyboard.press('Enter');

    await expect(page.getByText('스텝: 데이터 변환')).toBeVisible();
  });

  test('개요 보기 모드에서 Enter 를 누르면 읽기 전용 스텝 정보가 열린다', async ({ authenticatedPage: page }) => {
    // 「수정」 을 누르지 않은 개요 — 마우스로 노드를 누를 때와 같이 읽기 전용 설정 패널이 그 스텝을 보여 준다
    await page.goto('/pipelines/1');
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);
    await expect(page.locator('#step-name')).toHaveCount(0);

    await stepNode(page, '데이터 변환').focus();
    await page.keyboard.press('Enter');

    await expect(page.locator('#step-name')).toHaveValue('데이터 변환');
    await expect(page.locator('#step-name')).toBeDisabled();
  });

  test('노드 안 「다음 스텝 추가」 버튼에서 Enter 는 패널을 열지 않고 스텝만 추가한다', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);
    await expect(page.locator('.react-flow__node')).toHaveCount(2);

    await stepNode(page, '데이터 변환').getByRole('button', { name: '다음 스텝 추가' }).focus();
    await page.keyboard.press('Enter');

    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    // 새 스텝이 선택되어 패널이 열려도 그 패널은 「데이터 변환」 의 것이 아니다
    await expect(page.locator('#step-name')).not.toHaveValue('데이터 변환');
  });

  test('설정을 연 스텝은 화살표로 5px, Shift+화살표로 20px 움직이고 알림이 한국어로 나온다', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);
    const node = stepNode(page, '데이터 변환');
    await node.focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('#step-name')).toHaveValue('데이터 변환');
    const p0 = await nodeXY(node);

    await page.keyboard.press('ArrowRight');
    await expect.poll(() => nodeXY(node)).toEqual({ x: p0.x + 5, y: p0.y });
    await expect(page.getByTestId('canvas-move-announcement')).toHaveText(
      `「데이터 변환」 스텝을 오른쪽(으)로 옮겼습니다 (가로 ${Math.round(p0.x + 5)}, 세로 ${Math.round(p0.y)})`,
    );

    await page.keyboard.press('Shift+ArrowDown');
    await expect.poll(() => nodeXY(node)).toEqual({ x: p0.x + 5, y: p0.y + 20 });
    await expect(page.getByTestId('canvas-move-announcement')).toContainText('아래(으)로');
  });

  test('이름 없는 스텝을 옮기면 「스텝 #3」 으로 알린다', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);
    // 「데이터 변환」 뒤에 이름 없는 새 스텝(3번째)을 추가한다 — 추가된 스텝이 선택된다
    await stepNode(page, '데이터 변환').getByRole('button', { name: '다음 스텝 추가' }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    const third = page.locator('.react-flow__node').nth(2);

    await third.focus();
    await page.keyboard.press('Enter');
    await page.keyboard.press('ArrowRight');

    await expect(page.getByTestId('canvas-move-announcement')).toHaveText(
      /^「스텝 #3」 스텝을 오른쪽\(으\)로 옮겼습니다 \(가로 -?\d+, 세로 -?\d+\)$/,
    );
  });

  test('옮긴 위치는 다른 노드를 고른 뒤에도 유지된다', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);
    const node = stepNode(page, '데이터 변환');
    await node.focus();
    await page.keyboard.press('Enter');
    const p0 = await nodeXY(node);
    await page.keyboard.press('ArrowRight');
    await expect.poll(() => nodeXY(node)).toEqual({ x: p0.x + 5, y: p0.y });

    // 「데이터 변환」 노드 설명에도 「데이터 추출」 이 들어 있어 이름 필터 대신 첫 노드(데이터 추출)를 쓴다
    await page.locator('.react-flow__node').first().click();
    await expect(page.locator('#step-name')).toHaveValue('데이터 추출');

    expect(await nodeXY(node)).toEqual({ x: p0.x + 5, y: p0.y });
  });

  test('설정 패널 입력칸에서 화살표를 눌러도 노드는 움직이지 않는다', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);
    const node = stepNode(page, '데이터 변환');
    await node.focus();
    await page.keyboard.press('Enter');
    const p0 = await nodeXY(node);

    await page.locator('#step-name').click();
    await page.keyboard.press('ArrowLeft');

    expect(await nodeXY(node)).toEqual(p0);
    await expect(page.getByTestId('canvas-move-announcement')).toBeEmpty();
  });

  test('보기 모드에서는 화살표로 움직이지 않는다', async ({ authenticatedPage: page }) => {
    await page.goto('/pipelines/1');
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);
    const node = stepNode(page, '데이터 변환');
    const p0 = await nodeXY(node);

    await node.focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('#step-name')).toHaveValue('데이터 변환');
    await page.keyboard.press('ArrowRight');

    expect(await nodeXY(node)).toEqual(p0);
    await expect(page.getByTestId('canvas-move-announcement')).toBeEmpty();
  });

  test('설정을 연 스텝이 있을 때 다른 스텝을 끌면 끈 스텝만 움직인다', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);
    const first = page.locator('.react-flow__node').nth(0);
    const second = page.locator('.react-flow__node').nth(1);
    // 첫 노드를 눌러 설정을 연다 — 그 스텝이 선택 상태가 된다
    await first.click();
    await expect(page.locator('#step-name')).toHaveValue('데이터 추출');
    const a0 = await nodeXY(first);
    const b0 = await nodeXY(second);

    const box = (await second.getByText('데이터 변환').first().boundingBox())!;
    const sx = box.x + box.width / 2;
    const sy = box.y + box.height / 2;
    await page.mouse.move(sx, sy);
    await page.mouse.down();
    await page.mouse.move(sx + 60, sy + 40, { steps: 5 });
    await page.mouse.up();

    expect((await nodeXY(second)).x - b0.x).toBeGreaterThan(40);
    expect(await nodeXY(first)).toEqual(a0);
    // 다른 상태 변경(빈 곳 클릭) 뒤에도 첫 노드는 그대로
    await page.locator('.react-flow__pane').click({ position: { x: 5, y: 5 } });
    expect(await nodeXY(first)).toEqual(a0);
  });

  test('마우스로 끌면 위치가 남고 이동 알림은 없다', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);
    const node = stepNode(page, '데이터 변환');
    const p0 = await nodeXY(node);
    const box = (await node.getByText('데이터 변환').first().boundingBox())!;
    const sx = box.x + box.width / 2;
    const sy = box.y + box.height / 2;

    await page.mouse.move(sx, sy);
    await page.mouse.down();
    await page.mouse.move(sx + 60, sy + 40, { steps: 5 });
    await page.mouse.up();

    const p1 = await nodeXY(node);
    // 정확한 거리는 줌과, 누르는 순간 설정 패널이 열려 캔버스 폭이 줄어드는 영향을 받아 값을 못 박지 않는다 —
    // 끈 방향으로 눈에 띄게 움직여 남았는지를 본다
    expect(p1.x - p0.x).toBeGreaterThan(40);
    expect(p1.y - p0.y).toBeGreaterThan(25);
    await page.locator('.react-flow__node').first().click();
    expect(await nodeXY(node)).toEqual(p1);
    await expect(page.getByTestId('canvas-move-announcement')).toBeEmpty();
  });
});
