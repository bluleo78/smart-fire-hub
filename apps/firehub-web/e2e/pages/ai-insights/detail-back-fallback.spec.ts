/**
 * 실행 상세·리포트 보기의 「돌아가기」 목적지 (WD-661)
 *
 * 1) 돌아갈 기록 판정 — 예전 useGoBack 은 location.key !== 'default' 로 「기록 있음」을 판정했다.
 *    로그아웃 상태에서 이메일 링크를 새 탭으로 열면 로그인 리다이렉트(replace)를 거치며 key 가 바뀌어
 *    기록이 없는데도 navigate(-1) 을 불러 무반응이거나 앱 밖으로 나갔다. 이제 React Router 의 history idx 로 판정한다.
 * 2) 대체 목적지 통일 — 같은 주소 파라미터인데 실행 상세는 작업 상세로, 리포트 보기는 리포트 목록으로 돌아갔다.
 *    이제 둘 다 작업 id 가 유효하면 작업 상세(/ai-insights/jobs/:jobId), 아니면 작업 목록으로 보낸다.
 */

import type { Page } from '@playwright/test';

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
