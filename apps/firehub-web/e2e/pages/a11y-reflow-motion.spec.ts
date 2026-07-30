import { createSynonymReviewItem } from '../factories/reviewItem.factory';
import { setupAdminAuth, setupOntologyMocks } from '../fixtures/admin.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * 접근성 회귀 가드 — 리플로우(#345)와 동작 줄이기(#344).
 *
 * - #345 (WCAG SC 1.4.10 Reflow): 320 CSS px 폭에서 2차원 스크롤이 없어야 한다.
 *   데이터 표는 자체 overflow-x-auto 컨테이너를 가지므로 SC 예외에 해당 — 판정 기준은 <main> 자체의 가로 초과다.
 * - #344 (WCAG SC 2.3.3): prefers-reduced-motion: reduce 에서 키프레임 모션이 감쇠되어야 하되,
 *   포커스 가시성을 담당하는 transition 은 살아 있어야 한다.
 */

/** <main>이 가로로 넘치는 픽셀 수. 0이면 리플로우 만족. */
const mainOverflow = (page: import('@playwright/test').Page) =>
  page.evaluate(() => {
    const m = document.querySelector('main');
    return m ? m.scrollWidth - m.clientWidth : -1;
  });

test.describe('320px 리플로우 (#345)', () => {
  test('AI 검수 인박스가 320px에서 가로 스크롤 없이 표시된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.setViewportSize({ width: 320, height: 800 });
    await page.goto('/knowledge-graph/review');
    await expect(page.getByText('전기적 요인')).toBeVisible();

    expect(await mainOverflow(page)).toBe(0);
    // 원인이 탭 목록이었으므로, 탭이 실제로 줄바꿈해 컨테이너 폭 안에 들어왔는지도 확인한다.
    const list = page.locator('[data-slot=tabs-list]');
    const fits = await list.evaluate((el) => el.scrollWidth <= el.clientWidth + 1);
    expect(fits).toBe(true);
  });

  test('지식 모델 페이지가 320px에서 가로 스크롤 없이 표시된다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.setViewportSize({ width: 320, height: 800 });
    await page.goto('/knowledge-graph/model');
    await expect(page.locator('[data-slot=tabs-list]')).toBeVisible();

    expect(await mainOverflow(page)).toBe(0);
  });

  test('데스크톱(1280px)에서는 탭이 기존대로 한 줄로 유지된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.goto('/knowledge-graph/review');
    await expect(page.getByText('전기적 요인')).toBeVisible();

    // 줄바꿈 도입이 데스크톱 표현을 바꾸지 않아야 한다 — 탭 5개가 한 줄(높이 36px)에 그대로 있어야 한다.
    const box = await page.locator('[data-slot=tabs-list]').boundingBox();
    expect(box?.height).toBeLessThan(45);
    expect(await mainOverflow(page)).toBe(0);
  });
});

test.describe('동작 줄이기 (#344)', () => {
  test('reduce 설정에서 확인 다이얼로그의 확대 애니메이션이 감쇠된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('/knowledge-graph/review');

    await page.getByRole('button', { name: /승인/ }).click();
    const dialog = page.getByTestId('review-decide-confirm');
    await expect(dialog).toBeVisible();

    const motion = await dialog.evaluate((el) => {
      const cs = getComputedStyle(el);
      return { name: cs.animationName, duration: parseFloat(cs.animationDuration) };
    });
    // 애니메이션 자체는 걸려 있되(enter) 즉시 완료되어야 한다.
    expect(motion.name).not.toBe('none');
    expect(motion.duration).toBeLessThan(0.01);
  });

  test('reduce 설정에서도 포커스 가시성용 transition은 살아 있다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('/knowledge-graph/review');

    // 탭 트리거의 transition을 함께 죽이면 활성 인디케이터/포커스 링 표현이 사라져
    // SC 2.4.7 쪽에 결함을 새로 심게 된다 — 의도적으로 남긴 부분의 회귀 가드.
    const trans = await page.locator('[data-slot=tabs-trigger]').first()
      .evaluate((el) => parseFloat(getComputedStyle(el).transitionDuration));
    expect(trans).toBeGreaterThan(0);
  });

  test('기본(no-preference) 설정에서는 다이얼로그 모션이 그대로 재생된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [createSynonymReviewItem()]);
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.goto('/knowledge-graph/review');

    await page.getByRole('button', { name: /승인/ }).click();
    const duration = await page.getByTestId('review-decide-confirm')
      .evaluate((el) => parseFloat(getComputedStyle(el).animationDuration));
    // 감쇠 규칙이 미디어 쿼리 밖으로 새어 나가 모든 사용자의 모션을 죽이지 않았는지 확인한다.
    expect(duration).toBeGreaterThan(0.05);
  });
});
