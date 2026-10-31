import { createJob } from '../../factories/ai-insight.factory';
import { mockProactivePermissions, setupJobDetailMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 「지금 실행」 — 이미 실행 중일 때의 안내 (WD-368).
 * 서버는 중복 실행을 409 + code PROACTIVE_JOB_ALREADY_RUNNING 으로 막는다. 예전에는 목록이 사유 없는 「실행에 실패했습니다.」,
 * 상세가 「Job이 이미 실행 중입니다: 1」(영어·내부 번호)을 보였다. 두 화면 모두 같은 한국어 안내와 「실행 이력」 바로가기를 보이는지 확인한다.
 */

const ALREADY_RUNNING_MESSAGE = '이미 실행 중인 작업입니다. 끝나면 실행 이력에서 결과를 확인하세요';
const ALREADY_RUNNING_BODY = {
  status: 409,
  error: 'Conflict',
  code: 'PROACTIVE_JOB_ALREADY_RUNNING',
  message: ALREADY_RUNNING_MESSAGE,
};

// 실행 버튼은 proactive:write 가 있어야 보인다(WD-133)
test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

test.describe('스마트 작업 지금 실행 — 이미 실행 중 (WD-368)', () => {
  test('목록에서 이미 실행 중이면 사유 없는 실패 대신 안내와 「실행 이력」 바로가기를 보인다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 1, name: '잡 1', enabled: true })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    const execute = await mockApi(page, 'POST', '/api/v1/proactive/jobs/1/execute', ALREADY_RUNNING_BODY, {
      status: 409,
      capture: true,
    });
    // 바로가기로 들어갈 상세 화면
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1, name: '잡 1' }));
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);

    await page.goto('/ai-insights/jobs');
    await page.getByRole('button', { name: '지금 실행' }).click();
    await execute.waitForRequest();

    const toast = page.locator('[data-sonner-toast]').filter({ hasText: ALREADY_RUNNING_MESSAGE });
    await expect(toast).toBeVisible();
    await expect(page.getByText('실행에 실패했습니다.')).toHaveCount(0);
    await expect(page.getByText(/Job이/)).toHaveCount(0);

    await toast.getByRole('button', { name: '실행 이력' }).click();
    await expect(page).toHaveURL('/ai-insights/jobs/1?tab=executions');
  });

  test('목록에서 그 밖의 오류는 서버 안내를 그대로 보인다(사유 없는 실패 아님)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 1, name: '잡 1', enabled: true })]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(
      page,
      'POST',
      '/api/v1/proactive/jobs/1/execute',
      { status: 403, error: 'Forbidden', message: '스마트 작업 실행 권한이 없습니다' },
      { status: 403 },
    );

    await page.goto('/ai-insights/jobs');
    await page.getByRole('button', { name: '지금 실행' }).click();

    const toast = page.locator('[data-sonner-toast]').filter({ hasText: '스마트 작업 실행 권한이 없습니다' });
    await expect(toast).toBeVisible();
    await expect(toast.getByRole('button', { name: '실행 이력' })).toHaveCount(0);
  });

  test('상세에서 이미 실행 중이면 같은 안내와 「실행 이력」 탭 바로가기를 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    const execute = await mockApi(page, 'POST', '/api/v1/proactive/jobs/1/execute', ALREADY_RUNNING_BODY, {
      status: 409,
      capture: true,
    });

    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '지금 실행' }).click();
    await execute.waitForRequest();

    const toast = page.locator('[data-sonner-toast]').filter({ hasText: ALREADY_RUNNING_MESSAGE });
    await expect(toast).toBeVisible();
    await expect(page.getByText(/Job이 이미 실행 중/)).toHaveCount(0);

    await toast.getByRole('button', { name: '실행 이력' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1\?tab=executions/);
  });
});
