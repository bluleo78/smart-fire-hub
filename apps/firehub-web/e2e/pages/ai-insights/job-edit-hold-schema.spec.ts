/**
 * 스마트 작업 편집 — 재조회한 주기로 저장이 막히지 않는다 E2E (WD-1109)
 *
 * 결함: 실행 중 작업은 상세 조회가 RUN_POLL_MS(5초) 마다 다시 받아지는데, 편집 중 재조회로 다른 곳이 바꾼
 * 주기(cronExpression)가 job 에 반영되면 resolverSchema 가 그 최신 job 을 기준으로 「안 건드린 주기는
 * 필수 아님」(WD-995) 판정을 다시 해, 멈춰 있는 폼의 빈 주기가 「실행 주기를 설정하세요」로 바뀌며
 * 저장 버튼이 꺼졌다. 폼 시드·주기 비교(cronUnchanged)는 멈춘 baseJob 을 따르므로 검증 기준도
 * baseJob 을 따라야 한다.
 *
 * 모킹: 첫 GET 은 주기 없는 BOTH 작업(triggerType 'BOTH', cronExpression null, 최근 실행 RUNNING),
 * 둘째 GET 부터는 다른 곳이 주기를 받은 값(cronExpression '0 9 * * *')을 싣는다 — 폴링 재조회 경로 그대로를 시험한다.
 *
 * 둘째 시험(WD-1109 반려 2): 안내(cronRequiredHint)·판정(triggerUnchanged)의 기준도 같은 baseJob 이어야 한다 —
 * 최신 job 을 쓰면 재조회로 다른 곳이 트리거만 바꾼 순간 안내가 먼저 뒤집혀
 * 「저장은 켜져 있는데 실행 주기를 설정하세요」가 동시에 보였다.
 */

import type { Route } from '@playwright/test';

import { createJob, createJobExecution, createTemplates } from '../../factories/ai-insight.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

// resolverSchema 가 CRON_REQUIRED_MESSAGE 로 쓰는 문구 — 안내 자리에 실제로 뜨는지도 같은 문구로 본다
const UNSET = '실행 주기를 설정하세요';

test.describe('스마트 작업 편집 — 재조회한 주기로 저장을 막지 않는다 (WD-1109)', () => {
  test('실행 중 작업을 편집하다 폴링 재조회로 주기가 바뀌어도 저장 버튼이 켜져 있다', async ({
    authenticatedPage: page,
  }) => {
    // 편집 버튼은 쓰기 권한이 있을 때만 보인다(WD-133)
    await mockProactivePermissions(page);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/7/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/7/anomaly-events', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    // 프롬프트를 고친 뒤(dirty)에야 재조회 응답을 풀어 시드·기준이 재조회 전 값으로 확정되게 한다(WD-1153)
    let unlock: () => void = () => {};
    const fillDone = new Promise<void>((resolve) => {
      unlock = resolve;
    });
    let arrived = 0;
    let responded = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs/7',
      async (route: Route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const nth = ++arrived;
        // 첫 응답은 그대로, 둘째 응답부터는 프롬프트를 고친 뒤에야 다른 곳이 받은 주기를 실어 보낸다
        if (nth > 1) await fillDone;
        const job = createJob({
          id: 7,
          triggerType: 'BOTH',
          cronExpression: nth === 1 ? null : '0 9 * * *',
          lastExecution: createJobExecution({ status: 'RUNNING' }),
        });
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(job) });
        responded += 1;
      },
    );

    await page.goto('/ai-insights/jobs/7');
    await expect(page.getByRole('heading', { level: 1, name: '매일 현황 리포트' })).toBeVisible();

    // 상세 → 편집 → 프롬프트 수정(dirty) — 이 시점의 조회 결과(주기 없음)가 폼 시드와 기준(baseJob)으로 멈춘다
    await page.getByRole('button', { name: '편집' }).click();
    await expect(page.locator('#job-prompt')).toBeVisible();
    await page.locator('#job-prompt').fill('재조회 뒤에도 저장되는 프롬프트');
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    unlock();

    // 폴링 재조회 응답이 실제로 돌아왔는지를 응답 횟수로 확정한다 — 고정 sleep 대신
    await expect.poll(() => responded, { timeout: 15_000 }).toBeGreaterThanOrEqual(2);

    // 재조회 뒤에도 계속 고치면(입력할 때마다 그 칸이 다시 검증된다) 최신 기준으로 판정이 돌아간다 —
    // 이 한 번의 검증이 있어야 재조회가 스키마 기준에 반영되는지 실제로 볼 수 있다
    await page.locator('#job-prompt').fill('재조회 뒤에도 저장되는 프롬프트 (두 번째 수정)');

    // 재조회로 주기가 바뀐 뒤에도 저장이 막히지 않는다(수정 전: 검증 기준이 최신 job 이라
    // 「실행 주기를 설정하세요」로 저장 버튼이 꺼졌다)
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeEnabled();
  });

  test('재조회로 트리거가 바뀌어도 주기 없는 안내가 제멋대로 뜨지 않는다 (WD-1109 반려 2)', async ({
    authenticatedPage: page,
  }) => {
    // 안내(cronRequiredHint)·판정(triggerUnchanged)의 기준도 resolverSchema 와 같은 baseJob 이어야 한다 —
    // 최신 조회 값을 쓰면 편집 중 다른 곳이 트리거를 바꾼 순간 안내만 먼저 뒤집혀
    // 「저장은 켜져 있는데 실행 주기를 설정하세요」가 동시에 보였다.
    await mockProactivePermissions(page);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/7/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/7/anomaly-events', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    // 프롬프트를 고친 뒤에야 재조회 응답을 풀어, 시드·기준이 멈춘 시점(주기 없는 스케줄)이 재조회 전 값으로
    // 확정되게 한다 — 응답이 dirty 이전에 새면 시드까지 따라가 이 시험이 흔들린다.
    let unlock: () => void = () => {};
    const fillDone = new Promise<void>((resolve) => {
      unlock = resolve;
    });
    let arrived = 0;
    let responded = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs/7',
      async (route: Route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const nth = ++arrived;
        // 첫 응답은 그대로 두고, 둘째 응답부터는 프롬프트를 고친 뒤에야 보낸다
        if (nth > 1) await fillDone;
        const job = createJob({
          id: 7,
          triggerType: nth === 1 ? 'SCHEDULE' : 'BOTH',
          cronExpression: null,
          lastExecution: createJobExecution({ status: 'RUNNING' }),
        });
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(job) });
        responded += 1;
      },
    );

    await page.goto('/ai-insights/jobs/7');
    await expect(page.getByRole('heading', { level: 1, name: '매일 현황 리포트' })).toBeVisible();
    await page.getByRole('button', { name: '편집' }).click();

    // 트리거는 저장된 그대로(스케줄) 두고 프롬프트만 고친다 — 주기 없는 스케줄 수정은 WD-995 규칙으로 저장 가능하다
    await page.locator('#job-prompt').fill('재조회 뒤에도 안내가 뒤집히지 않는 프롬프트');
    // 미저장 표시가 뜬 뒤에야 재조회를 풀어, 시드·기준이 이 시점의 값(스케줄·주기 없음)에 멈추게 한다
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    unlock();

    // 폴링 재조회가 실제로 한 번 더 일어났는지 확인한다(고정 sleep 대신 응답 횟수로)
    await expect.poll(() => responded, { timeout: 15_000 }).toBeGreaterThanOrEqual(2);

    // 안내 자리(직접 입력 칸의 cron 오류)가 실제로 그려질 수 있는 상태인지 먼저 확인한다 —
    // 트리거가 이상 탐지면 주기 칸 자체가 사라져 시험이 아무것도 보지 않고 통과할 수 있다
    await expect(page.locator('#job-cron-preset')).toBeVisible();
    // 최신 job 은 다른 곳이 바꾼 'BOTH' 로 바뀌었지만, 기준(baseJob)은 여전히 'SCHEDULE' —
    // 안내는 뜨지 않고 저장은 켜져 있다(수정 전: 최신 job 기준으로 두 판정이 갈렸다)
    await expect(page.getByText(UNSET)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeEnabled();
  });
});
