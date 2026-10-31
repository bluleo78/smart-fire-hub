import { mockApi } from "../fixtures/api-mock";
import { expect, test } from "../fixtures/auth.fixture";
import { grantPipelinePermissions } from "../fixtures/pipeline.fixture";

/**
 * 홈 — 조회 실패를 빈 상태로 보이지 않는다(WD-333).
 * 건강 상태·주의 필요·최근 대시보드·최근 데이터셋·활동 피드 조회가 서버 오류로 실패하면
 * 「0개」「없습니다. 만들기」 대신 카드마다 「…을 불러오지 못했습니다」와 「다시 시도」를 보이고,
 * 다시 시도하면 해당 조회를 다시 불러 정상 내용을 그리는지 확인한다.
 */
const FAIL = { message: "서버 오류" };
const FAILING_PATHS = [
  "/api/v1/dashboard/health",
  "/api/v1/dashboard/attention",
  "/api/v1/dashboard/activity",
  "/api/v1/analytics/dashboards",
  "/api/v1/datasets",
] as const;

test.describe("홈 — 조회 실패 안내 (WD-333)", () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page, ["pipeline:read", "pipeline:execute"]);
  });

  test("조회가 모두 실패하면 빈 상태·0개 대신 카드마다 실패 안내를 보이고 「모든 시스템 정상」을 말하지 않는다", async ({
    authenticatedPage: page,
  }) => {
    for (const path of FAILING_PATHS) {
      await mockApi(page, "GET", path, FAIL, { status: 500 });
    }
    await page.goto("/");

    await expect(
      page.getByText("시스템 상태를 불러오지 못했습니다."),
    ).toBeVisible();
    await expect(
      page.getByText("주의 필요 항목을 불러오지 못했습니다."),
    ).toBeVisible();
    await expect(
      page.getByText("대시보드를 불러오지 못했습니다."),
    ).toBeVisible();
    await expect(
      page.getByText("데이터셋을 불러오지 못했습니다."),
    ).toBeVisible();
    await expect(
      page.getByText("활동 내역을 불러오지 못했습니다."),
    ).toBeVisible();

    // 비어 있다는 문구·0개·정상 표시는 없다
    await expect(page.getByText("대시보드가 없습니다.")).toHaveCount(0);
    await expect(page.getByText("데이터셋이 없습니다.")).toHaveCount(0);
    await expect(page.getByText("활동 내역이 없습니다.")).toHaveCount(0);
    await expect(page.getByText("모든 시스템 정상")).toHaveCount(0);
    await expect(
      page.getByRole("alert").filter({ hasText: "불러오지 못했습니다" }),
    ).toHaveCount(5);
  });

  test("실패한 최근 데이터셋 카드에서 「다시 시도」를 누르면 다시 불러와 목록을 보인다", async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, "GET", "/api/v1/datasets", FAIL, { status: 500 });
    await page.goto("/");

    const alert = page
      .getByRole("alert")
      .filter({ hasText: "데이터셋을 불러오지 못했습니다." });
    await expect(alert).toBeVisible();

    // 서버가 회복된 뒤 다시 시도 → 목록이 그려진다
    const retryCapture = await mockApi(
      page,
      "GET",
      "/api/v1/datasets",
      {
        content: [
          {
            id: 7,
            name: "복구된 데이터셋",
            tableName: "recovered",
            description: null,
            category: null,
            datasetType: "SOURCE",
            storageType: "TABLE",
            originType: "UPLOAD",
            createdAt: "2026-10-30T00:00:00Z",
            isFavorite: false,
            tags: [],
            status: "NONE",
            statusNote: null,
          },
        ],
        page: 0,
        size: 5,
        totalElements: 1,
        totalPages: 1,
      },
      { capture: true },
    );
    await alert.getByRole("button", { name: "다시 시도" }).click();
    await retryCapture.waitForRequest();
    await expect(page.getByText("복구된 데이터셋")).toBeVisible();
    await expect(alert).toHaveCount(0);
  });
});
