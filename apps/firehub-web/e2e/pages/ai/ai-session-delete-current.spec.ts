/**
 * AI 대화 목록에서 지금 열어 둔 대화를 지우면 새 대화로 바꾼다 — E2E (WD-972)
 *
 * 수정 전 결함: 사이드 패널·캔버스의 SessionSwitcher 는 삭제 뒤에도 지운 대화를 열어 둔 채였고(다음 글이
 * 지워진 대화로 갔다), 전체 화면 목록은 삭제 호출별 콜백이 겹친 삭제 중 마지막 것만 불려 현재 대화를
 * 지워도 새 대화로 바뀌지 않을 수 있었다.
 *
 * 새 세션은 첫 글을 보내면 채팅 요청(sessionId 없음) → init 이벤트 → POST /ai/sessions 로 만들어진다.
 * 그래서 "새 대화로 바뀜"은 메시지 영역이 비고, 다음 채팅 요청의 sessionId 가 비어 있고, POST 가 1회인 것으로 본다.
 */

import type { Page } from "@playwright/test";

import { createAISession } from "../../factories/ai-session.factory";
import { expect, test } from "../../fixtures/auth.fixture";
import { DUPLICATE_WINDOW_MS, holdRequests } from "../../fixtures/hold-api";

const SESSIONS = [
  createAISession({ id: 1, sessionId: "session-1", title: "첫째 대화" }),
  createAISession({ id: 2, sessionId: "session-2", title: "둘째 대화" }),
];

interface Mock {
  /** POST /ai/sessions 요청 body 들 */
  creates: unknown[];
  /** 채팅 요청 body.sessionId 들 */
  chats: (string | null)[];
  /** 서버 취소 요청(POST /ai/chat/:sessionId/cancel)의 sessionId 들 */
  cancels: string[];
}

interface Options {
  /** DELETE 응답 상태(기본 204) */
  deleteStatus?: number;
  /** true 면 채팅 요청에 응답하지 않고 붙잡아 스트리밍 중인 상태를 만든다 */
  hangChat?: boolean;
}

/** 목록·메시지·삭제·채팅 SSE 를 모킹하고 요청 기록을 돌려준다 */
async function mockAll(page: Page, opts: Options = {}): Promise<Mock> {
  let server = SESSIONS;
  const mock: Mock = {
    creates: [],
    chats: [],
    cancels: [],
  };
  await page.route(
    (url) => url.pathname === "/api/v1/ai/sessions",
    (route) => {
      if (route.request().method() === "POST") {
        const body = route.request().postDataJSON() as {
          sessionId: string;
          title: string;
        };
        mock.creates.push(body);
        return route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify(createAISession({ id: 900, ...body })),
        });
      }
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(server),
      });
    },
  );
  await page.route(
    (url) => /^\/api\/v1\/ai\/sessions\/[^/]+\/messages$/.test(url.pathname),
    (route) => {
      const sid = new URL(route.request().url()).pathname.split("/")[5];
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([
          {
            id: "m-" + sid,
            role: "assistant",
            content: sid + " 의 메시지",
            timestamp: "2026-11-23T00:00:00Z",
          },
        ]),
      });
    },
  );
  await page.route(
    (url) => /^\/api\/v1\/ai\/sessions\/\d+$/.test(url.pathname),
    async (route) => {
      if (route.request().method() !== "DELETE") return route.fallback();
      const id = new URL(route.request().url()).pathname.split("/").pop() ?? "";
      const status = opts.deleteStatus ?? 204;
      if (status >= 400) {
        return route.fulfill({
          status,
          contentType: "application/json",
          body: '{"message":"fail"}',
        });
      }
      server = server.filter((s) => String(s.id) !== id);
      return route.fulfill({ status: 204 });
    },
  );
  await page.route(
    (url) => /^\/api\/v1\/ai\/chat\/[^/]+\/cancel$/.test(url.pathname),
    (route) => {
      mock.cancels.push(
        decodeURIComponent(
          new URL(route.request().url()).pathname.split("/")[5],
        ),
      );
      return route.fulfill({ status: 204 });
    },
  );
  await page.route(
    (url) => url.pathname === "/api/v1/ai/chat",
    (route) => {
      if (route.request().method() !== "POST") return route.fallback();
      const body = route.request().postDataJSON() as {
        sessionId: string | null;
      };
      mock.chats.push(body.sessionId);
      if (opts.hangChat) return new Promise<void>(() => {});
      const sid = body.sessionId ?? "new-session-972";
      const sse = (d: Record<string, unknown>) =>
        `data: ${JSON.stringify(d)}\n\n`;
      return route.fulfill({
        status: 200,
        headers: {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-cache",
        },
        body:
          sse({ type: "init", sessionId: sid }) +
          sse({ type: "text", content: "답변" }) +
          sse({ type: "done", inputTokens: 1 }),
      });
    },
  );
  return mock;
}

const input = (page: Page) => page.getByPlaceholder("메시지를 입력하세요...");
const chip = (page: Page) => page.getByRole("button", { name: /AI 상태/ });
const SESSION_1_TEXT = "session-1 의 메시지";
const SESSION_2_TEXT = "session-2 의 메시지";

/** 사이드 패널을 열고 첫째 대화를 현재 대화로 연다 */
async function openPanelWithSession1(page: Page, opts: Options = {}) {
  const mock = await mockAll(page, opts);
  await page.goto("/", { waitUntil: "commit" });
  await chip(page).click();
  await input(page).waitFor({ state: "visible", timeout: 5_000 });
  await selectInSwitcher(page, "첫째 대화");
  await expect(page.getByText(SESSION_1_TEXT)).toBeVisible();
  return mock;
}

/** 대화 선택 펼침 목록(Popover) — ai-session-management.spec.ts 의 switcherList 와 같은 모양 */
const switcherList = (page: Page) =>
  page.getByRole("dialog", { name: "대화 선택" });

/**
 * 목록이 닫혀 있으면 연다. 직전 동작(선택=닫힘, 삭제 확인=열린 채)이 끝나 상태가 정해진 뒤에 부르므로
 * 기다려 추측하지 않고 지금 보이는지만 즉시 확인한다.
 */
async function ensureListOpen(page: Page) {
  if (!(await switcherList(page).isVisible())) {
    await page
      .getByRole("button", { name: /^(대화 선택|첫째 대화|둘째 대화)$/ })
      .first()
      .click();
  }
  await expect(switcherList(page)).toBeVisible();
}

/** 삭제 확인 창에서 「삭제」를 누르고 창이 사라질 때까지 기다린다 — 이후 목록 상태가 정해진다 */
async function confirmDelete(page: Page) {
  await page
    .getByRole("alertdialog")
    .getByRole("button", { name: "삭제" })
    .click();
  await expect(page.getByRole("alertdialog")).toHaveCount(0);
}

async function selectInSwitcher(page: Page, title: string) {
  await ensureListOpen(page);
  await switcherList(page)
    .getByRole("button", { name: title, exact: true })
    .click();
  // 닫히는 애니메이션이 끝나 목록이 사라질 때까지 기다린다 — 다음 조작이 닫히는 중의 목록을 잡지 않게
  await expect(switcherList(page)).toHaveCount(0);
}

/** 목록을 열어 그 대화의 삭제를 확인한다 */
async function deleteInSwitcher(page: Page, title: string) {
  await ensureListOpen(page);
  await switcherList(page)
    .getByRole("button", { name: `${title} 삭제`, exact: true })
    .click();
  await confirmDelete(page);
}

/** 그 id 의 DELETE 응답이 브라우저에 닿는다 */
const deleteResponse = (page: Page, id: string) =>
  page.waitForResponse(
    (r) =>
      r.request().method() === "DELETE" &&
      r.url().endsWith(`/ai/sessions/${id}`),
  );
/** 응답 처리(then)가 돌 틈 — 음성 단언이 응답보다 앞서 통과하지 않게. hold-api 의 중복 요청 창과 같은 값을 쓴다 */
const flush = (page: Page) =>
  page.evaluate(
    (ms) => new Promise((r) => setTimeout(r, ms)),
    DUPLICATE_WINDOW_MS,
  );

/** 열린 모달 목록을 닫는다 — 열린 채면 바깥(입력창·칩)이 막힌다. 삭제 확인이 끝난 뒤라 상태는 정해져 있다 */
async function closeListIfOpen(page: Page) {
  if (await switcherList(page).isVisible()) await page.keyboard.press("Escape");
  await expect(switcherList(page)).toHaveCount(0);
}

async function send(page: Page, text: string) {
  await closeListIfOpen(page);
  await input(page).fill(text);
  await input(page).press("Enter");
}

test.describe("현재 대화 삭제 → 새 대화 (WD-972)", () => {
  test("현재 대화를 지우면 새 대화로 바뀌고 다음 글은 새 세션으로 간다", async ({
    authenticatedPage: page,
  }) => {
    const mock = await openPanelWithSession1(page);
    await deleteInSwitcher(page, "첫째 대화");
    await expect(page.getByText(SESSION_1_TEXT)).toHaveCount(0);

    await send(page, "새 글");
    await expect.poll(() => mock.chats.length).toBe(1);
    expect(mock.chats[0]).not.toBe("session-1");
    await expect.poll(() => mock.creates.length).toBe(1);
  });

  test("다른 대화를 지우면 현재 대화는 그대로다", async ({
    authenticatedPage: page,
  }) => {
    const mock = await openPanelWithSession1(page);
    await deleteInSwitcher(page, "둘째 대화");
    await expect(page.getByText(SESSION_1_TEXT)).toBeVisible();

    await send(page, "이어서");
    await expect.poll(() => mock.chats).toEqual(["session-1"]);
    expect(mock.creates).toHaveLength(0);
  });

  test("현재 대화 삭제가 실패하면 그대로다", async ({
    authenticatedPage: page,
  }) => {
    const mock = await openPanelWithSession1(page, { deleteStatus: 500 });
    const responded = deleteResponse(page, "1");
    await deleteInSwitcher(page, "첫째 대화");
    await responded;
    await flush(page);
    await expect(page.getByText(SESSION_1_TEXT)).toBeVisible();

    await send(page, "이어서");
    await expect.poll(() => mock.chats).toEqual(["session-1"]);
    expect(mock.creates).toHaveLength(0);
  });

  test("응답 전 다른 대화로 옮겼으면 그대로다", async ({
    authenticatedPage: page,
  }) => {
    const mock = await openPanelWithSession1(page);
    const hold1 = await holdRequests(page, "/api/v1/ai/sessions/1", "DELETE");
    await deleteInSwitcher(page, "첫째 대화");
    await selectInSwitcher(page, "둘째 대화");
    await expect(page.getByText(SESSION_2_TEXT)).toBeVisible();

    const responded = deleteResponse(page, "1");
    hold1.release();
    await responded;
    await flush(page);
    // 응답이 처리된 뒤에도 둘째 대화가 그대로여야 한다
    await expect(page.getByText(SESSION_2_TEXT)).toBeVisible();
    await send(page, "이어서");
    await expect.poll(() => mock.chats).toEqual(["session-2"]);
  });

  test("겹친 삭제에서도 현재 대화를 지우면 새 대화로 바뀐다", async ({
    authenticatedPage: page,
  }) => {
    const mock = await openPanelWithSession1(page);
    const hold1 = await holdRequests(page, "/api/v1/ai/sessions/1", "DELETE");
    const hold2 = await holdRequests(page, "/api/v1/ai/sessions/2", "DELETE");
    await deleteInSwitcher(page, "첫째 대화");
    await deleteInSwitcher(page, "둘째 대화");
    hold1.release();
    await expect(page.getByText(SESSION_1_TEXT)).toHaveCount(0);
    hold2.release();

    await send(page, "새 글");
    await expect.poll(() => mock.chats.length).toBe(1);
    expect(mock.chats[0]).not.toBe("session-1");
    await expect.poll(() => mock.creates.length).toBe(1);
  });

  test("전체 화면 목록: 겹친 삭제에서도 현재 대화를 지우면 새 대화로 바뀐다", async ({
    authenticatedPage: page,
  }) => {
    const mock = await mockAll(page);
    const hold1 = await holdRequests(page, "/api/v1/ai/sessions/1", "DELETE");
    const hold2 = await holdRequests(page, "/api/v1/ai/sessions/2", "DELETE");
    await page.goto("/", { waitUntil: "commit" });
    await chip(page).click();
    await input(page).waitFor({ state: "visible", timeout: 5_000 });
    await chip(page).click();
    const list = page.getByRole("list", { name: "대화 이력" });
    await list.waitFor({ state: "visible", timeout: 5_000 });
    await list.getByRole("button", { name: "첫째 대화", exact: true }).click();
    await expect(page.getByText(SESSION_1_TEXT)).toBeVisible();

    await list.getByRole("button", { name: "첫째 대화 삭제" }).click();
    await confirmDelete(page);
    await list.getByRole("button", { name: "둘째 대화 삭제" }).click();
    await confirmDelete(page);
    hold1.release();
    await expect(page.getByText(SESSION_1_TEXT)).toHaveCount(0);
    hold2.release();

    await send(page, "새 글");
    await expect.poll(() => mock.chats.length).toBe(1);
    expect(mock.chats[0]).not.toBe("session-1");
    await expect.poll(() => mock.creates.length).toBe(1);
  });

  test("삭제 응답 전에 사이드 패널에서 전체 화면으로 바꿔 다른 대화를 열면 그 대화는 그대로다", async ({
    authenticatedPage: page,
  }) => {
    // 패널의 SessionSwitcher 는 전체 화면 전환으로 사라진다 — 응답 때 컴포넌트 상태가 아닌 최신 현재 대화로 판단해야 한다
    const mock = await openPanelWithSession1(page);
    const hold1 = await holdRequests(page, "/api/v1/ai/sessions/1", "DELETE");
    await deleteInSwitcher(page, "첫째 대화");
    await closeListIfOpen(page);
    await chip(page).click();
    const list = page.getByRole("list", { name: "대화 이력" });
    await list.waitFor({ state: "visible", timeout: 5_000 });
    await list.getByRole("button", { name: "둘째 대화", exact: true }).click();
    await expect(page.getByText(SESSION_2_TEXT)).toBeVisible();

    const responded = deleteResponse(page, "1");
    hold1.release();
    await responded;
    await flush(page);
    await expect(page.getByText(SESSION_2_TEXT)).toBeVisible();
    await send(page, "이어서");
    await expect.poll(() => mock.chats).toEqual(["session-2"]);
    expect(mock.creates).toHaveLength(0);
  });

  test("스트리밍 중인 현재 대화를 지우면 서버 실행도 취소하고 새 대화로 바뀐다", async ({
    authenticatedPage: page,
  }) => {
    const mock = await openPanelWithSession1(page, { hangChat: true });
    await send(page, "긴 글");
    await expect.poll(() => mock.chats).toEqual(["session-1"]);

    await deleteInSwitcher(page, "첫째 대화");
    await expect.poll(() => mock.cancels).toEqual(["session-1"]);
    await expect(page.getByText(SESSION_1_TEXT)).toHaveCount(0);
    await expect(page.getByText("긴 글")).toHaveCount(0);
  });
});
