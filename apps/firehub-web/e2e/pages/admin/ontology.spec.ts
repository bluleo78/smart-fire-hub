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

    await expect(page.getByText('지식 모델이 다른 사용자에 의해 이미 수정되었습니다.')).toBeVisible();
    await expect(dialog).toBeVisible();
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
