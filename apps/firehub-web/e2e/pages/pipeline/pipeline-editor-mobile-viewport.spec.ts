/**
 * 파이프라인 에디터 모바일 뷰포트 회귀 테스트 (refs #22)
 * - 375px(모바일) 뷰포트에서 ReactFlow 캔버스가 width=0px로 붕괴하는 버그 회귀 방지
 * - 데스크톱(1280px) 뷰포트에서는 기존 가로 레이아웃 유지 확인
 */

import type { Locator, Page } from '@playwright/test';

import { createExecutionDetail, createStepExecution } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/** 버튼이 뷰포트 가로 안에 온전히 들어와 있는지 확인한다(WD-342 — 머리글이 넘쳐 오른쪽 밖으로 잘리던 회귀 방지). */
async function expectInsideViewport(page: Page, button: Locator) {
  await expect(button).toBeVisible();
  const box = await button.boundingBox();
  const width = page.viewportSize()!.width;
  expect(box, '버튼 좌표').not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(width);
}

test.describe('파이프라인 에디터 — 반응형 레이아웃 (refs #22)', () => {
  /**
   * 모바일 375px 뷰포트: 캔버스가 width > 0으로 표시되어야 한다.
   * 버그 조건: StepConfigPanel 고정 너비(400px)가 반응형 처리 없이 캔버스를 압착.
   */
  test('모바일(375px) 뷰포트에서 ReactFlow 캔버스가 width > 0으로 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    // 모바일 뷰포트 설정
    await page.setViewportSize({ width: 375, height: 812 });

    // 파이프라인 에디터 API 모킹
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    // ReactFlow 캔버스 컨테이너 로드 대기
    const reactFlowEl = page.locator('[class*="react-flow"]').first();
    await expect(reactFlowEl).toBeVisible();

    // 핵심 검증: 캔버스 너비가 0이어서는 안 된다 (버그 재현 조건: clientWidth = 0)
    const canvasWidth = await reactFlowEl.evaluate((el) => (el as HTMLElement).clientWidth);
    expect(canvasWidth).toBeGreaterThan(0);
  });

  /**
   * 데스크톱 1280px 뷰포트: 기존 가로 배치 레이아웃 유지 확인.
   * lg breakpoint(1024px) 이상에서 flex-row로 동작해야 한다.
   */
  test('데스크톱(1280px) 뷰포트에서 ReactFlow 캔버스가 width > 0으로 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    // 데스크톱 뷰포트 설정
    await page.setViewportSize({ width: 1280, height: 800 });

    // 파이프라인 에디터 API 모킹
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    // ReactFlow 캔버스 컨테이너 로드 대기
    const reactFlowEl = page.locator('[class*="react-flow"]').first();
    await expect(reactFlowEl).toBeVisible();

    // 데스크톱에서도 캔버스 너비 > 0이어야 한다 (기존 기능 회귀 방지)
    const canvasWidth = await reactFlowEl.evaluate((el) => (el as HTMLElement).clientWidth);
    expect(canvasWidth).toBeGreaterThan(0);
  });

  /**
   * WD-342: 휴대폰 폭(375·390·430)에서 머리글 「실행」·「수정」, 수정 모드의 「취소」·「저장」이 화면 안에 있어야 한다.
   * 예전엔 머리글이 한 줄 고정·이름 칸 최소 200px 이라 버튼이 x≈398 부터 놓여 바깥 overflow-hidden 에 잘렸다.
   */
  for (const width of [375, 390, 430]) {
    test(`모바일(${width}px) — 보기·수정 모드의 머리글 버튼이 화면 안에 있고 수정 모드로 들어갈 수 있다 (WD-342)`, async ({
      authenticatedPage: page,
    }) => {
      await page.setViewportSize({ width, height: 667 });
      // 「실행」·「수정」은 pipeline:execute·write 가 있어야 보인다(WD-219)
      await grantPipelinePermissions(page);
      await setupPipelineEditorMocks(page, 1);
      await page.goto('/pipelines/1');

      await expectInsideViewport(page, page.getByRole('button', { name: '실행', exact: true }));
      const editButton = page.getByRole('button', { name: '수정', exact: true });
      await expectInsideViewport(page, editButton);

      // 화면 안의 「수정」을 실제로 눌러 수정 모드로 들어갈 수 있어야 한다
      await editButton.click();
      await expectInsideViewport(page, page.getByRole('button', { name: '취소', exact: true }));
      await expectInsideViewport(page, page.getByRole('button', { name: '저장', exact: true }));
    });
  }

  test('모바일(390px) — 새 파이프라인의 「취소」·「저장」이 화면 안에 있다 (WD-342)', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await grantPipelinePermissions(page);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
    });
    await page.goto('/pipelines/new');

    await expectInsideViewport(page, page.getByRole('button', { name: '취소', exact: true }));
    await expectInsideViewport(page, page.getByRole('button', { name: '저장', exact: true }));
  });

  test('데스크톱(1280px) — 머리글 버튼은 글자와 함께 보인다 (WD-342 회귀 방지)', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await grantPipelinePermissions(page);
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    // 글자 span 이 sr-only 가 아니어서 버튼 폭이 아이콘 하나(약 40px)보다 넓어야 한다
    const editButton = page.getByRole('button', { name: '수정', exact: true });
    await expect(editButton).toBeVisible();
    const box = await editButton.boundingBox();
    expect(box!.width).toBeGreaterThan(56);
  });
});

/**
 * WD-309: 실행 상세(모바일) — 고정 400px 「실행 정보」 패널이 390px 화면을 다 차지해 DAG 가 폭 0 으로 사라지고,
 * 머리글 「재실행」 버튼이 오른쪽 밖으로 잘렸다. 좁은 화면에서는 DAG 를 위·패널을 아래로 쌓아 둘 다 보여야 한다.
 */
test.describe('파이프라인 실행 상세 — 모바일 레이아웃 (WD-309)', () => {
  test('390px 에서 DAG 와 실행 정보 패널이 위아래로 쌓이고, DAG 스텝을 눌러 오류 상세를 볼 수 있으며, 재실행 버튼이 화면 안에 있다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await setupPipelineEditorMocks(page, 1);
    // 재실행 버튼은 pipeline:execute 권한이 있어야 보인다
    await grantPipelinePermissions(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines/1/executions/2',
      createExecutionDetail({
        id: 2,
        pipelineId: 1,
        status: 'FAILED',
        stepExecutions: [
          createStepExecution({ id: 1, stepName: '데이터 추출', status: 'FAILED', errorMessage: '모바일 확인용 오류' }),
          createStepExecution({ id: 2, stepId: 2, stepName: '데이터 변환', status: 'SKIPPED' }),
        ],
      }),
    );

    await page.goto('/pipelines/1/executions/2');
    await expect(page.getByText('실행 정보')).toBeVisible();

    // DAG 캔버스가 화면 폭 가까이 보인다(이전: 폭 0)
    const flow = page.locator('.react-flow').first();
    await expect(flow).toBeVisible();
    const flowBox = (await flow.boundingBox())!;
    expect(flowBox.width).toBeGreaterThan(300);
    expect(flowBox.height).toBeGreaterThan(200);

    // 패널은 DAG 아래에 전체 폭으로 쌓인다
    const panelTitle = page.getByText('실행 정보');
    const titleBox = (await panelTitle.boundingBox())!;
    expect(titleBox.y).toBeGreaterThanOrEqual(flowBox.y + flowBox.height - 1);
    expect(titleBox.x).toBeLessThan(flowBox.x + 40); // 패널이 DAG 와 같은 왼쪽에서 시작(전체 폭)

    // 화면 전체가 옆으로 넘치지 않는다
    const overflowX = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflowX).toBeLessThanOrEqual(0);

    // 재실행 버튼이 화면 안에 있다
    await expectInsideViewport(page, page.getByRole('button', { name: '현재 설정으로 다시 실행' }));

    // DAG 의 스텝을 누르면 패널이 그 스텝의 오류 상세로 바뀐다
    await page
      .locator('.react-flow__node')
      .filter({ has: page.getByText('데이터 추출', { exact: true }) })
      .click();
    await expect(page.getByText('오류 상세')).toBeVisible();
    await page.getByText('모바일 확인용 오류').scrollIntoViewIfNeeded();
    await expect(page.getByText('모바일 확인용 오류')).toBeInViewport();
  });

  test('1280px 에서는 패널이 DAG 오른쪽 400px 사이드패널로 남는다 (WD-309 회귀 방지)', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await setupPipelineEditorMocks(page, 1);
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines/1/executions/2',
      createExecutionDetail({ id: 2, pipelineId: 1, status: 'FAILED', stepExecutions: [] }),
    );

    await page.goto('/pipelines/1/executions/2');
    await expect(page.getByText('실행 정보')).toBeVisible();
    const flowBox = (await page.locator('.react-flow').first().boundingBox())!;
    const titleBox = (await page.getByText('실행 정보').boundingBox())!;
    // 패널 제목이 DAG 오른쪽에 있고, 위아래로 쌓이지 않는다
    expect(titleBox.x).toBeGreaterThanOrEqual(flowBox.x + flowBox.width - 1);
    expect(titleBox.y).toBeLessThan(flowBox.y + flowBox.height);
  });
});
