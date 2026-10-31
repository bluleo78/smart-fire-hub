import {
  createChart,
  createQueryResult,
  createWidget,
} from "../../factories/analytics.factory";
import { setupDashboardEditorMocks } from "../../fixtures/analytics.fixture";
import { mockApi } from "../../fixtures/api-mock";
import { expect, test } from "../../fixtures/auth.fixture";

/**
 * 대시보드 편집 — 키보드로 위젯 옮기기·크기 바꾸기(WD-324).
 * 머리글 끌기·모서리 크기 조절은 마우스로만 되므로, 위젯마다 Tab 으로 닿는 「이동·크기 조절」 손잡이에서
 * 화살표 = 한 칸 이동, Shift+화살표 = 크기 조절이 되고, 바뀐 위치·크기를 화면 읽기 프로그램에 알리며,
 * 「완료」를 누르면 끌기와 같은 PUT 으로 저장되는지 확인한다.
 */
test.describe("대시보드 편집 — 키보드 배치 조작 (WD-324)", () => {
  test("손잡이에 Tab 으로 닿고, 화살표·Shift+화살표로 바꾼 위치·크기가 안내되고 「완료」 때 저장된다", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    const putCapture = await mockApi(
      page,
      "PUT",
      "/api/v1/analytics/dashboards/1/widgets/1",
      {},
      { capture: true },
    );

    await page.goto("/analytics/dashboards/1");
    await expect(page.getByText("테스트 차트")).toBeVisible();
    await page.getByRole("button", { name: "편집" }).click();

    // 키보드만으로 닿는지 — 「위젯 제거」에서 Shift+Tab 하면 바로 앞 손잡이로 간다
    const handle = page.getByRole("button", {
      name: "「테스트 차트」 위젯 이동·크기 조절",
    });
    await expect(handle).toBeVisible();
    await expect(handle).toHaveAccessibleDescription(
      "화살표 키로 한 칸씩 옮기고, Shift 와 화살표 키로 크기를 바꿉니다.",
    );
    await page.getByTitle("위젯 제거").focus();
    await page.keyboard.press("Shift+Tab");
    await expect(handle).toBeFocused();

    const announcement = page.getByTestId("dashboard-layout-announcement");

    // → 두 번: 1열 → 3열
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await expect(announcement).toHaveText(
      "「테스트 차트」 위치 3열 1행, 크기 너비 6칸 높이 4칸",
    );

    // Shift+↓: 높이 4 → 5, Shift+←: 너비 6 → 5
    await page.keyboard.press("Shift+ArrowDown");
    await page.keyboard.press("Shift+ArrowLeft");
    await expect(announcement).toHaveText(
      "「테스트 차트」 위치 3열 1행, 크기 너비 5칸 높이 5칸",
    );
    // 키를 누르는 동안 포커스가 손잡이에 그대로 남아 이어서 조작할 수 있다
    await expect(handle).toBeFocused();

    await page.getByRole("button", { name: "완료" }).click();
    const captured = await putCapture.waitForRequest();
    expect(captured.payload).toMatchObject({
      positionX: 2,
      positionY: 0,
      width: 5,
      height: 5,
    });
  });

  test("아래 위젯을 ↑ 로 올리면 위 위젯과 자리를 바꾸고, 그리드 끝에서는 더 나가지 않는다", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1, {
      widgets: [
        createWidget({
          id: 1,
          chartId: 1,
          chartName: "위 차트",
          positionX: 0,
          positionY: 0,
          width: 6,
          height: 4,
        }),
        createWidget({
          id: 2,
          chartId: 2,
          chartName: "아래 차트",
          positionX: 0,
          positionY: 4,
          width: 6,
          height: 4,
        }),
      ],
    });
    const chart2 = createChart({ id: 2, name: "아래 차트" });
    await mockApi(page, "GET", "/api/v1/analytics/charts/2", chart2);
    await mockApi(page, "GET", "/api/v1/analytics/charts/2/data", {
      chart: chart2,
      queryResult: createQueryResult({
        columns: ["name", "value"],
        rows: [{ name: "B", value: 1 }],
        totalRows: 1,
      }),
      exportAllowed: true,
    });
    const put1 = await mockApi(
      page,
      "PUT",
      "/api/v1/analytics/dashboards/1/widgets/1",
      {},
      { capture: true },
    );
    const put2 = await mockApi(
      page,
      "PUT",
      "/api/v1/analytics/dashboards/1/widgets/2",
      {},
      { capture: true },
    );

    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();

    const lower = page.getByRole("button", {
      name: "「아래 차트」 위젯 이동·크기 조절",
    });
    await lower.focus();
    const announcement = page.getByTestId("dashboard-layout-announcement");

    // ← 는 왼쪽 끝이라 그대로 1열
    await page.keyboard.press("ArrowLeft");
    await expect(announcement).toHaveText(
      "「아래 차트」 위치 1열 5행, 크기 너비 6칸 높이 4칸",
    );

    // ↑ 한 번에 위 위젯과 자리를 바꾼다(끌어 올릴 때와 같음 — 압축으로 제자리에 돌아오지 않는다)
    await page.keyboard.press("ArrowUp");
    await expect(announcement).toHaveText(
      "「아래 차트」 위치 1열 1행, 크기 너비 6칸 높이 4칸",
    );

    await page.getByRole("button", { name: "완료" }).click();
    expect((await put2.waitForRequest()).payload).toMatchObject({
      positionX: 0,
      positionY: 0,
    });
    expect((await put1.waitForRequest()).payload).toMatchObject({
      positionX: 0,
      positionY: 4,
    });
  });

  test("키보드로 옮긴 위젯만 「완료」 때 저장하고, 저장이 실패하면 편집 모드와 옮긴 배치를 그대로 둔다 (WD-330 과 함께)", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1, {
      widgets: [
        createWidget({
          id: 1,
          chartId: 1,
          chartName: "왼쪽 차트",
          positionX: 0,
          positionY: 0,
          width: 4,
          height: 4,
        }),
        createWidget({
          id: 2,
          chartId: 2,
          chartName: "오른쪽 차트",
          positionX: 6,
          positionY: 0,
          width: 4,
          height: 4,
        }),
      ],
    });
    const chart2 = createChart({ id: 2, name: "오른쪽 차트" });
    await mockApi(page, "GET", "/api/v1/analytics/charts/2", chart2);
    await mockApi(page, "GET", "/api/v1/analytics/charts/2/data", {
      chart: chart2,
      queryResult: createQueryResult({
        columns: ["name", "value"],
        rows: [{ name: "B", value: 1 }],
        totalRows: 1,
      }),
      exportAllowed: true,
    });
    // 옮긴 위젯(1)의 저장은 서버 오류, 옮기지 않은 위젯(2)은 요청이 가면 안 된다
    const put1 = await mockApi(
      page,
      "PUT",
      "/api/v1/analytics/dashboards/1/widgets/1",
      { message: "서버 오류" },
      {
        status: 500,
        capture: true,
      },
    );
    const put2Requests: string[] = [];
    page.on("request", (req) => {
      if (
        req.method() === "PUT" &&
        req.url().endsWith("/api/v1/analytics/dashboards/1/widgets/2")
      ) {
        put2Requests.push(req.url());
      }
    });

    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();
    await page
      .getByRole("button", { name: "「왼쪽 차트」 위젯 이동·크기 조절" })
      .focus();
    await page.keyboard.press("ArrowRight");
    await expect(page.getByTestId("dashboard-layout-announcement")).toHaveText(
      "「왼쪽 차트」 위치 2열 1행, 크기 너비 4칸 높이 4칸",
    );

    await page.getByRole("button", { name: "완료" }).click();
    expect((await put1.waitForRequest()).payload).toMatchObject({
      positionX: 1,
      positionY: 0,
    });
    // 실패 안내 뒤에도 편집 모드 — 「완료」가 그대로 있고 옮긴 위치도 유지된다
    await expect(page.getByText(/배치를 저장하지 못했습니다/)).toBeVisible();
    await expect(page.getByRole("button", { name: "완료" })).toBeVisible();
    await page
      .getByRole("button", { name: "「왼쪽 차트」 위젯 이동·크기 조절" })
      .focus();
    await page.keyboard.press("Shift+ArrowUp");
    await expect(page.getByTestId("dashboard-layout-announcement")).toHaveText(
      "「왼쪽 차트」 위치 2열 1행, 크기 너비 4칸 높이 3칸",
    );
    expect(put2Requests).toEqual([]);
  });

  test("보기 모드에는 이동 손잡이가 없다", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    await page.goto("/analytics/dashboards/1");
    await expect(page.getByText("테스트 차트")).toBeVisible();
    await expect(
      page.getByRole("button", { name: /위젯 이동·크기 조절/ }),
    ).toHaveCount(0);
  });
});
