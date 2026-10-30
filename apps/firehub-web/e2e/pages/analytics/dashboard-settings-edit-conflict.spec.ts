/**
 * 대시보드 설정 창 — 동시 편집 충돌(낙관적 잠금) E2E (WD-364)
 *
 * 결함: 두 사람(또는 두 탭)이 같은 대시보드의 설정(이름·설명·공개·자동 새로고침)을 고쳐 저장하면 나중 저장이
 * 먼저 저장된 변경을 경고 없이 되돌렸다(설정 창이 모든 필드를 보낸다). 이제 저장 요청이 설정 창을 연 시점의
 * 버전(expectedUpdatedAt)을 싣고, 서버가 409 EDIT_CONFLICT 로 거부하면 설정 창 위에 충돌 안내 창이 뜬다.
 *
 * 서버는 버전을 가진 상태 기반 모킹이다 — 기준 버전이 다르면 실제 서버처럼 409 를 돌려준다.
 */

import type { Page, Route } from '@playwright/test';

import type { UpdateDashboardRequest } from '@/types/analytics';

import { createDashboard, createDashboardListItem } from '../../factories/analytics.factory';
import { createPageResponse } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 서버 버전 — 마이크로초까지 있는 문자열을 그대로 되돌려 보내는지 확인하려고 소수점 6자리를 쓴다 */
const V1 = '2026-10-30T10:00:00.123456';

/** 버전을 가진 가짜 대시보드 서버(목록·상세·수정). PUT 의 기준 버전이 다르면 409 EDIT_CONFLICT */
async function mockVersionedDashboard(page: Page) {
  let version = V1;
  let seq = 1;
  let name = '버전 대시보드';
  let description = 'base-desc';
  const puts: UpdateDashboardRequest[] = [];

  /** 다른 탭(다른 사람)이 먼저 저장했다 — 서버 내용과 버전이 바뀐다 */
  const otherTabSaves = (desc: string) => {
    description = desc;
    seq += 1;
    version = `2026-10-30T10:00:0${seq}.654321`;
  };

  await page.route(
    (url) => url.pathname === '/api/v1/analytics/dashboards',
    (route) =>
      route.request().method() === 'GET'
        ? route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(
              createPageResponse([createDashboardListItem({ id: 1, name, description, updatedAt: version })]),
            ),
          })
        : route.fallback(),
  );
  await page.route(
    (url) => url.pathname === '/api/v1/analytics/dashboards/1',
    async (route: Route) => {
      const method = route.request().method();
      const detail = () => createDashboard({ id: 1, name, description, updatedAt: version });
      if (method === 'PUT') {
        const body = route.request().postDataJSON() as UpdateDashboardRequest;
        puts.push(body);
        if ('expectedUpdatedAt' in body && body.expectedUpdatedAt !== version) {
          return route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 409,
              code: 'EDIT_CONFLICT',
              message: '이 대시보드는 편집을 시작한 뒤 다른 곳에서 변경되었습니다. 다시 불러온 뒤 저장하세요.',
            }),
          });
        }
        name = body.name ?? name;
        description = body.description ?? description;
        seq += 1;
        version = `2026-10-30T10:00:0${seq}.111111`;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detail()) });
      }
      if (method !== 'GET') return route.fallback();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detail()) });
    },
  );

  return { puts, otherTabSaves, version: () => version, description: () => description };
}

const settingsDialog = (page: Page) => page.getByRole('dialog', { name: '대시보드 설정' });
const conflictDialog = (page: Page) => page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });

/** 목록 행의 「설정」으로 설정 창을 연다 */
async function openSettings(page: Page, rowName: string) {
  await page.getByRole('row', { name: new RegExp(rowName) }).getByLabel('설정').click();
  await expect(settingsDialog(page)).toBeVisible();
}

test.describe('대시보드 설정 — 동시 편집 충돌 (WD-364)', () => {
  test('저장 요청이 목록 행의 updatedAt 을 그대로 싣고, 다시 열어 저장하면 새 버전을 기준으로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedDashboard(page);
    await page.goto('/analytics/dashboards');

    await openSettings(page, '버전 대시보드');
    await settingsDialog(page).getByLabel('이름 *').fill('이름 A');
    await settingsDialog(page).getByRole('button', { name: '저장' }).click();
    await expect.poll(() => server.puts.length).toBe(1);
    // 서버가 준 문자열 그대로(마이크로초 포함) — Date 로 바꾸면 잘려서 매번 충돌한다
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    await expect(settingsDialog(page)).toHaveCount(0);

    // 목록이 새 버전으로 다시 읽힌 뒤 다시 열어 저장 — 기준은 그 새 버전이다(자기 저장과 충돌하지 않는다)
    const afterFirst = server.version();
    await openSettings(page, '이름 A');
    await settingsDialog(page).getByLabel('설명').fill('second');
    await settingsDialog(page).getByRole('button', { name: '저장' }).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect(server.puts[1].expectedUpdatedAt).toBe(afterFirst);
    await expect(settingsDialog(page)).toHaveCount(0);
    await expect(conflictDialog(page)).toHaveCount(0);
    expect(server.description()).toBe('second');
  });

  test('다른 탭이 먼저 저장했으면 설정 창 위에 충돌 창이 뜨고, 「내 변경으로 덮어쓰기」는 같은 내용을 기준 버전 없이 다시 보낸다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedDashboard(page);
    await page.goto('/analytics/dashboards');

    await openSettings(page, '버전 대시보드');
    await settingsDialog(page).getByLabel('설명').fill('mine');
    server.otherTabSaves('other-tab');
    await settingsDialog(page).getByRole('button', { name: '저장' }).click();

    await expect(conflictDialog(page)).toBeVisible();
    await expect(conflictDialog(page)).toContainText('이 대시보드은(는) 편집을 시작한 뒤 다른 사용자 또는 다른 탭에서 변경되었습니다');
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    expect(server.description()).toBe('other-tab');

    await conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect('expectedUpdatedAt' in server.puts[1]).toBe(false);
    // 기준 버전만 빠지고 나머지는 첫 저장과 같다(toEqual 은 undefined 속성을 없는 것으로 본다)
    expect(server.puts[1]).toEqual({ ...server.puts[0], expectedUpdatedAt: undefined });
    await expect(conflictDialog(page)).toHaveCount(0);
    await expect(settingsDialog(page)).toHaveCount(0);
    expect(server.description()).toBe('mine');
  });

  test('「다시 불러오기」는 설정 창을 최신 값으로 바꾸고(창은 그대로), 이어 저장하면 최신 버전을 기준으로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedDashboard(page);
    await page.goto('/analytics/dashboards');

    await openSettings(page, '버전 대시보드');
    await settingsDialog(page).getByLabel('설명').fill('mine');
    server.otherTabSaves('other-tab');
    await settingsDialog(page).getByRole('button', { name: '저장' }).click();
    await expect(conflictDialog(page)).toBeVisible();

    await conflictDialog(page).getByRole('button', { name: '다시 불러오기' }).click();
    await expect(conflictDialog(page)).toHaveCount(0);
    await expect(settingsDialog(page)).toBeVisible();
    await expect(settingsDialog(page).getByLabel('설명')).toHaveValue('other-tab');

    const latest = server.version();
    await settingsDialog(page).getByLabel('설명').fill('after-reload');
    await settingsDialog(page).getByRole('button', { name: '저장' }).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect(server.puts[1].expectedUpdatedAt).toBe(latest);
    await expect(settingsDialog(page)).toHaveCount(0);
    expect(server.description()).toBe('after-reload');
  });

  test('코드 없는 409 는 충돌 창이 아니라 서버 문구 토스트로 안내한다', async ({ authenticatedPage: page }) => {
    await mockVersionedDashboard(page);
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/dashboards/1',
      (route) =>
        route.request().method() === 'PUT'
          ? route.fulfill({
              status: 409,
              contentType: 'application/json',
              body: JSON.stringify({ status: 409, message: '같은 이름의 대시보드가 이미 있습니다.' }),
            })
          : route.fallback(),
    );
    await page.goto('/analytics/dashboards');

    await openSettings(page, '버전 대시보드');
    await settingsDialog(page).getByLabel('설명').fill('mine');
    await settingsDialog(page).getByRole('button', { name: '저장' }).click();
    await expect(page.getByText('같은 이름의 대시보드가 이미 있습니다.')).toBeVisible();
    await expect(conflictDialog(page)).toHaveCount(0);
  });
});
