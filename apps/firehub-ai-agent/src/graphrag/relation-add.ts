// HITL 승인된 저신뢰 관계를 Neo4j에 적재한다(관계 추출 검수 승인).
// ingest 시 보류됐던 엣지를, 양 끝점 노드가 존재할 때만 MERGE한다.
// 끝점이 없으면 MATCH가 0행이 되어 MERGE가 통째로 no-op이 되는데(에러 없음), 예전에는 이를 성공으로 보고해
// 검수 항목만 approved로 바뀌고 엣지는 없는 무음 유실이 발생했다(#310 — 저신뢰 엔티티를 먼저 [거부]한 뒤
// 그 엔티티를 참조하는 관계를 [적재]하면 재현). 이제 반영 건수를 확인해 0이면 실패로 전파한다.
// loader.ts / entity-add.ts와 동일한 sourceChunkIds 누적 관용구를 재사용한다.
import neo4j from 'neo4j-driver';
import { getSession } from './neo4j-client.js';
import { Ontology, RelationType, isAllowedTriple, isRelationType } from './ontology.js';
import { GraphTargetMissingError, OntologyConformanceError, affectedCount } from './graph-mutation-guard.js';

/**
 * 승인된 관계를 적재한다. schemaVersion만이 아니라 ontology 전체를 받는 이유는 스키마 준수(conformance)
 * 검증 때문이다(#319) — 예전에는 relType이 온톨로지 대조 없이 그대로 Cypher에 바인딩돼, 스키마에 없는
 * 관계 타입의 엣지가 실제로 생성됐다(add-entity는 entityTypeId()가 우연히 막아주지만 여기엔 그런 후속 조회가 없다).
 */
export async function addRelation(
  ontology: Ontology, subjectKey: string, relType: RelationType, objectKey: string, sourceChunkIds: number[],
): Promise<void> {
  // 1차: 관계 타입 자체가 온톨로지에 존재하는가. 그래프를 건드리기 전에 거른다.
  if (!isRelationType(ontology, relType)) {
    throw new OntologyConformanceError(
      `온톨로지에 정의되지 않은 관계 타입이라 관계를 적재할 수 없습니다(${relType}).`,
    );
  }
  const session = getSession();
  try {
    // 2차: 양 끝점의 실제 엔티티 타입으로 (주어타입, 관계, 목적어타입) 트리플까지 검증한다.
    // 끝점 조회를 겸하므로, 여기서 0행이면 대상 부재(GraphTargetMissingError)로 곧장 갈린다.
    const endpoints = await session.run(
      `MATCH (a:Entity {key: $subjectKey}), (b:Entity {key: $objectKey})
       RETURN a.type AS subjectType, b.type AS objectType`,
      { subjectKey, objectKey },
    );
    const endpoint = endpoints.records[0];
    if (!endpoint) {
      throw new GraphTargetMissingError(
        `주어/목적어 엔티티가 그래프에 없어 관계를 적재할 수 없습니다(subject=${subjectKey}, object=${objectKey}).`,
      );
    }
    const subjectType = endpoint.get('subjectType') as string | null;
    const objectType = endpoint.get('objectType') as string | null;
    // 타입 스탬프가 없는 노드(구버전 적재 등)는 트리플 대조 자체가 불가능하므로 1차 검증만으로 통과시킨다 —
    // 여기서 막으면 정상 승인이 진단 불가능한 사유로 거부된다.
    if (subjectType && objectType && !isAllowedTriple(ontology, subjectType, relType, objectType)) {
      throw new OntologyConformanceError(
        `온톨로지가 허용하지 않는 관계라 적재할 수 없습니다(${subjectType} -${relType}-> ${objectType}).`,
      );
    }

    const schemaVersion = ontology.schemaVersion;
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
