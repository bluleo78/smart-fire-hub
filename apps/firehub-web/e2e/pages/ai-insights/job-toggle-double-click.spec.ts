import type { Page } from '@playwright/test';

import { createJob } from '../../factories/ai-insight.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 목록 — 활성 스위치 더블클릭 중복 요청 방지 E2E (WD-511)
 * - 전환 요청이 진행 중인 동안 스위치가 잠기고 요청한 값을 바로 보이는지,
 *   더블클릭해도 끄기(POST /disable)·켜기(PUT) 요청이 한 번만 나가는지를 실제 요청 수로 검증한다.
 */

const DELAY_MS = 500;

/**
 * 한 작업의 목록·끄기·켜기 API 를 모킹한다 — 서버 상태(enabled)를 들고 있어
 * 끄기/켜기 성공 뒤 재조회가 바뀐 값을 돌려준다. 각 요청 수를 세고, 응답을 지연시켜 진행 중 상태를 관찰한다.
 */
async function mockToggleJob(
  page: Page,
  { initialEnabled, status = 200 }: { initialEnabled: boolean; status?: number },
) {
  const state = { enabled: initialEnabled };
  const counts = { disable: 0, put: 0 };
  const putPayloads: unknown[] = [];

  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await page.route(
    (url) => url.pathname === '/api/v1/proactive/jobs',
    (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([createJob({ id: 1, name: '잡 1', enabled: state.enabled })]),
      });
    },
  );
  await page.route(
    (url) => url.pathname === '/api/v1/proactive/jobs/1/disable',
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      counts.disable += 1;
      await new Promise((r) => setTimeout(r, DELAY_MS));
      if (status === 200) state.enabled = false;
      return route.fulfill({ status, contentType: 'application/json', body: '{}' });
    },
  );
  await page.route(
    (url) => url.pathname === '/api/v1/proactive/jobs/1',
    async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      counts.put += 1;
      putPayloads.push(route.request().postDataJSON());
      await new Promise((r) => setTimeout(r, DELAY_MS));
      if (status === 200) state.enabled = true;
      return route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(status === 200 ? createJob({ id: 1, name: '잡 1', enabled: true }) : {}),
      });
    },
  );
  return { counts, putPayloads };
}

test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

test.describe('스마트 작업 목록 — 활성 스위치 더블클릭 (WD-511)', () => {
  test('켜진 작업을 더블클릭해도 끄기 요청은 1회, 진행 중 잠기고 꺼진 상태로 끝난다', async ({
    authenticatedPage: page,
  }) => {
    const { counts, putPayloads } = await mockToggleJob(page, { initialEnabled: true });
    await page.goto('/ai-insights/jobs');

    const toggle = page.getByRole('switch', { name: '잡 1 활성화' });
    await expect(toggle).toBeChecked();

    await toggle.dblclick();

    // 진행 중: 요청한 값(꺼짐)을 바로 보이고 잠긴다
    await expect(toggle).toBeDisabled();
    await expect(toggle).not.toBeChecked();
    await expect(toggle).toHaveAttribute('aria-busy', 'true');

    // 완료: 잠금이 풀리고 재조회 값(꺼짐)으로 남는다
    await expect(page.getByText('작업이 비활성화되었습니다.')).toBeVisible();
    await expect(toggle).toBeEnabled();
    await expect(toggle).not.toBeChecked();
    expect(counts.disable).toBe(1);
    expect(counts.put).toBe(0);

    // 다시 한 번 누르면 켜기(PUT enabled:true) 가 1회 나간다
    await toggle.click();
    await expect(page.getByText('작업이 활성화되었습니다.')).toBeVisible();
    await expect(toggle).toBeEnabled();
    await expect(toggle).toBeChecked();
    expect(counts.put).toBe(1);
    expect(putPayloads[0]).toMatchObject({ enabled: true });
    expect(counts.disable).toBe(1);
  });

  test('꺼진 작업을 더블클릭해도 켜기 요청(PUT)은 1회다', async ({ authenticatedPage: page }) => {
    const { counts, putPayloads } = await mockToggleJob(page, { initialEnabled: false });
    await page.goto('/ai-insights/jobs');

    const toggle = page.getByRole('switch', { name: '잡 1 활성화' });
    await expect(toggle).not.toBeChecked();

    await toggle.dblclick();

    await expect(toggle).toBeDisabled();
    await expect(toggle).toBeChecked();

    await expect(page.getByText('작업이 활성화되었습니다.')).toBeVisible();
    await expect(toggle).toBeEnabled();
    await expect(toggle).toBeChecked();
    expect(counts.put).toBe(1);
    expect(putPayloads[0]).toMatchObject({ enabled: true });
    expect(counts.disable).toBe(0);
  });

  test('끄기가 실패하면 잠금이 풀리고 원래 값(켜짐)으로 돌아오며 오류를 알린다', async ({
    authenticatedPage: page,
  }) => {
    const { counts } = await mockToggleJob(page, { initialEnabled: true, status: 500 });
    await page.goto('/ai-insights/jobs');

    const toggle = page.getByRole('switch', { name: '잡 1 활성화' });
    await toggle.dblclick();
    await expect(toggle).toBeDisabled();

    await expect(page.getByText('상태 변경에 실패했습니다.')).toBeVisible();
    await expect(toggle).toBeEnabled();
    await expect(toggle).toBeChecked();
    expect(counts.disable).toBe(1);
  });

  test('끄기는 성공했지만 목록 재조회가 실패해도 스위치는 꺼진 채로 남는다(서버와 어긋나지 않음)', async ({
    authenticatedPage: page,
  }) => {
    // 첫 목록 조회만 켜짐을 주고, 끄기 뒤의 재조회는 500 — 잠금이 풀린 뒤 낡은 캐시(켜짐)로 되돌아가면 안 된다(리뷰 지적)
    const { counts } = await mockToggleJob(page, { initialEnabled: true });
    let listCalls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/proactive/jobs',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        listCalls += 1;
        if (listCalls === 1) return route.fallback();
        return route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
      },
    );
    await page.goto('/ai-insights/jobs');

    const toggle = page.getByRole('switch', { name: '잡 1 활성화' });
    await expect(toggle).toBeChecked();
    await toggle.click();

    await expect(page.getByText('작업이 비활성화되었습니다.')).toBeVisible();
    await expect(toggle).toBeEnabled();
    await expect(toggle).not.toBeChecked();
    // 재조회가 실제로 시도됐고(실패) 그래도 꺼짐이 유지된다
    await expect.poll(() => listCalls).toBeGreaterThan(1);
    await expect(toggle).not.toBeChecked();
    expect(counts.disable).toBe(1);
  });
});
