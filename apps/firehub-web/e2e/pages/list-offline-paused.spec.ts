import type { Page, Route } from '@playwright/test';

import { offlineListMessage } from '../../src/hooks/useOnlineStatus';
import { createApiConnection, createAuditLog } from '../factories/admin.factory';
import { createAdminJobRow, createJob, createJobExecution, createReport } from '../factories/ai-insight.factory';
import { createChartListItem, createDashboardListItem, createSavedQueryListItem } from '../factories/analytics.factory';
import { createUser } from '../factories/auth.factory';
import { createCategory } from '../factories/dataset.factory';
import { createPipeline, createPipelines } from '../factories/pipeline.factory';
import { createSynonymReviewItem } from '../factories/reviewItem.factory';
import { setupAdminAuth, setupApiConnectionListMocks, setupAuditLogMocks } from '../fixtures/admin.fixture';
import { setupChartListMocks, setupDashboardListMocks, setupQueryListMocks } from '../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupDatasetMocks } from '../fixtures/dataset.fixture';
import { enterWithListError } from '../fixtures/list-retry';
import { setupPipelineMocks } from '../fixtures/pipeline.fixture';

/** JSON 본문으로 라우트를 응답한다(기본 200) — 반복되는 route.fulfill 보일러플레이트를 줄인다 */
function fulfillJson(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

/**
 * 연결 대기 안내가 role=status 로 보이고(실패가 아닌 중립 안내라 alert 에는 없다) 빈 상태 문구는 없는지 확인한다.
 * label 은 offlineListMessage 의 목록 이름(공용 문구, WD-982·WD-971). 안내 문구를 돌려줘 호출한 쪽이 연결 복구 뒤 사라짐을 확인할 수 있게 한다.
 */
async function expectOfflineWaiting(page: Page, label: string, emptyText: string) {
  const text = offlineListMessage(label);
  await expect(page.getByRole('status').filter({ hasText: text })).toBeVisible();
  await expect(page.getByRole('alert').filter({ hasText: text })).toHaveCount(0);
  await expect(page.getByText(emptyText)).toHaveCount(0);
  return text;
}

/**
 * pathname 의 GET 을 setOk() 전까지 500 으로 실패시키고 그 뒤엔 okBody() 를 200 으로 돌려준다.
 * override 가 값을 돌려주면(특정 쿼리 파라미터 분기 등) 그 본문을 항상 200 으로 응답한다.
 */
async function failUntilOk(page: Page, pathname: string, okBody: () => unknown, override?: (url: URL) => unknown) {
  let ok = false;
  await page.route(
    (url) => url.pathname === pathname,
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const overridden = override?.(new URL(route.request().url()));
      if (overridden !== undefined) return fulfillJson(route, overridden);
      return ok ? fulfillJson(route, okBody()) : fulfillJson(route, { message: '서버 오류' }, 500);
    },
  );
  return { setOk: () => { ok = true; } };
}

/**
 * 분석·API 연결 목록 — 데이터 없이 오프라인으로 멈추면 빈 안내 대신 연결 대기 안내 (WD-971)
 *
 * 첫 조회가 실패한 뒤 오프라인에서 「다시 시도」를 누르면 쿼리가 데이터 없이 paused 라 로딩도 실패도 아니어서
 * 「…가 없습니다」(+ 만들기 버튼)로 떨어졌다. WD-966(데이터셋 목록)과 같이 연결 대기 안내(role=status)를 보이고,
 * 연결이 돌아오면 자동으로 다시 불러와 목록을 채운다.
 */
const cases = [
  {
    name: '쿼리 목록',
    pagePath: '/analytics/queries',
    apiPath: '/api/v1/analytics/queries',
    setup: (page: Page) => setupQueryListMocks(page, 0),
    success: () => createPageResponse([createSavedQueryListItem({ id: 1, name: '대기 뒤 쿼리' })]),
    rowName: '대기 뒤 쿼리',
    label: '쿼리',
    emptyText: '저장된 쿼리가 없습니다.',
    createButton: '새 쿼리 만들기',
    admin: false,
  },
  {
    name: '차트 목록',
    pagePath: '/analytics/charts',
    apiPath: '/api/v1/analytics/charts',
    setup: (page: Page) => setupChartListMocks(page, 0),
    success: () => createPageResponse([createChartListItem({ id: 1, name: '대기 뒤 차트' })]),
    rowName: '대기 뒤 차트',
    label: '차트',
    emptyText: '차트가 없습니다.',
    createButton: '새 차트 만들기',
    admin: false,
  },
  {
    name: '대시보드 목록',
    pagePath: '/analytics/dashboards',
    apiPath: '/api/v1/analytics/dashboards',
    setup: (page: Page) => setupDashboardListMocks(page, 0),
    success: () => createPageResponse([createDashboardListItem({ id: 1, name: '대기 뒤 대시보드' })]),
    rowName: '대기 뒤 대시보드',
    label: '대시보드',
    emptyText: '대시보드가 없습니다.',
    createButton: '새 대시보드 만들기',
    admin: false,
  },
  {
    name: 'API 연결 목록',
    pagePath: '/admin/api-connections',
    apiPath: '/api/v1/api-connections',
    setup: (page: Page) => setupApiConnectionListMocks(page),
    success: () => [createApiConnection({ id: 1, name: '대기 뒤 연결' })],
    rowName: '대기 뒤 연결',
    label: 'API 연결',
    emptyText: '등록된 API 연결이 없습니다.',
    createButton: '새 API 연결 만들기',
    admin: true,
  },
];

for (const c of cases) {
  test(`${c.name} — 데이터 없이 오프라인으로 멈추면 빈 안내 대신 연결 대기 안내, 연결되면 채운다 (WD-971)`, async ({ authenticatedPage: page }) => {
    if (c.admin) await setupAdminAuth(page);
    await c.setup(page);
    const { setOutcome } = await enterWithListError(page, { apiPath: c.apiPath, pagePath: c.pagePath });
    setOutcome({ status: 200, body: c.success() });

    await page.context().setOffline(true);
    await page.getByRole('button', { name: '다시 시도' }).click();

    const text = await expectOfflineWaiting(page, c.label, c.emptyText);
    await expect(page.getByRole('button', { name: c.createButton })).toHaveCount(0);

    await page.context().setOffline(false);
    await expect(page.getByText(c.rowName, { exact: true })).toBeVisible();
    await expect(page.getByText(text)).toHaveCount(0);
  });
}

/**
 * 사용자·감사 로그·카테고리·파이프라인 목록 — 같은 오프라인 멈춤 (WD-971)
 *
 * 이 화면들엔 「다시 시도」가 없거나(사용자·감사 로그·카테고리) 재시도 중엔 오류 행이 우선해(파이프라인),
 * 캐시 없는 쿼리 키(검색어·쪽 변경 또는 오류로 남은 키로의 SPA 이동)를 오프라인에서 만들어 멈춤을 재현한다.
 */
test('사용자 목록 — 오프라인에서 검색어를 바꾸면 빈 안내 대신 연결 대기 안내, 연결되면 채운다 (WD-971)', async ({ authenticatedPage: page }) => {
  await setupAdminAuth(page);
  await page.route(
    (url) => url.pathname === '/api/v1/users',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const searched = new URL(route.request().url()).searchParams.has('search');
      const users = searched ? [createUser({ id: 9, name: '대기 뒤 사용자', username: 'later' })] : [createUser({ id: 1, name: '처음 사용자' })];
      return fulfillJson(route, createPageResponse(users));
    },
  );
  await page.goto('/admin/users');
  await expect(page.getByText('처음 사용자', { exact: true })).toBeVisible();

  await page.context().setOffline(true);
  await page.getByPlaceholder('이름 또는 아이디로 검색...').fill('later');
  const text = await expectOfflineWaiting(page, '사용자', '사용자가 없습니다.');

  await page.context().setOffline(false);
  await expect(page.getByText('대기 뒤 사용자', { exact: true })).toBeVisible();
  await expect(page.getByText(text)).toHaveCount(0);
});

test('감사 로그 — 오프라인에서 검색어를 바꾸면 결과 없음 대신 연결 대기 안내, 연결되면 채운다 (WD-971)', async ({ authenticatedPage: page }) => {
  await setupAdminAuth(page);
  await setupAuditLogMocks(page, 3);
  await page.route(
    (url) => url.pathname === '/api/v1/admin/audit-logs',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const searched = new URL(route.request().url()).searchParams.has('search');
      const logs = searched ? [createAuditLog({ id: 99, description: '대기 뒤 감사 로그' })] : [createAuditLog({ id: 1, description: '처음 감사 로그' })];
      return fulfillJson(route, createPageResponse(logs));
    },
  );
  await page.goto('/admin/audit-logs');
  await expect(page.getByText('처음 감사 로그', { exact: true })).toBeVisible();

  await page.context().setOffline(true);
  await page.getByPlaceholder('설명으로 검색...').fill('later');
  const text = await expectOfflineWaiting(page, '감사 로그', '감사 로그가 없습니다.');

  await page.context().setOffline(false);
  await expect(page.getByText('대기 뒤 감사 로그', { exact: true })).toBeVisible();
  await expect(page.getByText(text)).toHaveCount(0);
});

test('카테고리 목록 — 오류로 비어 있던 키를 오프라인에서 다시 열면 빈 안내 대신 연결 대기 안내, 연결되면 채운다 (WD-971)', async ({ authenticatedPage: page }) => {
  await setupDatasetMocks(page);
  const { setOk } = await failUntilOk(page, '/api/v1/dataset-categories', () => [createCategory({ id: 1, name: '대기 뒤 카테고리' })]);
  // 오류로 데이터 없는 카테고리 키와 화면 조각을 온라인에서 먼저 만든 뒤 다른 화면으로 나갔다가
  await page.goto('/data/categories');
  // 앱이 첫 실패를 한 번 재시도한 뒤 최종 오류가 되도록 오류 행(다시 시도)이 보일 때까지 기다린다
  await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible();
  await page.getByRole('link', { name: '데이터셋', exact: true }).first().click();
  // 데이터셋 화면 조각이 내려받아지고 그려질 때까지 기다린다 — 오프라인에서 지연 조각을 못 받아 화면 오류가 나지 않게
  await expect(page.getByText('데이터셋 1', { exact: true })).toBeVisible();

  // 오프라인에서 되돌아오면 같은 키의 재조회가 데이터 없이 대기한다
  await page.context().setOffline(true);
  await page.getByRole('link', { name: '카테고리', exact: true }).first().click();
  const text = await expectOfflineWaiting(page, '카테고리', '카테고리가 없습니다.');
  // 오프라인 대기 중에는 「0개」 도 그리지 않는다 (WD-1035)
  await expect(page.getByText(/^\d+개$/)).toHaveCount(0);

  setOk();
  await page.context().setOffline(false);
  await expect(page.getByText('대기 뒤 카테고리', { exact: true })).toBeVisible();
  await expect(page.getByText(text)).toHaveCount(0);
});

test('파이프라인 목록 — 오프라인에서 다음 쪽으로 가면 빈 안내 대신 연결 대기 안내, 연결되면 채운다 (WD-971)', async ({ authenticatedPage: page }) => {
  await setupPipelineMocks(page, 10);
  await page.route(
    (url) => url.pathname === '/api/v1/pipelines',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const second = new URL(route.request().url()).searchParams.get('page') === '1';
      const body = second
        ? createPageResponse([createPipeline({ id: 11, name: '대기 뒤 파이프라인' })], { page: 1, totalElements: 11, totalPages: 2 })
        : createPageResponse(createPipelines(10), { totalElements: 11, totalPages: 2 });
      return fulfillJson(route, body);
    },
  );
  await page.goto('/pipelines');
  await expect(page.getByRole('table', { name: '파이프라인 목록' })).toBeVisible();
  await expect(page.getByRole('button', { name: '다음 페이지' })).toBeEnabled();

  await page.context().setOffline(true);
  await page.getByRole('button', { name: '다음 페이지' }).click();
  const text = await expectOfflineWaiting(page, '파이프라인', '파이프라인이 없습니다.');
  await expect(page.getByRole('link', { name: '새 파이프라인 만들기' })).toHaveCount(0);

  await page.context().setOffline(false);
  await expect(page.getByText('대기 뒤 파이프라인', { exact: true })).toBeVisible();
  await expect(page.getByText(text)).toHaveCount(0);
});

/**
 * AI 인사이트·AI 검수 목록 — 같은 오프라인 멈춤 (WD-971)
 *
 * 이 화면들은 탭 안에 있어, 오류로 데이터 없이 남은 쿼리 키를 다른 탭으로 나갔다가 오프라인에서 되돌아와 재현한다.
 * 연결 대기 중에는 「…가 없습니다」(+ 만들기 버튼) 대신 role=status 안내가 보이고, 연결되면 자동으로 채운다.
 */
const insightCases = [
  {
    name: '스마트 작업 목록',
    path: '/ai-insights/jobs',
    apiPath: '/api/v1/proactive/jobs',
    errorText: '작업 목록을 불러오지 못했습니다',
    leaveTab: '워크스페이스 전체',
    returnTab: '내 작업',
    label: '스마트 작업',
    emptyText: '스마트 작업 없음',
    success: () => [createJob({ id: 1, name: '대기 뒤 작업' })],
    row: (page: Page) => page.getByText('대기 뒤 작업').first(),
    extraMocks: async (page: Page) => {
      await mockApi(page, 'GET', '/api/v1/proactive/admin/jobs', [createAdminJobRow()]);
    },
  },
  {
    name: '워크스페이스 스마트 작업 목록',
    path: '/ai-insights/jobs?view=workspace',
    apiPath: '/api/v1/proactive/admin/jobs',
    errorText: '불러오지 못했습니다',
    leaveTab: '내 작업',
    returnTab: '워크스페이스 전체',
    label: '워크스페이스 스마트 작업',
    emptyText: '워크스페이스에 스마트 작업이 없습니다.',
    success: () => [createAdminJobRow({ id: 10, name: '대기 뒤 남의 작업' })],
    row: (page: Page) => page.getByText('대기 뒤 남의 작업').first(),
    extraMocks: async (page: Page) => {
      await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob()]);
    },
  },
  {
    name: '실행 이력',
    path: '/ai-insights/jobs/1?tab=executions',
    apiPath: '/api/v1/proactive/jobs/1/executions',
    errorText: '실행 이력을 불러오지 못했습니다.',
    leaveTab: '개요',
    returnTab: '실행 이력',
    label: '실행 이력',
    emptyText: '실행 이력이 없습니다.',
    success: () => [createJobExecution({ id: 7, jobId: 1 })],
    row: (page: Page) => page.locator('a[href="/ai-insights/jobs/1/executions/7"]').first(),
    extraMocks: async (page: Page) => {
      await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1 }));
      await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
      await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);
    },
  },
  {
    name: '리포트 목록',
    path: '/ai-insights/reports',
    apiPath: '/api/v1/proactive/reports',
    errorText: '리포트 목록을 불러오지 못했습니다.',
    leaveTab: '리포트 양식',
    returnTab: '생성된 리포트',
    label: '리포트',
    emptyText: '아직 생성된 리포트가 없습니다.',
    success: () => [createReport({ title: '대기 뒤 리포트' })],
    row: (page: Page) => page.getByText('대기 뒤 리포트').first(),
    extraMocks: async (page: Page) => {
      await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
      await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    },
  },
  {
    name: 'AI 검수 목록',
    path: '/knowledge-graph/review',
    apiPath: '/api/v1/graphrag/review-items',
    // 공용 실패 문구(listLoadErrorProps, WD-990)는 서버 사유를 그대로 보인다 — failUntilOk 가 돌려주는 사유
    errorText: '서버 오류',
    leaveTab: '동의어',
    returnTab: '전체',
    label: 'AI 검수',
    emptyText: '검수 대기 중인 항목이 없습니다.',
    success: () => [createSynonymReviewItem({ payload: { entityType: 'Cause', nameA: '대기 뒤 항목', nameB: '다른 이름' } })],
    row: (page: Page) => page.getByText('대기 뒤 항목').first(),
    // 「전체」 키만 실패시키고 「동의어」 키는 빈 목록으로 응답한다
    onlyAllFails: true,
  },
];

for (const c of insightCases) {
  test(`${c.name} — 오류로 비어 있던 키를 오프라인에서 다시 열면 빈 안내 대신 연결 대기 안내, 연결되면 채운다 (WD-971)`, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['dataset:read', 'dataset:write', 'proactive:read', 'proactive:write', 'proactive:admin']);
    await c.extraMocks?.(page);
    const { setOk } = await failUntilOk(page, c.apiPath, c.success, (url) => (c.onlyAllFails && url.searchParams.has('type') ? [] : undefined));
    await page.goto(c.path);
    // 앱이 첫 실패를 한 번 재시도한 뒤 최종 오류가 되도록 오류 안내가 보일 때까지 기다린다
    await expect(page.getByText(c.errorText).first()).toBeVisible({ timeout: 15000 });

    // 다른 탭으로 나갔다가(키는 오류로 데이터 없이 남는다), 오프라인에서 되돌아오면 같은 키의 재조회가 데이터 없이 대기한다
    await page.getByRole('tab', { name: c.leaveTab, exact: true }).click();
    await expect(page.getByRole('tab', { name: c.leaveTab, exact: true })).toHaveAttribute('aria-selected', 'true');
    await page.context().setOffline(true);
    await page.getByRole('tab', { name: c.returnTab, exact: true }).click();
    const text = await expectOfflineWaiting(page, c.label, c.emptyText);
    await expect(page.getByText(c.errorText)).toHaveCount(0);

    setOk();
    await page.context().setOffline(false);
    await expect(c.row(page)).toBeVisible();
    await expect(page.getByText(text)).toHaveCount(0);
  });
}

test('파이프라인 목록 — 첫 조회 실패 뒤 오프라인에서 다시 시도하면 연결 대기 안내(status)가 보이고 초점이 버튼에 남는다 (WD-971)', async ({ authenticatedPage: page }) => {
  await setupPipelineMocks(page, 10);
  // 첫 조회를 500 으로 실패시키고(앱의 자동 재시도 포함), ok 가 되면 성공 응답을 돌려준다
  const { setOk } = await failUntilOk(page, '/api/v1/pipelines', () => createPageResponse([createPipeline({ id: 11, name: '대기 뒤 파이프라인' })]));
  await page.goto('/pipelines');
  // 공용 실패 문구(listLoadErrorProps, WD-990)는 서버 사유를 그대로 보인다 — failUntilOk 가 돌려주는 사유
  await expect(page.getByRole('alert').filter({ hasText: '서버 오류' })).toBeVisible({ timeout: 15000 });
  setOk();

  await page.context().setOffline(true);
  const retry = page.getByRole('button', { name: '다시 시도' });
  await retry.click();

  // 재시도 중 오프라인이면 오류 문구 대신 연결 대기 안내가 role=status 로 보이고 alert 에는 없다
  const text = await expectOfflineWaiting(page, '파이프라인', '파이프라인이 없습니다.');
  // 같은 버튼이 마운트된 채라 초점이 유지된다(WD-956)
  await expect(retry).toBeFocused();

  await page.context().setOffline(false);
  await expect(page.getByText('대기 뒤 파이프라인', { exact: true })).toBeVisible();
  await expect(page.getByText(text)).toHaveCount(0);
});
