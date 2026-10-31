/**
 * 파이프라인 수동 실행 더블클릭 중복 방지 E2E 테스트 (WD-208)
 *
 * 「실행」·「재실행」을 더블클릭하면 버튼 disabled(mutation isPending)가 반영되기 전에 두 번째 클릭이 들어가
 * 같은 초에 수동 실행이 2건 생기고 APPEND 출력 행이 두 배가 됐다. 요청이 끝날 때까지 들어온 클릭은 무시해야 한다.
 * 서버 응답을 일부러 늦춰 요청이 진행 중인 동안 두 번째 클릭이 들어가게 만들고, 실행 API 호출 횟수를 센다.
 */

import type { Page } from "@playwright/test";

import {
  createExecutionDetail,
  createStepExecution,
} from "../../factories/pipeline.factory";
import { mockApi } from "../../fixtures/api-mock";
import { expect, test } from "../../fixtures/auth.fixture";
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/** POST /pipelines/1/execute 를 늦게 응답하며 호출 횟수를 세는 라우트를 건다. 반환 함수로 현재 횟수를 읽는다. */
async function countExecuteCalls(page: Page): Promise<() => number> {
  let count = 0;
  await page.route("**/api/v1/pipelines/1/execute", async (route) => {
    if (route.request().method() !== "POST") {
      await route.continue();
      return;
    }
    count += 1;
    // 응답을 늦춰 두 번째 클릭이 「요청 진행 중」에 들어가게 한다
    await new Promise((resolve) => setTimeout(resolve, 600));
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: 99,
        pipelineId: 1,
        status: "RUNNING",
        triggeredBy: "MANUAL",
        executedBy: "testuser",
        startedAt: "2024-01-01T00:10:00Z",
        completedAt: null,
        errorMessage: null,
      }),
    });
  });
  return () => count;
}

test.describe("파이프라인 실행 더블클릭 중복 방지 (WD-208)", () => {
  // 실행·수정·트리거 편집 버튼은 권한이 있을 때만 보인다(WD-219) — 기본 인증 fixture 는 권한이 비어 있어 모든 쓰기 권한을 준다.
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test("「실행」 버튼을 더블클릭해도 실행 API 는 한 번만 호출된다", async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    const executeCount = await countExecuteCalls(page);

    await page.goto("/pipelines/1");
    const executeBtn = page.getByRole("button", { name: "실행", exact: true });
    await expect(executeBtn).toBeEnabled();

    await executeBtn.dblclick();

    // 요청이 끝나 성공 토스트가 뜬 뒤에도 호출은 1회여야 한다
    await expect(
      page
        .locator("[data-sonner-toast]")
        .filter({ hasText: "파이프라인 실행이 시작되었습니다." })
        .first(),
    ).toBeVisible();
    await page.waitForTimeout(300);
    expect(executeCount()).toBe(1);
  });

  test("요청이 끝난 뒤에는 「실행」을 다시 누를 수 있다", async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    const executeCount = await countExecuteCalls(page);

    await page.goto("/pipelines/1");
    const executeBtn = page.getByRole("button", { name: "실행", exact: true });

    await executeBtn.click();
    await expect.poll(executeCount).toBe(1);
    // 첫 요청이 끝나 버튼이 다시 활성화되면 두 번째 실행이 정상적으로 나간다(플래그 해제 확인)
    await expect(executeBtn).toBeEnabled();
    await executeBtn.click();
    await expect.poll(executeCount).toBe(2);
  });

  test("「재실행」 버튼을 더블클릭해도 실행 API 는 한 번만 호출된다", async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    await mockApi(
      page,
      "GET",
      "/api/v1/pipelines/1/executions/2",
      createExecutionDetail({
        id: 2,
        pipelineId: 1,
        status: "FAILED",
        stepExecutions: [
          createStepExecution({
            id: 1,
            stepName: "데이터 추출",
            status: "FAILED",
          }),
        ],
        startedAt: "2024-01-01T00:00:00Z",
        completedAt: "2024-01-01T00:02:00Z",
      }),
    );
    await mockApi(
      page,
      "GET",
      "/api/v1/pipelines/1/executions/99",
      createExecutionDetail({
        id: 99,
        pipelineId: 1,
        status: "RUNNING",
        stepExecutions: [],
      }),
    );
    const executeCount = await countExecuteCalls(page);

    await page.goto("/pipelines/1/executions/2");
    const rerunBtn = page.getByRole("button", {
      name: "현재 설정으로 다시 실행",
    });
    await expect(rerunBtn).toBeEnabled();

    await rerunBtn.dblclick();

    await expect(page).toHaveURL(/\/pipelines\/1\/executions\/99$/);
    await page.waitForTimeout(300);
    expect(executeCount()).toBe(1);
  });
});
