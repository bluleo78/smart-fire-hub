/**
 * 차트 편집 — 동시 편집 충돌(낙관적 잠금) E2E (WD-364)
 *
 * 결함: 두 사람(또는 두 탭)이 같은 차트를 고쳐 저장하면 나중 저장이 먼저 저장된 변경을 경고 없이 되돌렸다(폼이 모든 필드를 보낸다).
 * 이제 저장 요청이 편집 기준 버전(expectedUpdatedAt)을 싣고, 서버가 다르면 409 EDIT_CONFLICT 로 거부하면
 * 저장 창 위에 충돌 안내 창(다시 불러오기 / 내 변경으로 덮어쓰기)이 뜬다.
 *
 * 서버는 버전을 가진 상태 기반 모킹이다 — 기준 버전이 다르면 실제 서버처럼 409 를 돌려준다.
 */

import type { Page, Route } from '@playwright/test';

import type { UpdateChartRequest } from '@/types/analytics';

import { createChart } from '../../factories/analytics.factory';
import { setupChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/** 서버 버전 — 마이크로초까지 있는 문자열을 그대로 되돌려 보내는지 확인하려고 소수점 6자리를 쓴다 */
const V1 = '2026-10-30T10:00:00.123456';

/** 버전을 가진 가짜 차트 서버. PUT 의 기준 버전이 다르면 409 EDIT_CONFLICT, 같거나 없으면 반영하고 버전을 올린다 */
async function mockVersionedChart(page: Page) {
  let version = V1;
  let seq = 1;
  let description = 'base-desc';
  const puts: UpdateChartRequest[] = [];

  const detail = () => createChart({ id: 1, description, updatedAt: version });
  /** 다른 탭(다른 사람)이 먼저 저장했다 — 서버 내용과 버전이 바뀐다 */
  const otherTabSaves = (desc: string) => {
    description = desc;
    seq += 1;
    version = `2026-10-30T10:00:0${seq}.654321`;
  };

  await setupChartBuilderMocks(page, 1);
  // 나중에 등록한 라우트가 먼저 처리된다 — 상세 GET 과 PUT 을 버전 서버로 바꾼다
  await page.route(
    (url) => url.pathname === '/api/v1/analytics/charts/1',
    async (route: Route) => {
      const method = route.request().method();
      if (method === 'PUT') {
        const body = route.request().postDataJSON() as UpdateChartRequest;
        puts.push(body);
        if ('expectedUpdatedAt' in body && body.expectedUpdatedAt !== version) {
          return route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 409,
              code: 'EDIT_CONFLICT',
              message: '이 차트는 편집을 시작한 뒤 다른 곳에서 변경되었습니다. 다시 불러온 뒤 저장하세요.',
            }),
          });
        }
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

const saveDialog = (page: Page) => page.getByRole('dialog', { name: '차트 수정' });
const conflictDialog = (page: Page) => page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });

/** 저장 창을 열어 설명을 바꾼다 */
async function openSaveAndEdit(page: Page, value: string) {
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(saveDialog(page)).toBeVisible();
  await saveDialog(page).getByLabel('설명').fill(value);
}

test.describe('차트 편집 — 동시 편집 충돌 (WD-364)', () => {
  test('저장 요청이 상세의 updatedAt 을 그대로 싣고, 응답의 새 버전으로 이어 저장해도 충돌하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedChart(page);
    await page.goto('/analytics/charts/1');
    await expect(page.getByText('테스트 차트').first()).toBeVisible();

    await openSaveAndEdit(page, 'first');
    await saveDialog(page).getByRole('button', { name: '수정' }).click();
    await expect.poll(() => server.puts.length).toBe(1);
    // 서버가 준 문자열 그대로(마이크로초 포함) — Date 로 바꾸면 잘려서 매번 충돌한다
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    await expect(saveDialog(page)).toHaveCount(0);

    // 바로 다시 고쳐 저장 — 기준은 저장 응답의 새 버전이어야 한다(자기 저장과 충돌하지 않는다)
    const afterFirst = server.version();
    await openSaveAndEdit(page, 'second');
    await saveDialog(page).getByRole('button', { name: '수정' }).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect(server.puts[1].expectedUpdatedAt).toBe(afterFirst);
    await expect(saveDialog(page)).toHaveCount(0);
    await expect(conflictDialog(page)).toHaveCount(0);
    expect(server.description()).toBe('second');
  });

  test('다른 탭이 먼저 저장했으면 저장 창 위에 충돌 창이 뜨고, 「내 변경으로 덮어쓰기」는 같은 내용을 기준 버전 없이 다시 보낸다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedChart(page);
    await page.goto('/analytics/charts/1');
    await expect(page.getByText('테스트 차트').first()).toBeVisible();

    await openSaveAndEdit(page, 'mine');
    server.otherTabSaves('other-tab');
    await saveDialog(page).getByRole('button', { name: '수정' }).click();

    await expect(conflictDialog(page)).toBeVisible();
    await expect(conflictDialog(page)).toContainText('이 차트은(는) 편집을 시작한 뒤 다른 사용자 또는 다른 탭에서 변경되었습니다');
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    // 아직 아무것도 덮어쓰지 않았다 — 먼저 저장된 변경이 남아 있다
    expect(server.description()).toBe('other-tab');

    await conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect('expectedUpdatedAt' in server.puts[1]).toBe(false);
    // 덮어쓰기는 첫 저장과 같은 내용(설정 포함)을 보낸다
    // 기준 버전만 빠지고 나머지는 첫 저장과 같다(toEqual 은 undefined 속성을 없는 것으로 본다)
    expect(server.puts[1]).toEqual({ ...server.puts[0], expectedUpdatedAt: undefined });
    await expect(conflictDialog(page)).toHaveCount(0);
    await expect(saveDialog(page)).toHaveCount(0);
    await expect(page.getByText('차트가 수정되었습니다.')).toBeVisible();
    expect(server.description()).toBe('mine');
  });

  test('「다시 불러오기」는 내 변경을 버리고 최신 내용으로 바꾸며, 이어 저장하면 최신 버전을 기준으로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedChart(page);
    await page.goto('/analytics/charts/1');
    await expect(page.getByText('테스트 차트').first()).toBeVisible();

    await openSaveAndEdit(page, 'mine');
    server.otherTabSaves('other-tab');
    await saveDialog(page).getByRole('button', { name: '수정' }).click();
    await expect(conflictDialog(page)).toBeVisible();

    await conflictDialog(page).getByRole('button', { name: '다시 불러오기' }).click();
    await expect(conflictDialog(page)).toHaveCount(0);
    // 저장 창은 닫히고, 다시 열면 최신 서버 내용이 채워져 있다
    await expect(saveDialog(page)).toHaveCount(0);
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(saveDialog(page).getByLabel('설명')).toHaveValue('other-tab');

    const latest = server.version();
    await saveDialog(page).getByLabel('설명').fill('after-reload');
    await saveDialog(page).getByRole('button', { name: '수정' }).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect(server.puts[1].expectedUpdatedAt).toBe(latest);
    await expect(saveDialog(page)).toHaveCount(0);
    expect(server.description()).toBe('after-reload');
  });

  test('코드 없는 409 는 충돌 창이 아니라 서버 문구 토스트로 안내한다', async ({ authenticatedPage: page }) => {
    await mockVersionedChart(page);
    // 나중에 등록한 라우트가 먼저 처리된다 — PUT 만 코드 없는 409 로 바꾼다
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/charts/1',
      (route) =>
        route.request().method() === 'PUT'
          ? route.fulfill({
              status: 409,
              contentType: 'application/json',
              body: JSON.stringify({ status: 409, message: '같은 이름의 차트가 이미 있습니다.' }),
            })
          : route.fallback(),
    );
    await page.goto('/analytics/charts/1');
    await expect(page.getByText('테스트 차트').first()).toBeVisible();

    await openSaveAndEdit(page, 'mine');
    await saveDialog(page).getByRole('button', { name: '수정' }).click();
    await expect(page.getByText('같은 이름의 차트가 이미 있습니다.')).toBeVisible();
    await expect(conflictDialog(page)).toHaveCount(0);
  });
});
