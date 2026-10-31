import type { Page } from "@playwright/test";

import {
  createChart,
  createChartListItem,
  createDashboard,
  createQueryResult,
  createWidget,
} from "../../factories/analytics.factory";
import { createPageResponse, mockApi } from "../../fixtures/api-mock";
import { expect, test } from "../../fixtures/auth.fixture";

/**
 * 대시보드 편집 — 위젯 추가·삭제가 서버 상태와 어긋나 실패하면 서버 목록을 다시 읽어 화면을 맞춘다 (WD-367)
 * - 다른 탭에서 먼저 지운 위젯 삭제(404): 예전에는 실패 토스트만 뜨고 위젯이 남아, 다시 눌러도 같은 오류가 되풀이됐다.
 * - 다른 탭에서 먼저 추가한 차트(409): 예전에는 그 위젯이 이 탭에 끝내 보이지 않았다.
 * - 다른 곳에서 지운 차트 추가(404): 예전에는 차트 고르기 목록에 지워진 차트가 그대로 남았다.
 */

const W1 = createWidget({ id: 1, chartId: 1, chartName: "테스트 차트", positionX: 0, positionY: 0 });
const W2 = createWidget({ id: 2, chartId: 2, chartName: "둘째 차트", positionX: 6, positionY: 0 });
const W3 = createWidget({ id: 3, chartId: 3, chartName: "셋째 차트", positionX: 0, positionY: 4 });

/** 위젯 차트 메타·데이터를 차트 id 별로 모킹한다 */
async function mockWidgetCharts(page: Page) {
  const names: Record<string, string> = { "1": "테스트 차트", "2": "둘째 차트", "3": "셋째 차트" };
  await page.route(/\/api\/v1\/analytics\/charts\/(\d+)(\/data)?$/, (route) => {
    const [, id, data] = /charts\/(\d+)(\/data)?$/.exec(new URL(route.request().url()).pathname)!;
    const chart = createChart({ id: Number(id), name: names[id] ?? `차트 ${id}` });
    const body = data
      ? {
          chart,
          queryResult: createQueryResult({ columns: ["name", "value"], rows: [{ name: "A", value: 1 }], totalRows: 1 }),
          exportAllowed: true,
        }
      : chart;
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });
}

/** 대시보드 GET 을 모킹한다 — widgets() 가 그때그때의 서버 위젯 목록을 돌려주고, 조회 횟수를 센다 */
async function mockDashboard(page: Page, widgets: () => ReturnType<typeof createWidget>[]) {
  const counter = { gets: 0 };
  await page.route(
    (url) => url.pathname === "/api/v1/analytics/dashboards/1",
    (route) => {
      if (route.request().method() !== "GET") return route.fallback();
      counter.gets += 1;
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(createDashboard({ id: 1, widgets: widgets() })),
      });
    },
  );
  return counter;
}

/** 이름이 name 인 위젯의 오른쪽 아래 핸들을 끌어 폭을 줄인다 — dashboard-edit-layout-keep.spec 과 같은 방식 */
async function shrinkWidget(page: Page, name: string) {
  const gridItem = page.locator(".react-grid-item", { hasText: name });
  const handle = gridItem.locator(".react-resizable-handle-se");
  await expect(handle).toBeVisible({ timeout: 5000 });
  await handle.hover();
  const box = (await handle.boundingBox())!;
  const initialWidth = (await gridItem.boundingBox())!.width;
  const startX = box.x + box.width / 2;
  const startY = box.y + box.height / 2;
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  for (let offset = 30; offset <= 300; offset += 30) {
    await page.mouse.move(startX - offset, startY, { steps: 3 });
  }
  await expect.poll(async () => (await gridItem.boundingBox())?.width ?? initialWidth).toBeLessThan(initialWidth - 100);
  await page.mouse.up();
  await expect(gridItem).not.toHaveClass(/\bresizing\b/);
}

/** 서버 오류 응답 본문(ErrorResponse) */
const errorBody = (status: number, message: string) =>
  JSON.stringify({ status, error: "Error", message, timestamp: "2026-10-31T00:00:00" });

test.describe("대시보드 편집 — 실패한 추가·삭제 뒤 서버 상태로 맞추기 (WD-367)", () => {
  test("다른 곳에서 이미 지운 위젯을 삭제하면(404) 화면에서도 빠지고 「이미 삭제됨」으로 안내한다", async ({
    authenticatedPage: page,
  }) => {
    await mockWidgetCharts(page);
    // 다른 탭이 W2 를 지웠다 — 삭제 요청이 404 로 실패한 뒤 서버 목록에는 W2 가 없다
    let deletedElsewhere = false;
    const dash = await mockDashboard(page, () => (deletedElsewhere ? [W1] : [W1, W2]));
    await mockApi(page, "GET", "/api/v1/analytics/charts", createPageResponse([]));
    let deleteCalls = 0;
    await page.route(
      (url) => url.pathname === "/api/v1/analytics/dashboards/1/widgets/2",
      (route) => {
        if (route.request().method() !== "DELETE") return route.fallback();
        deleteCalls += 1;
        deletedElsewhere = true;
        return route.fulfill({
          status: 404,
          contentType: "application/json",
          body: errorBody(404, "위젯을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."),
        });
      },
    );
    const put1 = await mockApi(page, "PUT", "/api/v1/analytics/dashboards/1/widgets/1", {}, { capture: true });

    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();
    const getsBefore = dash.gets;

    await page
      .locator(".react-grid-item", { hasText: "둘째 차트" })
      .getByRole("button", { name: "위젯 제거" })
      .click();
    await page.getByRole("alertdialog").getByRole("button", { name: "삭제" }).click();

    // 실패 토스트 대신 「이미 삭제됨」 안내가 뜨고, 위젯이 화면에서 빠진다
    await expect(page.getByText("다른 곳에서 이미 삭제된 위젯이라 화면에서도 뺐습니다.")).toBeVisible();
    await expect(page.locator(".react-grid-item", { hasText: "둘째 차트" })).toHaveCount(0);
    await expect(page.locator(".react-grid-item", { hasText: "테스트 차트" })).toBeVisible();
    await expect(page.getByText("위젯 제거에 실패했습니다.")).toHaveCount(0);
    // 서버 목록을 다시 읽었고, 삭제는 한 번만 보냈다(되풀이할 버튼이 남지 않는다)
    expect(dash.gets).toBeGreaterThan(getsBefore);
    expect(deleteCalls).toBe(1);

    // 「완료」 저장도 남은 위젯만 다룬다 — 바꾼 배치가 없으니 저장 요청이 없고 편집이 끝난다
    await page.getByRole("button", { name: "완료" }).click();
    await expect(page.getByRole("button", { name: "편집", exact: true })).toBeVisible();
    expect(put1.lastRequest()).toBeUndefined();
  });

  test("서버에 위젯이 그대로 있는 삭제 실패(500)는 실패 토스트를 보이고 위젯을 남긴다", async ({
    authenticatedPage: page,
  }) => {
    await mockWidgetCharts(page);
    await mockDashboard(page, () => [W1, W2]);
    await mockApi(page, "GET", "/api/v1/analytics/charts", createPageResponse([]));
    await mockApi(
      page,
      "DELETE",
      "/api/v1/analytics/dashboards/1/widgets/2",
      { status: 500, error: "Error", message: "서버 오류가 발생했습니다." },
      { status: 500 },
    );

    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();
    await page
      .locator(".react-grid-item", { hasText: "둘째 차트" })
      .getByRole("button", { name: "위젯 제거" })
      .click();
    await page.getByRole("alertdialog").getByRole("button", { name: "삭제" }).click();

    await expect(page.getByText("서버 오류가 발생했습니다.")).toBeVisible();
    await expect(page.getByText("다른 곳에서 이미 삭제된 위젯이라 화면에서도 뺐습니다.")).toHaveCount(0);
    await expect(page.locator(".react-grid-item", { hasText: "둘째 차트" })).toBeVisible();
  });

  test("다른 탭에서 먼저 추가한 차트를 추가하면(409) 사유를 보이고 그 위젯이 이 탭에도 나타난다", async ({
    authenticatedPage: page,
  }) => {
    await mockWidgetCharts(page);
    // 다른 탭이 차트 3 을 먼저 추가했다 — 이 탭이 편집을 시작할 때는 아직 W3 가 없었다
    let addedElsewhere = false;
    await mockDashboard(page, () => (addedElsewhere ? [W1, W2, W3] : [W1, W2]));
    await mockApi(
      page,
      "GET",
      "/api/v1/analytics/charts",
      createPageResponse([createChartListItem({ id: 3, name: "셋째 차트", chartType: "BAR" })]),
    );
    let postPayload: unknown;
    await page.route(
      (url) => url.pathname === "/api/v1/analytics/dashboards/1/widgets",
      (route) => {
        if (route.request().method() !== "POST") return route.fallback();
        postPayload = route.request().postDataJSON();
        addedElsewhere = true;
        return route.fulfill({
          status: 409,
          contentType: "application/json",
          body: errorBody(409, "이미 대시보드에 추가된 차트입니다"),
        });
      },
    );

    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();
    await page.getByRole("button", { name: "차트 추가" }).click();
    await page.getByRole("button", { name: /셋째 차트/ }).click();
    await page.getByRole("dialog").getByRole("button", { name: "추가", exact: true }).click();

    await expect(page.getByText("이미 대시보드에 추가된 차트입니다")).toBeVisible();
    expect(postPayload).toMatchObject({ chartId: 3 });
    // 서버 목록을 다시 읽어 다른 탭이 추가한 위젯이 나타난다
    await page.keyboard.press("Escape");
    await expect(page.locator(".react-grid-item", { hasText: "셋째 차트" })).toBeVisible();
    await expect(page.locator(".react-grid-item", { hasText: "테스트 차트" })).toBeVisible();
  });

  test("다른 곳에서 지운 차트를 추가하면(404) 사유를 보이고 차트 고르기 목록을 다시 읽어 그 차트를 뺀다", async ({
    authenticatedPage: page,
  }) => {
    await mockWidgetCharts(page);
    await mockDashboard(page, () => [W1, W2]);
    // 차트 4 가 다른 곳에서 지워졌다 — 404 뒤 목록 조회에는 차트 4 가 없다
    let chartDeleted = false;
    let listGets = 0;
    await page.route(
      (url) => url.pathname === "/api/v1/analytics/charts",
      (route) => {
        if (route.request().method() !== "GET") return route.fallback();
        listGets += 1;
        const items = [createChartListItem({ id: 5, name: "다섯째 차트", chartType: "BAR" })];
        if (!chartDeleted) items.unshift(createChartListItem({ id: 4, name: "넷째 차트", chartType: "BAR" }));
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(createPageResponse(items)) });
      },
    );
    await page.route(
      (url) => url.pathname === "/api/v1/analytics/dashboards/1/widgets",
      (route) => {
        if (route.request().method() !== "POST") return route.fallback();
        chartDeleted = true;
        return route.fulfill({
          status: 404,
          contentType: "application/json",
          body: errorBody(404, "차트를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."),
        });
      },
    );

    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();
    await page.getByRole("button", { name: "차트 추가" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: /넷째 차트/ }).click();
    const listGetsBefore = listGets;
    await dialog.getByRole("button", { name: "추가", exact: true }).click();

    await expect(page.getByText("차트를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.")).toBeVisible();
    // 목록을 다시 읽어 지워진 차트가 빠지고 다른 차트는 남는다
    await expect.poll(() => listGets).toBeGreaterThan(listGetsBefore);
    await expect(dialog.getByRole("button", { name: /넷째 차트/ })).toHaveCount(0);
    await expect(dialog.getByRole("button", { name: /다섯째 차트/ })).toBeVisible();
    // 대시보드 위젯은 그대로다
    await page.keyboard.press("Escape");
    await expect(page.locator(".react-grid-item")).toHaveCount(2);
  });
  test("「완료」 저장 중 다른 곳에서 지운 위젯이 있으면(404) 사유를 보이고 그 위젯을 빼, 다시 「완료」하면 편집이 끝난다", async ({
    authenticatedPage: page,
  }) => {
    await mockWidgetCharts(page);
    let deletedElsewhere = false;
    await mockDashboard(page, () => (deletedElsewhere ? [W1] : [W1, W2]));
    await mockApi(page, "GET", "/api/v1/analytics/charts", createPageResponse([]));
    let put2Calls = 0;
    await page.route(
      (url) => url.pathname === "/api/v1/analytics/dashboards/1/widgets/2",
      (route) => {
        if (route.request().method() !== "PUT") return route.fallback();
        put2Calls += 1;
        deletedElsewhere = true;
        return route.fulfill({
          status: 404,
          contentType: "application/json",
          body: errorBody(404, "위젯을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."),
        });
      },
    );

    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();
    await shrinkWidget(page, "둘째 차트");

    await page.getByRole("button", { name: "완료" }).click();
    await expect(page.getByText(/배치를 저장하지 못했습니다 — 위젯을 찾을 수 없습니다/)).toBeVisible();
    // 서버 목록으로 맞춰 지워진 위젯이 빠지고, 편집은 이어진다
    await expect(page.locator(".react-grid-item", { hasText: "둘째 차트" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "완료" })).toBeVisible();

    // 다시 「완료」하면 지워진 위젯은 보내지 않고 편집이 끝난다
    await page.getByRole("button", { name: "완료" }).click();
    await expect(page.getByRole("button", { name: "편집", exact: true })).toBeVisible();
    expect(put2Calls).toBe(1);
  });
});
