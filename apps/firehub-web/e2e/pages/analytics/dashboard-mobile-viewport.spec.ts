import { createWidget } from '../../factories/analytics.factory';
import { setupChartBuilderMocks, setupDashboardEditorMocks } from '../../fixtures/analytics.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 상세·차트 편집 모바일 폭 (WD-321)
 * - 머리글 버튼에 밀려 이름이 「Q.」만 보이던 것, 편집 모드 「완료」·「편집 취소」가 화면 밖으로 밀리던 것,
 *   12칸 배치가 그대로 줄어 위젯이 95~147px 로 찌그러지던 것을 막는다.
 */
const LONG_NAME = 'QA267 r7 편집기 대시보드';
// 데스크톱 배치: 둘째 줄 왼쪽 6칸, 첫 줄 오른쪽 8칸, 첫 줄 왼쪽 4칸 — 쌓을 때는 위→아래, 왼→오른쪽 순서여야 한다.
const WIDGETS = [
  createWidget({ id: 3, positionX: 0, positionY: 4, width: 6, height: 3 }),
  createWidget({ id: 2, positionX: 4, positionY: 0, width: 8, height: 4 }),
  createWidget({ id: 1, positionX: 0, positionY: 0, width: 4, height: 4 }),
];

test.describe('대시보드 모바일 폭 (WD-321)', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('보기 모드: 이름이 읽히고 위젯은 화면 폭으로 한 줄에 하나씩 쌓인다', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 1, { name: LONG_NAME, widgets: WIDGETS });
    await page.goto('/analytics/dashboards/1');

    const title = page.getByRole('heading', { name: LONG_NAME });
    await expect(title).toBeVisible();
    // 전에는 h1 폭이 18px(「Q.」) — 버튼 묶음이 다음 줄로 내려가 이름에 줄 폭이 돌아온다.
    expect((await title.boundingBox())!.width).toBeGreaterThan(150);
    await expect(title).toHaveAttribute('title', LONG_NAME);

    const items = page.getByTestId('dashboard-stacked').locator(':scope > div');
    await expect(items).toHaveCount(3);
    const boxes = await Promise.all([0, 1, 2].map((i) => items.nth(i).boundingBox()));
    for (const box of boxes) {
      // 화면 폭(390) 안에서 거의 전체 폭 — 전에는 4칸 위젯이 95px 이었다.
      expect(box!.width).toBeGreaterThan(300);
      expect(box!.x + box!.width).toBeLessThanOrEqual(390);
    }
    // 위→아래, 왼→오른쪽 순서: (0,0) 4행 → (4,0) 4행 → (0,4) 3행. 높이는 행 수 × 80 + 간격.
    expect(boxes[0]!.y).toBeLessThan(boxes[1]!.y);
    expect(boxes[1]!.y).toBeLessThan(boxes[2]!.y);
    expect(Math.round(boxes[0]!.height)).toBe(4 * 80 + 3 * 8);
    expect(Math.round(boxes[2]!.height)).toBe(3 * 80 + 2 * 8);

    // 페이지가 가로로 넘치지 않는다.
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });

  test('편집 모드: 「완료」·「편집 취소」가 화면 안에 있고 누를 수 있다', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 1, { name: LONG_NAME, widgets: WIDGETS });
    await page.goto('/analytics/dashboards/1');

    await page.getByRole('button', { name: '편집' }).click();
    const done = page.getByRole('button', { name: '완료' });
    const cancel = page.getByTitle('편집 취소');
    for (const btn of [done, cancel]) {
      await expect(btn).toBeVisible();
      const box = (await btn.boundingBox())!;
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(390);
    }
    // 편집은 저장 좌표를 다루므로 12칸 격자 그대로다(쌓기 보기 아님).
    await expect(page.getByTestId('dashboard-stacked')).toHaveCount(0);

    await cancel.click();
    await expect(page.getByRole('button', { name: '편집' })).toBeVisible();
    await expect(page.getByTestId('dashboard-stacked')).toBeVisible();
  });

  test('차트 편집 화면: 제목이 「QA…」로 줄지 않는다', async ({ authenticatedPage: page }) => {
    await setupChartBuilderMocks(page, 1);
    await page.goto('/analytics/charts/1');

    const title = page.getByRole('heading', { level: 1 });
    await expect(title).toBeVisible();
    // 전에는 390 폭에서 45px — 다운로드·대시보드 추가 버튼이 다음 줄로 내려간다.
    expect((await title.boundingBox())!.width).toBeGreaterThan(150);
  });
});

test.describe('대시보드 데스크톱 폭 (WD-321)', () => {
  test('넓은 화면 보기 모드는 12칸 격자 배치를 그대로 쓴다', async ({ authenticatedPage: page }) => {
    await setupDashboardEditorMocks(page, 1, { widgets: WIDGETS });
    await page.goto('/analytics/dashboards/1');

    await expect(page.getByRole('heading', { name: '테스트 대시보드' })).toBeVisible();
    await expect(page.locator('.react-grid-item')).toHaveCount(3);
    await expect(page.getByTestId('dashboard-stacked')).toHaveCount(0);
  });
});
