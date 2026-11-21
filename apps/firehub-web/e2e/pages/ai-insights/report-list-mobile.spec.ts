import { createReport } from "../../factories/ai-insight.factory";
import { createLevelSummary } from "../../factories/security-level.factory";
import { mockProactivePermissions } from "../../fixtures/ai-insight.fixture";
import { mockApi } from "../../fixtures/api-mock";
import { expect, test } from "../../fixtures/auth.fixture";

/**
 * 리포트 목록 — 휴대폰 폭(WD-334).
 * 390 폭에서 「제목」 칸이 고정 폭 칸들에 밀려 40px 로 줄어 제목 글자가 0px 이 되고, 등급 배지가 옆 칸 배지와 겹쳤다.
 * 제목 칸에 최소 폭을 두어 제목이 읽히고(말줄임 포함), 배지가 제목 칸 안에 들어오는지 확인한다.
 */
test.describe("리포트 목록 — 휴대폰 폭 (WD-334)", () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
    await mockApi(page, "GET", "/api/v1/proactive/messages/unread-count", {
      count: 0,
    });
  });

  test("제목 칸이 넓게 남아 제목이 보이고, 등급 배지가 스마트 작업 칸과 겹치지 않는다", async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, "GET", "/api/v1/proactive/reports", [
      {
        ...createReport({
          executionId: 2,
          title: "QA 일간 운영 요약 리포트 — 아주 긴 제목으로 말줄임 확인",
        }),
        securityLevel: createLevelSummary("공개"),
      },
    ]);
    await page.goto("/ai-insights/reports");

    const row = page.getByRole("row").nth(1);
    const titleCell = row.getByRole("cell").nth(0);
    const jobCell = row.getByRole("cell").nth(1);
    await expect(titleCell).toContainText("QA 일간 운영 요약 리포트");

    // 제목 칸이 최소 폭 이상이고, 제목 글자 영역이 실제로 보이는 폭을 갖는다
    const cellBox = (await titleCell.boundingBox())!;
    expect(cellBox.width).toBeGreaterThanOrEqual(180);
    const titleBox = (await titleCell
      .getByText(/QA 일간 운영 요약 리포트/)
      .boundingBox())!;
    expect(titleBox.width).toBeGreaterThan(60);

    // 등급 배지가 제목 칸 안에 있다 — 옆 「스마트 작업」 칸으로 넘치지 않는다
    const badgeBox = (await titleCell
      .getByTestId("security-level-badge")
      .boundingBox())!;
    const jobBox = (await jobCell.boundingBox())!;
    expect(badgeBox.x + badgeBox.width).toBeLessThanOrEqual(jobBox.x + 1);
  });

  test("긴 등급 이름 배지도 제목 칸 안에서 말줄임되고 title 로 전체 이름을 보인다 (WD-864)", async ({
    authenticatedPage: page,
  }) => {
    const longName = "개인정보보호법 적용대상 고객민감정보 외부반출 금지 등급";
    await mockApi(page, "GET", "/api/v1/proactive/reports", [
      {
        ...createReport({
          executionId: 2,
          title: "QA 일간 운영 요약 리포트 — 아주 긴 제목으로 말줄임 확인",
        }),
        securityLevel: { ...createLevelSummary("공개"), name: longName },
      },
    ]);
    await page.goto("/ai-insights/reports");

    const row = page.getByRole("row").nth(1);
    const titleCell = row.getByRole("cell").nth(0);
    const jobCell = row.getByRole("cell").nth(1);
    const badge = titleCell.getByTestId("security-level-badge");
    await expect(badge).toBeVisible();

    // 목록 표 칸 규칙(WD-658): 이름은 말줄임, 전체 이름은 title
    await expect(badge.locator("[title]")).toHaveAttribute("title", longName);
    const badgeBox = (await badge.boundingBox())!;
    const jobBox = (await jobCell.boundingBox())!;
    expect(badgeBox.x + badgeBox.width).toBeLessThanOrEqual(jobBox.x + 1);

    // 배지 상한 덕분에 제목 글자도 읽을 폭이 남는다
    const titleBox = (await titleCell
      .getByText(/QA 일간 운영 요약 리포트/)
      .boundingBox())!;
    expect(titleBox.width).toBeGreaterThan(60);
  });
});
