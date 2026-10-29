/**
 * 파이프라인 편집 — 동시 편집 충돌(낙관적 잠금) E2E (WD-353)
 *
 * 결함: 두 사람(또는 두 탭)이 같은 파이프라인을 고쳐 저장하면 나중 저장이 먼저 저장된 변경을 경고 없이 되돌렸다.
 * 이제 저장 요청이 편집 기준 버전(expectedUpdatedAt)을 싣고, 서버가 다르면 409 EDIT_CONFLICT 로 거부하면
 * 화면이 충돌 안내 창(다시 불러오기 / 내 변경으로 덮어쓰기)을 띄운다.
 *
 * 서버는 버전을 가진 상태 기반 모킹이다 — 기준 버전이 다르면 실제 서버처럼 409 를 돌려준다.
 */

import type { Page, Route } from '@playwright/test';

import type { PipelineDetailResponse, UpdatePipelineRequest } from '@/types/pipeline';

import { createExecution, createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

/** 서버 버전 — 마이크로초까지 있는 문자열을 그대로 되돌려 보내는지 확인하려고 소수점 6자리를 쓴다 */
const V1 = '2026-10-30T10:00:00.123456';

/** 버전을 가진 가짜 서버. PUT 의 기준 버전이 다르면 409 EDIT_CONFLICT, 같거나 없으면 반영하고 버전을 올린다 */
async function mockVersionedServer(page: Page, opts: { runningThenCompleted?: boolean } = {}) {
  let version = V1;
  let seq = 1;
  let sqlDesc: string | null = 'base-desc';
  const puts: UpdatePipelineRequest[] = [];
  let detailCalls = 0;
  let listCalls = 0;

  const detail = (): PipelineDetailResponse =>
    createPipelineDetail({
      id: 1,
      updatedAt: version,
      steps: [createStep({ id: 11, name: 'sql1', description: sqlDesc, scriptType: 'SQL', scriptContent: 'SELECT 1' })],
    });
  /** 다른 탭(다른 사람)이 먼저 저장했다 — 서버 내용과 버전이 바뀐다 */
  const otherTabSaves = (desc: string) => {
    sqlDesc = desc;
    seq += 1;
    version = `2026-10-30T10:00:0${seq}.654321`;
  };

  await mockApi(page, 'GET', '/api/v1/pipelines/1/triggers', []);
  await mockApi(page, 'GET', '/api/v1/pipelines/1/trigger-events', []);
  await mockApi(page, 'GET', '/api/v1/datasets', { content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0 });
  await page.route(
    (url) => url.pathname === '/api/v1/pipelines/1/executions',
    (route) => {
      listCalls += 1;
      // 실행 목록이 RUNNING → COMPLETED 로 바뀌면 상세가 재조회된다(#733) — 편집 중 재조회를 일으키는 데 쓴다
      const list = opts.runningThenCompleted
        ? [createExecution({ id: 475, status: listCalls === 1 ? 'RUNNING' : 'COMPLETED' })]
        : [];
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(list) });
    },
  );
  await page.route(
    (url) => url.pathname === '/api/v1/pipelines/1',
    async (route: Route) => {
      const method = route.request().method();
      if (method === 'PUT') {
        const body = route.request().postDataJSON() as UpdatePipelineRequest;
        puts.push(body);
        if ('expectedUpdatedAt' in body && body.expectedUpdatedAt !== version) {
          return route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 409,
              code: 'EDIT_CONFLICT',
              message: '이 파이프라인은 편집을 시작한 뒤 다른 곳에서 변경되었습니다. 다시 불러온 뒤 저장하세요.',
            }),
          });
        }
        sqlDesc = body.steps.find((s) => s.name === 'sql1')?.description ?? null;
        seq += 1;
        version = `2026-10-30T10:00:0${seq}.111111`;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ updatedAt: version }) });
      }
      if (method !== 'GET') return route.fallback();
      detailCalls += 1;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(detail()) });
    },
  );

  return {
    puts,
    otherTabSaves,
    version: () => version,
    sqlDesc: () => sqlDesc,
    detailCalls: () => detailCalls,
  };
}

const node = (page: Page) => page.locator('.react-flow__node', { hasText: 'sql1' });
const stepDesc = (page: Page) => page.locator('#step-description');
const saveButton = (page: Page) => page.getByRole('button', { name: '저장', exact: true });
const conflictDialog = (page: Page) => page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });

/** 수정 모드로 들어가 sql1 설명을 바꾼다 */
async function editDesc(page: Page, value: string) {
  await page.getByRole('button', { name: '수정' }).click();
  await node(page).click();
  await stepDesc(page).fill(value);
}

test.describe('파이프라인 편집 — 동시 편집 충돌 (WD-353)', () => {
  // 수정 버튼은 권한이 있을 때만 보인다(WD-219)
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('저장 요청이 상세의 updatedAt 을 그대로 싣고, 응답의 새 버전으로 이어 저장해도 충돌하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedServer(page);
    await page.goto('/pipelines/1');

    await editDesc(page, 'first');
    await saveButton(page).click();
    await expect.poll(() => server.puts.length).toBe(1);
    // 서버가 준 문자열 그대로(마이크로초 포함) — Date 로 바꾸면 잘려서 매번 충돌한다
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();

    // 바로 다시 고쳐 저장 — 기준은 저장 응답의 새 버전이어야 한다(자기 저장과 충돌하지 않는다)
    const afterFirst = server.version();
    await editDesc(page, 'second');
    await saveButton(page).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect(server.puts[1].expectedUpdatedAt).toBe(afterFirst);
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
    await expect(conflictDialog(page)).toHaveCount(0);
    expect(server.sqlDesc()).toBe('second');
  });

  test('다른 탭이 먼저 저장했으면 충돌 창이 뜨고, 「내 변경으로 덮어쓰기」는 기준 버전 없이 다시 저장한다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedServer(page);
    await page.goto('/pipelines/1');

    await editDesc(page, 'mine');
    server.otherTabSaves('other-tab');
    await saveButton(page).click();

    await expect(conflictDialog(page)).toBeVisible();
    await expect(conflictDialog(page)).toContainText('이 파이프라인은(는) 편집을 시작한 뒤 다른 사용자 또는 다른 탭에서 변경되었습니다');
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    // 아직 아무것도 덮어쓰지 않았다 — 먼저 저장된 변경이 남아 있다
    expect(server.sqlDesc()).toBe('other-tab');

    await conflictDialog(page).getByRole('button', { name: '내 변경으로 덮어쓰기' }).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect('expectedUpdatedAt' in server.puts[1]).toBe(false);
    expect(server.puts[1].steps[0].description).toBe('mine');
    await expect(conflictDialog(page)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
    expect(server.sqlDesc()).toBe('mine');
  });

  test('「다시 불러오기」는 내 변경을 버리고 최신 내용으로 바꾸며, 이어 저장하면 최신 버전을 기준으로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedServer(page);
    await page.goto('/pipelines/1');

    await editDesc(page, 'mine');
    server.otherTabSaves('other-tab');
    await saveButton(page).click();
    await expect(conflictDialog(page)).toBeVisible();

    await conflictDialog(page).getByRole('button', { name: '다시 불러오기' }).click();
    await expect(conflictDialog(page)).toHaveCount(0);
    // 편집 모드는 그대로, 화면은 최신 서버 내용
    await expect(saveButton(page)).toBeVisible();
    await node(page).click();
    await expect(stepDesc(page)).toHaveValue('other-tab');

    const latest = server.version();
    await stepDesc(page).fill('after-reload');
    await saveButton(page).click();
    await expect.poll(() => server.puts.length).toBe(2);
    expect(server.puts[1].expectedUpdatedAt).toBe(latest);
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
    expect(server.sqlDesc()).toBe('after-reload');
  });

  test('편집 중 재조회로 새 버전이 도착해도 기준은 편집을 시작한 버전이라 충돌이 감지된다', async ({
    authenticatedPage: page,
  }) => {
    const server = await mockVersionedServer(page, { runningThenCompleted: true });
    await page.goto('/pipelines/1');

    await editDesc(page, 'mine');
    server.otherTabSaves('other-tab');
    // 실행 완료 폴링 뒤 상세가 다시 조회된다 — 미저장 변경이 있으니 화면은 그대로여야 한다
    await expect.poll(() => server.detailCalls(), { timeout: 15_000 }).toBeGreaterThanOrEqual(2);
    await page.waitForTimeout(300);
    await expect(stepDesc(page)).toHaveValue('mine');

    await saveButton(page).click();
    await expect(conflictDialog(page)).toBeVisible();
    expect(server.puts[0].expectedUpdatedAt).toBe(V1);
    expect(server.sqlDesc()).toBe('other-tab');
  });

  test('코드 없는 409(이름 중복)는 충돌 창이 아니라 서버 문구 토스트로 안내한다', async ({ authenticatedPage: page }) => {
    await mockVersionedServer(page);
    // 나중에 등록한 라우트가 먼저 처리된다 — PUT 만 이름 중복 409 로 바꾼다
    await page.route(
      (url) => url.pathname === '/api/v1/pipelines/1',
      (route) =>
        route.request().method() === 'PUT'
          ? route.fulfill({
              status: 409,
              contentType: 'application/json',
              body: JSON.stringify({ status: 409, message: '같은 이름의 파이프라인이 이미 있습니다.' }),
            })
          : route.fallback(),
    );
    await page.goto('/pipelines/1');

    await editDesc(page, 'mine');
    await saveButton(page).click();
    await expect(page.getByText('같은 이름의 파이프라인이 이미 있습니다.')).toBeVisible();
    await expect(conflictDialog(page)).toHaveCount(0);
  });
});
