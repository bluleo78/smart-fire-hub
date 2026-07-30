import type { UpdateOntologyRequest } from '@/types/ontology';

import { createOntologyGraph, createOntologySchema } from '../../factories/ontology.factory';
import {
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
 *   dev에서 노출되는 window.__ontologyCy로 검증한다. fcose 레이아웃은 비결정적이라 캔버스 좌표 클릭은 쓰지 않는다.
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
    await mockApi(page, 'GET', '/api/v1/ontology', schema);
    await mockApi(page, 'GET', '/api/v1/ontology/graph', graph);
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

  test('스키마 탭에서 Incident 타입 클릭 시 인스턴스 탭으로 드릴다운되어 Incident 노드만 표시된다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const incidentCount = graph.nodes.filter((n) => n.type === 'Incident').length;
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    // 스키마 탭의 'Incident' 타입 노드 tap → 드릴다운 브리지.
    // 스키마도 Cytoscape(canvas)라 좌표 클릭 대신 cy에서 프로그래매틱 tap을 발생시킨다(노드 id = 타입명).
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');
    await page.evaluate(() => {
      (window as unknown as { __ontologySchemaCy: { $(sel: string): { emit(e: string): void } } }).__ontologySchemaCy
        .$('#Incident')
        .emit('tap');
    });

    // 인스턴스 탭이 활성화되어야 한다
    await expect(page.getByRole('tab', { name: '그래프 탐색' })).toHaveAttribute('aria-selected', 'true');
    // Incident 타입 노드만 필터되어 표시되어야 한다 (모킹 그래프 기준 2개)
    await expectNodeCount(page, incidentCount);
  });

  test('범례에서 타입 버튼 클릭 시 인스턴스 노드 집합이 해당 타입만으로 필터링된다', async ({
    authenticatedPage: page,
  }) => {
    const graph = createOntologyGraph();
    const buildingCount = graph.nodes.filter((n) => n.type === 'Building').length;
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('tab', { name: '그래프 탐색' }).click();

    // 초기: 전체 노드(7개) 표시
    await expectNodeCount(page, graph.nodes.length);

    // 타입 필터 패널에서 'Building' 타입 버튼 클릭 → 필터 토글(Building만 활성)
    const panel = page.getByTestId('type-filter-panel');
    await panel.getByRole('button', { name: /Building/ }).click();

    // Building 타입 노드만 남아야 한다 (모킹 그래프 기준 1개)
    await expectNodeCount(page, buildingCount);
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
    await expect(panel.getByRole('button', { name: '전체' })).toHaveCount(0);
    await panel.getByRole('button', { name: /Building/ }).click();
    await expectNodeCount(page, buildingCount);
    const reset = panel.getByRole('button', { name: '전체' });
    await expect(reset).toBeVisible();
    await reset.click();
    await expectNodeCount(page, graph.nodes.length);
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

  test('ADMIN은 지식 모델 탭에서 편집 다이얼로그를 열어 description을 수정·저장할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await expect(dialog).toBeVisible();

    // Incident의 description만 수정한다.
    const incidentRow = page.getByTestId('entity-edit-Incident');
    await incidentRow.getByLabel('설명').fill('수정된 사건 설명');
    await dialog.getByRole('button', { name: '저장' }).click();

    // 입력→API payload 검증
    const req = await capture.waitForRequest();
    const payload = req.payload as typeof schema;
    expect(payload.entities.find((e) => e.type === 'Incident')?.description).toBe('수정된 사건 설명');
    expect(payload.schemaVersion).toBe(schema.schemaVersion);

    // 저장 성공 → 다이얼로그가 닫힌다
    await expect(dialog).toBeHidden();
  });

  // ⚠️ 라운드트립 무결성 회귀 테스트 — full-document PUT(전체 삭제·재삽입) 방식의 핵심 위험을 검증한다.
  // 편집 폼이 손대지 않은 Damage.properties(피해액)와 전체 relations가 payload에 그대로(값·순서) 보존되어야 한다.
  // 그렇지 않으면 "저장됨" 토스트가 뜨고 버전도 증가하지만 조용히 데이터가 유실된다.
  test('편집 저장 시 미편집 엔티티의 properties와 전체 relations가 원본 그대로 payload에 포함된다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    // Building만 편집(Damage는 건드리지 않음) — Damage.properties가 편집 UI 밖에서도 보존되는지가 관건.
    await dialog.getByTestId('entity-edit-Building').getByLabel('설명').fill('수정된 건물 설명');
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    const payload = req.payload as typeof schema;

    // 미편집 Damage의 properties(피해액)가 완전히 동일하게 보존된다.
    const damage = payload.entities.find((e) => e.type === 'Damage');
    expect(damage?.properties).toEqual(schema.entities.find((e) => e.type === 'Damage')!.properties);

    // relations는 이 슬라이스에서 편집 UI가 없으므로 순서·값이 원본과 완전히 동일해야 한다.
    expect(payload.relations).toEqual(schema.relations);

    // 편집 대상 엔티티 수는 원본과 동일(엔티티 추가/삭제 없음 — 이 슬라이스 범위 밖).
    expect(payload.entities).toHaveLength(schema.entities.length);
  });

  // 5-2: 관계(트리플) CRUD — 추가한 관계가 저장 payload의 relations 배열에 반영되는지 검증.
  test('관계를 추가하면 저장 payload의 relations 배열에 새 트리플이 포함된다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    const relationsEditor = dialog.getByTestId('relations-editor');
    await relationsEditor.getByRole('button', { name: '관계 추가' }).click();

    const newRow = relationsEditor.getByTestId(`relation-row-${schema.relations.length}`);
    await newRow.getByLabel('관계 주어 타입').click();
    await page.getByRole('option', { name: 'Cause', exact: true }).click();
    await newRow.getByLabel('관계명').fill('OVERLAPS_WITH');
    await newRow.getByLabel('관계 목적어 타입').click();
    await page.getByRole('option', { name: 'Damage', exact: true }).click();
    await newRow.getByLabel('관계 설명').fill('원인과 피해가 겹침');

    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    const payload = req.payload as typeof schema;
    expect(payload.relations).toHaveLength(schema.relations.length + 1);
    expect(payload.relations).toContainEqual({
      subject: 'Cause',
      relation: 'OVERLAPS_WITH',
      object: 'Damage',
      description: '원인과 피해가 겹침',
    });
  });

  // 5-2: 관계 삭제 — 삭제한 관계가 저장 payload에서 제거되는지 검증.
  test('관계를 삭제하면 저장 payload의 relations 배열에서 제거된다', async ({ authenticatedPage: page }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    const relationsEditor = dialog.getByTestId('relations-editor');
    await relationsEditor.getByTestId('relation-row-0').getByLabel('관계 삭제').click();
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    const payload = req.payload as typeof schema;
    expect(payload.relations).toHaveLength(schema.relations.length - 1);
    expect(payload.relations).not.toContainEqual(schema.relations[0]);
  });

  // 5-2: 속성(property) CRUD — 엔티티 카드에서 추가한 속성이 저장 payload에 반영되는지 검증.
  test('엔티티에 속성을 추가하면 저장 payload에 반영된다', async ({ authenticatedPage: page }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    const incidentRow = dialog.getByTestId('entity-edit-Incident');
    await incidentRow.getByRole('button', { name: 'Incident 속성 추가' }).click();

    const newProp = incidentRow.getByTestId('property-row-Incident-0');
    await newProp.getByLabel('Incident 속성 이름').fill('사상자수');
    await newProp.getByLabel('Incident 속성 단위').fill('명');
    await newProp.getByLabel('Incident 속성 설명').fill('사망·부상자 합계');

    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    const payload = req.payload as typeof schema;
    const incident = payload.entities.find((e) => e.type === 'Incident');
    expect(incident?.properties).toContainEqual({
      name: '사상자수',
      description: '사망·부상자 합계',
      dataType: 'text',
      unit: '명',
    });
  });

  // 5-2: 속성 삭제 — 삭제한 속성이 저장 payload에서 제거되는지 검증(다른 엔티티의 속성은 보존).
  test('엔티티의 속성을 삭제하면 저장 payload에서 제거된다', async ({ authenticatedPage: page }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    const damageRow = dialog.getByTestId('entity-edit-Damage');
    await damageRow.getByTestId('property-row-Damage-0').getByLabel('Damage 속성 삭제').click();
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    const payload = req.payload as typeof schema;
    const damage = payload.entities.find((e) => e.type === 'Damage');
    expect(damage?.properties).toHaveLength(0);
  });

  // 5-2: 예약어 속성명은 서버 왕복 없이 로컬에서 즉시 차단된다(저장 API 호출 안 됨).
  test('속성명에 예약어(type)를 입력하면 로컬 에러 토스트가 뜨고 저장 API가 호출되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    const incidentRow = dialog.getByTestId('entity-edit-Incident');
    await incidentRow.getByRole('button', { name: 'Incident 속성 추가' }).click();
    await incidentRow.getByTestId('property-row-Incident-0').getByLabel('Incident 속성 이름').fill('type');

    await dialog.getByRole('button', { name: '저장' }).click();

    // 인라인 힌트("예약어는 속성명으로 쓸 수 없습니다: key, type, ...")와 텍스트가 겹치므로
    // 토스트 고유 문구(엔티티·속성명 포함)로 특정해 strict-mode 충돌을 피한다.
    await expect(page.getByText('예약어는 속성명으로 쓸 수 없습니다(Incident): type')).toBeVisible();
    expect(capture.requests).toHaveLength(0);
    await expect(dialog).toBeVisible();
  });

  // #302 회귀: 관계명을 비운 채 저장하면 로컬에서 막히고, 이름을 채우면 정상 저장된다.
  test('관계명을 비운 채 저장하면 위치를 특정한 에러가 뜨고 저장 API가 호출되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    const relationsEditor = dialog.getByTestId('relations-editor');
    // 관계 추가는 관계명을 빈 칸으로 seed하므로 아무것도 입력하지 않고 저장을 시도한다.
    await relationsEditor.getByRole('button', { name: '관계 추가' }).click();
    const newRow = relationsEditor.getByTestId(`relation-row-${schema.relations.length}`);
    await expect(newRow.getByText('관계명을 입력하세요')).toBeVisible();

    await dialog.getByRole('button', { name: '저장' }).click();

    // 인라인 힌트와 문구가 겹치므로 주어→목적어가 붙은 토스트 문구로 특정한다.
    await expect(page.getByText('관계명을 입력하세요(Incident → Incident)')).toBeVisible();
    expect(capture.requests).toHaveLength(0);
    await expect(dialog).toBeVisible();

    // 이름을 채우면 인라인 에러가 사라지고 저장이 payload에 담겨 나간다.
    await newRow.getByLabel('관계명').fill('RELATED_TO');
    await expect(newRow.getByText('관계명을 입력하세요')).toBeHidden();
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    const payload = req.payload as typeof schema;
    expect(payload.relations).toContainEqual({
      subject: 'Incident',
      relation: 'RELATED_TO',
      object: 'Incident',
      description: '',
    });
  });

  // #302 회귀: 이름이 빈 속성 2개는 예전에 ''끼리 충돌해 "중복된 속성명(Building):"으로 잘려 표시됐다.
  // blank 검사가 중복 검사보다 먼저 걸려 어느 행이 문제인지 알려주는지 검증한다.
  test('속성명을 비운 채 저장하면 중복이 아니라 빈 이름으로 진단되고 저장 API가 호출되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    const buildingCard = dialog.getByTestId('entity-edit-Building');
    await buildingCard.getByRole('button', { name: 'Building 속성 추가' }).click();
    await buildingCard.getByRole('button', { name: 'Building 속성 추가' }).click();
    await expect(buildingCard.getByTestId('property-row-Building-0').getByText('속성명을 입력하세요')).toBeVisible();

    await dialog.getByRole('button', { name: '저장' }).click();

    await expect(page.getByText('이름이 비어 있는 속성이 있습니다(Building, 1번째 행)')).toBeVisible();
    await expect(page.getByText('중복된 속성명')).toBeHidden();
    expect(capture.requests).toHaveLength(0);

    // 두 행 모두 이름을 채우면 저장이 성사되고 payload에 두 속성이 담긴다.
    await buildingCard.getByTestId('property-row-Building-0').getByLabel('Building 속성 이름').fill('층수');
    await buildingCard.getByTestId('property-row-Building-1').getByLabel('Building 속성 이름').fill('연면적');
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    const payload = req.payload as typeof schema;
    const building = payload.entities.find((e) => e.type === 'Building');
    expect(building?.properties.map((p) => p.name)).toEqual(['층수', '연면적']);
  });

  // 5-3: 엔티티 타입 추가 — 추가한 타입이 저장 payload의 entities 배열에 반영되는지 검증.
  test('엔티티 타입을 추가하면 저장 payload의 entities 배열에 새 타입이 포함된다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('new-entity-type-name').fill('Sensor');
    await dialog.getByTestId('add-entity-type').click();

    // 새 카드가 즉시 렌더된다(추가는 이름 확정 후 append이므로 카드 식별자가 곧 타입명).
    await expect(dialog.getByTestId('entity-edit-Sensor')).toBeVisible();
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    const payload = req.payload as typeof schema;
    expect(payload.entities).toHaveLength(schema.entities.length + 1);
    expect(payload.entities).toContainEqual({
      type: 'Sensor',
      description: '',
      naming: '',
      resolution: 'embedding',
      properties: [],
    });
  });

  // 5-3: 이름 없이 추가 시도 — 로컬 검증이 즉시 막고 새 카드가 생기지 않는다.
  test('이름 없이 타입 추가를 시도하면 로컬 에러 토스트가 뜨고 카드가 추가되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('add-entity-type').click();

    await expect(page.getByText('타입 이름을 입력하세요.')).toBeVisible();
    await expect(dialog.locator('[data-testid^="entity-edit-"]')).toHaveCount(schema.entities.length);
  });

  // 5-3: 중복 이름 추가 시도 — 현재 목록과 겹치는 이름은 로컬 검증이 막는다.
  test('이미 존재하는 타입 이름으로 추가를 시도하면 로컬 에러 토스트가 뜬다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('new-entity-type-name').fill('Incident');
    await dialog.getByTestId('add-entity-type').click();

    await expect(page.getByText('이미 존재하는 타입입니다: Incident')).toBeVisible();
    await expect(dialog.locator('[data-testid^="entity-edit-"]')).toHaveCount(schema.entities.length);
  });

  // 5-3: 타입 삭제 — 삭제한 타입과 그 타입을 참조하던 관계가 저장 payload에서 함께 제거되는지 검증.
  // Regulation은 2개 관계(Incident→VIOLATED, Equipment→GOVERNED_BY)의 object이므로 cascade 대상이 명확하다.
  test('엔티티 타입을 삭제하면 그 타입과 참조 관계가 저장 payload에서 함께 제거된다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    const regulationRefs = schema.relations.filter(
      (r) => r.subject === 'Regulation' || r.object === 'Regulation',
    ).length;
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('entity-delete-Regulation').click();

    // cascade 안내 토스트(참조 관계 개수 포함) — 고유 문구로 특정한다.
    await expect(
      page.getByText(`타입 'Regulation'과(와) 이를 참조하는 관계 ${regulationRefs}개를 제거했습니다.`),
    ).toBeVisible();
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    const payload = req.payload as typeof schema;
    expect(payload.entities.map((e) => e.type)).not.toContain('Regulation');
    expect(payload.entities).toHaveLength(schema.entities.length - 1);
    // 참조 관계도 함께 제거 — Regulation을 subject/object로 하는 트리플이 남아 있으면 안 된다.
    expect(payload.relations).toHaveLength(schema.relations.length - regulationRefs);
    expect(payload.relations.some((r) => r.subject === 'Regulation' || r.object === 'Regulation')).toBe(false);
  });

  // 5-5: 엔티티 타입 리네임 — payload의 entities/relations에 새 이름이 반영되고, renames에 (from,to)가 담긴다.
  // Cause는 CAUSED_BY 관계의 object이므로 관계 cascade-rewrite도 함께 검증한다.
  test('엔티티 타입 이름을 변경하면 entities/relations/renames가 새 이름으로 저장 payload에 반영된다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('entity-rename-start-Cause').click();
    await dialog.getByTestId('entity-rename-form-Cause').getByLabel('Cause 타입 이름', { exact: true }).fill('RootCause');
    await dialog.getByTestId('entity-rename-form-Cause').getByLabel('Cause 타입 이름 확인').click();

    // 확정 직후 카드 식별자가 새 이름으로 바뀐다(리네임은 즉시 반영, 저장 전에도 UI에 보임).
    await expect(dialog.getByTestId('entity-edit-RootCause')).toBeVisible();
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    const payload = req.payload as UpdateOntologyRequest;
    expect(payload.entities.map((e) => e.type)).not.toContain('Cause');
    expect(payload.entities.map((e) => e.type)).toContain('RootCause');
    expect(payload.renames).toEqual([{ from: 'Cause', to: 'RootCause' }]);
    // CAUSED_BY 관계의 object가 Cause→RootCause로 cascade-rewrite 되어야 한다.
    const causedBy = payload.relations.find((r) => r.relation === 'CAUSED_BY');
    expect(causedBy?.object).toBe('RootCause');
  });

  // 5-5: 이미 존재하는 타입 이름으로 리네임 시도 시 로컬에서 즉시 거부(저장 API 호출 안 됨).
  test('이미 존재하는 타입 이름으로 변경을 시도하면 로컬 에러 토스트가 뜨고 카드가 그대로 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('entity-rename-start-Cause').click();
    await dialog.getByTestId('entity-rename-form-Cause').getByLabel('Cause 타입 이름', { exact: true }).fill('Building');
    await dialog.getByTestId('entity-rename-form-Cause').getByLabel('Cause 타입 이름 확인').click();

    await expect(page.getByText('이미 존재하는 타입입니다: Building')).toBeVisible();
    await expect(dialog.getByTestId('entity-edit-Cause')).toBeVisible();
  });

  // 5-5: 리네임 후 원래 이름으로 되돌리면 renames 엔트리가 제거되어(무변경 취급) payload에 담기지 않는다.
  test('리네임 후 원래 이름으로 되돌리면 renames가 비어 저장 payload에 담기지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('entity-rename-start-Cause').click();
    await dialog.getByTestId('entity-rename-form-Cause').getByLabel('Cause 타입 이름', { exact: true }).fill('RootCause');
    await dialog.getByTestId('entity-rename-form-Cause').getByLabel('Cause 타입 이름 확인').click();

    await dialog.getByTestId('entity-rename-start-RootCause').click();
    await dialog.getByTestId('entity-rename-form-RootCause').getByLabel('RootCause 타입 이름', { exact: true }).fill('Cause');
    await dialog.getByTestId('entity-rename-form-RootCause').getByLabel('RootCause 타입 이름 확인').click();
    await expect(dialog.getByTestId('entity-edit-Cause')).toBeVisible();

    await dialog.getByRole('button', { name: '저장' }).click();
    const req = await capture.waitForRequest();
    const payload = req.payload as UpdateOntologyRequest;
    expect(payload.renames).toEqual([]);
    expect(payload.entities.map((e) => e.type)).toContain('Cause');
  });

  // 5-5 회귀: 리네임한 타입을 곧바로 삭제하면(변심), 해당 renames 엔트리도 함께 정리되어야 한다.
  // 정리하지 않으면 서버가 "리네임의 to가 최종 엔티티 타입에 없다"고 400을 던져 단순 삭제가 실패로 보인다.
  test('리네임 후 그 타입을 삭제하면 renames 엔트리도 함께 제거되어 저장이 성공한다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('entity-rename-start-Cause').click();
    await dialog.getByTestId('entity-rename-form-Cause').getByLabel('Cause 타입 이름', { exact: true }).fill('RootCause');
    await dialog.getByTestId('entity-rename-form-Cause').getByLabel('Cause 타입 이름 확인').click();
    await expect(dialog.getByTestId('entity-edit-RootCause')).toBeVisible();

    await dialog.getByTestId('entity-delete-RootCause').click();
    await dialog.getByRole('button', { name: '저장' }).click();

    const req = await capture.waitForRequest();
    const payload = req.payload as UpdateOntologyRequest;
    expect(payload.renames).toEqual([]);
    expect(payload.entities.map((e) => e.type)).not.toContain('RootCause');
    expect(payload.entities.map((e) => e.type)).not.toContain('Cause');
    // Cause를 object로 참조하던 CAUSED_BY 관계도 함께 제거되어야 한다.
    expect(payload.relations.some((r) => r.relation === 'CAUSED_BY')).toBe(false);
  });

  // #304 회귀: 이름 맞바꾸기(A 이름을 비운 뒤 그 이름을 B에 부여). 이전에는 UI가 조합을 통과시키고
  // 저장 시점에야 서버가 400("타입 리네임의 from이 여전히...")으로 튕겨, 세션의 편집 전량을 포기해야 했다.
  // 이제 세 번째 리네임 확정 시점에 원인 이름을 특정해 차단하므로, 앞선 정상 편집은 그대로 저장할 수 있다.
  test('타입 이름 맞바꾸기를 시도하면 확정 시점에 차단되고 앞선 정상 리네임은 그대로 저장된다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { ...schema, schemaVersion: schema.schemaVersion + 1 },
      { capture: true },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    const rename = async (from: string, to: string) => {
      await dialog.getByTestId(`entity-rename-start-${from}`).click();
      await dialog.getByTestId(`entity-rename-form-${from}`).getByLabel(`${from} 타입 이름`, { exact: true }).fill(to);
      await dialog.getByTestId(`entity-rename-form-${from}`).getByLabel(`${from} 타입 이름 확인`).click();
    };

    // 1~2단계는 정상 리네임 — Incident 이름이 비워지므로 UI 중복 검사는 통과한다.
    await rename('Building', 'Temp');
    await expect(dialog.getByTestId('entity-edit-Temp')).toBeVisible();
    await rename('Incident', 'Xyz');
    await expect(dialog.getByTestId('entity-edit-Xyz')).toBeVisible();

    // 3단계: 비워진 Incident 이름을 재사용 → 차단. 어떤 이름이 문제인지 + 어떻게 하면 되는지 안내.
    await rename('Temp', 'Incident');
    await expect(page.getByText('다른 타입이 쓰던 이름은 같은 저장에서 재사용할 수 없습니다: Incident')).toBeVisible();
    // 카드는 Temp 그대로 — 차단된 리네임이 부분 적용되면 안 된다.
    await expect(dialog.getByTestId('entity-edit-Temp')).toBeVisible();
    await expect(dialog.getByTestId('entity-edit-Incident')).toHaveCount(0);

    // 차단은 그 한 건에만 적용된다 — 앞선 정상 리네임 2건은 여전히 저장 가능해야 한다(작업 전량 포기 방지).
    await dialog.getByRole('button', { name: '저장' }).click();
    const req = await capture.waitForRequest();
    const payload = req.payload as UpdateOntologyRequest;
    expect(payload.renames).toEqual([
      { from: 'Building', to: 'Temp' },
      { from: 'Incident', to: 'Xyz' },
    ]);
    // 서버가 400을 던지는 조건(어떤 from이 최종 타입 목록에 남아 있음)이 payload에 없어야 한다.
    const finalTypes = payload.entities.map((e) => e.type);
    expect(payload.renames?.some((r) => finalTypes.includes(r.from))).toBe(false);
  });

  // #304 회귀: 리네임으로 비워진 이름을 "새 타입 추가"로 재사용하는 경로도 서버 검증에 동일하게 걸린다.
  test('리네임으로 비워진 이름을 새 타입 이름으로 추가하려 하면 차단되고 카드가 추가되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('entity-rename-start-Incident').click();
    await dialog.getByTestId('entity-rename-form-Incident').getByLabel('Incident 타입 이름', { exact: true }).fill('Xyz');
    await dialog.getByTestId('entity-rename-form-Incident').getByLabel('Incident 타입 이름 확인').click();
    await expect(dialog.getByTestId('entity-edit-Xyz')).toBeVisible();

    await dialog.getByTestId('new-entity-type-name').fill('Incident');
    await dialog.getByTestId('add-entity-type').click();

    await expect(page.getByText('다른 타입이 쓰던 이름은 같은 저장에서 재사용할 수 없습니다: Incident')).toBeVisible();
    await expect(dialog.getByTestId('entity-edit-Incident')).toHaveCount(0);
  });

  // #304 회귀: 클라이언트 가드를 우회한 경로로 서버가 그래도 renames 400을 던지는 경우,
  // 내부 검증 용어가 그대로 노출되면 안 된다 — 사용자 어휘로 치환해 보여준다.
  test('서버가 renames 검증 400을 반환해도 내부 용어 대신 사용자 어휘 안내가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await mockApi(
      page,
      'PUT',
      '/api/v1/ontology',
      { message: '타입 리네임의 from이 여전히 엔티티 타입으로 남아 있습니다: Incident' },
      { status: 400 },
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('entity-edit-Incident').getByLabel('설명').fill('서버 400 유도');
    await dialog.getByRole('button', { name: '저장' }).click();

    await expect(page.getByText('다른 타입이 쓰던 이름은 같은 저장에서 재사용할 수 없습니다: Incident')).toBeVisible();
    await expect(page.getByText('타입 리네임의 from이')).toHaveCount(0);
    await expect(dialog).toBeVisible();
  });

  test('편집 저장 중 버전 충돌(409) 시 에러 토스트가 표시되고 다이얼로그는 닫히지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await mockApi(page, 'PUT', '/api/v1/ontology', { message: '지식 모델이 다른 사용자에 의해 이미 수정되었습니다.' }, { status: 409 });
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('entity-edit-Incident').getByLabel('설명').fill('충돌 시도');
    await dialog.getByRole('button', { name: '저장' }).click();

    // 토스트로 안내되고 다이얼로그는 유지된다. #301 이후 같은 문구의 충돌 배너가 다이얼로그 안에도
    // 뜨므로, 토스트 영역으로 범위를 좁혀 단언한다.
    await expect(
      page.getByLabel('Notifications alt+T').getByText('지식 모델이 다른 사용자에 의해 이미 수정되었습니다.'),
    ).toBeVisible();
    await expect(dialog).toBeVisible();
    await expect(page.getByTestId('ontology-conflict-banner')).toBeVisible();
  });

  // #301 회귀 — 409 후 복구 경로. 이전에는 재시도·재오픈 모두 낡은 schemaVersion을 재전송해
  // 409가 무한 반복되고, 유일한 탈출구인 브라우저 새로고침은 편집 전량을 날렸다.
  // 검증 관전 포인트 3가지: (1) 첫 PUT은 v1, (2) 두 PUT 사이에 GET 재조회가 실제로 끼어들 것,
  // (3) 두 번째 PUT은 v2 + 사용자의 편집(도메인 문자열)이 그대로 살아 있을 것.
  test('저장 충돌(409) 후 최신 버전을 재조회해 편집 내용을 유지한 채 재저장할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);

    // 서버 상태를 흉내내는 카운터 모킹: 첫 PUT은 버전 불일치(409)로 튕기며 서버 버전을 2로 올리고,
    // 이후 GET은 v2를 돌려준다. 두 번째 PUT은 payload가 v2일 때만 성공한다.
    let serverVersion = schema.schemaVersion; // 1
    const events: string[] = [];
    const putPayloads: UpdateOntologyRequest[] = [];

    await page.route(
      (url) => url.pathname === '/api/v1/ontology',
      async (route) => {
        const method = route.request().method();
        if (method === 'GET') {
          events.push('GET');
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ ...schema, schemaVersion: serverVersion }),
          });
        }
        if (method === 'PUT') {
          events.push('PUT');
          const payload = route.request().postDataJSON() as UpdateOntologyRequest;
          putPayloads.push(payload);
          if (payload.schemaVersion !== serverVersion) {
            return route.fulfill({
              status: 409,
              contentType: 'application/json',
              body: JSON.stringify({ message: '지식 모델이 다른 사용자에 의해 이미 수정되었습니다.' }),
            });
          }
          serverVersion += 1;
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ ...schema, ...payload, schemaVersion: serverVersion }),
          });
        }
        return route.fallback();
      },
    );

    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await expect(dialog).toBeVisible();

    // 사용자의 편집 — 이 값이 충돌 복구 후에도 살아남아야 한다.
    await dialog.locator('#ontology-domain').fill('충돌 후에도 살아남는 도메인');

    // 다른 세션이 먼저 저장한 상황을 만든다(서버 버전만 앞서 나감).
    serverVersion = 2;

    await dialog.getByRole('button', { name: '저장' }).click();

    // 충돌 배너가 뜨고, 재조회가 끝나면 복구 액션이 활성화된다.
    const overwrite = page.getByTestId('ontology-conflict-overwrite');
    await expect(page.getByTestId('ontology-conflict-banner')).toBeVisible();
    await expect(overwrite).toBeEnabled();
    // 편집 내용은 그대로 유지된다(폼 리셋 금지).
    await expect(dialog.locator('#ontology-domain')).toHaveValue('충돌 후에도 살아남는 도메인');

    await overwrite.click();

    // 저장 성공 → 다이얼로그가 닫힌다.
    await expect(dialog).toBeHidden();

    // (1) 첫 PUT은 낡은 v1, (3) 두 번째 PUT은 재조회된 v2 + 편집 내용 유지
    expect(putPayloads).toHaveLength(2);
    expect(putPayloads[0].schemaVersion).toBe(1);
    expect(putPayloads[1].schemaVersion).toBe(2);
    expect(putPayloads[1].domain).toBe('충돌 후에도 살아남는 도메인');

    // (2) 두 PUT 사이에 온톨로지 GET 재조회가 실제로 발생했다 — 이슈의 핵심 누락 관측치.
    const firstPut = events.indexOf('PUT');
    const secondPut = events.lastIndexOf('PUT');
    const getBetween = events.slice(firstPut + 1, secondPut).includes('GET');
    expect(getBetween).toBe(true);
  });

  // ⚠️ 회귀 테스트(#303) — 뷰포트 5배 길이 폼에서 (1) 실수로 닫아 편집 전량이 무경고로 사라지고
  // (2) 저장 버튼이 3,700px 스크롤 끝에 있어 편집 위치와 절대 함께 보이지 않던 두 결함.
  test('편집 후 ESC를 누르면 닫기 가드가 뜨고, 편집 계속하기를 고르면 편집이 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByLabel('도메인').fill('잃어버리면 안 되는 도메인');

    await page.keyboard.press('Escape');

    // 다이얼로그는 닫히지 않고 가드가 뜬다.
    const guard = page.getByTestId('ontology-close-confirm');
    await expect(guard).toBeVisible();
    await expect(guard.getByText('지금 닫으면 편집한 내용이 모두 사라집니다.')).toBeVisible();
    await expect(dialog).toBeVisible();

    // 편집 계속하기 → 가드만 닫히고 편집 내용은 그대로.
    await guard.getByRole('button', { name: '편집 계속하기' }).click();
    await expect(guard).toBeHidden();
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel('도메인')).toHaveValue('잃어버리면 안 되는 도메인');
  });

  // 이슈가 지목한 세 경로(취소·ESC·오버레이 클릭)가 모두 같은 가드를 타야 한다 — ESC만 막고
  // 나머지가 새면 편집 유실은 그대로 남는다.
  test('오버레이 클릭도 닫기 가드를 타고 다이얼로그가 닫히지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByLabel('도메인').fill('오버레이로 잃으면 안 되는 도메인');

    // 다이얼로그는 y≈67px에서 시작하므로 (10,10)은 오버레이 영역이다.
    await page.mouse.click(10, 10);

    await expect(page.getByTestId('ontology-close-confirm')).toBeVisible();
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel('도메인')).toHaveValue('오버레이로 잃으면 안 되는 도메인');
  });

  test('푸터 취소 버튼도 닫기 가드를 타고 다이얼로그가 닫히지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByLabel('도메인').fill('취소로 잃으면 안 되는 도메인');

    await dialog.getByRole('button', { name: '취소' }).click();

    await expect(page.getByTestId('ontology-close-confirm')).toBeVisible();
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel('도메인')).toHaveValue('취소로 잃으면 안 되는 도메인');
  });

  test('닫기 가드에서 저장하지 않고 닫기를 고르면 다이얼로그가 닫히고 재오픈 시 원본이 복원된다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByLabel('도메인').fill('폐기될 도메인');
    await page.keyboard.press('Escape');

    await page.getByTestId('ontology-close-confirm').getByRole('button', { name: '저장하지 않고 닫기' }).click();
    await expect(dialog).toBeHidden();

    // 재오픈 — OntologyPage가 key로 리마운트하므로 원본 값으로 돌아와 있다.
    await page.getByRole('button', { name: '편집' }).click();
    await expect(dialog.getByLabel('도메인')).toHaveValue(schema.domain);
  });

  test('편집하지 않은 상태의 ESC는 가드 없이 즉시 닫힌다', async ({ authenticatedPage: page }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await expect(dialog).toBeVisible();

    await page.keyboard.press('Escape');

    await expect(dialog).toBeHidden();
    await expect(page.getByTestId('ontology-close-confirm')).toHaveCount(0);
  });

  // 리네임 인라인 입력에는 자체 Escape 핸들러가 있다 — ESC 한 번이 "리네임 취소 + 닫기 가드"를
  // 동시에 트리거하면 안 된다. 리네임 중 ESC는 리네임만 취소하고 다이얼로그는 열린 채여야 한다.
  test('타입 리네임 입력 중 ESC는 리네임만 취소하고 닫기 가드를 띄우지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await dialog.getByTestId('entity-rename-start-Incident').click();
    await dialog.getByRole('textbox', { name: 'Incident 타입 이름' }).fill('사건');

    await page.keyboard.press('Escape');

    // 리네임 인라인 폼만 사라지고, 가드도 뜨지 않으며 다이얼로그는 그대로 열려 있다.
    await expect(dialog.getByTestId('entity-rename-form-Incident')).toBeHidden();
    await expect(page.getByTestId('ontology-close-confirm')).toHaveCount(0);
    await expect(dialog).toBeVisible();
    // 리네임은 확정되지 않았으므로 원래 타입명이 그대로 남는다.
    await expect(dialog.getByTestId('entity-edit-Incident')).toBeVisible();
  });

  test('다이얼로그를 연 직후 스크롤하지 않아도 저장·취소 푸터가 뷰포트 안에 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    await expect(dialog).toBeVisible();

    // 수정 전에는 저장 버튼이 y≈3,763px(뷰포트 밖)에 있어 3,000px을 스크롤해야 닿았다.
    await expect(dialog.getByRole('button', { name: '저장' })).toBeInViewport();
    await expect(dialog.getByRole('button', { name: '취소' })).toBeInViewport();

    // 본문을 끝까지 스크롤해도 푸터는 같은 자리에 고정되어 있다(스크롤 영역 밖 형제).
    const footerTopBefore = await dialog.getByRole('button', { name: '저장' }).boundingBox();
    await dialog.getByTestId('relations-editor').scrollIntoViewIfNeeded();
    const footerTopAfter = await dialog.getByRole('button', { name: '저장' }).boundingBox();
    expect(footerTopAfter?.y).toBeCloseTo(footerTopBefore?.y ?? 0, 0);
    await expect(dialog.getByRole('button', { name: '저장' })).toBeInViewport();
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

  test('비관리자는 지식 모델 탭에서 편집 버튼을 볼 수 없다(ontology:write는 ADMIN 전용)', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await expect(page.getByRole('button', { name: '편집' })).toHaveCount(0);
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

    // 타입 필터 — Building만 켜면 검색과 교집합이 되어 1건만 남는다.
    await page.getByTestId('type-filter-list').getByRole('button', { name: /^Building/ }).click();
    await expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', '1');
    await expect(list.locator('[data-graph-item]')).toHaveCount(1);
    await expect(list.locator('[data-graph-item]')).toHaveText(/강남타워 \(Building\)/);
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

/**
 * #328 회귀: 지식 모델 편집 다이얼로그를 닫으면 포커스가 `편집` 버튼으로 복귀해야 한다.
 *
 * 공유 컴포넌트(dialog.tsx)의 복귀 보정 외에, 이 화면에는 추가 원인이 있었다 —
 * `<OntologyEditDialog key={editOpen ? 'open' : 'closed'}>` 가 **닫는 순간에도** key를 바꿔
 * 리마운트시키는 바람에 복귀 경로가 끊겼다. key는 열 때만 바뀌어야 한다.
 */
test.describe('#328 지식 모델 편집 다이얼로그 포커스 복귀', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('변경 없이 Escape로 닫으면 포커스가 편집 버튼으로 복귀한다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    const trigger = page.getByRole('button', { name: '편집' });
    await trigger.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('ontology-edit-dialog')).toBeVisible();

    await page.keyboard.press('Escape');
    await expect(page.getByTestId('ontology-edit-dialog')).toBeHidden();
    await expect(trigger).toBeFocused();
  });

  test('닫기 가드(중첩 확인)를 거쳐 닫아도 포커스가 편집 버튼으로 복귀한다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    const trigger = page.getByRole('button', { name: '편집' });
    await trigger.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByTestId('ontology-edit-dialog');
    await expect(dialog).toBeVisible();

    // 변경을 만들어 닫기 가드가 뜨도록 한 뒤, 중첩 확인에서 폐기를 선택한다.
    await dialog.getByLabel('도메인').fill('포커스 복귀 검증용 도메인');
    await page.keyboard.press('Escape');
    await page.getByTestId('ontology-close-confirm').getByRole('button', { name: '저장하지 않고 닫기' }).click();

    await expect(dialog).toBeHidden();
    await expect(page.getByTestId('ontology-close-confirm')).toBeHidden();
    await expect(trigger).toBeFocused();
  });
});

/**
 * #329 회귀: 지식 모델 편집의 인라인 검증 오류가 입력과 프로그램적으로 연결되어야 한다.
 *
 * 오류 문구를 빨간 텍스트로 "가까이" 두는 것만으로는 스크린리더에 전달되지 않는다.
 * 매핑 다이얼로그(#300)와 같은 배선(aria-invalid + aria-describedby → 오류 <p>의 id)을 적용한다.
 * WCAG 2.2 SC 3.3.1 / 1.3.1 / 4.1.2.
 */
test.describe('#329 지식 모델 편집 인라인 오류 ARIA 배선', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('속성명이 예약어면 입력에 aria-invalid와 오류 문구를 가리키는 aria-describedby가 붙는다', async ({
    authenticatedPage: page,
  }) => {
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    const incidentRow = dialog.getByTestId('entity-edit-Incident');
    await incidentRow.getByRole('button', { name: 'Incident 속성 추가' }).click();

    const propertyRow = incidentRow.getByTestId('property-row-Incident-0');
    const nameInput = propertyRow.getByLabel('Incident 속성 이름');

    // 빈 이름(추가 직후) — 이미 오류 상태이므로 배선이 살아 있어야 한다.
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    const blankErrorId = await nameInput.getAttribute('aria-describedby');
    expect(blankErrorId).toBeTruthy();
    await expect(page.locator(`#${blankErrorId}`)).toHaveText('속성명을 입력하세요');

    // 예약어 — 같은 배선이 예약어 문구를 가리킨다.
    await nameInput.fill('key');
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    const reservedErrorId = await nameInput.getAttribute('aria-describedby');
    expect(reservedErrorId).toBeTruthy();
    await expect(page.locator(`#${reservedErrorId}`)).toContainText('예약어는 속성명으로 쓸 수 없습니다');

    // 정상 이름으로 고치면 무효 표시가 사라지고 설명 연결도 끊긴다(존재하지 않는 노드를 가리키지 않도록).
    await nameInput.fill('severity');
    await expect(nameInput).not.toHaveAttribute('aria-invalid', 'true');
    expect(await nameInput.getAttribute('aria-describedby')).toBeNull();
  });

  test('관계명이 비면 관계명 입력에 aria-invalid와 오류 문구 연결이 붙는다', async ({
    authenticatedPage: page,
  }) => {
    const schema = createOntologySchema();
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '편집' }).click();
    const dialog = page.getByTestId('ontology-edit-dialog');
    const relationsEditor = dialog.getByTestId('relations-editor');
    // 관계 추가는 관계명을 빈 칸으로 seed한다.
    await relationsEditor.getByRole('button', { name: '관계 추가' }).click();

    const newRow = relationsEditor.getByTestId(`relation-row-${schema.relations.length}`);
    const relationInput = newRow.getByLabel('관계명');
    await expect(relationInput).toHaveAttribute('aria-invalid', 'true');
    const errorId = await relationInput.getAttribute('aria-describedby');
    expect(errorId).toBeTruthy();
    await expect(page.locator(`#${errorId}`)).toHaveText('관계명을 입력하세요');

    await relationInput.fill('CAUSED_BY');
    await expect(relationInput).not.toHaveAttribute('aria-invalid', 'true');
    expect(await relationInput.getAttribute('aria-describedby')).toBeNull();
  });
});
