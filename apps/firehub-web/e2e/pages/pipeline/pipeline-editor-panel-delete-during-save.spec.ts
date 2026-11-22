/**
 * 파이프라인 편집 — 저장 중에 연 설정 패널 삭제 확인 창 (WD-954)
 *
 * 결함: 저장 요청이 진행 중일 때 패널의 「스텝 삭제」 확인 창을 열면, 저장이 끝나 보기 모드가 되어도
 * 창이 남아 있었고 거기서 「삭제」를 누르면 저장된 스텝이 화면에서만 지워졌다.
 * 이제 보기 모드가 되면 창이 닫히고, 확인 동작도 보기 모드에서는 아무것도 지우지 않는다.
 */

import type { UpdatePipelineRequest } from '@/types/pipeline';

import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { holdRequests } from '../../fixtures/hold-api';
import { grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

test.describe('파이프라인 편집 — 저장 중 패널 삭제 창 (WD-954)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('저장 중에 연 패널 삭제 창은 보기 모드가 되면 닫히고 스텝을 지우지 않는다(WD-954)', async ({
    authenticatedPage: page,
  }) => {
    const V1 = '2026-10-30T10:00:00.123456';
    const puts: UpdatePipelineRequest[] = [];

    const steps = ['s1', 's2', 's3'].map((name, i) =>
      createStep({ id: 11 + i, name, scriptType: 'SQL', scriptContent: 'SELECT 1', stepOrder: i }),
    );
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1',
      async (route) => {
        const method = route.request().method();
        if (method === 'PUT') {
          puts.push(route.request().postDataJSON() as UpdatePipelineRequest);
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ updatedAt: '2026-10-30T10:00:01.111111' }),
          });
        }
        if (method !== 'GET') return route.fallback();
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPipelineDetail({ id: 1, updatedAt: V1, steps })),
        });
      },
    );

    // PUT 을 시험이 풀어 줄 때까지 붙잡는다 — 나중에 등록한 route 가 먼저 받으므로 위 상태 모킹 뒤에 건다
    const hold = await holdRequests(page, '/api/v1/pipelines/1', 'PUT');

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    await page.locator('#step-name').fill('s2-renamed');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect.poll(hold.count).toBe(1);

    // 저장이 붙잡힌 동안 패널의 삭제 창을 연다
    // 캔버스 노드의 X 도 같은 접근 이름이라, 글자가 보이는 패널 버튼만 고른다(DOM 순서에 기대지 않게).
    await page.getByRole('button', { name: '스텝 삭제' }).filter({ hasText: '스텝 삭제' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();

    hold.release();
    // 보기 모드로 돌아온 것(수정 버튼)을 먼저 기다린 뒤 확인해야, 저장이 끝나기 전 상태로 곧바로 통과하지 않는다.
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
    await expect(page.getByRole('alertdialog')).toBeHidden();
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);
    expect(puts).toHaveLength(1);
    expect(puts[0].steps).toHaveLength(3);

    // 다시 수정 모드로 들어가도 닫힌 창이 되살아나지 않는다
    // 수정 모드로 바뀐 것(저장 버튼)을 먼저 기다린 뒤 확인해야, 모드 전환 전에 곧바로 통과하지 않는다.
    await page.getByRole('button', { name: '수정' }).click();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });
});
