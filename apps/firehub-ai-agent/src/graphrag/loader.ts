// ResolvedGraph를 Neo4j에 MERGE로 멱등 적재한다.
// 모델: (:Entity {key,type,name,sourceChunkIds|sourceDatasetIds,schemaVersion})-[:REL {type,...}]->(:Entity)
// 문서 파이프라인(loadGraph)은 provenance=sourceChunkIds, 표 투영(loadTableGraph)은 sourceDatasetIds.
import { getSession } from './neo4j-client.js';
import { ResolvedGraph } from './resolver.js';

// 노드 정체성 필드 — 속성명이 이 중 하나면 `SET n += props`가 정체성 필드를 덮어써 노드가 깨진다.
// api(OntologyService.RESERVED_PROPERTY_NAMES)가 편집 시점에 막지만, 과거 데이터·직접 DB조작 등
// 우회 경로에 대비해 적재 직전에도 한 번 더 방어한다(defense-in-depth).
// 두 provenance 필드(sourceChunkIds/sourceDatasetIds) 모두 방어 대상에 포함한다.
const RESERVED_NODE_KEYS = new Set([
  'key', 'type', 'name', 'sourceChunkIds', 'sourceDatasetIds', 'schemaVersion',
]);

function sanitizeProperties(
  properties: Record<string, number | string> | undefined,
): Record<string, number | string> {
  if (!properties) return {};
  return Object.fromEntries(
    Object.entries(properties).filter(([k]) => !RESERVED_NODE_KEYS.has(k)),
  );
}

// 문서/표 공통 MERGE 로직 — provenance 필드명(provField)과 파라미터명(provParam)만 다르다.
// provField/provParam은 호출측(loadGraph/loadTableGraph)이 고정 리터럴로만 넘기는 코드 상수
// (사용자 입력 아님)이라 Cypher 문자열에 안전하게 보간한다.
async function mergeGraph(
  graph: ResolvedGraph,
  provField: 'sourceChunkIds' | 'sourceDatasetIds',
  provParam: 'chunkId' | 'datasetId',
  provValue: number,
  schemaVersion: number,
): Promise<{ nodes: number; relations: number }> {
  const session = getSession();
  try {
    // 노드 MERGE — key 기준. provenance(provField) 누적 + 정규화 속성 병합(예약키는 sanitizeProperties로 제거 후).
    // 속성은 SET n += e.properties(last-write-wins). Incident 는 문서당 1개(exact)라 동일값 전제하 허용.
    // schemaVersion도 n.type/n.name과 동일하게 last-write-wins — 같은 엔티티 key를 여러 데이터셋/문서가
    // 공유할 때 나중 적재가 더 낮은 버전으로 덮어쓸 수 있으나, 아직 이 값을 소비하는 재적재/drift 로직이
    // 없어 방어 로직은 추가하지 않는다(YAGNI, 슬라이스 5-4 설계 참조).
    const entities = graph.entities.map((e) => ({ ...e, properties: sanitizeProperties(e.properties) }));
    await session.run(
      `UNWIND $entities AS e
       MERGE (n:Entity {key: e.key})
       SET n.type = e.type, n.name = e.name, n.schemaVersion = $schemaVersion
       SET n += coalesce(e.properties, {})
       SET n.${provField} =
         CASE WHEN $${provParam} IN coalesce(n.${provField}, [])
              THEN n.${provField} ELSE coalesce(n.${provField}, []) + $${provParam} END`,
      { entities, [provParam]: provValue, schemaVersion },
    );
    // 관계 MERGE — (subjectKey)-[:REL {type}]->(objectKey). provenance(provField) 동일 누적.
    // schemaVersion은 저장만 하고 읽기 API(GraphEdge)에는 노출하지 않는다(소비자 생기면 노출 — 노드측
    // 소비자(NodeDetailDrawer)만 우선 구현).
    await session.run(
      `UNWIND $rels AS r
       MATCH (a:Entity {key: r.subjectKey}), (b:Entity {key: r.objectKey})
       MERGE (a)-[x:REL {type: r.type}]->(b)
       SET x.schemaVersion = $schemaVersion
       SET x.${provField} =
         CASE WHEN $${provParam} IN coalesce(x.${provField}, [])
              THEN x.${provField} ELSE coalesce(x.${provField}, []) + $${provParam} END`,
      { rels: graph.relations, [provParam]: provValue, schemaVersion },
    );
    return { nodes: graph.entities.length, relations: graph.relations.length };
  } finally { await session.close(); }
}

// 문서 청크 → 그래프 적재(provenance=sourceChunkIds). 방출 Cypher·파라미터는 리팩터 전과 동일.
export async function loadGraph(
  graph: ResolvedGraph, sourceChunkId: number, schemaVersion: number,
): Promise<{ nodes: number; relations: number }> {
  return mergeGraph(graph, 'sourceChunkIds', 'chunkId', sourceChunkId, schemaVersion);
}

// 표 행 → 그래프 결정적 투영(provenance=sourceDatasetIds). 문서 경로와 동일 exact-key MERGE.
export async function loadTableGraph(
  graph: ResolvedGraph, datasetId: number, schemaVersion: number,
): Promise<{ nodes: number; relations: number }> {
  return mergeGraph(graph, 'sourceDatasetIds', 'datasetId', datasetId, schemaVersion);
}
