import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트 목록 탭 E2E 테스트
 * - 목록 렌더, 행 클릭 시 뷰어 이동, 빈 상태 분기, 탭 전환을 검증한다.
 */
const REPORTS = [
  {
    executionId: 11,
    jobId: 3,
    jobName: '월간 화재 통계',
    title: '8월 화재 통계 리포트',
    summary: '8월 화재 건수는 전월 대비 12% 감소했습니다.',
    completedAt: '2026-08-10T09:00:00',
  },
];

const JOB = {
  id: 3,
  name: '월간 화재 통계',
  enabled: true,
};

test.describe('리포트 목록', () => {
  test('생성된 리포트가 목록에 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', REPORTS);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [JOB]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');

    await expect(page.getByRole('heading', { name: '리포트' })).toBeVisible();
    await expect(page.getByText('8월 화재 통계 리포트')).toBeVisible();
    // 제목 아래 요약 한 줄
    await expect(page.getByText('8월 화재 건수는 전월 대비 12% 감소했습니다.')).toBeVisible();
    // 잡 이름은 배지로만 — 링크가 아니어야 한다
    await expect(page.getByRole('link', { name: '월간 화재 통계' })).toHaveCount(0);
  });

  test('행을 클릭하면 리포트 뷰어로 이동한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', REPORTS);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [JOB]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/3', JOB);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/3/executions/11/html', '<h1>리포트 본문</h1>');

    await page.goto('/ai-insights/reports');
    await page.getByText('8월 화재 통계 리포트').click();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/3\/executions\/11\/report/);
  });

  test('잡이 없으면 스마트 작업 생성을 유도하는 빈 상태를 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');

    await expect(page.getByText('스마트 작업을 만들면 생성된 리포트가 여기에 쌓입니다.')).toBeVisible();
    // 문구만 바뀌고 CTA가 빠지는 반쪽 구현을 막는다
    await expect(page.getByRole('button', { name: '스마트 작업 만들기' })).toBeVisible();
  });

  test('잡은 있고 리포트만 없으면 다른 빈 상태 문구를 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [JOB]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');

    await expect(page.getByText('아직 생성된 리포트가 없습니다.')).toBeVisible();
    await expect(page.getByRole('button', { name: '스마트 작업 보기' })).toBeVisible();
  });

  test('행 액션으로 해당 스마트 작업 상세로 이동한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', REPORTS);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [JOB]);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/3', JOB);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');
    // 행 클릭(뷰어)과 구분되는 별도 액션 — stopPropagation 이 동작해야 한다
    await page.getByRole('button', { name: '스마트 작업 보기' }).click();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/3$/);
  });

  test('양식 탭으로 전환하면 URL에 tab=templates가 남는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/reports', REPORTS);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [JOB]);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/reports');
    await page.getByRole('tab', { name: '리포트 양식' }).click();

    await expect(page).toHaveURL(/tab=templates/);
  });
});
