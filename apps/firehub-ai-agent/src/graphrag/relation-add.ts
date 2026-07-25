// HITL 승인된 저신뢰 관계를 Neo4j에 적재한다(관계 추출 검수 승인).
// ingest 시 보류됐던 엣지를, 양 끝점 노드가 존재할 때만 MERGE한다(끝점 우선 결정상 항상 존재하나 no-op 가드 유지).
// loader.ts / entity-add.ts와 동일한 sourceChunkIds 누적 관용구를 재사용한다.
import { getSession } from './neo4j-client.js';
import { RelationType } from './ontology.js';

export async function addRelation(
  schemaVersion: number, subjectKey: string, relType: RelationType, objectKey: string, sourceChunkIds: number[],
): Promise<void> {
  const session = getSession();
  try {
    await session.run(
      `MATCH (a:Entity {key: $subjectKey}), (b:Entity {key: $objectKey})
       MERGE (a)-[x:REL {type: $relType}]->(b)
       SET x.schemaVersion = $schemaVersion
       SET x.sourceChunkIds = coalesce(x.sourceChunkIds, []) + [c IN $sourceChunkIds WHERE NOT c IN coalesce(x.sourceChunkIds, [])]`,
      { subjectKey, objectKey, relType, schemaVersion, sourceChunkIds },
    );
  } finally {
    await session.close();
  }
}
