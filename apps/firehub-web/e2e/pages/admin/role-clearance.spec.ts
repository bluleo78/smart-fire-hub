import {
  setupAdminAuth,
  setupRoleDetailMocks,
} from "../../fixtures/admin.fixture";
import { mockApi } from "../../fixtures/api-mock";
import { expect, test } from "../../fixtures/auth.fixture";
import { setupSecurityLevelMocks } from "../../fixtures/security-level.fixture";

/** 화면 3(목업 s3) — 세로 라디오 + 누적 설명, 별도 저장, 잠김 방지 2종, 시스템 ADMIN 고정. */
test.describe("역할 편집 — 데이터 열람 등급", () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSecurityLevelMocks(page);
  });

  test(
    "누적 설명과 함께 현재 자격이 선택돼 있고 저장 시 PUT",
    { tag: "@smoke" },
    async ({ authenticatedPage: page }) => {
      await setupRoleDetailMocks(page, 3, false);
      await mockApi(page, "GET", "/api/v1/roles/3/clearance", {
        roleId: 3,
        securityLevelId: 2,
        userCount: 14,
        fixed: false,
      });
      await mockApi(page, "POST", "/api/v1/roles/3/clearance/preview", {
        callerLostDatasetCount: 0,
        topLevelRoleCountAfter: 1,
        roleUserCount: 14,
      });
      const put = await mockApi(
        page,
        "PUT",
        "/api/v1/roles/3/clearance",
        {},
        { status: 204, capture: true },
      );
      await page.goto("/admin/roles/3");
      const card = page.getByTestId("role-clearance-card");
      await expect(card).toContainText(
        "이 역할이 볼 수 있는 가장 높은 보안 등급",
      );
      await expect(card).toContainText("공개·내부 데이터셋 열람");
      await expect(card).toContainText(
        "기밀까지 열람 — 단, 허용 목록에 있는 데이터셋만",
      );
      await expect(card).toContainText(
        "이 역할 사용자 14명. 여러 역할이 있으면 가장 높은 등급이 적용됩니다.",
      );
      await expect(card.getByRole("radio", { name: /내부/ })).toBeChecked();
      await card.getByRole("radio", { name: /민감/ }).check();
      await card.getByRole("button", { name: "저장" }).click();
      expect((await put.waitForRequest()).payload).toEqual({
        securityLevelId: 3,
      });
    },
  );

  test("본인 자격 하락은 확인 다이얼로그", async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    await mockApi(page, "GET", "/api/v1/roles/3/clearance", {
      roleId: 3,
      securityLevelId: 3,
      userCount: 2,
      fixed: false,
    });
    await mockApi(page, "POST", "/api/v1/roles/3/clearance/preview", {
      callerLostDatasetCount: 9,
      topLevelRoleCountAfter: 1,
      roleUserCount: 2,
    });
    const put = await mockApi(
      page,
      "PUT",
      "/api/v1/roles/3/clearance",
      {},
      { status: 204, capture: true },
    );
    await page.goto("/admin/roles/3");
    const card = page.getByTestId("role-clearance-card");
    await card.getByRole("radio", { name: /공개/ }).check();
    await card.getByRole("button", { name: "저장" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toContainText("열람 등급을 낮출까요?");
    await expect(dialog).toContainText(
      "저장 후 본인은 데이터셋 9개를 볼 수 없게 됩니다.",
    );
    await dialog.getByRole("button", { name: "낮추기" }).click();
    expect((await put.waitForRequest()).payload).toEqual({
      securityLevelId: 1,
    });
  });

  test("선택을 바꾸면 새 선택의 미리보기가 올 때까지 저장이 막혀 이전 결과로 확인을 건너뛰지 않는다", async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    await mockApi(page, "GET", "/api/v1/roles/3/clearance", {
      roleId: 3,
      securityLevelId: 3,
      userCount: 2,
      fixed: false,
    });
    // 내부(2)는 손실 0 즉시, 공개(1)는 손실 9 를 늦게 돌려준다 — 이전 선택의 결과가 남아 있으면 확인 없이 저장될 수 있는 구간.
    await page.route("**/api/v1/roles/3/clearance/preview", async (route) => {
      const { securityLevelId } = route.request().postDataJSON() as {
        securityLevelId: number;
      };
      if (securityLevelId === 1) await new Promise((r) => setTimeout(r, 800));
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          callerLostDatasetCount: securityLevelId === 1 ? 9 : 0,
          topLevelRoleCountAfter: 1,
          roleUserCount: 2,
        }),
      });
    });
    await page.goto("/admin/roles/3");
    const card = page.getByTestId("role-clearance-card");
    const save = card.getByRole("button", { name: "저장" });
    await card.getByRole("radio", { name: /내부/ }).check();
    await expect(save).toBeEnabled();
    await card.getByRole("radio", { name: /공개/ }).check();
    await expect(save).toBeDisabled();
    await expect(save).toBeEnabled();
    await save.click();
    await expect(page.getByRole("alertdialog")).toContainText(
      "저장 후 본인은 데이터셋 9개를 볼 수 없게 됩니다.",
    );
  });

  test("마지막 최상위 역할을 낮추면 저장 비활성 + 배너", async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    await mockApi(page, "GET", "/api/v1/roles/3/clearance", {
      roleId: 3,
      securityLevelId: 4,
      userCount: 1,
      fixed: false,
    });
    await mockApi(page, "POST", "/api/v1/roles/3/clearance/preview", {
      callerLostDatasetCount: 0,
      topLevelRoleCountAfter: 0,
      roleUserCount: 1,
    });
    await page.goto("/admin/roles/3");
    const card = page.getByTestId("role-clearance-card");
    await card.getByRole("radio", { name: /민감/ }).check();
    await expect(
      card.getByText(
        "최상위 등급을 열람할 수 있는 역할이 최소 1개 필요합니다.",
      ),
    ).toBeVisible();
    await expect(card.getByRole("button", { name: "저장" })).toBeDisabled();
  });

  test("시스템 ADMIN 은 최상위 고정 표시", async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 2, true);
    await mockApi(page, "GET", "/api/v1/roles/2/clearance", {
      roleId: 2,
      securityLevelId: 4,
      userCount: 1,
      fixed: true,
    });
    await page.goto("/admin/roles/2");
    const card = page.getByTestId("role-clearance-card");
    await expect(card).toContainText("기밀");
    await expect(card).toContainText("(최상위)");
    await expect(card).toContainText(
      "시스템 역할은 최상위 등급으로 고정됩니다.",
    );
    await expect(card.getByRole("radio")).toHaveCount(0);
  });

  test('권한 할당에 "보안" 카테고리와 PYTHON 우회 경고', async ({
    authenticatedPage: page,
  }) => {
    await setupRoleDetailMocks(page, 3, false);
    await mockApi(page, "GET", "/api/v1/roles/3/clearance", {
      roleId: 3,
      securityLevelId: 2,
      userCount: 1,
      fixed: false,
    });
    await mockApi(page, "GET", "/api/v1/permissions", [
      {
        id: 101,
        code: "security:settings",
        description: "보안 등급·정책 관리",
        category: "security",
      },
      {
        id: 102,
        code: "pipeline:python_execute",
        description: "Python 스텝 실행",
        category: "pipeline",
      },
    ]);
    await page.goto("/admin/roles/3");
    await expect(
      page.getByRole("heading", { name: "보안", level: 3 }),
    ).toBeVisible();
    await expect(
      page.getByText(
        "이 권한 보유자는 보안 등급 열람 통제를 우회할 수 있습니다",
        { exact: false },
      ),
    ).toBeVisible();
  });
});
