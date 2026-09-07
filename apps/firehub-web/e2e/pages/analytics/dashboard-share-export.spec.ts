import { setupDashboardEditorMocks } from '../../fixtures/analytics.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 이슈 #99: 대시보드 상세 — 공유/PDF 다운로드 진입점 부재
 * 이슈 #522: 비공개 대시보드에서도 "공유 링크 복사"가 활성화돼 있어
 *            받는 사람이 실제로는 접근 거부당하는 문제 수정
 *
 * 대시보드 상세 페이지 헤더에 공유 링크 복사 버튼과 PDF 내보내기(인쇄) 버튼이
 * 노출되고, 클릭 시 각각 클립보드 복사·window.print 호출이 동작하는지 검증한다.
 * 공유 링크 복사는 대시보드가 공개(isShared)인 경우에만 실제 복사가 동작해야 하며,
 * 비공개인 경우 복사 대신 안내 토스트만 노출해야 한다(백엔드가 비공개 링크를 거부하므로).
 */
test.describe('대시보드 — 공유 / PDF 내보내기 (#99)', () => {
  test('공개 대시보드에서 "공유 링크 복사" 버튼 클릭 시 현재 URL이 클립보드에 복사된다', async ({
    authenticatedPage: page,
    context,
  }) => {
    await setupDashboardEditorMocks(page, 1, { isShared: true });

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

  test('비공개 대시보드에서 "공유 링크 복사" 클릭 시 복사 대신 안내 토스트만 뜨고 클립보드는 변경되지 않는다 (#522)', async ({
    authenticatedPage: page,
    context,
  }) => {
    await setupDashboardEditorMocks(page, 1, { isShared: false });

    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    // 클립보드에 초기값을 심어두고, 클릭 후에도 그대로인지(=복사가 일어나지 않았는지) 확인한다
    await page.goto('/analytics/dashboards/1');
    await expect(page.getByRole('heading', { name: '테스트 대시보드' })).toBeVisible();
    await page.evaluate(() => navigator.clipboard.writeText('__unchanged__'));

    const shareButton = page.getByRole('button', { name: '공유 링크 복사' });
    await expect(shareButton).toBeVisible();

    await shareButton.click();

    // 비공개 안내 토스트가 떠야 하고, 성공 토스트는 뜨지 않아야 한다
    await expect(
      page.getByText('이 대시보드는 비공개입니다. 다른 사용자와 공유하려면 목록에서 "공개 대시보드"를 먼저 켜세요.'),
    ).toBeVisible({ timeout: 3000 });
    await expect(page.getByText('대시보드 링크가 복사되었습니다.')).not.toBeVisible();

    // 클립보드가 변경되지 않았어야 한다(실제로 복사를 시도하지 않음)
    const clipboardText = await page.evaluate(() => navigator.clipboard.readText());
    expect(clipboardText).toBe('__unchanged__');
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
