/**
 * 스마트 작업 상세 — 저장 중 탭 전환은 떠남이 아니다 (WD-1109 반려 1)
 *
 * 반려 1: 이동 판정이 `?tab=` 쿼리·해시를 「떠남」으로 세어, 저장 클릭 뒤 실행 이력 탭으로 옮기면 성공한 저장이
 * 이탈로 읽혀 편집 모드·미저장 표시가 남고 다음 저장이 자기 저장과 409 로 부딪혔다. 폼은 페이지가 들고 있어
 * 탭 전환은 떠남이 아니다 — 위치 축은 경로만 비교한다.
 *
 * 시나리오: GET 작업(정상) → 편집 모드 → 프롬프트 수정(dirty) → PUT 응답을 붙잡아 둔 채 저장 클릭.
 * 탭 이동(?tab 만 바뀐다)이 확인된 뒤 응답을 풀어, 개요 탭으로 돌아왔을 때 읽기 모드로 돌아왔는지 단언한다.
 * 응답 시각을 고정 지연에 맡기지 않는 것은 두 이동의 순서가 흔들리면 시험이 아무것도 보지 않고 통과하기 때문이다.
 */

import type { Page, Route } from '@playwright/test';

import { createJob, createTemplates } from '../../factories/ai-insight.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * GET 은 정상 작업을 돌려주고 PUT 응답은 테스트가 풀어줄 때까지 붙잡는 가짜 작업 서버 —
 * 저장 요청과 응답 사이에 탭 이동을 정확히 끼워 넣을 수 있다(WD-1109 반려 1).
 */
async function mockSlowSave(page: Page) {
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/anomaly-events', []);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', createTemplates());
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  let releaseSave: () => void = () => {};
  const saveHeld = new Promise<void>((resolve) => {
    releaseSave = resolve;
  });
  await page.route(
    (url) => url.pathname === '/api/v1/proactive/jobs/1',
    async (route: Route) => {
      const method = route.request().method();
      if (method === 'PUT') {
        // 탭 이동이 확인된 뒤 테스트가 풀어주기까지 응답을 붙잡는다
        await saveHeld;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ updatedAt: '2026-11-25T00:00:01.000000' }),
        });
      }
      if (method !== 'GET') return route.fallback();
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createJob({ id: 1, name: '이동 가드 작업' })),
      });
    },
  );
  return { releaseSave };
}

const saveButton = (page: Page) => page.getByRole('button', { name: '저장', exact: true });
const cancelButton = (page: Page) => page.getByRole('button', { name: '취소', exact: true });
const unsavedIndicator = (page: Page) => page.getByText('미저장 변경사항');

test.describe('스마트 작업 상세 — 저장 중 탭 전환은 떠남이 아니다 (WD-1109 반려 1)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    // 편집 버튼은 쓰기 권한이 있을 때만 보인다(WD-133)
    await mockProactivePermissions(page);
  });

  test('지연된 저장 응답이 실행 이력 탭 이동 중에도 편집을 풀어 읽기 모드로 돌아간다', async ({
    authenticatedPage: page,
  }) => {
    const { releaseSave } = await mockSlowSave(page);
    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '이동 가드 작업' })).toBeVisible();

    // 편집 진입 → 프롬프트를 바꿔 미저장 상태를 만든다
    await page.getByRole('button', { name: '편집', exact: true }).click();
    await expect(page.locator('#job-prompt')).toBeVisible();
    await page.locator('#job-prompt').fill('지연 응답 사이에 탭을 옮긴 프롬프트');
    await expect(unsavedIndicator(page)).toBeVisible();

    await saveButton(page).click();

    // 지연 중 실행 이력 탭 클릭 — pathname 은 그대로고 ?tab 만 바뀌는 이동
    await page.getByRole('tab', { name: '실행 이력' }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1\?tab=executions/);

    // 탭 이동이 확인된 뒤에 응답을 풀어, 「응답이 이동보다 먼저」인 경우를 만들지 않는다
    releaseSave();

    // 응답 도착을 성공 토스트로 확인한다 — 토스트는 가드와 무관하게 뜬다
    await expect(page.getByText('작업이 저장되었습니다.')).toBeVisible();

    // 개요 탭으로 돌아와 읽기 모드로 돌아왔는지 단언한다 — 탭 전환을 떠남으로 세면(반려 1 이전)
    // 저장이 이탈로 읽혀 편집 칸·미저장 표시가 그대로 남고 다음 저장이 409 로 부딪힌다
    await page.getByRole('tab', { name: '개요' }).click();
    await expect(saveButton(page)).toHaveCount(0);
    await expect(cancelButton(page)).toHaveCount(0);
    await expect(unsavedIndicator(page)).toHaveCount(0);
    await expect(page.getByRole('heading', { name: '이동 가드 작업' })).toBeVisible();
  });
});
