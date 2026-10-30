/**
 * AISidePanel 지연 로드 중 레이아웃 시프트 회귀 가드 (이슈 #729)
 *
 * 결함: AISidePanel은 AppLayout에서 `lazy`로 불러오는데, 그 Suspense fallback이 패널의 열림 여부와
 * 무관하게 lg 이상에서 항상 320px(`lg:w-80`) 자리를 차지했다. 패널은 기본이 닫힘(폭 0)이므로,
 * 청크가 도착하기 전까지 본문(<main>)이 320px 좁게 그려졌다가 도착하는 순간 넓어지는 레이아웃
 * 시프트가 모든 라우트의 첫 로드에서 일어났다. 병렬 부하로 청크가 늦게 오면 그 사이에 폭을 재는
 * E2E(a11y-reflow-motion.spec.ts의 1280px 3-pane 테스트)가 캔버스 256px를 읽고 간헐 실패했다.
 *
 * 타이밍에 기대지 않도록 모듈 요청을 테스트가 직접 붙잡아 "아직 도착하지 않은" 구간을 고정한다.
 */

import { expect, test } from '../../fixtures/auth.fixture';

/**
 * AISidePanel lazy 청크 요청 — 서버 모드마다 경로가 다르다.
 * - dev: 원본 모듈 경로(쿼리 ?t=, ?v= 가 붙을 수 있어 pathname 으로 본다)
 * - preview(커밋 훅·push 게이트의 전체 E2E): 번들 청크 `/assets/AISidePanel-<해시>.js`
 * dev 경로만 잡던 때는 preview 에서 아무것도 붙잡지 못해, 청크가 첫 확인보다 늦게 오기만 바라는 경주가 되었고
 * 부하로 청크가 먼저 오면 재시도까지 실패했다(WD-452). 이 청크는 index 의 동적 import 로만 불러오므로 붙잡아도 다른 화면은 막히지 않는다.
 */
const isSidePanelModule = (url: URL) =>
  url.pathname.endsWith('/src/components/ai/AISidePanel.tsx') ||
  /\/assets\/AISidePanel-[\w-]+\.js$/.test(url.pathname);

/** <main>의 실제 폭(px). */
const mainWidth = (page: import('@playwright/test').Page) =>
  page.locator('main').evaluate((m) => Math.round(m.getBoundingClientRect().width));

test.describe('AISidePanel — 지연 로드 중 본문 폭 (이슈 #729)', () => {
  test('닫힌 패널의 청크가 아직 안 왔어도 본문이 320px 좁아지지 않는다', async ({
    authenticatedPage: page,
  }) => {
    // 청크 요청을 release()를 부를 때까지 붙잡아 둔다 — fallback이 그려진 상태를 결정적으로 만든다.
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(isSidePanelModule, async (route) => {
      await gate;
      await route.continue();
    });

    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto('/');
    await expect(page.locator('main')).toBeVisible();

    // 공허한 통과 방지 — 지금이 정말 "청크 도착 전"(Suspense fallback 구간)인지 먼저 확인한다.
    await expect(page.getByTestId('ai-side-panel')).toHaveCount(0);
    // 1280 - 사이드바(펼침 240) = 1040. 수정 전에는 fallback이 320px를 먹어 720이었다.
    expect(await mainWidth(page)).toBe(1040);

    release();
    await expect(page.getByTestId('ai-side-panel')).toBeAttached();
    // 도착 후에도 같은 폭 — 즉 도착 전후로 본문이 움직이지 않는다.
    expect(await mainWidth(page)).toBe(1040);
  });
});
