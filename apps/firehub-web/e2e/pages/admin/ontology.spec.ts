import { createOntologySummaries } from '../../factories/mapping.factory';
import { createEntityTypeMutation, createOntologyGraph, createOntologySchema } from '../../factories/ontology.factory';
import {
  mockFailThenSucceed,
  mockOntologyGraph,
  setupAdminAuth,
  setupOntologyGraphErrorMock,
  setupOntologyGraphRetryMock,
  setupOntologyMocks,
} from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 지식그래프(구 온톨로지) 시각화 페이지 E2E 테스트
 * - 지식 모델(스키마)/그래프 탐색(인스턴스) 렌더, 탭 전환, 노드 클릭 드로어, 드릴다운 브리지, 범례 필터, 에러 상태를 검증한다.
 * - URL: /knowledge-graph/model(스키마=지식 모델) · /knowledge-graph/explore(인스턴스=그래프 탐색). 탭은 URL(:view)에서 파생.
 * - 페이지는 더 이상 AdminRoute로 게이팅되지 않지만(비관리자 접근은 별도 describe에서 검증), 이 블록은 ADMIN 픽스처로 유지한다.
 * - 백엔드 없이 page.route()로 /api/v1/ontology(/graph)를 모킹한다.
 * - 인스턴스 그래프는 Cytoscape.js(Canvas) 렌더 — DOM 노드가 없으므로 컨테이너의 data-node-count(필터 후 노드 수)와
 *   dev에서 노출되는 window.__ontologyCy로 검증한다. 힘-기반 레이아웃은 비결정적이라 캔버스 좌표 클릭은 쓰지 않는다.
 */
test.describe('지식그래프 시각화 페이지', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  // 인스턴스 그래프의 렌더된(필터 후) 노드 수를 data-node-count 속성으로 단언한다.
  const expectNodeCount = (page: import('@playwright/test').Page, count: number) =>
    expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(count));

  test(
    '범례 6타입 + 스키마 탭 6노드가 렌더링된다',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      await setupOntologyMocks(page);
      await page.goto('/knowledge-graph/model');

      // 타입 필터 패널: 6타입 토글 버튼 표시(리스트 영역 기준 — 검색/전체 버튼 제외)
      await expect(page.getByTestId('type-filter-panel')).toBeVisible();
      await expect(page.getByTestId('type-filter-list').getByRole('button')).toHaveCount(6);

      // 스키마 탭(기본 탭)도 Cytoscape 렌더 — 엔티티 타입 노드 6개(data-node-count로 검증)
      await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');
    },
  );

  test(
    '인스턴스 그래프 탭으로 전환하면 모킹 노드 수만큼 렌더된다',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      const graph = createOntologyGraph();
      await setupOntologyMocks(page);
      await page.goto('/knowledge-graph/model');

      await page.getByRole('tab', { name: '그래프 탐색' }).click();

      // 모킹 그래프의 노드 수(7개)만큼 Cytoscape 노드가 렌더된다(data-node-count로 검증)
      await expectNodeCount(page, graph.nodes.length);
      // 캔버스 뒤 dev 노출 cy 인스턴스로도 실제 노드/엣지 수를 교차 검증
      const counts = await page.evaluate(() => {
        const cy = (window as unknown as { __ontologyCy?: { nodes(): { length: number }; edges(): { length: number } } })
          .__ontologyCy;
        return cy ? { nodes: cy.nodes().length, edges: cy.edges().length } : null;
      });
      expect(counts).toEqual({ nodes: graph.nodes.length, edges: graph.edges.length });
    },
  );

  test('노드 클릭(tap) 시 상세 드로어에 이름과 인접 관계가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const building = graph.nodes.find((n) => n.type === 'Building')!;
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, graph.nodes.length);

    // 드로어 도킹 검증용: 선택 전 그래프 캔버스 폭을 기록한다.
    const graphBefore = await page.getByTestId('instance-graph').boundingBox();

    // Building 노드(강남타워)를 tap — canvas라 좌표 클릭 대신 cy에서 프로그래매틱 tap을 발생시킨다.
    // outgoing(HAS_EQUIPMENT)·incoming(OCCURRED_AT x2) 관계를 모두 가진다.
    await page.evaluate((key) => {
      (window as unknown as { __ontologyCy: { $(sel: string): { emit(e: string): void } } }).__ontologyCy
        .$(`#${key}`)
        .emit('tap');
    }, building.key);

    const drawer = page.getByTestId('node-detail-drawer');
    await expect(drawer).toBeVisible();

    // 도킹 회귀 방지(입력→처리→출력): 노드 선택 시 그래프가 드로어 폭만큼 줄고(min-w-0),
    // 드로어가 패널(overflow-hidden) 밖으로 잘리지 않아야 한다. toBeVisible만으로는 클리핑을 못 잡는다.
    const graphAfter = await page.getByTestId('instance-graph').boundingBox();
    const drawerBox = await drawer.boundingBox();
    const panelBox = await page.getByTestId('instance-graph-panel').boundingBox();
    expect(graphAfter!.width).toBeLessThan(graphBefore!.width);
    // 드로어 우측 끝이 패널 우측 경계 안에 있어야 한다(잘리지 않음).
    expect(drawerBox!.x + drawerBox!.width).toBeLessThanOrEqual(panelBox!.x + panelBox!.width + 1);
    // 클릭한 노드의 이름이 드로어에 표시된다
    await expect(drawer).toContainText(building.name);
    // 나가는 관계: HAS_EQUIPMENT → 스프링클러설비
    await expect(drawer).toContainText('HAS_EQUIPMENT');
    await expect(drawer).toContainText('스프링클러설비');
    // 들어오는 관계: OCCURRED_AT ← 강남구 오피스텔 화재
    await expect(drawer).toContainText('OCCURRED_AT');
  });

  // 5-4: schemaVersion이 현재 온톨로지 버전과 같으면 "적재 시점 스키마 v{N}"만 표시(구버전 강조 없음).
  test('노드의 schemaVersion이 현재 스키마와 같으면 구버전 표시 없이 버전만 노출된다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const incident1 = graph.nodes.find((n) => n.key === 'incident-1')!; // factory: schemaVersion=1
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, graph.nodes.length);

    await page.evaluate((key) => {
      (window as unknown as { __ontologyCy: { $(sel: string): { emit(e: string): void } } }).__ontologyCy
        .$(`#${key}`)
        .emit('tap');
    }, incident1.key);

    const versionBadge = page.getByTestId('node-schema-version');
    await expect(versionBadge).toHaveText('적재 시점 스키마 v1');
    await expect(versionBadge).not.toContainText('구버전');
  });

  // 5-4: schemaVersion이 없는 노드(스탬프 도입 이전 레거시 적재)는 버전 표시 자체를 생략한다.
  test('노드에 schemaVersion이 없으면(레거시) 버전 표시가 나타나지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const incident2 = graph.nodes.find((n) => n.key === 'incident-2')!; // factory: schemaVersion=null
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, graph.nodes.length);

    await page.evaluate((key) => {
      (window as unknown as { __ontologyCy: { $(sel: string): { emit(e: string): void } } }).__ontologyCy
        .$(`#${key}`)
        .emit('tap');
    }, incident2.key);

    await expect(page.getByTestId('node-detail-drawer')).toBeVisible();
    await expect(page.getByTestId('node-schema-version')).toHaveCount(0);
  });

  // 5-4: 노드의 schemaVersion이 현재 온톨로지 버전보다 낮으면 구버전 적재임을 강조 표시한다.
  test('노드의 schemaVersion이 현재 스키마보다 낮으면 구버전 표시가 강조된다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema({ schemaVersion: 2 });
    const graph = createOntologyGraph();
    const building = graph.nodes.find((n) => n.key === 'building-1')!; // factory: schemaVersion=1 (<2)
    // 인스턴스 탭의 "구버전" 배지 기준은 이제 schemaVersionByOntologyId(/ontologies 목록에서 파생) 맵이다
    // (#678) — building 노드의 ontologyId(factory: 1)로 이 맵에서 id=1 온톨로지의 schemaVersion을
    // 찾아 비교하므로, /ontologies 응답의 id=1 항목이 override된 schema(=2)와 같은 버전을 담고 있어야
    // 한다. /ontology/1은 지식 모델(스키마) 탭이 별도로 읽는 값이라 함께 모킹해 둔다.
    await mockApi(page, 'GET', '/api/v1/ontology/1', schema);
    await mockApi(
      page,
      'GET',
      '/api/v1/ontologies',
      createOntologySummaries().map((o) => (o.id === 1 ? { ...o, schemaVersion: 2 } : o)),
    );
    await mockOntologyGraph(page, graph);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, graph.nodes.length);

    await page.evaluate((key) => {
      (window as unknown as { __ontologyCy: { $(sel: string): { emit(e: string): void } } }).__ontologyCy
        .$(`#${key}`)
        .emit('tap');
    }, building.key);

    await expect(page.getByTestId('node-schema-version')).toHaveText('적재 시점 스키마 v1(구버전)');
  });

  test('인스펙터 관계 클릭 시 인접 노드로 이동한다(내비게이션)', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const building = graph.nodes.find((n) => n.type === 'Building')!;
    const equipment = graph.nodes.find((n) => n.name === '스프링클러설비')!;
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, graph.nodes.length);

    // 강남타워(Building) tap → 인스펙터 오픈(HAS_EQUIPMENT → 스프링클러설비 보유).
    await page.evaluate((key) => {
      (window as unknown as { __ontologyCy: { $(sel: string): { emit(e: string): void } } }).__ontologyCy
        .$(`#${key}`)
        .emit('tap');
    }, building.key);
    const drawer = page.getByTestId('node-detail-drawer');
    await expect(drawer).toContainText(building.name);

    // 관계 대상(스프링클러설비) 버튼 클릭 → 인스펙터가 대상 노드로 갱신(타입 헤더 Equipment).
    await drawer.getByRole('button', { name: '스프링클러설비' }).click();
    await expect(drawer.getByRole('heading')).toContainText('Equipment');
    await expect(drawer).toContainText('스프링클러설비');

    // 캔버스 선택도 대상 노드로 이동한다(입력→처리→출력).
    const selectedId = await page.evaluate(() => {
      const cy = (window as unknown as { __ontologyCy: { $(s: string): { length: number; map(f: (e: { id(): string }) => string): string[] } } })
        .__ontologyCy;
      const sel = cy.$('node:selected');
      return sel.length ? sel.map((e) => e.id())[0] : null;
    });
    expect(selectedId).toBe(equipment.key);
  });

  test('인스펙터 좌측 핸들 드래그로 폭을 조절한다', async ({ authenticatedPage: page }) => {
    const graph = createOntologyGraph();
    const building = graph.nodes.find((n) => n.type === 'Building')!;
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, graph.nodes.length);

    await page.evaluate((key) => {
      (window as unknown as { __ontologyCy: { $(sel: string): { emit(e: string): void } } }).__ontologyCy
        .$(`#${key}`)
        .emit('tap');
    }, building.key);
    const drawer = page.getByTestId('node-detail-drawer');
    await expect(drawer).toBeVisible();

    // 좌측 리사이즈 핸들을 왼쪽으로 끌면 폭이 늘어난다(오른쪽 도킹 패널).
    const before = (await drawer.boundingBox())!.width;
    const handle = drawer.getByRole('separator', { name: '인스펙터 폭 조절' });
    const hb = (await handle.boundingBox())!;
    await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2);
    await page.mouse.down();
    await page.mouse.move(hb.x - 120, hb.y + hb.height / 2, { steps: 8 });
    await page.mouse.up();
    const after = (await drawer.boundingBox())!.width;
    expect(after).toBeGreaterThan(before + 50);
  });

  test('노드 hover 시 이웃 아닌 요소가 흐려진다(스포트라이트)', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const building = graph.nodes.find((n) => n.type === 'Building')!;
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, graph.nodes.length);

    // canvas라 좌표 hover 대신 cy에서 mouseover를 프로그래매틱 발생.
    await page.evaluate((key) => {
      (window as unknown as { __ontologyCy: { $(s: string): { emit(e: string): void } } }).__ontologyCy
        .$(`#${key}`)
        .emit('mouseover');
    }, building.key);

    // 호버 노드의 닫힌 이웃은 안 흐려지고, 그 외 노드는 모두 .faded.
    const state = await page.evaluate((key) => {
      type Ele = { hasClass(c: string): boolean };
      type Coll = { length: number; every(f: (e: Ele) => boolean): boolean; some(f: (e: Ele) => boolean): boolean };
      const cy = (window as unknown as {
        __ontologyCy: {
          getElementById(id: string): { hasClass(c: string): boolean; closedNeighborhood(): unknown };
          nodes(): Coll & { difference(o: unknown): Coll };
        };
      }).__ontologyCy;
      const hovered = cy.getElementById(key);
      const others = cy.nodes().difference(hovered.closedNeighborhood());
      return {
        anyOthers: others.length > 0,
        allOthersFaded: others.length > 0 && others.every((n) => n.hasClass('faded')),
        hoveredFaded: hovered.hasClass('faded'),
        hoveredSpotlight: hovered.hasClass('spotlight'),
      };
    }, building.key);
    expect(state.anyOthers).toBe(true);
    expect(state.allOthersFaded).toBe(true);
    expect(state.hoveredFaded).toBe(false);
    expect(state.hoveredSpotlight).toBe(true);

    // mouseout → 모든 fade 해제.
    await page.evaluate((key) => {
      (window as unknown as { __ontologyCy: { $(s: string): { emit(e: string): void } } }).__ontologyCy
        .$(`#${key}`)
        .emit('mouseout');
    }, building.key);
    const anyFaded = await page.evaluate(() => {
      const cy = (window as unknown as { __ontologyCy: { elements(): { some(f: (e: { hasClass(c: string): boolean }) => boolean): boolean } } })
        .__ontologyCy;
      return cy.elements().some((e) => e.hasClass('faded') || e.hasClass('spotlight'));
    });
    expect(anyFaded).toBe(false);
  });

  test('타입 묶기 토글 시 타입별 compound 번들로 접힌다', async ({ authenticatedPage: page }) => {
    const graph = createOntologyGraph();
    const typeCount = new Set(graph.nodes.map((n) => n.type)).size;
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, graph.nodes.length);

    type IsGroupCy = { nodes(sel?: string): { length: number } };
    const groupCount = () =>
      page.evaluate(() => (window as unknown as { __ontologyCy: IsGroupCy }).__ontologyCy.nodes('[?isGroup]').length);

    // 묶기 전: 평면(부모 없음).
    expect(await groupCount()).toBe(0);

    // 타입 묶기 ON → layoutstop 후 collapseAll. 접힘 완료(부모 수=타입 수)까지 폴링.
    await page.getByRole('button', { name: '타입 묶기' }).click();
    await expect.poll(groupCount).toBe(typeCount);

    // 각 타입이 번들(collapsed 메타노드)로 축약되어, 보이는 노드 수가 타입 수로 줄어든다(실노드 숨김 = 밀집 감소).
    const after = await page.evaluate(() => {
      const cy = (window as unknown as { __ontologyCy: IsGroupCy }).__ontologyCy;
      return {
        groups: cy.nodes('[?isGroup]').length,
        collapsed: cy.nodes('.cy-expand-collapse-collapsed-node').length,
        total: cy.nodes().length,
      };
    });
    expect(after.groups).toBe(typeCount);
    expect(after.collapsed).toBe(typeCount);
    expect(after.total).toBe(typeCount);

    // 타입 묶기 OFF → 다시 평면(부모 0, 실노드 전량 복원).
    await page.getByRole('button', { name: '타입 묶기' }).click();
    await expect.poll(groupCount).toBe(0);
    const flatTotal = await page.evaluate(
      () => (window as unknown as { __ontologyCy: IsGroupCy }).__ontologyCy.nodes().length,
    );
    expect(flatTotal).toBe(graph.nodes.length);
  });

  // 묶기 모드의 레이아웃이 compound를 인지하지 못하면 타입 덩어리들이 한 점에 포개져, 접어도
  // "어느 덩어리가 어느 타입인지" 구분할 수 없게 된다(평탄 모드용 euler로 묶기까지 처리했을 때
  // 실제로 발생: 메타노드 최소 중심간 거리 47px, 타입 박스 21쌍이 전부 절반 이상 겹침).
  // 위 토글 테스트는 "개수"만 세므로 이 증상을 통과시켜 버린다 — 여기서는 "간격"을 단언한다.
  test('타입 묶기 시 접힌 메타노드들이 서로 겹치지 않고 분리된다', async ({ authenticatedPage: page }) => {
    // 기본 목 그래프(노드 수 한 자릿수)로는 이 증상이 재현되지 않는다 — 노드가 적으면 어떻게
    // 배치해도 서로 떨어지기 때문. 타입마다 노드를 충분히 넣고 관계를 "타입을 가로질러" 걸어,
    // 타입 개념이 없는 배치가 돌면 타입끼리 뒤섞이도록 만든 전용 그래프를 쓴다.
    const bundleTypes = ['Incident', 'Damage', 'Cause', 'Building', 'Equipment', 'Regulation'] as const;
    const PER_TYPE = 25;
    const nodes = bundleTypes.flatMap((type, ti) =>
      Array.from({ length: PER_TYPE }, (_, i) => ({
        key: `bundle-${ti}-${i}`,
        type,
        name: `${type} 노드 ${i}`,
        sourceChunkCount: 1,
        schemaVersion: 1,
      })),
    );
    // 관계는 전부 다른 타입 사이에만 건다 — 같은 타입끼리 끌어당기는 힘이 하나도 없는 조건이라,
    // compound를 인지하지 못하거나 노드가 잠겨 못 움직이면 타입 덩어리가 곧바로 포개진다.
    const edges = nodes.map((n, i) => ({
      subjectKey: n.key,
      type: 'RELATES_TO',
      objectKey: nodes[(i + PER_TYPE + 7) % nodes.length].key,
    }));
    const graph = { nodes, edges };
    const typeCount = bundleTypes.length;
    await setupOntologyMocks(page);
    await mockOntologyGraph(page, graph);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, graph.nodes.length);

    type MetaCy = {
      nodes(sel?: string): {
        length: number;
        map<T>(f: (n: { position(): { x: number; y: number }; width(): number }) => T): T[];
      };
    };
    const groupCount = () =>
      page.evaluate(() => (window as unknown as { __ontologyCy: MetaCy }).__ontologyCy.nodes('[?isGroup]').length);

    await page.getByRole('button', { name: '타입 묶기' }).click();
    await expect.poll(groupCount).toBe(typeCount);

    // 접힌 메타노드들의 중심 좌표와 지름을 읽어 쌍별 거리를 계산한다.
    const metas = await page.evaluate(() =>
      (window as unknown as { __ontologyCy: MetaCy }).__ontologyCy
        .nodes('.cy-expand-collapse-collapsed-node')
        .map((n) => ({ x: n.position().x, y: n.position().y, w: n.width() })),
    );
    expect(metas.length).toBe(typeCount);

    // 두 메타노드의 중심 거리가 반지름 합보다 짧으면 화면에서 원이 겹친다 — 한 쌍도 없어야 한다.
    const overlapping: string[] = [];
    for (let i = 0; i < metas.length; i++) {
      for (let j = i + 1; j < metas.length; j++) {
        const dist = Math.hypot(metas[i].x - metas[j].x, metas[i].y - metas[j].y);
        if (dist < (metas[i].w + metas[j].w) / 2) overlapping.push(`${i}-${j}(${Math.round(dist)}px)`);
      }
    }
    expect(overlapping, `겹친 메타노드 쌍: ${overlapping.join(', ')}`).toEqual([]);
  });

  // (#496) 검색/타입 필터 조작이 레이아웃을 전체 재실행해, 드래그로 옮긴 노드 위치가 필터를
  // 만질 때마다 초기화되던 회귀 가드. graph 참조가 그대로인(=같은 데이터셋) 필터 재렌더에서는
  // 기존 좌표가 그대로 보존돼야 한다(구현상 기존 노드를 lock()으로 고정해 신규 노드만 배치한다).
  // 힘-기반 최초 배치는 비결정적이므로 절대 좌표를 단언하지 않고, "드래그로 직접 설정한 좌표가
  // 필터 조작 후에도 그대로인가"만 검증한다.
  test('노드를 드래그로 옮긴 뒤 검색/타입 필터를 조작해도 위치가 유지된다(#496)', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, graph.nodes.length);

    type PositionCy = {
      getElementById(id: string): {
        empty(): boolean;
        position(pos?: { x: number; y: number }): { x: number; y: number };
      };
    };
    const targetKey = graph.nodes[0].key;
    const draggedPosition = { x: 321.5, y: -87.25 };

    // 드래그를 시뮬레이션 — 사용자가 마우스로 옮긴 결과와 동일하게 cy 노드 좌표를 직접 설정한다.
    await page.evaluate(
      ({ id, pos }) => {
        (window as unknown as { __ontologyCy: PositionCy }).__ontologyCy.getElementById(id).position(pos);
      },
      { id: targetKey, pos: draggedPosition },
    );

    // 이름 검색 한 글자 입력 — 필터로 인한 재렌더를 유발한다(수정 전에는 여기서 레이아웃이 랜덤 재배치됐다).
    await page.getByPlaceholder('이름 검색').fill(graph.nodes[0].name[0]);
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', /^[1-9]/);

    const afterSearch = await page.evaluate(
      (id) => (window as unknown as { __ontologyCy: PositionCy }).__ontologyCy.getElementById(id).position(),
      targetKey,
    );
    expect(afterSearch.x).toBeCloseTo(draggedPosition.x, 5);
    expect(afterSearch.y).toBeCloseTo(draggedPosition.y, 5);

    // 검색 초기화 후 타입 필터도 조작 — 마찬가지로 위치가 유지돼야 한다.
    await page.getByPlaceholder('이름 검색').fill('');
    await expectNodeCount(page, graph.nodes.length);
    const otherType = graph.nodes.find((n) => n.type !== graph.nodes[0].type)?.type;
    if (otherType) {
      const panel = page.getByTestId('type-filter-panel');
      await panel.getByRole('button', { name: new RegExp(otherType) }).click();
    }

    const afterFilter = await page.evaluate(
      (id) => (window as unknown as { __ontologyCy: PositionCy }).__ontologyCy.getElementById(id).position(),
      targetKey,
    );
    expect(afterFilter.x).toBeCloseTo(draggedPosition.x, 5);
    expect(afterFilter.y).toBeCloseTo(draggedPosition.y, 5);
  });

  // (#508) 사건→피해→원인→시설→장비→법규처럼 순차적으로 이어지는 체인형(선형) 위상의 그래프에서
  // 노드 전체가 하나의 대각선(y≈x+c) 위에 늘어놓이던 회귀 가드. 원인은 당시 쓰던 fcose의 spectral
  // (라플라시안 고유벡터) 초기 배치였고, 현재 레이아웃(euler)에는 그 단계 자체가 없다. 엔진을 바꿔도
  // 증상이 돌아오지 않는지 지키기 위해 테스트는 엔진에 의존하지 않고 결과 좌표만 본다 — 30노드 체인
  // 그래프를 모킹해, 렌더된 좌표들이 한 직선 위에 몰리지 않고(=x-y 편차가 노드마다 달라야 함)
  // 2차원으로 퍼져 있는지 검증한다.
  test('체인형 위상 그래프(30노드)가 기본 진입 시 하나의 대각선으로 붕괴하지 않는다(#508)', async ({
    authenticatedPage: page,
  }) => {
    // Incident → Damage → Cause → Building → Equipment → Regulation → (다음 Incident) 순으로
    // 이어지는 순차 체인 30개 노드 — 이슈에서 보고된 실제 데이터셋(30노드/32관계, 체인형 위상)을 재현한다.
    const chainTypes = ['Incident', 'Damage', 'Cause', 'Building', 'Equipment', 'Regulation'] as const;
    const nodes = Array.from({ length: 30 }, (_, i) => ({
      key: `chain-${i}`,
      type: chainTypes[i % chainTypes.length],
      name: `노드 ${i}`,
      sourceChunkCount: 1,
      schemaVersion: 1,
    }));
    const edges = Array.from({ length: 29 }, (_, i) => ({
      subjectKey: `chain-${i}`,
      type: 'NEXT',
      objectKey: `chain-${i + 1}`,
    }));
    await setupOntologyMocks(page);
    await mockOntologyGraph(page, { nodes, edges });
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, nodes.length);

    // layoutstop을 기다린 뒤 좌표를 읽는다 — 비동기 레이아웃이 끝나기 전에 읽으면 아직 초기값일 수 있다.
    await page.waitForFunction(() => {
      const cy = (window as unknown as { __ontologyCy?: { nodes(): { length: number } } }).__ontologyCy;
      return !!cy && cy.nodes().length === 30;
    });
    await page.waitForTimeout(500); // layoutstop 콜백(unlock 등) 완료 대기 — 이벤트 훅이 없어 짧게 대기.

    type PositionsCy = { nodes(): { map<T>(f: (n: { position(): { x: number; y: number } }) => T): T[] } };
    const positions = await page.evaluate(
      () => (window as unknown as { __ontologyCy: PositionsCy }).__ontologyCy.nodes().map((n) => n.position()),
    );

    expect(positions).toHaveLength(30);
    // 회귀 시 모든 노드의 (x - y) 편차가 사실상 상수(대각선 y=x+c)였다 — 정상이라면 노드마다 편차가
    // 크게 달라야 한다(전부 같은 직선 위에 있지 않음).
    const residuals = positions.map((p) => p.x - p.y);
    const maxResidual = Math.max(...residuals);
    const minResidual = Math.min(...residuals);
    expect(maxResidual - minResidual).toBeGreaterThan(50);
    // 각 축의 좌표 분산도 있어야 한다(한 점에 뭉치거나 한 축으로만 퍼지는 것도 방지).
    const xs = positions.map((p) => p.x);
    const ys = positions.map((p) => p.y);
    expect(Math.max(...xs) - Math.min(...xs)).toBeGreaterThan(50);
    expect(Math.max(...ys) - Math.min(...ys)).toBeGreaterThan(50);
  });

  test('스키마 탭에서 Incident 타입 클릭 시 인스턴스 탭으로 드릴다운되어 Incident 노드만 표시된다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const incidentCount = graph.nodes.filter((n) => n.type === 'Incident').length;
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    // 스키마 탭의 'Incident' 타입 노드 tap → 드릴다운 브리지.
    // 스키마도 Cytoscape(canvas)라 좌표 클릭 대신 cy에서 프로그래매틱 tap을 발생시킨다.
    // 캔버스 노드 id는 이제 타입 이름이 아니라 entityTypeId다(S3 Task 1) — id 값은 fixture 배정에
    // 우연히 결합된 디테일이라 label(타입 이름) 속성으로 노드를 찾는다.
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');
    await page.evaluate(() => {
      (window as unknown as { __ontologySchemaCy: { nodes(sel: string): { emit(e: string): void } } }).__ontologySchemaCy
        .nodes('[label = "Incident"]')
        .emit('tap');
    });

    // 인스턴스 탭이 활성화되어야 한다
    await expect(page.getByRole('tab', { name: '그래프 탐색' })).toHaveAttribute('aria-selected', 'true');
    // Incident 타입 노드만 필터되어 표시되어야 한다 (모킹 그래프 기준 2개)
    await expectNodeCount(page, incidentCount);
  });

  test('범례에서 타입 버튼 클릭 시 그 타입만 숨겨지고 나머지는 그대로 표시된다(#404)', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const buildingCount = graph.nodes.filter((n) => n.type === 'Building').length;
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();

    // 초기: 전체 노드(7개) 표시, 모든 타입 버튼이 pressed(빈 activeTypes = 전체 활성).
    await expectNodeCount(page, graph.nodes.length);
    const panel = page.getByTestId('type-filter-panel');
    const buildingButton = panel.getByRole('button', { name: /Building/ });
    await expect(buildingButton).toHaveAttribute('aria-pressed', 'true');

    // (#404 회귀 가드) 기본(전체 표시) 상태에서 pressed인 'Building' 버튼을 클릭하면 "그 타입만
    // 남기고 전부 숨김"이 아니라 "그 타입 하나만 숨김"이어야 한다 — 나머지 타입 노드는 그대로 남는다.
    await buildingButton.click();
    await expect(buildingButton).toHaveAttribute('aria-pressed', 'false');
    await expectNodeCount(page, graph.nodes.length - buildingCount);

    // 다시 클릭하면 전체 타입이 모두 켜진 상태로 복귀 → "빈 Set = 전체" 관례에 따라 전체 노드가 되돌아온다.
    await buildingButton.click();
    await expect(buildingButton).toHaveAttribute('aria-pressed', 'true');
    await expectNodeCount(page, graph.nodes.length);
  });

  test('타입 필터 패널 — resolution 그룹 헤더 표시 + "전체" 리셋 복원', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const buildingCount = graph.nodes.filter((n) => n.type === 'Building').length;
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();
    await expectNodeCount(page, graph.nodes.length);

    // resolution별 그룹 헤더가 노출된다(정확 매칭 / 임베딩 해소).
    const panel = page.getByTestId('type-filter-panel');
    await expect(panel.getByText('정확 매칭')).toBeVisible();
    await expect(panel.getByText('임베딩 해소')).toBeVisible();

    // 타입 토글 시 '전체' 리셋 버튼이 등장하고, 클릭하면 전체 필터로 복원된다(입력→처리→출력).
    // 기본(전체 표시) 상태의 토글은 "그 타입만 숨김"이므로(#404), Building을 끄면 Building을
    // 제외한 나머지가 남는다.
    await expect(panel.getByRole('button', { name: '전체' })).toHaveCount(0);
    await panel.getByRole('button', { name: /Building/ }).click();
    await expectNodeCount(page, graph.nodes.length - buildingCount);
    const reset = panel.getByRole('button', { name: '전체' });
    await expect(reset).toBeVisible();
    await reset.click();
    await expectNodeCount(page, graph.nodes.length);
  });

  // (#677) 그래프 탐색 탭도 지식 모델 탭과 동일하게 온톨로지 선택기를 공유한다 — 예전에는 타입
  // 필터가 하드코딩된 기본 온톨로지(id=1) 스키마에 고정돼 다른 온톨로지의 타입이 필터에 아예
  // 나타나지 않았다. 이제 선택기가 그래프 탐색 탭에도 노출되고, 선택을 바꾸면 타입 필터뿐 아니라
  // 캔버스도 그 온톨로지로 바뀐다.
  //
  // 스코프 주체가 클라이언트에서 서버로 옮겨간 것이 이 테스트의 핵심이다 — 예전에는 무스코프 전체
  // 그래프를 받아 node.ontologyId로 화면에서 걸렀다(그래서 남의 테넌트 그래프가 네트워크로 그대로
  // 내려왔다). 이제 온톨로지마다 별도 경로(/ontology/{id}/graph)로 조회하므로, 선택을 바꾸면 그
  // 온톨로지의 경로로 실제 재조회가 일어나야 한다 — 요청 경로까지 단언해 캐시 키 고정 회귀를 막는다.
  test('그래프 탐색 탭에서 온톨로지를 바꾸면 그 온톨로지 경로로 그래프를 재조회하고 캔버스·타입 필터가 함께 바뀐다(#677)', async ({
    authenticatedPage: page,
  }) => {
    const secondOntologySchema = createOntologySchema({
      domain: '건축물 대장',
      entities: [
        { type: 'Zoning', description: '용도지역', naming: '본문 표기 보존', resolution: 'exact', properties: [] },
        { type: 'Permit', description: '인허가', naming: '본문 표기 보존', resolution: 'embedding', properties: [] },
      ],
      relations: [],
    });
    // 서버가 온톨로지별로 다른 그래프를 내려주는 상황을 모킹한다 — 같은 응답을 주면 "재조회했는가"는
    // 검증되지만 "그 응답이 화면에 반영되는가"는 검증되지 않는다(캐시 키가 고정돼도 통과해 버린다).
    const firstGraph = createOntologyGraph();
    const secondGraph = {
      nodes: [
        { key: 'zoning:1', type: 'Zoning', name: '제1종일반주거', sourceChunkCount: 1, ontologyId: 2 },
        { key: 'permit:1', type: 'Permit', name: '건축허가', sourceChunkCount: 1, ontologyId: 2 },
        { key: 'permit:2', type: 'Permit', name: '사용승인', sourceChunkCount: 1, ontologyId: 2 },
      ],
      edges: [],
    };
    const { requestedPaths } = await mockOntologyGraph(page, (pathname) =>
      pathname === '/api/v1/ontology/2/graph' ? secondGraph : firstGraph,
    );
    await mockApi(page, 'GET', '/api/v1/ontology/1', createOntologySchema());
    await mockApi(page, 'GET', '/api/v1/ontology/2', secondOntologySchema);
    // createOntologySummaries() 기본값 — id=1(active, 기본) + id=2(active, 기본 아님).
    await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
    await page.goto('/knowledge-graph/explore');

    // 기본 선택(첫 active 온톨로지, id=1) 기준 6타입 + id=1 경로로 받은 그래프.
    const typeList = page.getByTestId('type-filter-list');
    await expect(typeList.getByRole('button')).toHaveCount(6);
    await expectNodeCount(page, firstGraph.nodes.length);
    expect(requestedPaths).toEqual(['/api/v1/ontology/1/graph']);

    // 온톨로지 선택기는 그래프 탐색 탭에도 노출된다 — 지식 모델 탭 전용이 아니다.
    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: '건축물 대장' }).click();

    // id=2로 바꾸면 타입 필터가 그 온톨로지의 타입(2개)으로 완전히 바뀐다.
    await expect(typeList.getByRole('button')).toHaveCount(2);
    await expect(typeList.getByRole('button', { name: /^Zoning/ })).toBeVisible();
    await expect(typeList.getByRole('button', { name: /^Permit/ })).toBeVisible();
    await expect(typeList.getByRole('button', { name: /^Incident/ })).toHaveCount(0);
    // 캔버스는 id=2 경로로 새로 받아온 그래프(3노드)를 그린다 — 이전 그래프를 걸러낸 결과가 아니다.
    await expectNodeCount(page, secondGraph.nodes.length);
    expect(requestedPaths).toContain('/api/v1/ontology/2/graph');
  });

  // 테넌트에 지식 모델이 하나도 없을 때. 예전에는 (1) 마운트 즉시 무스코프 전체 그래프를 조회하고
  // (2) 선택된 온톨로지가 없으면(effectiveOntologyId == null) 클라이언트 필터를 통째로 건너뛰어,
  // 다른 테넌트가 적재한 그래프가 그대로 캔버스에 렌더됐다. 지금은 조회 자체가 일어나지 않아야 한다.
  //
  // "요청이 0건"까지 단언하는 것이 이 테스트의 핵심이다 — 화면만 비우는 수정(빈 상태 렌더)으로는
  // 크로스테넌트 페이로드가 여전히 브라우저까지 내려오고, DevTools/네트워크 탭에서 그대로 읽힌다.
  test('지식 모델이 하나도 없으면 그래프를 조회하지 않고 빈 상태를 보여준다', async ({
    authenticatedPage: page,
  }) => {
    // 실제로 호출되면 응답까지 주어(200) 화면이 그려지므로, 아래 빈 상태 단언과 요청 0건 단언이
    // 서로를 보강한다. 매처가 id 자리를 숫자로 좁히지 않는 이유는 ONTOLOGY_GRAPH_PATH 주석 참고.
    const { requestedPaths } = await mockOntologyGraph(page, createOntologyGraph());
    await mockApi(page, 'GET', '/api/v1/ontologies', []); // 지식 모델 0개인 테넌트
    await page.goto('/knowledge-graph/explore');

    await expect(page.getByText('아직 지식 모델이 없습니다')).toBeVisible();
    // 캔버스 자체가 렌더되지 않는다 — 빈 그래프를 그리는 것이 아니라 그릴 대상이 없는 상태다.
    // (남의 그래프가 새어 들어와 노드가 그려지면 이 단언이 깨진다.)
    await expect(page.getByTestId('instance-graph')).toHaveCount(0);
    // 그래프 엔드포인트를 아예 부르지 않았다 — 고를 온톨로지가 없으면 물어보지도 않는다.
    expect(requestedPaths).toEqual([]);
  });

  // 지식 모델(스키마) 탭도 같은 상태를 말해야 한다. 예전엔 목록이 비어 selectedOntology 가 영원히
  // undefined 라 로딩 스켈레톤에 갇혀 "없다"는 말도, 만들 길도 없는 빈 캔버스만 남았다.
  test('지식 모델이 하나도 없으면 지식 모델 탭에 빈 상태와 생성 CTA를 보여준다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await mockApi(page, 'GET', '/api/v1/ontologies', []); // 지식 모델 0개인 테넌트
    await page.goto('/knowledge-graph/model');

    await expect(page.getByText('아직 지식 모델이 없습니다')).toBeVisible();
    // 끝나지 않는 로딩이 아니다 — 스켈레톤은 사라져 있어야 한다.
    await expect(page.getByTestId('graph-loading')).toHaveCount(0);
    // 빈 상태의 CTA 가 툴바의 "새 온톨로지"와 같은 생성 다이얼로그를 연다.
    await page.getByTestId('instance-graph-panel').getByRole('button', { name: '새 온톨로지' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
  });

  // "지식 모델 없음" 판정은 목록이 도착해서 실제로 비어 있을 때만 내려야 한다. 목록이 아직
  // 오지 않았을 때도 effectiveOntologyId 는 null 이므로, 그 둘을 구분하지 않으면 매 첫 진입마다
  // "없습니다"를 단정해 보였다가 뒤늦게 그래프로 바뀐다(잘못된 상태 깜빡임).
  test('온톨로지 목록이 아직 도착하지 않은 동안에는 "지식 모델 없음"을 단정하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    // 목록 응답을 붙잡아 둔 채로 진입한다 — 이 구간이 "로딩 중"이다. 기본 모킹 뒤에 등록해
    // 목록 라우트만 덮어쓴다(나중 라우트가 앞을 가린다).
    let releaseList: (() => void) | null = null;
    const listHeld = new Promise<void>((resolve) => {
      releaseList = resolve;
    });
    await page.route(
      (url) => url.pathname === '/api/v1/ontologies',
      async (route) => {
        await listHeld;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createOntologySummaries()),
        });
      },
    );
    await page.goto('/knowledge-graph/explore');

    // 목록이 오기 전에는 로딩 스켈레톤이어야 한다. 여기서 "빈 상태 문구가 없다"(toHaveCount(0))로만
    // 단언하면 렌더가 시작되기도 전에 통과해 버려 공허해진다 — 로딩 상태를 긍정으로 지목한다.
    await expect(page.getByTestId('graph-loading')).toBeVisible();
    await expect(page.getByText('아직 지식 모델이 없습니다')).toHaveCount(0);

    // 목록을 풀어 주면 정상적으로 그래프가 그려진다.
    releaseList!();
    await expect(page.getByTestId('instance-graph')).toBeVisible();
    await expect(page.getByText('아직 지식 모델이 없습니다')).toHaveCount(0);
  });

  // 목록 조회가 실패한 것도 "0개"와 구분해야 한다 — 구분하지 않으면 사용자가 "지식 모델이 없다"는
  // 오답에 영구히 갇힌다(그래프 쿼리의 재시도 버튼은 그 쿼리가 실행조차 되지 않아 나타나지 않는다).
  test('온톨로지 목록 조회가 실패하면 "없음"이 아니라 에러와 재시도를 보여준다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    // 기본 모킹 뒤에 목록 라우트만 덮어쓴다 — 실패 후 성공 관용구는 픽스처 헬퍼가 갖고 있다.
    await mockFailThenSucceed(page, '/api/v1/ontologies', {
      errorBody: { message: '목록 조회 실패' },
      okBody: createOntologySummaries(),
    });
    await page.goto('/knowledge-graph/explore');

    await expect(page.getByText('지식 모델 목록을 불러오지 못했습니다.')).toBeVisible();
    await expect(page.getByText('아직 지식 모델이 없습니다')).toHaveCount(0);

    // 재시도가 실제로 회복 경로다 — 누르면 목록이 로드되고 그래프가 그려진다.
    await page.getByRole('button', { name: '다시 시도' }).click();
    await expect(page.getByTestId('instance-graph')).toBeVisible();
  });

  // (#407) 빈 초안 온톨로지로 전환하면 좌측 타입 필터 패널도 진짜로 비어야 한다 — 예전에는
  // entities.length===0을 "로딩 중"과 동일하게 취급해 이전 온톨로지의 데모 타입 6종(색상표 키)을
  // 그대로 남겨 보여줬다(실재하지 않는 타입이 존재하는 것처럼 보이는 회귀).
  test('빈 초안 온톨로지로 전환하면 타입 필터 패널에 이전 온톨로지의 타입이 남지 않는다(#407)', async ({
    authenticatedPage: page,
  }) => {
    const emptySchema = createOntologySchema({ domain: '테스트', entities: [], relations: [] });
    await mockApi(page, 'GET', '/api/v1/ontology/1', createOntologySchema());
    await mockApi(page, 'GET', '/api/v1/ontology/2', emptySchema);
    await mockOntologyGraph(page, createOntologyGraph());
    await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
    await page.goto('/knowledge-graph/model');

    // 전환 전: 기본 온톨로지(id=1) 기준 6타입이 보인다.
    await expect(page.getByTestId('type-filter-list').getByRole('button')).toHaveCount(6);

    // 빈 초안 온톨로지(id=2)로 전환.
    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: '건축물 대장' }).click();

    // 좌측 타입 필터 패널에는 이전 온톨로지의 데모 타입이 하나도 남지 않아야 한다(토글 버튼 0개).
    await expect(page.getByTestId('type-filter-list').getByRole('button')).toHaveCount(0);
    await expect(page.getByTestId('type-filter-list').getByText('Incident')).toHaveCount(0);
  });

  test('인스턴스 그래프 조회 실패 시 에러 문구가 표시되고 페이지가 크래시하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyGraphErrorMock(page);
    await page.goto('/knowledge-graph/model');

    // 스키마는 정상 로드되어 타입 필터/스키마 탭은 그대로 동작한다
    await expect(page.getByTestId('type-filter-panel')).toBeVisible();

    await page.getByRole('tab', { name: '그래프 탐색' }).click();

    // 인스턴스 그래프 API 500 응답 시 에러 문구가 표시된다
    await expect(page.getByText('그래프를 불러오지 못했습니다.')).toBeVisible();
    // 그래프 캔버스는 렌더되지 않는다(크래시 없이 안전하게 폴백)
    await expect(page.getByTestId('instance-graph')).toHaveCount(0);
  });

  test('인스턴스 그래프 에러 후 "다시 시도" 클릭 시 재요청하여 그래프가 정상 렌더된다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    // 초기 로드는 실패시키고 이후 refetch부터 성공하는 모킹 — 재요청 횟수를 카운터로 추적.
    const counter = await setupOntologyGraphRetryMock(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('tab', { name: '그래프 탐색' }).click();

    // 초기 로드 실패 → 에러 문구 + 재시도 버튼 노출
    await expect(page.getByText('그래프를 불러오지 못했습니다.')).toBeVisible();
    const retryButton = page.getByRole('button', { name: '다시 시도' });
    await expect(retryButton).toBeVisible();

    // 재시도 이전까지의 요청 수 기록(최초 요청 + 자동 재시도 1회 = 2회)
    const callsBeforeRetry = counter.calls;
    expect(callsBeforeRetry).toBeGreaterThanOrEqual(1);

    // "다시 시도" 클릭 → 그래프 API 재요청 → 성공 응답으로 노드 렌더
    await retryButton.click();

    // 에러 문구가 사라지고 모킹 그래프의 노드 수(7개)만큼 렌더된다
    await expect(page.getByText('그래프를 불러오지 못했습니다.')).toHaveCount(0);
    await expectNodeCount(page, graph.nodes.length);
    // 실제 재요청이 발생했음을 검증(입력→처리→출력: 클릭 → API 재호출 → UI 반영)
    expect(counter.calls).toBeGreaterThan(callsBeforeRetry);
  });
});

/**
 * 지식그래프 IA — 접근 제어 완화 검증 (증분 4)
 * - 페이지가 관리(admin) 하위 → 최상위 '지식그래프' 그룹으로 이동하며 AdminRoute 밖으로 나왔다.
 * - 여기서는 setupAdminAuth를 호출하지 않는다 → 기본 authenticatedPage는 USER 역할(비관리자)이다.
 *   따라서 이 블록은 "비관리자도 접근 가능"이라는 완화 자체를 검증한다.
 *   (백엔드도 V2에서 USER 역할에 dataset:read를 부여하므로 실제 프로덕션에서도 접근 가능 — 무변경.)
 */
test.describe('지식그래프 IA — 비관리자 접근/리다이렉트', () => {
  test('비관리자(USER)도 /knowledge-graph/explore에 접근해 그래프 탐색이 렌더된다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/explore');

    // AdminRoute였다면 '/'로 튕겼겠지만, 이제 인증만으로 렌더된다.
    await expect(page.getByTestId('type-filter-panel')).toBeVisible();
    // explore = 그래프 탐색(인스턴스) 탭이 기본 활성.
    await expect(page.getByRole('tab', { name: '그래프 탐색' })).toHaveAttribute('aria-selected', 'true');
  });

  test('구 URL /admin/ontology는 비관리자도 /knowledge-graph/explore로 리다이렉트된다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/admin/ontology');

    // 리다이렉트가 AdminRoute 밖에 있으므로 비관리자도 '/'로 튕기지 않고 지식그래프로 이동한다.
    await expect(page).toHaveURL(/\/knowledge-graph\/explore$/);
    await expect(page.getByTestId('type-filter-panel')).toBeVisible();
  });
});

/**
 * 그래프 캔버스 키보드·스크린리더 접근성 회귀 (#326, #327)
 * - #326: Cytoscape 캔버스는 <canvas>만 그려 탭 스톱·대체 텍스트가 0이었다 → 필터/검색 결과와 동기화되는
 *   대체 노드 목록(GraphKeyboardList)을 렌더해 마우스 없이 노드 선택 → 상세 드로어까지 도달 가능해야 한다.
 * - #327: 접힌 타입 필터 패널이 aria-hidden만 갖고 있어 보이지 않는 탭 스톱 7개가 남았다 → inert로 차단해야 한다.
 */
test.describe('지식그래프 캔버스 키보드 접근성 (#326, #327)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('그래프 탐색 탭의 대체 노드 목록이 전체 노드·관계 수를 접근 가능한 이름으로 노출한다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/explore');
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(graph.nodes.length));

    // 캔버스 요약이 접근 가능한 이름으로 제공된다(SC 1.1.1 대체 텍스트).
    const list = page.getByTestId('instance-graph-node-list');
    await expect(list).toHaveAttribute(
      'aria-label',
      `지식그래프 노드 ${graph.nodes.length}개, 관계 ${graph.edges.length}개`,
    );
    // 노드 하나당 활성화 가능한 항목 1개 — 이름·타입·인접 관계 수가 텍스트로 읽힌다.
    await expect(list.locator('[data-graph-item]')).toHaveCount(graph.nodes.length);
    // incident-1은 OCCURRED_AT/CAUSED_BY/RESULTED_IN/VIOLATED 4개 관계를 갖는다.
    await expect(list.locator('[data-graph-item]').first()).toHaveText(
      `${graph.nodes[0].name} (Incident) — 관계 4개`,
    );
    // 캔버스 자체는 대체 텍스트가 없으므로 접근성 트리에서 제외되어야 한다.
    await expect(page.getByTestId('instance-graph').locator('> div[aria-hidden="true"]')).toHaveCount(1);
  });

  test('키보드만으로 노드를 선택해 상세 드로어를 열 수 있다(마우스 미사용)', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const building = graph.nodes.find((n) => n.type === 'Building')!;
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/explore');
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(graph.nodes.length));

    const items = page.getByTestId('instance-graph-node-list').locator('[data-graph-item]');
    // roving tabIndex: 목록의 탭 스톱은 1개(첫 항목)뿐이어야 한다 — 노드가 많아도 탭 순서를 막지 않는다.
    await expect(items.first()).toHaveAttribute('tabindex', '0');
    await expect(items.nth(1)).toHaveAttribute('tabindex', '-1');

    // Tab으로 목록 진입 — 툴바(탭/검색/타입 묶기) → 타입 필터 패널(검색 + 토글 6) → 탭패널 → 첫 노드 항목 순.
    // 실제 탭 스톱 수에 의존하지 않고, 목록에 닿을 때까지 Tab을 눌러 "키보드로 도달 가능"만 검증한다.
    await page.getByRole('button', { name: '타입 필터 접기' }).focus();
    for (let i = 0; i < 25 && !(await items.first().evaluate((el) => el === document.activeElement)); i++) {
      await page.keyboard.press('Tab');
    }
    await expect(items.first()).toBeFocused();
    // 포커스가 들어오면 시각적으로도 드러나야 한다(보이지 않는 포커스 금지, SC 2.4.7).
    await expect(items.first()).toBeVisible();

    // ↓ 로 Building 노드까지 이동 후 Enter → 상세 드로어 오픈.
    const buildingIndex = graph.nodes.findIndex((n) => n.key === building.key);
    for (let i = 0; i < buildingIndex; i++) await page.keyboard.press('ArrowDown');
    await expect(items.nth(buildingIndex)).toBeFocused();
    await page.keyboard.press('Enter');

    const drawer = page.getByTestId('node-detail-drawer');
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText(building.name)).toBeVisible();
    // 인접 관계(HAS_EQUIPMENT → 스프링클러설비)까지 드로어에 표시된다.
    await expect(drawer.getByText('HAS_EQUIPMENT')).toBeVisible();
  });

  test('대체 노드 목록은 이름 검색·타입 필터 결과와 동기화된다(숨긴 노드가 SR에 남지 않는다)', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/explore');
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(graph.nodes.length));

    const list = page.getByTestId('instance-graph-node-list');
    // 이름 검색 — '강남'은 Incident 1건 + Building 1건에 매칭된다.
    await page.getByPlaceholder('이름 검색').fill('강남');
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', '2');
    await expect(list.locator('[data-graph-item]')).toHaveCount(2);
    await expect(list).toHaveAttribute('aria-label', /노드 2개/);

    // 타입 필터 — 기본(전체 표시) 상태에서 'Incident'를 끄면(#404: 그 타입만 숨김) 검색 결과 중
    // Incident가 빠지고 Building만 남아 교집합이 1건이 된다.
    await page.getByTestId('type-filter-list').getByRole('button', { name: /^Incident/ }).click();
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', '1');
    await expect(list.locator('[data-graph-item]')).toHaveCount(1);
    await expect(list.locator('[data-graph-item]')).toHaveText(/강남타워 \(Building\)/);
  });

  // (#413) 좌측 타입 필터 패널의 타입별 개수 배지는 항상 graph.nodes 전체 기준으로만 세어
  // 이름 검색과 무관하게 고정돼 있었다 — 검색 결과가 0건인데도 배지는 "1"을 그대로 보여줘
  // 사용자가 데이터가 존재한다고 오인할 수 있었다. 배지가 캔버스와 같은 검색 조건을 반영해야 한다.
  test('타입별 개수 배지는 이름 검색 결과를 반영한다(#413)', async ({ authenticatedPage: page }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/explore');
    await expect(page.getByTestId('type-filter-panel')).toBeVisible();

    const typeList = page.getByTestId('type-filter-list');
    // 검색 전: Incident 2개, Building 1개(전체 그래프 기준).
    await expect(typeList.getByRole('button', { name: /^Incident/ })).toHaveText('Incident2');
    await expect(typeList.getByRole('button', { name: /^Building/ })).toHaveText('Building1');

    // 어떤 노드에도 매칭되지 않는 검색어 — 캔버스는 "조건에 맞는 노드가 없습니다"를 보여주고,
    // 배지도 전부 0으로 떨어져야 한다.
    await page.getByPlaceholder('이름 검색').fill('존재하지않는이름XYZ123');
    await expect(page.getByText('조건에 맞는 노드가 없습니다.')).toBeVisible();
    await expect(typeList.getByRole('button', { name: /^Incident/ })).toHaveText('Incident0');
    await expect(typeList.getByRole('button', { name: /^Building/ })).toHaveText('Building0');

    // '강남'은 Incident 1건(강남구 오피스텔 화재) + Building 1건(강남타워)에만 매칭 — 부분 검색도
    // 정확히 반영돼야 한다.
    await page.getByPlaceholder('이름 검색').fill('강남');
    await expect(typeList.getByRole('button', { name: /^Incident/ })).toHaveText('Incident1');
    await expect(typeList.getByRole('button', { name: /^Building/ })).toHaveText('Building1');
  });

  // (#405) 이름 검색으로 상대 노드가 화면에서 사라져도, 남은 노드의 "관계 N개"는 실제 전체 관계 수를
  // 유지해야 한다 — filteredEdges(화면에 그릴 엣지)로 degree를 계산하면 상대 노드가 안 보인다는 이유만
  // 으로 "관계 0개"가 되어 사용자가 "이 노드는 고립돼 있다"고 오인한다.
  test('이름 검색으로 상대 노드가 가려져도 관계 수는 실제 값을 유지한다(#405)', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/explore');
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(graph.nodes.length));

    const list = page.getByTestId('instance-graph-node-list');
    // '2026-03'은 incident-1(강남구 오피스텔 화재(2026-03-02))에만 매칭 — 인접한 building/cause/
    // damage/regulation 4개 노드는 모두 화면에서 사라진다.
    await page.getByPlaceholder('이름 검색').fill('2026-03');
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', '1');
    await expect(list.locator('[data-graph-item]')).toHaveCount(1);
    // 상대 노드가 전부 가려졌어도 incident-1은 여전히 4개 관계(OCCURRED_AT/CAUSED_BY/RESULTED_IN/
    // VIOLATED)를 가진다 — "관계 0개"로 표시되면 회귀.
    await expect(list.locator('[data-graph-item]')).toHaveText(/관계 4개/);
  });

  test('지식 모델 탭에서도 키보드로 타입 드릴다운이 가능하다', async ({ authenticatedPage: page }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', String(schema.entities.length));

    const list = page.getByTestId('schema-graph-type-list');
    await expect(list).toHaveAttribute(
      'aria-label',
      `지식 모델 타입 ${schema.entities.length}개, 관계 ${schema.relations.length}개`,
    );
    // Incident는 OCCURRED_AT/CAUSED_BY/RESULTED_IN/VIOLATED 4개 트리플의 주체다.
    await expect(list.locator('[data-graph-item]').first()).toHaveText('Incident — 관계 4개');

    // 첫 항목 포커스 → ↓ 로 Building(2번째) → Enter → 그래프 탐색 탭으로 드릴다운 + 해당 타입만 필터.
    await list.locator('[data-graph-item]').first().focus();
    await page.keyboard.press('ArrowDown');
    await expect(list.locator('[data-graph-item]').nth(1)).toBeFocused();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\/knowledge-graph\/explore$/);
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', '1');
    await expect(page.getByTestId('type-filter-list').getByRole('button', { name: /^Building/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  // (#411) 타입 필터 패널은 인스턴스 탭(그래프 탐색)에서만 동작하고 스키마 탭(지식 모델)에서는 칩
  // 상태만 바뀔 뿐 캔버스가 무반응이었다 — SchemaGraph가 activeTypes를 전혀 소비하지 않았기 때문.
  // 스키마 탭에서 칩을 꺼서 캔버스 노드/관계 수가 실제로 줄어드는지, 접근성 대체 목록(aria-label)도
  // 같이 갱신되는지 검증한다.
  test('스키마 탭에서도 타입 필터 칩을 끄면 캔버스에서 해당 타입이 사라진다(#411)', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', String(schema.entities.length));

    const typeList = page.getByTestId('schema-graph-type-list');
    await expect(typeList).toHaveAttribute(
      'aria-label',
      `지식 모델 타입 ${schema.entities.length}개, 관계 ${schema.relations.length}개`,
    );

    // Building은 OCCURRED_AT(Incident→Building)·HAS_EQUIPMENT(Building→Equipment) 2개 트리플의
    // 끝점이다 — 꺼지면 노드 6→5, 관계 6→4가 되어야 한다(#411 이전에는 6/6 그대로였다).
    await page.getByTestId('type-filter-list').getByRole('button', { name: /^Building/ }).click();
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '5');
    await expect(typeList).toHaveAttribute('aria-label', '지식 모델 타입 5개, 관계 4개');
    // 대체 목록(키보드 드릴다운용)에도 Building 항목이 더 이상 없어야 한다 — 캔버스와 목록이 어긋나면
    // 스크린리더 사용자에게는 필터가 반쪽만 적용된 것처럼 보인다.
    await expect(typeList.locator('[data-graph-item]', { hasText: 'Building' })).toHaveCount(0);

    // 다시 켜면 원상 복구된다.
    await page.getByTestId('type-filter-list').getByRole('button', { name: /^Building/ }).click();
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', String(schema.entities.length));
    await expect(typeList).toHaveAttribute(
      'aria-label',
      `지식 모델 타입 ${schema.entities.length}개, 관계 ${schema.relations.length}개`,
    );
  });

  // (#412) activeTypes가 이미 "부분 선택"(일부 타입만 끈) 상태에서 새 엔티티 타입을 추가하면, 사용자가
  // 그 타입을 필터로 건드린 적이 없는데도 자동으로 unpressed(꺼짐)로 나타나던 결함. OntologyPage가
  // 스키마의 타입 목록 변화를 렌더 중 이전 값과 비교해 activeTypes가 비어있지 않을 때만 새 타입을
  // 합류시키도록 고쳤다 — 이 테스트는 그 동기화가 실제로 동작하는지 검증한다.
  test('타입 필터가 부분 선택된 상태에서 새 타입을 추가하면 그 타입은 자동으로 숨겨지지 않는다(#412)', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', String(schema.entities.length));

    const typeList = page.getByTestId('type-filter-list');
    // Damage를 꺼서 activeTypes를 "부분 선택" 상태로 만든다(빈 Set이 아니게).
    await typeList.getByRole('button', { name: /^Damage/ }).click();
    await expect(typeList.getByRole('button', { name: /^Damage/ })).toHaveAttribute('aria-pressed', 'false');

    // 수정 모드로 들어가 새 타입(Sensor)을 만든다 — 필터를 손댄 적은 없다.
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByRole('button', { name: '타입 추가' }).click();
    await mockApi(
      page,
      'POST',
      '/api/v1/ontology/1/entity-types',
      createEntityTypeMutation({ id: 7, type: 'Sensor', description: '', naming: '', resolution: 'embedding', properties: [] }),
    );
    await page.getByLabel('타입 이름').fill('Sensor');
    await page.getByRole('button', { name: '타입 만들기' }).click();
    await expect(page.getByTestId('outline-entity-7')).toBeVisible();

    // 수정 모드를 종료해 필터 패널로 돌아온다.
    await page.getByRole('button', { name: '수정 모드' }).click();

    // 방금 만든 Sensor는 활성(pressed) 상태여야 한다 — 사용자가 끈 Damage만 계속 꺼져 있어야 한다.
    await expect(typeList.getByRole('button', { name: /^Sensor/ })).toHaveAttribute('aria-pressed', 'true');
    await expect(typeList.getByRole('button', { name: /^Damage/ })).toHaveAttribute('aria-pressed', 'false');
  });

  test('접힌 타입 필터 패널은 inert로 포커스 순서에서 제거된다(보이지 않는 탭 스톱 없음)', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/explore');
    const panel = page.getByTestId('type-filter-panel');
    await expect(panel).toBeVisible();
    // 펼친 상태에서는 inert가 없어야 한다(정상 조작 가능).
    await expect(panel).not.toHaveAttribute('inert', /.*/);

    await page.getByRole('button', { name: '타입 필터 접기' }).click();
    // 접히면 inert가 붙고, aria-hidden은 제거된다(inert가 접근성 트리 제거를 함의 — 중복 지정 금지).
    await expect(panel).toHaveAttribute('inert', /.*/);
    await expect(panel).not.toHaveAttribute('aria-hidden', /.*/);

    // 내부 컨트롤 7개(타입 검색 1 + 타입 토글 6)는 여전히 DOM에 있으나 포커스가 들어가지 않는다.
    const focusEscaped = await panel.evaluate((el) => {
      const target = el.querySelector<HTMLElement>('input, button');
      target?.focus();
      return el.contains(document.activeElement);
    });
    expect(focusEscaped).toBe(false);

    // 접기 토글 버튼에서 Tab 을 눌렀을 때 폭 0인 패널 내부가 아니라 다음 실제 컨트롤로 넘어간다.
    await page.getByRole('button', { name: '타입 필터 펼치기' }).focus();
    await page.keyboard.press('Tab');
    const inHiddenPanel = await page.evaluate(
      () => !!document.activeElement?.closest('[data-testid="type-filter-panel"]'),
    );
    expect(inHiddenPanel).toBe(false);
  });

  /**
   * #332 회귀: NodeDetailDrawer 리사이즈 핸들이 마우스 전용이었다
   * (role="separator"인데 tabIndex·화살표 키·aria-valuenow가 없었다).
   *
   * 방향 규약: 인스펙터가 오른쪽 고정이라 드래그도 "왼쪽으로 끌면 넓어진다".
   * 화살표도 이에 맞춰 ← = 폭 증가 / → = 폭 감소이며, Home = 최소 폭 / End = 최대 폭이다.
   */
  test('노드 상세 인스펙터 폭을 키보드(←/→/Home/End)로 조절할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/explore');
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(graph.nodes.length));

    // 마우스 없이 노드 선택 → 드로어 오픈(#326에서 추가된 대체 목록 경로).
    const items = page.getByTestId('instance-graph-node-list').locator('[data-graph-item]');
    await items.first().focus();
    await page.keyboard.press('Enter');

    const drawer = page.getByTestId('node-detail-drawer');
    await expect(drawer).toBeVisible();
    const handle = page.getByTestId('node-detail-resize-handle');

    // 선언한 role=separator(window splitter)에 걸맞은 값이 노출되어야 한다.
    await expect(handle).toHaveAttribute('aria-valuenow', '320');
    await expect(handle).toHaveAttribute('aria-valuemin', '260');
    await expect(handle).toHaveAttribute('aria-valuemax', '560');

    // 목록 항목에서 Tab 한 번으로 핸들에 도달한다(포커스 가능 = 마우스 없이 조작 가능).
    await items.first().focus();
    await page.keyboard.press('Tab');
    await expect(handle).toBeFocused();

    // 렌더된 실제 폭까지 함께 검증한다 — aria 값만 바뀌고 UI가 안 따라오는 것을 막는다.
    const renderedWidth = () => drawer.evaluate((el) => Math.round(el.getBoundingClientRect().width));
    expect(await renderedWidth()).toBe(320);

    // ← 2회 = +32px
    await page.keyboard.press('ArrowLeft');
    await page.keyboard.press('ArrowLeft');
    await expect(handle).toHaveAttribute('aria-valuenow', '352');
    expect(await renderedWidth()).toBe(352);

    // → 1회 = -16px
    await page.keyboard.press('ArrowRight');
    await expect(handle).toHaveAttribute('aria-valuenow', '336');
    expect(await renderedWidth()).toBe(336);

    // End = 최대 폭, Home = 최소 폭.
    await page.keyboard.press('End');
    await expect(handle).toHaveAttribute('aria-valuenow', '560');
    expect(await renderedWidth()).toBe(560);
    await page.keyboard.press('Home');
    await expect(handle).toHaveAttribute('aria-valuenow', '260');
    expect(await renderedWidth()).toBe(260);

    // 최소 폭에서 더 줄이려 해도 clamp되어 범위를 벗어나지 않는다.
    await page.keyboard.press('ArrowRight');
    await expect(handle).toHaveAttribute('aria-valuenow', '260');
    expect(await renderedWidth()).toBe(260);
  });
});
