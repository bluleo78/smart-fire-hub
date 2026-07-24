// HITL 승인된 저신뢰 엔티티를 Neo4j에 적재한다(엔티티 추출 검수 승인).
// ingest 시 보류돼 그래프에 없던 엔티티를 as-extracted 타입/이름으로 노드 MERGE하고,
// 함께 보류됐던 관계는 "상대 끝점이 이미 존재할 때만" MERGE한다(양쪽 보류 관계는 마지막 승인 시 생성).
// loader.ts와 동일한 예약키 방어·sourceChunkIds 누적 관용구를 재사용한다.
import { getSession } from './neo4j-client.js';
import { EntityType, Ontology, RelationType, entityTypeId } from './ontology.js';
import { entityKey } from './resolver.js';

const RESERVED_NODE_KEYS = new Set(['key', 'type', 'name', 'sourceChunkIds', 'schemaVersion']);

function sanitizeProperties(properties?: Record<string, number | string>): Record<string, number | string> {
  if (!properties) return {};
  return Object.fromEntries(Object.entries(properties).filter(([k]) => !RESERVED_NODE_KEYS.has(k)));
}

export interface AddEntityInput {
  entityType: EntityType;
  name: string;
  properties?: Record<string, number | string>;
  sourceChunkIds: number[];
  relations: { relType: RelationType; direction: 'out' | 'in'; otherKey: string }[];
}

export async function addEntity(ontology: Ontology, input: AddEntityInput): Promise<void> {
  const key = entityKey(entityTypeId(ontology, input.entityType), input.name);
  const props = sanitizeProperties(input.properties);
  const schemaVersion = ontology.schemaVersion;
  const session = getSession();
  try {
    // 노드 MERGE(loader.ts 관용구) — 예약키 제거 속성 병합 + sourceChunkIds 누적(dedup).
    await session.run(
      `MERGE (n:Entity {key: $key})
       SET n.type = $type, n.name = $name, n.schemaVersion = $schemaVersion
       SET n += $props
       SET n.sourceChunkIds = coalesce(n.sourceChunkIds, []) + [c IN $sourceChunkIds WHERE NOT c IN coalesce(n.sourceChunkIds, [])]`,
      { key, type: input.entityType, name: input.name, schemaVersion, props, sourceChunkIds: input.sourceChunkIds },
    );
    // 보류 관계 — 상대 끝점 존재 시에만 MERGE(MATCH 미스면 자연 no-op). 양쪽 보류는 마지막 승인 때 생성.
    for (const r of input.relations) {
      const query = r.direction === 'out'
        ? `MATCH (a:Entity {key: $key}), (b:Entity {key: $otherKey})
           MERGE (a)-[x:REL {type: $relType}]->(b)
           SET x.schemaVersion = $schemaVersion
           SET x.sourceChunkIds = coalesce(x.sourceChunkIds, []) + [c IN $sourceChunkIds WHERE NOT c IN coalesce(x.sourceChunkIds, [])]`
        : `MATCH (b:Entity {key: $otherKey}), (a:Entity {key: $key})
           MERGE (b)-[x:REL {type: $relType}]->(a)
           SET x.schemaVersion = $schemaVersion
           SET x.sourceChunkIds = coalesce(x.sourceChunkIds, []) + [c IN $sourceChunkIds WHERE NOT c IN coalesce(x.sourceChunkIds, [])]`;
      await session.run(query, { key, otherKey: r.otherKey, relType: r.relType, schemaVersion, sourceChunkIds: input.sourceChunkIds });
    }
  } finally {
    await session.close();
  }
}
