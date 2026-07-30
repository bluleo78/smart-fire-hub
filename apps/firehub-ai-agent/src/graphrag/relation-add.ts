// HITL 승인된 저신뢰 관계를 Neo4j에 적재한다(관계 추출 검수 승인).
// ingest 시 보류됐던 엣지를, 양 끝점 노드가 존재할 때만 MERGE한다.
// 끝점이 없으면 MATCH가 0행이 되어 MERGE가 통째로 no-op이 되는데(에러 없음), 예전에는 이를 성공으로 보고해
// 검수 항목만 approved로 바뀌고 엣지는 없는 무음 유실이 발생했다(#310 — 저신뢰 엔티티를 먼저 [거부]한 뒤
// 그 엔티티를 참조하는 관계를 [적재]하면 재현). 이제 반영 건수를 확인해 0이면 실패로 전파한다.
// loader.ts / entity-add.ts와 동일한 sourceChunkIds 누적 관용구를 재사용한다.
import neo4j from 'neo4j-driver';
import { getSession } from './neo4j-client.js';
import { RelationType } from './ontology.js';
import { GraphTargetMissingError, affectedCount } from './graph-mutation-guard.js';

export async function addRelation(
  schemaVersion: number, subjectKey: string, relType: RelationType, objectKey: string, sourceChunkIds: number[],
): Promise<void> {
  const session = getSession();
  try {
    const result = await session.run(
      `MATCH (a:Entity {key: $subjectKey}), (b:Entity {key: $objectKey})
       MERGE (a)-[x:REL {type: $relType}]->(b)
       SET x.schemaVersion = $schemaVersion
       SET x.sourceChunkIds = coalesce(x.sourceChunkIds, []) + [c IN $sourceChunkIds WHERE NOT c IN coalesce(x.sourceChunkIds, [])]
       RETURN count(x) AS merged`,
      // schemaVersion은 INTEGER로 바인딩한다 — plain number는 FLOAT로 저장돼 읽기측이 깨진다(#308).
      { subjectKey, objectKey, relType, schemaVersion: neo4j.int(schemaVersion), sourceChunkIds },
    );
    // 판정 기준은 "MATCH가 양 끝점을 바인딩했는가"(=MERGE가 실행됐는가)이지 "엣지가 새로 생겼는가"가 아니다.
    // 이미 같은 엣지가 있으면 relationshipsCreated는 0이지만 원하는 상태는 충족된 것이므로 성공이어야 한다.
    if (affectedCount(result, 'merged') === 0) {
      throw new GraphTargetMissingError(
        `주어/목적어 엔티티가 그래프에 없어 관계를 적재할 수 없습니다(subject=${subjectKey}, object=${objectKey}).`,
      );
    }
  } finally {
    await session.close();
  }
}
