// Neo4j 드라이버 싱글턴 + 세션 팩토리 + 제약 부트스트랩.
import { VerifiedOntologyId } from './verified-ontology-id.js';
import neo4j, { Driver, Session } from 'neo4j-driver';

let driver: Driver | null = null;

// env로 드라이버를 1회 생성해 재사용한다.
export function getDriver(): Driver {
  if (!driver) {
    const uri = process.env.NEO4J_URI ?? 'bolt://localhost:7687';
    const user = process.env.NEO4J_USER ?? 'neo4j';
    const password = process.env.NEO4J_PASSWORD ?? 'firehub-graph-dev';
    driver = neo4j.driver(uri, neo4j.auth.basic(user, password));
  }
  return driver;
}

export function getSession(): Session {
  return getDriver().session();
}

// Entity.key 유일성 제약 — MERGE 멱등성과 조회 성능의 기반.
// ontologyId 인덱스 — 모든 그래프 읽기가 이 속성으로 스코프되면서(readWholeGraph, retriever,
// structuredQuery) 필수가 됐다. 없으면 매 조회가 전 테넌트 :Entity 라벨 스캔이라, 노드 50개인
// 테넌트도 전체 노드 수에 비례한 비용을 낸다(스코프 도입 전에는 스캔이 곧 출력이라 무의미했다).
//
// 복합 (ontologyId, type) 인 이유: structuredQuery 가 `n.ontologyId = $x AND n.type = $t` 로 거는데,
// ontologyId 단일 인덱스면 그 온톨로지의 **전 노드**를 seek 한 뒤 타입을 메모리에서 거른다.
// 복합 인덱스는 접두(ontologyId 단독) 조회도 그대로 커버하므로 단일 인덱스를 대체한다 — 둘 다
// 둘 이유가 없다.
export async function bootstrapConstraints(): Promise<void> {
  const session = getSession();
  try {
    await session.run(
      'CREATE CONSTRAINT entity_key IF NOT EXISTS FOR (n:Entity) REQUIRE n.key IS UNIQUE',
    );
    await session.run(
      'CREATE INDEX entity_ontology_type IF NOT EXISTS FOR (n:Entity) ON (n.ontologyId, n.type)',
    );
  } finally {
    await session.close();
  }
}

export async function closeDriver(): Promise<void> {
  if (driver) {
    await driver.close();
    driver = null;
  }
}

// ── 온톨로지 시각화용 전체 그래프 읽기 (읽기 전용) ──
// 시각화 노드/엣지 형태. sourceChunkIds 배열 대신 개수만 노출(뷰에 충분·페이로드 축소).
// schemaVersion: 적재 당시 온톨로지 스키마 버전(5-4). 스탬프 도입 이전에 적재된 레거시 노드는
// 속성 자체가 없어 undefined — "값 없음"과 "구버전(0)"을 혼동하지 않도록 optional로 둔다.
export interface GraphNode {
  key: string; type: string; name: string; sourceChunkCount: number;
  schemaVersion?: number;
  // 이 노드가 실제로 적재된 온톨로지 id. 스탬프 도입(#678) 이전 레거시 노드는 없어 undefined.
  // schema_version은 온톨로지별 독립 카운터라, ontologyId 없이는 "구버전" 판정이 불가능하다.
  ontologyId?: number;
}
export interface GraphEdge { subjectKey: string; type: string; objectKey: string; }
export interface WholeGraph { nodes: GraphNode[]; edges: GraphEdge[]; }

// Neo4j 수치 속성 → JS number. INTEGER면 Integer 객체(.toNumber), FLOAT면 이미 JS number로 돌아온다.
// #308: 쓰기측이 plain number를 바인딩해 FLOAT(1.0)로 적재된 기존 데이터가 존재하므로, 쓰기측을 고쳐도
// 읽기측 방어가 없으면 과거 노드 하나 때문에 그래프 전체 조회가 TypeError로 실패한다.
function toJsNumber(v: unknown): number {
  return neo4j.isInt(v) ? v.toNumber() : Number(v);
}

// 한 온톨로지의 지식그래프를 1회 읽어 노드/엣지로 반환한다.
// 고립 노드(관계 없는 Entity)도 포함되도록 노드·엣지를 별도 쿼리로 읽는다.
//
// ontologyId 는 필수다. Neo4j 는 전 테넌트가 공유하는 단일 DB 라 RLS 가 없고 노드에 tenantId 도 없다 —
// 스코프 축은 loader 가 스탬프하는 ontologyId 뿐이고, 그 온톨로지가 요청자 테넌트의 것인지는 호출부인
// firehub-api 가 RLS 걸린 ontology 테이블로 검증한다(근거는 OntologyService.getGraph 참고).
// 따라서 이 술어를 빼면 그 검증이 통째로 무의미해진다.
export async function readWholeGraph(ontologyId: VerifiedOntologyId): Promise<WholeGraph> {
  const session = getSession();
  // 적재측이 neo4j.int() 로 INTEGER 를 썼으므로(#308 의 교훈) 조회측도 INTEGER 로 바인딩한다.
  const params = { ontologyId: neo4j.int(ontologyId) };
  try {
    const nodeRes = await session.run(
      'MATCH (n:Entity) WHERE n.ontologyId = $ontologyId ' +
      'RETURN n.key AS key, n.type AS type, n.name AS name, ' +
      'size(coalesce(n.sourceChunkIds, [])) AS sourceChunkCount, n.schemaVersion AS schemaVersion, ' +
      'n.ontologyId AS ontologyId',
      params,
    );
    // 엣지는 양끝이 모두 스코프 안일 때만 — 한쪽만 걸면 남의 노드 key 가 엣지를 타고 노출된다.
    // (엣지 자신의 ontologyId 가 아니라 끝점 기준으로 판정한다: 레거시 엣지엔 그 속성이 없을 수 있고,
    //  프론트가 쓰던 filterGraphByNode 와 같은 규칙이라 화면 의미도 바뀌지 않는다.)
    const edgeRes = await session.run(
      'MATCH (a:Entity)-[r:REL]->(b:Entity) ' +
      'WHERE a.ontologyId = $ontologyId AND b.ontologyId = $ontologyId ' +
      'RETURN a.key AS subjectKey, r.type AS type, b.key AS objectKey',
      params,
    );
    const nodes: GraphNode[] = nodeRes.records.map((r) => {
      const schemaVersion = r.get('schemaVersion');
      const ontologyId = r.get('ontologyId');
      return {
        key: r.get('key'), type: r.get('type'), name: r.get('name'),
        sourceChunkCount: r.get('sourceChunkCount').toNumber(), // neo4j Integer → JS number
        // 레거시 노드는 속성이 없어 schemaVersion이 null → undefined로 정규화(0/구버전과 구분).
        ...(schemaVersion != null ? { schemaVersion: toJsNumber(schemaVersion) } : {}),
        ...(ontologyId != null ? { ontologyId: toJsNumber(ontologyId) } : {}),
      };
    });
    const edges: GraphEdge[] = edgeRes.records.map((r) => ({
      subjectKey: r.get('subjectKey'), type: r.get('type'), objectKey: r.get('objectKey'),
    }));
    return { nodes, edges };
  } finally {
    await session.close();
  }
}
