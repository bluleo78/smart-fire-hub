/**
 * 파이프라인 에디터 모바일 뷰포트 회귀 테스트 (refs #22)
 * - 375px(모바일) 뷰포트에서 ReactFlow 캔버스가 width=0px로 붕괴하는 버그 회귀 방지
 * - 데스크톱(1280px) 뷰포트에서는 기존 가로 레이아웃 유지 확인
 */

import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

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
});
