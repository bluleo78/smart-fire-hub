// 실 Neo4j 통합 테스트가 공유하는 픽스처.
//
// ⚠️ 통합 테스트는 **격리된 테스트 DB 가 없다** — 개발자들이 실제로 쓰는 dev Neo4j 를 그대로 쓴다.
// 그래서 규약이 하나 있다: 이름이 `ZZTEST_` 로 시작하는 노드만 만들고, 정리도 그 접두사로만 한정한다.
// `MATCH (n:Entity) DETACH DELETE n` 같은 전체 삭제는 dev 그래프를 통째로 날린다(실제로 여러 번 겪었다).
//
// 왜 모듈로 뽑는가: 이 접두사 조건이 안전장치 전부인데, 그동안 파일마다 문자열을 다시 타이핑했다.
// 한 곳에서 `STARTS WITH` 를 빠뜨리거나 접두사를 `ZZTEST` 로 잘못 쓰면 그 파일 하나가 dev 데이터를
// 지우고, 고칠 곳이 한 군데가 아니라서 눈에도 잘 띄지 않는다.
import neo4j from 'neo4j-driver';
import { getSession } from '../neo4j-client.js';
import type { VerifiedOntologyId } from '../verified-ontology-id.js';

/** 통합 테스트가 만드는 모든 노드 이름의 접두사. 정리 조건과 짝을 이룬다. */
export const TEST_NAME_PREFIX = 'ZZTEST_';

/** 접두사 노드만 지운다 — 이 문자열이 dev 그래프를 지키는 유일한 장치다. */
const CLEANUP_CYPHER =
  `MATCH (n:Entity) WHERE n.name STARTS WITH '${TEST_NAME_PREFIX}' DETACH DELETE n`;

/** 이 테스트 실행이 만든 노드만 정리한다(beforeAll/afterEach 에서 호출). */
export async function cleanupMarked(): Promise<void> {
  const session = getSession();
  try { await session.run(CLEANUP_CYPHER); } finally { await session.close(); }
}

/**
 * 테스트용 엔티티 노드를 심는다.
 *
 * ontologyId 를 **반드시** 스탬프한다 — 실 적재 경로(loader/entity-add)가 항상 찍는 값이라,
 * 픽스처가 빠뜨리면 스코프 술어가 코드 결함이 아니라 픽스처 결함으로 항상 거짓이 되고
 * "차단됐다"가 아무것도 증명하지 못한다.
 *
 * type 은 온톨로지 트리플 검증(#319)의 입력이므로 호출측이 명시한다.
 */
export async function makeNode(
  key: string, name: string, type: string, ontologyId: number | VerifiedOntologyId,
): Promise<string> {
  const session = getSession();
  try {
    await session.run(
      'MERGE (n:Entity {key:$key}) SET n.name=$name, n.type=$type, n.ontologyId=$oid',
      { key, name, type, oid: neo4j.int(ontologyId) },
    );
  } finally { await session.close(); }
  return key;
}

/** key 로 지목한 노드가 존재하는가. */
export async function nodeExists(key: string): Promise<boolean> {
  const session = getSession();
  try {
    const r = await session.run('MATCH (n:Entity {key:$key}) RETURN count(n) AS c', { key });
    return r.records[0].get('c').toNumber() > 0;
  } finally { await session.close(); }
}

/** a -> b 방향 REL 엣지 개수. 전역 카운트가 아니라 키 단위라 다른 테스트의 잔여에 흔들리지 않는다. */
export async function edgeCount(fromKey: string, toKey: string): Promise<number> {
  const session = getSession();
  try {
    const r = await session.run(
      'MATCH (a:Entity {key:$a})-[x:REL]->(b:Entity {key:$b}) RETURN count(x) AS c',
      { a: fromKey, b: toKey },
    );
    return r.records[0].get('c').toNumber();
  } finally { await session.close(); }
}
