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

// 엔티티 타입 리네임(5-5) — key가 "<type>:<정규화이름>" 형태로 old-type을 리터럴로 포함하므로
// (resolver.ts entityKey), 타입명 변경 시 key 접두어와 type 속성을 함께 재작성해야 재적재 없이도
// 일관성이 유지된다. size($oldType)+1로 고정 길이 접두어만 잘라내 치환(전역 replace 대신) —
// 이름 부분에 우연히 "oldType:" 문자열이 포함돼도 오치환되지 않는다.
// key 충돌(신규 이름의 노드가 이미 존재) 시 UNIQUE 제약 위반으로 예외가 던져진다 — 호출부(라우트)가
// 502로 매핑하고, api는 best-effort로 처리(실패해도 DB 리네임은 이미 커밋됨). 이 경우 해당 데이터셋은
// schema_version 불일치로 stale 판정되어 재적재(graphrag_ingest)가 새 타입명 기준 조회는 복구하지만,
// 이 마이그레이션이 실패하면 old-type 노드는 재적재로 자동 삭제되지 않고 고아로 남는다(수동 정리 필요).
export async function renameEntityType(oldType: string, newType: string): Promise<number> {
  const session = getSession();
  try {
    const result = await session.run(
      `MATCH (n:Entity {type: $oldType})
       SET n.key = $newType + ':' + substring(n.key, size($oldType) + 1), n.type = $newType
       RETURN count(n) AS migrated`,
      { oldType, newType },
    );
    return result.records[0].get('migrated').toNumber();
  } finally {
    await session.close();
  }
}

// ── 온톨로지 시각화용 전체 그래프 읽기 (읽기 전용) ──
// 시각화 노드/엣지 형태. sourceChunkIds 배열 대신 개수만 노출(뷰에 충분·페이로드 축소).
// schemaVersion: 적재 당시 온톨로지 스키마 버전(5-4). 스탬프 도입 이전에 적재된 레거시 노드는
// 속성 자체가 없어 undefined — "값 없음"과 "구버전(0)"을 혼동하지 않도록 optional로 둔다.
export interface GraphNode { key: string; type: string; name: string; sourceChunkCount: number; schemaVersion?: number; }
export interface GraphEdge { subjectKey: string; type: string; objectKey: string; }
export interface WholeGraph { nodes: GraphNode[]; edges: GraphEdge[]; }

// 전체 지식그래프를 1회 읽어 노드/엣지로 반환한다.
// 고립 노드(관계 없는 Entity)도 포함되도록 노드·엣지를 별도 쿼리로 읽는다.
export async function readWholeGraph(): Promise<WholeGraph> {
  const session = getSession();
  try {
    const nodeRes = await session.run(
      'MATCH (n:Entity) RETURN n.key AS key, n.type AS type, n.name AS name, ' +
      'size(coalesce(n.sourceChunkIds, [])) AS sourceChunkCount, n.schemaVersion AS schemaVersion',
    );
    const edgeRes = await session.run(
      'MATCH (a:Entity)-[r:REL]->(b:Entity) RETURN a.key AS subjectKey, r.type AS type, b.key AS objectKey',
    );
    const nodes: GraphNode[] = nodeRes.records.map((r) => {
      const schemaVersion = r.get('schemaVersion');
      return {
        key: r.get('key'), type: r.get('type'), name: r.get('name'),
        sourceChunkCount: r.get('sourceChunkCount').toNumber(), // neo4j Integer → JS number
        // 레거시 노드는 속성이 없어 schemaVersion이 null → undefined로 정규화(0/구버전과 구분).
        ...(schemaVersion != null ? { schemaVersion: schemaVersion.toNumber() } : {}),
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
