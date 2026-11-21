import type { Page } from '@playwright/test';

import { createCategories } from '../factories/dataset.factory';
import { createPipelineDetail, createStep } from '../factories/pipeline.factory';
import { createPageResponse, mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../fixtures/dataset.fixture';
import { grantPipelinePermissions, PIPELINE_ALL_PERMISSIONS } from '../fixtures/pipeline.fixture';

/**
 * 탭 트리거·파이프라인 캔버스 컨트롤의 클릭 대상 크기 (WD-883, WCAG 2.5.8)
 *
 * 탭 트리거가 29px, xyflow 확대·축소·전체 보기 버튼이 26×26 이라 작았다.
 * 공용 tabs.tsx 와 PipelineCanvas 의 Controls 를 고쳐 둘 다 32px 이상이 되는지 확인한다.
 */

const MIN = 32;

/** 파이프라인 1 상세 모킹 — 스텝 하나로 캔버스(개요 탭)를 그린다. */
async function setupPipelineDetail(page: Page) {
  await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, steps: [createStep({ id: 5 })] }));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', createPageResponse([]));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
  await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]));
  await grantPipelinePermissions(page, PIPELINE_ALL_PERMISSIONS);
}

/** 화면의 탭 트리거가 최소 개수 이상 있고 모두 높이 32 이상인지 — 한 번의 evaluateAll 로 높이를 모아 비교한다. */
async function expectTabsMinHeight(page: Page, minCount: number) {
  const heights = await page.getByRole('tab').evaluateAll((els) => els.map((el) => el.getBoundingClientRect().height));
  expect(heights.length).toBeGreaterThanOrEqual(minCount);
  for (const h of heights) expect(h).toBeGreaterThanOrEqual(MIN);
}

test.describe('탭·캔버스 클릭 대상 크기 (WD-883)', () => {
  test('파이프라인 상세 탭 트리거 높이가 32 이상 (WD-883)', async ({ authenticatedPage: page }) => {
    await setupPipelineDetail(page);
    await page.goto('/pipelines/1');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();

    await expectTabsMinHeight(page, 3);
  });

  test('파이프라인 캔버스 확대·축소·전체 보기가 32×32 이상 (WD-883)', async ({ authenticatedPage: page }) => {
    await setupPipelineDetail(page);
    await page.goto('/pipelines/1');
    await expect(page.getByRole('button', { name: '확대' })).toBeVisible();

    const buttons = page.locator('.react-flow__controls-button');
    expect(await buttons.count()).toBeGreaterThanOrEqual(3);
    for (const button of await buttons.all()) {
      const box = await button.boundingBox();
      expect(box!.width).toBeGreaterThanOrEqual(MIN);
      expect(box!.height).toBeGreaterThanOrEqual(MIN);
    }
  });

  test('좁은 화면(375px, 탭 줄바꿈)에서도 파이프라인 상세 탭 높이가 32 이상 (WD-883)', async ({ authenticatedPage: page }) => {
    // max-sm 에서는 목록이 h-auto 라 트리거 높이가 목록을 따르지 않는다 — 트리거 자체 최소 높이를 확인한다.
    await page.setViewportSize({ width: 375, height: 800 });
    await setupPipelineDetail(page);
    await page.goto('/pipelines/1');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();

    await expectTabsMinHeight(page, 3);
  });

  test('다른 화면 탭(데이터셋 상세)도 32 이상 — 공용 tabs 적용 (WD-883)', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await page.goto('/data/datasets/1');
    await expect(page.getByRole('tab', { name: '정보' })).toBeVisible();

    // 테이블 데이터셋 기본 모킹의 탭 7개(정보·보안·필드·데이터·검색·매핑·이력)를 모두 센다.
    await expectTabsMinHeight(page, 7);
  });
});
