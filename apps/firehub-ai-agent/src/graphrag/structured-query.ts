// 구조적 질의: 단일 엔티티 타입을 타입값 술어로 필터한다(1-hop·집계 없음).
// 인젝션 방지: 속성명은 온톨로지 화이트리스트로만(백틱 인용), 값은 파라미터 바인딩.
import neo4j from 'neo4j-driver';
import { getSession } from './neo4j-client.js';
import { Ontology, isEntityType, EntityType } from './ontology.js';

export type Operator = 'gt' | 'gte' | 'lt' | 'lte' | 'eq' | 'neq' | 'contains';
export interface Filter { property: string; operator: Operator; value: number | string; }

const OP_CYPHER: Record<Exclude<Operator, 'contains'>, string> = {
  gt: '>', gte: '>=', lt: '<', lte: '<=', eq: '=', neq: '<>',
};
const MAX_RESULTS = 100;

// 화이트리스트 검증 후 Cypher WHERE 를 조립한다. 검증 실패 시 { error }.
export function buildStructuredCypher(
  ontology: Ontology, entityType: string, filters: Filter[],
): { cypher: string; params: Record<string, unknown> } | { error: string } {
  if (!isEntityType(ontology, entityType)) return { error: `알 수 없는 엔티티 타입: ${entityType}` };
  const def = ontology.entities.find((e) => e.type === (entityType as EntityType));
  const allowed = new Map((def?.properties ?? []).map((p) => [p.name, p.dataType]));

  // cap 은 Cypher LIMIT 에 쓰이므로 반드시 Neo4j INTEGER 로 바인딩한다.
  // (JS number 를 그대로 넘기면 드라이버가 Float 로 패킹 → LIMIT 이 INTEGER 를 요구해 런타임 에러.)
  const params: Record<string, unknown> = { entityType, cap: neo4j.int(MAX_RESULTS) };
  const preds: string[] = [];
  for (let i = 0; i < filters.length; i++) {
    const f = filters[i];
    if (!allowed.has(f.property)) return { error: `필터 불가 속성: ${f.property}` };
    const col = '`' + f.property.replace(/`/g, '') + '`'; // 백틱 인용, 백틱 제거로 이스케이프
    const pk = `p${i}`;
    params[pk] = f.value;
    if (f.operator === 'contains') preds.push(`n.${col} CONTAINS $${pk}`);
    else preds.push(`n.${col} ${OP_CYPHER[f.operator]} $${pk}`);
  }
  const where = ['n.type = $entityType', ...preds].join(' AND ');
  const cypher =
    `MATCH (n:Entity) WHERE ${where} ` +
    `RETURN n.key AS key, n.type AS type, n.name AS name, properties(n) AS props, ` +
    `n.sourceChunkIds AS sourceChunkIds LIMIT $cap`;
  return { cypher, params };
}

export interface StructuredResult {
  entities: { key: string; type: string; name: string; properties: Record<string, unknown> }[];
  sourceChunkIds: number[];
  truncated: boolean;
}

// Cypher 를 실행해 매칭 엔티티 + 출처 청크 id 를 반환한다.
export async function structuredQuery(
  ontology: Ontology, entityType: string, filters: Filter[],
): Promise<StructuredResult> {
  const built = buildStructuredCypher(ontology, entityType, filters);
  if ('error' in built) throw new Error(built.error);
  const session = getSession();
  try {
    const res = await session.run(built.cypher, built.params);
    const chunkIds = new Set<number>();
    const entities = res.records.map((rec) => {
      const props = rec.get('props') as Record<string, unknown>;
      // 내부 필드 제거 → 온톨로지 속성만 노출.
      const { key: _key, type: _type, name: _name, sourceChunkIds: _sourceChunkIds, ...rest } = props;
      // Neo4j Integer(number 속성은 Long 저장) → JS number 로 변환(retriever 의 Number(degree) 선례). 그 외 타입은 그대로.
      const properties = Object.fromEntries(
        Object.entries(rest).map(([k, v]) => [k, neo4j.isInt(v) ? (v as { toNumber(): number }).toNumber() : v]),
      );
      (rec.get('sourceChunkIds') as number[] | null ?? []).forEach((c) => chunkIds.add(Number(c)));
      return { key: rec.get('key'), type: rec.get('type'), name: rec.get('name'), properties };
    });
    return { entities, sourceChunkIds: [...chunkIds], truncated: entities.length >= MAX_RESULTS };
  } finally { await session.close(); }
}
