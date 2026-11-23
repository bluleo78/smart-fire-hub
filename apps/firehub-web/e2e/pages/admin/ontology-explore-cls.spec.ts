import { setupAdminAuth, setupOntologyMocks } from '../../fixtures/admin.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * WD-822 회귀: 그래프 탐색(/knowledge-graph/explore) 진입 때 cytoscape-expand-collapse 가
 * 컨테이너에 덧붙이는 `canvas.expand-collapse-canvas` 가 위치 지정 없이 들어갔다가, 나중에
 * (디바운스된 sizeCanvas) position:absolute/top/left 와 크기가 인라인으로 붙으면서 화면이 밀리는
 * 레이아웃 시프트(약 0.26)가 났다. CSS 로 처음부터 위치를 고정하면 이 캔버스발 시프트가 사라진다.
 *
 * 타입 필터 패널이 목록 로드 뒤 나타나며 생기는 다른 시프트는 이 이슈 범위 밖이라, CLS 총합이
 * 아니라 "원인 노드가 이 캔버스인 시프트"만 단언한다.
 */
test.describe('그래프 탐색 CLS', () => {
  test('그래프 탐색 진입 때 타입 묶기 큐 캔버스가 밀려 들어오는 레이아웃 시프트가 없다 (WD-822)', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await setupAdminAuth(page);
    await setupOntologyMocks(page);

    // 페이지 스크립트보다 먼저 관측자를 건다 — buffered 로 진입 직후 시프트도 놓치지 않는다.
    // 마지막에 부를 __flushCanvasShift 가 takeRecords() 로 아직 콜백에 오지 않은 항목까지 거둬 모은 값을 돌려준다
    // (PerformanceObserver 콜백은 낮은 우선순위 작업이라 몇 프레임 안에 온다는 보장이 없다 — code-review).
    await page.addInitScript(() => {
      type LayoutShift = PerformanceEntry & {
        hadRecentInput: boolean;
        value: number;
        sources?: { node?: Node | null }[];
      };
      const shifts: number[] = [];
      // 원인 노드 중 이 캔버스가 있는 시프트만 모은다(입력 직후 시프트는 CLS 에서 빠지므로 뺀다).
      const collect = (entries: PerformanceEntry[]) => {
        for (const entry of entries) {
          const ls = entry as LayoutShift;
          if (ls.hadRecentInput) continue;
          const hit = (ls.sources ?? []).some(
            (s) => s.node instanceof Element && s.node.matches('canvas.expand-collapse-canvas'),
          );
          if (hit) shifts.push(ls.value);
        }
      };
      const observer = new PerformanceObserver((list) => collect(list.getEntries()));
      observer.observe({ type: 'layout-shift', buffered: true });
      (window as unknown as { __flushCanvasShift: () => number[] }).__flushCanvasShift = () => {
        collect(observer.takeRecords());
        return shifts;
      };
    });

    await page.goto('/knowledge-graph/explore');

    // 그래프가 그려질 때까지
    const graph = page.locator('[data-testid="instance-graph"]');
    await expect
      .poll(async () => Number((await graph.getAttribute('data-node-count')) ?? 0))
      .toBeGreaterThan(0);

    // 공허 통과 방지: 캔버스가 실제로 있고, 플러그인이 크기를 줬는지(= 시프트가 났을 시점이 지났는지)
    const canvas = page.locator('canvas.expand-collapse-canvas');
    await expect(canvas).toHaveCount(1);
    await expect
      .poll(async () => Number((await canvas.getAttribute('width')) ?? 0))
      .toBeGreaterThan(0);

    // 한 프레임을 흘려 마지막 레이아웃을 끝낸 뒤, 콜백에 아직 오지 않은 항목까지 거둬 읽는다.
    const shifts = await page.evaluate(async () => {
      await new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
      return (window as unknown as { __flushCanvasShift: () => number[] }).__flushCanvasShift();
    });
    expect(shifts).toEqual([]);
  });
});
