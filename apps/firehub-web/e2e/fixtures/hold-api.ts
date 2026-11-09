import type { Locator, Page } from '@playwright/test';

import { expect } from './auth.fixture';

/**
 * 요청을 테스트가 풀어 줄 때까지 붙잡는 API 모킹 (WD-637).
 *
 * 왜: 응답 지연(delayMs)은 느린 CI 에서 「진행 중」 확인이 끝나기 전에 응답이 와 버릴 수 있다.
 * 테스트가 release() 를 부를 때까지 응답을 보내지 않아, 진행 중 상태 확인·중복 제출 시도를 시간에 기대지 않고 할 수 있다.
 * - requests: 도착한 요청 본문(원문). 붙잡힌 동안 들어온 중복 요청도 바로 쌓인다.
 * - release(): 붙잡힌 요청(이후 요청 포함)에 모두 같은 응답을 보낸다.
 */
export async function holdApi(page: Page, method: string, path: string, status: number, body: unknown) {
  let release: () => void = () => {};
  const released = new Promise<void>((resolve) => {
    release = resolve;
  });
  const requests: (string | null)[] = [];
  await page.route(
    (url) => url.pathname === path,
    async (route) => {
      if (route.request().method() !== method) return route.fallback();
      requests.push(route.request().postData());
      await released;
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    },
  );
  return { requests, release };
}

/**
 * 제출 버튼에 포커스를 두고 Enter 로 제출한 뒤, 진행 중(응답 붙잡힘) 상태를 확인하고 두 갈래로 다시 제출해 본다 (WD-637).
 * - 두 번째 Enter: 포커스된 잠긴 버튼의 click — PendingSubmitButton 이 막는다.
 * - form.requestSubmit(): 버튼 click 을 거치지 않는 제출(입력칸 Enter 와 같은 암묵적 제출) — 폼의 제출 잠금(useGuardedSubmit 등)만 막을 수 있다.
 * 포커스를 aria-disabled 보다 먼저 확인한다 — 수정 전에는 여기서 「body 에 포커스」로 실패해 결함 자체가 드러난다.
 * pending: 진행 중 버튼(라벨이 「… 중...」으로 바뀌면 그 버튼).
 *
 * 요청 1회 판정(WD-683): 응답을 아직 붙잡은 채로 센다 — 중복 요청은 붙잡힌 동안 반드시 requests 에 쌓이므로,
 * 응답을 푼 뒤 세다가 늦게 온 중복 요청이 바로 응답돼 놓치는 일이 없다. 첫 요청이 닿을 때까지 기다린 뒤(느린 CI),
 * requestSubmit 의 비동기 검증을 거쳐 늦게 오는 중복 요청까지 짧은 창만큼 더 기다리고 1회인지 본다(expectSingleHeldRequest).
 */
export async function submitAndRetryWhilePending(
  page: Page,
  submit: Locator,
  pending: Locator,
  held: { requests: unknown[] },
) {
  await submit.focus();
  await page.keyboard.press('Enter');
  await expect(pending).toBeFocused();
  await expect(pending).toHaveAttribute('aria-disabled', 'true');
  await page.keyboard.press('Enter');
  await pending.locator('xpath=ancestor::form').evaluate((f: HTMLFormElement) => f.requestSubmit());
  await expectSingleHeldRequest(() => held.requests.length);
}

/**
 * 붙잡힌 요청이 생긴 뒤 늦게 오는 중복 요청을 기다려 보는 창(ms). 한계(WD-688): 이 창 안에 온 중복만 잡고, 응답을 푼 뒤 오는 중복은 놓친다.
 * 200ms 로 충분하다 — 잠금이 없으면 중복은 재제출 직후 비동기 검증(마이크로태스크)만 거쳐 수 ms 안에 나간다.
 */
export const DUPLICATE_WINDOW_MS = 200;

/**
 * 응답을 붙잡은 채 요청이 정확히 1회인지 본다(WD-688) — 첫 요청이 닿을 때까지 기다리고(느린 CI), 중복이 나갈 틈(DUPLICATE_WINDOW_MS)을 준 뒤 센다.
 * count: 지금까지 붙잡힌 요청 수(holdApi 는 `() => held.requests.length`, holdRequests 는 `hold.count`).
 */
export async function expectSingleHeldRequest(count: () => number, message = '응답을 붙잡은 동안 요청은 1회여야 한다') {
  await expect.poll(count).toBeGreaterThanOrEqual(1);
  await new Promise((resolve) => setTimeout(resolve, DUPLICATE_WINDOW_MS));
  expect(count(), message).toBe(1);
}

// ── 원래 모킹으로 넘기는 붙잡기·편집 충돌 창 잠금 확인 (WD-678) ──
// holdApi 는 고정 응답을 직접 보내고, holdRequests 는 풀 때 route.fallback() 으로 이미 등록된 상태 서버 모킹이 응답하게 한다
// (나중에 등록한 route 가 먼저 받으므로 상태 서버 모킹 뒤에 부른다).

/**
 * pathname·method 가 맞는 요청을 release() 전까지 붙잡는다.
 * @returns release — 붙잡은(그리고 이후의) 요청을 원래 모킹으로 넘긴다 / count — 지금까지 붙잡힌 요청 수
 */
export async function holdRequests(page: Page, pathname: string, method: string) {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let count = 0;
  await page.route(
    (url) => url.pathname === pathname,
    async (route) => {
      if (route.request().method() !== method) return route.fallback();
      count += 1;
      await gate;
      return route.fallback();
    },
  );
  return { release, count: () => count };
}

/**
 * 편집 충돌 창이 요청 진행 중 잠겨 있는지 본다 — 세 버튼 모두 aria-disabled="true",
 * 누른 버튼은 실제 disabled 속성이 없고(포커스를 빼앗지 않음) 포커스를 그대로 가진다.
 */
export async function expectLockedInFlight(dialog: Locator, pressedButton: Locator) {
  for (const name of ['계속 편집', '다시 불러오기', '내 변경으로 덮어쓰기']) {
    await expect(dialog.getByRole('button', { name })).toHaveAttribute('aria-disabled', 'true');
  }
  // Playwright 의 toBeDisabled 는 aria-disabled 도 disabled 로 보므로 실제 disabled 속성이 없는지 본다
  await expect(pressedButton).not.toHaveAttribute('disabled');
  await expect(pressedButton).toBeFocused();
}
