import { setupAdminAuth } from '../fixtures/admin.fixture';
import { expect, test } from '../fixtures/auth.fixture';

/**
 * 앱 셸(사이드바·상단 AI 줄) 조작의 클릭 영역 (WD-899, 최소 32×32 §2.12)
 *
 * 수정 전: 접기 버튼·벨 28×28, 그룹 머리글 높이 28, AI 칩 높이 26, 브랜드 링크 높이 24, 메뉴 링크 높이 31.5.
 * 보이는 아이콘·글자 크기는 그대로 두고 클릭 영역만 키운다.
 */
test.describe('앱 셸 클릭 영역 32×32 (WD-899)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    // 펼친 사이드바로 시작한다
    await page.addInitScript(() => localStorage.setItem('sidebar-collapsed', 'false'));
    await setupAdminAuth(page);
    await page.goto('/');
    await expect(page.getByRole('button', { name: '사이드바 접기' })).toBeVisible();
  });

  test('펼친 사이드바·상단 AI 줄의 조작이 모두 32×32 이상이다', async ({
    authenticatedPage: page,
  }) => {
    /**
     * 로케이터가 가리키는 요소 전부가 최소 32px 인지 확인하고 마지막 요소의 box 를 돌려준다.
     * 높이는 늘 보고, withWidth 면 너비도 본다(아이콘 버튼). 요소가 하나도 없으면 실패한다.
     */
    const expectMin32 = async (loc: ReturnType<typeof page.locator>, withWidth = false) => {
      const boxes = await loc.evaluateAll((els) =>
        els.map((el) => {
          const r = el.getBoundingClientRect();
          return { y: r.y, width: r.width, height: r.height };
        })
      );
      expect(boxes.length).toBeGreaterThanOrEqual(1);
      for (const box of boxes) {
        expect(box.height).toBeGreaterThanOrEqual(32);
        if (withWidth) expect(box.width).toBeGreaterThanOrEqual(32);
      }
      return boxes[boxes.length - 1];
    };

    // 사이드바 접기 버튼·알림 벨 — 아이콘 버튼이라 너비도 본다
    await expectMin32(page.getByRole('button', { name: '사이드바 접기' }), true);
    await expectMin32(
      page
        .locator('button[aria-haspopup="dialog"]')
        .and(page.getByRole('button', { name: /AI 인사이트/ })),
      true
    );
    // 그룹 머리글(CollapsibleTrigger 는 aria-controls 를 가진다)·메뉴 링크 전부(활성 포함)·브랜드 링크
    await expectMin32(page.locator('aside nav button[data-state][aria-controls]'));
    await expectMin32(page.locator('aside nav a'));
    await expectMin32(page.locator('aside a[href="/"]').first());

    // AI 칩 — 상단 줄(본문 pt-10 = 40px) 안에 들어와야 한다. 래퍼 top-1.5 라 칩은 38px 에 끝나 2px 여백이 있다
    const chip = await expectMin32(page.locator('[role="button"][aria-label^="AI 상태"]'));
    expect(chip.y + chip.height).toBeLessThanOrEqual(40);
  });

  test('그룹 머리글 클릭으로 접고 펼치기는 그대로다', async ({ authenticatedPage: page }) => {
    const header = page.locator('aside nav button[data-state][aria-controls]').first();
    await expect(header).toHaveAttribute('data-state', 'open');
    await header.click();
    await expect(header).toHaveAttribute('data-state', 'closed');
    await header.click();
    await expect(header).toHaveAttribute('data-state', 'open');
  });
});
