/**
 * AISidePanel 모바일 반응형 E2E 테스트 (이슈 #23)
 *
 * 검증 내용:
 * - 375px 모바일 뷰포트에서 AI 패널 열었을 때 메인 콘텐츠가 압착되지 않음
 * - 모바일에서 AI 패널이 fixed overlay(전체 화면)로 렌더링됨
 * - 데스크탑(lg 이상)에서는 기존 사이드 패널 동작 유지
 *
 * 회귀 방지:
 * AISidePanel이 flex row 내 고정 너비(~320px)로 렌더링되면
 * 375px 뷰포트에서 main이 48px로 압착되는 버그 재발 방지
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** AIStatusChip 버튼 locator */
const chipLocator = (page: import('@playwright/test').Page) =>
  page.getByRole('button', { name: /AI 상태/ });

test.describe('AISidePanel — 모바일 375px 오버레이 (이슈 #23)', () => {
  test('모바일(375px)에서 AI 패널 열어도 메인 콘텐츠가 압착되지 않는다', async ({ authenticatedPage: page }) => {
    // 375px 모바일 뷰포트 설정
    await page.setViewportSize({ width: 375, height: 812 });
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.goto('/', { waitUntil: 'commit' });

    // AI 패널 열기 전 main 너비 측정
    const mainBefore = await page.locator('main').evaluate((el) => el.getBoundingClientRect().width);
    expect(mainBefore).toBeGreaterThan(300); // 압착 이전에는 충분한 너비

    // AI 상태 칩 클릭 → 패널 열기
    await chipLocator(page).click();

    // 패널 열린 후: main 요소가 여전히 충분한 너비를 가져야 함 (오버레이 방식)
    // 버그 상태에서는 ~48px로 줄어들었음
    const mainAfter = await page.locator('main').evaluate((el) => el.getBoundingClientRect().width);
    expect(mainAfter).toBeGreaterThan(300); // 48px 압착 버그 회귀 방지

    // AI 채팅 입력창이 표시되어 있어야 함 (패널이 열린 상태)
    await expect(page.getByPlaceholder('메시지를 입력하세요...')).toBeVisible({ timeout: 5000 });
  });

  test('모바일(375px)에서 AI 패널이 fixed overlay로 렌더링된다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.goto('/', { waitUntil: 'commit' });

    await chipLocator(page).click();

    // fixed overlay div 확인: position=fixed, inset=0
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });

    // AI 패널 컨테이너가 fixed positioning으로 렌더링되는지 확인
    // (flex row에서 분리된 오버레이)
    const panelStyle = await page.locator('.fixed.inset-0.z-50.bg-background').evaluate((el) => {
      const style = window.getComputedStyle(el);
      return { position: style.position, zIndex: style.zIndex };
    });
    expect(panelStyle.position).toBe('fixed');
    expect(Number(panelStyle.zIndex)).toBeGreaterThanOrEqual(50);
  });

  test('모바일(375px)에서 AI 패널 닫기 버튼으로 패널이 닫힌다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.goto('/', { waitUntil: 'commit' });

    await chipLocator(page).click();
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });

    // AIChatPanel 헤더의 X 버튼으로 닫기
    await page.locator('button').filter({ has: page.locator('svg.lucide-x') }).last().click();

    // 패널이 닫히면 입력창 비가시
    await expect(page.getByPlaceholder('메시지를 입력하세요...')).not.toBeVisible({ timeout: 3000 });

    // 메인 콘텐츠 복원 확인
    const mainAfterClose = await page.locator('main').evaluate((el) => el.getBoundingClientRect().width);
    expect(mainAfterClose).toBeGreaterThan(300);
  });

  test('데스크탑(1280px)에서 AI 패널은 기존 사이드 인라인으로 렌더링된다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await mockApi(page, 'GET', '/api/v1/ai/sessions', []);
    await page.goto('/', { waitUntil: 'commit' });

    await chipLocator(page).click();
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });

    // 데스크탑: fixed overlay가 없어야 함
    const overlay = page.locator('.fixed.inset-0.z-50.bg-background');
    await expect(overlay).not.toBeVisible();

    // 데스크탑: main은 인라인 사이드 패널로 인해 축소되지만 최소 300px 이상
    const mainWidth = await page.locator('main').evaluate((el) => el.getBoundingClientRect().width);
    expect(mainWidth).toBeGreaterThan(300);
  });
});
