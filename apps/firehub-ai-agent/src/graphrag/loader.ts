// ResolvedGraph를 Neo4j에 MERGE로 멱등 적재한다.
// 모델: (:Entity {key,type,name,sourceChunkIds})-[:REL {type,sourceChunkIds}]->(:Entity)
import { getSession } from './neo4j-client.js';
import { ResolvedGraph } from './resolver.js';

export async function loadGraph(
  graph: ResolvedGraph, sourceChunkId: number,
): Promise<{ nodes: number; relations: number }> {
  const session = getSession();
  try {
    // 노드 MERGE — key 기준. sourceChunkIds에 chunkId를 중복 없이 누적(멱등).
    await session.run(
      `UNWIND $entities AS e
       MERGE (n:Entity {key: e.key})
       SET n.type = e.type, n.name = e.name
       SET n.sourceChunkIds =
         CASE WHEN $chunkId IN coalesce(n.sourceChunkIds, [])
              THEN n.sourceChunkIds ELSE coalesce(n.sourceChunkIds, []) + $chunkId END`,
      { entities: graph.entities, chunkId: sourceChunkId },
    );
    // 관계 MERGE — (subjectKey)-[:REL {type}]->(objectKey). sourceChunkIds 동일 누적.
    await session.run(
      `UNWIND $rels AS r
       MATCH (a:Entity {key: r.subjectKey}), (b:Entity {key: r.objectKey})
       MERGE (a)-[x:REL {type: r.type}]->(b)
       SET x.sourceChunkIds =
         CASE WHEN $chunkId IN coalesce(x.sourceChunkIds, [])
              THEN x.sourceChunkIds ELSE coalesce(x.sourceChunkIds, []) + $chunkId END`,
      { rels: graph.relations, chunkId: sourceChunkId },
    );
    return { nodes: graph.entities.length, relations: graph.relations.length };
  } finally { await session.close(); }
}
