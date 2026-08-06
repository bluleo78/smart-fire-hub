import type { Locator } from '@playwright/test';

import { expect, test } from '../fixtures/auth.fixture';

/**
 * 라우트 전환 시 스크롤 위치 회귀 가드.
 *
 * 배경: 실제 스크롤 컨테이너가 `window`가 아니라 `<main>`(AppLayout)이라
 * 브라우저·라우터의 기본 스크롤 복원이 전혀 걸리지 않았다. 그 결과 목록을 아래까지 스크롤한 뒤
 * 다른 화면으로 이동하면 `main.scrollTop`이 그대로 이월돼 **새 페이지가 중간/맨 아래부터 보였다.**
 * (실측: 홈에서 400 → /data/datasets 도착 시 240 = maxScroll로 클램프된 최하단)
 *
 * 뷰포트를 낮게 잡아 `main`이 확실히 넘치게 만든 뒤 검증한다. 목표 오프셋은 **실측 maxScroll에서
 * 파생**한다 — 상수를 쓰면 모킹 데이터 양이 바뀔 때 조용히 클램프돼 테스트가 무의미해진다.
 */

const SHORT_VIEWPORT = { width: 1280, height: 400 };

/** `main`이 스크롤 가능해질 때까지 기다리고 최대 스크롤 오프셋을 돌려준다. */
async function waitForScrollable(main: Locator): Promise<number> {
  await expect
    .poll(() => main.evaluate((el) => el.scrollHeight - el.clientHeight))
    .toBeGreaterThan(0);
  return main.evaluate((el) => el.scrollHeight - el.clientHeight);
}

/** 클램프되지 않는 위치로 스크롤하고 실제 반영된 오프셋을 돌려준다. */
async function scrollTo(main: Locator, offset: number): Promise<number> {
  await main.evaluate((el, y) => {
    el.scrollTop = y;
  }, offset);
  const actual = await main.evaluate((el) => el.scrollTop);
  expect(actual).toBeGreaterThan(0);
  return actual;
}

test.describe('앱 셸 높이', () => {
  test('셸이 h-screen(100vh)이 아니라 h-dvh를 쓴다', async ({ authenticatedPage: page }) => {
    // 100vh는 모바일 브라우저에서 주소창을 포함한 높이라 하단이 잘리고 스크롤 범위가 실제와
    // 어긋난다. 데스크톱 Chrome에서는 vh와 dvh의 계산값이 같아 런타임 높이로는 구분할 수 없으므로
    // **클래스 자체가 계약**이다. 되돌리면 여기서 잡힌다.
    await page.goto('/');
    const shell = page.locator('body > #root > div').first();
    await expect(shell).toBeVisible();

    const cls = await shell.evaluate((el) => el.className);
    expect(cls).toContain('h-dvh');
    expect(cls).not.toContain('h-screen');
  });
});

test.describe('라우트 전환 스크롤 위치', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await page.setViewportSize(SHORT_VIEWPORT);
  });

  // 주의: 이 테스트만으로는 리셋 로직을 지워도 통과한다(뮤테이션으로 확인함).
  // 처음 방문하는 화면은 lazy 청크 로딩 동안 콘텐츠가 짧아 브라우저가 스크롤을 0으로 클램프하기 때문이다.
  // **실제 회귀 가드는 아래 "같은 화면으로 다시 이동…" 테스트**다 — 청크·쿼리 캐시가 더워진 상태에서
  // 페이지가 곧바로 길게 렌더되므로 리셋이 없으면 이전 오프셋이 그대로 남는다.
  // 이 테스트는 의도를 문서화하고 명백한 파손(리셋이 위로가 아닌 아래로 등)을 잡는 역할이다.
  test('다른 화면으로 이동하면 스크롤이 맨 위로 리셋된다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/data/datasets');
    const main = page.locator('main');
    await expect(main).toBeVisible();
    await waitForScrollable(main);

    await main.evaluate((el) => {
      el.scrollTop = el.scrollHeight;
    });
    expect(await main.evaluate((el) => el.scrollTop)).toBeGreaterThan(0);

    // 이동 대상도 **스크롤 가능한** 화면이어야 한다. 짧은 화면으로 가면 브라우저가 어차피
    // 0으로 클램프해서, 리셋 로직을 지워도 통과하는 공허한 단언이 된다.
    await page.getByRole('link', { name: '파이프라인' }).click();
    await expect(page).toHaveURL(/\/pipelines/);
    await waitForScrollable(main);

    await expect.poll(() => main.evaluate((el) => el.scrollTop)).toBe(0);
  });

  test('뒤로가기는 이전 화면의 스크롤 위치를 복원한다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/data/datasets');
    const main = page.locator('main');
    await expect(main).toBeVisible();
    const max = await waitForScrollable(main);

    // maxScroll의 절반 — 클램프되지 않으면서 0과도 확실히 구분되는 위치
    const target = await scrollTo(main, Math.floor(max / 2));

    await page.getByRole('link', { name: '카테고리' }).click();
    await expect(page).toHaveURL(/\/data\/categories/);
    await expect.poll(() => main.evaluate((el) => el.scrollTop)).toBe(0);

    await page.goBack();
    await expect(page).toHaveURL(/\/data\/datasets/);

    // POP이므로 리셋이 아니라 복원이어야 한다 — 0이 나오면 복원 경로가 죽은 것이다.
    // 복원은 콘텐츠가 다시 자라야 가능하므로 폴링 타임아웃을 훅의 예산(1200ms)보다 넉넉히 준다.
    await expect
      .poll(() => main.evaluate((el) => el.scrollTop), { timeout: 5000 })
      .toBe(target);
  });

  test('같은 화면으로 다시 이동하면 저장된 위치가 아니라 맨 위에서 시작한다', async ({
    authenticatedPage: page,
  }) => {
    // PUSH는 "새 화면"이므로 이전 방문 위치를 되살리면 안 된다.
    await page.goto('/data/datasets');
    const main = page.locator('main');
    await expect(main).toBeVisible();
    const max = await waitForScrollable(main);
    await scrollTo(main, Math.floor(max / 2));

    await page.getByRole('link', { name: '카테고리' }).click();
    await expect(page).toHaveURL(/\/data\/categories/);
    await page.getByRole('link', { name: '데이터셋' }).click();
    await expect(page).toHaveURL(/\/data\/datasets/);

    await expect.poll(() => main.evaluate((el) => el.scrollTop)).toBe(0);
  });
});
