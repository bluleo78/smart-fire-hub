import type { Page } from "@playwright/test";

import { createDataset } from "../../factories/dataset.factory";
import { mockApi } from "../../fixtures/api-mock";
import { expect, test } from "../../fixtures/auth.fixture";
import { grantPipelinePermissions } from '../../fixtures/pipeline.fixture';

/**
 * 파이프라인 스텝 출력 컬럼명 길이 상한 E2E (WD-95)
 *
 * 출력 데이터셋을 지정하지 않은 스텝의 출력 컬럼(Python outputColumns, AI 분류 outputColumns, API 호출 대상 컬럼)은
 * 실행 시 자동 생성되는 임시(TEMP) 데이터셋의 컬럼명이 된다. 백엔드가 63자를 넘는 새 컬럼명을 거부하므로(PostgreSQL
 * 63바이트 절단 방지) 편집기에서도 같은 상한·문구(「칼럼명은 63자 이하여야 합니다」)로 인라인 오류를 띄운다.
 * maxLength 로 조용히 자르지 않는다 — 붙여넣은 긴 이름이 잘려 다른 칼럼과 같아지는 것을 사용자가 모르게 되기 때문이다.
 */

const MESSAGE = "칼럼명은 63자 이하여야 합니다";

/** prefix 뒤를 'x' 로 채워 정확히 length 자인 이름을 만든다. */
function nameOf(prefix: string, length: number): string {
  return prefix + "x".repeat(length - prefix.length);
}

/** 새 파이프라인 편집기를 열고 스텝 하나를 추가한 뒤 스텝 타입을 고른다. */
async function openNewStep(
  page: Page,
  stepTypeLabel: string,
  datasets: unknown[] = [],
) {
  await mockApi(page, "GET", "/api/v1/datasets", {
    content: datasets,
    page: 0,
    size: 1000,
    totalElements: datasets.length,
    totalPages: datasets.length > 0 ? 1 : 0,
  });
  await mockApi(page, "GET", "/api/v1/api-connections/selectable", []);

  await page.goto("/pipelines/new");
  await page
    .getByPlaceholder(/파이프라인 이름|이름 입력/)
    .first()
    .fill("WD-95 출력 컬럼 길이");
  await page
    .getByRole("button", { name: /스텝 추가/ })
    .first()
    .click();
  await expect(page.locator("#step-name")).toBeVisible({ timeout: 10000 });
  await page.locator("#step-name").fill("출력 스텝");

  const typeSelect = page.getByRole("combobox").first();
  await typeSelect.click();
  await page.getByRole("option", { name: stepTypeLabel, exact: true }).click();
}

test.describe("파이프라인 출력 컬럼명 길이 상한 (WD-95)", () => {
  // 추가·삭제·생성 경로·실행·수정 버튼은 권한이 있을 때만 보인다(WD-219) — 기본 인증 fixture 는 권한이 비어 있어 모든 파이프라인 권한을 준다.
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test("Python 출력 컬럼명 64자는 인라인 오류, 63자는 오류 없음", async ({
    authenticatedPage: page,
  }) => {
    await openNewStep(page, "Python");

    await page.getByRole("button", { name: "출력 컬럼 추가" }).click();
    const nameInput = page.locator("#python-col-name-0");

    await nameInput.fill(nameOf("py_", 64));
    await expect(page.getByText(MESSAGE)).toBeVisible();
    await expect(nameInput).toHaveAttribute("aria-invalid", "true");

    await nameInput.fill(nameOf("py_", 63));
    await expect(page.getByText(MESSAGE)).toHaveCount(0);
    await expect(nameInput).toHaveValue(nameOf("py_", 63));
  });

  /**
   * WD-210: 출력 데이터셋을 쓰는 Python 스크립트는 stdout 전체가 JSON 하나여야 적재된다(디버그 print 가 섞이면 스텝 실패).
   * 편집기 안내에 「결과 JSON 한 번만 print, 디버그는 sys.stderr」 를 보인다.
   */
  test("Python 출력 컬럼 안내에 결과 JSON 한 번만 print·디버그는 sys.stderr 가 보인다 (WD-210)", async ({
    authenticatedPage: page,
  }) => {
    await openNewStep(page, "Python");

    const hint = page.getByText(
      /stdout에는 결과 JSON을\s*한 번만\s*print 하세요/,
    );
    await expect(hint).toBeVisible();
    await expect(hint).toContainText(
      "디버그 출력은 sys.stderr로 보내야 합니다",
    );
  });

  /**
   * WD-210: 출력 데이터셋을 지정한 Python 스텝에서는 출력 컬럼 편집(과 그 안내)이 숨겨지므로,
   * 출력 데이터셋 선택 아래에 같은 규칙 안내를 보인다 — 결함이 실제로 일어나는 구성이 바로 이 경우다.
   */
  test("출력 데이터셋을 지정한 Python 스텝에도 결과 JSON 한 번만 print 안내가 보인다 (WD-210)", async ({
    authenticatedPage: page,
  }) => {
    await openNewStep(page, "Python", [
      createDataset({ id: 7, name: "WD210 출력" }),
    ]);
    // 지정 전에는 출력 컬럼 편집 쪽 안내만 있고 데이터셋 지정용 안내는 없다
    await expect(page.getByTestId("python-stdout-json-help")).toHaveCount(0);

    await page.getByLabel("출력 데이터셋").click();
    await page.getByRole("option", { name: "WD210 출력" }).click();

    const help = page.getByTestId("python-stdout-json-help");
    await expect(help).toBeVisible();
    await expect(help).toContainText(
      "결과 JSON을 stdout에 한 번만 print 하세요",
    );
    await expect(help).toContainText("sys.stderr");
  });

  test("AI 분류 출력 컬럼명 64자는 자르지 않고 인라인 오류, 63자는 오류 없음", async ({
    authenticatedPage: page,
  }) => {
    await openNewStep(page, "AI 분류");

    await page.getByRole("button", { name: "컬럼 추가", exact: true }).click();
    const nameInput = page.getByPlaceholder("column_name").first();

    await nameInput.fill(nameOf("ai_", 64));
    // 입력값은 그대로 64자로 남고(조용히 자르지 않음) 오류가 보인다.
    await expect(nameInput).toHaveValue(nameOf("ai_", 64));
    await expect(page.getByText(MESSAGE)).toBeVisible();
    await expect(nameInput).toHaveAttribute("aria-invalid", "true");

    await nameInput.fill(nameOf("ai_", 63));
    await expect(page.getByText(MESSAGE)).toHaveCount(0);
  });

  test("API 호출 대상 컬럼 64자는 자르지 않고 인라인 오류, 63자는 오류 없음", async ({
    authenticatedPage: page,
  }) => {
    await openNewStep(page, "API 호출");
    await expect(page.getByText("기본 설정")).toBeVisible({ timeout: 10000 });

    await page.getByRole("button", { name: /매핑 추가/ }).click();
    const target = page.getByPlaceholder("target_col").first();

    await target.fill(nameOf("api_", 64));
    await expect(target).toHaveValue(nameOf("api_", 64));
    await expect(page.getByText(MESSAGE)).toBeVisible();
    await expect(target).toHaveAttribute("aria-invalid", "true");

    await target.fill(nameOf("api_", 63));
    await expect(page.getByText(MESSAGE)).toHaveCount(0);
  });
});
