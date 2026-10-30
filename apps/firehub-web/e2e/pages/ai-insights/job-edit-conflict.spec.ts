/**
 * 스마트 작업 편집 — 동시 편집 충돌(낙관적 잠금) E2E (WD-364)
 *
 * 결함: 두 사람(또는 두 탭)이 같은 작업을 고쳐 저장하면 나중 저장이 먼저 저장된 변경을 경고 없이 되돌렸다(폼이 모든 필드를 보낸다).
 * 이제 저장 요청이 편집 기준 버전(expectedUpdatedAt)을 싣고, 서버가 409 EDIT_CONFLICT 로 거부하면
 * 충돌 안내 창(다시 불러오기 / 내 변경으로 덮어쓰기)이 뜬다. 수정 응답은 204 가 아니라 200 {updatedAt} 이다.
 *
 * 서버는 버전을 가진 상태 기반 모킹이다 — 기준 버전이 다르면 실제 서버처럼 409 를 돌려준다.
 */

import type { Page, Route } from '@playwright/test';

import type { UpdateProactiveJobRequest } from '@/api/proactive';

import { createJob, createTemplates } from '../../factories/ai-insight.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 서버 버전 — 마이크로초까지 있는 문자열을 그대로 되돌려 보내는지 확인하려고 소수점 6자리를 쓴다 */
const V1 = '2026-10-30T10:00:00.123456';

/** 버전을 가진 가짜 작업 서버. PUT 의 기준 버전이 다르면 409 EDIT_CONFLICT, 같거나 없으면 반영하고 새 버전만 돌려준다 */
async function mockVersionedJob(page: Page) {
  let version = V1;
  let seq = 1;
  let name = '버전 작업';
  const puts: UpdateProactiveJobRequest[] = [];

  const detail = () => createJob({ id: 1, name, updatedAt: version });
  /** 다른 탭(다른 사람)이 먼저 저장했다 — 서버 내용(이름)과 버전이 바뀐다 */
  const otherTabSaves = (nextName: string) => {
    name = nextName;
    seq += 1;
    version = `2026-10-30T10:00:0${seq}.654321`;
  };

  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await page.route(
    (url) => url.pathname === '/api/v1/proactive/jobs/1',
    async (route: Route) => {
      const method = route.request().method();
      if (method === 'PUT') {
        const body = route.request().postDataJSON() as UpdateProactiveJobRequest;
        puts.push(body);
        if ('expectedUpdatedAt' in body && body.expectedUpdatedAt !== version) {
          return route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 409,
              code: 'EDIT_CONFLICT',
              message: '이 작업은 편집을 시작한 뒤 다른 곳에서 변경되었습니다. 다시 불러온 뒤 저장하세요.',
            }),
          });
        }
        name = body.name ?? name;
        seq += 1;
        version = `2026-10-30T10:00:0${seq}.111111`;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ updatedAt: version }) });
      }
      if (method !== 'GET') return route.fallback();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detail()) });
    },
  );

  return { puts, otherTabSaves, version: () => version, name: () => name };
}

const conflictDialog = (page: Page) => page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });
const editButton = (page: Page) => page.getByRole('button', { name: '편집' });
const saveButton = (page: Page) => page.getByRole('button', { name: '저장', exact: true });

/** 편집 모드로 들어가 작업 이름을 바꾼다 */
async function editName(page: Page, value: string) {
  await editButton(page).click();
  await expect(page.locator('#job-name')).toBeVisible();
  await page.locator('#job-name').fill(value);
}

test.describe('스마트 작업 편집 — 동시 편집 충돌 (WD-364)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // 편집 버튼은 쓰기 권한이 있을 때만 보인다(WD-133)
    await mockProactivePermissions(page);
  });

  test('저장 요청이 상세의 updatedAt 을 그대로 싣고, 응답의 새 버전으로 이어 저장해도 충돌하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedJob(page);
    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '버전 작업' })).toBeVisible();

    await editName(page, '이름 A');
    await saveButton(page).click();
    await expect.poll(() => server.puts.length).toBe(1);
    // 서버가 준 문자열 그대로(마이크로초 포함) — Date 로 바꾸면 잘려서 매번 충돌한다
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    await expect(page.getByText('작업이 저장되었습니다.')).toBeVisible();
    await expect(editButton(page)).toBeVisible();

    // 바로 다시 고쳐 저장 — 기준은 저장 응답의 새 버전이어야 한다(자기 저장과 충돌하지 않는다)
    const afterFirst = server.version();
    await editName(page, '이름 B');
    await saveButton(page).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect(server.puts[1].expectedUpdatedAt).toBe(afterFirst);
    await expect(editButton(page)).toBeVisible();
    await expect(conflictDialog(page)).toHaveCount(0);
    expect(server.name()).toBe('이름 B');
  });

  test('다른 탭이 먼저 저장했으면 충돌 창이 뜨고, 「내 변경으로 덮어쓰기」는 같은 내용을 기준 버전 없이 다시 보낸다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedJob(page);
    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '버전 작업' })).toBeVisible();

    await editName(page, '내 이름');
    server.otherTabSaves('다른 탭 이름');
    await saveButton(page).click();

    await expect(conflictDialog(page)).toBeVisible();
    await expect(conflictDialog(page)).toContainText('이 작업은(는) 편집을 시작한 뒤 다른 사용자 또는 다른 탭에서 변경되었습니다');
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    expect(server.name()).toBe('다른 탭 이름');
    await expect(page.locator('#job-name')).toHaveValue('내 이름');

    await conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect('expectedUpdatedAt' in server.puts[1]).toBe(false);
    // 기준 버전만 빠지고 나머지는 첫 저장과 같다(toEqual 은 undefined 속성을 없는 것으로 본다)
    expect(server.puts[1]).toEqual({ ...server.puts[0], expectedUpdatedAt: undefined });
    await expect(conflictDialog(page)).toHaveCount(0);
    await expect(editButton(page)).toBeVisible();
    expect(server.name()).toBe('내 이름');
  });

  test('「다시 불러오기」는 내 변경을 버리고 최신 내용으로 바꾸며(편집 모드 유지), 이어 저장하면 최신 버전을 기준으로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedJob(page);
    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '버전 작업' })).toBeVisible();

    await editName(page, '내 이름');
    server.otherTabSaves('다른 탭 이름');
    await saveButton(page).click();
    await expect(conflictDialog(page)).toBeVisible();

    await conflictDialog(page).getByRole('button', { name: '다시 불러오기' }).click();
    await expect(conflictDialog(page)).toHaveCount(0);
    await expect(saveButton(page)).toBeVisible();
    await expect(page.locator('#job-name')).toHaveValue('다른 탭 이름');

    const latest = server.version();
    await page.locator('#job-name').fill('불러온 뒤 이름');
    await saveButton(page).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect(server.puts[1].expectedUpdatedAt).toBe(latest);
    await expect(editButton(page)).toBeVisible();
    expect(server.name()).toBe('불러온 뒤 이름');
  });

  test('코드 없는 409 는 충돌 창이 아니라 서버 문구 토스트로 안내한다', async ({ authenticatedPage: page }) => {
    await mockVersionedJob(page);
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs/1',
      (route) =>
        route.request().method() === 'PUT'
          ? route.fulfill({
              status: 409,
              contentType: 'application/json',
              body: JSON.stringify({ status: 409, message: '같은 이름의 작업이 이미 있습니다.' }),
            })
          : route.fallback(),
    );
    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '버전 작업' })).toBeVisible();

    await editName(page, '내 이름');
    await saveButton(page).click();
    await expect(page.getByText('같은 이름의 작업이 이미 있습니다.')).toBeVisible();
    await expect(conflictDialog(page)).toHaveCount(0);
  });
});
