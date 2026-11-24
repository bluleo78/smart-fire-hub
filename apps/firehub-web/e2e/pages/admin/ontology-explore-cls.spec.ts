import { createOntologySummaries } from '../../factories/mapping.factory';
import { setupAdminAuth, setupOntologyMocks } from '../../fixtures/admin.fixture';
import { expect, test } from '../../fixtures/auth.fixture';
import { holdApi } from '../../fixtures/hold-api';

/**
 * WD-822 회귀: 그래프 탐색(/knowledge-graph/explore) 진입 때 cytoscape-expand-collapse 가
 * 컨테이너에 덧붙이는 `canvas.expand-collapse-canvas` 가 위치 지정 없이 들어갔다가, 나중에
 * (디바운스된 sizeCanvas) position:absolute/top/left 와 크기가 인라인으로 붙으면서 화면이 밀리는
 * 레이아웃 시프트(약 0.26)가 났다. CSS 로 처음부터 위치를 고정하면 이 캔버스발 시프트가 사라진다.
 *
 * 타입 필터 패널이 목록 로드 뒤 나타나며 생기는 다른 시프트는 WD-822 이슈 범위 밖이라, CLS 총합이
 * 아니라 "원인 노드가 이 캔버스인 시프트"만 단언한다 — 목록 로딩에서 패널·토글이 생기며 밀리던
 * 진입 시프트는 같은 describe 안의 WD-1041 시험이 따로 단언한다.
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

  // WD-1041 회귀: 목록 로딩 구간에 타입 필터 자리(패널·접기 토글)가 없었다가 응답과 함께 나타나면,
  // 좌측 패널 등장이 그래프 영역을 밀고(x 264→520), 토글 등장이 페이지 제목 h1 을 밀었다(x 276→320) —
  // 진입 시 한 번에 0.134 의 레이아웃 시프트가 났다. 로딩 구간을 «자리 유지»로 바꾼 뒤에는
  // 시프트 출처로 지목한 두 요소 어디도 밀리지 않아야 한다.
  //
  // 목록 응답을 붙잡아 뒀다가 풀어 진입 레이아웃 시프트를 재는 이유: 응답이 먼저 오면 로딩 구간이
  // 화면에 남지 않아 "자리가 밀리는" 순간 자체가 없어져, 시험이 아무것도 재지 못하고 통과한다
  // (공허 통과). holdApi 로 목록을 붙잡아 진입 → 로딩 구간 확인 → 풀어 준 뒤의 전환만 단언한다.
  test('그래프 탐색 진입 때 목록 로딩이 끝나도 타입 필터 자리 때문에 그래프 영역·제목이 밀리지 않는다 (WD-1041)', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await setupAdminAuth(page);
    await setupOntologyMocks(page);

    // 시프트 출처를 캔버스가 아니라 이슈가 지목한 두 요소(그래프 패널·제목 h1)로 둔다 —
    // 캔버스 내부(노드 레이아웃)의 움직임은 이 이슈의 진입 시프트가 아니므로 섞이지 않게 한다.
    // takeRecords() 없이 읽으면 PerformanceObserver 콜백이 늦어 빈 배열을 그대로 돌려주고
    // 시험이 공허 통과한다 — 마지막에 부르는 __flushLayoutShift 가 takeRecords() 까지 거둬 모은다.
    // 이 시험의 수집기는 캔버스가 아니라 그래프 패널·h1 만 보므로 이름도 canvas 가 아니다(WD-1041).
    await page.addInitScript(() => {
      type LayoutShift = PerformanceEntry & {
        hadRecentInput: boolean;
        value: number;
        sources?: { node?: Node | null }[];
      };
      const shifts: number[] = [];
      const SOURCES = ['[data-testid="instance-graph-panel"]', 'h1'];
      const collect = (entries: PerformanceEntry[]) => {
        for (const entry of entries) {
          const ls = entry as LayoutShift;
          if (ls.hadRecentInput) continue;
          const hit = (ls.sources ?? []).some((s) => {
            // Element 로 좁힌 뒤에 셀렉터와 비교한다 — 중첩 콜백 안에서는 s.node 프로퍼티의 좁힘이
            // 유지되지 않아 그대로 쓰면 Node 타입에 matches 가 없다(TS2339).
            const node = s.node;
            return node instanceof Element && SOURCES.some((sel) => node.matches(sel));
          });
          if (hit) shifts.push(ls.value);
        }
      };
      const observer = new PerformanceObserver((list) => collect(list.getEntries()));
      observer.observe({ type: 'layout-shift', buffered: true });
      (window as unknown as { __flushLayoutShift: () => number[] }).__flushLayoutShift = () => {
        collect(observer.takeRecords());
        return shifts;
      };
    });

    // 목록 응답을 붙잡아 둔 채로 진입한다 — 이 구간이 "로딩 중"이고, 그래프 영역·제목이
    // 곧 밀릴 자리(패널·토글 부재)를 그대로 보여 주는 유일한 시점이다.
    const listHeld = await holdApi(page, 'GET', '/api/v1/ontologies', 200, createOntologySummaries());
    await page.goto('/knowledge-graph/explore');

    // 로딩 구간이 실제로 화면에 떠 있는지 먼저 본다 — 응답이 이보다 먼저 풀리면 이 뒤의 시프트 재기가
    // 무의미해지므로, 이 단언이 곧 "시험이 공허해지지 않았다"의 증거다.
    await expect(page.getByTestId('graph-loading')).toBeVisible();

    // 목록 응답을 푼다 — 그래프는 목록이 와야 effectiveOntologyId 를 받아 그려지기 때문에,
    // 이 시점이 곧 로딩 구간 → 확정 구간으로 넘어가는 레이아웃 전환이다(노드 수가 늘어난다).
    listHeld.release();

    // 그래프가 그려질 때까지 — 목록이 풀리기 전엔 InstanceGraph 가 마운트되지 않아 노드 수를 읽을 수 없어
    // 여기(해제 뒤)에서 폴링한다.
    const graph = page.locator('[data-testid="instance-graph"]');
    await expect
      .poll(async () => Number((await graph.getAttribute('data-node-count')) ?? 0))
      .toBeGreaterThan(0);

    // 노드 수가 늘어난 뒤 2프레임을 흘려 마지막 레이아웃을 끝낸 뒤, 콜백에 아직 오지 않은 항목까지 거둬 읽는다.
    const shifts = await page.evaluate(async () => {
      await new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
      return (window as unknown as { __flushLayoutShift: () => number[] }).__flushLayoutShift();
    });
    expect(shifts).toEqual([]);
  });
});
