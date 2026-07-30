// HITL 승인된 정정 속성값을 Neo4j 엔티티 노드에 write한다(속성 정규화 검수 승인).
// loader.ts와 동일한 예약키 방어 — 정체성 필드를 덮어써 노드를 깨뜨리지 않게 한다.
import { getSession } from './neo4j-client.js';
import { GraphTargetMissingError, affectedCount } from './graph-mutation-guard.js';

const RESERVED_NODE_KEYS = new Set(['key', 'type', 'name', 'sourceChunkIds', 'schemaVersion']);

/**
 * entityKey로 식별되는 노드의 propertyName 속성값을 정정값으로 설정한다.
 * number 타입은 숫자로 강제(폼 입력은 문자열), 그 외는 문자열 그대로 저장한다.
 * 예약 속성명은 거부한다.
 * 대상 노드가 없으면(MATCH 미스) 예전에는 조용히 no-op이었고, 호출측은 이를 성공으로 보고 검수 항목을
 * approved로 바꿔 정정 내용이 유실됐다(#310). 이제 반영 건수 0이면 실패로 전파한다.
 */
export async function setEntityProperty(
  entityKey: string, propertyName: string, dataType: 'text' | 'number' | 'date', value: string,
): Promise<void> {
  if (RESERVED_NODE_KEYS.has(propertyName)) {
    throw new Error(`예약된 속성명은 변경할 수 없습니다: ${propertyName}`);
  }
  let coerced: number | string = value;
  if (dataType === 'number') {
    const n = Number(value);
    if (Number.isNaN(n)) throw new Error(`number 속성에 숫자가 아닌 정정값: ${value}`);
    coerced = n;
  }
  // 예약키 방어를 통과한 propertyName만으로 병합 맵을 구성한다(동적 키를 JS측에서 안전하게 결정).
  const props: Record<string, number | string> = { [propertyName]: coerced };
  const session = getSession();
  try {
    // loader.ts와 동일한 맵-병합 관용구(SET n += $props) — Neo4j 버전 무관, 주입 안전.
    const result = await session.run(
      'MATCH (n:Entity {key: $key}) SET n += $props RETURN count(n) AS updated',
      { key: entityKey, props },
    );
    // 판정 기준은 "MATCH가 노드를 찾았는가"다. 정정값이 기존 값과 같아 실제 write가 없어도 성공이어야 한다.
    if (affectedCount(result, 'updated') === 0) {
      throw new GraphTargetMissingError(
        `대상 엔티티가 그래프에 없어 속성을 정정할 수 없습니다(key=${entityKey}).`,
      );
    }
  } finally {
    await session.close();
  }
}
