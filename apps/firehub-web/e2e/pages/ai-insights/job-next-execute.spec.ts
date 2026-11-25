import type { Page } from '@playwright/test';

import { createAdminJobRow, createJob } from '../../factories/ai-insight.factory';
import { mockProactiveAdminPermissions, setupJobListMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 목록 — "다음 실행" 컬럼 E2E 회귀 테스트 (#348)
 *
 * 백엔드가 `next_execute_at`을 채우지 않아 전 작업이 상시 `-`였다. 백엔드가 값을 채우게 된 뒤
 * 프론트가 그 값을 올바른 타임존으로 해석해 표시하는지 검증한다. 서버는 타임존 표기 없는 UTC
 * 벽시계를 보내므로, `new Date()`로 파싱하면 9시간 어긋난다 (#349와 같은 계약).
 */

/** Date → 백엔드 직렬화 형식(타임존 표기 없는 UTC 벽시계) 문자열 */
function toServerLocalDateTime(d: Date): string {
  return d.toISOString().replace('Z', '').replace(/\.\d+$/, '');
}

/** 지금으로부터 N일 뒤, 서울 기준 09:00 정각의 UTC 시각 (서울 09:00 = UTC 00:00) */
function seoulNineAmInDays(days: number): Date {
  // eslint-disable-next-line no-restricted-syntax -- epoch 숫자 인자(서버 날짜 문자열 아님, #691 대상 아님)
  const d = new Date(Date.now() + days * 24 * 60 * 60 * 1000);
  d.setUTCHours(0, 0, 0, 0);
  return d;
}

test.describe('스마트 작업 목록 — 다음 실행 컬럼 (#348)', () => {
  test('next_execute_at 이 있으면 잡 타임존 기준 시각으로 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // 3일 뒤 서울 09:00 — "오늘/내일" 분기를 피해 "M월 D일 09:00" 형식으로 고정된다
    const nextRun = seoulNineAmInDays(3);
    const job = createJob({
      id: 1,
      name: '다음 실행 표시 작업',
      enabled: true,
      timezone: 'Asia/Seoul',
      nextExecuteAt: toServerLocalDateTime(nextRun),
    });

    await setupJobListMocks(page, 0, [job]);
    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: '다음 실행 표시 작업' });

    // 값이 있는데도 '-'로 남던 것이 이 이슈의 현상 — 시각이 실제로 렌더돼야 한다
    await expect(row).toContainText('09:00');

    // 서울 09:00을 로컬 파싱하면 18:00이 되어 어긋난다 — 계약 위반 회귀 방지
    await expect(row).not.toContainText('18:00');
  });

  test('이미 지난 다음 실행 시각은 「내일」이 아니라 날짜로 보인다 (WD-1099)', async ({
    authenticatedPage: page,
  }) => {
    // 2일 전 서울 09:00 — 서버가 아직 갱신하지 못한 지난 시각
    const past = seoulNineAmInDays(-2);
    const job = createJob({
      id: 3,
      name: '지난 다음 실행 작업',
      enabled: true,
      triggerType: 'SCHEDULE',
      cronExpression: '0 9 * * *',
      timezone: 'Asia/Seoul',
      nextExecuteAt: toServerLocalDateTime(past),
    });

    await setupJobListMocks(page, 0, [job]);
    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: '지난 다음 실행 작업' });
    const dateText = past.toLocaleDateString('ko-KR', {
      timeZone: 'Asia/Seoul',
      month: 'long',
      day: 'numeric',
    });
    await expect(row).toContainText('09:00');
    await expect(row).toContainText('KST');
    await expect(row).toContainText(dateText);
    await expect(row).not.toContainText('내일');
  });

  test('비활성 작업은 다음 실행이 없으므로 "-"로 표시된다', async ({ authenticatedPage: page }) => {
    // 비활성 잡은 스케줄이 해제되므로 백엔드가 next_execute_at 을 비운다
    const job = createJob({
      id: 2,
      name: '비활성 작업',
      enabled: false,
      timezone: 'Asia/Seoul',
      nextExecuteAt: null,
    });

    await setupJobListMocks(page, 0, [job]);
    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: '비활성 작업' });
    await expect(row).toContainText('-');
    await expect(row).not.toContainText('09:00');
  });
});

/**
 * 상세·목록 「다음 실행」 작업 시간대 + 약어 (WD-1077)
 *
 * 상세 카드가 formatDate 로 브라우저 시간대·이름 없이 보여(UTC 브라우저에서 「오전 12:00」) 바로 위
 * 「타임존 Asia/Seoul」 카드·편집 미리보기(「09:00 KST」)와 같은 실행이 다른 시각처럼 보였다.
 * 브라우저를 UTC 로 고정해 작업 시간대 기준 시각과 그 시각의 약어(서머타임 반영)가 나오는지 본다.
 */
test.describe('스마트 작업 「다음 실행」 작업 시간대 표기 (WD-1077)', () => {
  test.use({ timezoneId: 'UTC' });

  async function mockDetail(page: Page, job: ReturnType<typeof createJob>) {
    await setupJobListMocks(page, 0, [job]);
    await mockApi(page, 'GET', `/api/v1/proactive/jobs/${job.id}`, job);
    await mockApi(page, 'GET', `/api/v1/proactive/jobs/${job.id}/executions`, []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
  }

  test('서울 작업 — 상세는 「09:00 KST」, 목록도 KST', async ({ authenticatedPage: page }) => {
    // 3일 뒤 UTC 00:00 = KST 09:00 (현재 시각 기준 — 오늘/내일 분기를 피한다)
    const job = createJob({
      id: 31,
      name: '서울 다음 실행 작업',
      enabled: true,
      triggerType: 'SCHEDULE',
      cronExpression: '0 9 * * *',
      timezone: 'Asia/Seoul',
      nextExecuteAt: toServerLocalDateTime(seoulNineAmInDays(3)),
    });
    await mockDetail(page, job);

    await page.goto('/ai-insights/jobs/31');
    const card = page.getByText('다음 실행', { exact: true }).locator('..');
    await expect(card).toContainText('09:00 KST');
    await expect(card).not.toContainText('오전 12:00');

    await page.goto('/ai-insights/jobs');
    const row = page.getByRole('row').filter({ hasText: '서울 다음 실행 작업' });
    await expect(row).toContainText(/09:00 KST/);
  });

  test('뉴욕 작업(겨울) — 상세는 EST, 목록도 EST', async ({ authenticatedPage: page }) => {
    // 뉴욕 09:00 EST = UTC 14:00. 서머타임(EDT)이면 시각이 달라지므로 다가오는 1월 15일(겨울)로 잡는다 — 현재 시각 기준.
    const now = new Date();
    // 이번 해 1월 15일이 이미 지났으면(15일 포함 이후) 다음 해로 — 늘 앞으로 올 겨울 날짜가 되게 한다.
    const passed = now.getUTCMonth() > 0 || now.getUTCDate() >= 15;
    const year = passed ? now.getUTCFullYear() + 1 : now.getUTCFullYear();
    // eslint-disable-next-line no-restricted-syntax -- epoch 숫자 인자(서버 날짜 문자열 아님, #691 대상 아님)
    const winterNine = new Date(Date.UTC(year, 0, 15, 14, 0, 0));
    const job = createJob({
      id: 32,
      name: '뉴욕 다음 실행 작업',
      enabled: true,
      triggerType: 'SCHEDULE',
      cronExpression: '0 9 * * *',
      timezone: 'America/New_York',
      nextExecuteAt: toServerLocalDateTime(winterNine),
    });
    await mockDetail(page, job);

    await page.goto('/ai-insights/jobs/32');
    const card = page.getByText('다음 실행', { exact: true }).locator('..');
    await expect(card).toContainText('09:00 EST');

    await page.goto('/ai-insights/jobs');
    const row = page.getByRole('row').filter({ hasText: '뉴욕 다음 실행 작업' });
    await expect(row).toContainText(/09:00 EST/);
    await expect(row).not.toContainText('14:00');
  });
});

/**
 * 「다음 실행」이 이미 지난 시각이면 「· 지남」, 내일은 그 시간대 달력 기준 (WD-1147)
 *
 * 서버가 아직 갱신하지 못한 과거 시각이 「오늘 09:00 KST」로만 보여 곧 실행될 것처럼 읽혔다.
 * 시계를 KST 2026-04-11 15:00 (= 2026-04-11T06:00:00Z)으로 고정해 과거(오늘 09:00)·미래(내일 09:00)
 * 두 행의 표기를 내 작업·워크스페이스 전체 두 화면에서 모두 확인한다.
 */
test.describe('스마트 작업 「다음 실행」 지남 표기 (WD-1147)', () => {
  const FIXED_NOW = new Date('2026-04-11T06:00:00Z');
  /** 서버 직렬화 형식(타임존 없는 UTC 벽시계) — 오늘 KST 09:00 = 이미 지남 */
  const PAST = '2026-04-11T00:00:00';
  /** 내일 KST 09:00 = 아직 오지 않음 */
  const FUTURE = '2026-04-12T00:00:00';

  test('내 작업 — 지난 행은 「오늘 09:00 KST · 지남」, 미래 행은 「내일 09:00 KST」', async ({
    authenticatedPage: page,
  }) => {
    await page.clock.setFixedTime(FIXED_NOW);
    await setupJobListMocks(page, 0, [
      createJob({ id: 1, name: '지남 작업', enabled: true, timezone: 'Asia/Seoul', nextExecuteAt: PAST }),
      createJob({ id: 2, name: '내일 작업', enabled: true, timezone: 'Asia/Seoul', nextExecuteAt: FUTURE }),
    ]);
    await page.goto('/ai-insights/jobs');

    const pastRow = page.getByRole('row').filter({ hasText: '지남 작업' });
    await expect(pastRow).toContainText('오늘 09:00 KST · 지남');

    const futureRow = page.getByRole('row').filter({ hasText: '내일 작업' });
    await expect(futureRow).toContainText('내일 09:00 KST');
    await expect(futureRow).not.toContainText('지남');
  });

  test('워크스페이스 전체 — 지난 행에만 「· 지남」이 붙는다', async ({ authenticatedPage: page }) => {
    await page.clock.setFixedTime(FIXED_NOW);
    await mockProactiveAdminPermissions(page);
    await setupJobListMocks(page, 0, []);
    await mockApi(page, 'GET', '/api/v1/proactive/admin/jobs', [
      createAdminJobRow({ id: 10, name: '지남 작업', nextExecuteAt: PAST }),
      createAdminJobRow({ id: 11, name: '내일 작업', nextExecuteAt: FUTURE }),
    ]);
    await page.goto('/ai-insights/jobs?view=workspace');

    const pastRow = page.getByTestId('workspace-job-10');
    await expect(pastRow).toContainText('오늘 09:00 KST · 지남');

    const futureRow = page.getByTestId('workspace-job-11');
    await expect(futureRow).toContainText('내일 09:00 KST');
    await expect(futureRow).not.toContainText('지남');
  });
});
