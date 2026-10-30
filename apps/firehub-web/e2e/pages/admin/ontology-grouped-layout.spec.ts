import type { Page } from '@playwright/test';

import type { GraphData } from '@/types/ontology';

import { createLargeOntologyGraph } from '../../factories/ontology.factory';
import { mockOntologyGraph, setupAdminAuth, setupOntologyMocks } from '../../fixtures/admin.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * (WD-378) 지식그래프 「타입 묶기」 레이아웃 회귀 테스트.
 *
 * - 묶기 중 검색/타입 필터 변경은 새 노드가 없으면 레이아웃(fcose)을 다시 돌리지 않고 묶기 좌표를 유지해야 한다
 *   (예전에는 검색어 한 글자마다 전체 fcose가 다시 돌아 탭이 멈췄다).
 * - 큰 그래프 묶기는 시간 상한 안에 끝나고, 배치 중에는 「배치 중…」 표시가 보였다가 끝나면 사라져야 한다.
 *
 * 캔버스(Cytoscape)라 DOM으로 레이아웃을 볼 수 없으므로, E2E 빌드에서 노출되는 window.__ontologyCy에
 * layoutstop 리스너를 달아 "레이아웃이 몇 번 돌았는가"를 센다.
 */

// 테스트에서 쓰는 cy 최소 형태 — 공식 타입을 페이지 컨텍스트로 가져갈 수 없어 필요한 메서드만 선언한다.
type TestCy = {
  on(event: string, handler: () => void): void;
  nodes(sel?: string): {
    length: number;
    map<T>(f: (n: { position(): { x: number; y: number }; width(): number; data(key: string): string }) => T): T[];
  };
  edges(): { length: number };
  getElementById(id: string): { position(): { x: number; y: number } };
};
type TestWindow = { __ontologyCy: TestCy; __layoutStops?: number };
// 「배치 중…」 표시·프레임·레이아웃 시작 시각 기록(페이지 안에서 채운다).
type ProgressRecord = {
  shownAt?: number;
  hiddenAt?: number;
  text?: string;
  ariaLive?: string | null;
  frames: number[];
  layoutStartAt?: number;
};

const BUNDLE_TYPES = ['Incident', 'Damage', 'Cause', 'Building', 'Equipment', 'Regulation'] as const;

/**
 * 타입마다 PER_TYPE개 노드 + 타입을 가로지르는 관계만 있는 소형 그래프(150노드) — 기존 메타노드 분리 테스트와
 * 같은 모양이라, 묶기 좌표가 아니라 평탄 좌표로 복원되는 회귀가 생기면 메타노드가 곧바로 겹친다.
 */
function createBundleGraph(perType = 25): GraphData {
  const nodes = BUNDLE_TYPES.flatMap((type, ti) =>
    Array.from({ length: perType }, (_, i) => ({
      key: `bundle-${ti}-${i}`,
      type,
      name: `${type} 노드 ${i}`,
      sourceChunkCount: 1,
      schemaVersion: 1,
      ontologyId: 1,
    })),
  );
  const edges = nodes.map((n, i) => ({
    subjectKey: n.key,
    type: 'RELATES_TO',
    objectKey: nodes[(i + perType + 7) % nodes.length].key,
  }));
  return { nodes, edges };
}

/** 그래프 탐색 탭에 그래프를 띄우고 렌더된 노드 수가 전량이 될 때까지 기다린다. */
async function openExplore(page: Page, graph: GraphData) {
  await setupOntologyMocks(page);
  await mockOntologyGraph(page, graph);
  await page.goto('/knowledge-graph/explore');
  await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(graph.nodes.length), {
    timeout: 20_000,
  });
}

/** 이 시점부터 끝난 레이아웃 수를 window.__layoutStops에 센다. */
async function countLayoutStops(page: Page) {
  await page.evaluate(() => {
    const w = window as unknown as TestWindow;
    w.__layoutStops = 0;
    w.__ontologyCy.on('layoutstop', () => {
      w.__layoutStops = (w.__layoutStops ?? 0) + 1;
    });
  });
}

const layoutStops = (page: Page) => page.evaluate(() => (window as unknown as TestWindow).__layoutStops ?? 0);

// 묶기 부모/요약 노드(isGroup) 수 — 요약 화면은 expand-collapse를 쓰지 않으므로 접힘 클래스 대신 이걸 센다.
const groupCount = (page: Page) =>
  page.evaluate(() => (window as unknown as TestWindow).__ontologyCy.nodes('[?isGroup]').length);

const collapsedCount = (page: Page) =>
  page.evaluate(
    () => (window as unknown as TestWindow).__ontologyCy.nodes('.cy-expand-collapse-collapsed-node').length,
  );

/**
 * 묶기 레이아웃은 표시를 그릴 프레임을 양보한 뒤(rAF → rAF → setTimeout) 실행된다. "레이아웃이 안 돌았다"를
 * 단언하려면 그 예약이 실행됐을 시점까지 기다려야 하므로, 같은 경로보다 한 프레임 더 길게 기다린다.
 */
async function waitPastDeferredLayout(page: Page) {
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() =>
          requestAnimationFrame(() => requestAnimationFrame(() => setTimeout(() => resolve(), 50))),
        ),
      ),
  );
}

/**
 * 셀렉터에 걸린 노드(접힌 메타노드·요약 노드)끼리 원이 겹치는 쌍 목록 — 묶기 좌표가 아닌 평탄 좌표로 복원되거나,
 * 접기 전에 부모 좌표가 갱신되지 않아 전부 원점에 쌓이면 여기서 걸린다.
 */
async function overlappingNodePairs(page: Page, selector = '.cy-expand-collapse-collapsed-node'): Promise<string[]> {
  const metas = await page.evaluate(
    (sel) =>
      (window as unknown as TestWindow).__ontologyCy
        .nodes(sel)
        .map((n) => ({ x: n.position().x, y: n.position().y, w: n.width() })),
    selector,
  );
  const pairs: string[] = [];
  for (let i = 0; i < metas.length; i++) {
    for (let j = i + 1; j < metas.length; j++) {
      const dist = Math.hypot(metas[i].x - metas[j].x, metas[i].y - metas[j].y);
      if (dist < (metas[i].w + metas[j].w) / 2) pairs.push(`${i}-${j}(${Math.round(dist)}px)`);
    }
  }
  return pairs;
}

test.describe('지식그래프 타입 묶기 레이아웃 (WD-378)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('묶기 중 검색어를 바꿔도 레이아웃을 다시 돌리지 않고 접힌 메타노드 배치를 유지한다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createBundleGraph();
    await openExplore(page, graph);

    await page.getByRole('button', { name: '타입 묶기' }).click();
    await expect.poll(() => collapsedCount(page)).toBe(BUNDLE_TYPES.length);
    await countLayoutStops(page);

    // 검색 — 모든 타입에 "노드 1"·"노드 1x"가 있어 6타입이 모두 남는 부분집합(타입당 11개)이 된다.
    const query = '노드 1';
    const expected = graph.nodes.filter((n) => n.name.includes(query)).length;
    await page.getByPlaceholder('이름 검색').fill(query);
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(expected));
    await waitPastDeferredLayout(page);

    // 재배치는 없었고(layoutstop 0회), 그래도 다시 접혀 타입 수만큼의 메타노드만 보이며, 서로 겹치지 않는다.
    expect(await layoutStops(page)).toBe(0);
    expect(await collapsedCount(page)).toBe(BUNDLE_TYPES.length);
    expect(await page.evaluate(() => (window as unknown as TestWindow).__ontologyCy.nodes().length)).toBe(
      BUNDLE_TYPES.length,
    );
    expect(await overlappingNodePairs(page), '검색 후 겹친 메타노드 쌍').toEqual([]);
    // 재배치하지 않으므로 「배치 중…」 표시도 뜨지 않는다.
    await expect(page.getByTestId('instance-graph-laying-out')).toHaveCount(0);

    // 검색을 지워 원래 노드로 돌아와도 전부 묶기 좌표가 있는 노드라 재배치하지 않는다.
    await page.getByPlaceholder('이름 검색').fill('');
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(graph.nodes.length));
    // 타입 필터로 한 타입을 숨겨도 마찬가지 — 남은 5타입만 접혀 보인다.
    await page.getByTestId('type-filter-panel').getByRole('button', { name: /Regulation/ }).click();
    await expect(page.getByTestId('instance-graph')).toHaveAttribute(
      'data-node-count',
      String(graph.nodes.filter((n) => n.type !== 'Regulation').length),
    );
    await waitPastDeferredLayout(page);
    expect(await layoutStops(page)).toBe(0);
    expect(await collapsedCount(page)).toBe(BUNDLE_TYPES.length - 1);
    expect(await overlappingNodePairs(page), '타입 필터 후 겹친 메타노드 쌍').toEqual([]);

    // 대조군 — 묶기를 껐다 켜면(토글) 다시 배치한다. 카운터가 실제로 레이아웃을 세고 있음을 보장한다.
    await page.getByRole('button', { name: '타입 묶기' }).click();
    await expect.poll(() => collapsedCount(page)).toBe(0);
    await page.getByRole('button', { name: '타입 묶기' }).click();
    await expect.poll(() => layoutStops(page)).toBe(1);
    await expect.poll(() => collapsedCount(page)).toBe(BUNDLE_TYPES.length - 1);
  });

  test('묶기 중 검색을 지워 처음 보는 노드가 나타나면 그때는 다시 배치한다', async ({ authenticatedPage: page }) => {
    const graph = createBundleGraph();
    await openExplore(page, graph);

    // 검색으로 일부만 남긴 채 묶는다 — 묶기 좌표는 이 부분집합에만 생긴다.
    const query = '노드 1';
    await page.getByPlaceholder('이름 검색').fill(query);
    await expect(page.getByTestId('instance-graph')).toHaveAttribute(
      'data-node-count',
      String(graph.nodes.filter((n) => n.name.includes(query)).length),
    );
    await page.getByRole('button', { name: '타입 묶기' }).click();
    await expect.poll(() => collapsedCount(page)).toBe(BUNDLE_TYPES.length);
    await countLayoutStops(page);

    // 검색을 지우면 묶기 좌표가 없는 노드가 대거 나타난다 — 평탄 좌표 그대로 접으면 타입이 섞이므로 재배치해야 한다.
    await page.getByPlaceholder('이름 검색').fill('');
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(graph.nodes.length));
    await expect.poll(() => layoutStops(page)).toBe(1);
    await expect.poll(() => collapsedCount(page)).toBe(BUNDLE_TYPES.length);
    expect(await overlappingNodePairs(page), '재배치 후 겹친 메타노드 쌍').toEqual([]);
  });

  test('큰 그래프 묶기는 상한 시간 안에 타입별 요약 노드로 끝나고 「배치 중…」 표시가 나타났다가 사라진다', async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(90_000);
    // 운영 신고 규모(3,071노드)의 약 1/3(1,075노드) — 요약 노드 경로(500노드 초과)를 타면서도 테스트가 수 초 안에 돈다.
    const graph = createLargeOntologyGraph(0.35);
    const countOf = (nodes: GraphData['nodes'], type: string) => nodes.filter((n) => n.type === type).length;
    await openExplore(page, graph);

    // 배치 중에는 메인 스레드가 막혀 Playwright의 가시성 폴링이 끼어들 수 없다 — 페이지 안에서
    // MutationObserver로 표시가 붙은/떨어진 시각을, rAF로 프레임 시각을, cy 이벤트로 레이아웃 시작 시각을 기록한다.
    await page.evaluate(() => {
      const w = window as unknown as TestWindow & { __wd378: ProgressRecord };
      const rec: ProgressRecord = { frames: [] };
      w.__wd378 = rec;
      const sel = '[data-testid="instance-graph-laying-out"]';
      new MutationObserver(() => {
        const el = document.querySelector(sel);
        if (el && rec.shownAt === undefined) {
          rec.shownAt = performance.now();
          rec.text = el.textContent ?? '';
          rec.ariaLive = el.querySelector('[role="status"]')?.getAttribute('aria-live') ?? null;
        }
        if (!el && rec.shownAt !== undefined && rec.hiddenAt === undefined) rec.hiddenAt = performance.now();
      }).observe(document.body, { childList: true, subtree: true });
      const tick = () => {
        rec.frames.push(performance.now());
        if (rec.hiddenAt === undefined) requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
      w.__ontologyCy.on('layoutstart', () => {
        rec.layoutStartAt ??= performance.now();
      });
    });

    // 묶기 해제 후 평탄 배치가 묶기 전 그대로 돌아오는지 보려고, 묶기 전 한 노드의 좌표를 기억해 둔다.
    const probeKey = graph.nodes[0].key;
    const flatBefore = await page.evaluate(
      (id) => (window as unknown as TestWindow).__ontologyCy.getElementById(id).position(),
      probeKey,
    );

    const startedAt = Date.now();
    await page.getByRole('button', { name: '타입 묶기' }).click();
    await expect.poll(() => groupCount(page), { timeout: 30_000 }).toBe(6);
    const elapsed = Date.now() - startedAt;
    await expect(page.getByTestId('instance-graph-laying-out')).toHaveCount(0);

    const rec = await page.evaluate(() => (window as unknown as { __wd378: ProgressRecord }).__wd378);
    // 표시가 실제로 나타났다가 사라졌고, 문구·상태 알림 속성을 갖췄다.
    expect(rec.shownAt, '「배치 중…」 표시가 나타나지 않았다').toBeDefined();
    expect(rec.hiddenAt, '「배치 중…」 표시가 사라지지 않았다').toBeDefined();
    expect(rec.text).toContain('배치 중…');
    expect(rec.ariaLive).toBe('polite');
    // 표시가 붙은 뒤 배치가 시작되기 전에 프레임이 한 번 이상 지나갔다 = 멈추기 전에 표시가 칠해졌다.
    expect(rec.layoutStartAt).toBeDefined();
    expect(
      rec.frames.some((f) => f > rec.shownAt! && f < rec.layoutStartAt!),
      '표시가 칠해지기 전에 배치가 시작됐다',
    ).toBe(true);
    // 상한 — 운영 규모(3천 노드)의 수정 전 39.6초와 달리 수 초 안에 끝나야 한다. 부하 머신을 감안해 넉넉히 잡는다.
    expect(elapsed, `묶기 소요 ${elapsed}ms`).toBeLessThan(15_000);

    // 요약 화면: 캔버스에는 타입 수만큼의 요약 노드만 있고(자식 1,075개는 올리지 않음), 라벨에 타입별 노드 수가,
    // 관계는 타입 쌍마다 하나씩(이 그래프는 5쌍)만 그려진다. 요약 노드끼리 겹치지 않는다.
    const summary = await page.evaluate(() => {
      const cy = (window as unknown as TestWindow).__ontologyCy;
      return { nodes: cy.nodes().length, edges: cy.edges().length, labels: cy.nodes('[?isGroup]').map((n) => n.data('label')) };
    });
    expect(summary.nodes).toBe(6);
    expect(summary.edges).toBe(5);
    expect(summary.labels).toContain(`Incident (${countOf(graph.nodes, 'Incident').toLocaleString()})`);
    expect(await overlappingNodePairs(page, '[?isGroup]'), '요약 노드 겹침').toEqual([]);

    // 묶기 중 검색 — 새 타입이 없으므로 재배치(layoutstop) 없이 라벨의 노드 수만 바뀌고, 자리와 분리는 유지된다.
    await countLayoutStops(page);
    const query = '1';
    const matched = graph.nodes.filter((n) => n.name.includes(query));
    await page.getByPlaceholder('이름 검색').fill(query);
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(matched.length));
    await waitPastDeferredLayout(page);
    expect(await layoutStops(page)).toBe(0);
    await expect(page.getByTestId('instance-graph-laying-out')).toHaveCount(0);
    const afterSearch = await page.evaluate(() =>
      (window as unknown as TestWindow).__ontologyCy.nodes('[?isGroup]').map((n) => n.data('label')),
    );
    expect(afterSearch).toContain(`Incident (${countOf(matched, 'Incident').toLocaleString()})`);
    expect(await overlappingNodePairs(page, '[?isGroup]'), '검색 후 요약 노드 겹침').toEqual([]);

    // 검색을 지우고 묶기를 끄면 — 요약 화면에는 자식이 없으므로 평탄 좌표 캐시가 그대로 남아 있어야 하고,
    // 전체 노드가 묶기 전 자리로 돌아온다(재배치 없이 복원).
    await page.getByPlaceholder('이름 검색').fill('');
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(graph.nodes.length));
    await page.getByRole('button', { name: '타입 묶기' }).click();
    await expect.poll(() => groupCount(page)).toBe(0);
    expect(await page.evaluate(() => (window as unknown as TestWindow).__ontologyCy.nodes().length)).toBe(
      graph.nodes.length,
    );
    const flatAfter = await page.evaluate(
      (id) => (window as unknown as TestWindow).__ontologyCy.getElementById(id).position(),
      probeKey,
    );
    expect(flatAfter.x).toBeCloseTo(flatBefore.x, 5);
    expect(flatAfter.y).toBeCloseTo(flatBefore.y, 5);
    expect(await layoutStops(page)).toBe(0);
  });
});
