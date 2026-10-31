import { setupDashboardEditorMocks } from "../../fixtures/analytics.fixture";
import { expect, test } from "../../fixtures/auth.fixture";

/**
 * 대시보드 전체화면에서 편집(WD-329).
 * 전체화면은 대시보드 영역만 띄워, 문서 본문에 붙는 「차트 추가」·위젯 삭제 대화상자가 보이지 않고 클릭만 삼켰다.
 * 편집은 전체화면을 풀고 시작하고, 편집 중에는 전체화면 버튼을 숨겨 대화상자가 늘 보이는지 확인한다.
 */
test.describe("대시보드 전체화면 편집 (WD-329)", () => {
  test("전체화면에서 「편집」을 누르면 전체화면이 풀리고, 「차트 추가」 대화상자가 보인다", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    await page.goto("/analytics/dashboards/1");
    await expect(
      page.getByRole("heading", { name: "테스트 대시보드" }),
    ).toBeVisible();

    await page.getByRole("button", { name: "전체화면" }).click();
    await expect
      .poll(() => page.evaluate(() => !!document.fullscreenElement))
      .toBe(true);

    await page.getByRole("button", { name: "편집" }).click();
    // 전체화면이 풀리고 편집 모드로 들어간다
    await expect
      .poll(() => page.evaluate(() => !!document.fullscreenElement))
      .toBe(false);
    await expect(page.getByRole("button", { name: "완료" })).toBeVisible();

    await page.getByRole("button", { name: "차트 추가" }).click();
    await expect(page.getByRole("dialog")).toBeVisible();
  });

  test("편집 중에는 전체화면 버튼이 없고, 편집을 마치면 다시 보인다", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    await page.goto("/analytics/dashboards/1");
    await expect(page.getByRole("button", { name: "전체화면" })).toBeVisible();

    await page.getByRole("button", { name: "편집" }).click();
    await expect(page.getByRole("button", { name: "완료" })).toBeVisible();
    await expect(page.getByRole("button", { name: "전체화면" })).toHaveCount(0);

    await page.getByRole("button", { name: "편집 취소" }).click();
    await expect(page.getByRole("button", { name: "전체화면" })).toBeVisible();
  });
});
