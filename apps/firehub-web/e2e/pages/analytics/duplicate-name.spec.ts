/**
 * 분석 자산 이름 중복(409 DUPLICATE_NAME) E2E (WD-824)
 *
 * 결함: 같은 소유자가 같은 이름의 쿼리·차트·대시보드를 여러 개 만들 수 있어 목록에서 구분되지 않았다.
 * 이제 서버가 같은 소유자 안의 중복 이름을 409 DUPLICATE_NAME 으로 막고, 화면은 서버 문구를 이름 칸 아래에 보이며
 * 창을 닫지 않고 토스트도 띄우지 않는다(같은 문구가 두 번 보이지 않게). 대시보드 목록은 작성자·공유 표시로 같은 이름도 구분한다.
 */

import type { Page, Route } from '@playwright/test';

import { createDashboard, createDashboardListItem } from '../../factories/analytics.factory';
import { setupChartBuilderMocks, setupDashboardListMocks, setupQueryEditorMocks } from '../../fixtures/analytics.fixture';
import { createPageResponse } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 서버가 돌려주는 자원별 문구(api ResourceKind 의 message 와 같다) */
const QUERY_MSG = '이미 있는 쿼리 이름입니다. 다른 이름을 입력하세요.';
const CHART_MSG = '이미 있는 차트 이름입니다. 다른 이름을 입력하세요.';
const DASHBOARD_MSG = '이미 있는 대시보드 이름입니다. 다른 이름을 입력하세요.';

/** 해당 경로의 비-GET 요청을 409 DUPLICATE_NAME 으로 거부한다 */
async function rejectWritesAsDuplicate(page: Page, path: string, message: string) {
  await page.route(
    (url) => url.pathname === path,
    (route: Route) => {
      if (route.request().method() === 'GET') return route.fallback();
      return route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ status: 409, error: 'Conflict', code: 'DUPLICATE_NAME', message }),
      });
    },
  );
}

test.describe('이름 중복 409 — 이름 칸 오류 (WD-824)', () => {
  test('쿼리 저장: 이름 칸 아래에 서버 문구가 한 번 보이고 저장 창이 닫히지 않는다', async ({ authenticatedPage: page }) => {
    await setupQueryEditorMocks(page, 1);
    await rejectWritesAsDuplicate(page, '/api/v1/analytics/queries/1', QUERY_MSG);
    await page.goto('/analytics/queries/1');
    await expect(page.locator('.cm-content')).toContainText('SELECT');

    await page.getByRole('button', { name: '저장', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '쿼리 수정' });
    await dialog.getByRole('button', { name: '저장' }).click();

    await expect(dialog.locator('#query-name').locator('..').getByText(QUERY_MSG)).toBeVisible();
    // 창 안·토스트 어디에도 같은 문구가 또 없다
    await expect(page.getByText(QUERY_MSG)).toHaveCount(1);
    await expect(dialog).toBeVisible();

    // 이름을 고치면 오류가 사라진다
    await dialog.locator('#query-name').fill('다른 이름');
    await expect(page.getByText(QUERY_MSG)).toHaveCount(0);
  });

  test('차트 저장: 이름 칸 아래에 서버 문구가 한 번 보이고 저장 창이 닫히지 않는다', async ({ authenticatedPage: page }) => {
    await setupChartBuilderMocks(page, 1);
    await rejectWritesAsDuplicate(page, '/api/v1/analytics/charts/1', CHART_MSG);
    await page.goto('/analytics/charts/1');
    await expect(page.getByText('테스트 차트').first()).toBeVisible();

    await page.getByRole('button', { name: '저장', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '차트 수정' });
    await dialog.getByRole('button', { name: '저장' }).click();

    await expect(dialog.locator('#chart-name').locator('..').getByText(CHART_MSG)).toBeVisible();
    await expect(page.getByText(CHART_MSG)).toHaveCount(1);
    await expect(dialog).toBeVisible();

    await dialog.locator('#chart-name').fill('다른 이름');
    await expect(page.getByText(CHART_MSG)).toHaveCount(0);
  });

  test('대시보드 만들기: 이름 칸 아래에 서버 문구가 한 번 보이고 창이 닫히지 않는다', async ({ authenticatedPage: page }) => {
    await setupDashboardListMocks(page, 1);
    await rejectWritesAsDuplicate(page, '/api/v1/analytics/dashboards', DASHBOARD_MSG);
    await page.goto('/analytics/dashboards');

    await page.getByRole('button', { name: '새 대시보드' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('이름 *').fill('겹치는 이름');
    await dialog.getByRole('button', { name: '만들기' }).click();

    await expect(dialog.locator('#dash-name').locator('..').getByText(DASHBOARD_MSG)).toBeVisible();
    await expect(page.getByText(DASHBOARD_MSG)).toHaveCount(1);
    await expect(dialog).toBeVisible();

    await dialog.getByLabel('이름 *').fill('겹치지 않는 이름');
    await expect(page.getByText(DASHBOARD_MSG)).toHaveCount(0);
  });

  test('대시보드 설정: 이름을 겹치게 고쳐 저장하면 이름 칸 아래에 서버 문구가 보이고 창이 닫히지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/dashboards',
      (route) =>
        route.request().method() === 'GET'
          ? route.fulfill({
              status: 200,
              contentType: 'application/json',
              body: JSON.stringify(createPageResponse([createDashboardListItem({ id: 1, name: '설정 대시보드' })])),
            })
          : route.fallback(),
    );
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/dashboards/1',
      (route) =>
        route.request().method() === 'GET'
          ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createDashboard({ id: 1 })) })
          : route.fulfill({
              status: 409,
              contentType: 'application/json',
              body: JSON.stringify({ status: 409, error: 'Conflict', code: 'DUPLICATE_NAME', message: DASHBOARD_MSG }),
            }),
    );
    await page.goto('/analytics/dashboards');
    await page.getByRole('row', { name: /설정 대시보드/ }).getByLabel('설정').click();
    const dialog = page.getByRole('dialog', { name: '대시보드 설정' });
    await dialog.locator('#edit-dash-name').fill('이미 있는 이름');
    await dialog.getByRole('button', { name: '저장' }).click();

    await expect(dialog.locator('#edit-dash-name').locator('..').getByText(DASHBOARD_MSG)).toBeVisible();
    await expect(page.getByText(DASHBOARD_MSG)).toHaveCount(1);
    await expect(dialog).toBeVisible();
  });

  test('대시보드 목록: 같은 이름이어도 작성자 이름과 공유 배지로 구분된다', async ({ authenticatedPage: page }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/dashboards',
      (route) =>
        route.request().method() === 'GET'
          ? route.fulfill({
              status: 200,
              contentType: 'application/json',
              body: JSON.stringify(
                createPageResponse([
                  createDashboardListItem({ id: 1, name: '월간 현황', createdByName: '김작성', isShared: false }),
                  createDashboardListItem({ id: 2, name: '월간 현황', createdByName: '박공유', isShared: true, createdBy: 2 }),
                ]),
              ),
            })
          : route.fallback(),
    );
    await page.goto('/analytics/dashboards');

    const mine = page.getByRole('row', { name: /김작성/ });
    const shared = page.getByRole('row', { name: /박공유/ });
    await expect(mine).toContainText('월간 현황');
    await expect(shared).toContainText('월간 현황');
    await expect(shared.getByText('공유', { exact: true })).toBeVisible();
    await expect(mine.getByText('공유', { exact: true })).toHaveCount(0);
  });
});
