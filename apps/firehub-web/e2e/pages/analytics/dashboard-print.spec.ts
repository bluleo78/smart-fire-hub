import type { Page } from '@playwright/test';

import { setupDashboardEditorMocks } from '../../fixtures/analytics.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 PDF 내보내기(인쇄) 출력 회귀 테스트 (WD-424)
 *
 * 「PDF로 내보내기」는 window.print() 로 브라우저 인쇄를 띄우는데 인쇄 스타일이 없어, PDF 에 앱 머리(햄버거·AI 칩·알림 종),
 * 「←」·공유·PDF·새로고침·전체화면·편집 버튼, 위젯마다 신선도 바(「N분 전」+새로고침), 넓은 인쇄 폭에서는 사이드바 메뉴·사용자 정보까지
 * 그대로 찍혔다. 인쇄 미디어를 흉내 내(emulateMedia) 대시보드 이름과 위젯(제목+차트)만 남고 나머지는 숨는지,
 * 본문이 사이드바 자리 없이 왼쪽 끝에서 시작하는지, 인쇄 시각 줄이 인쇄에서만 보이는지 확인한다.
 */

const DASHBOARD_NAME = '테스트 대시보드';
const WIDGET_TITLE = '테스트 차트';

/** 위젯 카드(제목으로 식별) */
const widgetCard = (page: Page) =>
  page.locator('[data-slot="card"]').filter({ has: page.getByText(WIDGET_TITLE, { exact: true }) });

/** 화면에서는 보이고 인쇄에서는 숨어야 하는 앱·페이지 조작 요소들 */
const chromeLocators = (page: Page) => ({
  aiChip: page.locator('[aria-label^="AI 상태"]'),
  bell: page.locator('button[aria-label*="AI 인사이트"]'),
  sidebar: page.locator('aside'),
  share: page.getByRole('button', { name: '공유 링크 복사' }),
  pdf: page.getByRole('button', { name: 'PDF로 내보내기' }),
  fullscreen: page.getByRole('button', { name: '전체화면' }),
  edit: page.getByRole('button', { name: '편집', exact: true }),
  // 헤더 새로고침과 위젯 신선도 바의 새로고침 버튼이 같은 title 을 쓴다
  refreshButtons: page.locator('button[title="새로고침"]'),
});

/** 대시보드를 열고 위젯 차트가 그려질 때까지 기다린다 */
async function openDashboard(page: Page) {
  await setupDashboardEditorMocks(page, 1);
  await page.goto('/analytics/dashboards/1');
  await expect(page.getByRole('heading', { name: DASHBOARD_NAME })).toBeVisible();
  await expect(widgetCard(page).locator('.recharts-wrapper')).toBeVisible();
}

test.describe('대시보드 — PDF 내보내기 인쇄 출력 (WD-424)', () => {
  test('1440 폭 인쇄에서는 대시보드 이름·위젯만 남고 앱 머리·사이드바·조작 버튼·신선도 바가 숨는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await openDashboard(page);

    const chrome = chromeLocators(page);
    // 전제: 화면에서는 앱 테두리·조작 버튼이 모두 보이고 인쇄 시각 줄은 숨어 있다
    await expect(chrome.aiChip).toBeVisible();
    await expect(chrome.bell).toBeVisible();
    await expect(chrome.sidebar).toBeVisible();
    await expect(chrome.share).toBeVisible();
    await expect(chrome.pdf).toBeVisible();
    await expect(chrome.fullscreen).toBeVisible();
    await expect(chrome.edit).toBeVisible();
    await expect(chrome.refreshButtons).toHaveCount(2);
    await expect(page.getByTestId('dashboard-print-time')).toBeHidden();

    await page.emulateMedia({ media: 'print' });

    // 남아야 하는 것 — 대시보드 이름, 위젯 제목과 차트
    await expect(page.getByRole('heading', { name: DASHBOARD_NAME })).toBeVisible();
    await expect(widgetCard(page).getByText(WIDGET_TITLE, { exact: true })).toBeVisible();
    await expect(widgetCard(page).locator('.recharts-wrapper')).toBeVisible();

    // 숨어야 하는 것 — 앱 머리(AI 칩·알림 종), 사이드바, 페이지 조작 버튼, 위젯 신선도 바 새로고침
    await expect(chrome.aiChip).toBeHidden();
    await expect(chrome.bell).toBeHidden();
    await expect(chrome.sidebar).toBeHidden();
    await expect(chrome.share).toBeHidden();
    await expect(chrome.pdf).toBeHidden();
    await expect(chrome.fullscreen).toBeHidden();
    await expect(chrome.edit).toBeHidden();
    for (const button of await chrome.refreshButtons.all()) {
      await expect(button).toBeHidden();
    }

    // 본문은 사이드바 자리 없이 종이 왼쪽 끝에서 시작한다
    const mainBox = await page.locator('main').boundingBox();
    expect(mainBox).not.toBeNull();
    expect(mainBox!.x).toBeLessThan(8);
    const headingBox = await page.getByRole('heading', { name: DASHBOARD_NAME }).boundingBox();
    expect(headingBox!.x).toBeLessThan(40);
  });

  test('인쇄 시각 줄은 인쇄에서만 대시보드 이름 아래에 보이고 화면에서는 숨는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await openDashboard(page);

    const printTime = page.getByTestId('dashboard-print-time');
    await expect(printTime).toBeHidden();

    await page.emulateMedia({ media: 'print' });
    await expect(printTime).toBeVisible();
    await expect(printTime).toHaveText(/^인쇄 시각: \d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    // 이름 아래에 놓인다
    const headingBox = await page.getByRole('heading', { name: DASHBOARD_NAME }).boundingBox();
    const timeBox = await printTime.boundingBox();
    expect(timeBox!.y).toBeGreaterThan(headingBox!.y);

    // 화면으로 돌아오면 다시 숨는다
    await page.emulateMedia({ media: 'screen' });
    await expect(printTime).toBeHidden();
  });

  test('A4 폭(794px) 인쇄에서도 모바일 상단 줄·메뉴 버튼·사이드바 없이 위젯이 왼쪽 끝부터 찍힌다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 794, height: 1123 });
    await openDashboard(page);
    // 전제: lg 미만 폭이라 화면에서는 모바일 메뉴 버튼이 보인다
    await expect(page.getByRole('button', { name: '메뉴 열기' })).toBeVisible();

    await page.emulateMedia({ media: 'print' });

    await expect(page.getByRole('heading', { name: DASHBOARD_NAME })).toBeVisible();
    await expect(widgetCard(page).locator('.recharts-wrapper')).toBeVisible();
    await expect(page.getByTestId('dashboard-print-time')).toBeVisible();

    await expect(page.getByRole('button', { name: '메뉴 열기' })).toBeHidden();
    await expect(page.getByTestId('mobile-topbar')).toBeHidden();
    await expect(page.locator('aside')).toBeHidden();
    await expect(page.locator('[aria-label^="AI 상태"]')).toBeHidden();
    await expect(page.getByRole('button', { name: 'PDF로 내보내기' })).toBeHidden();

    const mainBox = await page.locator('main').boundingBox();
    expect(mainBox!.x).toBeLessThan(8);
    // 위젯 카드도 본문 왼쪽 끝 가까이에서 시작한다(사이드바 폭 240px 만큼 밀리지 않는다)
    const cardBox = await widgetCard(page).boundingBox();
    expect(cardBox!.x).toBeLessThan(40);
  });
});
