// Neo4j 드라이버 싱글턴 + 세션 팩토리 + 제약 부트스트랩.
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
export async function bootstrapConstraints(): Promise<void> {
  const session = getSession();
  try {
    await session.run(
      'CREATE CONSTRAINT entity_key IF NOT EXISTS FOR (n:Entity) REQUIRE n.key IS UNIQUE',
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
export interface GraphNode { key: string; type: string; name: string; sourceChunkCount: number; }
export interface GraphEdge { subjectKey: string; type: string; objectKey: string; }
export interface WholeGraph { nodes: GraphNode[]; edges: GraphEdge[]; }

// 전체 지식그래프를 1회 읽어 노드/엣지로 반환한다.
// 고립 노드(관계 없는 Entity)도 포함되도록 노드·엣지를 별도 쿼리로 읽는다.
export async function readWholeGraph(): Promise<WholeGraph> {
  const session = getSession();
  try {
    const nodeRes = await session.run(
      'MATCH (n:Entity) RETURN n.key AS key, n.type AS type, n.name AS name, ' +
      'size(coalesce(n.sourceChunkIds, [])) AS sourceChunkCount',
    );
    const edgeRes = await session.run(
      'MATCH (a:Entity)-[r:REL]->(b:Entity) RETURN a.key AS subjectKey, r.type AS type, b.key AS objectKey',
    );
    const nodes: GraphNode[] = nodeRes.records.map((r) => ({
      key: r.get('key'), type: r.get('type'), name: r.get('name'),
      sourceChunkCount: r.get('sourceChunkCount').toNumber(), // neo4j Integer → JS number
    }));
    const edges: GraphEdge[] = edgeRes.records.map((r) => ({
      subjectKey: r.get('subjectKey'), type: r.get('type'), objectKey: r.get('objectKey'),
    }));
    return { nodes, edges };
  } finally {
    await session.close();
  }
}
