/**
 * 스마트 작업 새 폼 — 화면을 떠난 뒤 늦게 온 만들기 실패가 서버 사유를 토스트로 남긴다 (WD-1109 반려 3)
 *
 * 결함: 이탈 확정으로 화면이 언마운트되면 `mutate(...)` 로 넘긴 onError 는 리스너가 없어 실행되지 않아,
 * 늦게 온 실패의 토스트 자체가 사라졌다(보편 문구도 서버 사유도 안 뜬다).
 * 현 구조: `useCreateProactiveJob`/`useUpdateProactiveJob` 를 `mutateAsync` 로 불러 결과를 `try/catch` 로 받는다 —
 * 리스너 게이트는 `mutate(...)`/`mutateAsync(...)` 두 번째 인자로 넘긴 콜백에만 걸리고, 훅(`useMutation` 옵션) 수준의 `onError` 는 무조건 실행된다. promise 는 마운트와 무관하게 끝나 이탈 뒤에도 같은 처리가
 * 돈다(WD-1109 반려 3, WD-1153, WD-1156).
 *
 * 시나리오: 이름·프롬프트를 채우고 「만들기」 → POST 응답을 테스트가 붙잡아 둔 사이 사이드바 홈 링크로
 * 이탈 확인창에서 이탈을 확정해 화면이 실제로 언마운트된다. 그 뒤 응답을
 * 400 + { message: '이미 사용 중인 작업 이름입니다.' } 로 풀어 토스트에 서버 문구가 보이는지 단언한다 —
 * 이전에는 청크를 붙잡아 화면을 살려 둬야 통과했다(구조가 아니라 타이밍에 기댄 시험이었다).
 * 이동 없이 실패하는 경로(칸 오류로 붙는 applyDuplicateNameError)는 이 spec 대신 기존
 * duplicate-name.spec.ts · job-create-disabled-hint.spec.ts 가 회귀로 지킨다.
 */

import type { Page } from '@playwright/test';

import { createJob } from '../../factories/ai-insight.factory';
import { mockProactivePermissions, setupJobDetailMocks, setupNewJobMocks } from '../../fixtures/ai-insight.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

const SERVER_MSG = '이미 사용 중인 작업 이름입니다.';
const FALLBACK_MSG = '작업 생성에 실패했습니다.';
// 떠난 뒤 이름 중복 — 서버 문구 대신 보낸 이름을 넣은 문구 (WD-1153)
const SERVER_DUP_MSG = '이미 있는 스마트 작업 이름입니다. 다른 이름을 입력하세요.';
const DUP_TOAST = '「주간 보고」 작업을 만들지 못했습니다 — 같은 이름의 작업이 이미 있습니다.';

/** 같은 SPA 안에서 기록 항목을 하나 쌓고 라우터에 알린다 — 새로고침 없이 다른 화면으로 옮긴다 */
async function pushHistoryEntry(page: Page, path: string) {
  await page.evaluate((to) => {
    const idx = ((window.history.state as { idx?: number } | null)?.idx ?? 0) + 1;
    const state = { usr: null, key: `wd1153-${idx}`, idx };
    window.history.pushState(state, '', to);
    window.dispatchEvent(new PopStateEvent('popstate', { state }));
  }, path);
}

/** 새 작업 폼에 이름 「주간 보고」·프롬프트를 채우고 만들기를 눌러 요청이 서버에 닿을 때까지 기다린다 */
async function fillAndSubmit(page: Page, hold: Awaited<ReturnType<typeof holdCreateRequest>>) {
  await page.goto('/ai-insights/jobs/new');
  await page.locator('#job-name').fill('주간 보고');
  await page.locator('#job-prompt').fill('어제 결과를 요약하세요');
  await page.getByRole('button', { name: '만들기', exact: true }).click();
  await hold.requested;
}

/** 만들기를 보낸 뒤 사이드바 홈으로 이탈을 확정해 화면을 언마운트한다 */
async function createThenLeave(page: Page, hold: Awaited<ReturnType<typeof holdCreateRequest>>) {
  await fillAndSubmit(page, hold);
  await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: '이탈' }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.locator('#job-name')).toHaveCount(0);
}

/**
 * POST /api/v1/proactive/jobs 응답을 테스트가 풀어줄 때까지 붙잡는다 —
 * 요청 도착(requested)과 응답 해제(release)를 각각 약속으로 제어해, 이동 사이에 응답을 넣을 수 있다.
 */
async function holdCreateRequest(page: Page) {
  let markRequested: () => void = () => {};
  const requested = new Promise<void>((resolve) => {
    markRequested = resolve;
  });
  let doRelease: (r: { status: number; json: unknown }) => void = () => {};
  const released = new Promise<{ status: number; json: unknown }>((resolve) => {
    doRelease = resolve;
  });
  const payloads: { name: string }[] = [];
  await page.route(
    (url) => url.pathname === '/api/v1/proactive/jobs',
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      payloads.push(route.request().postDataJSON() as { name: string });
      markRequested();
      const r = await released;
      await route.fulfill({ status: r.status, contentType: 'application/json', body: JSON.stringify(r.json) });
    },
  );
  return {
    requested,
    payloads,
    release: (status: number, json: unknown) => doRelease({ status, json }),
  };
}

test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

test.describe('스마트 작업 새 폼 — 이탈 뒤 만들기 실패의 서버 사유 (WD-1109 반려 3)', () => {
  test('화면이 언마운트된 뒤 도착한 실패도 토스트에 서버 문구를 남긴다', async ({ authenticatedPage: page }) => {
    const hold = await holdCreateRequest(page);
    await setupNewJobMocks(page);

    await page.goto('/ai-insights/jobs/new');
    await page.locator('#job-name').fill('겹치는 작업');
    await page.locator('#job-prompt').fill('어제 결과를 요약하세요');
    await page.getByRole('button', { name: '만들기', exact: true }).click();
    // 요청이 서버에 도착한 것을 확인한 뒤에 이동한다 — 응답이 이동 전에 새지 않게 하려는 것이다
    await hold.requested;
    expect(hold.payloads[0]?.name).toBe('겹치는 작업');

    // 저장 클릭 직후 사이드바 홈 링크로 이동 — 미저장 확인창에서 이탈을 확정한다(새 폼이 실제로 언마운트된다)
    await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('alertdialog').getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL(/\/$/);
    // URL 만 바뀌었다고 언마운트가 아니다 — 지연 로딩 청크가 준비될 때까지 페이지가 남는다(WD-651).
    // 폼이 실제로 사라진 뒤에 응답을 풀어야, 언마운트 이후에 도착한 실패여도 토스트가 남음이 확정된다
    await expect(page.locator('#job-name')).toHaveCount(0);

    // 화면이 없어진 뒤에 도착한 실패 응답 — 서버 사유를 담은 400
    hold.release(400, { message: SERVER_MSG });

    // 서버가 말한 사유가 토스트로 남는다 (반려 3 이전: 화면이 언마운트돼 onError 가 통째로 사라졌다)
    await expect(page.getByText(SERVER_MSG)).toBeVisible();
    await expect(page.getByText(FALLBACK_MSG)).toHaveCount(0);
    // 다른 화면(홈)에 머문다 — 실패 응답이 사용자를 새 작업으로 끌고 가지 않는다
    expect(new URL(page.url()).pathname).toBe('/');
  });
});

test.describe('스마트 작업 새 폼 — 떠난 뒤 도착한 이름 중복 실패 (WD-1153)', () => {
  // 떠난 뒤 돌아간 곳이 같은 경로의 새 작업 화면이든 다른 작업 화면이든 같은 문구로 알린다
  const returnTargets = [
    { label: '새 작업으로 돌아온', path: '/ai-insights/jobs/new', ready: (page: Page) => expect(page.locator('#job-name')).toBeVisible() },
    { label: '다른 작업 화면에서', path: '/ai-insights/jobs/1', ready: (page: Page) => expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible() },
  ];
  for (const target of returnTargets) {
    test(`떠난 뒤 ${target.label} 도착한 이름 중복 실패를 서버 문구가 아니라 작업 이름 넣은 토스트로 알린다 (WD-1153)`, async ({
      authenticatedPage: page,
    }) => {
      const hold = await holdCreateRequest(page);
      await setupNewJobMocks(page);
      await setupJobDetailMocks(page, 1);
      await createThenLeave(page, hold);

      await pushHistoryEntry(page, target.path);
      await target.ready(page);

      hold.release(409, { status: 409, code: 'DUPLICATE_NAME', message: SERVER_DUP_MSG });

      await expect(page.getByText(DUP_TOAST)).toBeVisible();
      await expect(page.locator('#job-name-error')).toHaveCount(0);
      await expect(page.getByText(SERVER_DUP_MSG)).toHaveCount(0);
    });
  }
});

test.describe('스마트 작업 새 폼 — 만들기 성공 알림 (WD-1153)', () => {
  test('떠난 뒤 늦게 온 만들기 성공을 작업 이름 넣은 토스트로 알리고 경로는 그대로 둔다 (WD-1153)', async ({
    authenticatedPage: page,
  }) => {
    const hold = await holdCreateRequest(page);
    await setupNewJobMocks(page);
    await createThenLeave(page, hold);

    hold.release(201, createJob({ id: 77, name: '주간 보고' }));

    // 떠난 뒤에도 성공이 이름과 함께 남는다 (수정 전: 화면이 없어 mutate 의 onSuccess 가 실행되지 않아 토스트가 없었다)
    await expect(page.getByText('「주간 보고」 작업이 생성되었습니다.')).toBeVisible();
    // 이름 없는 보편 문구는 함께 뜨지 않는다 — 토스트는 한 번만 난다
    await expect(page.getByText('작업이 생성되었습니다.', { exact: true })).toHaveCount(0);
    // 떠난 곳(홈)에 그대로 머문다 — 새 작업 상세로 끌고 가지 않는다
    expect(new URL(page.url()).pathname).toBe('/');
  });

  test('머문 채 성공하면 「작업이 생성되었습니다.」 한 번만 뜨고 상세로 간다 (WD-1153)', async ({
    authenticatedPage: page,
  }) => {
    const hold = await holdCreateRequest(page);
    await setupNewJobMocks(page);
    await setupJobDetailMocks(page, 77);

    await fillAndSubmit(page, hold);
    hold.release(201, createJob({ id: 77, name: '주간 보고' }));

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/77$/);
    await expect(page.getByText('작업이 생성되었습니다.', { exact: true })).toHaveCount(1);
  });
});
