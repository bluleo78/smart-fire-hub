/**
 * 스마트 작업 편집 창 「다음 실행」 — 서버 미리보기 (WD-391) E2E
 *
 * 예전에는 화면이 cron-parser 로 다음 실행을 따로 계산해, 서머타임 전환일에 서버 스케줄러의 실제 실행과 달랐다.
 * 이제 서버 미리보기 API(GET /api/v1/proactive/jobs/cron-preview, 스케줄러와 같은 규칙)가 준 시각을 보인다.
 * - 폼의 cron·시간대가 쿼리로 전달되는지, 응답 시각이 그 시간대 약어(EDT 등)와 함께 표시되는지,
 *   서버가 거부(400)하면 그 문구를 안내하는지 검증한다.
 */
import { mockProactivePermissions, setupNewJobMocks } from '../../fixtures/ai-insight.fixture';
import { expect, test } from '../../fixtures/auth.fixture';
import { JOB_CRON_PREVIEW_PATH, mockCronPreview } from '../../fixtures/cron-preview.fixture';

test.describe('스마트 작업 — 다음 실행 서버 미리보기 (WD-391)', () => {
  test.use({ timezoneId: 'Asia/Seoul' });

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
    await setupNewJobMocks(page);
  });

  test('기본 주기(매일 09:00, 서울)의 다음 실행을 서버 값으로 보인다', async ({ authenticatedPage: page }) => {
    const preview = await mockCronPreview(page, JOB_CRON_PREVIEW_PATH, ['2026-01-02T00:00:00Z']);

    await page.goto('/ai-insights/jobs/new');

    // 서버가 준 UTC 00:00 = 서울 09:00
    await expect(page.getByText(/다음 실행:/)).toContainText('09:00');
    await expect(page.getByText(/다음 실행:/)).toContainText('KST');
    const last = preview.lastRequest();
    expect(last?.searchParams.get('cron')).toBe('0 9 * * *');
    expect(last?.searchParams.get('timezone')).toBe('Asia/Seoul');
    expect(last?.searchParams.get('count')).toBe('1');
  });

  test('서머타임 해제일 — 직접 입력한 cron 과 뉴욕 시간대를 보내고, 서버가 준 1:30 EDT 를 보인다', async ({
    authenticatedPage: page,
  }) => {
    // 뉴욕 해제일(2026-11-01) 매일 1:30 은 겹친 1시대 중 첫 번째(EDT, 05:30Z) 한 번만 돈다
    const preview = await mockCronPreview(page, JOB_CRON_PREVIEW_PATH, ['2026-11-01T05:30:00Z']);
    await page.goto('/ai-insights/jobs/new');

    await page.locator('#job-timezone').click();
    await page.getByRole('option', { name: /^America\/New_York/ }).click();
    await page.locator('#job-cron-preset').click();
    await page.getByRole('option', { name: '직접 입력' }).click();
    await page.getByPlaceholder('0 9 * * *').fill('30 1 * * *');

    await expect
      .poll(() => preview.lastRequest()?.searchParams.get('cron'))
      .toBe('30 1 * * *');
    expect(preview.lastRequest()?.searchParams.get('timezone')).toBe('America/New_York');
    // 고정 표의 약어(EST)가 아니라 그 날짜의 실제 약어(EDT)를 붙인다
    await expect(page.getByText(/다음 실행:/)).toContainText('01:30');
    await expect(page.getByText(/다음 실행:/)).toContainText('EDT');
  });

  test('서버가 거부하면(400) 그 문구로 계산할 수 없다고 안내한다', async ({ authenticatedPage: page }) => {
    await mockCronPreview(page, JOB_CRON_PREVIEW_PATH, [], {
      status: 400,
      message: '유효하지 않은 cron 표현식입니다: "61 9 * * *" — 「분」 칸의 값 "61" 이(가) 올바르지 않습니다 (허용 범위 0~59)',
    });
    await page.goto('/ai-insights/jobs/new');

    await expect(page.getByText(/다음 실행을 계산할 수 없습니다: 유효하지 않은 cron 표현식입니다/)).toBeVisible();
    await expect(page.getByText(/다음 실행:/)).toHaveCount(0);
  });
});
