/**
 * 본문 main 으로 떨어진 포커스도 「잃음」으로 보는 화면들 (WD-980)
 *
 * 「본문으로 건너뛰기」 뒤 main#main-content 에 tabindex=-1 이 남아 포커스가 body 대신 main 으로 떨어질 수 있다.
 * 그때도 화면별 포커스 복구(모델 개요 접기/펴기·파이프라인 저장 실패 복원)가 동작해야 한다.
 * 건너뛰기는 AppLayout 과 같이 main 에 tabindex=-1 을 붙이고 focus() 하는 것으로 재현한다.
 * 포커스를 가져가지 않게 대상 버튼은 DOM click() 으로 누른다(마우스·Tab 은 포커스를 옮겨 버린다).
 */
import type { Route } from '@playwright/test';

import { createPipelineDetail, createStep } from '../factories/pipeline.factory';
import { setupAdminAuth, setupOntologyMocks } from '../fixtures/admin.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { gotoUntilReady } from '../fixtures/first-visit';
import { focusMain } from '../fixtures/focus-main';
import { holdApi } from '../fixtures/hold-api';
import { grantPipelinePermissions } from '../fixtures/pipeline.fixture';

test.describe('본문 main 포커스 잃음 판정 (WD-980)', () => {
  test('모델 편집기 개요: 포커스가 main 일 때 목록을 펴고 접으면 보이는 쪽 토글로 옮겨진다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.setViewportSize({ width: 1024, height: 800 });
    await gotoUntilReady(page, '/knowledge-graph/model', page.getByRole('button', { name: '수정 모드' }));
    await page.getByRole('button', { name: '수정 모드' }).click();
    await expect(page.getByRole('button', { name: '목록 펼치기' })).toBeVisible();

    await focusMain(page);
    await page.getByRole('button', { name: '목록 펼치기' }).evaluate((el) => (el as HTMLElement).click());
    await expect(page.getByRole('button', { name: '목록 접기' })).toBeFocused();

    await focusMain(page);
    await page.getByRole('button', { name: '목록 접기' }).evaluate((el) => (el as HTMLElement).click());
    await expect(page.getByRole('button', { name: '목록 펼치기' })).toBeFocused();
  });

  test('파이프라인 편집기: 포커스가 main 일 때 저장이 실패하면 저장 버튼으로 복원된다', async ({
    authenticatedPage: page,
  }) => {
    await grantPipelinePermissions(page);
    const steps = ['s1', 's2'].map((name, i) =>
      createStep({ id: 11 + i, name, scriptType: 'SQL', scriptContent: 'SELECT 1', stepOrder: i }),
    );
    await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
    await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1',
      (route: Route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPipelineDetail({ id: 1, updatedAt: '2026-10-30T10:00:00.123456', steps })),
        });
      },
    );
    const hold = await holdApi(page, 'PUT', '/api/v1/pipelines/1', 500, { status: 500, message: '서버 오류' });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: 's2' }).click();
    await page.locator('#step-name').fill('s2-renamed');

    await focusMain(page);
    const save = page.getByRole('button', { name: '저장', exact: true });
    await save.evaluate((el) => (el as HTMLElement).click());
    await expect.poll(() => hold.requests.length).toBe(1);
    hold.release();

    // 실패 뒤 포커스는 main 이 아니라 저장 버튼에 있다
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    await expect(save).toBeFocused();
  });
});
