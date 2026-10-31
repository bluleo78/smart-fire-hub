/**
 * AI 전체 화면 — Escape 로 사이드 패널 복귀 E2E (WD-61)
 *
 * 수정 전 결함: 전체 화면 모드에서 Escape 를 눌러도 아무 반응이 없었다.
 * 실제 키 입력(page.keyboard)으로 검증한다:
 * - 입력창 포커스 / 본문 포커스 모두 Escape → 사이드 패널(세션 사이드바 사라짐, ai-mode=side 저장)
 * - 사이드 패널에서 Escape → 변화 없음
 * - 열린 확인 다이얼로그가 있으면 Escape 는 다이얼로그만 닫고 전체 화면 유지
 */

import type { Page } from "@playwright/test";

import { mockApi } from "../../fixtures/api-mock";
import { expect, test } from "../../fixtures/auth.fixture";

const chipLocator = (page: Page) =>
  page.getByRole("button", { name: /AI 상태/ });
const chatInput = (page: Page) =>
  page.getByPlaceholder("메시지를 입력하세요...");
/**
 * 전체 화면에서만 렌더되는 세션 사이드바의 "대화 이력" 목록 — 전체 화면 여부 판별에 쓴다.
 * ("새 대화" 버튼은 사이드 패널의 SessionSwitcher 에도 있어 판별용으로 쓸 수 없다)
 */
const fullscreenMarker = (page: Page) =>
  page.getByRole("list", { name: "대화 이력" });

const MOCK_SESSIONS = [
  {
    id: 61,
    sessionId: "session-wd61",
    title: "WD-61 테스트 대화",
    createdAt: "2026-10-18T00:00:00Z",
    updatedAt: "2026-10-18T01:00:00Z",
  },
];

/** 사이드 패널을 연다 (chip 1회 클릭) */
async function openSidePanel(page: Page) {
  await mockApi(page, "GET", "/api/v1/ai/sessions", MOCK_SESSIONS);
  await page.goto("/", { waitUntil: "commit" });
  await chipLocator(page).click();
  await chatInput(page).waitFor({ state: "visible", timeout: 5_000 });
}

/** 사이드 패널 → 전체 화면 (chip 2회 클릭) */
async function openFullscreen(page: Page) {
  await openSidePanel(page);
  await chipLocator(page).click();
  await fullscreenMarker(page).waitFor({ state: "visible", timeout: 5_000 });
}

const storedMode = (page: Page) =>
  page.evaluate(() => localStorage.getItem("ai-mode"));

test.describe("AI 전체 화면 — Escape 로 사이드 패널 복귀 (WD-61)", () => {
  test("입력창에 포커스가 있을 때 Escape 로 사이드 패널로 돌아간다", async ({
    authenticatedPage: page,
  }) => {
    await openFullscreen(page);
    await chatInput(page).focus();

    await page.keyboard.press("Escape");

    await expect(fullscreenMarker(page)).toBeHidden();
    await expect(chatInput(page)).toBeVisible();
    await expect(
      page.getByRole("button", { name: "사이드 패널" }),
    ).toBeVisible();
    expect(await storedMode(page)).toBe("side");
  });

  test("포커스가 본문에 있을 때도 Escape 로 사이드 패널로 돌아간다", async ({
    authenticatedPage: page,
  }) => {
    await openFullscreen(page);
    await page.evaluate(() =>
      (document.activeElement as HTMLElement | null)?.blur(),
    );

    await page.keyboard.press("Escape");

    await expect(fullscreenMarker(page)).toBeHidden();
    await expect(chatInput(page)).toBeVisible();
    expect(await storedMode(page)).toBe("side");
  });

  test("사이드 패널에서 Escape 는 아무 동작도 하지 않는다", async ({
    authenticatedPage: page,
  }) => {
    await openSidePanel(page);
    await chatInput(page).focus();

    await page.keyboard.press("Escape");

    // 패널은 열린 채, 모드도 side 그대로
    await expect(chatInput(page)).toBeVisible();
    await expect(fullscreenMarker(page)).toBeHidden();
    expect(await storedMode(page)).toBe("side");
  });

  test("확인 다이얼로그가 열려 있으면 Escape 는 다이얼로그만 닫고 전체 화면을 유지한다", async ({
    authenticatedPage: page,
  }) => {
    await openFullscreen(page);
    await page
      .getByRole("button", { name: "WD-61 테스트 대화 삭제" })
      .click({ force: true });
    await expect(page.getByRole("alertdialog")).toBeVisible();

    await page.keyboard.press("Escape");

    await expect(page.getByRole("alertdialog")).toBeHidden();
    await expect(fullscreenMarker(page)).toBeVisible();
    expect(await storedMode(page)).toBe("fullscreen");

    // 다이얼로그가 닫힌 뒤 다시 누르는 Escape 는 사이드 패널로 돌아간다
    await page.keyboard.press("Escape");
    await expect(fullscreenMarker(page)).toBeHidden();
    expect(await storedMode(page)).toBe("side");
  });

  test("호버로 연 AI 상태 칩 드롭다운은 Escape 로 드롭다운만 닫히고 전체 화면을 유지한다", async ({
    authenticatedPage: page,
  }) => {
    // 칩 호버 3초 타이머를 실제로 기다리지 않도록 가짜 시계를 쓴다 (탐색 전에 설치해야 앱 타이머가 가짜가 된다)
    await page.clock.install();
    await openFullscreen(page);
    // 칩 위에 3초 머무르면 드롭다운이 열린다 — 포커스는 드롭다운 밖(본문)에 둔다
    await page.evaluate(() =>
      (document.activeElement as HTMLElement | null)?.blur(),
    );
    await chipLocator(page).hover();
    await page.clock.runFor(3_000);
    const chipMenu = page.getByRole("dialog", { name: "AI 상태 및 제어" });
    await expect(chipMenu).toBeVisible();

    await page.keyboard.press("Escape");

    await expect(chipMenu).toBeHidden();
    await expect(fullscreenMarker(page)).toBeVisible();
    expect(await storedMode(page)).toBe("fullscreen");
  });

  test("스트리밍 중 Escape 는 응답을 중단하지 않고 사이드 패널로만 돌아간다", async ({
    authenticatedPage: page,
  }) => {
    // 채팅 응답을 붙잡아 두어 스트리밍 상태를 유지한다 — 테스트 끝에 풀어 준다
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => (release = resolve));
    await page.route(
      (url) => url.pathname === "/api/v1/ai/chat",
      async (route) => {
        await held;
        await route
          .fulfill({
            status: 200,
            headers: { "Content-Type": "text/event-stream" },
            body: 'data: {"type":"done","inputTokens":1}\n\n',
          })
          .catch(() => {});
      },
    );
    let cancelCalled = false;
    await page.route(/\/api\/v1\/ai\/chat\/.+\/cancel$/, (route) => {
      cancelCalled = true;
      return route.fulfill({ status: 204, body: "" });
    });

    await openFullscreen(page);
    try {
      await chatInput(page).fill("긴 질문");
      await chatInput(page).press("Enter");
      const stopButton = page.getByRole("button", { name: "응답 중단" });
      await expect(stopButton).toBeVisible();

      await page.keyboard.press("Escape");

      await expect(fullscreenMarker(page)).toBeHidden();
      expect(await storedMode(page)).toBe("side");
      // 응답은 계속 진행 중 — 중단 버튼이 그대로이고 취소 API 도 호출되지 않는다
      await expect(stopButton).toBeVisible();
      expect(cancelCalled).toBe(false);
    } finally {
      // 단언이 실패해도 붙잡아 둔 응답을 풀어 라우트 핸들러가 남지 않게 한다
      release();
    }
  });
});
