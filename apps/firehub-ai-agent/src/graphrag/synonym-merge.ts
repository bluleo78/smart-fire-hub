// HITL 승인된 근접쌍을 Neo4j에서 병합한다 — 이미 별도 노드로 적재된 두 엔티티의 관계를 재배선하고
// 중복 노드를 삭제한다(semantic-resolver.ts의 union-find는 노드 생성 "전" 메모리 병합이라 이 용도로 재사용 불가).
import { getSession } from './neo4j-client.js';
import { EntityType, Ontology, entityTypeId } from './ontology.js';
import { entityKey } from './resolver.js';
import { pickCanonicalName } from './semantic-resolver.js';
import { GraphTargetMissingError, affectedCount } from './graph-mutation-guard.js';

interface EntityRow { key: string; sourceChunkIds: number[]; }
interface RelRow { type: string; otherKey: string; sourceChunkIds: number[]; }

// 중복 없는 배열 합집합.
function unionDedupe(a: number[], b: number[]): number[] {
  return [...new Set([...a, ...b])];
}

/**
 * entityType/nameA/nameB로 식별되는 두 엔티티 노드를 하나로 병합한다.
 * entityKey는 5-6부터 typeId 기반("<entity_type_id>:<정규화이름>")이라, entityType 문자열을
 * ontology로 typeId로 변환한 뒤에만 키를 계산할 수 있다(엔티티 타입 리네임에도 key가 안정적).
 * keeper(존치)/loser(삭제) 판정: semantic-resolver.ts의 pickCanonicalName과 동일한 규칙(긴 이름 우선,
 * 동률이면 localeCompare)을 사용한다 — 이 두 규칙이 어긋나면, 승인되어 삭제된 노드가 다음 적재 시
 * pickCanonicalName에 의해 canonical로 재선정되어 Neo4j에 다시 생성되는 버그가 발생한다(승인 병합이
 * 조용히 원복됨). 따라서 approval-time keeper는 반드시 향후 ingest가 수렴할 이름과 일치해야 한다.
 * 둘 중 하나라도 그래프에 아직 없으면(적재 전) 병합할 대상이 없다 — 예전에는 조용히 no-op했고 호출측이
 * 이를 성공으로 보고 검수 항목을 approved로 바꿔 병합 결정이 유실됐다(#310). 이제 실패로 전파한다.
 */
export async function mergeEntities(
  ontology: Ontology, entityType: EntityType, nameA: string, nameB: string,
): Promise<void> {
  const typeId = entityTypeId(ontology, entityType);
  const keyA = entityKey(typeId, nameA);
  const keyB = entityKey(typeId, nameB);

  const session = getSession();
  try {
    if (keyA === keyB) {
      // 이미 같은 키(정규화 후 동일 이름) — 재배선/삭제할 것은 없다. 다만 "병합 불필요"와 "병합 대상이
      // 아예 없음"은 다르다(#316): 그 노드조차 그래프에 없으면 예전에는 조용히 성공으로 보고돼 검수 항목이
      // approved로 바뀌고 병합 결정이 유실됐다. 존재를 확인한 뒤에만 no-op 성공으로 처리한다.
      const sameKeyRes = await session.run(
        'MATCH (n:Entity {key: $key}) RETURN count(n) AS matched', { key: keyA },
      );
      if (affectedCount(sameKeyRes, 'matched') === 0) {
        throw new GraphTargetMissingError(
          `병합할 엔티티가 그래프에 없어 동의어를 병합할 수 없습니다(${nameA}).`,
        );
      }
      return;
    }

    const nodeRes = await session.run(
      'MATCH (n:Entity) WHERE n.key IN [$keyA, $keyB] RETURN n.key AS key, coalesce(n.sourceChunkIds, []) AS sourceChunkIds',
      { keyA, keyB },
    );
    const nodes = new Map<string, EntityRow>();
    for (const r of nodeRes.records) nodes.set(r.get('key'), { key: r.get('key'), sourceChunkIds: r.get('sourceChunkIds') });
    const a = nodes.get(keyA);
    const b = nodes.get(keyB);
    if (!a || !b) {
      // 둘 중 하나가 그래프에 없음 — 병합할 수 없으므로 승인을 실패시켜 항목을 pending으로 남긴다.
      const missing = !a ? nameA : nameB;
      throw new GraphTargetMissingError(
        `병합할 엔티티가 그래프에 없어 동의어를 병합할 수 없습니다(${missing}).`,
      );
    }

    const canonicalName = pickCanonicalName([nameA, nameB]);
    const keeperKey = entityKey(typeId, canonicalName);
    const [keeper, loser] = keeperKey === keyA ? [a, b] : [b, a];

    await reconnectRelations(session, loser.key, keeper.key, 'out');
    await reconnectRelations(session, loser.key, keeper.key, 'in');

    await session.run(
      'MATCH (k:Entity {key: $keeperKey}) SET k.sourceChunkIds = $merged',
      { keeperKey: keeper.key, merged: unionDedupe(keeper.sourceChunkIds, loser.sourceChunkIds) },
    );
    await session.run('MATCH (l:Entity {key: $loserKey}) DETACH DELETE l', { loserKey: loser.key });
  } finally {
    await session.close();
  }
}

// loser의 관계(direction 방향)를 조회해 keeper로 재배선한다. 자기참조(상대가 keeper 자신)는 제외한다.
async function reconnectRelations(
  session: ReturnType<typeof getSession>, loserKey: string, keeperKey: string, direction: 'out' | 'in',
): Promise<void> {
  const query = direction === 'out'
    ? 'MATCH (l:Entity {key: $loserKey})-[r:REL]->(o:Entity) WHERE o.key <> $keeperKey '
      + 'RETURN r.type AS type, o.key AS otherKey, coalesce(r.sourceChunkIds, []) AS sourceChunkIds'
    : 'MATCH (o:Entity)-[r:REL]->(l:Entity {key: $loserKey}) WHERE o.key <> $keeperKey '
      + 'RETURN r.type AS type, o.key AS otherKey, coalesce(r.sourceChunkIds, []) AS sourceChunkIds';
  const res = await session.run(query, { loserKey, keeperKey });
  // Cypher의 WHERE o.key <> $keeperKey로 자기참조는 이미 걸러지지만, 방어적으로 한 번 더 제외한다.
  const rels: RelRow[] = res.records
    .map((r) => ({ type: r.get('type'), otherKey: r.get('otherKey'), sourceChunkIds: r.get('sourceChunkIds') }))
    .filter((r: RelRow) => r.otherKey !== keeperKey);

  for (const r of rels) {
    const mergeQuery = direction === 'out'
      ? 'MATCH (k:Entity {key: $keeperKey}), (o:Entity {key: $otherKey}) '
        + 'MERGE (k)-[x:REL {type: $type}]->(o) '
        + 'SET x.sourceChunkIds = coalesce(x.sourceChunkIds, []) + [c IN $sourceChunkIds WHERE NOT c IN coalesce(x.sourceChunkIds, [])]'
      : 'MATCH (o:Entity {key: $otherKey}), (k:Entity {key: $keeperKey}) '
        + 'MERGE (o)-[x:REL {type: $type}]->(k) '
        + 'SET x.sourceChunkIds = coalesce(x.sourceChunkIds, []) + [c IN $sourceChunkIds WHERE NOT c IN coalesce(x.sourceChunkIds, [])]';
    await session.run(mergeQuery, { keeperKey, otherKey: r.otherKey, type: r.type, sourceChunkIds: r.sourceChunkIds });
  }
}
