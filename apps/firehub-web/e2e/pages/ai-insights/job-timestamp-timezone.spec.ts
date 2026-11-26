import type { Page } from '@playwright/test';

import { createJob, createJobExecution, createReport } from '../../factories/ai-insight.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 타임스탬프 타임존 해석 E2E 회귀 테스트 (#349)
 *
 * 백엔드는 `LocalDateTime`을 타임존 표기 없이 직렬화한다(`2026-07-30T14:01:12`). 프로젝트 계약상
 * 이 문자열은 **UTC**로 해석해야 하는데, 목록의 `timeAgo`는 `new Date(str)`로 브라우저 로컬(KST)
 * 파싱을, 상세의 `formatDate`는 UTC 파싱을 해서 같은 값이 두 화면에서 정확히 9시간 어긋났다.
 *
 * 이 스펙은 "타임존 없는 UTC 문자열"을 실제로 넣고 목록/상세가 같은 시각을 가리키는지 검증한다.
 * 하드코딩된 문자열이 아니라 현재 시각 기준으로 만들어야 상대시간("N분 전")을 단정할 수 있다.
 */

/** Date → 백엔드 직렬화 형식(타임존 표기 없는 UTC 벽시계) 문자열 */
function toServerLocalDateTime(d: Date): string {
  return d.toISOString().replace('Z', '').replace(/\.\d+$/, '');
}

test.describe('스마트 작업 타임스탬프 타임존 (#349)', () => {
  test('타임존 없는 UTC 문자열을 목록은 "N분 전"으로, 상세는 같은 시각으로 표시한다', async ({
    authenticatedPage: page,
  }) => {
    // 5분 전(UTC)에 실행된 것으로 꾸민다 — 목록은 "5분 전"이어야 한다
    // eslint-disable-next-line no-restricted-syntax -- epoch 숫자 인자(서버 날짜 문자열 아님, #691 대상 아님)
    const executedAt = new Date(Date.now() - 5 * 60 * 1000);
    const job = createJob({
      id: 1,
      name: '타임존 검증 작업',
      lastExecutedAt: toServerLocalDateTime(executedAt),
      // 다음 실행은 이 테스트 관심사가 아니므로 비운다
      nextExecuteAt: null,
      // WD-1097: 마지막 실행은 작업 시간대(약어 포함)로 보이므로 시간대를 명시한다
      timezone: 'Asia/Seoul',
    });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [job]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // 목록 — timeAgo가 로컬 파싱을 하면 KST 브라우저에서 "9시간 전"이 되어 실패한다
    const row = page.getByRole('row').filter({ hasText: '타임존 검증 작업' });
    await expect(row).toContainText('5분 전');
    await expect(row).not.toContainText('시간 전');

    // 상세 — 목록과 같은 시각(UTC 파싱)을 작업 시간대(Asia/Seoul) 기준 「HH:MM KST」로 보여야 한다(WD-1097).
    // 미래 시각이 아니라 방금 지난 시각으로 렌더되는지 확인한다.
    await openJobDetail(page, job);

    // 기대 문자열은 브라우저 안에서 만든다 — Node와 브라우저의 ko-KR ICU 출력이 달라
    // 테스트 프로세스에서 만든 문자열로 비교하면 표기 차이로 헛되이 깨진다
    // WD-1097: 상세 「마지막 실행」은 작업 시간대(Asia/Seoul) 시각 + 약어(KST)로 보인다.
    const expectedTime = await page.evaluate(
      (iso) =>
        // eslint-disable-next-line no-restricted-syntax -- 인자는 toISOString() 결과(Z 포함) — 타임존이 명시돼 #691 대상 아님
        new Date(iso).toLocaleTimeString('ko-KR', {
          timeZone: 'Asia/Seoul',
          hour: '2-digit',
          minute: '2-digit',
          hour12: false,
        }),
      executedAt.toISOString(),
    );
    await expect(lastRunValue(page)).toContainText(`${expectedTime} KST`);
  });
});

/** 「마지막 실행」 카드의 값 영역(라벨 p 의 다음 형제 div) */
function lastRunValue(page: Page) {
  return page
    .getByText('마지막 실행', { exact: true })
    .locator('xpath=following-sibling::div[1]');
}

/** 작업 상세(id 1) 진입 — 상세가 부르는 API 를 모킹하고 상세로 간다 */
async function openJobDetail(page: Page, job: ReturnType<typeof createJob>) {
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', job);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
  await page.goto('/ai-insights/jobs/1');
}

test.describe('스마트 작업 마지막 실행 시간대 (WD-1097)', () => {
  // 컨테이너 기본처럼 브라우저 시간대를 UTC 로 둔다 — 브라우저 시간으로 찍으면 날짜가 하루 앞서 보이던 재현 조건
  test.use({ timezoneId: 'UTC' });

  /** 16:39:05 UTC(= 다음 날 01:39 KST)에 마지막으로 실행된 매일 09시 작업 */
  const jobIn = (timezone: string) =>
    createJob({
      id: 1,
      lastExecutedAt: '2026-11-24T16:39:05',
      nextExecuteAt: '2026-11-25T00:00:00',
      timezone,
      cronExpression: '0 9 * * *',
    });

  test('브라우저가 UTC 여도 마지막 실행은 작업 시간대(KST) 날짜·시각으로 보인다 (WD-1097)', async ({
    authenticatedPage: page,
  }) => {
    await openJobDetail(page, jobIn('Asia/Seoul'));
    const value = lastRunValue(page);
    await expect(value).toHaveText(/2026\. 11\. 25\..*01:39 KST$/);
    await expect(value).not.toContainText('오후 4:39');
  });

  test('작업 시간대가 UTC+9 여도 상세가 깨지지 않고 마지막 실행이 보인다 (WD-1097)', async ({
    authenticatedPage: page,
  }) => {
    await openJobDetail(page, jobIn('UTC+9'));
    // Intl 이 거부하는 시간대는 브라우저 시간대(여기서는 UTC)로 대신 보인다 — 16:39 UTC 그대로
    await expect(lastRunValue(page)).toHaveText(/16:39 UTC$/);
  });
});

// 서버 시각 16:39:05 UTC = 작업 시간대(Asia/Seoul) 다음 날 01:39 — 팩토리 기본 시간대가 Asia/Seoul 이다
const AT = '2026-11-24T16:39:05';
const SEOUL_TEXT = '2026. 11. 25. (수) 01:39 KST';

test.describe('스마트 작업 실행·리포트·생성 시각 시간대 (WD-1101)', () => {
  // 브라우저는 UTC — 브라우저 시간대로 찍으면 날짜가 하루 앞서 보이던 재현 조건
  test.use({ timezoneId: 'UTC' });

  test('실행 이력 시작 시각은 작업 시간대+약어', async ({ authenticatedPage: page }) => {
    // 서버 시각 16:39:05 UTC = 작업 시간대(Asia/Seoul) 다음 날 01:39
    await openJobDetail(page, createJob({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', [
      createJobExecution({ id: 5, jobId: 1, startedAt: AT, completedAt: '2026-11-24T16:40:05' }),
    ]);
    await page.reload();
    await page.getByRole('tab', { name: '실행 이력' }).click();

    const row = page.getByRole('row').filter({ hasText: '2026. 11. 25.' });
    await expect(row).toContainText(SEOUL_TEXT);
    // 상대시간은 같은 칸에 괄호로 이어진다
    await expect(row).toContainText(/01:39 KST\s*\(/);
    await expect(row).not.toContainText('오후 4:39:05');
  });

  test('생성일은 작업 시간대+약어', async ({ authenticatedPage: page }) => {
    await openJobDetail(
      page,
      createJob({ id: 1, createdAt: AT }),
    );
    const value = page
      .getByText('생성일', { exact: true })
      .locator('xpath=following-sibling::div[1]');
    await expect(value).toContainText(SEOUL_TEXT);
  });

  test('리포트 목록은 행마다 그 작업 시간대', async ({ authenticatedPage: page }) => {
    await mockReports(page, [
      createReport({ executionId: 1, title: '서울 리포트', completedAt: AT }),
      createReport({ executionId: 2, title: '뉴욕 리포트', jobTimezone: 'America/New_York', completedAt: AT }),
    ]);
    await page.goto('/ai-insights/reports');
    await expect(page.getByRole('row').filter({ hasText: '서울 리포트' })).toContainText(
      SEOUL_TEXT,
    );
    // formatNextRunWithZone 이 11월 뉴욕을 EST 로 낸다(next-run.test.ts 와 동일)
    await expect(page.getByRole('row').filter({ hasText: '뉴욕 리포트' })).toContainText(
      '2026. 11. 24. (화) 11:39 EST',
    );
  });

  test('jobTimezone 없는 리포트 행은 Asia/Seoul', async ({ authenticatedPage: page }) => {
    await mockReports(page, [
      createReport({ executionId: 3, title: '무시간대 리포트', jobTimezone: null, completedAt: AT }),
    ]);
    await page.goto('/ai-insights/reports');
    await expect(page.getByRole('row').filter({ hasText: '무시간대 리포트' })).toContainText(
      SEOUL_TEXT,
    );
  });
});

/**
 * 실행 상세 화면이 부르는 API 모킹 후 진입 (WD-1175) — 작업 응답(시간대 출처)도 여기서 목한다.
 * status 를 주면 그 상태로 작업 조회를 실패시킨다.
 */
async function openExecutionDetail(page: Page, job: unknown, options?: { status: number }) {
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', job, options);
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await mockApi(
    page,
    'GET',
    '/api/v1/proactive/jobs/1/executions/5',
    createJobExecution({
      id: 5,
      jobId: 1,
      status: 'COMPLETED',
      startedAt: AT,
      completedAt: '2026-11-24T16:40:05',
    }),
  );
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/5/html', '', { status: 404 });
  await page.goto('/ai-insights/jobs/1/executions/5');
}

/** 실행 상세 「실행 시각」 카드의 값 줄 */
function executionTimeValue(page: Page) {
  return page.getByText('실행 시각', { exact: true }).locator('xpath=following-sibling::p[1]');
}

test.describe('실행 상세 실행 시각 시간대 (WD-1175)', () => {
  // 브라우저는 UTC — 브라우저 시간대로 찍으면 날짜가 하루 앞서 보이던 재현 조건
  test.use({ timezoneId: 'UTC' });

  test('실행 상세 실행 시각은 작업 시간대+약어 (WD-1175)', async ({ authenticatedPage: page }) => {
    await openExecutionDetail(page, createJob({ id: 1, timezone: 'Asia/Seoul' }));
    await expect(executionTimeValue(page)).toHaveText(SEOUL_TEXT);
  });

  test('실행 상세 실행 시각은 작업이 뉴욕이면 뉴욕 시각 (WD-1175)', async ({
    authenticatedPage: page,
  }) => {
    await openExecutionDetail(page, createJob({ id: 1, timezone: 'America/New_York' }));
    await expect(executionTimeValue(page)).toHaveText('2026. 11. 24. (화) 11:39 EST');
  });

  // 작업 시간대를 모르면 Asia/Seoul 로 단정하지 않고 브라우저 시간대(여기서는 UTC)+약어로 보인다 — 뉴욕 작업이 KST 로 잘못 읽히지 않게
  test('작업을 읽지 못하면 실행 시각은 브라우저 시간대+약어로 보인다 (WD-1175)', async ({
    authenticatedPage: page,
  }) => {
    await openExecutionDetail(page, { message: '작업을 찾을 수 없습니다.' }, { status: 404 });
    await expect(executionTimeValue(page)).toHaveText('2026. 11. 24. (화) 16:39 UTC');
  });
});

/** 리포트 목록 화면이 부르는 API 모킹 */
async function mockReports(page: Page, reports: ReturnType<typeof createReport>[]) {
  await mockApi(page, 'GET', '/api/v1/proactive/reports', reports);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs', []);
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
}
