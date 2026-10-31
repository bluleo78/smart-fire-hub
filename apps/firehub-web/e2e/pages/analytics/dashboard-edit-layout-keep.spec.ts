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
 * 대시보드 편집 — 차트 추가·위젯 삭제 뒤에도 「완료」 전에 바꿔 둔 배치를 유지한다 (WD-331)
 * - 예전에는 추가 뒤 서버 값, 삭제 뒤 편집 시작 때 목록으로 레이아웃을 다시 만들어, 바꿔 둔 크기·위치가 안내 없이 되돌아갔고
 *   「완료」를 눌러도 저장되지 않았다.
 */

const W1 = createWidget({
  id: 1,
  chartId: 1,
  chartName: "테스트 차트",
  positionX: 0,
  positionY: 0,
});
const W2 = createWidget({
  id: 2,
  chartId: 2,
  chartName: "둘째 차트",
  positionX: 6,
  positionY: 0,
});
const W3 = createWidget({
  id: 3,
  chartId: 3,
  chartName: "셋째 차트",
  positionX: 0,
  positionY: 4,
});

/** 위젯 차트 메타·데이터를 차트 id 별로 모킹한다 */
async function mockWidgetCharts(page: Page) {
  const names: Record<string, string> = {
    "1": "테스트 차트",
    "2": "둘째 차트",
    "3": "셋째 차트",
  };
  await page.route(/\/api\/v1\/analytics\/charts\/(\d+)(\/data)?$/, (route) => {
    const [, id, data] = /charts\/(\d+)(\/data)?$/.exec(
      new URL(route.request().url()).pathname,
    )!;
    const chart = createChart({
      id: Number(id),
      name: names[id] ?? `차트 ${id}`,
    });
    const body = data
      ? {
          chart,
          queryResult: createQueryResult({
            columns: ["name", "value"],
            rows: [{ name: "A", value: 1 }],
            totalRows: 1,
          }),
          exportAllowed: true,
        }
      : chart;
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
}

/** 첫 위젯 오른쪽 아래 핸들을 끌어 폭을 줄이고 줄이기 전 폭을 돌려준다 — dashboard-chart-unsaved-guard.spec 과 같은 방식 */
async function shrinkFirstWidget(page: Page) {
  const gridItem = page.locator(".react-grid-item", { hasText: "테스트 차트" });
  const handle = gridItem.locator(".react-resizable-handle-se");
  await expect(handle).toBeVisible({ timeout: 5000 });
  await handle.hover();
  const box = (await handle.boundingBox())!;
  const initialWidth = (await gridItem.boundingBox())!.width;
  const renderedWidth = async () =>
    (await gridItem.boundingBox())?.width ?? initialWidth;
  const startX = box.x + box.width / 2;
  const startY = box.y + box.height / 2;
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  for (let offset = 30; offset <= 300; offset += 30) {
    await page.mouse.move(startX - offset, startY, { steps: 3 });
  }
  await expect.poll(renderedWidth).toBeLessThan(initialWidth - 100);
  await page.mouse.up();
  await expect(gridItem).not.toHaveClass(/\bresizing\b/);
  return initialWidth;
}

const firstWidgetWidth = async (page: Page) =>
  (await page
    .locator(".react-grid-item", { hasText: "테스트 차트" })
    .boundingBox())!.width;

test.describe("대시보드 편집 — 추가·삭제 뒤 바꿔 둔 배치 유지 (WD-331)", () => {
  test("크기를 바꾼 뒤 차트를 추가해도 바꾼 크기가 남고, 「완료」 때 그 크기가 저장된다", async ({
    authenticatedPage: page,
  }) => {
    await mockWidgetCharts(page);
    // 위젯 추가 POST 뒤에는 대시보드 조회가 새 위젯(W3)을 포함한다
    let added = false;
    await page.route(
      (url) => url.pathname === "/api/v1/analytics/dashboards/1",
      (route) =>
        route.request().method() === "GET"
          ? route.fulfill({
              status: 200,
              contentType: "application/json",
              body: JSON.stringify(
                createDashboard({
                  id: 1,
                  widgets: added ? [W1, W2, W3] : [W1, W2],
                }),
              ),
            })
          : route.fallback(),
    );
    await mockApi(
      page,
      "GET",
      "/api/v1/analytics/charts",
      createPageResponse([
        createChartListItem({ id: 3, name: "셋째 차트", chartType: "BAR" }),
      ]),
    );
    await page.route(
      (url) => url.pathname === "/api/v1/analytics/dashboards/1/widgets",
      (route) => {
        if (route.request().method() !== "POST") return route.fallback();
        added = true;
        return route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify(W3),
        });
      },
    );
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
    const put3 = await mockApi(
      page,
      "PUT",
      "/api/v1/analytics/dashboards/1/widgets/3",
      {},
      { capture: true },
    );

    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();
    const initialWidth = await shrinkFirstWidget(page);

    await page.getByRole("button", { name: "차트 추가" }).click();
    await page.getByRole("button", { name: /셋째 차트/ }).click();
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "추가", exact: true })
      .click();
    await expect(
      page.getByText("차트가 대시보드에 추가되었습니다."),
    ).toBeVisible();
    await expect(
      page.locator(".react-grid-item", { hasText: "셋째 차트" }),
    ).toBeVisible();

    // 바꾼 폭이 원래대로 돌아가지 않는다
    expect(await firstWidgetWidth(page)).toBeLessThan(initialWidth - 100);

    await page.getByRole("button", { name: "완료" }).click();
    const payload = (await put1.waitForRequest()).payload as { width: number };
    expect(payload.width).toBeLessThan(6);
    await expect(
      page.getByRole("button", { name: "편집", exact: true }),
    ).toBeVisible();
    // 바꾸지 않은 위젯·방금 추가한 위젯은 저장 요청이 없다
    expect(put2.lastRequest()).toBeUndefined();
    expect(put3.lastRequest()).toBeUndefined();
  });

  test("크기를 바꾼 뒤 다른 위젯을 삭제해도 바꾼 크기가 남고, 「완료」 때 그 크기가 저장된다", async ({
    authenticatedPage: page,
  }) => {
    await mockWidgetCharts(page);
    await mockApi(
      page,
      "GET",
      "/api/v1/analytics/dashboards/1",
      createDashboard({ id: 1, widgets: [W1, W2] }),
    );
    await mockApi(
      page,
      "GET",
      "/api/v1/analytics/charts",
      createPageResponse([]),
    );
    const del2 = await mockApi(
      page,
      "DELETE",
      "/api/v1/analytics/dashboards/1/widgets/2",
      null,
      {
        status: 204,
        capture: true,
      },
    );
    const put1 = await mockApi(
      page,
      "PUT",
      "/api/v1/analytics/dashboards/1/widgets/1",
      {},
      { capture: true },
    );

    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();
    const initialWidth = await shrinkFirstWidget(page);

    await page
      .locator(".react-grid-item", { hasText: "둘째 차트" })
      .getByRole("button", { name: "위젯 제거" })
      .click();
    await page
      .getByRole("alertdialog")
      .getByRole("button", { name: "삭제" })
      .click();
    await del2.waitForRequest();
    await expect(page.getByText("위젯이 제거되었습니다.")).toBeVisible();
    await expect(
      page.locator(".react-grid-item", { hasText: "둘째 차트" }),
    ).toHaveCount(0);

    expect(await firstWidgetWidth(page)).toBeLessThan(initialWidth - 100);

    await page.getByRole("button", { name: "완료" }).click();
    const payload = (await put1.waitForRequest()).payload as { width: number };
    expect(payload.width).toBeLessThan(6);
  });
});
