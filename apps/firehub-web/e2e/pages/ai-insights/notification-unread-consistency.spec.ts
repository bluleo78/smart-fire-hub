/**
 * AI 알림 패널 미읽음 수 일관성 + 페이지네이션 E2E 테스트 (이슈 #351)
 *
 * 결함:
 * 1. 헤더 벨 배지는 서버 전체 집계(`unread-count`)를, 패널 배지는 받아온 50건 안에서만
 *    센 값을 표시해 한 화면에서 140 vs 50으로 어긋났다.
 * 2. 패널이 `limit: 50` 한 페이지만 가져오고 더 보기가 없어 나머지 90건에 도달할 수 없었다.
 *
 * 회귀 방지 포인트: 두 배지가 같은 서버 집계를 쓰고, "더 보기"로 다음 페이지가 실제로 붙는다.
 */

import type { Page } from '@playwright/test';

import { createMessage } from '../../factories/ai-insight.factory';
import { setupJobListMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 서버 전체 미읽음 수 — 한 페이지(50) 밖에 더 있는 상황을 재현한다 */
const TOTAL_UNREAD = 140;

/** 미읽음 메시지 n건 생성 (id/title 유니크) */
function unreadMessages(count: number, startId = 1) {
  return Array.from({ length: count }, (_, i) =>
    createMessage({ id: startId + i, title: `알림 ${startId + i}`, read: false }),
  );
}

/**
 * `limit` 쿼리 파라미터를 실제로 존중하는 목록 모킹.
 * 고정 배열 모킹으로는 "더 보기가 다음 페이지를 실제로 가져오는가"를 검증할 수 없다.
 */
async function mockPagedMessages(page: Page, total: number) {
  const all = unreadMessages(total);
  await page.route(
    (url) => url.pathname === '/api/v1/proactive/messages',
    (route) => {
      const limit = Number(new URL(route.request().url()).searchParams.get('limit') ?? total);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(all.slice(0, limit)),
      });
    },
  );
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: total });
}

test.describe('AI 알림 미읽음 수 일관성 (이슈 #351)', () => {
  test('패널 배지가 헤더 벨과 같은 서버 전체 미읽음 수를 표시한다', async ({
    authenticatedPage: page,
  }) => {
    // 목록은 50건만 반환하지만 서버 전체 미읽음은 140건 — 수정 전 불일치가 나던 조건.
    // setupJobListMocks가 unread-count를 0으로 덮으므로 반드시 먼저 호출한다(나중 등록이 우선).
    await setupJobListMocks(page);
    await mockPagedMessages(page, TOTAL_UNREAD);

    await page.goto('/ai-insights/jobs');

    const bell = page.getByRole('button', { name: `안 읽은 AI 인사이트 ${TOTAL_UNREAD}개` });
    await expect(bell).toBeVisible();
    await bell.click();

    await expect(page.getByRole('dialog', { name: 'AI 인사이트 알림' })).toBeVisible();
    // 수정 전에는 받아온 페이지 크기(50)가 찍혔다.
    await expect(page.getByTestId('notification-panel-unread-badge')).toHaveText(
      String(TOTAL_UNREAD),
    );

    // 전체 읽음 버튼이 대상 범위(서버 전량)를 문구로 밝힌다.
    await expect(
      page.getByRole('button', { name: `안 읽은 알림 ${TOTAL_UNREAD}건 전체를 읽음 처리` }),
    ).toBeVisible();
  });

  test('"더 보기"로 50건 너머의 알림을 열람할 수 있다', async ({ authenticatedPage: page }) => {
    // setupJobListMocks가 unread-count를 0으로 덮으므로 반드시 먼저 호출한다(나중 등록이 우선).
    await setupJobListMocks(page);
    await mockPagedMessages(page, TOTAL_UNREAD);

    await page.goto('/ai-insights/jobs');
    await page.getByRole('button', { name: /안 읽은 AI 인사이트/ }).click();

    const dialog = page.getByRole('dialog', { name: 'AI 인사이트 알림' });
    await expect(dialog).toBeVisible();

    // 목록 항목 전용 locator — '전체 읽음' 버튼의 이름에도 숫자가 들어가 이름 부분매칭은 충돌한다.
    const item = (n: number) =>
      dialog.getByRole('button', { name: `AI 인사이트: 알림 ${n} (안 읽음)`, exact: true });

    // 첫 페이지 경계: 50번은 있고 51번은 없다.
    await expect(item(50)).toBeAttached();
    await expect(item(51)).toHaveCount(0);

    // 더 보기 → 다음 50건이 실제로 붙는다(수정 전에는 버튼 자체가 없었다).
    const more = dialog.getByRole('button', { name: '더 보기' });
    await expect(more).toBeVisible();
    await more.click();

    await expect(item(51)).toBeAttached();
    await expect(item(100)).toBeAttached();

    // 마지막 페이지까지 가면 더 보기가 사라진다(140건 < 150).
    await dialog.getByRole('button', { name: '더 보기' }).click();
    await expect(item(140)).toBeAttached();
    await expect(dialog.getByRole('button', { name: '더 보기' })).toHaveCount(0);
  });
});
