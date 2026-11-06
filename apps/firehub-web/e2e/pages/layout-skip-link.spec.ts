import type { Page } from '@playwright/test';

import { setupQueryListMocks } from '../fixtures/analytics.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupDatasetMocks, setupGeometryDatasetDetailMocks } from '../fixtures/dataset.fixture';

/**
 * 앱 셸 「본문으로 건너뛰기」 링크 (WD-530, WCAG 2.4.1 Bypass Blocks)
 *
 * 수정 전 결함: 모든 화면에서 키보드 사용자가 본문(main)에 닿으려면 사이드바 컨트롤 17~24개를 매번 Tab 으로 지나가야 했다.
 * 앱 셸의 첫 포커스 요소로 건너뛰기 링크를 두고, 누르면 본문(main#main-content)으로 포커스를 옮긴다(URL 해시는 남기지 않는다).
 */

const skipLink = (page: Page) => page.getByRole('link', { name: '본문으로 건너뛰기' });

/** 데이터셋 목록 제목(h1)이 그려질 때까지 기다린다 */
const waitForDatasetsHeading = (page: Page) =>
  expect(page.getByRole('heading', { name: '데이터셋', level: 1 })).toBeVisible();

/** 데이터셋 목록(모킹)을 열고 제목이 그려질 때까지 기다린다 */
async function openDatasets(page: Page) {
  await setupDatasetMocks(page);
  await page.goto('/data/datasets');
  await waitForDatasetsHeading(page);
}

/** AI 상태 칩을 눌러 사이드 패널을 열고 입력창이 보일 때까지 기다린다(닫힘 → 사이드 패널) */
async function openAISidePanel(page: Page) {
  await page.getByRole('button', { name: /AI 상태/ }).click();
  await expect(page.getByPlaceholder('메시지를 입력하세요...')).toBeVisible();
}

test.describe('본문으로 건너뛰기 링크 (WD-530)', () => {
  test('데이터셋 목록: 첫 Tab 이 보이는 건너뛰기 링크이고, Enter 로 본문에 포커스가 가며 다음 Tab 은 본문 안이다', async ({
    authenticatedPage: page,
  }) => {
    await openDatasets(page);

    // 포커스 전에는 시각적으로 숨겨져 있다(sr-only — 1px 상자). 접근성 트리에는 남아 있어야 화면 읽기 사용자도 쓴다.
    const link = skipLink(page);
    await expect(link).toHaveCount(1);
    const hiddenBox = await link.boundingBox();
    expect(hiddenBox === null || (hiddenBox.width <= 1 && hiddenBox.height <= 1)).toBe(true);

    // 본문(main)은 평소 tabindex 가 없다 — 빈 곳을 마우스로 눌러도 main 이 포커스를 가져가지 않는다(activeElement 그대로 body)
    const main = page.locator('main#main-content');
    await expect(main).not.toHaveAttribute('tabindex', /.*/);
    const mainBox = await main.boundingBox();
    // 본문 오른쪽 아래 여백(목록·버튼이 없는 자리)을 누른다
    await page.mouse.click(mainBox!.x + mainBox!.width - 5, mainBox!.y + mainBox!.height - 5);
    expect(await page.evaluate(() => document.activeElement === document.body)).toBe(true);
    // 클릭으로 바뀐 순차 포커스 시작점을 처음 상태로 되돌린다(새로 불러와 첫 Tab 을 다시 본다)
    await page.reload();
    await waitForDatasetsHeading(page);

    // 첫 Tab — 사이드바보다 먼저 건너뛰기 링크에 닿고, 화면에 실제로 보인다
    await page.keyboard.press('Tab');
    await expect(link).toBeFocused();
    await expect(link).toBeVisible();
    const box = await link.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.width).toBeGreaterThan(20);
    expect(box!.height).toBeGreaterThan(10);
    // 왼쪽 위에 뜬다
    expect(box!.x).toBeLessThan(100);
    expect(box!.y).toBeLessThan(100);

    // Enter — 본문(main#main-content)으로 포커스가 옮겨지고 URL 에 해시가 남지 않는다
    await page.keyboard.press('Enter');
    await expect(main).toBeFocused();
    // 프로그램 포커스를 받는 동안에만 tabindex=-1 이 붙는다
    await expect(main).toHaveAttribute('tabindex', '-1');
    await expect(page).toHaveURL(/\/data\/datasets$/);
    await expect(page).not.toHaveURL(/#/);

    // 다음 Tab 은 사이드바가 아니라 본문 안의 첫 포커스 요소로 간다
    await page.keyboard.press('Tab');
    const inMain = await page.evaluate(() => {
      const el = document.activeElement;
      return !!el && !!el.closest('main#main-content') && !el.matches('main');
    });
    expect(inMain).toBe(true);
    // 포커스가 떠나면 tabindex 는 다시 떼어진다
    await expect(main).not.toHaveAttribute('tabindex', /.*/);
  });

  test('다른 화면(내 쿼리)에서도 첫 Tab 은 건너뛰기 링크다', async ({ authenticatedPage: page }) => {
    await setupQueryListMocks(page);
    await page.goto('/analytics/queries');
    await expect(page.locator('main#main-content')).toBeVisible();

    await page.keyboard.press('Tab');
    await expect(skipLink(page)).toBeFocused();
  });

  test('AI 전체 화면 모드에서는 본문(main)이 없으므로 건너뛰기 링크도 그리지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 전체 화면 판별에 쓰는 「대화 이력」 목록은 세션이 있을 때만 그려진다(WD-61 spec 과 같은 방식)
    await mockApi(page, 'GET', '/api/v1/ai/sessions', [
      { id: 530, sessionId: 'session-wd530', title: 'WD-530 대화', createdAt: '2026-11-06T00:00:00Z', updatedAt: '2026-11-06T01:00:00Z' },
    ]);
    await page.goto('/');
    await expect(skipLink(page)).toHaveCount(1);

    await openAISidePanel(page);
    await page.getByRole('button', { name: /AI 상태/ }).click(); // 사이드 패널 → 전체 화면
    await expect(page.getByRole('list', { name: '대화 이력' })).toBeVisible();

    await expect(page.locator('main#main-content')).toHaveCount(0);
    await expect(skipLink(page)).toHaveCount(0);
  });

  test('모바일: 첫 Tab 은 건너뛰기 링크이고 메뉴 버튼을 덮지 않으며, 서랍이 열린 동안에는 서랍 포커스 가둠을 깨지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openDatasets(page);

    await page.keyboard.press('Tab');
    const link = skipLink(page);
    await expect(link).toBeFocused();
    await expect(link).toBeVisible();

    // 포커스된 링크가 왼쪽 위 「메뉴 열기」 버튼(top-3 left-3)과 겹치지 않는다 — 상단 줄 아래에 뜬다
    const menuButton = page.getByRole('button', { name: '메뉴 열기' });
    const l = (await link.boundingBox())!;
    const m = (await menuButton.boundingBox())!;
    const overlap = l.x < m.x + m.width && m.x < l.x + l.width && l.y < m.y + m.height && m.y < l.y + l.height;
    expect(overlap).toBe(false);

    // 링크에 포커스가 있는 채로 메뉴 버튼을 마우스로 누를 수 있다(덮였다면 클릭이 링크로 간다)
    await menuButton.click();
    // 서랍을 열면 건너뛰기 링크는 빠진다(서랍 밖으로 포커스가 새지 않게)
    await expect(page.getByRole('dialog', { name: '메뉴' })).toBeVisible();
    await expect(skipLink(page)).toHaveCount(0);
  });

  test('모바일: AI 사이드 패널(전체 덮개)이 열린 동안에는 본문이 가려져 건너뛰기 링크를 그리지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await openDatasets(page);
    await expect(skipLink(page)).toHaveCount(1);

    await openAISidePanel(page); // 모바일 사이드 패널은 fixed inset-0 덮개
    await expect(skipLink(page)).toHaveCount(0);
  });

  test('데이터셋 상세 ?tab=history: 활성 탭을 끌어오는 스크롤이 있어도 첫 Tab 은 건너뛰기 링크다', async ({
    authenticatedPage: page,
  }) => {
    // 탭 바가 가로로 넘치는 조건(지도 탭이 생기는 GEOMETRY 컬럼 데이터셋, 탭 바 폭이 좁은 화면) — dataset-detail-tabs-scroll 과 같은 구성.
    // 이 조건에서 활성 탭 끌어오기를 scrollIntoView 로 하면 Chromium 이 순차 포커스 시작점을 그 탭으로 옮겨 첫 Tab 이 다음 탭으로 갔다.
    await page.setViewportSize({ width: 640, height: 900 });
    await setupGeometryDatasetDetailMocks(page);
    await page.goto('/data/datasets/1?tab=history');

    const history = page.getByRole('tab', { name: '이력' });
    await expect(history).toHaveAttribute('data-state', 'active');
    // 재현 조건 — 탭 바가 실제로 가로로 넘치고, 활성 탭이 끌어와져 탭 바가 스크롤돼 있다(공허한 통과 방지)
    const tablist = page.getByRole('tablist').first();
    await expect.poll(() => tablist.evaluate((el) => el.scrollLeft)).toBeGreaterThan(0);

    await page.keyboard.press('Tab');
    await expect(skipLink(page)).toBeFocused();
  });
});
