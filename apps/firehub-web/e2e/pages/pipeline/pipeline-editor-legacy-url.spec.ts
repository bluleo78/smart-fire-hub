/**
 * 파이프라인 에디터 — 옛 `apiConfig.url` 키로 저장된 API 호출 스텝 E2E (WD-925)
 *
 * API 가져오기 마법사가 URL 을 `url` 로 저장했지만 편집기는 `customUrl` 만 읽어 URL 칸이 비고 저장이 막혔다.
 * 불러올 때 `customUrl` 로 옮겨 보기·편집·취소·저장이 모두 그 URL 을 쓰는지 검증한다.
 */

import type { Page } from '@playwright/test';

import { API_FULL_URL_PLACEHOLDER } from '../../../src/lib/api-connection-form';
import type { UpdatePipelineRequest } from '../../../src/types/pipeline';
import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

const LEGACY_URL = 'https://legacy.example.com/data';

/** 옛 url 키만 가진 API_CALL 스텝 한 개 — 연결 없음 */
const detail = createPipelineDetail({
  id: 1,
  steps: [
    createStep({
      id: 11,
      name: 'api1',
      description: '',
      scriptType: 'API_CALL',
      scriptContent: '',
      apiConfig: { url: LEGACY_URL, method: 'GET', headers: {}, queryParams: {} },
      apiConnectionId: null,
    }),
  ],
});

/** 편집기 공용 모킹 위에 상세만 이 스텝으로 덮는다(나중에 등록한 모킹이 이긴다) */
async function mockDetail(page: Page) {
  await setupPipelineEditorMocks(page);
  await mockApi(page, 'GET', '/api/v1/pipelines/1', detail);
  await mockApi(page, 'GET', '/api/v1/api-connections/selectable', []);
}

/** 스텝 노드를 열고 URL 칸을 돌려준다 */
async function openStep(page: Page) {
  await page.locator('.react-flow__node').first().click();
  await expect(page.getByText('기본 설정')).toBeVisible({ timeout: 10000 });
  return page.getByPlaceholder(API_FULL_URL_PLACEHOLDER, { exact: true });
}

/** 편집 모드로 들어가 스텝을 연다 */
async function openEditing(page: Page) {
  await page.goto('/pipelines/1');
  await page.getByRole('button', { name: '수정' }).click();
  return openStep(page);
}

test.describe('파이프라인 에디터 — 옛 url 키 API 스텝 (WD-925)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
    await mockDetail(page);
  });

  test('보기·편집 모두 옛 url 이 URL 칸에 보인다', async ({ authenticatedPage: page }) => {
    await page.goto('/pipelines/1');
    const urlInput = await openStep(page);
    await expect(urlInput).toHaveValue(LEGACY_URL);
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);

    await page.getByRole('button', { name: '수정' }).click();
    await expect(urlInput).toHaveValue(LEGACY_URL);
  });

  test('다른 칸만 고쳐 저장하면 customUrl 로 보내고 url 은 없다', async ({ authenticatedPage: page }) => {
    const capture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', null, { capture: true, status: 204 });
    await openEditing(page);
    await page.locator('#step-description').fill('설명 변경');
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    await page.getByRole('button', { name: '저장', exact: true }).click();

    const body = (await capture.waitForRequest()).payload as UpdatePipelineRequest;
    const apiConfig = body.steps[0].apiConfig as Record<string, unknown>;
    expect(apiConfig.customUrl).toBe(LEGACY_URL);
    expect(apiConfig).not.toHaveProperty('url');
  });

  test('URL 을 지우면 저장이 막힌다', async ({ authenticatedPage: page }) => {
    let putCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1',
      (route) => {
        if (route.request().method() === 'PUT') putCount += 1;
        return route.fallback();
      },
    );
    const urlInput = await openEditing(page);
    await urlInput.fill('');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByText('URL을 입력하세요').first()).toBeVisible();
    expect(putCount).toBe(0);
  });

  test('편집을 취소하면 옛 url 값으로 돌아온다', async ({ authenticatedPage: page }) => {
    const urlInput = await openEditing(page);
    await urlInput.fill('https://other.example.com');
    await page.getByRole('button', { name: '취소', exact: true }).click();
    // 미저장 변경이 있어 확인 대화상자가 뜬다
    await page.getByRole('button', { name: '변경사항 취소' }).click();
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
    await expect(await openStep(page)).toHaveValue(LEGACY_URL);
  });
});
