import {
  createArchivedOntologySummary,
  createDraftOntologySummary,
  createOntologySummaries,
} from '../../factories/mapping.factory';
import {
  createEntityTypeMutation,
  createOntologyGraph,
  createOntologySchema,
  createPropertyMutation,
  createRelationMutation,
  createVersionOnly,
} from '../../factories/ontology.factory';
import { setupAdminAuth, setupOntologyMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 요소 단위 지식 모델 편집기(S2) — 편집 모드 셸(모드 토글 + 아웃라인 + 캔버스 + 인스펙터 자리) E2E.
 * 전체 문서를 왕복시키던 모달(OntologyEditDialog, "편집" 버튼)은 Task 6에서 제거됐다 — "수정 모드"
 * 버튼이 유일한 편집 진입점이다(라벨은 "편집"을 포함하지 않는다 — Playwright getByRole 이름 매칭이
 * 부분 일치라, 과거 모달의 "편집" 버튼과 공존하던 시절 strict mode 충돌을 피하려고 붙인 이름을
 * 하위 호환을 위해 유지한다).
 * 인스펙터(EntityInspector/RelationInspector)는 Task 4/5가 채운다 — 여기서는 껍데기(3-pane 등장,
 * 선택 동기화)만 검증한다.
 */
test.describe('지식 모델 요소 편집기 — 모드 셸', () => {
  test('ADMIN이 편집 모드를 켜면 3-pane이 나타난다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await page.getByRole('button', { name: '수정 모드' }).click();

    await expect(page.getByTestId('model-outline')).toBeVisible();
    await expect(page.getByTestId('schema-graph')).toBeVisible();
    await expect(page.getByTestId('model-inspector')).toBeVisible();
  });

  test('편집 모드에서 캔버스 노드 선택이 아웃라인 선택과 동기화된다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    // 아웃라인 클릭(Building, entity id=2) → 캔버스가 같은 노드를 :selected로 표시한다.
    // (리뷰 IMP-6) click() 직후 바로 page.evaluate로 읽으면 React 리렌더 + 선택 effect 완료를 기다리지
    // 않는 한 번짜리 읽기라 flake가 난다 — 재시도하는 expect(...).toContainText로 먼저 상태 반영을
    // 게이트한 다음에 evaluate한다.
    await page.getByTestId('outline-entity-2').click();
    // (Task 4) EntityInspector가 채워지면서 "타입 #2 선택됨" 플레이스홀더가 실제 편집 폼으로
    // 바뀌었다 — 인스펙터가 올바른 엔티티(Building)를 로드했는지는 타입 이름 필드값으로 확인한다.
    await expect(page.getByLabel('타입 이름')).toHaveValue('Building');
    // 캔버스 노드 id는 이제 타입 이름이 아니라 entityTypeId다(S3 Task 1) — 그 id 값 자체는 fixture의
    // 엔티티 배열 순서에 우연히 결합된 구현 디테일이라, 여기서 검증하려는 건 "선택 동기화가 되는가"이지
    // "id가 몇인가"가 아니다. 그래서 id 대신 사람이 읽는 label(타입 이름)로 단언한다 — id 스킴이 다시
    // 바뀌어도 이 단언은 그대로 살아남는다.
    const selectedNodeLabels = await page.evaluate(() => {
      const cy = (window as unknown as { __ontologySchemaCy?: { $(sel: string): { map<T>(fn: (e: { data(k: string): unknown }) => T): T[] } } })
        .__ontologySchemaCy;
      return cy ? cy.$(':selected').map((e) => e.data('label')) : [];
    });
    expect(selectedNodeLabels).toEqual(['Building']);

    // 반대 방향: 캔버스에서 다른 타입(Cause, id=3)을 탭 → 아웃라인의 선택 표시가 그쪽으로 옮겨간다.
    // 셀렉터도 id가 아니라 label 속성으로 노드를 찾는다 — 같은 이유(fixture id 결합 회피).
    await page.evaluate(() => {
      (window as unknown as { __ontologySchemaCy: { nodes(sel: string): { emit(e: string): void } } }).__ontologySchemaCy
        .nodes('[label = "Cause"]')
        .emit('tap');
    });
    await expect(page.getByTestId('outline-entity-3')).toHaveAttribute('aria-current', 'true');
    await expect(page.getByTestId('outline-entity-2')).not.toHaveAttribute('aria-current', 'true');
    await expect(page.getByLabel('타입 이름')).toHaveValue('Cause');
  });

  test('편집 모드에서 관계 선택도 아웃라인 선택과 양방향으로 동기화된다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    // 아웃라인에서 관계 클릭(OCCURRED_AT, relation id=1) → 캔버스가 같은 엣지를 :selected로 표시한다.
    // (Task 5) "관계 #1 선택됨" 플레이스홀더가 실제 RelationInspector 편집 폼으로 바뀌었다 — 인스펙터가
    // 올바른 관계(OCCURRED_AT: Incident→Building)를 로드했는지는 끝점 표시값으로 확인한다.
    await page.getByTestId('outline-relation-1').click();
    await expect(page.getByTestId('relation-endpoint-object')).toHaveText('Building');
    const selectedEdgeIds = await page.evaluate(() => {
      const cy = (window as unknown as { __ontologySchemaCy?: { $(sel: string): { map<T>(fn: (e: { id(): string }) => T): T[] } } })
        .__ontologySchemaCy;
      return cy ? cy.$(':selected').map((e) => e.id()) : [];
    });
    expect(selectedEdgeIds).toEqual(['t0']); // OCCURRED_AT은 factory 배열의 0번째 트리플

    // 반대 방향: 캔버스에서 다른 관계(CAUSED_BY, relation id=2) 엣지를 탭 → 아웃라인 선택이 옮겨간다.
    await page.evaluate(() => {
      (window as unknown as { __ontologySchemaCy: { $(sel: string): { emit(e: string): void } } }).__ontologySchemaCy
        .$('#t1')
        .emit('tap');
    });
    await expect(page.getByTestId('outline-relation-2')).toHaveAttribute('aria-current', 'true');
    await expect(page.getByTestId('outline-relation-1')).not.toHaveAttribute('aria-current', 'true');
    // CAUSED_BY(id=2)는 Incident→Cause.
    await expect(page.getByTestId('relation-endpoint-object')).toHaveText('Cause');
  });

  test('읽기 모드에서는 노드 클릭이 여전히 인스턴스 탭 드릴다운이다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    // 수정 모드를 켜지 않은 채(기본 read 모드) 캔버스 노드를 탭한다 — 기존 드릴다운 브리지가 그대로 살아 있어야 한다.
    // 캔버스 노드 id는 이제 타입 이름이 아니라 entityTypeId다(S3 Task 1) — id 값은 fixture 배정에 우연히
    // 결합된 디테일이라 label(타입 이름) 속성으로 노드를 찾는다.
    await expect(page.getByTestId('schema-graph')).toHaveAttribute('data-node-count', '6');
    await page.evaluate(() => {
      (window as unknown as { __ontologySchemaCy: { nodes(sel: string): { emit(e: string): void } } }).__ontologySchemaCy
        .nodes('[label = "Incident"]')
        .emit('tap');
    });

    await expect(page.getByRole('tab', { name: '그래프 탐색' })).toHaveAttribute('aria-selected', 'true');
    // 편집기 pane은 아예 나타나지 않는다(read 모드 그대로).
    await expect(page.getByTestId('model-outline')).toHaveCount(0);
    await expect(page.getByTestId('model-inspector')).toHaveCount(0);
  });

  test('수정 모드를 다시 끄면 TypeFilterPanel이 돌아오고 인스펙터가 사라진다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    const toggle = page.getByRole('button', { name: '수정 모드' });
    await toggle.click();
    await page.getByTestId('outline-entity-2').click();
    await expect(page.getByTestId('model-outline')).toBeVisible();
    await expect(page.getByTestId('type-filter-panel')).toHaveCount(0);

    await toggle.click();

    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByTestId('type-filter-panel')).toBeVisible();
    await expect(page.getByTestId('model-outline')).toHaveCount(0);
    await expect(page.getByTestId('model-inspector')).toHaveCount(0);

    // 다시 켰을 때 이전 선택이 새어 나오지 않는다 — 인스펙터는 빈 안내 문구로 시작해야 한다.
    await toggle.click();
    await expect(page.getByTestId('model-inspector')).toContainText('왼쪽에서 타입 또는 관계를 선택하세요.');
  });

  test('온톨로지를 전환하면 편집 모드가 꺼지고 선택이 초기화된다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    const otherSchema = createOntologySchema({ domain: '건축물 대장', schemaVersion: 3 });
    await mockApi(page, 'GET', '/api/v1/ontology', createOntologySchema());
    await mockApi(page, 'GET', '/api/v1/ontology/1', createOntologySchema());
    await mockApi(page, 'GET', '/api/v1/ontology/2', otherSchema);
    await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
    await page.goto('/knowledge-graph/model');

    const toggle = page.getByRole('button', { name: '수정 모드' });
    await toggle.click();
    await page.getByTestId('outline-entity-2').click();
    // (Task 4) EntityInspector가 채워지면서 플레이스홀더 대신 실제 편집 폼이 보인다.
    await expect(page.getByLabel('타입 이름')).toHaveValue('Building');

    // 다른 온톨로지로 전환 — 선택이 다른 온톨로지의 id를 계속 가리키면 인스펙터가 남의 데이터를 지목하게 된다.
    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: '건축물 대장' }).click();

    // 편집 모드 자체가 꺼진다 — 토글이 눌리지 않은 상태로 보여야 한다.
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByTestId('model-outline')).toHaveCount(0);

    // 다시 켜면 이전 온톨로지의 선택이 남아있지 않다(빈 안내 문구).
    await toggle.click();
    await expect(page.getByTestId('model-inspector')).toContainText('왼쪽에서 타입 또는 관계를 선택하세요.');
  });

  test('비관리자에게는 편집 모드 토글이 보이지 않는다', async ({ authenticatedPage: page }) => {
    // setupAdminAuth를 호출하지 않는다 — 기본 authenticatedPage는 USER(비관리자) 역할이다.
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');

    await expect(page.getByRole('button', { name: '수정 모드' })).toHaveCount(0);
  });

  test('archived 온톨로지에서는 편집 모드 토글이 보이지 않는다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    const archived = createArchivedOntologySummary();
    await mockApi(page, 'GET', '/api/v1/ontology', createOntologySchema());
    await mockApi(page, 'GET', '/api/v1/ontology/1', createOntologySchema());
    await mockApi(page, 'GET', '/api/v1/ontologies', [
      ...createOntologySummaries(),
      createDraftOntologySummary(),
      archived,
    ]);
    await mockApi(page, 'GET', `/api/v1/ontology/${archived.id}`, createOntologySchema({ domain: archived.domain }));
    await page.goto('/knowledge-graph/model');

    await page.getByRole('combobox', { name: '온톨로지 선택' }).click();
    await page.getByRole('option', { name: archived.domain }).click();

    await expect(page.getByRole('button', { name: '수정 모드' })).toHaveCount(0);
  });

  // Task 4 리뷰 N-3 이관(task-5-brief.md "추가 범위") — 관계의 subject/object가 어떤 이유로든(캐시
  // 낙관적 갱신의 remap 누락 등, C-2와 같은 부류) 실제 엔티티 타입을 가리키지 못하면, cytoscape는
  // 존재하지 않는 노드를 잇는 엣지를 add()하는 즉시 예외를 던져 PageErrorBoundary까지 크래시가
  // 번진다. RelationInspector(Task 5)가 관계 편집을 열어 이 표면을 다시 노출시키므로, SchemaGraph의
  // elements 구성 단계에 걸러내는 방어망을 두었다(SchemaGraph.tsx) — 이 테스트는 그 방어망을 겨냥한다.
  test('끝점이 없는 관계가 섞여도 캔버스가 크래시하지 않고 방어망이 걸러낸다(N-3 회귀 가드)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    const base = createOntologySchema();
    // 존재하지 않는 타입('유령타입')을 목적어로 가리키는 관계 — 실서버에서는 절대 나오면 안 되지만
    // (참조 무결성은 서버가 보장), 클라이언트 낙관적 캐시 경로만의 결함으로 생길 수 있다(C-2 참고).
    const orphanRelation = {
      id: 99,
      subject: 'Incident',
      relation: 'GHOST',
      object: '유령타입',
      description: '',
      subjectTypeId: 1,
      objectTypeId: 999,
    };
    const schemaWithOrphan = createOntologySchema({ relations: [...base.relations, orphanRelation] });
    await mockApi(page, 'GET', '/api/v1/ontology', schemaWithOrphan);
    await mockApi(page, 'GET', '/api/v1/ontology/1', schemaWithOrphan);
    await mockApi(page, 'GET', '/api/v1/ontology/graph', createOntologyGraph());
    await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());

    const warnings: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'warning') warnings.push(msg.text());
    });

    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    // 크래시하지 않는다 — 에러 바운더리가 뜨지 않고 캔버스가 정상 렌더된다.
    await expect(page.getByRole('heading', { name: '페이지를 불러오는 중 문제가 발생했습니다' })).toHaveCount(0);
    await expect(page.getByTestId('schema-graph')).toBeVisible();
    // 걸러진 엣지가 조용히 사라지지 않는다 — console.warn으로 흔적이 남는다(SchemaGraph.tsx는 노드를
    // id로 비교해 거르지만, 경고 문구에는 사람이 읽을 수 있게 소스/타깃 노드 "이름"을 담는다 —
    // 존재하지 않는 목적어 이름 자체가 원인 추적의 단서다).
    await expect.poll(() => warnings.some((w) => w.includes('유령타입'))).toBe(true);
    // (리뷰 I-2) "정상 관계 6개는 그대로 보인다"는 주장은 위 단언만으로는 확인되지 않는다 — 필터가
    // 걸러낸 엣지 1건만 셀 뿐, 나머지 6건이 실제로 캔버스에 남아 있는지는 별도로 단언해야 한다.
    // (필터가 모든 엣지를 떨어뜨리는 결함이 있어도 위 단언들은 그대로 통과한다.) 선택 동기화
    // 테스트가 이미 쓰는 cy 핸들로 실제 엣지 개수를 확인한다 — 정상 6개 + 오염 1개 제외 = 6개.
    // (리뷰 N-4) 단발 evaluate가 아니라 poll을 쓴다 — console.warn은 렌더(useMemo) 중에 찍히지만
    // cytoscape 인스턴스 생성·요소 주입은 별도 effect에서 한 박자 늦게 일어난다. warn 폴이 먼저
    // 통과한 직후 곧바로 읽으면 아직 cy.add()가 끝나지 않아 엣지가 0으로 보일 수 있다(간헐적 실패).
    await expect
      .poll(() =>
        page.evaluate(() => {
          const cy = (window as unknown as { __ontologySchemaCy?: { edges(): { length: number } } })
            .__ontologySchemaCy;
          return cy?.edges().length ?? -1;
        }),
      )
      .toBe(6);
  });
});

/**
 * ModelOutline — 타입/관계 검색(#415).
 * 읽기 모드 TypeFilterPanel에는 있던 "타입 검색"이 수정 모드 아웃라인에는 없어, 온톨로지가 커지면
 * 스크롤로만 탐색해야 했다. TypeFilterPanel과 동일한 패턴(SearchInput + 클라이언트 부분일치)으로
 * 타입/관계 섹션 각각에 검색 입력을 추가했다 — 검색어 입력 → 목록이 즉시 걸러지는지 검증한다.
 */
test.describe('ModelOutline — 타입/관계 검색', () => {
  test('타입 검색어를 입력하면 일치하는 타입만 남고, 지우면 전체가 돌아온다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    const entityList = page.getByTestId('model-outline-entities');
    // 초기 상태 — factory의 6개 타입이 모두 보인다.
    await expect(entityList.getByRole('button')).toHaveCount(6);

    await page.getByLabel('타입 검색').fill('build');
    // 부분일치 + 대소문자 무시 — "Building" 하나만 남는다.
    await expect(entityList.getByRole('button')).toHaveCount(1);
    await expect(page.getByTestId('outline-entity-2')).toContainText('Building');
    await expect(page.getByTestId('outline-entity-1')).toHaveCount(0); // Incident는 걸러짐

    // 검색어를 지우면 전체 목록이 돌아온다.
    await page.getByLabel('타입 검색').fill('');
    await expect(entityList.getByRole('button')).toHaveCount(6);

    // 아무 타입에도 매치되지 않는 검색어 — 빈 상태 안내 문구가 보인다.
    await page.getByLabel('타입 검색').fill('존재하지않는타입');
    await expect(entityList.getByRole('button')).toHaveCount(0);
    await expect(entityList).toContainText('검색 결과가 없습니다.');
  });

  test('관계 검색은 subject → relation → object 전체 텍스트를 대상으로 부분일치한다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    const relationList = page.getByTestId('model-outline-relations');
    // 초기 상태 — factory의 6개 관계가 모두 보인다.
    await expect(relationList.getByRole('button')).toHaveCount(6);

    // relation 필드(가운데 텍스트)로 검색 — subject/object가 아닌 relation 자체 매칭도 되는지 확인.
    await page.getByLabel('관계 검색').fill('equipment');
    // HAS_EQUIPMENT(Building→Equipment)와 GOVERNED_BY(Equipment→Regulation) 둘 다 "equipment"를 포함한다
    // (하나는 relation 이름에, 하나는 subject 이름에).
    await expect(relationList.getByRole('button')).toHaveCount(2);
    await expect(page.getByTestId('outline-relation-4')).toContainText('Building → HAS_EQUIPMENT → Equipment');
    await expect(page.getByTestId('outline-relation-6')).toContainText('Equipment → GOVERNED_BY → Regulation');
    await expect(page.getByTestId('outline-relation-1')).toHaveCount(0); // OCCURRED_AT은 걸러짐

    // 검색어를 지우면 전체 목록이 돌아온다.
    await page.getByLabel('관계 검색').fill('');
    await expect(relationList.getByRole('button')).toHaveCount(6);
  });
});

/**
 * ModelOutline — 도메인명 자동 저장(I-1, Task 6 리뷰).
 * 전체 문서 모달(OntologyEditDialog)의 `#ontology-domain` 입력이 유일한 도메인 편집 경로였는데,
 * 모달을 지우면서(Task 6) patchDomain 뮤테이션·API가 부르는 UI 없이 죽은 코드로 남을 뻔했다.
 */
test.describe('ModelOutline — 도메인명 자동 저장', () => {
  test('도메인명을 고치면 PATCH가 그 필드만 담아 나가고 화면에도 반영된다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    const capture = await mockApi(page, 'PATCH', '/api/v1/ontology/1', { schemaVersion: 2 }, { capture: true });
    const domainInput = page.getByLabel('도메인');
    await expect(domainInput).toHaveValue('화재조사 보고서'); // factory 기본값
    await domainInput.fill('화재조사 보고서(개정)');
    await domainInput.blur();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ domain: '화재조사 보고서(개정)' });

    // NEW-3(Task 6 리뷰 라운드2) — toHaveValue만으로는 로컬 draft를 읽을 뿐이라, patchDomain의
    // 캐시 업데이터가 domain 필드를 실제로 반영하지 않아도 이 단언은 그대로 통과했을 것이다.
    // 수정 모드를 껐다 켜서 ModelOutline을 리마운트시키면 domainField의 초기값이 draft가 아니라
    // ['ontology', 1] 캐시(schema.domain)에서 다시 읽힌다 — 캐시가 실제로 갱신됐는지를 검증한다.
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByRole('button', { name: '수정 모드' }).click();
    await expect(page.getByLabel('도메인')).toHaveValue('화재조사 보고서(개정)');
  });

  test('도메인명을 비우면 PATCH 없이 로컬에서 막히고 aria-invalid가 붙는다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1',
      (route) => {
        if (route.request().method() === 'PATCH') {
          called = true;
          return route.fulfill({ status: 200, body: JSON.stringify({ schemaVersion: 2 }) });
        }
        return route.fallback();
      },
    );

    const domainInput = page.getByLabel('도메인');
    await domainInput.fill('');
    await domainInput.blur();

    await expect(page.getByText('도메인명을 입력하세요.')).toBeVisible();
    await expect(domainInput).toHaveAttribute('aria-invalid', 'true');
    const errorId = await domainInput.getAttribute('aria-describedby');
    expect(errorId).toBeTruthy();
    await expect(page.locator(`#${errorId}`)).toHaveText('도메인명을 입력하세요.');
    expect(called).toBe(false);
  });
});

/**
 * EntityInspector(Task 4) — 타입 필드 + 속성 CRUD + 자동 저장 E2E.
 * factory 엔티티: Building(id=2, 속성 없음, HAS_EQUIPMENT 관계 1개의 끝점),
 * Damage(id=4, 속성 피해액 id=1 unit='원', exact 해상도).
 */
test.describe('EntityInspector — 타입 필드 · 속성 CRUD · 자동 저장', () => {
  // 편집 모드를 켜고 지정한 엔티티(outline-entity-{id})를 선택해 인스펙터를 연 상태까지 준비한다.
  async function openInspector(page: import('@playwright/test').Page, entityId: number) {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByTestId(`outline-entity-${entityId}`).click();
  }

  test('타입 설명을 고치면 PATCH가 그 필드만 담아 나간다', async ({ authenticatedPage: page }) => {
    await openInspector(page, 2);
    const capture = await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/1/entity-types/2',
      createEntityTypeMutation({ id: 2, type: 'Building', description: '바뀐 설명', naming: '본문 표기 보존', resolution: 'embedding', properties: [] }),
      { capture: true },
    );

    await page.getByLabel('설명', { exact: true }).fill('바뀐 설명');
    await page.getByLabel('설명', { exact: true }).blur();

    const req = await capture.waitForRequest();
    // 나머지 필드(type/naming/resolution)는 아예 실리지 않는다 — PATCH 의미론상 null도 "값 있음"으로
    // 해석되므로, 바뀐 필드만 보내야 한다(toEqual은 예상 밖의 여분 키가 있어도 실패한다).
    expect(req.payload).toEqual({ description: '바뀐 설명' });
  });

  // Task 2 리뷰 M-3(RESOLUTIONS 미소비) 해소를 코드로는 확인했지만, 실제로 Select 변경이 즉시
  // 그 필드만 담아 PATCH로 나가는지는 테스트가 없었다(Task 4 리뷰 M-6).
  test('해상도 정책 Select를 바꾸면 즉시(디바운스 없이) 그 필드만 담아 PATCH가 나간다', async ({ authenticatedPage: page }) => {
    await openInspector(page, 2); // Building — 기존 resolution은 embedding
    const capture = await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/1/entity-types/2',
      createEntityTypeMutation({ id: 2, type: 'Building', description: '', naming: '본문 표기 보존', resolution: 'exact', properties: [] }),
      { capture: true },
    );

    await page.getByLabel('해상도 정책', { exact: true }).click();
    await page.getByRole('option', { name: '정확 매칭' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ resolution: 'exact' });
  });

  test('속성 추가/수정/삭제가 각각 올바른 요소 엔드포인트로 나간다', async ({ authenticatedPage: page }) => {
    await openInspector(page, 4); // Damage — 기존 속성 피해액(id=1) 보유

    const addCapture = await mockApi(
      page,
      'POST',
      '/api/v1/ontology/1/entity-types/4/properties',
      createPropertyMutation({ id: 2, name: '진화시간', description: '', dataType: 'number', unit: '분' }),
      { capture: true },
    );
    await page.getByLabel('새 속성 이름').fill('진화시간');
    await page.getByLabel('새 속성 단위').fill('분');
    await page.getByRole('button', { name: 'Damage 속성 추가' }).click();
    const addReq = await addCapture.waitForRequest();
    expect(addReq.payload).toEqual({ name: '진화시간', description: '', dataType: 'text', unit: '분' });

    const updateCapture = await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/1/entity-types/4/properties/1',
      createPropertyMutation({ id: 1, name: '피해액수정', description: '추정 재산 피해액(원)', dataType: 'number', unit: '원' }),
      { capture: true },
    );
    const existingRow = page.getByTestId('property-row-1');
    await existingRow.getByLabel('Damage 속성 이름').fill('피해액수정');
    await existingRow.getByLabel('Damage 속성 이름').blur();
    const updateReq = await updateCapture.waitForRequest();
    expect(updateReq.payload).toEqual({ name: '피해액수정' });

    const deleteCapture = await mockApi(
      page,
      'DELETE',
      '/api/v1/ontology/1/entity-types/4/properties/1',
      createVersionOnly(),
      { capture: true },
    );
    await existingRow.getByLabel('Damage 속성 삭제').click();
    // #419 — 속성 삭제도 관계 삭제와 마찬가지로 확인 다이얼로그를 거친 뒤에만 요청이 나간다.
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();
    await deleteCapture.waitForRequest();
    // 삭제 성공 → 행이 화면에서도 사라진다(M-1, Task 6 리뷰) — 요청이 나간 것만으로는 캐시 갱신이
    // entities[].properties에서 실제로 걸러내는지 증명하지 않는다.
    await expect(page.getByTestId('property-row-1')).toHaveCount(0);
  });

  // I-1(S2 최종 리뷰) — 관계 삭제와 같은 계열: PropertyRow가 언마운트되는데 포커스를 옮기는 코드가
  // 전혀 없었다. 응답을 지연시켜 toBeFocused()의 자동 재시도로 지연 자기 치유를 놓치지 않는다
  // (관계 삭제 포커스 테스트와 동일한 이유 — 단발 page.evaluate()로 확인).
  test('속성 삭제 성공 시 포커스가 "속성 추가" 버튼으로 복귀한다(지연 응답에서도 body 유실 없음)', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 4); // Damage.피해액(id=1)

    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types/4/properties/1',
      async (route) => {
        if (route.request().method() !== 'DELETE') return route.fallback();
        await new Promise((resolve) => setTimeout(resolve, 300));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createVersionOnly()),
        });
      },
    );

    await page.getByTestId('property-row-1').getByLabel('Damage 속성 삭제').click();
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();
    await expect(page.getByTestId('property-row-1')).toHaveCount(0);
    const activeElement = await page.evaluate(() => ({
      tag: document.activeElement?.tagName,
      label: document.activeElement?.getAttribute('aria-label'),
    }));
    expect(activeElement).toEqual({ tag: 'BUTTON', label: 'Damage 속성 추가' });
  });

  // I-2(S2 최종 리뷰) — CreateEntityTypeForm/CreateRelationForm은 리뷰 M-5로 이 가드를 이미 받았는데
  // "속성 추가"만 빠져 있었다. 로컬 중복 검사로는 연타를 막지 못한다: 두 번째 클릭 시점의
  // entity.properties는 첫 번째 응답이 캐시를 갱신하기 전이라 새 이름이 목록에 없어 검증을 통과하고,
  // 서버가 "중복된 속성명(...)" 400을 내 이 파일 전역 제약(서버 문구가 정상 사용에 노출되면 안 된다)을
  // 실제로 깬다. 여기서는 두 번째 클릭이 아예 API를 부르지 않는지(버튼이 잠겼는지)를 검증한다.
  test('"속성 추가"를 연타해도 두 번째 클릭은 요청을 보내지 않는다(이중 제출 가드)', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 4); // Damage
    let callCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types/4/properties',
      async (route) => {
        callCount += 1;
        await new Promise((resolve) => setTimeout(resolve, 200));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPropertyMutation({ id: 2, name: '진화시간', description: '', dataType: 'text', unit: null })),
        });
      },
    );

    await page.getByLabel('새 속성 이름').fill('진화시간');
    const addButton = page.getByRole('button', { name: 'Damage 속성 추가' });
    await addButton.click();
    // 응답이 아직 안 왔으므로(200ms 지연) 버튼이 잠겨 있어야 한다 — 잠기지 않았다면 아래 두 번째
    // 클릭이 실제로 두 번째 요청을 만들어 낼 것이다.
    await expect(addButton).toBeDisabled();
    await addButton.click({ force: true });

    await expect(addButton).toBeEnabled();
    expect(callCount).toBe(1);
  });

  // N-1(S2 최종 리뷰) — I-1(포커스 복귀)과 I-2(이중 제출 가드)가 같은 대상(addPropertyButtonRef)을
  // 공유해 생긴 상호작용. 속성 추가가 진행 중이면 그 버튼은 disabled라 focus()가 no-op이 되어,
  // 그 사이 다른 속성의 삭제가 성공하면 포커스가 다시 <body>로 떨어진다 — I-1이 막으려던 바로 그
  // 회귀가 재발한다. propertiesLabelRef(항상 활성)로 대체 복귀하는지 확인한다.
  test('속성 추가가 진행 중일 때 다른 속성 삭제가 성공하면 포커스가 "속성" 라벨로 대체 복귀한다', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 4); // Damage.피해액(id=1)

    // "속성 추가"를 지연 응답으로 눌러 addingProperty=true 상태를 유지한다.
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types/4/properties',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        await new Promise((resolve) => setTimeout(resolve, 500));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPropertyMutation({ id: 2, name: '진화시간', description: '', dataType: 'text', unit: null })),
        });
      },
    );
    await page.getByLabel('새 속성 이름').fill('진화시간');
    const addButton = page.getByRole('button', { name: 'Damage 속성 추가' });
    await addButton.click();
    await expect(addButton).toBeDisabled();

    // 그 사이 기존 속성(id=1)을 즉시 응답으로 삭제한다.
    await mockApi(page, 'DELETE', '/api/v1/ontology/1/entity-types/4/properties/1', createVersionOnly());
    await page.getByTestId('property-row-1').getByLabel('Damage 속성 삭제').click();
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();
    await expect(page.getByTestId('property-row-1')).toHaveCount(0);

    const activeElement = await page.evaluate(() => ({
      tag: document.activeElement?.tagName,
      text: document.activeElement?.textContent,
    }));
    expect(activeElement).toEqual({ tag: 'LABEL', text: '속성' });

    await expect(addButton).toBeEnabled();
  });

  test('속성 단위를 비우면 unit: ""가 나간다(null 아님)', async ({ authenticatedPage: page }) => {
    await openInspector(page, 4); // Damage.피해액(id=1)의 unit은 '원'
    const capture = await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/1/entity-types/4/properties/1',
      createPropertyMutation({ id: 1, name: '피해액', description: '추정 재산 피해액(원)', dataType: 'number', unit: null }),
      { capture: true },
    );

    const row = page.getByTestId('property-row-1');
    await row.getByLabel('Damage 속성 단위').fill('');
    await row.getByLabel('Damage 속성 단위').blur();

    const req = await capture.waitForRequest();
    // null이면 PATCH 의미론상 "변경 없음"이라 서버가 지우지 않는다 — 반드시 빈 문자열이어야 한다.
    expect(req.payload).toEqual({ unit: '' });
  });

  test('예약어 속성명은 API 호출 없이 로컬에서 차단되고 aria-invalid + aria-describedby가 붙는다', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 4);
    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types/4/properties/1',
      (route) => {
        called = true;
        return route.fulfill({ status: 200, body: JSON.stringify(createPropertyMutation({ id: 1, name: 'type', description: '', dataType: 'number', unit: null })) });
      },
    );

    const row = page.getByTestId('property-row-1');
    const nameInput = row.getByLabel('Damage 속성 이름');
    await nameInput.fill('type'); // Neo4j 예약 필드(RESERVED_PROPERTY_NAMES)
    await nameInput.blur();

    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    const describedBy = await nameInput.getAttribute('aria-describedby');
    expect(describedBy).toBeTruthy();
    await expect(page.locator(`#${describedBy}`)).toContainText('예약어');
    expect(called).toBe(false);
  });

  // 옛 모달의 "속성명을 비운 채 저장하면 중복이 아니라 빈 이름으로 진단되고 저장 API가 호출되지
  // 않는다"를 이식한다 — 요소 단위 편집기에는 저장 버튼이 없으므로("속성 추가" 클릭이 곧 커밋),
  // handleAddProperty가 blank를 addProperty 호출 전에 막는지와 그 aria 배선(#329 이관)을 검증한다.
  test('새 속성을 이름 없이 추가하려 하면 API 호출 없이 로컬에서 막히고 aria-invalid가 붙는다', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 4); // Damage
    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types/4/properties',
      (route) => {
        called = true;
        return route.fulfill({ status: 200, body: JSON.stringify(createPropertyMutation({ id: 2, name: '', description: '', dataType: 'text', unit: null })) });
      },
    );

    const nameInput = page.getByLabel('새 속성 이름');
    await page.getByRole('button', { name: 'Damage 속성 추가' }).click();

    await expect(page.getByText('속성명을 입력하세요')).toBeVisible();
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    const errorId = await nameInput.getAttribute('aria-describedby');
    expect(errorId).toBeTruthy();
    await expect(page.locator(`#${errorId}`)).toHaveText('속성명을 입력하세요');
    expect(called).toBe(false);
  });

  // 옛 모달의 "타입을 삭제하면 그 타입과 참조 관계가 저장 payload에서 함께 제거된다"를 이식한다 —
  // 다만 요소 단위 API는 payload를 조립하는 대신 DELETE 한 번을 보내고 서버가 deletedRelationIds로
  // 무엇이 CASCADE로 함께 사라졌는지 알려준다(useOntologyElement.ts). 확인 다이얼로그의 안내 문구만
  // 검증하던 기존 테스트에 실제 삭제 요청·아웃라인 반영까지 더한다 — 문구가 맞아도 삭제 자체가
  // 안 나가거나 관계가 화면에 남아 있으면 안 된다.
  test('타입 삭제 확인 후 DELETE가 나가고 그 타입과 참조 관계가 아웃라인에서 함께 사라진다', async ({
    authenticatedPage: page,
  }) => {
    // Building(id=2)은 두 관계의 끝점이다 — OCCURRED_AT(id=1, object=Building)과 HAS_EQUIPMENT(id=4, subject=Building).
    await openInspector(page, 2);

    await page.getByTestId('entity-delete-trigger').click();

    const affected = page.getByTestId('delete-type-affected-relations');
    await expect(affected).toContainText('함께 삭제되는 관계 2개');
    await expect(affected).toContainText('Incident → OCCURRED_AT → Building');
    await expect(affected).toContainText('Building → HAS_EQUIPMENT → Equipment');

    // 응답을 즉시(mockApi 기본값) 준다 — NEW-1(Task 6 리뷰 라운드2)이 겨냥하는 실패 조건이 바로
    // 이 "빠른 응답" 경로다. 검증해 보니(직접 소스에서 아래 focus() 호출을 지워보고 확인) 다이얼로그가
    // 닫히는 시점에 트리거가 아직 DOM에 남아 있으면 공유 dialog.tsx의 자동 복귀가 일단 트리거로
    // 성공하지만, 뮤테이션이 곧바로 성공해 인스펙터가 갈아치워지며 트리거가 사라지면 포커스가
    // 순간적으로 <body>로 떨어진다 — 그 뒤 dialog.tsx의 restoreFocusRef 안전망(M-2)이 지연 끝에
    // 스스로 복구하긴 하지만(관측상 최대 ~1초), 그 사이의 <body> 유실 구간 자체가 회귀다(#328류,
    // 스크린리더 사용자에게는 그 순간 컨텍스트를 잃은 것으로 들린다). 그래서 이 단언은 Playwright의
    // 자동 재시도(toBeFocused)가 아니라 단발 page.evaluate()로 "그 즉시"의 activeElement를 확인한다
    // — 재시도형 단언은 안전망이 뒤늦게 복구하는 순간까지 기다렸다 통과해 버려 이 회귀를 가린다.
    const capture = await mockApi(
      page,
      'DELETE',
      '/api/v1/ontology/1/entity-types/2',
      { schemaVersion: 2, deletedRelationIds: [1, 4] },
      { capture: true },
    );
    await page.getByTestId('entity-delete-confirm').click();

    // 삭제 성공 → 선택이 비워지고 인스펙터가 빈 안내 문구로 돌아간다(RelationInspector 삭제와 동일한 패턴).
    await expect(page.getByText('왼쪽에서 타입 또는 관계를 선택하세요.')).toBeVisible();
    // 포커스 복귀(M-2/NEW-1, Task 6 리뷰) — 단발 확인이라 재시도로 뒤늦게 성공하는 걸 놓치지 않는다.
    const activeElement = await page.evaluate(() => ({
      tag: document.activeElement?.tagName,
      label: document.activeElement?.getAttribute('aria-label'),
    }));
    expect(activeElement).toEqual({ tag: 'BUTTON', label: '타입 추가' });

    await capture.waitForRequest();
    // 타입 자신과, CASCADE로 함께 사라진 관계 2건이 모두 아웃라인에서 사라진다.
    await expect(page.getByTestId('outline-entity-2')).toHaveCount(0);
    await expect(page.getByTestId('outline-relation-1')).toHaveCount(0);
    await expect(page.getByTestId('outline-relation-4')).toHaveCount(0);
    // 삭제와 무관한 관계(id=2, Incident→Cause)는 그대로 남는다 — 필터가 과도하게 지우지 않았는지 확인.
    await expect(page.getByTestId('outline-relation-2')).toBeVisible();
  });

  // M-4.2(S2 최종 리뷰) — 서버(OntologyElementService.deleteEntityType)는 active 온톨로지의 마지막
  // 엔티티 타입 삭제를 "엔티티 타입은 최소 1개 이상이어야 합니다." 400으로 거부한다. 로컬이 이 조건을
  // 모른 채 트리거를 그대로 활성화해 두면 그 서버 문구가 정상 사용 경로에 노출된다(이 파일 전역
  // 제약 위반). 타입이 1개뿐인 active 온톨로지를 모킹해 트리거가 미리 비활성화되는지 확인한다.
  test('active 온톨로지의 마지막 엔티티 타입은 삭제 트리거가 비활성화되고 이유가 화면에 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    // GET /api/v1/ontology(id 없는 bare 엔드포인트)는 모킹하지 않는다 — M-2로 프론트 호출부
    // (useOntologySchema/ontologyApi.getOntology)를 지웠으므로 이 화면이 더 이상 그 경로를 부르지
    // 않는다(N-3, S2 최종 리뷰). 남겨두면 다음 독자가 "여전히 호출된다"고 오해할 수 있었다.
    await mockApi(page, 'GET', '/api/v1/ontology/graph', createOntologyGraph());
    await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
    await mockApi(
      page,
      'GET',
      '/api/v1/ontology/1',
      createOntologySchema({
        entities: [
          { id: 1, type: 'Incident', description: '사건/이벤트', naming: '문서마다 고유', resolution: 'exact', properties: [] },
        ],
        relations: [],
      }),
    );
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByTestId('outline-entity-1').click();

    await expect(page.getByTestId('entity-delete-trigger')).toBeDisabled();
    // disabled 버튼은 pointer-events-none이라 title 툴팁이 hover로도 안 뜬다(N-2) — 이유가
    // 눈에 보이는 텍스트로 실제로 렌더되는지까지 확인한다.
    await expect(page.getByTestId('entity-delete-disabled-reason')).toHaveText(
      '활성 온톨로지는 엔티티 타입이 최소 1개 있어야 합니다.',
    );
  });

  test('저장 실패(500) 시 SaveStatusChip이 error가 되고 재시도가 가능하다', async ({ authenticatedPage: page }) => {
    await openInspector(page, 2);
    let attempts = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types/2',
      (route) => {
        attempts += 1;
        if (attempts === 1) {
          return route.fulfill({ status: 500, body: JSON.stringify({ message: '저장 실패' }) });
        }
        return route.fulfill({
          status: 200,
          body: JSON.stringify(
            createEntityTypeMutation({ id: 2, type: 'Building', description: '재시도로 저장됨', naming: '본문 표기 보존', resolution: 'embedding', properties: [] }),
          ),
        });
      },
    );

    await page.getByLabel('설명', { exact: true }).fill('재시도로 저장됨');
    await page.getByLabel('설명', { exact: true }).blur();

    const chip = page.getByTestId('save-status-chip');
    await expect(chip).toHaveAttribute('data-state', 'error');
    await page.getByTestId('save-status-retry').click();

    await expect(chip).toHaveAttribute('data-state', 'saved');
    expect(attempts).toBe(2);
  });

  // Task 3 리뷰 IMP-1 회귀 가드 — 인스펙터가 OntologyPage의 useOntologyElementMutations 인스턴스를
  // 다시 호출하면(자기만의 훅을 새로 부르면) 실제 뮤테이션은 그 인스턴스에서 일어나는데 툴바 칩이
  // 구독하는 saveState는 별개 인스턴스라 영원히 idle에 머문다 — 이 테스트 없이는 그 위반이 아무
  // 것도 깨뜨리지 않고 통과해 버린다.
  test('인스펙터에서 편집하면 툴바 SaveStatusChip이 저장 중 → 저장됨으로 전이한다(IMP-1 회귀 가드)', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 2);
    // 'saving' 상태를 고정된 지연 시간이 아니라 수동으로 응답을 풀어주는 방식으로 관측한다
    // (Task 4 리뷰 M-5) — 시간 창에 의존하면 CI 부하 시 flake가 될 수 있다.
    let releaseResponse: (() => void) | undefined;
    const responseGate = new Promise<void>((resolve) => {
      releaseResponse = resolve;
    });
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types/2',
      async (route) => {
        await responseGate;
        return route.fulfill({
          status: 200,
          body: JSON.stringify(
            createEntityTypeMutation({ id: 2, type: 'Building', description: '느린 저장', naming: '본문 표기 보존', resolution: 'embedding', properties: [] }),
          ),
        });
      },
    );

    const chip = page.getByTestId('save-status-chip');
    await expect(chip).toHaveCount(0); // idle: 시각 칩은 아직 그려지지 않는다.

    await page.getByLabel('설명', { exact: true }).fill('느린 저장');
    await page.getByLabel('설명', { exact: true }).blur();

    await expect(chip).toHaveAttribute('data-state', 'saving');
    releaseResponse?.();
    await expect(chip).toHaveAttribute('data-state', 'saved');
  });

  // Task 4 리뷰 C-1 회귀 가드 — commit()이 요청 완료 전에 committed를 확정해 버리면, 실패한 편집 A는
  // 다시는 재시도되지 않고(blur/디바운스가 매번 조기 반환) 그 뒤 다른 필드 B가 성공하는 순간 칩만
  // "저장됨"으로 바뀌어 사용자가 A의 유실을 알아챌 수 없다. 이 테스트는 그 경로를 그대로 밟는다:
  // 타입 이름(A) 편집 실패 → 설명(B) 편집 성공 → 칩은 저장됨이어도 A는 여전히 dirty(재시도 가능)여야
  // 하고, 이후 blur가 다시 오면 A의 PATCH가 실제로 재전송돼야 한다.
  //
  // 재시도 성공 응답은 실제로 타입 이름을 바꾼다(리네임) — 처음엔 이름을 그대로 두어 우회했었지만,
  // 그건 실제 크래시(C-2, 아래 별도 테스트)를 피해가는 것이었을 뿐 이 테스트의 목적과 무관하지
  // 않다: 실패→재시도 경로가 "리네임이 아닌 편집"에서만 도는 게 아니라 리네임에서도 똑같이 안전해야
  // 한다. C-2 수정(캐시 업데이터의 relations 리매핑) 이후에는 리네임을 그대로 둬도 크래시하지 않는다.
  test('편집 A 실패 후 편집 B가 성공해도 A는 서버에 없다 — 유실되지 않고 dirty로 남아 재시도된다(C-1 회귀 가드)', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 2); // Building
    let typeAttempts = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types/2',
      (route) => {
        const payload = route.request().postDataJSON() as { type?: string; description?: string };
        if (payload.type !== undefined) {
          typeAttempts += 1;
          if (typeAttempts === 1) {
            return route.fulfill({ status: 500, body: JSON.stringify({ message: '저장 실패' }) });
          }
          return route.fulfill({
            status: 200,
            body: JSON.stringify(
              createEntityTypeMutation(
                { id: 2, type: '재시도된이름', description: '', naming: '본문 표기 보존', resolution: 'embedding', properties: [] },
                3,
              ),
            ),
          });
        }
        // 설명(B) 편집은 항상 성공한다.
        return route.fulfill({
          status: 200,
          body: JSON.stringify(
            createEntityTypeMutation(
              { id: 2, type: 'Building', description: payload.description ?? '', naming: '본문 표기 보존', resolution: 'embedding', properties: [] },
              2,
            ),
          ),
        });
      },
    );

    // A: 타입 이름 편집 — 실패한다.
    const typeInput = page.getByLabel('타입 이름', { exact: true });
    await typeInput.fill('바뀔뻔한이름');
    await typeInput.blur();
    await expect(page.getByTestId('save-status-chip')).toHaveAttribute('data-state', 'error');
    expect(typeAttempts).toBe(1);

    // B: 설명 편집 — 성공한다.
    const descInput = page.getByLabel('설명', { exact: true });
    await descInput.fill('B는 성공');
    await descInput.blur();

    // 칩은 "저장됨"이지만, 이건 B의 성공일 뿐 A(타입 이름)의 실패를 덮어 가리면 안 된다.
    await expect(page.getByTestId('save-status-chip')).toHaveAttribute('data-state', 'saved');

    // A가 조용히 버려지지 않았다면, 이제 타입 이름 필드에 다시 포커스했다 벗어나는 것만으로
    // 재전송돼야 한다(draft가 여전히 dirty이므로) — committed가 요청 성공 전에 확정돼 버렸다면
    // 이 두 번째 blur는 "변경 없음"으로 조기 반환되어 typeAttempts가 늘지 않는다.
    await typeInput.focus();
    await typeInput.blur();
    await expect.poll(() => typeAttempts).toBe(2);
    await expect(page.getByTestId('save-status-chip')).toHaveAttribute('data-state', 'saved');

    // 리네임이 성공했는데도 캔버스가 크래시하지 않고(C-2), 아웃라인의 관계 목록도 새 이름으로
    // 갱신되어 있어야 한다 — relations[].subject/object가 옛 이름('Building')에 머물러 있으면
    // SchemaGraph가 존재하지 않는 노드를 잇는 엣지를 만들려다 PageErrorBoundary로 떨어진다.
    await expect(page.getByRole('heading', { name: '페이지를 불러오는 중 문제가 발생했습니다' })).toHaveCount(0);
    await expect(page.getByTestId('schema-graph')).toBeVisible();
    await expect(page.getByTestId('outline-entity-2')).toContainText('재시도된이름');
    // HAS_EQUIPMENT(id=4)는 Building(id=2)이 주어(subject)인 관계 — 리네임 후 새 이름으로 보여야 한다.
    await expect(page.getByTestId('outline-relation-4')).toContainText('재시도된이름 → HAS_EQUIPMENT → Equipment');
  });

  // Task 4 리뷰 C-2 회귀 가드 — updateEntityType의 낙관적 캐시 갱신이 entities[]만 바꾸고
  // relations[].subject/object(타입 "이름" 참조)를 그대로 두면, SchemaGraph가 옛 이름을 가리키는
  // 엣지를 만들려다 존재하지 않는 노드를 참조해 크래시한다. 이 테스트는 실패 재시도 경로 없이
  // "정상적으로 한 번에 성공하는 리네임"만으로 이 경로를 직접 겨냥한다.
  test('타입 이름을 바꾸면 캐시의 관계 subject/object도 새 이름으로 갱신되고 캔버스가 크래시하지 않는다(C-2 회귀 가드)', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 3); // Cause — CAUSED_BY(id=2)의 목적어(object)
    await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/1/entity-types/3',
      createEntityTypeMutation({ id: 3, type: '원인유형', description: '', naming: '본문 표기 보존', resolution: 'embedding', properties: [] }),
    );

    await page.getByLabel('타입 이름', { exact: true }).fill('원인유형');
    await page.getByLabel('타입 이름', { exact: true }).blur();

    await expect(page.getByTestId('save-status-chip')).toHaveAttribute('data-state', 'saved');
    await expect(page.getByRole('heading', { name: '페이지를 불러오는 중 문제가 발생했습니다' })).toHaveCount(0);
    await expect(page.getByTestId('schema-graph')).toBeVisible();
    await expect(page.getByTestId('outline-entity-3')).toContainText('원인유형');
    // CAUSED_BY(id=2)는 Cause(id=3)가 목적어(object)인 관계.
    await expect(page.getByTestId('outline-relation-2')).toContainText('Incident → CAUSED_BY → 원인유형');
    // 이름이 바뀌지 않은 다른 관계(HAS_EQUIPMENT, Cause와 무관)는 그대로여야 한다 — 리매핑이
    // "옛 이름과 일치하는 것만" 건드리는지 확인한다.
    await expect(page.getByTestId('outline-relation-4')).toContainText('Building → HAS_EQUIPMENT → Equipment');
  });

  // 옛 모달의 "이미 존재하는 타입 이름으로 변경을 시도하면 로컬 에러 토스트가 뜨고 카드가 그대로
  // 유지된다"를 이식한다 — typeField의 검증 함수가 validateEntityTypeName(..., entity.type)로
  // excludeName을 자기 자신으로 넘기므로(EntityInspector.tsx), 정상적으로 이름을 그대로 두는 blur는
  // 통과하고 다른 타입과 겹치는 이름만 막혀야 한다.
  test('이미 존재하는 타입 이름으로 리네임을 시도하면 API 호출 없이 로컬에서 막힌다', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 3); // Cause
    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types/3',
      (route) => {
        called = true;
        return route.fulfill({ status: 200, body: JSON.stringify(createEntityTypeMutation({ id: 3, type: 'Building', description: '', naming: '본문 표기 보존', resolution: 'embedding', properties: [] })) });
      },
    );

    const typeInput = page.getByLabel('타입 이름', { exact: true });
    await typeInput.fill('Building'); // 이미 존재하는 타입(id=2)
    await typeInput.blur();

    await expect(page.getByText('이미 존재하는 타입입니다: Building')).toBeVisible();
    await expect(typeInput).toHaveAttribute('aria-invalid', 'true');
    expect(called).toBe(false);
    // 카드(아웃라인)는 원래 이름 그대로 — 차단된 리네임이 부분 적용되지 않는다.
    await expect(page.getByTestId('outline-entity-3')).toContainText('Cause');
  });

  // Task 4 리뷰 N-2 회귀 가드 — 겹친 실패 커밋 X→Y에서 되돌림 기준을 "직전 committed"로 쓰면(수정 전)
  // Y의 되돌림이 committed를 X로 만들어 버린다("서버가 가진 적 없는 값"). 그러면 사용자가 나중에
  // 정확히 X를 다시 입력해도 draft===committed라 재전송이 조용히 스킵된다. 수정 후에는
  // prevServerValue(마지막으로 관측한 실제 서버 값)로 되돌리므로 이 상태가 아예 생기지 않는다.
  test('겹친 커밋이 모두 실패해도, 그중 한 값을 다시 입력하면 조용히 스킵되지 않고 재전송된다(N-2 회귀 가드)', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 2); // Building, 원래 설명='물리적 장소/건물'
    // 두 요청(X, Y)을 각각 수동으로 풀어줄 수 있도록 게이트를 건다 — X가 아직 응답 전인 상태에서
    // Y가 시작되게 하려면 실제 네트워크 타이밍에 기대면 안 된다(M-5와 같은 이유).
    const gates: Array<() => void> = [];
    const payloads: string[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types/2',
      async (route) => {
        const payload = route.request().postDataJSON() as { description?: string };
        payloads.push(payload.description ?? '');
        await new Promise<void>((resolve) => gates.push(resolve));
        return route.fulfill({ status: 500, body: JSON.stringify({ message: '저장 실패' }) });
      },
    );

    const descInput = page.getByLabel('설명', { exact: true });

    // X: 커밋 — 응답이 게이트에 걸려 아직 진행 중이다.
    await descInput.fill('X값');
    await descInput.blur();
    await expect.poll(() => payloads.length).toBe(1);

    // Y: X가 아직 진행 중인 상태에서 커밋 — commit()의 낙관적 갱신 덕분에 X와 다른 값이라 즉시 나간다.
    await descInput.fill('Y값');
    await descInput.blur();
    await expect.poll(() => payloads.length).toBe(2);
    expect(payloads).toEqual(['X값', 'Y값']);

    // 두 요청 모두 실패로 풀어준다(순서는 상관없다 — 되돌림은 값 동일성으로 판단되지 시간 순서로
    // 판단되지 않는다).
    gates[0]();
    gates[1]();
    await expect(page.getByTestId('save-status-chip')).toHaveAttribute('data-state', 'error');

    // X를 다시 정확히 입력 — 수정 전이었다면 committed가 이미 'X값'으로 잘못 되돌아가 있어
    // 이 blur가 "변경 없음"으로 조기 반환되고 payloads가 늘지 않았을 것이다.
    await descInput.fill('X값');
    await descInput.blur();
    await expect.poll(() => payloads.length).toBe(3);
    expect(payloads[2]).toBe('X값');
  });

  // Task 4 리뷰 N-1 회귀 가드 — trim 필드(타입 이름/속성 이름)가 커밋된 값은 trim하면서 draft는
  // trim하지 않으면, 성공 이후에도 draft(공백 포함) !== committed(trim됨)라 그 필드가 영구히
  // dirty로 남는다 — blur할 때마다 매번 같은 trim된 값을 다시 보내려 시도하게 된다.
  test('앞뒤 공백이 있는 타입 이름을 저장하면 trim된 값이 화면에도 반영되고 더 이상 dirty로 남지 않는다(N-1 회귀 가드)', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 2); // Building
    const capture = await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/1/entity-types/2',
      createEntityTypeMutation({ id: 2, type: '새건물', description: '', naming: '본문 표기 보존', resolution: 'embedding', properties: [] }),
      { capture: true },
    );

    const typeInput = page.getByLabel('타입 이름', { exact: true });
    await typeInput.fill('  새건물  ');
    await typeInput.blur();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ type: '새건물' }); // trim된 값으로 전송
    await expect(typeInput).toHaveValue('새건물'); // 화면 draft도 trim된 값으로 정리된다

    // 다시 포커스했다 벗어나도(값 변경 없음) 재전송되지 않아야 한다 — draft===committed(둘 다
    // trim된 값)이므로 dirty가 아니다. 수정 전이었다면 draft에 공백이 남아 있어 이 blur가 또
    // 같은 값을 보내려 시도했을 것이다.
    await typeInput.focus();
    await typeInput.blur();
    // 짧게 여유를 두고도 추가 요청이 없는지 확인한다(디바운스 타이머까지 감안).
    await page.waitForTimeout(500);
    expect(capture.requests).toHaveLength(1);
  });
});

/**
 * RelationInspector(Task 5) — 관계 생성·편집·삭제 E2E.
 * factory 트리플: OCCURRED_AT(id=1, Incident→Building) 등 6개(ontology.factory.ts 참고).
 * Damage(id=4)→Regulation(id=6)은 기존 트리플에 없는 조합이라 생성 테스트에 쓴다.
 */
test.describe('RelationInspector — 관계 생성·편집·삭제', () => {
  // 편집 모드를 켜고 지정한 관계(outline-relation-{id})를 선택해 인스펙터를 연 상태까지 준비한다.
  async function openRelationInspector(page: import('@playwright/test').Page, relationId: number) {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByTestId(`outline-relation-${relationId}`).click();
  }

  test('관계 추가 시 POST /relations 본문이 subjectTypeId/objectTypeId를 id로 담는다(이름 아님)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    await page.getByRole('button', { name: '관계 추가' }).click();
    await expect(page.getByTestId('relation-inspector-create')).toBeVisible();

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/ontology/1/relations',
      createRelationMutation({
        id: 7,
        subject: 'Damage',
        relation: 'REQUIRES',
        object: 'Regulation',
        description: '피해 유형별 요구 규정',
        subjectTypeId: 4,
        objectTypeId: 6,
      }),
      { capture: true },
    );

    await page.getByLabel('주어 타입').click();
    await page.getByRole('option', { name: 'Damage' }).click();
    await page.getByLabel('목적어 타입').click();
    await page.getByRole('option', { name: 'Regulation' }).click();
    // 앞뒤 공백(리뷰 M-7 회귀 가드) — trim된 값으로 검증·전송돼야 한다. 공백째 전송되면 화면에
    // 보이는 이름과 실제 저장된 트리플 키가 미묘하게 어긋난다.
    await page.getByLabel('관계명').fill('  REQUIRES  ');
    await page.getByLabel('관계 설명').fill('피해 유형별 요구 규정');
    await page.getByRole('button', { name: '관계 만들기' }).click();

    const req = await capture.waitForRequest();
    // 끝점은 이름(Damage/Regulation)이 아니라 id(4/6)로 나가야 한다 — 서버 계약(CreateRelationRequest)이
    // subjectTypeId/objectTypeId를 받는다(types/ontology.ts 주석: 이름으로 보내면 리네임과 경합한다).
    // relation도 trim된 'REQUIRES'로 나간다(M-7) — '  REQUIRES  ' 그대로면 안 된다.
    expect(req.payload).toEqual({
      subjectTypeId: 4,
      relation: 'REQUIRES',
      objectTypeId: 6,
      description: '피해 유형별 요구 규정',
    });

    // 생성 성공 → 새 관계가 선택 상태로 전환되어 편집 폼(끝점 읽기 전용 표시)이 보인다.
    await expect(page.getByTestId('relation-inspector')).toBeVisible();
    await expect(page.getByTestId('relation-endpoint-subject')).toHaveText('Damage');
    await expect(page.getByTestId('relation-endpoint-object')).toHaveText('Regulation');
  });

  test('관계명·설명 수정이 PATCH로 나가고 끝점은 담기지 않는다', async ({ authenticatedPage: page }) => {
    await openRelationInspector(page, 1); // OCCURRED_AT(Incident→Building)

    const nameCapture = await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/1/relations/1',
      createRelationMutation({
        id: 1,
        subject: 'Incident',
        relation: 'HAPPENED_AT',
        object: 'Building',
        description: '사건이 발생한 장소',
        subjectTypeId: 1,
        objectTypeId: 2,
      }),
      { capture: true },
    );
    const nameInput = page.getByLabel('관계명', { exact: true });
    await nameInput.fill('HAPPENED_AT');
    await nameInput.blur();
    const nameReq = await nameCapture.waitForRequest();
    // 관계명만 실린다 — description은 물론 끝점(subjectTypeId/objectTypeId)도 절대 담기지 않는다
    // (types/ontology.ts UpdateRelationRequest 주석: "끝점은 수정 대상이 아니다").
    expect(nameReq.payload).toEqual({ relation: 'HAPPENED_AT' });

    const descCapture = await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/1/relations/1',
      createRelationMutation({
        id: 1,
        subject: 'Incident',
        relation: 'HAPPENED_AT',
        object: 'Building',
        description: '바뀐 설명',
        subjectTypeId: 1,
        objectTypeId: 2,
      }),
      { capture: true },
    );
    const descInput = page.getByLabel('설명', { exact: true });
    await descInput.fill('바뀐 설명');
    await descInput.blur();
    const descReq = await descCapture.waitForRequest();
    expect(descReq.payload).toEqual({ description: '바뀐 설명' });
  });

  // 옛 모달의 "관계명을 비운 채 저장하면 위치를 특정한 에러가 뜨고 저장 API가 호출되지 않는다"와
  // "#329 관계명이 비면 관계명 입력에 aria-invalid와 오류 문구 연결이 붙는다"를 함께 이식한다.
  // validateRelationName의 blank 차단은 CreateRelationForm(생성)과 EditRelationForm(편집) 양쪽에
  // 배선돼 있다 — 편집 쪽(자동 저장)을 겨냥한다: 기존 값을 지우면 PATCH가 나가지 않아야 한다.
  test('관계명을 비우면 PATCH 없이 로컬에서 막히고 aria-invalid가 붙는다', async ({ authenticatedPage: page }) => {
    await openRelationInspector(page, 1); // OCCURRED_AT(Incident→Building)
    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/relations/1',
      (route) => {
        called = true;
        return route.fulfill({ status: 200, body: JSON.stringify(createRelationMutation({ id: 1, subject: 'Incident', relation: '', object: 'Building', description: '', subjectTypeId: 1, objectTypeId: 2 })) });
      },
    );

    const nameInput = page.getByLabel('관계명', { exact: true });
    await nameInput.fill('');
    await nameInput.blur();

    await expect(page.getByText('관계명을 입력하세요(Incident → Building)')).toBeVisible();
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    const errorId = await nameInput.getAttribute('aria-describedby');
    expect(errorId).toBeTruthy();
    await expect(page.locator(`#${errorId}`)).toHaveText('관계명을 입력하세요(Incident → Building)');
    expect(called).toBe(false);
  });

  // 같은 blank 차단이 생성 폼(CreateRelationForm)에도 배선돼 있는지 — "관계 만들기" 클릭 시
  // API 호출 전에 막혀야 한다(생성 폼은 관계명이 항상 빈 문자열로 시작한다).
  test('생성 폼에서 관계명 없이 "관계 만들기"를 누르면 API 호출 없이 막힌다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByRole('button', { name: '관계 추가' }).click();

    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/relations',
      (route) => {
        called = true;
        return route.fulfill({ status: 200, body: JSON.stringify(createRelationMutation({ id: 7, subject: 'Damage', relation: '', object: 'Regulation', description: '', subjectTypeId: 4, objectTypeId: 6 })) });
      },
    );

    await page.getByLabel('주어 타입').click();
    await page.getByRole('option', { name: 'Damage' }).click();
    await page.getByLabel('목적어 타입').click();
    await page.getByRole('option', { name: 'Regulation' }).click();
    await page.getByRole('button', { name: '관계 만들기' }).click();

    await expect(page.getByText('관계명을 입력하세요(Damage → Regulation)')).toBeVisible();
    expect(called).toBe(false);
  });

  // (리뷰 I-1 수정 후 재조준) validateTripleUniqueness가 생기면서 "정상 사용" 경로의 중복은 이제
  // 로컬에서 막힌다(아래 "로컬에서 중복을 막아 API 호출조차 나가지 않는다" 테스트가 그걸 검증한다).
  // 서버 400은 더 이상 정상 경로로 도달하지 않으므로, 이 테스트는 동시 편집 레이스로 재조준한다:
  // 다른 세션이 먼저 같은 트리플을 만들어 서버 상태가 이 화면의 캐시된 스키마보다 앞서 있으면,
  // 로컬 검사(캐시 기준)는 통과하지만 서버는 여전히 거부한다 — 서버가 최종 방어선으로 남아 있는지
  // 확인하는 테스트다. 문구는 실제 서버(OntologyRules.java:131, 파이프 구분)의 문구를 그대로 쓴다.
  test('로컬 검증을 통과해도 서버가 거부하면(동시 편집 레이스) 400 문구가 그대로 토스트에 뜬다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByRole('button', { name: '관계 추가' }).click();

    // Damage(4)→Regulation(6)은 이 화면이 들고 있는 캐시된 스키마(factory 6트리플)에는 없는 조합이라
    // 로컬 중복 검사는 통과한다 — 하지만 다른 세션이 이미 같은 트리플을 만들었다고 가정하고 서버는
    // 400을 반환한다.
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/relations',
      (route) =>
        route.fulfill({
          status: 400,
          body: JSON.stringify({ message: '중복된 관계: Damage|REQUIRES|Regulation' }),
        }),
    );

    await page.getByLabel('주어 타입').click();
    await page.getByRole('option', { name: 'Damage' }).click();
    await page.getByLabel('목적어 타입').click();
    await page.getByRole('option', { name: 'Regulation' }).click();
    await page.getByLabel('관계명').fill('REQUIRES');
    await page.getByRole('button', { name: '관계 만들기' }).click();

    await expect(page.getByText('중복된 관계: Damage|REQUIRES|Regulation')).toBeVisible();
  });

  test('로컬에서 중복을 막아 API 호출조차 나가지 않는다(생성)', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByRole('button', { name: '관계 추가' }).click();

    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/relations',
      (route) => {
        called = true;
        return route.fulfill({ status: 200, body: JSON.stringify(createRelationMutation({ id: 99, subject: 'Incident', relation: 'OCCURRED_AT', object: 'Building', description: '', subjectTypeId: 1, objectTypeId: 2 })) });
      },
    );

    // OCCURRED_AT(Incident→Building)은 이미 존재하는 트리플(id=1) — 캐시된 스키마로 로컬에서 바로 막혀야 한다.
    await page.getByLabel('주어 타입').click();
    await page.getByRole('option', { name: 'Incident' }).click();
    await page.getByLabel('목적어 타입').click();
    await page.getByRole('option', { name: 'Building' }).click();
    await page.getByLabel('관계명').fill('OCCURRED_AT');
    await page.getByRole('button', { name: '관계 만들기' }).click();

    await expect(page.getByText('중복된 관계: Incident -OCCURRED_AT-> Building')).toBeVisible();
    expect(called).toBe(false);
  });

  test('로컬에서 중복을 막아 API 호출조차 나가지 않는다(리네임) + 자기 자신 제외(excludeId)', async ({
    authenticatedPage: page,
  }) => {
    // factory의 6트리플은 끝점 조합이 모두 달라(진짜 중복 리네임을 재현할 수 없어) 같은 끝점
    // (Incident→Cause)을 쓰는 관계를 하나 더 얹은 스키마를 쓴다 — DIFFERENT_NAME(id=7).
    await setupAdminAuth(page);
    const base = createOntologySchema();
    const extra = { id: 7, subject: 'Incident', relation: 'DIFFERENT_NAME', object: 'Cause', description: '', subjectTypeId: 1, objectTypeId: 3 };
    const schema = createOntologySchema({ relations: [...base.relations, extra] });
    await mockApi(page, 'GET', '/api/v1/ontology', schema);
    await mockApi(page, 'GET', '/api/v1/ontology/1', schema);
    await mockApi(page, 'GET', '/api/v1/ontology/graph', createOntologyGraph());
    await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByTestId('outline-relation-2').click(); // CAUSED_BY(Incident→Cause)

    // excludeId 가드 확인(먼저): CAUSED_BY(id=2) 자신은 schema.relations 안에 이미 "Incident|
    // CAUSED_BY|Cause"로 존재한다 — excludeId 없이 전체 목록과 비교했다면 건드리지도 않은 원래
    // 값조차 스스로와 충돌해 즉시 "중복" 에러가 떴을 것이다. 아무것도 입력하지 않은 상태에서
    // 에러가 없어야 한다.
    const nameInput = page.getByLabel('관계명', { exact: true });
    await expect(nameInput).not.toHaveAttribute('aria-invalid');

    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/relations/2',
      (route) => {
        called = true;
        return route.fulfill({ status: 500, body: JSON.stringify({ message: '도달하면 안 됨' }) });
      },
    );

    // CAUSED_BY(id=2, Incident→Cause)를 DIFFERENT_NAME으로 리네임 — 같은 끝점(Incident→Cause)을 쓰는
    // 관계(id=7)가 이미 그 이름이라 트리플이 겹친다. PATCH가 나가면 안 되고 로컬 에러가 떠야 한다.
    await nameInput.fill('DIFFERENT_NAME');
    await nameInput.blur();
    await expect(page.getByText('중복된 관계: Incident -DIFFERENT_NAME-> Cause')).toBeVisible();
    await page.waitForTimeout(500); // 디바운스까지 감안해도 추가 요청이 없는지 확인.
    expect(called).toBe(false);
  });

  // #419 — 이전에는 확인 없이 클릭 즉시 DELETE가 나갔다(실수 클릭 시 복구 수단 전무). 엔티티 타입
  // 삭제(DeleteTypeConfirm)와 같은 AlertDialog 확인 패턴을 범용 DeleteConfirmDialog로 적용했다.
  test('관계 삭제는 확인 다이얼로그를 거친 뒤에만 DELETE가 나간다(취소 시 요청 없음)', async ({
    authenticatedPage: page,
  }) => {
    await openRelationInspector(page, 4); // HAS_EQUIPMENT(Building→Equipment)
    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/relations/4',
      (route) => {
        if (route.request().method() !== 'DELETE') return route.fallback();
        called = true;
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(createVersionOnly()) });
      },
    );

    await page.getByTestId('relation-delete-trigger').click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByRole('alertdialog').getByText('Building → HAS_EQUIPMENT → Equipment')).toBeVisible();

    // 취소하면 요청이 전혀 안 나간다 — 확인 없이 즉시 삭제되던 원래 결함(#419)의 핵심.
    await page.getByRole('alertdialog').getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    expect(called).toBe(false);
    await expect(page.getByTestId('relation-delete-trigger')).toBeVisible();

    const capture = await mockApi(page, 'DELETE', '/api/v1/ontology/1/relations/4', createVersionOnly(), {
      capture: true,
    });
    await page.getByTestId('relation-delete-trigger').click();
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();
    await capture.waitForRequest();

    // 삭제 성공 → 선택이 비워지고 인스펙터가 빈 안내 문구로 돌아간다 + 아웃라인에서도 사라진다.
    await expect(page.getByText('왼쪽에서 타입 또는 관계를 선택하세요.')).toBeVisible();
    await expect(page.getByTestId('outline-relation-4')).toHaveCount(0);
  });

  // I-1(S2 최종 리뷰) — 엔티티 타입 삭제(#328류)와 같은 계열의 결함: 삭제 트리거가 onDeleted로
  // 인스펙터와 함께 언마운트되는데, 포커스를 옮기는 코드가 아예 없어 <body>로 떨어졌다.
  // 응답을 일부러 지연시킨다 — toBeFocused()의 자동 재시도는 dialog.tsx류의 지연 자기 치유가
  // 끝날 때까지 기다렸다 통과해 버려 그 사이의 <body> 유실 구간 자체를 가릴 수 있다(엔티티 타입
  // 삭제 테스트의 선례 주석 참고). 그래서 여기서도 재시도 없는 단발 page.evaluate()로 확인한다.
  test('관계 삭제 성공 시 포커스가 "타입 추가" 버튼으로 복귀한다(지연 응답에서도 body 유실 없음)', async ({
    authenticatedPage: page,
  }) => {
    await openRelationInspector(page, 4); // HAS_EQUIPMENT(Building→Equipment)

    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/relations/4',
      async (route) => {
        if (route.request().method() !== 'DELETE') return route.fallback();
        await new Promise((resolve) => setTimeout(resolve, 300));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createVersionOnly()),
        });
      },
    );

    await page.getByTestId('relation-delete-trigger').click();
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();
    await expect(page.getByText('왼쪽에서 타입 또는 관계를 선택하세요.')).toBeVisible();
    const activeElement = await page.evaluate(() => ({
      tag: document.activeElement?.tagName,
      label: document.activeElement?.getAttribute('aria-label'),
    }));
    expect(activeElement).toEqual({ tag: 'BUTTON', label: '타입 추가' });
  });

  test('끝점을 바꾸려 하면 UI가 삭제 후 재생성으로 안내한다(수정 불가 고지)', async ({ authenticatedPage: page }) => {
    await openRelationInspector(page, 1); // OCCURRED_AT(Incident→Building)

    // 끝점은 입력창이 아니라 읽기 전용 텍스트로 보인다 — 편집 가능한 요소(input/select)가 아니다.
    await expect(page.getByTestId('relation-endpoint-subject')).toHaveText('Incident');
    await expect(page.getByTestId('relation-endpoint-object')).toHaveText('Building');
    await expect(page.getByRole('combobox', { name: '주어 타입' })).toHaveCount(0);
    await expect(page.getByRole('combobox', { name: '목적어 타입' })).toHaveCount(0);

    await expect(page.getByTestId('relation-endpoint-hint')).toContainText('삭제하고 새로 만드세요');
  });
});

/**
 * EntityInspector — 새 타입 만들기(S2 Task 6 백로그, Task 5 리뷰 M-8 이관).
 * 전체 문서 모달(OntologyEditDialog)이 유일한 엔티티 타입 생성 경로였는데, Task 6이 그 모달을
 * 지우면서 이 진입점이 새 편집기에 하나도 남지 않게 되는 것을 막는다 — ModelOutline의 "타입 추가"
 * 버튼(RelationInspector의 "관계 추가"와 대칭)이 새 진입점이다.
 */
test.describe('EntityInspector — 새 타입 만들기', () => {
  test('타입 추가 시 POST /entity-types 본문에 필드가 담겨 나간다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();

    await page.getByRole('button', { name: '타입 추가' }).click();
    await expect(page.getByTestId('entity-inspector-create')).toBeVisible();

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/ontology/1/entity-types',
      createEntityTypeMutation({ id: 7, type: 'Sensor', description: '화재 감지 센서', naming: '', resolution: 'embedding', properties: [] }),
      { capture: true },
    );

    await page.getByLabel('타입 이름').fill('Sensor');
    await page.getByLabel('설명').fill('화재 감지 센서');
    await page.getByRole('button', { name: '타입 만들기' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toEqual({ type: 'Sensor', description: '화재 감지 센서', naming: '', resolution: 'embedding' });

    // 생성 성공 → 새 타입이 선택 상태로 전환되어 편집 폼(자동 저장)이 보인다 + 아웃라인에도 나타난다.
    await expect(page.getByTestId('entity-inspector')).toBeVisible();
    await expect(page.getByLabel('타입 이름')).toHaveValue('Sensor');
    await expect(page.getByTestId('outline-entity-7')).toBeVisible();
  });

  test('이름 없이 타입 추가를 시도하면 API 호출 없이 로컬에서 막히고 aria-invalid가 붙는다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByRole('button', { name: '타입 추가' }).click();

    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types',
      (route) => {
        called = true;
        return route.fulfill({ status: 200, body: JSON.stringify(createEntityTypeMutation({ id: 7, type: '', description: '', naming: '', resolution: 'embedding', properties: [] })) });
      },
    );

    const nameInput = page.getByLabel('타입 이름');
    await page.getByRole('button', { name: '타입 만들기' }).click();

    await expect(page.getByText('타입 이름을 입력하세요.')).toBeVisible();
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    const errorId = await nameInput.getAttribute('aria-describedby');
    expect(errorId).toBeTruthy();
    await expect(page.locator(`#${errorId}`)).toHaveText('타입 이름을 입력하세요.');
    expect(called).toBe(false);
  });

  test('이미 존재하는 타입 이름으로 추가를 시도하면 로컬에서 막힌다', async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByRole('button', { name: '타입 추가' }).click();

    let called = false;
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types',
      (route) => {
        called = true;
        return route.fulfill({ status: 200, body: JSON.stringify(createEntityTypeMutation({ id: 7, type: 'Incident', description: '', naming: '', resolution: 'embedding', properties: [] })) });
      },
    );

    await page.getByLabel('타입 이름').fill('Incident');
    await page.getByRole('button', { name: '타입 만들기' }).click();

    await expect(page.getByText('이미 존재하는 타입입니다: Incident')).toBeVisible();
    expect(called).toBe(false);
  });

  test('엔티티 타입이 하나도 없어도 타입 추가 폼을 열고 만들 수 있다(빈 스키마 첫 타입)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    const emptySchema = createOntologySchema({ entities: [], relations: [] });
    await mockApi(page, 'GET', '/api/v1/ontology', emptySchema);
    await mockApi(page, 'GET', '/api/v1/ontology/1', emptySchema);
    await mockApi(page, 'GET', '/api/v1/ontology/graph', createOntologyGraph());
    await mockApi(page, 'GET', '/api/v1/ontologies', createOntologySummaries());
    await page.goto('/knowledge-graph/model');
    // 엔티티가 0개면 캔버스 대신 빈 상태가 뜨지만, 수정 모드 자체는 독립적으로 켤 수 있다.
    await page.getByRole('button', { name: '수정 모드' }).click();
    await expect(page.getByTestId('model-outline')).toContainText('타입 0개');

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/ontology/1/entity-types',
      createEntityTypeMutation({ id: 1, type: 'Incident', description: '', naming: '', resolution: 'embedding', properties: [] }),
      { capture: true },
    );
    await page.getByRole('button', { name: '타입 추가' }).click();
    await page.getByLabel('타입 이름').fill('Incident');
    // exact: true — 엔티티가 0개인 동안 캔버스 자리의 빈 상태(OntologyEmptyState, S3 Task 4)도 함께
    // 떠 있어 "타입 만들기"를 부분 일치로 찾으면 그 CTA("첫 타입 만들기")와 strict mode 충돌이 난다.
    await page.getByRole('button', { name: '타입 만들기', exact: true }).click();

    await capture.waitForRequest();
    await expect(page.getByTestId('outline-entity-1')).toBeVisible();
  });
});

// (#484) 지식 모델 3-pane 인스펙터의 자동저장(디바운스/PATCH in-flight) 이탈 가드 회귀 테스트.
// 다른 5개 편집기(파이프라인/리포트/프로액티브 잡/쿼리/차트, 이슈 #86)와 동일한
// useUnsavedChangesGuard + useDirtyAggregator 패턴을 지식 모델 편집기(ModelOutline 도메인명 +
// EntityInspector/RelationInspector 자동저장 필드)에도 연결했는지 검증한다 — 원인은 이 가드가
// 아예 배선돼 있지 않아 blur/디바운스가 아직 끝나지 않은 입력이 이탈 시 무경고로 유실되던 것이었다.
test.describe('지식 모델 편집기 — 자동저장 이탈 가드(#484)', () => {
  async function openInspector(page: import('@playwright/test').Page, entityId: number) {
    await setupAdminAuth(page);
    await setupOntologyMocks(page);
    await page.goto('/knowledge-graph/model');
    await page.getByRole('button', { name: '수정 모드' }).click();
    await page.getByTestId(`outline-entity-${entityId}`).click();
  }

  test('설명 필드를 blur/디바운스 전에 두고 사이드바로 이동하면 이탈 다이얼로그가 뜬다', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 2); // Building

    // PATCH가 나가도(디바운스가 먼저 발화해도) in-flight 응답을 아직 못 받은 채로 남겨둔다 — #484의
    // 핵심 회귀 지점: commit()이 committed를 응답 전에 낙관적으로 전진시키므로(useAutosaveText.ts),
    // draft!==committed만 보면 이 창에서 "clean"으로 오판된다. isPending으로 그 창까지 잡는지 검증한다.
    await page.route(
      (url) => url.pathname === '/api/v1/ontology/1/entity-types/2',
      async (route) => {
        if (route.request().method() !== 'PATCH') return route.fallback();
        await new Promise((resolve) => setTimeout(resolve, 5000)); // 테스트 시간 내내 응답하지 않는다.
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({}) });
      },
    );

    // fill()은 blur를 발생시키지 않는다 — onChange만 거쳐 draft가 committed와 달라져(isDirty) 곧바로
    // dirty 상태가 된다. 이후 디바운스(400ms)가 자연히 발화해도 위 route가 응답을 보류하므로 PATCH가
    // in-flight 상태로 남아, "blur 전/디바운스 대기 중"과 "PATCH in-flight" 두 창을 모두 검증한다.
    await page.getByLabel('설명', { exact: true }).fill('변경된 설명');

    // 사이드바 "그래프 탐색" 링크 클릭(SPA 이동) — useUnsavedChangesGuard의 document click capture가 가로챈다.
    await page.getByRole('navigation').getByRole('link', { name: '그래프 탐색' }).click();

    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?')).toBeVisible();
    // 가로챘으므로 URL은 그대로 지식 모델 화면에 머문다.
    expect(new URL(page.url()).pathname).toBe('/knowledge-graph/model');
  });

  test('이탈 다이얼로그에서 취소하면 페이지에 머무르고 입력값이 그대로 보존된다', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 2);

    await page.getByLabel('설명', { exact: true }).fill('변경된 설명');
    await page.getByRole('navigation').getByRole('link', { name: '그래프 탐색' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();

    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toBeHidden();

    expect(new URL(page.url()).pathname).toBe('/knowledge-graph/model');
    await expect(page.getByLabel('설명', { exact: true })).toHaveValue('변경된 설명');
  });

  test('이탈 다이얼로그에서 이탈을 확정하면 변경값을 버리고 다른 탭으로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 2);

    await page.getByLabel('설명', { exact: true }).fill('변경된 설명');
    await page.getByRole('navigation').getByRole('link', { name: '그래프 탐색' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();

    await page.getByRole('button', { name: '이탈' }).click();

    await expect(page).toHaveURL(/\/knowledge-graph\/explore$/);
  });

  test('저장 완료(clean) 상태에서는 사이드바 이동이 다이얼로그 없이 즉시 된다', async ({
    authenticatedPage: page,
  }) => {
    await openInspector(page, 2);

    // blur까지 마쳐 committed와 draft가 같아지면(clean) 이탈 가드가 걸리지 않아야 한다 — 이 훅이
    // 모든 이탈을 무조건 막는 게 아니라 dirty일 때만 개입한다는 것을 보장하는 대비군(contrast) 테스트.
    await mockApi(
      page,
      'PATCH',
      '/api/v1/ontology/1/entity-types/2',
      createEntityTypeMutation({ id: 2, type: 'Building', description: '변경된 설명', naming: '본문 표기 보존', resolution: 'embedding', properties: [] }),
    );
    await page.getByLabel('설명', { exact: true }).fill('변경된 설명');
    await page.getByLabel('설명', { exact: true }).blur();
    await page.waitForTimeout(50); // useAutosaveText commit()이 committed를 낙관적으로 전진시키는 시점까지 대기

    await page.getByRole('navigation').getByRole('link', { name: '그래프 탐색' }).click();

    await expect(page.getByRole('alertdialog')).toBeHidden();
    await expect(page).toHaveURL(/\/knowledge-graph\/explore$/);
  });
});
