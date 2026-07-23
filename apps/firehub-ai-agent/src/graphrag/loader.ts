// ResolvedGraph를 Neo4j에 MERGE로 멱등 적재한다.
// 모델: (:Entity {key,type,name,sourceChunkIds,schemaVersion})-[:REL {type,sourceChunkIds,schemaVersion}]->(:Entity)
import { getSession } from './neo4j-client.js';
import { ResolvedGraph } from './resolver.js';

// 노드 정체성 필드(위 모델 참조) — 속성명이 이 중 하나면 `SET n += props`가 정체성 필드를 덮어써
// 노드가 깨진다. api(OntologyService.RESERVED_PROPERTY_NAMES)가 편집 시점에 막지만, 과거 데이터·
// 직접 DB조작 등 우회 경로에 대비해 적재 직전에도 한 번 더 방어한다(defense-in-depth).
// schemaVersion(5-4)도 노드 정체성 필드에 포함 — api/web의 동일 상수와 함께 갱신해야 한다.
const RESERVED_NODE_KEYS = new Set(['key', 'type', 'name', 'sourceChunkIds', 'schemaVersion']);

function sanitizeProperties(
  properties: Record<string, number | string> | undefined,
): Record<string, number | string> {
  if (!properties) return {};
  return Object.fromEntries(
    Object.entries(properties).filter(([k]) => !RESERVED_NODE_KEYS.has(k)),
  );
}

export async function loadGraph(
  graph: ResolvedGraph, sourceChunkId: number, schemaVersion: number,
): Promise<{ nodes: number; relations: number }> {
  const session = getSession();
  try {
    // 노드 MERGE — key 기준. sourceChunkIds 누적 + 정규화 속성 병합(예약키는 sanitizeProperties로 제거 후).
    // 속성은 SET n += e.properties(last-write-wins). Incident 는 문서당 1개(exact)라 동일값 전제하 허용.
    // schemaVersion도 n.type/n.name과 동일하게 last-write-wins — 같은 엔티티 key를 여러 데이터셋이
    // 공유할 때 나중 적재가 더 낮은 버전으로 덮어쓸 수 있으나(기존 type/name도 이미 같은 특성),
    // 아직 이 값을 소비하는 재적재/drift 로직이 없어 방어 로직은 추가하지 않는다(YAGNI, 슬라이스 5-4 설계 참조).
    const entities = graph.entities.map((e) => ({ ...e, properties: sanitizeProperties(e.properties) }));
    await session.run(
      `UNWIND $entities AS e
       MERGE (n:Entity {key: e.key})
       SET n.type = e.type, n.name = e.name, n.schemaVersion = $schemaVersion
       SET n += coalesce(e.properties, {})
       SET n.sourceChunkIds =
         CASE WHEN $chunkId IN coalesce(n.sourceChunkIds, [])
              THEN n.sourceChunkIds ELSE coalesce(n.sourceChunkIds, []) + $chunkId END`,
      { entities, chunkId: sourceChunkId, schemaVersion },
    );
    // 관계 MERGE — (subjectKey)-[:REL {type}]->(objectKey). sourceChunkIds 동일 누적.
    // schemaVersion은 sourceChunkIds와 같은 전례로 저장만 하고 읽기 API(GraphEdge)에는 노출하지 않는다
    // (소비자 생기면 노출 — 노드측 소비자(NodeDetailDrawer)만 우선 구현).
    await session.run(
      `UNWIND $rels AS r
       MATCH (a:Entity {key: r.subjectKey}), (b:Entity {key: r.objectKey})
       MERGE (a)-[x:REL {type: r.type}]->(b)
       SET x.schemaVersion = $schemaVersion
       SET x.sourceChunkIds =
         CASE WHEN $chunkId IN coalesce(x.sourceChunkIds, [])
              THEN x.sourceChunkIds ELSE coalesce(x.sourceChunkIds, []) + $chunkId END`,
      { rels: graph.relations, chunkId: sourceChunkId, schemaVersion },
    );
    return { nodes: graph.entities.length, relations: graph.relations.length };
  } finally { await session.close(); }
}
