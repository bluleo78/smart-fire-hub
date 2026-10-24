import { createJob, createJobExecution, createReport } from '../../factories/ai-insight.factory';
import { createLevelSummary } from '../../factories/security-level.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 리포트 등급 E2E (WD-198).
 * - 서버는 실행 동안 읽은 데이터셋의 최고 등급을 실행(리포트)에 남기고, 목록·상세 응답에 securityLevel 로 싣는다 → 데이터셋과 같은 등급 배지.
 * - 리포트 등급이 작성자의 현재 등급보다 높으면 상세·HTML 이 403 REPORT_SECURITY_LEVEL_BLOCKED → 권한 요청 안내가 아니라 등급 안내.
 * - 등급 없는 리포트(이전 리포트)는 배지 없이 지금처럼 보인다(대조).
 */

const LEVEL_BLOCKED_BODY = {
  status: 403,
  error: 'Forbidden',
  code: 'REPORT_SECURITY_LEVEL_BLOCKED',
  message: '현재 보안 등급으로 열 수 없는 리포트입니다',
};
const LEVEL_BLOCKED_TEXT = '현재 보안 등급으로 열 수 없는 리포트입니다';
/** 조회 권한 안내(WD-137) — 등급 차단을 이 문구로 잘못 그리면 안 된다. */
const READ_FORBIDDEN_TEXT = /스마트 작업 조회 권한이 필요합니다/;

test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
});

test.describe('리포트 등급 (WD-198)', () => {
  test('실행 이력 목록에 리포트 등급 배지가 보이고, 등급 없는 실행은 배지가 없다', async ({
    authenticatedPage: page,
  }) => {
    const leveled = { ...createJobExecution({ id: 5, jobId: 1 }), securityLevel: createLevelSummary('민감') };
    const legacy = { ...createJobExecution({ id: 4, jobId: 1 }), securityLevel: null };
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', [leveled, legacy]);

    await page.goto('/ai-insights/jobs/1?tab=executions');

    const rows = page.getByRole('row');
    await expect(rows.nth(1).getByTestId('security-level-badge')).toHaveText('민감');
    await expect(rows.nth(2).getByTestId('security-level-badge')).toHaveCount(0);
  });

  test('생성된 리포트 목록에 등급 배지가 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', [
      { ...createReport({ executionId: 11, title: '민감 리포트', summary: null }), securityLevel: createLevelSummary('민감') },
      { ...createReport({ executionId: 12, title: '이전 리포트' }), securityLevel: null },
    ]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 3, name: '월간 화재 통계' })]);

    await page.goto('/ai-insights/reports');

    await expect(
      page.getByRole('row').filter({ hasText: '민감 리포트' }).getByTestId('security-level-badge'),
    ).toHaveText('민감');
    await expect(
      page.getByRole('row').filter({ hasText: '이전 리포트' }).getByTestId('security-level-badge'),
    ).toHaveCount(0);
  });

  test('실행 상세는 등급 배지를 보이고, 등급 차단 403 이면 등급 안내를 보인다', async ({
    authenticatedPage: page,
  }) => {
    const leveled = {
      ...createJobExecution({ id: 5, jobId: 1, result: { summary: '요약' } }),
      securityLevel: createLevelSummary('내부'),
    };
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5', leveled);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5/html', '', { status: 404 });
    await page.goto('/ai-insights/jobs/1/executions/5');
    await expect(page.getByRole('heading', { name: '실행 #5' })).toBeVisible();
    await expect(page.getByTestId('security-level-badge')).toHaveText('내부');

    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/6', LEVEL_BLOCKED_BODY, { status: 403 });
    await page.goto('/ai-insights/jobs/1/executions/6');
    await expect(page.getByTestId('execution-level-blocked')).toHaveText(LEVEL_BLOCKED_TEXT);
    await expect(page.getByText(READ_FORBIDDEN_TEXT)).toHaveCount(0);
    await expect(page.getByText('실행 정보를 찾을 수 없습니다.')).toHaveCount(0);
  });

  test('리포트 뷰어는 등급 차단 403 이면 등급 안내를 보이고 조회 권한 안내는 보이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/3/executions/11/html', LEVEL_BLOCKED_BODY, {
      status: 403,
    });

    await page.goto('/ai-insights/jobs/3/executions/11/report');

    await expect(page.getByTestId('report-level-blocked')).toHaveText(LEVEL_BLOCKED_TEXT);
    await expect(page.getByTestId('report-forbidden')).toHaveCount(0);
    await expect(page.getByText(READ_FORBIDDEN_TEXT)).toHaveCount(0);
    await expect(page.getByText('리포트를 불러올 수 없습니다.')).toHaveCount(0);
  });

  test('조회 권한 403(코드 없음)은 여전히 조회 권한 안내다(대조)', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/3/executions/11/html',
      { status: 403, error: 'Forbidden', message: '권한 없음' },
      { status: 403 },
    );

    await page.goto('/ai-insights/jobs/3/executions/11/report');

    await expect(page.getByTestId('report-forbidden')).toBeVisible();
    await expect(page.getByTestId('report-level-blocked')).toHaveCount(0);
  });
});
