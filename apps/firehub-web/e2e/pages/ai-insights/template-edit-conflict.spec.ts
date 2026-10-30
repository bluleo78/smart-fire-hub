/**
 * 리포트 양식 편집 — 동시 편집 충돌(낙관적 잠금) E2E (WD-364)
 *
 * 결함: 두 사람(또는 두 탭)이 같은 양식을 고쳐 저장하면 나중 저장이 먼저 저장된 변경을 경고 없이 되돌렸다 — 섹션은 전체 교체라 통째로 사라졌다.
 * 이제 저장 요청이 편집 기준 버전(expectedUpdatedAt)을 싣고, 서버가 409 EDIT_CONFLICT 로 거부하면
 * 충돌 안내 창(다시 불러오기 / 내 변경으로 덮어쓰기)이 뜬다. 수정 응답은 204 가 아니라 200 {updatedAt} 이다.
 *
 * 서버는 버전을 가진 상태 기반 모킹이다 — 기준 버전이 다르면 실제 서버처럼 409 를 돌려준다.
 */

import type { Page, Route } from '@playwright/test';

import type { UpdateReportTemplateRequest } from '@/api/proactive';

import { createTemplate } from '../../factories/ai-insight.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 서버 버전 — 마이크로초까지 있는 문자열을 그대로 되돌려 보내는지 확인하려고 소수점 6자리를 쓴다 */
const V1 = '2026-10-30T10:00:00.123456';

/** 버전을 가진 가짜 양식 서버. PUT 의 기준 버전이 다르면 409 EDIT_CONFLICT, 같거나 없으면 반영하고 새 버전만 돌려준다 */
async function mockVersionedTemplate(page: Page) {
  let version = V1;
  let seq = 1;
  let description = 'base-desc';
  let style = 'base-style';
  const puts: UpdateReportTemplateRequest[] = [];

  const detail = () =>
    createTemplate({ id: 210, name: '버전 양식', builtin: false, description, style, updatedAt: version });
  /** 다른 탭(다른 사람)이 먼저 저장했다 — 서버 내용(설명·작성 스타일)과 버전이 바뀐다 */
  const otherTabSaves = (desc: string, nextStyle: string) => {
    description = desc;
    style = nextStyle;
    seq += 1;
    version = `2026-10-30T10:00:0${seq}.654321`;
  };

  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
  await page.route(
    (url) => url.pathname === '/api/v1/proactive/templates',
    (route) =>
      route.request().method() === 'GET'
        ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([detail()]) })
        : route.fallback(),
  );
  await page.route(
    (url) => url.pathname === '/api/v1/proactive/templates/210',
    async (route: Route) => {
      const method = route.request().method();
      if (method === 'PUT') {
        const body = route.request().postDataJSON() as UpdateReportTemplateRequest;
        puts.push(body);
        if ('expectedUpdatedAt' in body && body.expectedUpdatedAt !== version) {
          return route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 409,
              code: 'EDIT_CONFLICT',
              message: '이 리포트 양식은 편집을 시작한 뒤 다른 곳에서 변경되었습니다. 다시 불러온 뒤 저장하세요.',
            }),
          });
        }
        description = body.description ?? description;
        style = body.style ?? style;
        seq += 1;
        version = `2026-10-30T10:00:0${seq}.111111`;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ updatedAt: version }) });
      }
      if (method !== 'GET') return route.fallback();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detail()) });
    },
  );

  return { puts, otherTabSaves, version: () => version, description: () => description };
}

const conflictDialog = (page: Page) => page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });
const editButton = (page: Page) => page.getByRole('button', { name: '편집' });
const saveButton = (page: Page) => page.getByRole('button', { name: '저장', exact: true });

/** 상세를 열고 편집 모드로 들어가 설명을 바꾼다 */
async function editDesc(page: Page, value: string) {
  await editButton(page).click();
  await expect(page.locator('#tpl-desc')).toBeVisible();
  await page.locator('#tpl-desc').fill(value);
}

test.describe('리포트 양식 편집 — 동시 편집 충돌 (WD-364)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // 편집 버튼은 쓰기 권한이 있을 때만 보인다(WD-133)
    await mockProactivePermissions(page);
  });

  test('저장 요청이 상세의 updatedAt 을 그대로 싣고, 응답의 새 버전으로 이어 저장해도 충돌하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedTemplate(page);
    await page.goto('/ai-insights/templates/210');
    await expect(page.getByRole('heading', { name: '버전 양식' })).toBeVisible({ timeout: 10000 });

    await editDesc(page, 'first');
    await saveButton(page).click();
    await expect.poll(() => server.puts.length).toBe(1);
    // 서버가 준 문자열 그대로(마이크로초 포함) — Date 로 바꾸면 잘려서 매번 충돌한다
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    await expect(page.getByText('템플릿이 수정되었습니다.')).toBeVisible();
    await expect(editButton(page)).toBeVisible();

    // 바로 다시 고쳐 저장 — 기준은 저장 응답의 새 버전이어야 한다(자기 저장과 충돌하지 않는다)
    const afterFirst = server.version();
    await editDesc(page, 'second');
    await saveButton(page).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect(server.puts[1].expectedUpdatedAt).toBe(afterFirst);
    await expect(editButton(page)).toBeVisible();
    await expect(conflictDialog(page)).toHaveCount(0);
    expect(server.description()).toBe('second');
  });

  test('다른 탭이 먼저 저장했으면 충돌 창이 뜨고, 「내 변경으로 덮어쓰기」는 같은 내용(섹션 포함)을 기준 버전 없이 다시 보낸다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedTemplate(page);
    await page.goto('/ai-insights/templates/210');
    await expect(page.getByRole('heading', { name: '버전 양식' })).toBeVisible({ timeout: 10000 });

    await editDesc(page, 'mine');
    server.otherTabSaves('other-tab', 'other-style');
    await saveButton(page).click();

    await expect(conflictDialog(page)).toBeVisible();
    await expect(conflictDialog(page)).toContainText('이 리포트 양식은(는) 편집을 시작한 뒤 다른 사용자 또는 다른 탭에서 변경되었습니다');
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    expect(server.description()).toBe('other-tab');
    // 편집 모드는 그대로 — 내 변경이 화면에 남아 있다
    await expect(page.locator('#tpl-desc')).toHaveValue('mine');

    await conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect('expectedUpdatedAt' in server.puts[1]).toBe(false);
    // 기준 버전만 빠지고 나머지는 첫 저장과 같다(toEqual 은 undefined 속성을 없는 것으로 본다)
    expect(server.puts[1]).toEqual({ ...server.puts[0], expectedUpdatedAt: undefined });
    await expect(conflictDialog(page)).toHaveCount(0);
    await expect(editButton(page)).toBeVisible();
    expect(server.description()).toBe('mine');
  });

  test('「다시 불러오기」는 내 변경을 버리고 이름·설명·작성 스타일을 최신으로 바꾸며(편집 모드 유지), 이어 저장하면 최신 버전을 보낸다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedTemplate(page);
    await page.goto('/ai-insights/templates/210');
    await expect(page.getByRole('heading', { name: '버전 양식' })).toBeVisible({ timeout: 10000 });

    await editDesc(page, 'mine');
    await page.locator('#tpl-style').fill('my-style');
    server.otherTabSaves('other-tab', 'other-style');
    await saveButton(page).click();
    await expect(conflictDialog(page)).toBeVisible();

    await conflictDialog(page).getByRole('button', { name: '다시 불러오기' }).click();
    await expect(conflictDialog(page)).toHaveCount(0);
    await expect(saveButton(page)).toBeVisible();
    await expect(page.locator('#tpl-desc')).toHaveValue('other-tab');
    // 섹션·스타일은 편집 중 재조회로 바뀌지 않는 상태라 따로 채워야 한다
    await expect(page.locator('#tpl-style')).toHaveValue('other-style');

    const latest = server.version();
    await page.locator('#tpl-desc').fill('after-reload');
    await saveButton(page).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect(server.puts[1].expectedUpdatedAt).toBe(latest);
    expect(server.puts[1].style).toBe('other-style');
    await expect(editButton(page)).toBeVisible();
    expect(server.description()).toBe('after-reload');
  });

  test('캐시가 있는 채로 다시 들어온 상세(앱 안 이동)도 기준 버전과 섹션을 채워 보낸다', async ({ authenticatedPage: page }) => {
    // 상세 캐시가 이미 있으면 첫 렌더의 양식이 이전 값 추적 초깃값과 같아 섹션·기준 버전 채우기를 건너뛰었다 —
    // 기준 버전이 null 로 나가 서버가 비교하지 않고(조용한 덮어쓰기), 섹션도 빈 배열로 나가 통째로 지워질 수 있었다.
    const server = await mockVersionedTemplate(page);
    await page.goto('/ai-insights/templates/210');
    await expect(page.getByRole('heading', { name: '버전 양식' })).toBeVisible({ timeout: 10000 });

    // 새로고침 없이 앱 안에서 다른 화면으로 갔다가 같은 양식으로 돌아온다(React Router 는 popstate 로 위치를 다시 읽는다)
    const spaNavigate = (path: string) =>
      page.evaluate((to) => {
        window.history.pushState({}, '', to);
        window.dispatchEvent(new PopStateEvent('popstate'));
      }, path);
    await spaNavigate('/ai-insights/jobs');
    await expect(page.getByRole('heading', { name: '버전 양식' })).toHaveCount(0);
    await spaNavigate('/ai-insights/templates/210');
    await expect(page.getByRole('heading', { name: '버전 양식' })).toBeVisible();

    await editDesc(page, 'revisit');
    await saveButton(page).click();
    await expect.poll(() => server.puts.length).toBe(1);
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    // 섹션도 서버 값 그대로 실려야 한다(빈 배열이면 저장이 섹션을 지운다)
    expect(server.puts[0].sections?.length).toBe(2);
  });

  test('코드 없는 409 는 충돌 창이 아니라 서버 문구 토스트로 안내한다', async ({ authenticatedPage: page }) => {
    await mockVersionedTemplate(page);
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/templates/210',
      (route) =>
        route.request().method() === 'PUT'
          ? route.fulfill({
              status: 409,
              contentType: 'application/json',
              body: JSON.stringify({ status: 409, message: '같은 이름의 양식이 이미 있습니다.' }),
            })
          : route.fallback(),
    );
    await page.goto('/ai-insights/templates/210');
    await expect(page.getByRole('heading', { name: '버전 양식' })).toBeVisible({ timeout: 10000 });

    await editDesc(page, 'mine');
    await saveButton(page).click();
    await expect(page.getByText('같은 이름의 양식이 이미 있습니다.')).toBeVisible();
    await expect(conflictDialog(page)).toHaveCount(0);
  });
});
