import type { Page } from "@playwright/test";

import {
  createChart,
  createQueryResult,
  createWidget,
} from "../../factories/analytics.factory";
import { setupDashboardEditorMocks } from "../../fixtures/analytics.fixture";
import { mockApi } from "../../fixtures/api-mock";
import { expect, test } from "../../fixtures/auth.fixture";

/**
 * 대시보드 편집 화면을 열어 「편집」 모드로 들어가고 첫 위젯의 이동 손잡이에 포커스를 둔다(WD-823 시험 공통 준비).
 * 이 호출 전에 setupDashboardEditorMocks 로 모킹을 끝내 둬야 한다.
 */
async function openEditorWithHandleFocused(page: Page) {
  await page.goto("/analytics/dashboards/1");
  await expect(page.getByText("테스트 차트", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "편집" }).click();
  const handle = page.getByRole("button", {
    name: "「테스트 차트」 위젯 이동·크기 조절",
  });
  await handle.focus();
  return handle;
}

/** 저사양처럼 CPU 를 6배 늦춘다 — 렌더가 키 입력을 못 따라와야 RGL 배치 동기화와 연타가 겹치는 경합이 드러난다(WD-823) */
async function throttleCpu(page: Page) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 6 });
}

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
    await expect(page.getByText("테스트 차트", { exact: true })).toBeVisible();
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
    // 안내 영역에 「저장하는 중」이 남지 않고 실패와 다시 옮길 수 있음을 알린다(WD-823 리뷰 후속)
    await expect(page.getByTestId("dashboard-layout-announcement")).toHaveText(
      "저장에 실패해 편집을 이어 갑니다. 위젯을 계속 옮기거나 다시 「완료」를 누를 수 있습니다.",
    );
    await page
      .getByRole("button", { name: "「왼쪽 차트」 위젯 이동·크기 조절" })
      .focus();
    await page.keyboard.press("Shift+ArrowUp");
    await expect(page.getByTestId("dashboard-layout-announcement")).toHaveText(
      "「왼쪽 차트」 위치 2열 1행, 크기 너비 4칸 높이 3칸",
    );
    expect(put2Requests).toEqual([]);
  });

  test("화살표·Shift+화살표를 기다리지 않고 연달아 눌러도 모든 키가 반영되고 「완료」 때 그대로 저장된다 (WD-823)", async ({
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

    await openEditorWithHandleFocused(page);
    await throttleCpu(page);

    // 안내를 기다리지 않고 연달아 누른다 — react-grid-layout 의 늦은 배치 메아리가 앞 키 결과를 덮던 경합(WD-823)
    // 시작 x0·w6·h4 → x3(→×3), h6(Shift+↓×2), w4(Shift+←×2)
    for (let i = 0; i < 3; i++) await page.keyboard.press("ArrowRight");
    for (let i = 0; i < 2; i++) await page.keyboard.press("Shift+ArrowDown");
    for (let i = 0; i < 2; i++) await page.keyboard.press("Shift+ArrowLeft");

    await expect(page.getByTestId("dashboard-layout-announcement")).toHaveText(
      "「테스트 차트」 위치 4열 1행, 크기 너비 4칸 높이 6칸",
    );
    await page.getByRole("button", { name: "완료" }).click();
    const captured = await putCapture.waitForRequest();
    expect(captured.payload).toMatchObject({
      positionX: 3,
      positionY: 0,
      width: 4,
      height: 6,
    });
  });

  test("→ 와 ← 를 기다리지 않고 누르면 제자리로 돌아오고, 그린 위치·안내·저장이 모두 1열이다 (WD-823)", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    // 저장 확인 — 「완료」는 서버 값과 달라진 위젯만 PUT 하므로, 1열(서버 값 positionX 0)로 돌아왔으면 PUT 이 없어야 한다.
    // 부모 배치가 2열로 남았다면 positionX 1 PUT 이 나가 여기 잡힌다.
    const putPayloads: unknown[] = [];
    page.on("request", (req) => {
      if (
        req.method() === "PUT" &&
        req.url().endsWith("/api/v1/analytics/dashboards/1/widgets/1")
      ) {
        putPayloads.push(req.postDataJSON());
      }
    });
    await mockApi(page, "PUT", "/api/v1/analytics/dashboards/1/widgets/1", {});

    await openEditorWithHandleFocused(page);

    // 그리드가 실제로 그린 가로 위치 — RGL 은 위젯을 style 의 translate(x, y) 로 놓는다(위젯 1개)
    const gridItem = page.locator(".react-grid-item");
    const drawnX = async () => {
      const style = (await gridItem.getAttribute("style")) ?? "";
      const m = /translate\((-?[\d.]+)px/.exec(style);
      return m ? Number(m[1]) : null;
    };
    const startX = await drawnX();
    expect(startX).not.toBeNull();

    await throttleCpu(page);

    // → 결과의 배치 동기화가 ← 의 렌더 뒤로 밀리면 RGL 은 2열을 그리고 부모는 1열을 들어 화면과 저장이 어긋났다(WD-823)
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowLeft");

    await expect(page.getByTestId("dashboard-layout-announcement")).toHaveText(
      "「테스트 차트」 위치 1열 1행, 크기 너비 6칸 높이 4칸",
    );
    await expect.poll(drawnX).toBe(startX);

    await page.getByRole("button", { name: "완료" }).click();
    await expect(page.getByRole("button", { name: "편집" })).toBeVisible();
    expect(putPayloads).toEqual([]);
  });

  test("키를 연달아 누르고 곧바로 「완료」를 눌러도 누른 키가 모두 저장된다 (WD-823)", async ({
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

    await openEditorWithHandleFocused(page);

    // → 세 번과 「완료」 클릭을 브라우저의 한 작업 안에서 잇달아 보낸다(WD-823).
    // RGL 의 메아리는 키 렌더 뒤 별도 작업에서 오므로, 이렇게 하면 「완료」 때 첫 키만 RGL 에 보냈고 둘째·셋째 키는
    // 반드시 대기열에 남아 있다. playwright 의 press 를 하나씩 기다리면 CPU 를 늦춰도 그 사이 메아리가 와서
    // 대기열이 비어 버려(옛 코드에서도 통과) 이 경우를 시험하지 못했다. 옛 코드는 여기서 PUT 을 아예 보내지 않았다.
    await page.evaluate(() => {
      const handleEl = document.activeElement as HTMLElement;
      for (let i = 0; i < 3; i++) {
        handleEl.dispatchEvent(
          new KeyboardEvent("keydown", {
            key: "ArrowRight",
            bubbles: true,
            cancelable: true,
          }),
        );
      }
      const done = Array.from(document.querySelectorAll("button")).find(
        (b) => b.textContent?.trim() === "완료",
      );
      done?.click();
    });

    const captured = await putCapture.waitForRequest();
    expect(captured.payload).toMatchObject({
      positionX: 3,
      positionY: 0,
      width: 6,
      height: 4,
    });
  });

  test("「완료」 저장을 기다리는 동안에는 화살표·끌기·편집 취소·차트 추가가 잠기고, 저장 중이라 옮길 수 없다고 안내한다 (WD-823 리뷰 후속)", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    // 저장 응답을 1.5초 늦춰 「저장 중」 창을 만든다 — 요청은 도착 즉시 캡처된다
    const putCapture = await mockApi(
      page,
      "PUT",
      "/api/v1/analytics/dashboards/1/widgets/1",
      {},
      { capture: true, delayMs: 1500 },
    );

    const handle = await openEditorWithHandleFocused(page);
    const announcement = page.getByTestId("dashboard-layout-announcement");
    const gridItem = page.locator(".react-grid-item");
    const drawnStyle = () => gridItem.getAttribute("style");

    await page.keyboard.press("ArrowRight");
    await expect(announcement).toHaveText(
      "「테스트 차트」 위치 2열 1행, 크기 너비 6칸 높이 4칸",
    );
    const styleBeforeSave = await drawnStyle();

    await page.getByRole("button", { name: "완료" }).click();
    expect((await putCapture.waitForRequest()).payload).toMatchObject({
      positionX: 1,
      positionY: 0,
    });

    // 저장 응답 전 — 저장 중이라 옮길 수 없다고 안내하고, 손잡이로 → 를 두 번 더 눌러도 화면 배치는 그대로다.
    // 옛 코드는 화면에서는 3열·4열로 움직였다가 저장 성공 뒤 편집이 끝나며 이 이동이 말없이 버려졌다.
    await expect(announcement).toHaveText(
      "배치를 저장하는 중입니다. 저장이 끝날 때까지 위젯을 옮길 수 없습니다.",
    );
    await handle.focus();
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    expect(await drawnStyle()).toBe(styleBeforeSave);
    // 마우스 끌기도 잠긴다 — RGL 은 끌 수 있는 항목에만 react-draggable 클래스를 붙인다
    await expect(gridItem).not.toHaveClass(/react-draggable(\s|$)/);
    // 취소 뒤 다시 편집하면 늦게 끝난 저장이 새 편집을 닫았으므로 취소·차트 추가도 잠근다
    await expect(page.getByTitle("편집 취소")).toBeDisabled();
    await expect(page.getByRole("button", { name: "차트 추가" })).toBeDisabled();

    // 저장이 끝나면 편집이 끝나고, 저장 요청은 처음 한 번(2열)뿐이다
    await expect(page.getByRole("button", { name: "편집" })).toBeVisible();
    expect(putCapture.requests).toHaveLength(1);
  });

  test("키를 누른 뒤 「편집 취소」하고 다시 편집하면 서버 배치에서 출발한다 (WD-823)", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);

    const handle = await openEditorWithHandleFocused(page);
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await expect(page.getByTestId("dashboard-layout-announcement")).toHaveText(
      "「테스트 차트」 위치 3열 1행, 크기 너비 6칸 높이 4칸",
    );

    await page.getByRole("button", { name: "편집 취소" }).click();
    await page.getByRole("button", { name: "편집" }).click();
    await handle.focus();
    await page.keyboard.press("ArrowRight");
    await expect(page.getByTestId("dashboard-layout-announcement")).toHaveText(
      "「테스트 차트」 위치 2열 1행, 크기 너비 6칸 높이 4칸",
    );
  });

  test("보기 모드에는 이동 손잡이가 없다", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    await page.goto("/analytics/dashboards/1");
    await expect(page.getByText("테스트 차트", { exact: true })).toBeVisible();
    await expect(
      page.getByRole("button", { name: /위젯 이동·크기 조절/ }),
    ).toHaveCount(0);
  });
});
