/**
 * 실행 상세·리포트 보기의 「돌아가기」 목적지 (WD-661)
 *
 * 1) 돌아갈 기록 판정 — 예전 useGoBack 은 location.key !== 'default' 로 「기록 있음」을 판정했다.
 *    로그아웃 상태에서 이메일 링크를 새 탭으로 열면 로그인 리다이렉트(replace)를 거치며 key 가 바뀌어
 *    기록이 없는데도 navigate(-1) 을 불러 무반응이거나 앱 밖으로 나갔다. 이제 앱 안 PUSH 이동을 직접 센다(WD-667, lib/app-history).
 * 2) 대체 목적지 통일 — 같은 주소 파라미터인데 실행 상세는 작업 상세로, 리포트 보기는 리포트 목록으로 돌아갔다.
 *    이제 두 화면이 한 규칙(jobBackFallback)을 쓴다(WD-671) — 화면 자신의 조회(실행·리포트 HTML)가 성공했으면 작업 상세,
 *    아직 모르거나 403·404·오류·잘못된 실행 id 면 리포트 목록, 작업 id 가 잘못되면 작업 목록.
 */

import type { Page } from '@playwright/test';

import { createJob, createJobExecution, createReport } from '../../factories/ai-insight.factory';
import { setupJobDetailMocks, setupJobListMocks, setupReportViewerMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const NOT_FOUND_BODY = { status: 404, error: 'Not Found', message: '실행을 찾을 수 없습니다' };

/** 리포트 목록 화면이 그려지도록 목록 API 를 모킹한다 — 대체 목적지(리포트 목록)로 간 뒤 화면 오류를 피한다 */
async function mockReportList(page: Page) {
  await mockApi(page, 'GET', '/api/v1/proactive/reports', [createReport()]);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 3 })]);
}

/** 로그인 화면에서 로그인한다 — 로그인 후 ProtectedRoute 가 넘긴 원래 주소로 replace 이동한다 */
async function loginFromRedirect(page: Page) {
  await expect(page).toHaveURL(/\/login/);
  await page.getByLabel('아이디 (이메일)').fill('test@example.com');
  await page.getByLabel('비밀번호', { exact: true }).fill('testpassword123');
  await page.getByRole('button', { name: '로그인' }).click();
}

test.describe('로그인 리다이렉트를 거친 첫 화면의 돌아가기 (WD-661)', () => {
  test('실행 상세(없는 실행)를 로그아웃 상태로 열고 로그인하면 돌아가기가 리포트 목록으로 보낸다', async ({
    authMockedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    await mockReportList(page);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5', NOT_FOUND_BODY, { status: 404 });

    await page.goto('/ai-insights/jobs/1/executions/5');
    await loginFromRedirect(page);
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1\/executions\/5$/);
    await expect(page.getByTestId('execution-not-found')).toBeVisible();

    // 로그인 리다이렉트(replace)로 key 는 바뀌었지만 이 탭의 앱 기록은 이 화면 하나뿐이다 — navigate(-1) 이 아니라 대체 목적지로.
    // 실행을 읽지 못했으니(404) 작업 상세가 아니라 리포트 목록이다(WD-671)
    await page.getByRole('button', { name: '돌아가기' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/reports$/);
  });

  test('리포트 보기를 로그아웃 상태로 열고 로그인하면 머리 버튼이 「작업 상세」이고 작업 상세로 보낸다', async ({
    authMockedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    await setupReportViewerMocks(page, 1, 5);

    await page.goto('/ai-insights/jobs/1/executions/5/report');
    await loginFromRedirect(page);
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1\/executions\/5\/report$/);

    // 돌아갈 기록이 없으니 「뒤로」 대신 어디로 가는지 이름에 드러낸다
    await expect(page.getByRole('button', { name: '뒤로' })).toHaveCount(0);
    await page.getByRole('button', { name: '작업 상세' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
  });
});

test.describe('실행 상세·리포트 보기의 대체 목적지 통일 (WD-661)', () => {
  test('리포트 보기 — 작업 id 는 유효하고 실행 id 만 잘못되면(조회하지 않아 모름) 돌아가기가 리포트 목록으로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await mockReportList(page);

    await page.goto('/ai-insights/jobs/1/executions/abc/report');

    await expect(page.getByText('잘못된 요청입니다.')).toBeVisible();
    await page.getByRole('button', { name: '돌아가기' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/reports$/);
  });

  test('리포트 보기 — 작업 id 가 잘못되면 머리 버튼이 「작업 목록」이고 돌아가기도 작업 목록으로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobListMocks(page);

    await page.goto('/ai-insights/jobs/abc/executions/5/report');

    await expect(page.getByText('잘못된 요청입니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '작업 목록' })).toBeVisible();
    await page.getByRole('button', { name: '돌아가기' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs$/);
  });

  test('실행 상세 — 작업 id 는 유효하고 실행 id 만 잘못되면 돌아가기가 리포트 보기와 같은 리포트 목록으로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await mockReportList(page);

    await page.goto('/ai-insights/jobs/1/executions/abc');

    await expect(page.getByTestId('execution-not-found')).toBeVisible();
    await page.getByRole('button', { name: '돌아가기' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/reports$/);
  });
});

test.describe('돌아갈 기록 판정 — 앱 안 PUSH 기준, 새로고침 때만 idx 참고 (WD-667)', () => {
  test('앱 안 이동 뒤 미저장 가드처럼 idx 없는 기록(pushState(null))이 끼어도 「뒤로」가 들어온 화면으로 돌아간다', async ({
    authenticatedPage: page,
  }) => {
    const report = createReport();
    await mockApi(page, 'GET', '/api/v1/proactive/reports', [report]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: report.jobId })]);
    await setupReportViewerMocks(page, report.jobId, report.executionId);
    await mockApi(
      page,
      'GET',
      `/api/v1/proactive/jobs/${report.jobId}/executions/${report.executionId}/pdf`,
      '%PDF-1.4',
      { headers: { 'Content-Type': 'application/pdf' } },
    );

    // 미저장 가드(unsaved-changes-guard-registry)의 동작을 그대로 흉내 낸다 — 브라우저 뒤로를 라우터보다 먼저 가로채
    // 라우터가 못 보게 막고 주소만 pushState(null, …) 로 되돌린다. 라우터보다 먼저 듣도록 앱 부팅 전에 등록하고, 표시가 켜진 한 번만 동작한다.
    await page.addInitScript(() => {
      const w = window as unknown as { __interceptNextPop?: boolean; __restorePath?: string };
      window.addEventListener('popstate', (event) => {
        if (!w.__interceptNextPop) return;
        w.__interceptNextPop = false;
        event.stopImmediatePropagation();
        window.history.pushState(null, '', w.__restorePath);
      });
    });

    // 리포트 목록 → 행을 눌러 뷰어로(앱 안 PUSH)
    await page.goto('/ai-insights/reports?from=list');
    await page.getByText(report.title).click();
    await expect(page).toHaveURL(/\/report$/);
    await expect(page.getByRole('button', { name: '뒤로' })).toBeVisible();
    const viewerPath = new URL(page.url()).pathname;

    // 브라우저 뒤로 → 가드가 가로채 뷰어 주소로 되돌린다. 이제 현재 기록에는 React Router 의 idx 가 없다.
    await page.evaluate((path) => {
      const w = window as unknown as { __interceptNextPop?: boolean; __restorePath?: string };
      w.__interceptNextPop = true;
      w.__restorePath = path;
      window.history.back();
    }, viewerPath);
    await expect.poll(() => page.evaluate(() => window.history.state)).toBeNull();
    await expect(page).toHaveURL(/\/report$/);

    // 화면을 다시 그리게 한다(PDF 내려받기 중 표시) — 예전엔 다시 그릴 때 idx 가 없어 「작업 상세」로 바뀌었다
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'PDF' }).click();
    await download;

    await expect(page.getByRole('button', { name: '작업 상세' })).toHaveCount(0);
    await page.getByRole('button', { name: '뒤로' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/reports\?from=list$/);
  });

  test('앱 안 이동 뒤 새로고침해도 「뒤로」가 들어온 화면으로 돌아간다', async ({ authenticatedPage: page }) => {
    const report = createReport();
    await mockApi(page, 'GET', '/api/v1/proactive/reports', [report]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: report.jobId })]);
    await setupReportViewerMocks(page, report.jobId, report.executionId);

    await page.goto('/ai-insights/reports?from=list');
    await page.getByText(report.title).click();
    await expect(page).toHaveURL(/\/report$/);

    // 새로고침하면 이 문서에서 본 이동 기록은 사라지지만 브라우저 기록에는 리포트 목록이 그대로 있다
    await page.reload();
    await expect(page.locator('iframe[title="리포트"]')).toBeVisible();
    await page.getByRole('button', { name: '뒤로' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/reports\?from=list$/);
  });

  test('로그인 리다이렉트 뒤 앱 안에서 한 번 이동하면 그 화면에서는 「뒤로」가 된다(PUSH 만 기록으로 센다)', async ({
    authMockedPage: page,
  }) => {
    const report = createReport();
    await mockApi(page, 'GET', '/api/v1/proactive/reports', [report]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: report.jobId })]);
    await setupReportViewerMocks(page, report.jobId, report.executionId);

    // 로그아웃 상태로 리포트 목록을 열고 로그인 — /login 으로 replace, 로그인 뒤 원래 주소로 replace
    await page.goto('/ai-insights/reports');
    await loginFromRedirect(page);
    await expect(page).toHaveURL(/\/ai-insights\/reports$/);

    await page.getByText(report.title).click();
    await expect(page).toHaveURL(/\/report$/);
    await page.getByRole('button', { name: '뒤로' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/reports$/);
  });
});

test.describe('기록 없이 연 실행 상세·리포트 보기의 대체 목적지 — 화면 자신의 조회 결과로 정한다 (WD-671)', () => {
  for (const { status, label } of [
    { status: 404, label: '삭제된 작업·작성자 아님(404)' },
    { status: 403, label: '권한 없음(403)' },
  ]) {
    test(`리포트 보기 ${label} — 머리 버튼이 「리포트 목록」이고, 작업을 따로 조회하지 않고 리포트 목록으로 보낸다`, async ({
      authenticatedPage: page,
    }) => {
      await mockReportList(page);
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
      await mockApi(
        page,
        'GET',
        '/api/v1/proactive/jobs/1/executions/5/html',
        { status, error: 'Error', message: '리포트를 읽을 수 없습니다' },
        { status },
      );
      const job = await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1 }), { capture: true });

      await page.goto('/ai-insights/jobs/1/executions/5/report');

      // 작업 상세로 보내면 오류·권한 안내 화면에 떨어진다 — 리포트 목록으로 보내고 이름도 그 목적지다
      await expect(page.getByRole('button', { name: '리포트 목록' })).toBeEnabled();
      await expect(page.getByRole('button', { name: '작업 상세' })).toHaveCount(0);
      await page.getByRole('button', { name: '리포트 목록' }).click();
      await expect(page).toHaveURL(/\/ai-insights\/reports$/);
      // 목적지를 정하려고 작업을 따로 조회하지 않는다(예전엔 기록 없이 열 때마다 GET /jobs/{id} 를 더 불렀다)
      expect(job.requests).toHaveLength(0);
    });

    test(`실행 상세 ${label} — 새 탭으로 열면 돌아가기가 리포트 보기와 같은 리포트 목록으로 보낸다`, async ({
      authenticatedPage: page,
    }) => {
      // 작업 상세가 열리면 다른 목적지로 간 것이 드러나도록 작업 조회도 같은 상태로 막아 둔다
      await mockReportList(page);
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
      await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', { status, error: 'Error', message: '작업' }, { status });
      await mockApi(
        page,
        'GET',
        '/api/v1/proactive/jobs/1/executions/5',
        { status, error: 'Error', message: '실행을 읽을 수 없습니다' },
        { status },
      );

      await page.goto('/ai-insights/jobs/1/executions/5');

      await expect(page.getByRole('button', { name: '돌아가기' })).toBeVisible();
      await page.getByRole('button', { name: '돌아가기' }).click();
      await expect(page).toHaveURL(/\/ai-insights\/reports$/);
    });
  }

  test('리포트 보기 — 리포트 요청이 끝나지 않아도 머리 버튼은 잠기지 않고 이름이 그대로이며, 누르면 리포트 목록으로 간다', async ({
    authenticatedPage: page,
  }) => {
    await mockReportList(page);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    // HTML·작업 요청을 응답하지 않고 붙잡아 둔다 — axios 에 타임아웃이 없어 실제로도 멈출 수 있다
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs/1/executions/5/html' || url.pathname === '/api/v1/proactive/jobs/1',
      () => {},
    );

    await page.goto('/ai-insights/jobs/1/executions/5/report');
    await expect(page.getByText('리포트를 불러오는 중...')).toBeVisible();

    const back = page.getByRole('button', { name: '리포트 목록' });
    await expect(back).toBeEnabled();
    // 기다리는 동안 이름이 바뀌지 않는다 — 몇 번 다시 확인해도 같은 이름·같은 활성 상태다
    for (let i = 0; i < 3; i++) {
      await page.waitForTimeout(500);
      await expect(back).toBeEnabled();
      await expect(page.getByRole('button', { name: '작업 상세' })).toHaveCount(0);
      await expect(page.getByText('리포트를 불러오는 중...')).toBeVisible();
    }

    await back.click();
    await expect(page).toHaveURL(/\/ai-insights\/reports$/);
  });

  test('실행 상세 — 실행을 읽었으면 기록 없이 열어도 머리 「뒤로」가 작업 상세로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5', createJobExecution({ id: 5, jobId: 1 }));
    await setupReportViewerMocks(page, 1, 5);

    await page.goto('/ai-insights/jobs/1/executions/5');

    await page.getByRole('button', { name: '뒤로' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
  });

  test('앱 안에서 들어오면(기록 있음) 작업을 따로 조회하지 않는다', async ({ authenticatedPage: page }) => {
    const report = createReport();
    await mockApi(page, 'GET', '/api/v1/proactive/reports', [report]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: report.jobId })]);
    await setupReportViewerMocks(page, report.jobId, report.executionId);
    const job = await mockApi(page, 'GET', `/api/v1/proactive/jobs/${report.jobId}`, createJob({ id: report.jobId }), {
      capture: true,
    });

    await page.goto('/ai-insights/reports');
    await page.getByText(report.title).click();
    await expect(page.getByRole('button', { name: '뒤로' })).toBeVisible();
    await expect(page.locator('iframe[title="리포트"]')).toBeVisible();
    expect(job.requests).toHaveLength(0);
  });
});
