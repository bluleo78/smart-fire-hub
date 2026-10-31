import type { Page } from '@playwright/test';

import { mockProactivePermissions, setupNewJobMocks } from '../../fixtures/ai-insight.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 「타임존」 이름표 회귀 테스트(WD-417).
 *
 * 약어·UTC 시차가 연중 고정 문자열이라 서머타임 기간에 틀렸다 — 10월 뉴욕을 「EST, UTC-5」(실제 EDT, UTC-4)로, 시드니를
 * 「AEST, UTC+10」(실제 AEDT, UTC+11)로 보였고 「다음 실행」도 「01:30 EST」였다. 시계를 고정해 그 날짜 기준 이름표가 나오는지 본다.
 */

/** 새 작업 화면에서 실행 주기를 직접 입력(30 1 * * *)으로 두고 타임존을 고른다. */
async function pickTimezone(page: Page, tz: string) {
  // 새 작업 양식은 proactive:write 가 있어야 보인다(WD-133)
  await mockProactivePermissions(page);
  await setupNewJobMocks(page);
  await page.goto('/ai-insights/jobs/new');
  await page.locator('#job-cron-preset').click();
  await page.getByRole('option', { name: '직접 입력' }).click();
  await page.getByPlaceholder('0 9 * * *').fill('30 1 * * *');
  await page.locator('#job-timezone').click();
  await page.getByRole('option', { name: new RegExp(`^${tz} `) }).click();
}

test.describe('스마트 작업 타임존 이름표 — 날짜 기준 약어·시차 (WD-417)', () => {
  test('서머타임 기간(10월)에는 뉴욕을 EDT·UTC-4, 시드니를 AEDT·UTC+11 로 보이고 다음 실행에도 그 약어를 붙인다', async ({
    authenticatedPage: page,
  }) => {
    // 2026-10-29 12:00 UTC — 뉴욕은 EDT, 시드니는 AEDT 기간
    // eslint-disable-next-line no-restricted-syntax -- 오프셋(Z)을 명시한 고정 문자열 — 브라우저 존 해석 오류 없음(#691 대상 아님)
    await page.clock.setFixedTime(new Date('2026-10-29T12:00:00Z'));
    await pickTimezone(page, 'America/New_York');

    await expect(page.getByText('다음 실행:')).toContainText(/01:30 EDT/);
    await expect(page.locator('#job-timezone')).toContainText('America/New_York (EDT, UTC-4)');

    // 목록의 다른 시간대도 같은 날짜 기준이다
    await page.locator('#job-timezone').click();
    await expect(page.getByRole('option', { name: 'Australia/Sydney (AEDT, UTC+11)' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Europe/London (GMT, UTC+0)' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Asia/Seoul (KST, UTC+9)' })).toBeVisible();
    await page.getByRole('option', { name: 'Australia/Sydney (AEDT, UTC+11)' }).click();
    await expect(page.getByText('다음 실행:')).toContainText(/01:30 AEDT/);
  });

  test('표준시 기간(11월 중순)에는 뉴욕을 EST·UTC-5 로 보인다', async ({ authenticatedPage: page }) => {
    // 2026-11-10 — 뉴욕 서머타임(11-01 종료) 뒤
    // eslint-disable-next-line no-restricted-syntax -- 오프셋(Z)을 명시한 고정 문자열 — 브라우저 존 해석 오류 없음(#691 대상 아님)
    await page.clock.setFixedTime(new Date('2026-11-10T12:00:00Z'));
    await pickTimezone(page, 'America/New_York');

    await expect(page.locator('#job-timezone')).toContainText('America/New_York (EST, UTC-5)');
    await expect(page.getByText('다음 실행:')).toContainText(/01:30 EST/);
  });
});
