/**
 * 파이프라인 연쇄 트리거 — 선행 파이프라인 선택 목록 E2E 테스트 (#737)
 *
 * 서버(PipelineController)는 GET /pipelines 의 size 에 @Max(200) 검증을 걸어, 초과하면 잘라내지 않고 400 을 준다.
 * 예전 PipelineChainForm 은 size=1000 한 번만 조회해 항상 400 을 받았고, 오류를 빈 목록으로 삼켜
 * "파이프라인을 찾을 수 없습니다."만 보였다(연쇄 트리거를 UI 에서 만들 수 없음).
 * 이 모킹은 서버의 상한(초과 시 400)·페이지 분할 동작을 그대로 흉내 내어,
 * 전 페이지 순회로 두 번째 페이지 항목까지 고를 수 있는지와 조회 실패 시 오류가 드러나는지를 검증한다.
 */

import type { Page } from '@playwright/test';

import { createPipelineDetail, createPipelines, createTrigger } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 서버 목록 조회 상한 — PipelineController 의 @Max(200) 과 같게 둔다. */
const SERVER_MAX_SIZE = 200;
/** 전체 파이프라인 205개 — id 201~205 는 두 번째 페이지에만 있다. id 1 은 현재 파이프라인. */
const ALL_PIPELINES = createPipelines(205);

/** 서버처럼 size>200 이면 400, 아니면 page 에 맞는 조각을 돌려주는 파이프라인 목록 모킹 */
async function mockPagedPipelines(page: Page) {
  const requestedSizes: number[] = [];
  await page.route(
    (url) => url.pathname === '/api/v1/pipelines',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const params = new URL(route.request().url()).searchParams;
      const size = Number(params.get('size') ?? 20);
      requestedSizes.push(size);
      if (size > SERVER_MAX_SIZE || size < 1) {
        return route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Validation failed', errors: { size: '200 이하여야 합니다' } }),
        });
      }
      const pageNo = Math.max(0, Number(params.get('page') ?? 0));
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content: ALL_PIPELINES.slice(pageNo * size, pageNo * size + size),
          page: pageNo,
          size,
          totalElements: ALL_PIPELINES.length,
          totalPages: Math.ceil(ALL_PIPELINES.length / size),
        }),
      });
    },
  );
  return requestedSizes;
}

/** 파이프라인 상세·트리거 탭 공통 모킹 */
async function setupDetailMocks(page: Page, triggers: ReturnType<typeof createTrigger>[] = []) {
  await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, name: '파이프라인 1' }));
  await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', triggers);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
}

/** 트리거 탭 → 트리거 추가 → 파이프라인 연쇄 폼까지 연다 */
async function openChainForm(page: Page) {
  await page.goto('/pipelines/1');
  await page.getByRole('tab', { name: '트리거' }).click();
  await page.getByRole('button', { name: /트리거 추가/ }).click();
  await page.getByRole('button', { name: /파이프라인 연쇄/ }).click();
}

test.describe('파이프라인 연쇄 트리거 — 선행 파이프라인 목록 (#737)', () => {
  test('두 번째 페이지에만 있는 파이프라인을 선행으로 골라 생성 payload 로 전달된다', async ({
    authenticatedPage: page,
  }) => {
    await setupDetailMocks(page);
    const requestedSizes = await mockPagedPipelines(page);
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines/1/triggers',
      createTrigger({ id: 30, name: '연쇄', triggerType: 'PIPELINE_CHAIN' }),
      { capture: true },
    );

    await openChainForm(page);
    await page.getByLabel(/^이름/).fill('연쇄 트리거');

    await page.getByRole('combobox', { name: '선행 파이프라인' }).click();
    // 두 번째 페이지 항목(205)을 검색해 고른다
    await page.getByPlaceholder('파이프라인 검색...').fill('파이프라인 205');
    await page.getByRole('option', { name: '파이프라인 205', exact: true }).click();
    await expect(page.getByRole('combobox', { name: '선행 파이프라인' })).toHaveText('파이프라인 205');

    // 자기 자신(현재 파이프라인 id 1)은 선행 후보에서 빠져 있어야 한다
    await page.getByRole('combobox', { name: '선행 파이프라인' }).click();
    await page.getByPlaceholder('파이프라인 검색...').fill('파이프라인 1');
    await expect(page.getByRole('option', { name: '파이프라인 10', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: '파이프라인 1', exact: true })).toHaveCount(0);
    await page.keyboard.press('Escape');

    await page.getByRole('button', { name: '트리거 생성' }).click();
    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      triggerType: 'PIPELINE_CHAIN',
      config: { upstreamPipelineId: 205 },
    });

    // 서버 상한을 넘는 size 로 요청하지 않는다(넘으면 400)
    expect(requestedSizes.length).toBeGreaterThan(0);
    expect(requestedSizes.every((s) => s <= SERVER_MAX_SIZE)).toBe(true);
  });

  test('기존 연쇄 트리거 편집 시 두 번째 페이지의 선행 파이프라인이 이름으로 보인다', async ({
    authenticatedPage: page,
  }) => {
    const existing = createTrigger({
      id: 7,
      name: '기존 연쇄',
      triggerType: 'PIPELINE_CHAIN',
      config: { upstreamPipelineId: 203, condition: 'SUCCESS' },
    });
    await setupDetailMocks(page, [existing]);
    await mockPagedPipelines(page);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: '트리거' }).click();
    await expect(page.getByText('기존 연쇄')).toBeVisible();
    await page.getByRole('button').filter({ has: page.locator('.lucide-ellipsis') }).first().click();
    await page.getByRole('menuitem', { name: '편집' }).click();

    await expect(page.getByRole('combobox', { name: '선행 파이프라인' })).toHaveText('파이프라인 203');
  });

  test('목록 조회가 실패하면 "찾을 수 없습니다" 대신 오류를 보여준다', async ({ authenticatedPage: page }) => {
    await setupDetailMocks(page);
    await mockApi(page, 'GET', '/api/v1/pipelines', { message: '서버 오류' }, { status: 500 });

    await openChainForm(page);
    await page.getByRole('combobox', { name: '선행 파이프라인' }).click();

    await expect(page.getByText('파이프라인 목록을 불러오지 못했습니다.')).toBeVisible();
    await expect(page.getByText('파이프라인을 찾을 수 없습니다.')).toHaveCount(0);
  });
});
