import type { Locator, Page } from '@playwright/test';

import { expect } from './auth.fixture';

/**
 * 요청을 테스트가 풀어 줄 때까지 붙잡는 API 모킹 (WD-637).
 *
 * 왜: 응답 지연(delayMs)은 느린 CI 에서 「진행 중」 확인이 끝나기 전에 응답이 와 버릴 수 있다.
 * 테스트가 release() 를 부를 때까지 응답을 보내지 않아, 진행 중 상태 확인·중복 제출 시도를 시간에 기대지 않고 할 수 있다.
 * - requests: 도착한 요청 본문(원문) — 중복 요청 수 검증용. 붙잡힌 동안 들어온 중복 요청도 바로 쌓인다.
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
 * - form.requestSubmit(): 버튼 click 을 거치지 않는 제출(입력칸 Enter 와 같은 암묵적 제출) — 폼의 제출 잠금(useSubmitGuard 등)만 막을 수 있다.
 * 포커스를 aria-disabled 보다 먼저 확인한다 — 수정 전에는 여기서 「body 에 포커스」로 실패해 결함 자체가 드러난다.
 * pending: 진행 중 버튼(라벨이 「… 중...」으로 바뀌면 그 버튼).
 */
export async function submitAndRetryWhilePending(page: Page, submit: Locator, pending: Locator) {
  await submit.focus();
  await page.keyboard.press('Enter');
  await expect(pending).toBeFocused();
  await expect(pending).toHaveAttribute('aria-disabled', 'true');
  await page.keyboard.press('Enter');
  await pending.locator('xpath=ancestor::form').evaluate((f: HTMLFormElement) => f.requestSubmit());
}
