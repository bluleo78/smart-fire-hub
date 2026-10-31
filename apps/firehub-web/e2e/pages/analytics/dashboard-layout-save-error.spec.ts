import type { Page } from "@playwright/test";

import { setupDashboardEditorMocks } from "../../fixtures/analytics.fixture";
import { mockApi } from "../../fixtures/api-mock";
import { expect, test } from "../../fixtures/auth.fixture";

/**
 * 대시보드 편집 「완료」 — 배치 저장 실패 안내 (WD-330)
 * - 예전에는 위젯 PUT 결과를 기다리지 않고 편집 모드를 닫아, 403·400·500 이 나도 안내 없이 저장된 것처럼 보였다.
 * - 이제 저장을 모두 기다려 실패하면 사유를 토스트로 알리고 편집 모드·바꾼 배치를 그대로 둔다.
 */

const WIDGET_PUT = "/api/v1/analytics/dashboards/1/widgets/1";

/** 편집 모드에서 위젯 오른쪽 아래 핸들을 끌어 폭을 줄이고, 줄이기 전 폭을 돌려준다 — dashboard-chart-unsaved-guard.spec 과 같은 방식 */
async function shrinkWidget(page: Page) {
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

test.describe("대시보드 편집 「완료」 — 배치 저장 실패 안내 (WD-330)", () => {
  test("저장이 403 으로 실패하면 사유를 알리고 편집 모드와 바꾼 배치를 그대로 둔다", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    const putCapture = await mockApi(
      page,
      "PUT",
      WIDGET_PUT,
      { status: 403, message: "이 작업을 수행할 권한이 없습니다." },
      { status: 403, capture: true },
    );
    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();
    const initialWidth = await shrinkWidget(page);

    await page.getByRole("button", { name: "완료" }).click();
    const payload = (await putCapture.waitForRequest()).payload as {
      width: number;
    };
    expect(payload.width).toBeLessThan(6);

    // 실패 사유(서버 문구)와 무엇이 실패했는지를 함께 알린다
    await expect(
      page.getByText(
        "배치를 저장하지 못했습니다 — 이 작업을 수행할 권한이 없습니다.",
      ),
    ).toBeVisible();
    // 편집 모드가 유지돼 다시 「완료」를 누르거나 「편집 취소」로 되돌릴 수 있다
    await expect(page.getByRole("button", { name: "완료" })).toBeEnabled();
    await expect(page.getByRole("button", { name: "편집 취소" })).toBeVisible();
    const gridItem = page.locator(".react-grid-item", {
      hasText: "테스트 차트",
    });
    // 화면은 서버 값(원래 폭)으로 몰래 되돌아가지 않고 줄인 폭을 유지한다
    expect((await gridItem.boundingBox())!.width).toBeLessThan(
      initialWidth - 100,
    );
  });

  test("본문 없는 500 이면 「잠시 후 다시 시도」로 안내하고, 다시 「완료」해 성공하면 편집 모드가 닫힌다", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    let calls = 0;
    // 첫 PUT 은 500, 두 번째는 성공 — 다시 시도로 저장되는 흐름
    await page.route(
      (url) => url.pathname === WIDGET_PUT,
      (route) => {
        if (route.request().method() !== "PUT") return route.fallback();
        calls += 1;
        return calls === 1
          ? route.fulfill({ status: 500, body: "" })
          : route.fulfill({
              status: 200,
              contentType: "application/json",
              body: "{}",
            });
      },
    );
    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();
    await shrinkWidget(page);

    await page.getByRole("button", { name: "완료" }).click();
    await expect(
      page.getByText(
        "배치를 저장하지 못했습니다 — 잠시 후 다시 시도해 주세요.",
      ),
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "완료" })).toBeVisible();

    await page.getByRole("button", { name: "완료" }).click();
    await expect(
      page.getByRole("button", { name: "편집", exact: true }),
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "완료" })).toHaveCount(0);
    expect(calls).toBe(2);
  });

  test("배치를 바꾸지 않고 「완료」하면 저장 요청 없이 편집 모드를 닫는다", async ({
    authenticatedPage: page,
  }) => {
    await setupDashboardEditorMocks(page, 1);
    const putCapture = await mockApi(
      page,
      "PUT",
      WIDGET_PUT,
      {},
      { capture: true },
    );
    await page.goto("/analytics/dashboards/1");
    await page.getByRole("button", { name: "편집" }).click();
    await expect(
      page.locator(".react-resizable-handle-se").first(),
    ).toBeVisible();

    await page.getByRole("button", { name: "완료" }).click();
    await expect(
      page.getByRole("button", { name: "편집", exact: true }),
    ).toBeVisible();
    expect(putCapture.lastRequest()).toBeUndefined();
  });
});
