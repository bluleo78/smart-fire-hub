import { setupDashboardEditorMocks } from '../../fixtures/analytics.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 이슈 #99: 대시보드 상세 — 공유/PDF 다운로드 진입점 부재
 *
 * 대시보드 상세 페이지 헤더에 공유 링크 복사 버튼과 PDF 내보내기(인쇄) 버튼이
 * 노출되고, 클릭 시 각각 클립보드 복사·window.print 호출이 동작하는지 검증한다.
 */
test.describe('대시보드 — 공유 / PDF 내보내기 (#99)', () => {
  test('"공유 링크 복사" 버튼 클릭 시 현재 URL이 클립보드에 복사된다', async ({
    authenticatedPage: page,
    context,
  }) => {
    await setupDashboardEditorMocks(page, 1);

    // 클립보드 권한 부여 (chromium에서만 가능)
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);

    await page.goto('/analytics/dashboards/1');
    await expect(page.getByRole('heading', { name: '테스트 대시보드' })).toBeVisible();

    // 공유 버튼이 헤더에 표시되어야 한다
    const shareButton = page.getByRole('button', { name: '공유 링크 복사' });
    await expect(shareButton).toBeVisible();

    await shareButton.click();

    // 토스트 안내가 나와야 한다
    await expect(page.getByText('대시보드 링크가 복사되었습니다.')).toBeVisible({ timeout: 3000 });

    // 클립보드 내용 검증 — 현재 URL이 복사되어야 한다
    const clipboardText = await page.evaluate(() => navigator.clipboard.readText());
    expect(clipboardText).toContain('/analytics/dashboards/1');
  });

  test('"PDF로 내보내기" 버튼 클릭 시 window.print가 호출된다', async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);

    await page.goto('/analytics/dashboards/1');
    await expect(page.getByRole('heading', { name: '테스트 대시보드' })).toBeVisible();

    // window.print 호출 카운트를 추적
    await page.evaluate(() => {
      (window as unknown as { __printCalls: number }).__printCalls = 0;
      const original = window.print.bind(window);
      window.print = () => {
        (window as unknown as { __printCalls: number }).__printCalls += 1;
        // 실제로 인쇄 다이얼로그가 뜨면 테스트가 멈추므로 호출 추적만 한다
        void original;
      };
    });

    const exportButton = page.getByRole('button', { name: 'PDF로 내보내기' });
    await expect(exportButton).toBeVisible();
    await exportButton.click();

    const calls = await page.evaluate(
      () => (window as unknown as { __printCalls: number }).__printCalls
    );
    expect(calls).toBe(1);
  });
});
