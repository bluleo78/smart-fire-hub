import { mockProactivePermissions, setupJobDetailMocks, setupJobListMocks } from '../../fixtures/ai-insight.fixture';
import { expect, test } from '../../fixtures/auth.fixture';
import { tabUntilFocused } from '../../fixtures/keyboard-focus';

/**
 * 스마트 작업 목록 — 작업명이 키보드로 열리는 상세 링크여야 한다 (WD-927, WCAG 2.1.1).
 * 행 onClick 은 마우스 편의일 뿐이라, 키보드·스크린리더 사용자는 이 링크로만 상세에 닿는다.
 */
test.describe('스마트 작업 목록 작업명 키보드 링크 (WD-927)', () => {
  // 상세 진입은 조회 권한이라 쓰기 권한이 없는 읽기 전용 사용자도 키보드로 상세에 가야 한다
  for (const canWrite of [true, false]) {
    test(`Tab 으로 작업명 링크에 닿고 Enter 로 상세로 간다 (쓰기 권한 ${canWrite ? '있음' : '없음'})`, async ({ authenticatedPage: page }) => {
      await mockProactivePermissions(page, { canWrite });
      await setupJobListMocks(page);
      await setupJobDetailMocks(page, 1);
      await page.goto('/ai-insights/jobs');
      const link = page.getByRole('link', { name: '잡 1' });
      await expect(link).toHaveAttribute('href', '/ai-insights/jobs/1');

      await tabUntilFocused(page, link);
      await expect(link).toBeFocused();
      await page.keyboard.press('Enter');
      await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    });
  }

  test.describe('쓰기 권한 사용자', () => {
    test.beforeEach(async ({ authenticatedPage: page }) => {
      await mockProactivePermissions(page);
      await setupJobListMocks(page);
      await setupJobDetailMocks(page, 1);
      await page.goto('/ai-insights/jobs');
    });

    test('작업명 링크 클릭은 한 번만 이동한다', async ({ authenticatedPage: page }) => {
      await page.getByRole('link', { name: '잡 1' }).click();
      await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
      // 링크 이동과 행 onClick 이동이 겹쳐 히스토리가 두 번 쌓였다면 뒤로 한 번에 목록으로 돌아오지 못한다
      await page.goBack();
      await expect(page).toHaveURL(/\/ai-insights\/jobs$/);
    });

    test('링크 폭은 이름 글자만큼이라 칸 전체로 늘지 않는다', async ({ authenticatedPage: page }) => {
      const link = page.getByRole('link', { name: '잡 1' });
      await expect(link).toBeVisible();
      const linkBox = await link.boundingBox();
      const cellBox = await page.locator('main tbody tr').first().locator('td').first().boundingBox();
      expect(linkBox!.width).toBeLessThan(cellBox!.width * 0.6);
    });

    test('행 빈 곳 클릭은 여전히 상세로 이동한다', async ({ authenticatedPage: page }) => {
      await page.locator('main tbody tr').first().getByText('매일 오전 9시').click();
      await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    });
  });
});
