/**
 * AISessionSidebar — 키보드 접근성 회귀 테스트 (#346)
 *
 * 수정 전 결함:
 * - 대화 행이 `<div onClick>`이라 키보드로 대화를 열 수 없었다 (WCAG SC 2.1.1)
 * - 그런데 포커스는 `opacity: 0`인 삭제 버튼에만 갔다 (SC 2.4.7)
 * - 목록 구조(role=list/listitem)가 없어 개수·위계가 읽히지 않았다 (SC 4.1.2)
 *
 * "버튼이 보인다"만 확인하면 회귀를 못 잡으므로, 실제 키보드 입력으로
 * 대화를 열고 document.activeElement / computed opacity를 직접 계측한다.
 */

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

const chipLocator = (page: Page) => page.getByRole('button', { name: /AI 상태/ });

const MOCK_SESSIONS = [
  {
    id: 40,
    sessionId: 'session-346-a',
    title: '첫 번째 대화',
    createdAt: '2026-07-30T00:00:00Z',
    updatedAt: '2026-07-30T01:00:00Z',
  },
  {
    id: 41,
    sessionId: 'session-346-b',
    title: '두 번째 대화',
    createdAt: '2026-07-30T00:00:00Z',
    updatedAt: '2026-07-30T01:00:00Z',
  },
];

/** AI fullscreen 모드를 열고 세션 사이드바가 렌더될 때까지 대기한다 */
async function openFullscreenWithSessions(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_SESSIONS),
        });
      }
      return route.continue();
    },
  );

  await page.goto('/', { waitUntil: 'commit' });
  await chipLocator(page).click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5_000 });
  await chipLocator(page).click();
  await page.getByRole('button', { name: '새 대화' }).waitFor({ state: 'visible', timeout: 5_000 });
}

test.describe('AISessionSidebar — 키보드 접근성 (#346)', () => {
  test('대화 목록이 list/listitem 구조로 노출된다', async ({ authenticatedPage: page }) => {
    await openFullscreenWithSessions(page);

    const list = page.getByRole('list', { name: '대화 이력' });
    await expect(list).toBeVisible();
    await expect(list.getByRole('listitem')).toHaveCount(MOCK_SESSIONS.length);
  });

  test('대화가 없으면 role=list를 부여하지 않는다 (빈 상태 문구가 listitem이 아니므로)', async ({
    authenticatedPage: page,
  }) => {
    await page.route(
      (url) => url.pathname === '/api/v1/ai/sessions',
      (route) =>
        route.request().method() === 'GET'
          ? route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
          : route.continue(),
    );
    await page.goto('/', { waitUntil: 'commit' });
    await chipLocator(page).click();
    await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5_000 });
    await chipLocator(page).click();
    await page.getByRole('button', { name: '새 대화' }).waitFor({ state: 'visible', timeout: 5_000 });

    await expect(page.getByText('대화 이력이 없습니다')).toBeVisible();
    await expect(page.getByRole('list', { name: '대화 이력' })).toHaveCount(0);
  });

  test('키보드만으로 대화 행에 포커스해 Enter로 대화를 열 수 있다', async ({
    authenticatedPage: page,
  }) => {
    await openFullscreenWithSessions(page);

    // 세션 메시지 조회 API — Enter로 실제 대화가 로드되는지 확인하는 신호
    let loadedSessionId: string | null = null;
    await page.route(
      (url) => /\/api\/v1\/ai\/sessions\/session-346-b\/messages$/.test(url.pathname),
      (route) => {
        loadedSessionId = 'session-346-b';
        return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      },
    );

    // "새 대화" 버튼에서 Tab을 눌러 목록으로 진입 — 마우스는 전혀 쓰지 않는다
    await page.getByRole('button', { name: '새 대화' }).focus();

    const rowButton = page.getByRole('button', { name: '두 번째 대화', exact: true });
    // 최대 10회 Tab으로 두 번째 대화 행 버튼까지 이동
    let reached = false;
    for (let i = 0; i < 10; i += 1) {
      await page.keyboard.press('Tab');
      if (await rowButton.evaluate((el) => document.activeElement === el).catch(() => false)) {
        reached = true;
        break;
      }
    }
    // 수정 전에는 행이 div라 이 지점에 절대 도달하지 못했다
    expect(reached).toBe(true);

    await page.keyboard.press('Enter');
    await expect.poll(() => loadedSessionId, { timeout: 5_000 }).toBe('session-346-b');
  });

  test('삭제 버튼은 마우스 hover 없이 키보드 포커스만으로도 보인다', async ({
    authenticatedPage: page,
  }) => {
    await openFullscreenWithSessions(page);

    const deleteBtn = page.getByRole('button', { name: '첫 번째 대화 삭제' });
    // Button에 transition-all이 걸려 있어 값이 안정될 때까지 폴링한다
    const opacity = () => deleteBtn.evaluate((el) => parseFloat(getComputedStyle(el).opacity));

    // 포커스 전: hover가 없으므로 투명 (기존 동작 유지 확인)
    await expect.poll(opacity, { timeout: 2_000 }).toBe(0);

    // 실제 키보드 Tab으로 도달 — 마우스는 쓰지 않는다.
    // 수정 전에는 여기서도 opacity가 0이라 "보이지 않는 파괴적 액션"에만 포커스가 갔다.
    await page.getByRole('button', { name: '첫 번째 대화', exact: true }).focus();
    await page.keyboard.press('Tab');
    expect(await deleteBtn.evaluate((el) => document.activeElement === el)).toBe(true);
    // 키보드 도달이므로 :focus-visible → 완전 불투명
    await expect.poll(opacity, { timeout: 2_000 }).toBeGreaterThan(0.9);

    // 같은 행의 대화 버튼에 포커스가 있을 때도(group-focus-within) 삭제 버튼이 드러난다
    await page.getByRole('button', { name: '첫 번째 대화', exact: true }).focus();
    await expect.poll(opacity, { timeout: 2_000 }).toBeGreaterThan(0);
  });

  test('삭제 확인 다이얼로그를 Escape로 닫으면 포커스가 보이는 삭제 버튼으로 돌아온다', async ({
    authenticatedPage: page,
  }) => {
    await openFullscreenWithSessions(page);

    const deleteBtn = page.getByRole('button', { name: '첫 번째 대화 삭제' });
    await deleteBtn.click({ force: true });
    await expect(page.getByRole('alertdialog')).toBeVisible({ timeout: 3_000 });

    await page.keyboard.press('Escape');
    await expect(page.getByRole('alertdialog')).toBeHidden({ timeout: 3_000 });

    // #337 포커스 복귀 훅과의 상호작용 — 복귀한 포커스가 투명한 버튼이면 안 된다.
    // 마우스로 연 다이얼로그를 닫으면 :focus-visible이 안 걸릴 수 있으므로
    // group-focus-within이 가시성을 보장해야 한다.
    expect(await deleteBtn.evaluate((el) => document.activeElement === el)).toBe(true);
    await expect
      .poll(() => deleteBtn.evaluate((el) => parseFloat(getComputedStyle(el).opacity)), {
        timeout: 2_000,
      })
      .toBeGreaterThan(0);
  });
});
