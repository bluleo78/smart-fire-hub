/**
 * 스마트 작업 편집 — 저장 중 이탈한 뒤 늦게 온 실패·충돌 알림이 남는다 E2E (WD-1156)
 *
 * 결함: 편집 분기가 아직 `updateMutation.mutateAsync(payload, { onSuccess, onError })` 콜백이라,
 * 저장 중 화면을 떠나 언마운트되면 리스너가 없어 늦게 온 성공·실패 알림(토스트·충돌 창·칸 오류)이
 * 통째로 사라진다. 만들기 분기에는 WD-1109·WD-1153 에 만든 「mutateAsync promise 로 결과를 받는」
 * 구조가 이미 적용돼 있고, 이 시험은 그 구조가 편집에도 적용됐는지 확인하는 기준이다(WD-1156).
 *
 * 시나리오: 작업 상세에서 이름·프롬프트를 고쳐(isDirty) 저장을 누르고 PUT 응답을 테스트가 붙잡아 둔 사이,
 * 사이드바 홈 링크로 이탈을 확정해 화면이 실제로 언마운트된다(이후 남은 이동으로 작업 목록으로 간다).
 * 그 뒤 응답을 500·409 로 풀어 실패 토스트가 남는지, 409 충돌 창이 새 화면에 뜨지 않는지 단언한다 —
 * 이동 없이 성공·실패하는 경로는 기존 job-edit-conflict·job-leave-nav-guard 시험이 회귀로 지킨다.
 */

import type { Page } from '@playwright/test';

import { mockProactivePermissions, setupJobDetailMocks } from '../../fixtures/ai-insight.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

// 500 응답 본문의 서버 사유 — 이탈 뒤에도 토스트에 그대로 남아야 한다
const SERVER_MSG = '서버 오류';
// 서버 사유가 있으면 함께 뜨지 않는 보편 실패 문구(handleApiError 의 fallback)
const FALLBACK_MSG = '작업 저장에 실패했습니다.';
// 409 EDIT_CONFLICT 응답 — 머물렀다면 충돌 창으로 갔을 코드·문구
const CONFLICT_MSG = '이 작업은 편집을 시작한 뒤 다른 곳에서 변경되었습니다. 다시 불러온 뒤 저장하세요.';
// 편집으로 고쳐 넣을 이름·프롬프트 — payload 단언에도 그대로 쓴다
const EDITED_NAME = '고친 이름';
const EDITED_PROMPT = '고친 프롬프트입니다';

/** 같은 SPA 안에서 기록 항목을 하나 쌓고 라우터에 알린다 — 새로고침 없이 다른 화면으로 옮긴다 */
async function pushHistoryEntry(page: Page, path: string) {
  await page.evaluate((to) => {
    const idx = ((window.history.state as { idx?: number } | null)?.idx ?? 0) + 1;
    const state = { usr: null, key: `wd1156-${idx}`, idx };
    window.history.pushState(state, '', to);
    window.dispatchEvent(new PopStateEvent('popstate', { state }));
  }, path);
}

/**
 * PUT /api/v1/proactive/jobs/1 응답을 테스트가 풀어줄 때까지 붙잡는다 —
 * 요청 도착(requested)과 응답 해제(release)를 각각 약속으로 제어해, 이동(언마운트) 사이에 응답을 넣을 수 있다.
 * hold-api 의 holdRequests 는 붙잡힌 요청 본문을 못 보므로(postDataJSON 미수집), 본문은 여기서 payloads 로 모은다(WD-1156).
 * 등록은 나중 등록이 이기는 Playwright 규칙 때문에 setupJobDetailMocks 같은 setup 모킹을 먼저 깔고 이 헬퍼를 마지막에 부른다.
 */
async function holdUpdateRequest(page: Page) {
  let markRequested: () => void = () => {};
  const requested = new Promise<void>((resolve) => {
    markRequested = resolve;
  });
  let doRelease: (r: { status: number; json: unknown }) => void = () => {};
  const released = new Promise<{ status: number; json: unknown }>((resolve) => {
    doRelease = resolve;
  });
  const payloads: { name?: string; prompt?: string }[] = [];
  await page.route(
    (url) => url.pathname === '/api/v1/proactive/jobs/1',
    async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      payloads.push(route.request().postDataJSON() as { name?: string; prompt?: string });
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

/** 작업 상세에서 이름·프롬프트를 고쳐 저장을 누르고 PUT 요청이 서버에 닿을 때까지 기다린다 — 응답이 이동 전에 새지 않게 하려는 것이다 */
async function editAndSave(page: Page, hold: Awaited<ReturnType<typeof holdUpdateRequest>>) {
  await page.goto('/ai-insights/jobs/1');
  await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '편집', exact: true }).click();
  await expect(page.locator('#job-name')).toBeVisible();
  await page.locator('#job-name').fill(EDITED_NAME);
  await page.locator('#job-prompt').fill(EDITED_PROMPT);
  await expect(page.getByText('미저장 변경사항')).toBeVisible();
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await hold.requested;
}

/** 저장 클릭 뒤 사이드바 홈 링크로 이탈을 확정해 화면을 언마운트한다(가드 확인창에서 「이탈」을 고른다) */
async function saveThenLeave(page: Page) {
  await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
  await expect(page.getByRole('alertdialog')).toBeVisible();
  await page.getByRole('alertdialog').getByRole('button', { name: '이탈' }).click();
  await expect(page).toHaveURL(/\/$/);
  // URL 만 바뀐다고 언마운트가 아니다 — 지연 로딩 청크가 준비될 때까지 화면이 남는다(WD-651).
  // 폼이 실제로 사라진 뒤에만 응답을 풀어야, 콜백에 의존하는 알림이 없음을 시험이 확정으로 볼 수 있다
  await expect(page.locator('#job-name')).toHaveCount(0);
}

test.beforeEach(async ({ authenticatedPage: page }) => {
  // 편집·저장 버튼은 쓰기 권한이 있을 때만 보인다(WD-133)
  await mockProactivePermissions(page);
});

test.describe('스마트 작업 편집 — 저장 중 이탈한 뒤 늦게 온 알림 (WD-1156)', () => {
  test('편집 저장 중 이탈한 뒤 늦게 온 500 실패가 토스트로 남는다', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);
    const hold = await holdUpdateRequest(page);
    await editAndSave(page, hold);
    await saveThenLeave(page);

    // 상세에서 목록으로 남은 이동 — 가드는 언마운트로 풀려 있어 pushState + popstate 로 그대로 간다
    await pushHistoryEntry(page, '/ai-insights/jobs');
    await expect(page).toHaveURL(/\/ai-insights\/jobs$/);

    // 화면이 없어진 뒤에 도착한 실패 응답 — 서버 사유를 담은 500
    hold.release(500, { message: SERVER_MSG });

    // 이탈 뒤에도 서버가 말한 사유가 토스트로 남는다 (수정 전: 언마운트라 콜백 onError 가 불리지 않아 어떤 토스트도 없었다)
    await expect(page.getByText(SERVER_MSG)).toBeVisible();
    // 서버 사유가 있으면 보편 문구는 함께 뜨지 않는다 — 실패 알림은 하나다
    await expect(page.getByText(FALLBACK_MSG)).toHaveCount(0);
    // 실패 응답이 사용자를 새 화면에서 다른 곳으로 끌고 가지 않는다
    expect(new URL(page.url()).pathname).toBe('/ai-insights/jobs');
  });

  test('편집 저장 중 이탈한 뒤 늦게 온 409 충돌이 충돌 창 대신 토스트로 남는다', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);
    const hold = await holdUpdateRequest(page);
    await editAndSave(page, hold);
    await saveThenLeave(page);

    await pushHistoryEntry(page, '/ai-insights/jobs');
    await expect(page).toHaveURL(/\/ai-insights\/jobs$/);

    // 머물렀다면 EditConflictDialog 로 갔을 409 — 이탈이 확인된 뒤에야 도착한다
    hold.release(409, { status: 409, code: 'EDIT_CONFLICT', message: CONFLICT_MSG });

    // 이탈했으므로(movedAway) 실패 토스트부터 남는다 — 500 케이스와 같은 순서로 긍정 단언이 먼저다
    await expect(page.getByText(CONFLICT_MSG)).toBeVisible();
    // 토스트가 뜬 상태에서 충돌 창이 없다 — 응답 처리 전에 공허하게 통과하지 않도록 뒤로 미룬다
    await expect(page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' })).toHaveCount(0);
    expect(new URL(page.url()).pathname).toBe('/ai-insights/jobs');
  });

  test('편집 저장 요청 payload 가 화면 값을 그대로 보낸다', async ({ authenticatedPage: page }) => {
    await setupJobDetailMocks(page, 1);
    const hold = await holdUpdateRequest(page);
    await editAndSave(page, hold);

    // 붙잡힌 요청의 본문 — 화면에 친 값을 그대로 보낸다(web CLAUDE.md payload 검증 규칙)
    expect(hold.payloads[0].name).toBe(EDITED_NAME);
    expect(hold.payloads[0].prompt).toBe(EDITED_PROMPT);

    // 500·409 케이스와 같은 순서 — 화면이 실제로 언마운트된 뒤(#job-name count 0)에 응답을 푼다
    await saveThenLeave(page);

    // 이탈한 사이에 도착한 200 성공 — 이슈가 명명한 「늦게 온 성공 알림」이 되는 경로
    hold.release(200, { updatedAt: '2026-11-26T00:00:00.000000' });

    // 화면이 없어진 뒤에 도착한 성공도 토스트로 남는다(ProactiveJobDetailPage 성공 분기의 문구)
    await expect(page.getByText('작업이 저장되었습니다.')).toBeVisible();

    // 같은 작업 상세로 돌아왔을 때도 읽기 모드다 — 늦게 온 성공이 편집 폼을 되살리지 않는다
    await pushHistoryEntry(page, '/ai-insights/jobs/1');
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();
  });
});
