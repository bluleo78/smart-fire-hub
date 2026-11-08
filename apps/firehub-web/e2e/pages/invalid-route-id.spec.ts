/**
 * 주소의 id 가 숫자가 아닐 때 — 화면이 깨지지 않고 없는 id 와 같게 안내한다 (WD-434)
 *
 * `Number('abc')` = NaN 이 실행 상세에서는 `NaN !== NaN` 렌더 중 상태 조정으로 무한 렌더(앱 전체 오류 화면)를,
 * API 연결 상세에서는 조회가 꺼진 빈 화면을, 차트·쿼리에서는 `parseInt` 의 NaN 으로 「새로 만들기」 + 「NaN」 글자를,
 * `parseInt('15abc')` = 15 로 다른 항목 편집기를 열었다. 공용 parseRouteId 로 숫자만 받게 고쳤다.
 */

import type { Page } from '@playwright/test';

import { setupAdminAuth, setupApiConnectionListMocks, setupRoleListMocks } from '../fixtures/admin.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupPipelineEditorMocks } from '../fixtures/pipeline.fixture';

/** 주어진 경로의 상세 조회 API 가 불렸는지 기록한다 — 숫자가 아닌 id 로는 조회하지 않아야 한다 */
async function trackRequest(page: Page, pattern: string) {
  const hit = { called: false };
  await page.route(pattern, (route) => {
    hit.called = true;
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{"message":"Not found"}' });
  });
  return hit;
}

test.describe('숫자가 아닌 주소 id (WD-434)', () => {
  test('실행 상세 /pipelines/1/executions/abc — 오류 화면 없이 안내 후 파이프라인으로 돌아간다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    const pageErrors: string[] = [];
    page.on('pageerror', (e) => pageErrors.push(String(e)));
    const execDetail = await trackRequest(page, '**/api/v1/pipelines/1/executions/abc');

    await page.goto('/pipelines/1/executions/abc');

    await expect(page.getByText('실행 정보를 불러오는 데 실패했습니다.')).toBeVisible();
    await expect(page).toHaveURL(/\/pipelines\/1$/);
    await expect(page.getByText('페이지를 불러오는 중 문제가 발생했습니다')).toHaveCount(0);
    expect(pageErrors.filter((e) => /Too many re-renders/.test(e))).toEqual([]);
    expect(execDetail.called).toBe(false);
  });

  test('API 연결 상세 /admin/api-connections/abc — 빈 화면 대신 안내 후 목록으로 간다', async ({
    authenticatedPage: page,
  }) => {
    // 관리 화면 권한 가드 통과용 — api-connections.spec.ts 와 같다
    await setupAdminAuth(page);
    await setupApiConnectionListMocks(page);
    const detail = await trackRequest(page, '**/api/v1/api-connections/abc');

    await page.goto('/admin/api-connections/abc');

    await expect(page.getByText('연결 정보를 불러오는 데 실패했습니다.')).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/api-connections$/);
    expect(detail.called).toBe(false);
  });

  for (const raw of ['abc', '15abc']) {
    test(`차트 /analytics/charts/${raw} — 새 차트·「NaN」·다른 차트 대신 「차트를 찾을 수 없습니다」`, async ({
      authenticatedPage: page,
    }) => {
      // 15abc 를 15 로 읽으면 이 차트가 열린다 — 열리지 않아야 한다
      const chart15 = await trackRequest(page, '**/api/v1/analytics/charts/15');
      await mockApi(page, 'GET', '/api/v1/analytics/queries', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

      await page.goto(`/analytics/charts/${raw}`);

      await expect(page.getByText('차트를 찾을 수 없습니다.')).toBeVisible();
      await expect(page.getByRole('button', { name: '목록으로' })).toBeVisible();
      await expect(page.getByText('새 차트')).toHaveCount(0);
      await expect(page.getByText('NaN')).toHaveCount(0);
      expect(chart15.called).toBe(false);
    });
  }

  for (const raw of ['abc', '15abc']) {
    test(`저장 쿼리 /analytics/queries/${raw} — 새 쿼리 편집기 대신 「쿼리를 찾을 수 없습니다」`, async ({
      authenticatedPage: page,
    }) => {
      const query15 = await trackRequest(page, '**/api/v1/analytics/queries/15');
      await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', { tables: [] });
      await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);

      await page.goto(`/analytics/queries/${raw}`);

      await expect(page.getByText('쿼리를 찾을 수 없습니다.')).toBeVisible();
      await expect(page.getByText('새 쿼리')).toHaveCount(0);
      expect(query15.called).toBe(false);
    });
  }
});

/**
 * 숫자만이 아닌 주소 id (WD-628) — WD-434/568 에서 빠진 상세 화면들
 *
 * `Number('1e1')` = 10 이라 다른 항목(10번)을 열고, `Number('1.5')`·`Number(' 7')` 은 1.5·7 로 읽혀
 * 엉뚱한 id 로 상세 API 를 불렀다. 대시보드는 `parseInt` 라 `15abc`→15, `1e1`·`1.5`→1 을 열었다.
 * 공용 parseRouteId 로 숫자만 받아, 잘못된 id 는 조회 없이 각 화면의 기존 「찾을 수 없음」 처리로 보낸다.
 * 사용자·역할·대시보드는 WD-581 의 화면 안 안내(ResourceNotFound — 주소 유지, 토스트 없음)를 그대로 쓴다.
 */
const BAD_RAW_IDS = ['1e1', '1.5', 'abc', '15abc', '%207'];
/**
 * 잘못 읽힌 id 가 주소에 들어간 형태 — Number/parseInt 결과(10·1.5·1·15·7·NaN)와 원문 그대로.
 * url.pathname 은 늘 퍼센트 인코딩돼 있으므로 공백은 `%20` 형태(`%207`)로만 나타난다.
 */
const BAD_SEGMENT = '(?:10|1\\.5|1|15|7|NaN|1e1|abc|15abc|%207)';

/** 상세 API 경로(와 그 아래 하위 자원 `/<bad>/...`)가 잘못된 id 로 불렸는지 기록한다 — 나머지 mock 뒤에 등록해야 먼저 쓰인다 */
async function trackBadIdRequest(page: Page, prefix: string) {
  const re = new RegExp(`^${prefix}/${BAD_SEGMENT}(?:/.*)?$`);
  const hit = { urls: [] as string[] };
  await page.route(
    (url) => re.test(url.pathname),
    (route) => {
      hit.urls.push(route.request().url());
      return route.fulfill({ status: 404, contentType: 'application/json', body: '{"message":"Not found"}' });
    },
  );
  return hit;
}

/**
 * WD-581 화면 안 「찾을 수 없음」 안내 확인 — 제목(h1)으로 안내가 보이고, 「목록으로」 버튼이 있으며,
 * 목록으로 이동하지 않고 주소(잘못된 id 그대로)가 남아 있고, 실패 토스트를 따로 띄우지 않는다.
 */
async function expectInPageNotFound(page: Page, message: string, path: string) {
  await expect(page.getByRole('heading', { level: 1, name: message })).toBeVisible();
  await expect(page.getByRole('button', { name: '목록으로' })).toBeVisible();
  expect(new URL(page.url()).pathname).toBe(path);
  await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
}

test.describe('숫자만이 아닌 주소 id — 남은 상세 화면 (WD-628)', () => {
  for (const raw of BAD_RAW_IDS) {
    test(`AI 실행 상세 /ai-insights/jobs/1/executions/${raw} — 조회 없이 「실행 정보를 찾을 수 없습니다」`, async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
      const hit = await trackBadIdRequest(page, '/api/v1/proactive/jobs/1/executions');

      await page.goto(`/ai-insights/jobs/1/executions/${raw}`);

      await expect(page.getByTestId('execution-not-found')).toHaveText('실행 정보를 찾을 수 없습니다.');
      expect(hit.urls).toEqual([]);
    });

    test(`리포트 보기 /ai-insights/jobs/1/executions/${raw}/report — 조회 없이 「잘못된 요청입니다」`, async ({
      authenticatedPage: page,
    }) => {
      const hit = await trackBadIdRequest(page, '/api/v1/proactive/jobs/1/executions');

      await page.goto(`/ai-insights/jobs/1/executions/${raw}/report`);

      await expect(page.getByText('잘못된 요청입니다.')).toBeVisible();
      await expect(page.getByRole('heading', { name: '리포트', exact: true })).toBeVisible();
      expect(hit.urls).toEqual([]);
    });

    test(`리포트 양식 상세 /ai-insights/templates/${raw} — 조회 없이 「존재하지 않는 템플릿」 후 목록`, async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
      const hit = await trackBadIdRequest(page, '/api/v1/proactive/templates');

      await page.goto(`/ai-insights/templates/${raw}`);

      await expect(page.locator('[data-sonner-toast]').filter({ hasText: '존재하지 않는 템플릿입니다.' })).toBeVisible();
      await expect(page).toHaveURL(/\/ai-insights\/reports\?tab=templates$/);
      expect(hit.urls).toEqual([]);
    });

    test(`사용자 상세 /admin/users/${raw} — 조회 없이 화면 안 「사용자를 찾을 수 없습니다」`, async ({
      authenticatedPage: page,
    }) => {
      await setupAdminAuth(page);
      const hit = await trackBadIdRequest(page, '/api/v1/users');

      await page.goto(`/admin/users/${raw}`);

      await expectInPageNotFound(page, '사용자를 찾을 수 없습니다.', `/admin/users/${raw}`);
      expect(hit.urls).toEqual([]);
    });

    test(`역할 상세 /admin/roles/${raw} — 조회 없이 화면 안 「역할을 찾을 수 없습니다」`, async ({
      authenticatedPage: page,
    }) => {
      await setupAdminAuth(page);
      await setupRoleListMocks(page);
      const hit = await trackBadIdRequest(page, '/api/v1/roles');

      await page.goto(`/admin/roles/${raw}`);

      await expectInPageNotFound(page, '역할을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.', `/admin/roles/${raw}`);
      expect(hit.urls).toEqual([]);
    });

    test(`API 연결 상세 /admin/api-connections/${raw} — 조회 없이 안내 후 목록`, async ({ authenticatedPage: page }) => {
      await setupAdminAuth(page);
      await setupApiConnectionListMocks(page);
      const hit = await trackBadIdRequest(page, '/api/v1/api-connections');

      await page.goto(`/admin/api-connections/${raw}`);

      await expect(page.getByText('연결 정보를 불러오는 데 실패했습니다.')).toBeVisible();
      await expect(page).toHaveURL(/\/admin\/api-connections$/);
      expect(hit.urls).toEqual([]);
    });

    test(`대시보드 /analytics/dashboards/${raw} — 조회 없이 「대시보드를 찾을 수 없습니다」`, async ({
      authenticatedPage: page,
    }) => {
      const hit = await trackBadIdRequest(page, '/api/v1/analytics/dashboards');

      await page.goto(`/analytics/dashboards/${raw}`);

      await expectInPageNotFound(page, '대시보드를 찾을 수 없습니다.', `/analytics/dashboards/${raw}`);
      expect(hit.urls).toEqual([]);
    });
  }
});
