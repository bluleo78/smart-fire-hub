/**
 * 실행 상세·리포트 보기의 「돌아가기」 목적지 (WD-661)
 *
 * 1) 돌아갈 기록 판정 — 예전 useGoBack 은 location.key !== 'default' 로 「기록 있음」을 판정했다.
 *    로그아웃 상태에서 이메일 링크를 새 탭으로 열면 로그인 리다이렉트(replace)를 거치며 key 가 바뀌어
 *    기록이 없는데도 navigate(-1) 을 불러 무반응이거나 앱 밖으로 나갔다. 이제 앱 안 PUSH 이동을 직접 센다(WD-667, lib/app-history).
 * 2) 대체 목적지 통일 — 같은 주소 파라미터인데 실행 상세는 작업 상세로, 리포트 보기는 리포트 목록으로 돌아갔다.
 *    이제 둘 다 작업 id 가 유효하면 작업 상세(/ai-insights/jobs/:jobId), 아니면 작업 목록으로 보낸다.
 */

import type { Page } from '@playwright/test';

import { createJob, createReport } from '../../factories/ai-insight.factory';
import { setupJobDetailMocks, setupJobListMocks, setupReportViewerMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const NOT_FOUND_BODY = { status: 404, error: 'Not Found', message: '실행을 찾을 수 없습니다' };

/** 로그인 화면에서 로그인한다 — 로그인 후 ProtectedRoute 가 넘긴 원래 주소로 replace 이동한다 */
async function loginFromRedirect(page: Page) {
  await expect(page).toHaveURL(/\/login/);
  await page.getByLabel('아이디 (이메일)').fill('test@example.com');
  await page.getByLabel('비밀번호', { exact: true }).fill('testpassword123');
  await page.getByRole('button', { name: '로그인' }).click();
}

test.describe('로그인 리다이렉트를 거친 첫 화면의 돌아가기 (WD-661)', () => {
  test('실행 상세(없는 실행)를 로그아웃 상태로 열고 로그인하면 돌아가기가 작업 상세로 보낸다', async ({
    authMockedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5', NOT_FOUND_BODY, { status: 404 });

    await page.goto('/ai-insights/jobs/1/executions/5');
    await loginFromRedirect(page);
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1\/executions\/5$/);
    await expect(page.getByTestId('execution-not-found')).toBeVisible();

    // 로그인 리다이렉트(replace)로 key 는 바뀌었지만 이 탭의 앱 기록은 이 화면 하나뿐이다 — navigate(-1) 이 아니라 작업 상세로
    await page.getByRole('button', { name: '돌아가기' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
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
  test('리포트 보기 — 작업 id 가 유효하고 리포트가 없으면(404) 돌아가기가 작업 상세로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5/html', NOT_FOUND_BODY, { status: 404 });

    await page.goto('/ai-insights/jobs/1/executions/5/report');

    await expect(page.getByTestId('report-not-found')).toBeVisible();
    await page.getByTestId('report-not-found').getByRole('button', { name: '돌아가기' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
  });

  test('리포트 보기 — 작업 id 는 유효하고 실행 id 만 잘못되면 돌아가기가 작업 상세로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);

    await page.goto('/ai-insights/jobs/1/executions/abc/report');

    await expect(page.getByText('잘못된 요청입니다.')).toBeVisible();
    await page.getByRole('button', { name: '돌아가기' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
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

  test('실행 상세 — 작업 id 는 유효하고 실행 id 만 잘못되면 돌아가기가 같은 작업 상세로 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);

    await page.goto('/ai-insights/jobs/1/executions/abc');

    await expect(page.getByTestId('execution-not-found')).toBeVisible();
    await page.getByRole('button', { name: '돌아가기' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
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

test.describe('리포트 보기 — 작업을 읽을 수 없으면 대체 목적지가 리포트 목록 (WD-667)', () => {
  for (const { status, label } of [
    { status: 404, label: '삭제된 작업(404)' },
    { status: 403, label: '권한 없음(403)' },
  ]) {
    test(`${label} — 머리 버튼이 「리포트 목록」이고 리포트 목록으로 보낸다`, async ({ authenticatedPage: page }) => {
      await setupReportViewerMocks(page, 1, 5);
      const job = await mockApi(
        page,
        'GET',
        '/api/v1/proactive/jobs/1',
        { status, error: 'Error', message: '작업을 읽을 수 없습니다' },
        { status, capture: true },
      );
      await mockApi(page, 'GET', '/api/v1/proactive/reports', [createReport()]);
      await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 3 })]);

      await page.goto('/ai-insights/jobs/1/executions/5/report');

      // 작업 상세로 보내면 오류·권한 안내 화면에 떨어진다 — 리포트 목록으로 이름과 목적지를 바꾼다
      await expect(page.getByRole('button', { name: '리포트 목록' })).toBeEnabled();
      await expect(page.getByRole('button', { name: '작업 상세' })).toHaveCount(0);
      // 대체 목적지 확인용 조회는 한 번만 — 재시도·폴링하지 않는다
      expect(job.requests).toHaveLength(1);
      await page.getByRole('button', { name: '리포트 목록' }).click();
      await expect(page).toHaveURL(/\/ai-insights\/reports$/);
    });
  }

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
