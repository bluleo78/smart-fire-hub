import { createJob, createJobExecution } from '../../factories/ai-insight.factory';
import {
  setupJobDetailMocks,
  setupJobDetailWithExecutionsMocks,
  setupNewJobMocks,
} from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업(Job) 상세 페이지 E2E 테스트
 * - 기존 작업 조회, 실행 이력, 새 작업 생성 폼 UI를 검증한다.
 */
test.describe('스마트 작업 상세 페이지', () => {
  test('작업 정보가 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 잡 ID 1 상세 페이지 모킹
    await setupJobDetailMocks(page, 1);

    await page.goto('/ai-insights/jobs/1');

    // 작업명이 헤더에 표시되는지 확인 (팩토리 기본값: "매일 현황 리포트")
    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();

    // 헤더 영역 내 활성 뱃지 확인 (enabled: true 기본값)
    // header와 overview 탭 두 곳에 모두 표시될 수 있으므로 header로 범위를 좁힌다
    await expect(page.locator('header').locator('[data-slot="badge"]').filter({ hasText: '활성' })).toBeVisible();

    // 탭 목록 확인
    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '실행 이력' })).toBeVisible();
  });

  test('실행 이력 탭에서 실행 목록을 확인할 수 있다', async ({ authenticatedPage: page }) => {
    // 실행 이력 3건 포함하여 모킹
    await setupJobDetailWithExecutionsMocks(page, 1, 3);

    await page.goto('/ai-insights/jobs/1?tab=executions');

    // 실행 이력 탭이 활성화되어 있는지 확인
    await expect(page.getByRole('tab', { name: '실행 이력' })).toHaveAttribute('data-state', 'active');

    // 실행 이력 테이블 헤더 확인
    await expect(page.getByRole('columnheader', { name: '실행 시간' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '상태' })).toBeVisible();
  });

  test('새 작업 페이지에서 생성 폼이 표시된다', async ({ authenticatedPage: page }) => {
    // 새 작업 페이지 API 모킹
    await setupNewJobMocks(page);

    await page.goto('/ai-insights/jobs/new');

    // "새 스마트 작업" 헤더 확인
    await expect(page.getByRole('heading', { name: '새 스마트 작업' })).toBeVisible();

    // "생성" 버튼 확인
    await expect(page.getByRole('button', { name: '생성' })).toBeVisible();
  });

  test('목록으로 버튼 클릭 시 작업 목록 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);
    // 목록 페이지로 돌아갈 때 필요한 API 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [createJob({ id: 1 })]);

    await page.goto('/ai-insights/jobs/1');

    // 뒤로가기 버튼(aria-label="목록으로") 클릭
    await page.getByRole('button', { name: '목록으로' }).click();

    // 작업 목록 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/jobs');
  });

  test('실행 이력 탭에서 실행 행 클릭 시 실행 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    // 실행 이력 1건 포함하여 모킹
    const execution = createJobExecution({ id: 5, jobId: 1, status: 'COMPLETED' });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', [execution]);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    // 실행 상세 페이지 API
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5', execution);
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1/executions/5/html',
      '',
      { status: 404 },
    );

    await page.goto('/ai-insights/jobs/1?tab=executions');

    // 실행 행 클릭 — 상태 뱃지가 있는 첫 번째 행 클릭
    const rows = page.getByRole('row');
    // 헤더 제외한 첫 번째 데이터 행 클릭
    await rows.nth(1).click();

    // 실행 상세 페이지로 이동 확인
    await expect(page).toHaveURL('/ai-insights/jobs/1/executions/5');
  });

  test('비활성 작업에는 "비활성" 뱃지가 표시된다', async ({ authenticatedPage: page }) => {
    // enabled: false 작업 모킹
    const disabledJob = createJob({ id: 2, enabled: false, name: '비활성 작업' });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2', disabledJob);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs/2');

    // 헤더 영역 내 비활성 뱃지 확인
    // header와 overview 탭 두 곳에 모두 표시될 수 있으므로 header로 범위를 좁힌다
    await expect(page.locator('header').locator('[data-slot="badge"]').filter({ hasText: '비활성' })).toBeVisible();
  });
});
