// HITL 승인된 근접쌍을 Neo4j에서 병합한다 — 이미 별도 노드로 적재된 두 엔티티의 관계를 재배선하고
// 중복 노드를 삭제한다(semantic-resolver.ts의 union-find는 노드 생성 "전" 메모리 병합이라 이 용도로 재사용 불가).
import { type Integer } from 'neo4j-driver';
import { getSession } from './neo4j-client.js';
import { VerifiedOntologyId, ontologyIdParam } from './verified-ontology-id.js';
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
 *
 * 스코프 규약(근거는 verified-ontology-id.ts): 입구인 노드 조회에서 keyA/keyB 가 이 온톨로지 소속인지
 * 확인하고, 그 뒤 keeper/loser 는 그 결과에서만 나오므로 구성상 스코프 안이다 — entity-add 가 자기 노드에
 * 술어를 안 거는 것과 같은 이유다. 예외는 DETACH DELETE 한 곳: 되돌릴 수 없는 연산이라 술어를 한 번 더 건다.
 * 상대 끝점(o)은 호출자 통제 밖이라 반드시 술어가 필요하다.
 * 스코프 밖 키는 "없는 키"와 똑같이 GraphTargetMissingError 가 되어야 한다(갈리면 읽기 오라클이 된다).
 */
export async function mergeEntities(
  ontology: Ontology, ontologyId: VerifiedOntologyId, entityType: EntityType, nameA: string, nameB: string,
): Promise<void> {
  const typeId = entityTypeId(ontology, entityType);
  const keyA = entityKey(typeId, nameA);
  const keyB = entityKey(typeId, nameB);

  // 아래 모든 질의가 같은 값을 재사용한다(INTEGER 바인딩 근거는 ontologyIdParam 참고).
  const ontologyIdInt = ontologyIdParam(ontologyId);
  const session = getSession();
  try {
    if (keyA === keyB) {
      // 이미 같은 키(정규화 후 동일 이름) — 재배선/삭제할 것은 없다. 다만 "병합 불필요"와 "병합 대상이
      // 아예 없음"은 다르다(#316): 그 노드조차 그래프에 없으면 예전에는 조용히 성공으로 보고돼 검수 항목이
      // approved로 바뀌고 병합 결정이 유실됐다. 존재를 확인한 뒤에만 no-op 성공으로 처리한다.
      const sameKeyRes = await session.run(
        'MATCH (n:Entity {key: $key}) WHERE n.ontologyId = $ontologyId RETURN count(n) AS matched',
        { key: keyA, ontologyId: ontologyIdInt },
      );
      if (affectedCount(sameKeyRes, 'matched') === 0) {
        throw new GraphTargetMissingError(
          `병합할 엔티티가 그래프에 없어 동의어를 병합할 수 없습니다(${nameA}).`,
        );
      }
      return;
    }

    const nodeRes = await session.run(
      'MATCH (n:Entity) WHERE n.key IN [$keyA, $keyB] AND n.ontologyId = $ontologyId '
        + 'RETURN n.key AS key, coalesce(n.sourceChunkIds, []) AS sourceChunkIds',
      { keyA, keyB, ontologyId: ontologyIdInt },
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

    await reconnectRelations(session, ontologyIdInt, loser.key, keeper.key, 'out');
    await reconnectRelations(session, ontologyIdInt, loser.key, keeper.key, 'in');

    await session.run(
      'MATCH (k:Entity {key: $keeperKey}) SET k.sourceChunkIds = $merged',
      { keeperKey: keeper.key, merged: unionDedupe(keeper.sourceChunkIds, loser.sourceChunkIds) },
    );
    // 유일하게 술어를 중복으로 거는 곳 — 삭제는 되돌릴 수 없다(위 jsdoc 참고).
    await session.run(
      'MATCH (l:Entity {key: $loserKey}) WHERE l.ontologyId = $ontologyId DETACH DELETE l',
      { loserKey: loser.key, ontologyId: ontologyIdInt },
    );
  } finally {
    await session.close();
  }
}

// sourceChunkIds 누적 관용구 — loader.ts/entity-add.ts 와 같은 형태(중복 없이 합집합).
const ACCUMULATE_CHUNKS =
  'SET x.sourceChunkIds = coalesce(x.sourceChunkIds, []) + '
  + '[c IN $sourceChunkIds WHERE NOT c IN coalesce(x.sourceChunkIds, [])]';

// loser의 관계(direction 방향)를 조회해 keeper로 재배선한다. 자기참조(상대가 keeper 자신)는 제외한다.
//
// 방향별로 다른 것은 화살표뿐이라 패턴 조각만 갈라 쓴다 — 예전에는 질의 네 벌이 통째로 복제돼 있어,
// 한쪽만 고치면 반대 방향이 조용히 어긋났다.
//
// 상대 끝점(o)에만 ontologyId 술어를 건다: o 를 열어두면 남의 온톨로지 노드의 key 가 조회 결과로
// 흘러나오고(읽기 누수), 그 키로 keeper 에 엣지가 새로 그어진다(쓰기 누수). loser/keeper 는 호출부의
// 노드 조회가 이미 스코프로 걸러낸 값이다. ontologyId 는 이미 INTEGER 로 감싼 값을 받는다.
async function reconnectRelations(
  session: ReturnType<typeof getSession>, ontologyId: Integer,
  loserKey: string, keeperKey: string, direction: 'out' | 'in',
): Promise<void> {
  const loserPattern = direction === 'out'
    ? '(l:Entity {key: $loserKey})-[r:REL]->(o:Entity)'
    : '(o:Entity)-[r:REL]->(l:Entity {key: $loserKey})';
  const res = await session.run(
    `MATCH ${loserPattern} WHERE o.key <> $keeperKey AND o.ontologyId = $ontologyId `
      + 'RETURN r.type AS type, o.key AS otherKey, coalesce(r.sourceChunkIds, []) AS sourceChunkIds',
    { loserKey, keeperKey, ontologyId },
  );
  // Cypher의 WHERE o.key <> $keeperKey로 자기참조는 이미 걸러지지만, 방어적으로 한 번 더 제외한다.
  const rels: RelRow[] = res.records
    .map((r) => ({ type: r.get('type'), otherKey: r.get('otherKey'), sourceChunkIds: r.get('sourceChunkIds') }))
    .filter((r: RelRow) => r.otherKey !== keeperKey);

  const keeperPattern = direction === 'out'
    ? '(k)-[x:REL {type: $type}]->(o)'
    : '(o)-[x:REL {type: $type}]->(k)';
  for (const r of rels) {
    await session.run(
      'MATCH (k:Entity {key: $keeperKey}), (o:Entity {key: $otherKey}) '
        + `WHERE o.ontologyId = $ontologyId MERGE ${keeperPattern} ${ACCUMULATE_CHUNKS}`,
      { keeperKey, otherKey: r.otherKey, type: r.type, sourceChunkIds: r.sourceChunkIds, ontologyId },
    );
  }
}
