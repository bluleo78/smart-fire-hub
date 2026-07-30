// HITL 승인 적재가 "아무 것도 반영하지 못한 채 성공"으로 보고되는 무음 유실을 막는 공용 가드(#310).
//
// 왜 필요한가: Cypher `MATCH (a), (b) MERGE ...`는 MATCH가 0행이면 에러 없이 통째로 no-op이다.
// 검수 승인 경로는 그 결과를 확인하지 않고 status='approved'로 바꿔버려, 검수자에게는 성공 토스트가 뜨지만
// 그래프에는 아무 것도 없고 항목은 인박스에서 사라져 되돌릴 방법이 없었다.
// 따라서 각 mutation은 "MATCH가 대상을 바인딩했는가"를 RETURN count(...)로 확인하고,
// 0이면 GraphTargetMissingError를 던져 상위(라우트 → firehub-api → UI)로 실패를 전파한다.

/**
 * 그래프 변경 대상 노드가 Neo4j에 없어 승인 적재를 반영할 수 없는 상태.
 * 일반 장애(502)와 구분해야 하므로 별도 타입으로 둔다 — 라우트가 이 타입만 409로 매핑한다.
 */
export class GraphTargetMissingError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GraphTargetMissingError';
  }
}

/** session.run() 결과 중 이 가드가 쓰는 최소 형태(테스트에서 세션을 모킹하기 쉽도록 구조적 타입). */
export interface CountableResult {
  records: { get(key: string): unknown }[];
}

/**
 * `RETURN count(x) AS <field>` 결과에서 반영 건수를 읽는다.
 *
 * count 집계는 MATCH가 0행이어도 "0을 담은 1행"을 반드시 돌려주므로, 행 자체가 없거나 필드가 없다면
 * 그건 대상 부재가 아니라 쿼리/필드명이 틀린 코딩 오류다. 이 경우를 0으로 뭉뚱그리면
 * "엔티티가 그래프에 없습니다"라는 그럴듯하지만 틀린 진단이 사용자에게 나가므로, 일반 Error로 구분해 던진다
 * (라우트에서 502 + 로그로 떨어져 원인 추적이 가능해진다).
 */
export function affectedCount(result: CountableResult, field: string): number {
  const row = result.records[0];
  if (!row) {
    throw new Error(`그래프 변경 결과에 집계 행이 없습니다(field=${field}) — Cypher RETURN 절을 확인하세요.`);
  }
  const value = row.get(field);
  if (value === null || value === undefined) {
    throw new Error(`그래프 변경 결과에 집계 필드가 없습니다(field=${field}) — Cypher RETURN 별칭을 확인하세요.`);
  }
  // neo4j-driver는 기본적으로 Integer 객체를 돌려주지만, 테스트 모킹 등에서는 plain number가 올 수 있다.
  if (typeof value === 'number') return value;
  return (value as { toNumber(): number }).toNumber();
}
