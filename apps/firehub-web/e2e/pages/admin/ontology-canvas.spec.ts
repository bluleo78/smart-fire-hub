import type { Page } from '@playwright/test';

import { createOntologySummaries,MAPPING_ONTOLOGY_ID } from '../../factories/mapping.factory';
import {
  createEntityTypeDeletion,
  createEntityTypeMutation,
  createOntologyGraph,
  createOntologySchema,
  createRelationMutation,
  createVersionOnly,
} from '../../factories/ontology.factory';
import { setupAdminAuth, setupOntologyMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

// 캔버스 뷰포트(zoom/pan)가 실제로 멈출 때까지 기다린다 — "몇 ms 지났는가"가 아니라 "더 이상 안
// 움직인다"는 사실 자체를 관측한다.
//
// 실측(page.evaluate로 20ms 간격 20회 샘플링 + 이후 라운드에서 50ms 간격 재확인, --workers로 부하
// 재현): 처음엔 "'수정 모드' 클릭 → 아웃라인 패널 등장 → ResizeObserver 150ms 디바운스 →
// cy.fit()"이 유일한 원인이라고 봤는데, 그 가설로 만든 "클릭 전 zoom을 기준선으로 잡고 바뀔 때까지
// 기다리기"가 실제 --repeat-each=20(기본 워커) 실행에서 200/200 중 11개를 "5초 동안 zoom이 전혀
// 안 바뀜" 타임아웃으로 실패시켰다 — 즉 그 가설이 전제로 삼은 "클릭하면 반드시 리사이즈가 일어난다"는
// 것 자체가 틀렸다(TypeFilterPanel과 ModelOutline은 둘 다 w-64라 폭이 같아서 전환이 리사이즈를
// 유발하지 않는 실행이 실제로 있었다). 추가로 로그를 보니 zoom이 페이지 진입 직후부터 이미 여러 값
// (0.77/0.61/1.245/1.48 등) 중 하나로 요동치고, "수정 모드" 클릭과 무관하게(model-inspector가 보인
// 뒤에도) 250ms 넘게 지나서야 한 번 더 바뀌는 사례가 있었다 — 즉 이 페이지엔 우리가 누르는 버튼과
// 무관한 또 다른 리사이즈 유발원(가장 유력한 후보: 우측 AI 어시스턴트 패널의 초기 폭 계산)이 최소
// 하나 더 있고, 그게 SchemaGraph의 같은 ResizeObserver→fit 파이프라인을 다른 타이밍에 건드린다.
// "어떤 특정 원인이 끝났는가"를 기다리는 방식은 원인이 여러 개라 전부 알아낼 수도, 다 기다렸다고
// 보장할 수도 없다 — 그래서 원인을 특정해 개별로 기다리는 대신 결과(뷰포트가 안 움직인다)를 직접
// 관측한다: requestAnimationFrame으로 매 프레임 zoom/pan을 비교해 "연속 10프레임 동안 값이 전혀
// 안 바뀜"을 만족할 때까지 기다린다. ms 기반 폴링과 다른 점 — 프레임 수 기준이라 시스템이 느려지면
// (부하가 큰 실행일수록) 같은 프레임 수를 채우는 데 자연히 더 오래 걸린다(스스로 부하에 맞춰 늘어난다).
async function waitForCanvasIdle(page: Page, timeoutMs = 8000) {
  await page.evaluate(
    ({ timeoutMs: timeout }) =>
      new Promise<void>((resolve, reject) => {
        const cy = (
          window as unknown as { __ontologySchemaCy?: { zoom(): number; pan(): { x: number; y: number } } }
        ).__ontologySchemaCy;
        if (!cy) {
          reject(new Error('__ontologySchemaCy가 아직 없다(dev 노출 전).'));
          return;
        }
        const REQUIRED_STABLE_FRAMES = 10;
        const deadline = performance.now() + timeout;
        let stableFrames = 0;
        let last = '';
        const tick = () => {
          const p = cy.pan();
          const cur = `${cy.zoom()}:${p.x}:${p.y}`;
          if (cur === last) {
            stableFrames += 1;
          } else {
            stableFrames = 0;
            last = cur;
          }
          if (stableFrames >= REQUIRED_STABLE_FRAMES) {
            resolve();
            return;
          }
          if (performance.now() > deadline) {
            reject(new Error(`캔버스 뷰포트가 ${timeout}ms 안에 안정되지 않았다(마지막 상태: ${cur}).`));
            return;
          }
          requestAnimationFrame(tick);
        };
        requestAnimationFrame(tick);
      }),
    { timeoutMs },
  );
}

// "수정 모드"를 켜고 3-pane 편집기가 커밋될 때까지, 그리고 그로 인해 트리거될 수 있는 리사이즈까지
// 안정될 때까지 기다린다.
async function enterEditMode(page: Page) {
  await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');
  await page.getByRole('button', { name: '수정 모드' }).click();
  await expect(page.getByTestId('model-inspector')).toBeVisible();
  await waitForCanvasIdle(page);
}

async function readNode(page: Page, label: string) {
  return page.evaluate((lbl) => {
    const cy = (
      window as unknown as { __ontologySchemaCy: { nodes(sel: string): { id(): string; renderedPosition(): { x: number; y: number } } } }
    ).__ontologySchemaCy;
    const node = cy.nodes(`[label = "${lbl}"]`);
    return { id: node.id(), position: node.renderedPosition() };
  }, label);
}

// cy 노드의 label(타입 이름)로 화면(페이지) 좌표를 얻는다 — id를 하드코딩하지 않는다는 이 스펙 파일의
// 원칙(S3 Task 1 라운드에서 확립)을 드래그 좌표 계산에도 그대로 지킨다. renderedPosition()은
// schema-graph testid 컨테이너 기준 좌표라, 그 컨테이너의 페이지상 boundingBox를 더해야 실제 마우스
// 좌표가 된다. 여기서도 매번 waitForCanvasIdle을 부르는 이유 — enterEditMode가 이미 한 번 안정을
// 확인했더라도, dragConnect가 source/target 두 번 이 함수를 부르는 사이에 (또는 read 모드처럼
// enterEditMode 자체를 거치지 않는 호출부에서) 위에서 실측한 "클릭과 무관한 두 번째 리사이즈원"이
// 뒤늦게 도착할 수 있다 — 이미 안정 상태라면 이 호출은 사실상 즉시 반환되므로 비용이 크지 않다.
async function nodeScreenPosition(page: Page, label: string) {
  await waitForCanvasIdle(page);
  const containerBox = await page.getByTestId('schema-graph').boundingBox();
  const rendered = await readNode(page, label);
  if (!containerBox) throw new Error('schema-graph 컨테이너를 찾을 수 없습니다.');
  return {
    id: Number(rendered.id),
    x: containerBox.x + rendered.position.x,
    y: containerBox.y + rendered.position.y,
  };
}

// 소스 노드에서 타겟 노드로 마우스를 눌러 끌었다 놓는다 — edgehandles의 draw mode(전체 노드 바디가
// 핸들 역할)가 tapstart를 가로채 제스처를 시작하므로, 실제 마우스 이동 없이는(steps 없이 곧장 up)
// cytoscape가 tap과 drag를 구분하지 못해 제스처가 시작되지 않는다.
async function dragConnect(page: Page, sourceLabel: string, targetLabel: string) {
  const source = await nodeScreenPosition(page, sourceLabel);
  const target = await nodeScreenPosition(page, targetLabel);
  await page.mouse.move(source.x, source.y);
  await page.mouse.down();
  await page.mouse.move((source.x + target.x) / 2, (source.y + target.y) / 2, { steps: 5 });
  await page.mouse.move(target.x, target.y, { steps: 5 });
  await page.mouse.up();
  return { subjectTypeId: source.id, objectTypeId: target.id };
}

const getEdgeCount = (page: Page) =>
  page.evaluate(() => (window as unknown as { __ontologySchemaCy: { edges(): { length: number } } }).__ontologySchemaCy.edges().length);

/**
 * 캔버스 노드 id 이관(S3 Task 1) 회귀 가드.
 * SchemaGraph의 cytoscape 노드 id를 타입 "이름"에서 EntityTypeDef.id로 옮긴 배경(SchemaGraph.tsx의
 * `elements` useMemo 주석 참고 — 줄 번호 대신 심볼명으로 참조한다, Task 1 IMP-2) — 리네임 시
 * relations[].subject/object remap이 한 순간이라도 entities[].type과 어긋나면
 * cytoscape가 add() 시점에 예외를 던져 PageErrorBoundary까지 크래시가 번지는 결함(S2 리뷰 C-2)이 있었다.
 * 이 스펙은 (1) 드릴다운이 여전히 타입 이름으로 나가는지, (2) 리네임이 더 이상 캔버스를 깨뜨리지 않는지,
 * (3) id가 없는 레거시 관계(엣지 폴백)도, (4) id가 없는 레거시 엔티티(노드 폴백)도 이름 폴백으로 정상
 * 렌더되는지 네 갈래를 증명한다.
 */
test.describe('SchemaGraph — 캔버스 노드 id 이관', () => {
  const expectInstanceNodeCount = (page: import('@playwright/test').Page, count: number) =>
    expect(page.getByTestId('instance-graph')).toHaveAttribute('data-node-count', String(count));

  test('읽기 모드에서 노드 tap이 타입 이름으로 드릴다운한다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    const graph = createOntologyGraph();
    const incidentCount = graph.nodes.filter((n) => n.type === 'Incident').length;
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');

    // 노드 id는 이제 타입 이름이 아니라 entityTypeId다(S3 Task 1) — 값 자체는 fixture의 엔티티 배열
    // 순서에 우연히 결합된 디테일이라 여기서 검증할 대상이 아니다. label(타입 이름) 속성으로 노드를
    // 찾아 tap한다. 드릴다운은 API를 타지 않는 순수 클라이언트 필터(OntologyPage.drillDown →
    // setActiveTypes)라 요청 캡처 대신 필터 결과로 증명한다: onTypeClick이 숫자 id를 그대로 넘기면
    // activeTypes가 {"1"}이 되어 어떤 노드의 type과도 매치하지 않으므로 필터 후 0개가 나온다 —
    // 타입 이름 해소가 깨졌을 때 즉시 드러나는 형태의 단언이다.
    await page.evaluate(() => {
      (window as unknown as { __ontologySchemaCy: { nodes(sel: string): { emit(e: string): void } } }).__ontologySchemaCy
        .nodes('[label = "Incident"]')
        .emit('tap');
    });

    await expect(page.getByRole('tab', { name: '그래프 탐색' })).toHaveAttribute('aria-selected', 'true');
    await expectInstanceNodeCount(page, incidentCount);
  });

  test('타입을 리네임해도 캔버스가 크래시하지 않고 관계 엣지가 유지된다(C-2 회귀 가드)', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);
    // Cause(id=3) — CAUSED_BY(id=2)의 목적어(object). 관계가 붙은 타입을 골라야 remap 경로를 태운다.
    // 아웃라인 testid는 EntityInspector가 대화하는 요소 편집 API의 실제 id라 여기서는 그대로 쓴다
    // (fixture 결합이 아니라 이 테스트가 대상으로 지목하는 실제 엔티티).
    await page.getByTestId('outline-entity-3').click();

    // 리네임 "전" cy 노드 id를 라벨로 찾아 캡처해 둔다 — 이후 이 id로 같은 노드를 다시 찾아 "id는 그대로,
    // 라벨만 바뀌었는가"를 증명한다. id를 하드코딩(#3)하지 않는 이유: 이 값은 fixture의 엔티티 배열
    // 순서에 우연히 결합된 디테일이고, 이 테스트가 검증하려는 건 "id 안정성"이지 "id가 3인가"가 아니다.
    const before = await page.evaluate(() => {
      const cy = (window as unknown as {
        __ontologySchemaCy: { nodes(sel: string): { id(): string }; edges(): { length: number } };
      }).__ontologySchemaCy;
      return { nodeId: cy.nodes('[label = "Cause"]').id(), edgeCount: cy.edges().length };
    });

    await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/1/entity-types/3',
      createEntityTypeMutation({ id: 3, type: '원인유형', description: '', naming: '본문 표기 보존', resolution: 'embedding', properties: [] }),
    );

    await page.getByLabel('타입 이름', { exact: true }).fill('원인유형');
    await page.getByLabel('타입 이름', { exact: true }).blur();
    await expect(page.getByTestId('save-status-chip')).toHaveAttribute('data-state', 'saved');

    // 크래시하면 PageErrorBoundary가 이 헤딩을 띄운다 — 뜨지 않아야 한다.
    await expect(page.getByRole('heading', { name: '페이지를 불러오는 중 문제가 발생했습니다' })).toHaveCount(0);
    await expect(page.getByTestId('schema-graph')).toBeVisible();

    const after = await page.evaluate((nodeId) => {
      const cy = (window as unknown as {
        __ontologySchemaCy: { edges(): { length: number }; getElementById(id: string): { data(k: string): unknown } };
      }).__ontologySchemaCy;
      return { edgeCount: cy.edges().length, label: cy.getElementById(nodeId).data('label') };
    }, before.nodeId);
    // 엣지가 고아 처리로 걸러지지 않고 그대로 유지된다 — id 기반이라 리네임에 영향받지 않는다.
    expect(after.edgeCount).toBe(before.edgeCount);
    // 리네임 전에 캡처한 것과 같은 cy 노드 id가 여전히 존재하고, 라벨만 새 이름으로 바뀐다.
    expect(after.label).toBe('원인유형');
  });

  test('subjectTypeId가 없는 레거시 관계도 이름으로 해소돼 엣지가 그려진다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    const baseSchema = createOntologySchema();
    // subjectTypeId/objectTypeId를 뺀 레거시 목 데이터 — 서버는 항상 채워 주지만 타입상 옵셔널이라
    // 이 폴백 경로(이름 → idByName 조회)가 없으면 엣지가 전부 고아로 걸러진다.
    const legacySchema = {
      ...baseSchema,
      relations: baseSchema.relations.map((r) => {
        const rest = { ...r };
        delete rest.subjectTypeId;
        delete rest.objectTypeId;
        return rest;
      }),
    };
    await mockApi(page, 'GET', '/api/v1/ontology', legacySchema);
    await mockApi(page, 'GET', '/api/v1/ontology/1', legacySchema);
    await mockApi(page, 'GET', '/api/v1/ontology/graph', createOntologyGraph());
    await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
    await page.goto('/knowledge-graph/model');

    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');
    const edgeCount = await page.evaluate(
      () => (window as unknown as { __ontologySchemaCy: { edges(): { length: number } } }).__ontologySchemaCy.edges().length,
    );
    expect(edgeCount).toBe(legacySchema.relations.length);
  });

  test('entities[]에 id가 없는 레거시 스키마도 name: 접두사 폴백으로 노드가 그려지고 관계가 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    const baseSchema = createOntologySchema();
    // entities[].id를 뺀 레거시 목 데이터 — 위 레거시 관계 테스트가 태우는 "엣지 폴백"과 달리 이번엔
    // "노드 폴백"(`name:${type}` 접두사) 경로를 태운다. relations[].subjectTypeId/objectTypeId는
    // factory 기본값 그대로 둔다 — 그러면 숫자 id는 채워져 있는데 그 id를 가리키는 노드가 없는(엔티티
    // 쪽만 id가 빠진) 조합이 되어, 리뷰 MIN-1 하드닝(숫자 id가 있어도 nodeIds에 없으면 이름으로
    // 재해소)까지 함께 검증한다. 이 하드닝이 없으면 존재하지 않는 숫자 노드를 가리키는 엣지로 취급돼
    // 전량 고아 처리된다.
    const legacySchema = {
      ...baseSchema,
      entities: baseSchema.entities.map((e) => {
        const rest = { ...e };
        delete rest.id;
        return rest;
      }),
    };
    await mockApi(page, 'GET', '/api/v1/ontology', legacySchema);
    await mockApi(page, 'GET', '/api/v1/ontology/1', legacySchema);
    await mockApi(page, 'GET', '/api/v1/ontology/graph', createOntologyGraph());
    await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
    await page.goto('/knowledge-graph/model');

    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');
    const result = await page.evaluate(() => {
      const cy = (window as unknown as {
        __ontologySchemaCy: { nodes(sel: string): { id(): string }; edges(): { length: number } };
      }).__ontologySchemaCy;
      return {
        edgeCount: cy.edges().length,
        // `name:` 접두사가 실제로 노드 id에 쓰였는지 직접 확인 — "3"(진짜 id)과 "이름이 3인 타입"의
        // 충돌을 막는 설계의 핵심이라 접두사 자체를 검증하지 않으면 이 설계가 지켜지는지 아무도 모른다.
        incidentNodeId: cy.nodes('[label = "Incident"]').id(),
      };
    });
    expect(result.edgeCount).toBe(legacySchema.relations.length);
    expect(result.incidentNodeId).toBe('name:Incident');
  });
});

/**
 * 캔버스 핸들 드래그 관계 생성(S3 Task 2) — RelationInspector의 드롭다운 2개+텍스트 입력 폼과
 * 별개인 두 번째 관계 생성 경로. edgehandles를 편집 모드에서만 draw mode로 켜고(SchemaGraph.tsx의
 * [editing] effect), 드롭 시 뜨는 CanvasInlineInput에 이름을 입력해 mutations.addRelation을 부른다.
 */
test.describe('SchemaGraph — 캔버스 드래그 관계 생성', () => {
  test('핸들 드래그로 관계를 만들면 subjectTypeId/objectTypeId가 담긴 POST가 나가고 엣지가 하나 늘어난다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const before = await getEdgeCount(page);
    const { subjectTypeId, objectTypeId } = await dragConnect(page, 'Incident', 'Equipment');

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/ontology/1/relations',
      createRelationMutation({
        id: 100,
        subject: 'Incident',
        relation: 'LOCATED_NEAR',
        object: 'Equipment',
        description: '',
        subjectTypeId,
        objectTypeId,
      }),
      { capture: true },
    );

    const input = page.getByTestId('canvas-connect-input');
    await expect(input).toBeVisible();
    await input.fill('LOCATED_NEAR');
    await input.press('Enter');

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ subjectTypeId, relation: 'LOCATED_NEAR', objectTypeId, description: '' });

    await expect(page.getByTestId('relation-inspector')).toBeVisible();
    await expect.poll(() => getEdgeCount(page)).toBe(before + 1);
  });

  test('Esc로 취소하면 요청이 나가지 않고 엣지도 남지 않는다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const capture = await mockApi(page, 'POST', '/api/v1/ontology/1/relations', createRelationMutation({ id: 100, subject: 'Incident', relation: 'LOCATED_NEAR', object: 'Equipment', description: '', subjectTypeId: 1, objectTypeId: 5 }), {
      capture: true,
    });

    const before = await getEdgeCount(page);
    await dragConnect(page, 'Incident', 'Equipment');

    const input = page.getByTestId('canvas-connect-input');
    await expect(input).toBeVisible();
    await input.fill('LOCATED_NEAR');
    await input.press('Escape');

    await expect(input).toHaveCount(0);
    expect(capture.requests).toHaveLength(0);
    expect(await getEdgeCount(page)).toBe(before);
  });

  test('이미 있는 트리플을 다시 그리면 로컬 검증이 막고 요청이 나가지 않는다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    // Incident -OCCURRED_AT-> Building은 fixture(createTriples id=1)에 이미 존재하는 트리플이다.
    const capture = await mockApi(page, 'POST', '/api/v1/ontology/1/relations', createRelationMutation({ id: 100, subject: 'Incident', relation: 'LOCATED_NEAR', object: 'Equipment', description: '', subjectTypeId: 1, objectTypeId: 5 }), {
      capture: true,
    });

    const before = await getEdgeCount(page);
    await dragConnect(page, 'Incident', 'Building');

    const input = page.getByTestId('canvas-connect-input');
    await input.fill('OCCURRED_AT');
    await input.press('Enter');

    await expect(page.getByTestId('canvas-connect-input-error')).toHaveText('중복된 관계: Incident -OCCURRED_AT-> Building');
    // 검증 실패 시 입력을 닫지 않는다(브리프 Step 4) — 사용자가 값을 고쳐 바로 재시도할 수 있어야 한다.
    await expect(input).toBeVisible();
    expect(capture.requests).toHaveLength(0);
    expect(await getEdgeCount(page)).toBe(before);
  });

  test('읽기 모드에서는 핸들이 나타나지 않는다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    // 수정 모드를 켜지 않는다 — 읽기 모드 그대로 드래그를 시도한다. dev 전용 __ontologySchemaCy는
    // cy 인스턴스 생성 effect가 돌고 나서야 채워지므로, 캔버스가 렌더됐다는 신호(data-node-count)를
    // 먼저 기다린다 — 다른 테스트들은 "수정 모드" 버튼 클릭의 actionability 대기가 이 역할을 겸했다.
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');

    const before = await getEdgeCount(page);
    await dragConnect(page, 'Incident', 'Equipment');

    await expect(page.getByTestId('canvas-connect-input')).toHaveCount(0);
    expect(await getEdgeCount(page)).toBe(before);
  });

  // edgehandles의 draw mode(전체 노드 바디가 핸들)를 켜면서 기존 노드 tap 선택 핸들러와 충돌하지
  // 않는지 — 소스 레벨로는 확인했지만(SchemaGraph.tsx 위쪽 주석) 실제 동작으로도 증명해야 한다
  // (팀 리뷰 요구). 이동 없는 클릭(down→up, 같은 좌표)은 cytoscape 'tap' 제스처만 발생시키고
  // edgehandles의 target 스냅은 실제 이동(tapdrag)이 있어야만 채워지므로 관계 생성으로 새지 않는다.
  test('편집 모드에서 노드를 드래그 없이 클릭하면 관계 생성이 아니라 인스펙터 선택이 된다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/ontology/1/relations',
      createRelationMutation({ id: 100, subject: 'Incident', relation: 'SELF', object: 'Incident', description: '', subjectTypeId: 1, objectTypeId: 1 }),
      { capture: true },
    );

    const before = await getEdgeCount(page);
    const incident = await nodeScreenPosition(page, 'Incident');
    // 이동 없이 그 자리에서 누르고 뗀다(page.mouse.click) — dragConnect처럼 중간 move 스텝이 없다.
    await page.mouse.click(incident.x, incident.y);

    await expect(page.getByTestId('canvas-connect-input')).toHaveCount(0);
    // EntityInspector가 편집 폼(entity-inspector)으로 뜬다 — 관계 생성 흐름이 아니라 기존 선택 경로다.
    await expect(page.getByTestId('entity-inspector')).toBeVisible();
    expect(capture.requests).toHaveLength(0);
    expect(await getEdgeCount(page)).toBe(before);
  });

  // autoungrabify:true가 edgehandles 도입 후에도 유지되는지 — 레이아웃은 항상 breadthfirst가 계산하므로
  // 드래그로 노드가 움직이면 회귀다(팀 리뷰 요구). model position()(줌·팬에 영향받지 않는 레이아웃
  // 좌표)을 드래그 제스처 전후로 비교한다 — grabbable 노드였다면 이 값이 마우스를 따라 바뀐다.
  test('편집 모드에서 핸들 드래그를 해도 소스 노드의 레이아웃 위치가 바뀌지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const getIncidentPosition = () =>
      page.evaluate(
        () =>
          (
            window as unknown as { __ontologySchemaCy: { nodes(sel: string): { position(): { x: number; y: number } } } }
          ).__ontologySchemaCy.nodes('[label = "Incident"]').position(),
      );

    const before = await getIncidentPosition();
    await dragConnect(page, 'Incident', 'Equipment');
    // 이 테스트의 관심사는 위치뿐이다 — 뜬 입력은 취소해 정리한다.
    await page.getByTestId('canvas-connect-input').press('Escape');

    const after = await getIncidentPosition();
    expect(after).toEqual(before);
  });

  // C-1 회귀 가드(task-2-review.md) — 저장 실패 후 pendingConnection이 유지되며(사용자가 친 이름을
  // 지키기 위해) 같은 CanvasInlineInput 인스턴스가 남는데, committingRef가 리셋되지 않으면 그 이후
  // 어떤 blur도 취소로 이어지지 않아 "바깥 클릭 = 취소"(브리프 §상호작용)가 영구히 죽는다.
  test('저장 실패(500) 후 바깥 클릭으로 입력이 닫힌다(C-1 회귀 가드)', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    await mockApi(page, 'POST', '/api/v1/ontology/1/relations', { message: '저장에 실패했습니다.' }, { status: 500 });

    await dragConnect(page, 'Incident', 'Equipment');
    const input = page.getByTestId('canvas-connect-input');
    await expect(input).toBeVisible();
    await input.fill('LOCATED_NEAR');
    await input.press('Enter');

    // 실패 후 재활성화됐는지 확인 — disabled가 풀리지 않으면 아래 blur 자체가 무의미해진다.
    await expect(input).toBeEnabled();

    // 바깥(툴바 제목)을 클릭해 실제 blur를 일으킨다 — 캔버스 빈 곳이 아니라 명확히 입력 밖의
    // 실제 DOM 요소를 눌러야 브라우저가 진짜 포커스 이동(blur)을 발생시킨다.
    await page.getByRole('heading', { name: '지식그래프' }).click();

    await expect(input).toHaveCount(0);
  });

  // I-1 회귀 가드(task-2-review.md) — 이전에는 메커니즘(disabled된 input은 keydown을 발생시키지
  // 않음, React 19 discrete 이벤트 flush 순서)만 근거로 들었을 뿐 실제로 증명하는 테스트가 없었다.
  // 응답을 지연시켜 놓고 두 번째 Enter를 최대한 빠르게 이어 보내 POST가 1건만 나가는지 직접 확인한다.
  test('저장 응답이 지연되는 동안 두 번째 Enter를 보내도 POST는 1건만 나간다(이중 제출 가드)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    let postCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/relations',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        postCount += 1;
        await new Promise((resolve) => setTimeout(resolve, 200));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            createRelationMutation({ id: 100, subject: 'Incident', relation: 'LOCATED_NEAR', object: 'Equipment', description: '', subjectTypeId: 1, objectTypeId: 5 }),
          ),
        });
      },
    );

    await dragConnect(page, 'Incident', 'Equipment');
    const input = page.getByTestId('canvas-connect-input');
    await expect(input).toBeVisible();
    await input.fill('LOCATED_NEAR');
    await input.press('Enter');
    // 응답이 아직 안 왔으므로(200ms 지연) 입력이 잠겨 있어야 한다 — 잠기지 않았다면 곧이어 보내는
    // 두 번째 Enter가 실제로 두 번째 요청을 만들어 낼 것이다.
    await expect(input).toBeDisabled();
    // disabled 상태에서는 브라우저가 애초에 keydown을 발생시키지 않는다 — page.keyboard.press로
    // 같은 확인을 한 번 더 시도해 "이 시점에 어떤 두 번째 Enter도 요청을 만들지 않는다"를 증명한다.
    await page.keyboard.press('Enter');

    await expect(page.getByTestId('relation-inspector')).toBeVisible();
    expect(postCount).toBe(1);
  });

  // I-3 회귀 가드(task-2-review.md) — canEdit이 사용자 조작 없이 꺼지는 경로(archived 전환, 다른
  // 온톨로지 선택 등)를 "수정 모드" 토글로 근사한다. pendingConnection 정리를 blur-취소 경로와
  // 분리해서 증명해야 한다 — "수정 모드" 버튼을 그냥 클릭하면 그 클릭 자체가 입력 밖을 눌러 blur를
  // 일으키고, blur의 onCancel이 이미 pendingConnection을 비워 버려서 [editing] effect의 정리 로직이
  // 실제로 기여했는지 이 테스트가 증명하지 못하게 된다(직접 확인: 이 effect를 되돌려도 이 시나리오는
  // 통과했다). 그래서 요청을 응답 없이 묶어 둬 disabled=true(제출 중) 상태를 유지한다 —
  // CanvasInlineInput의 onBlur는 disabled 중엔 취소하지 않으므로(C-1 수정 참고), 이 상태에서 입력이
  // 사라진다면 그건 blur가 아니라 [editing] effect의 정리 로직 때문일 수밖에 없다.
  test('저장이 진행 중인 상태로 편집 모드가 꺼지면 입력도 함께 사라진다(I-3 회귀 가드)', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    let postCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/relations',
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        postCount += 1;
        // 응답하지 않는다 — submitting=true(disabled=true)가 테스트 끝까지 유지되게 한다.
        return new Promise(() => {});
      },
    );

    await dragConnect(page, 'Incident', 'Equipment');
    const input = page.getByTestId('canvas-connect-input');
    await expect(input).toBeVisible();
    await input.fill('LOCATED_NEAR');
    await input.press('Enter');
    await expect(input).toBeDisabled();

    // 편집 모드를 끈다 — disabled(제출 중) 상태라 blur-취소 경로는 무력하다.
    await page.getByRole('button', { name: '수정 모드' }).click();

    await expect(input).toHaveCount(0);
    expect(postCount).toBe(1);
  });
});

/**
 * 캔버스 인라인 리네임 + 빈 곳 더블클릭 타입 추가(S3 Task 3) — 관계 드래그 연결(Task 2)과 별개인
 * 세 번째/네 번째 편집 경로. dbltap이 실제로 무엇을 부르는지는 SchemaGraph.tsx 위쪽 주석대로
 * cytoscape 소스에서 확인했다 — 마우스 경로는 두 번째 click이 cy.multiClickDebounceTime()(기본
 * 250ms) 안에 들어오면 'dbltap'을 추가로 쏜다. page.mouse.click(x, y, { clickCount: 2 })가 그 조건을
 * 만족시킨다(같은 호출 안에서 두 클릭이 연속 발생해 250ms를 넉넉히 밑돈다) — 아래 dblclickAt이 이를 감싼다.
 */
test.describe('SchemaGraph — 캔버스 인라인 리네임 + 빈 곳 더블클릭 타입 추가', () => {
  // clickCount: 2로 실제 더블클릭 DOM 이벤트 시퀀스(mousedown/up ×2)를 만든다 — cytoscape가 자체
  // 타임스탬프로 dbltap을 판정하므로 emit()으로 흉내 내지 않는다(다른 테스트는 tap emit을 쓰지만
  // 그건 단일 tap이라 판정 로직이 없다).
  async function dblclickAt(page: Page, x: number, y: number) {
    await page.mouse.click(x, y, { clickCount: 2 });
  }

  test('노드를 더블클릭해 이름을 바꾸면 PATCH가 새 이름을 담아 나가고 라벨이 바뀐다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const incident = await nodeScreenPosition(page, 'Incident');

    const capture = await mockApi(
      page,
      'PATCH',
      `/api/v1/ontology/1/entity-types/${incident.id}`,
      createEntityTypeMutation({ id: 1, type: '사건유형', description: '사건/이벤트 (예: 발생한 화재)', naming: '문서마다 고유', resolution: 'exact', properties: [] }),
      { capture: true },
    );

    await dblclickAt(page, incident.x, incident.y);
    const input = page.getByTestId('canvas-rename-input');
    await expect(input).toBeVisible();
    await expect(input).toHaveValue('Incident');
    await input.fill('사건유형');
    await input.press('Enter');

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ type: '사건유형' });

    await expect(input).toHaveCount(0);
    const label = await page.evaluate(
      (nodeId) =>
        (
          window as unknown as { __ontologySchemaCy: { getElementById(id: string): { data(k: string): unknown } } }
        ).__ontologySchemaCy.getElementById(nodeId).data('label'),
      String(incident.id),
    );
    expect(label).toBe('사건유형');
  });

  test('이미 있는 이름으로 리네임하면 로컬 검증이 막고 요청이 나가지 않으며 Esc로 닫으면 원래 이름이 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const incident = await nodeScreenPosition(page, 'Incident');
    const capture = await mockApi(page, 'PATCH', `/api/v1/ontology/1/entity-types/${incident.id}`, createEntityTypeMutation({ id: 1, type: 'Building', description: '', naming: '', resolution: 'exact', properties: [] }), {
      capture: true,
    });

    await dblclickAt(page, incident.x, incident.y);
    const input = page.getByTestId('canvas-rename-input');
    await expect(input).toBeVisible();
    // Building은 fixture(createEntityTypeDefs id=2)에 이미 존재하는 타입명이다.
    await input.fill('Building');
    await input.press('Enter');

    await expect(page.getByTestId('canvas-rename-input-error')).toHaveText('이미 존재하는 타입입니다: Building');
    // 검증 실패 시 입력을 닫지 않는다(브리프 Step 4, Task 2의 동일 원칙) — 사용자가 값을 고쳐 바로
    // 재시도할 수 있어야 한다.
    await expect(input).toBeVisible();
    expect(capture.requests).toHaveLength(0);

    await input.press('Escape');
    await expect(input).toHaveCount(0);
    const label = await page.evaluate(
      (nodeId) =>
        (
          window as unknown as { __ontologySchemaCy: { getElementById(id: string): { data(k: string): unknown } } }
        ).__ontologySchemaCy.getElementById(nodeId).data('label'),
      String(incident.id),
    );
    expect(label).toBe('Incident');
  });

  test('이름을 바꾸지 않고 커밋하면 요청이 나가지 않는다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const incident = await nodeScreenPosition(page, 'Incident');
    const capture = await mockApi(page, 'PATCH', `/api/v1/ontology/1/entity-types/${incident.id}`, createEntityTypeMutation({ id: 1, type: 'Incident', description: '', naming: '', resolution: 'exact', properties: [] }), {
      capture: true,
    });

    await dblclickAt(page, incident.x, incident.y);
    const input = page.getByTestId('canvas-rename-input');
    await expect(input).toBeVisible();
    // 프리필된 값 그대로 커밋 — 편집하지 않는다.
    await input.press('Enter');

    await expect(input).toHaveCount(0);
    expect(capture.requests).toHaveLength(0);
  });

  test('빈 캔버스를 더블클릭해 타입을 만들면 description/naming/resolution 기본값을 담은 POST가 나가고 새 노드가 그려진다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);
    await waitForCanvasIdle(page);

    // breadthfirst 레이아웃은 padding(40px)을 두고 fit하므로 컨테이너 좌상단 모서리 근처는 어떤
    // 실행에서도 배경일 가능성이 매우 높다 — 노드 라벨로 좌표를 얻는 다른 헬퍼들과 달리 여기는
    // "노드가 아닌 지점"이 필요해 좌표를 직접 고른다.
    const containerBox = await page.getByTestId('schema-graph').boundingBox();
    if (!containerBox) throw new Error('schema-graph 컨테이너를 찾을 수 없습니다.');
    const bgX = containerBox.x + 12;
    const bgY = containerBox.y + 12;

    const before = await page.getByTestId('schema-graph').getAttribute('data-node-count');

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/ontology/1/entity-types',
      createEntityTypeMutation({ id: 7, type: 'Sensor', description: '', naming: '', resolution: 'embedding', properties: [] }),
      { capture: true },
    );

    await dblclickAt(page, bgX, bgY);
    const input = page.getByTestId('canvas-create-input');
    await expect(input).toBeVisible();
    await input.fill('Sensor');
    await input.press('Enter');

    const req = await capture.waitForRequest();
    // CreateEntityTypeForm(EntityInspector.tsx)과 같은 기본값(description/naming: '', resolution:
    // 'embedding') — 브리프 설계 노트가 요구하는 "두 생성 경로 동일 기본값"을 여기서 직접 단언한다.
    expect(req.payload).toEqual({ type: 'Sensor', description: '', naming: '', resolution: 'embedding' });

    await expect(input).toHaveCount(0);
    // 성공 시 새 타입이 선택 상태로 전환되어 인스펙터가 뜬다(onCreateEntityAt → selectModelElement).
    await expect(page.getByTestId('entity-inspector')).toBeVisible();
    await expect(page.getByLabel('타입 이름')).toHaveValue('Sensor');
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', String(Number(before) + 1));
  });

  test('읽기 모드에서는 노드 더블클릭도 빈 곳 더블클릭도 아무 입력도 열지 않는다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    // 수정 모드를 켜지 않는다 — 읽기 모드 그대로 더블클릭을 시도한다.
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');
    await waitForCanvasIdle(page);

    // 노드는 실제 물리 더블클릭 대신 dbltap을 직접 emit한다 — 읽기 모드에서 노드 tap은 이미 다른
    // 동작(드릴다운, onTypeClick → '그래프 탐색' 탭 전환)을 갖고 있어(위 "읽기 모드에서 노드 tap이…"
    // 테스트 참고), 실제 물리 더블클릭을 쓰면 첫 tap에서 이미 탭이 전환되어 schema-graph 자체가
    // TabsContent 밖으로 사라진다 — 그 상태에서 "입력이 안 열렸다"는 이 테스트가 증명하려는 것(dbltap
    // 핸들러가 editing 가드로 막혔다)이 아니라 "페이지를 이미 떠났다"는 별개 사실만 증명하게 된다.
    await page.evaluate(() => {
      (window as unknown as { __ontologySchemaCy: { nodes(sel: string): { emit(e: string): void } } }).__ontologySchemaCy
        .nodes('[label = "Incident"]')
        .emit('dbltap');
    });
    await expect(page.getByTestId('canvas-rename-input')).toHaveCount(0);
    // 드릴다운도 일어나지 않았어야 한다(dbltap 전에 발생하는 단일 tap과 달리, dbltap 자체는 어떤
    // 핸들러도 없어야 정상) — schema-graph가 여전히 그대로 남아 있는지로 확인한다.
    await expect(page.getByTestId('schema-graph')).toBeVisible();

    // 빈 곳은 배경 tap/dbltap에 read 모드용 핸들러가 원래 없어(cy.on('tap','node'|'edge')만 존재)
    // 물리 더블클릭을 그대로 써도 부작용이 없다.
    const containerBox = await page.getByTestId('schema-graph').boundingBox();
    if (!containerBox) throw new Error('schema-graph 컨테이너를 찾을 수 없습니다.');
    await dblclickAt(page, containerBox.x + 12, containerBox.y + 12);
    await expect(page.getByTestId('canvas-create-input')).toHaveCount(0);
  });

  // I-1 회귀 가드(task-3-review.md) — ehcomplete에는 hasAnyPendingInput() 가드가 없어 "리네임/생성
  // 입력이 열려 있으면 연결 입력이 안 열린다"는 방향이 뚫려 있었다. disabled(제출 중) 구간은
  // CanvasInlineInput의 onBlur 취소가 꺼져 있어(C-1 수정) 평범한 blur로는 이 구멍이 재현되지 않고,
  // 저장 응답을 지연시켜야 확실히 재현된다 — 그 구간을 그대로 재현해 연결 입력이 함께 뜨지 않는지
  // 직접 확인한다. 리네임 대상은 드래그 경로(Incident→Equipment)와 겹치지 않는 Building을 골랐다 —
  // 같은 노드를 리네임하면서 드래그하면 CanvasInlineInput 오버레이가 마우스 이벤트를 가로채 이
  // 테스트가 증명하려는 것과 무관한 실패를 낳는다.
  test('리네임 입력이 제출 중일 때 다른 두 노드를 드래그해도 연결 입력이 함께 뜨지 않는다(I-1 회귀 가드)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const building = await nodeScreenPosition(page, 'Building');

    await page.route(
      (url) => url.pathname === `/api/v1/ontology/1/entity-types/${building.id}`,
      (route) => {
        if (route.request().method() !== 'PATCH') return route.fallback();
        // 응답하지 않는다 — submitting=true(disabled=true)가 테스트 끝까지 유지되게 한다.
        return new Promise(() => {});
      },
    );

    await dblclickAt(page, building.x, building.y);
    const renameInput = page.getByTestId('canvas-rename-input');
    await expect(renameInput).toBeVisible();
    await renameInput.fill('건물유형');
    await renameInput.press('Enter');
    await expect(renameInput).toBeDisabled();

    // 제출 중(disabled, onBlur 취소가 꺼진 구간)에 리네임 대상과 무관한 두 노드를 드래그해 연결을
    // 시도한다 — ehcomplete가 가드 없이 pendingConnection을 세우면 리네임 입력과 연결 입력이 동시에
    // 뜬다.
    await dragConnect(page, 'Incident', 'Equipment');

    await expect(page.getByTestId('canvas-connect-input')).toHaveCount(0);
    await expect(renameInput).toBeVisible();
  });

  // I-2 회귀 가드(task-3-review.md) — Task 2가 연결 입력에 대해 이미 가진 이중 제출 가드 테스트(이
  // 파일 L481-520)를 리네임 경로가 물려받지 않았다. disabled 전파가 깨지면(예: CanvasInlineInput에
  // disabled={submitting}을 빠뜨리면) 이 테스트가 실패해야 한다.
  test('리네임 저장 응답이 지연되는 동안 두 번째 Enter를 보내도 PATCH는 1건만 나간다(이중 제출 가드)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const incident = await nodeScreenPosition(page, 'Incident');

    let patchCount = 0;
    await page.route(
      (url) => url.pathname === `/api/v1/ontology/1/entity-types/${incident.id}`,
      async (route) => {
        if (route.request().method() !== 'PATCH') return route.fallback();
        patchCount += 1;
        await new Promise((resolve) => setTimeout(resolve, 200));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            createEntityTypeMutation({ id: incident.id, type: '사건유형', description: '', naming: '', resolution: 'exact', properties: [] }),
          ),
        });
      },
    );

    await dblclickAt(page, incident.x, incident.y);
    const input = page.getByTestId('canvas-rename-input');
    await expect(input).toBeVisible();
    await input.fill('사건유형');
    await input.press('Enter');
    // 응답이 아직 안 왔으므로(200ms 지연) 입력이 잠겨 있어야 한다.
    await expect(input).toBeDisabled();
    // disabled 상태에서는 브라우저가 애초에 keydown을 발생시키지 않는다 — 한 번 더 시도해 "이 시점에
    // 어떤 두 번째 Enter도 요청을 만들지 않는다"를 증명한다.
    await page.keyboard.press('Enter');

    await expect(input).toHaveCount(0);
    expect(patchCount).toBe(1);
  });

  // I-2 회귀 가드(task-3-review.md) — Task 2 I-3(이 파일 L530-559)가 연결 입력에 대해 세운 "editing
  // off 시 열린 입력 정리"를 생성 입력이 물려받지 않았다. blur-취소 경로와 분리하기 위해 응답 없는
  // 요청으로 disabled=true(제출 중)를 강제하는 동일 기법을 쓴다 — 그렇지 않으면 "수정 모드" 버튼
  // 클릭 자체가 blur를 일으켜 onCancel이 먼저 정리해 버려서, [editing] effect의 정리 로직이 실제로
  // 기여했는지 이 테스트가 증명하지 못한다.
  test('생성 입력이 제출 중인 상태로 편집 모드가 꺼지면 입력도 함께 사라진다(editing off 정리 회귀 가드)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);
    await waitForCanvasIdle(page);

    const containerBox = await page.getByTestId('schema-graph').boundingBox();
    if (!containerBox) throw new Error('schema-graph 컨테이너를 찾을 수 없습니다.');
    const bgX = containerBox.x + 12;
    const bgY = containerBox.y + 12;

    let postCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types',
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        postCount += 1;
        // 응답하지 않는다 — submitting=true(disabled=true)가 테스트 끝까지 유지되게 한다.
        return new Promise(() => {});
      },
    );

    await dblclickAt(page, bgX, bgY);
    const input = page.getByTestId('canvas-create-input');
    await expect(input).toBeVisible();
    await input.fill('Sensor');
    await input.press('Enter');
    await expect(input).toBeDisabled();

    // 편집 모드를 끈다 — disabled(제출 중) 상태라 blur-취소 경로는 무력하다.
    await page.getByRole('button', { name: '수정 모드' }).click();

    await expect(input).toHaveCount(0);
    expect(postCount).toBe(1);
  });
});

/**
 * 캔버스 Delete 키 삭제(S3 Task 4, #420으로 관계 경로 갱신) — Task 2/3의 두 번째/세 번째 캔버스 편집
 * 경로에 이은 마지막 조작 경로. 타입 삭제는 기존 DeleteTypeConfirm(EntityInspector의 트리거 기반
 * 인스턴스와 별개인 controlled 인스턴스, OntologyPage.tsx)으로 확인을 받고, 관계 삭제도(#420부터)
 * RelationInspector의 트리거 기반 DeleteConfirmDialog(#419)와 별개인 controlled 인스턴스로 같은
 * 확인 절차를 거친다 — 이전에는 확인 없이 즉시 나갔다(#419 회귀 갭).
 * SchemaGraph.tsx의 keydown 핸들러가 지키는 네 가드(편집 모드/hasAnyPendingInput/활성 요소/열린
 * 모달) 각각을 개별 테스트로 증명한다 — "동작한다"만 있고 "엉뚱한 데서 동작하지 않는다"가 없으면
 * 미완이라는 팀의 사전 경고를 그대로 따른다.
 *
 * 판별력 관련(리뷰 I-1): 핸들러 안의 `if (!sel) return`(선택 없음 조기 반환)이 네 가드보다 뒤에 있어,
 * 살아 있는 캔버스 선택 없이 가드만 지워 봐야 이 조기 반환이 대신 막아 버리는 무효 테스트가 되기
 * 쉽다. 가드 ②(hasAnyPendingInput)와 ④(모달 열림) 테스트는 선택을 먼저 만들어 둔 뒤 겨냥해 이
 * 함정을 피했다. 가드 ①(편집 모드)은 예외다 — read 모드에서는 애초에 아무것도 선택되지 않으므로
 * (아래 "읽기 모드에서 Delete는..." 테스트 주석 참고) 그 테스트는 가드 ①을 증명하지 못한다. 가드
 * ①이 유일한 방어선인 실제 경로(편집 중 선택한 채로 관리 다이얼로그가 그 온톨로지를 archived로
 * 전이시키는 경우)는 별도 온톨로지 스키마 mock + 선택 전환까지 필요해 비용이 커서 이번엔 만들지
 * 않았다 — 후속 검토 대상으로 남긴다.
 *
 * 가드 ④(포커스가 캔버스 자신의 서브트리 밖에 있으면 무시)는 최종 리뷰 라운드에서 role
 * 덴리스트(alertdialog만 → dialog 추가)가 두 번 새서(role="listbox"인 Select 드롭다운이 항상 툴바에
 * 떠 있다) 포함 판정으로 뒤집었다(SchemaGraph.tsx 참고). 그래서 이 가드는 세 테스트로 나눠 겨냥한다:
 * EntityInspector 자신의 트리거 기반 다이얼로그(role="alertdialog", M-1 — 다른 다이얼로그를 겹치지
 * 않아야 단독 판별력이 생긴다), 온톨로지 선택 드롭다운(role="listbox", I-1 — 병합 전 필수였던 그
 * 결함의 재현형), 관리 다이얼로그(role="dialog", C-1의 재현형 유지). 셋 다 "무관한 선택 + 무관한
 * 오버레이"로 겨냥해 가드가 실제로 막았는지(선택이 없어서가 아니라)를 증명한다.
 */
test.describe('SchemaGraph — 캔버스 Delete 키 삭제', () => {
  // 노드/엣지를 label로 지목해 편집 모드 선택(tap emit)까지 하는 공통 준비 — 숫자 id를 하드코딩하지
  // 않는다는 이 스펙 파일의 원칙(S3 Task 1 라운드에서 확립)을 선택에도 그대로 지킨다.
  async function selectNodeByLabel(page: Page, label: string) {
    await page.evaluate((lbl) => {
      (window as unknown as { __ontologySchemaCy: { nodes(sel: string): { emit(e: string): void } } }).__ontologySchemaCy
        .nodes(`[label = "${lbl}"]`)
        .emit('tap');
    }, label);
  }

  async function selectEdgeByLabel(page: Page, label: string) {
    await page.evaluate((lbl) => {
      (window as unknown as { __ontologySchemaCy: { edges(sel: string): { emit(e: string): void } } }).__ontologySchemaCy
        .edges(`[label = "${lbl}"]`)
        .emit('tap');
    }, label);
  }

  // 관계 id를 라벨로 파생시킨다(리뷰 M-1) — nodeScreenPosition이 노드 id를 라벨로 얻는 것과 대칭.
  // 라우트 URL에 숫자 id를 하드코딩하지 않는다는 이 스펙 파일의 원칙(Task 3 M-7, Task 1 라운드)을
  // 관계 id에도 지킨다.
  async function edgeRelationId(page: Page, label: string): Promise<number> {
    return page.evaluate(
      (lbl) =>
        Number(
          (
            window as unknown as { __ontologySchemaCy: { edges(sel: string): { data(k: string): unknown } } }
          ).__ontologySchemaCy.edges(`[label = "${lbl}"]`).data('relationId'),
        ),
      label,
    );
  }

  test('노드 선택 후 Delete를 누르면 함께 지워질 관계를 나열한 확인 다이얼로그가 뜬다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    // Building(id=2) — OCCURRED_AT(id=1, object=Building)과 HAS_EQUIPMENT(id=4, subject=Building)
    // 두 관계의 끝점이다(e2e/factories/ontology.factory.ts).
    await selectNodeByLabel(page, 'Building');
    await expect(page.getByTestId('entity-inspector')).toBeVisible();

    await page.keyboard.press('Delete');

    await expect(page.getByRole('heading', { name: 'Building 타입 삭제' })).toBeVisible();
    const affected = page.getByTestId('delete-type-affected-relations');
    await expect(affected).toContainText('함께 삭제되는 관계 2개');
    await expect(affected).toContainText('Incident → OCCURRED_AT → Building');
    await expect(affected).toContainText('Building → HAS_EQUIPMENT → Equipment');
  });

  test('확인하면 DELETE가 나가고 노드가 사라진다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const before = Number(await page.getByTestId('schema-graph').getAttribute('data-node-count'));
    const building = await nodeScreenPosition(page, 'Building');

    await selectNodeByLabel(page, 'Building');
    await expect(page.getByTestId('entity-inspector')).toBeVisible();
    await page.keyboard.press('Delete');
    await expect(page.getByTestId('entity-delete-confirm')).toBeVisible();

    const capture = await mockApi(
      page,
      'DELETE',
      `/api/v1/ontology/1/entity-types/${building.id}`,
      createEntityTypeDeletion([1, 4]),
      { capture: true },
    );
    await page.getByTestId('entity-delete-confirm').click();

    await capture.waitForRequest();
    // 삭제 성공 → 노드 수가 하나 줄고, 선택이 비워져 인스펙터가 빈 안내 문구로 돌아간다(EntityInspector의
    // entity-delete-trigger 경로와 동일한 사후 상태).
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', String(before - 1));
    await expect(page.getByText('왼쪽에서 타입 또는 관계를 선택하세요.')).toBeVisible();
  });

  test('엣지 선택 후 Delete를 누르면 관계 삭제 확인 다이얼로그가 뜬다(#420)', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    await selectEdgeByLabel(page, 'OCCURRED_AT');
    await expect(page.getByTestId('relation-inspector')).toBeVisible();

    let called = false;
    await page.route(
      (url) => url.pathname.startsWith('/api/v1/ontology/1/relations/'),
      (route) => {
        if (route.request().method() !== 'DELETE') return route.fallback();
        called = true;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createVersionOnly()) });
      },
    );

    await page.keyboard.press('Delete');

    // #419 이전에는 이 경로가 확인 없이 곧바로 DELETE를 보냈다(#420 회귀 갭) — 이제는 인스펙터 버튼
    // 경로와 동일하게 alertdialog가 뜨고, 취소하면 요청이 전혀 나가지 않는다.
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByRole('alertdialog').getByText('Incident → OCCURRED_AT → Building')).toBeVisible();
    expect(called).toBe(false);

    await page.getByRole('alertdialog').getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    expect(called).toBe(false);
    await expect(page.getByTestId('relation-inspector')).toBeVisible();
  });

  test('엣지 삭제 확인 다이얼로그에서 확인하면 DELETE가 나가고 관계가 사라진다(#420)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const before = await getEdgeCount(page);
    const relationId = await edgeRelationId(page, 'OCCURRED_AT');
    await selectEdgeByLabel(page, 'OCCURRED_AT');
    await expect(page.getByTestId('relation-inspector')).toBeVisible();

    await page.keyboard.press('Delete');
    await expect(page.getByRole('alertdialog')).toBeVisible();

    const capture = await mockApi(page, 'DELETE', `/api/v1/ontology/1/relations/${relationId}`, createVersionOnly(), {
      capture: true,
    });
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    await capture.waitForRequest();
    await expect.poll(() => getEdgeCount(page)).toBe(before - 1);
    await expect(page.getByText('왼쪽에서 타입 또는 관계를 선택하세요.')).toBeVisible();
  });

  // 가드 회귀(팀 사전 경고의 핵심 사례) — 리스너를 document/window에 걸 때 활성 요소 검사가 없으면
  // 인스펙터 입력에서 글자를 지울 때마다 캔버스 선택(타입)까지 함께 지워진다.
  test('인스펙터 입력에 포커스가 있을 때 Backspace는 아무것도 지우지 않는다(가드 회귀)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const before = Number(await page.getByTestId('schema-graph').getAttribute('data-node-count'));
    const building = await nodeScreenPosition(page, 'Building');
    await selectNodeByLabel(page, 'Building');
    const nameInput = page.getByLabel('타입 이름', { exact: true });
    await expect(nameInput).toBeVisible();
    await nameInput.click(); // 포커스만 옮긴다 — 값은 건드리지 않는다.

    let deleteRequested = false;
    await page.route(
      (url) => url.pathname === `/api/v1/ontology/1/entity-types/${building.id}`,
      (route) => {
        if (route.request().method() === 'DELETE') deleteRequested = true;
        return route.fallback();
      },
    );

    await page.keyboard.press('Backspace');

    // 인스펙터 입력 안의 통상적인 글자 삭제(브라우저 기본 동작)는 막지 않는다 — 우리가 막아야 하는
    // 건 "캔버스 선택을 지우는 DELETE 요청"이지 입력 자체의 편집이 아니다.
    expect(deleteRequested).toBe(false);
    await expect(page.getByTestId('entity-delete-confirm')).toHaveCount(0);
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', String(before));
  });

  // 가드 회귀 — hasAnyPendingInput()을 activeElement 검사와 분리해 단독으로 겨냥한다. 인라인 입력
  // (연결/리네임/생성)이 disabled(제출 중)라 onBlur 취소가 꺼진 구간에서, 포커스를 명시적으로
  // 입력 밖(body)으로 옮겨도 — 즉 activeElement 가드만으로는 통과할 상태에서도 — pendingConnection이
  // 남아 있는 한 Delete가 발화하면 안 된다.
  test('인라인 입력이 열려 있을 때 Delete는 아무것도 지우지 않는다(가드 회귀)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    // 먼저 Building을 선택해 둔다 — 이 테스트가 증명하려는 건 "선택이 없어서 막혔다"가 아니라
    // "선택은 있지만 인라인 입력이 열려 있어서 막혔다"는 hasAnyPendingInput() 가드 자체다.
    await selectNodeByLabel(page, 'Building');
    await expect(page.getByTestId('entity-inspector')).toBeVisible();

    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/relations',
      (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        // 응답하지 않는다 — submitting=true(disabled=true)가 테스트 끝까지 유지되게 한다(Task 2 I-3와
        // 동일 기법).
        return new Promise(() => {});
      },
    );

    const beforeNodeCount = await page.getByTestId('schema-graph').getAttribute('data-node-count');
    const beforeEdgeCount = await getEdgeCount(page);
    await dragConnect(page, 'Incident', 'Equipment');
    const input = page.getByTestId('canvas-connect-input');
    await expect(input).toBeVisible();
    await input.fill('LOCATED_NEAR');
    await input.press('Enter');
    await expect(input).toBeDisabled();

    // activeElement 가드(#3)가 아니라 hasAnyPendingInput() 가드(#2)를 단독으로 겨냥한다 — 포커스를
    // 입력 밖(body)으로 명시적으로 옮겨 활성 요소 검사는 통과시킨 채로 Delete를 보낸다.
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());

    let deleteRequested = false;
    await page.route('**/api/v1/ontology/1/**', (route) => {
      if (route.request().method() === 'DELETE') deleteRequested = true;
      return route.fallback();
    });

    await page.keyboard.press('Delete');

    expect(deleteRequested).toBe(false);
    await expect(input).toBeVisible(); // 여전히 같은 연결 입력이 남아 있다 — 다른 상태로 전이하지 않았다.
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', beforeNodeCount ?? '');
    expect(await getEdgeCount(page)).toBe(beforeEdgeCount);
    // 리뷰 I-1(b) — DELETE 미발생만으로는 가드 ②(hasAnyPendingInput)를 단독으로 증명하지 못한다.
    // 이 가드를 지워도 흐름은 requestDeleteEntity(선택된 Building) → DeleteTypeConfirm이 "열릴
    // 뿐"(확인 클릭 전이라 DELETE는 안 나간다)이라 위 단언들은 그대로 통과해 버린다. 다이얼로그
    // 자체가 안 열렸는지까지 확인해야 가드 ②가 실제로 막았다는 것이 드러난다.
    await expect(page.getByTestId('entity-delete-confirm')).toHaveCount(0);
  });

  // 가드 회귀(리뷰 M-1) — 가드 ④를 "자기 자신의 다이얼로그" 하나만으로 단독 겨냥한다. 이전 라운드의
  // 같은 이름 테스트는 OntologyManageDialog(role="dialog")를 먼저 연 뒤 그 안에서
  // DeleteConfirmDialog(role="alertdialog")를 띄웠는데, 두 레이어가 동시에 DOM에 있어 셀렉터를
  // role="dialog" 하나로 되돌려도(또는 alertdialog 판정 자체가 없어도, 포함 판정 도입 이후에는
  // "OntologyManageDialog 자체가 컨테이너 밖"이라는 사실 하나로도) 이 테스트가 통과해 버려
  // role="alertdialog" 절만 단독으로는 증명하지 못했다. 여기서는 다른 다이얼로그를 겹쳐 열지 않고
  // EntityInspector 자신의 트리거 기반 DeleteTypeConfirm(entity-delete-trigger)만 연다 — 이러면
  // "선택된 대상 = 다이얼로그가 확인 중인 대상"과 정확히 같은 상황에서, 가드 ④가 없으면
  // requestDeleteEntity가 다시 불려 OntologyPage의 controlled 인스턴스가 그 위에 하나 더 열린다.
  // 두 인스턴스 모두 같은 data-testid="entity-delete-confirm"을 쓰므로 count로 판별할 수 있다.
  test('EntityInspector 트리거로 연 확인 다이얼로그 위에 Delete로 두 번째 확인을 겹쳐 열지 않는다(가드 회귀, M-1)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    await selectNodeByLabel(page, 'Building');
    await expect(page.getByTestId('entity-inspector')).toBeVisible();
    await page.getByTestId('entity-delete-trigger').click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    expect(await page.getByTestId('entity-delete-confirm').count()).toBe(1);

    await page.keyboard.press('Delete');

    // 여전히 1개면(2개가 아니면) 가드 ④가 두 번째(캔버스 요청) 인스턴스를 막은 것이다.
    expect(await page.getByTestId('entity-delete-confirm').count()).toBe(1);
  });

  // 가드 회귀(최종 리뷰 라운드 I-1, 병합 전 필수) — 가드 ④가 "다이얼로그"만 나열한 덴리스트였을
  // 때는 이 시나리오가 새는 결함이었다: OntologySelect의 "온톨로지 선택" 콤보박스는 shadcn Select
  // (Radix `SelectContent` → role="listbox")라 role="dialog"도 role="alertdialog"도 아니고, 편집
  // 모드에서도 항상 툴바에 떠 있다. 팝업만 연 채(옵션을 클릭하지 않는다) 무관한 관계를 선택해 둔
  // 상태에서 Delete를 누른다 — 포커스는 listbox/option 위(INPUT/TEXTAREA 아님)라 가드 ①②③은 전부
  // 통과한다. 포함 판정(가드 ④, SchemaGraph.tsx)이 없다면 이 시점에 확인 없이 DELETE가 나간다.
  test('온톨로지 선택 드롭다운(role="listbox")이 열려 있을 때 Delete는 아무것도 지우지 않는다(가드 회귀, I-1)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const relationId = await edgeRelationId(page, 'OCCURRED_AT');
    await selectEdgeByLabel(page, 'OCCURRED_AT');
    await expect(page.getByTestId('relation-inspector')).toBeVisible();

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await expect(page.getByRole('listbox')).toBeVisible();

    let deleteRequested = false;
    await page.route(
      (url) => url.pathname === `/api/v1/ontology/1/relations/${relationId}`,
      (route) => {
        if (route.request().method() === 'DELETE') deleteRequested = true;
        return route.fallback();
      },
    );

    const beforeEdgeCount = await getEdgeCount(page);
    await page.keyboard.press('Delete');

    expect(deleteRequested).toBe(false);
    expect(await getEdgeCount(page)).toBe(beforeEdgeCount);
  });

  // 가드 회귀 — role="dialog" 모달(Select와 달리 alertdialog가 아닌 일반 Dialog)에서도 포함 판정이
  // 걸리는지 확인한다(리뷰 C-1의 재현 형태를 유지). OntologyManageDialog 자체(shadcn Dialog)만 연다 —
  // 행 삭제 확인까지 열 필요 없이 관리 다이얼로그만 열어도 role="dialog"다.
  test('role="dialog" 모달이 열려 있을 때 Delete는 아무것도 지우지 않는다(가드 회귀, C-1)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const relationId = await edgeRelationId(page, 'OCCURRED_AT');
    await selectEdgeByLabel(page, 'OCCURRED_AT');
    await expect(page.getByTestId('relation-inspector')).toBeVisible();

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: '온톨로지 관리…' }).click();
    await expect(page.getByTestId('ontology-manage-dialog')).toBeVisible();
    await expect(page.getByRole('dialog')).toBeVisible();

    let deleteRequested = false;
    await page.route(
      (url) => url.pathname === `/api/v1/ontology/1/relations/${relationId}`,
      (route) => {
        if (route.request().method() === 'DELETE') deleteRequested = true;
        return route.fallback();
      },
    );

    const beforeEdgeCount = await getEdgeCount(page);
    await page.keyboard.press('Delete');

    expect(deleteRequested).toBe(false);
    expect(await getEdgeCount(page)).toBe(beforeEdgeCount);
  });

  // 회귀 방지(#409) — 도메인명이 매우 긴 온톨로지가 목록에 있으면 옵션이 줄바꿈 없이 한 줄로
  // truncate 돼야 한다. renderItem의 텍스트 <span>에 max-width가 없으면 텍스트가 그대로 여러 줄로
  // 렌더돼 드롭다운 전체 레이아웃이 무너진다(원본 결함). scrollWidth > clientWidth로 "화면에 잘려서
  // 넘치는 텍스트가 있다"는 사실을, offsetHeight로 "그 옵션 행이 한 줄 높이만 차지한다"는 사실을
  // 각각 확인해야 truncate가 실제로 적용됐다는 증거가 된다(단순히 텍스트가 보이는지만 확인하면 줄바꿈
  // 상태도 통과해버려 결함을 못 잡는다).
  test('도메인명이 매우 긴 온톨로지도 선택 드롭다운에서 한 줄로 truncate 된다(회귀, #409)', async ({
    authenticatedPage: page,
  }) => {
    const longDomain = '가나다라마바사아자차'.repeat(50);
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/ontologies',
      createOntologySummaries([
        {
          id: MAPPING_ONTOLOGY_ID,
          domain: '화재조사 보고서',
          schemaVersion: 1,
          status: 'active',
          entityCount: 6,
          datasetCount: 3,
          updatedAt: '2026-04-12T09:00:00Z',
          isDefault: true,
        },
        {
          id: 99,
          domain: longDomain,
          schemaVersion: 1,
          status: 'draft',
          entityCount: 0,
          datasetCount: 0,
          updatedAt: '2026-08-01T09:00:00Z',
          isDefault: false,
        },
      ]),
    );
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    const longOption = page.getByRole('option', { name: longDomain });
    await expect(longOption).toBeVisible();

    // truncate 대상은 옵션 안의 텍스트 <span>이다(title도 이 span에 붙는다 — 바깥 role="option"
    // div가 아니라 그 텍스트를 감싸는 자식에 있어야 실제로 잘린 이름을 hover로 확인할 수 있다).
    const textSpan = longOption.locator('span[title]');
    await expect(textSpan).toHaveAttribute('title', longDomain);

    const { scrollWidth, clientWidth, height } = await textSpan.evaluate((el) => {
      const rect = el.getBoundingClientRect();
      return { scrollWidth: el.scrollWidth, clientWidth: el.clientWidth, height: rect.height };
    });
    // 텍스트가 잘려 넘친다(= truncate 대상이 실제로 존재) + 텍스트 행은 한 줄 높이만 차지한다(=
    // 줄바꿈되지 않았다). 둘 다 참이어야 max-width+truncate가 실제로 동작한 것이다.
    expect(scrollWidth).toBeGreaterThan(clientWidth);
    expect(height).toBeLessThan(30);
  });

  // 회귀 방지(#504) — #409는 "펼쳐진 드롭다운 옵션"만 truncate 처리했고, 옵션을 실제로 골라
  // "닫힌 SelectTrigger(현재 선택값)"로 표시되는 경로는 그대로 남아 있었다. SelectValue는 기본적으로
  // 매칭되는 SelectItem의 children(드롭다운 폭 280px 기준 래퍼)을 그대로 재사용하는데, 그 래퍼는
  // overflow 제약이 없어 트리거 폭(220px)보다 넓은 233px까지 스스로 커진 뒤에야 ellipsis를 적용한다
  // (원본 결함 — 실측: title span의 렌더 폭이 233px, 트리거 오른쪽 경계를 26px 넘어선다). 그래서
  // scrollWidth>clientWidth·한 줄 높이 유지만으로는 회귀를 못 잡는다(원본 결함도 이 조건을 만족한다 —
  // 실측 확인됨). 실제 결함의 증거는 "title이 붙은 span이 트리거 박스 오른쪽 경계를 넘는가"이므로
  // 두 bounding box를 직접 비교한다.
  test('도메인명이 매우 긴 온톨로지를 선택하면 닫힌 트리거에서도 한 줄로 truncate 된다(회귀, #504)', async ({
    authenticatedPage: page,
  }) => {
    const longDomain = '가나다라마바사아자차'.repeat(50);
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/ontologies',
      createOntologySummaries([
        {
          id: MAPPING_ONTOLOGY_ID,
          domain: '화재조사 보고서',
          schemaVersion: 1,
          status: 'active',
          entityCount: 6,
          datasetCount: 3,
          updatedAt: '2026-04-12T09:00:00Z',
          isDefault: true,
        },
        {
          id: 99,
          domain: longDomain,
          schemaVersion: 1,
          status: 'draft',
          entityCount: 0,
          datasetCount: 0,
          updatedAt: '2026-08-01T09:00:00Z',
          isDefault: false,
        },
      ]),
    );
    await mockApi(page, 'GET', '/api/v1/ontology/99', createOntologySchema({ domain: longDomain, entities: [], relations: [] }));
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: longDomain }).click();

    const trigger = page.getByRole('combobox', { name: '온톨로지 선택' });
    // 트리거 자체는 옵션과 달리 별도의 children(전용 span)으로 렌더링된다 — title이 붙은 그 span이
    // truncate 대상이다.
    const valueSpan = trigger.locator('span[title]');
    await expect(valueSpan).toHaveAttribute('title', longDomain);

    // 결정적 증거: title span의 오른쪽 경계가 트리거(SelectTrigger) 박스의 오른쪽 경계를 넘지 않아야
    // 한다. 원본 결함에서는 이 span이 233px로 렌더돼 트리거 오른쪽 경계를 넘어섰다(실측 26px) — 이게
    // 바로 이슈가 말하는 "옆 버튼과 겹침"의 실체다. scrollWidth/clientWidth 비교만으로는 이 결함을
    // 못 잡는다(원본 결함도 그 span 자신 안에서는 ellipsis가 걸려 조건을 만족해버린다).
    const triggerBox = await trigger.boundingBox();
    const spanBox = await valueSpan.boundingBox();
    expect(triggerBox).not.toBeNull();
    expect(spanBox).not.toBeNull();
    expect(spanBox!.x + spanBox!.width).toBeLessThanOrEqual(triggerBox!.x + triggerBox!.width);
  });

  // (리뷰 I-1(a)) 이 테스트는 가드 ①(편집 모드) 자체를 단독으로 증명하지 못한다 — 읽기 모드에서는
  // "수정 모드" 토글이 꺼질 때 modelSelected도 함께 비워지고 읽기 모드 노드 tap은 드릴다운이라
  // 애초에 캔버스 선택이 생기지 않으므로, 가드 ①을 지워도 핸들러의 `if (!sel) return`이 선택 없음을
  // 이유로 대신 막아 이 테스트는 여전히 통과한다. 이 테스트가 실제로 지키는 것은 "읽기 모드에서
  // Delete를 눌러도 아무 부작용이 없다"는 더 약한 사실이다 — 가드 ①이 유일한 방어선인 시나리오(편집
  // 중 선택한 채 관리 다이얼로그로 그 온톨로지를 archived 전이)는 이 파일 상단 describe 주석 참고.
  test('읽기 모드에서 Delete는 아무 요청도 보내지 않는다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    // 수정 모드를 켜지 않는다 — 읽기 모드 그대로 Delete를 시도한다.
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');

    let deleteRequested = false;
    await page.route('**/api/v1/ontology/1/**', (route) => {
      if (route.request().method() === 'DELETE') deleteRequested = true;
      return route.fallback();
    });

    await page.keyboard.press('Delete');

    expect(deleteRequested).toBe(false);
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');
    await expect(page.getByTestId('entity-delete-confirm')).toHaveCount(0);
  });

  // 삭제 후 포커스 복귀(#328류 회피, S2 Task 6 리뷰가 EntityInspector의 트리거 기반 삭제에 세운 규칙과
  // 동일) — 캔버스는 포커스 대상이 아니므로(aria-hidden canvas) 남아 있는 컨트롤(아웃라인의 "타입
  // 추가" 버튼)로 명시적으로 옮긴다. 응답을 지연시켜 Playwright의 자동 재시도(toBeFocused)가 그 사이의
  // <body> 유실 구간을 가리지 못하게 하고, 단발 page.evaluate()로 "그 즉시"의 activeElement를 확인한다.
  // (리뷰 M-2) 이 테스트는 브리프가 요구한 결과(<body> 유실 없음)는 검증하지만, 그 결과를 만드는
  // 메커니즘(OntologyPage의 onConfirm 성공 콜백 안 명시적 focus() 호출)을 단독으로 판별하진 못한다 —
  // AlertDialogContent의 restoreFocusRef(닫힘 시점 자동 복귀)가 이미 같은 버튼을 겨냥하고 있어, 그
  // 명시적 focus() 호출을 지워도 이 테스트는 통과할 가능성이 높다. 결과 자체(브리프 요구사항)는
  // 지키고 있으므로 테스트를 다시 설계하지는 않는다.
  test('삭제 후 포커스가 <body>로 유실되지 않는다(지연 응답, 단발 확인)', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await enterEditMode(page);

    const building = await nodeScreenPosition(page, 'Building');
    await selectNodeByLabel(page, 'Building');
    await expect(page.getByTestId('entity-inspector')).toBeVisible();
    await page.keyboard.press('Delete');
    await expect(page.getByTestId('entity-delete-confirm')).toBeVisible();

    await page.route(
      (url) => url.pathname === `/api/v1/ontology/1/entity-types/${building.id}`,
      async (route) => {
        if (route.request().method() !== 'DELETE') return route.fallback();
        await new Promise((resolve) => setTimeout(resolve, 300));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createEntityTypeDeletion([1, 4])),
        });
      },
    );

    await page.getByTestId('entity-delete-confirm').click();
    await expect(page.getByText('왼쪽에서 타입 또는 관계를 선택하세요.')).toBeVisible();

    const activeElement = await page.evaluate(() => ({
      tag: document.activeElement?.tagName,
      label: document.activeElement?.getAttribute('aria-label'),
    }));
    expect(activeElement).toEqual({ tag: 'BUTTON', label: '타입 추가' });
  });
});

/**
 * 빈 상태 CTA(S3 Task 4) — 설계 스펙 §빈 상태에 맞춰 예시 트리플과 "첫 타입 만들기" 단일 CTA를
 * 보여준다. CTA는 수정 모드만 켜던 이전과 달리 생성 폼까지 함께 연다(OntologyPage.tsx의 onDefine).
 */
test.describe('OntologyEmptyState — 예시 트리플 + 첫 타입 만들기 CTA', () => {
  test('빈 상태가 예시 트리플과 "첫 타입 만들기" CTA를 보여주고, 누르면 생성 폼이 열린다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    const emptySchema = createOntologySchema({ entities: [], relations: [] });
    await mockApi(page, 'GET', '/api/v1/ontology', emptySchema);
    await mockApi(page, 'GET', '/api/v1/ontology/1', emptySchema);
    await mockApi(page, 'GET', '/api/v1/ontology/graph', createOntologyGraph());
    await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
    await page.goto('/knowledge-graph/model');

    await expect(page.getByText('아직 엔티티 타입이 없습니다')).toBeVisible();
    await expect(
      page.getByText('지식 모델은 무엇을 추출할지(타입)와 어떻게 이어질지(관계)로 이루어집니다.'),
    ).toBeVisible();
    await expect(page.getByText('Incident → OCCURRED_AT → Building')).toBeVisible();
    // "AI로 초안 생성" 버튼은 배관이 없어 넣지 않는다(OntologyEmptyState.tsx 주석 참고) — 실수로
    // 다시 추가되지 않는지 이 테스트가 함께 지킨다.
    await expect(page.getByRole('button', { name: /AI로 초안 생성/ })).toHaveCount(0);

    await page.getByRole('button', { name: '첫 타입 만들기' }).click();

    // 편집 모드가 켜지고 생성 폼까지 곧바로 열린다 — 아웃라인의 "타입 추가"를 다시 찾지 않아도 된다.
    await expect(page.getByRole('button', { name: '수정 모드' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByTestId('entity-inspector-create')).toBeVisible();
  });
});
