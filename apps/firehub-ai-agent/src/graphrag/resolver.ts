// 추출된 엔티티를 정규화 키로 병합하고, 관계를 키 기반으로 재작성한다(엔티티 해소).
import { ExtractionResult, EntityType, RelationType, Ontology, entityTypeId } from './ontology.js';

export interface ResolvedEntity { key: string; type: EntityType; name: string; properties?: Record<string, number | string>; sourceChunkIds?: number[]; }
export interface ResolvedRelation { subjectKey: string; type: RelationType; objectKey: string; }
export interface ResolvedGraph { entities: ResolvedEntity[]; relations: ResolvedRelation[]; }

// 앞뒤 공백 제거 + 연속 공백 1칸 + 소문자화. 표기 변형을 흡수하는 최소 정규화.
export function normalizeName(name: string): string {
  return name.trim().replace(/\s+/g, ' ').toLowerCase();
}
// 엔티티 병합 키 = "<entity_type_id>:<정규화이름>"(5-6). Neo4j 노드 유일성 기준.
// 타입 "문자열"이 아니라 api가 보존하는 안정적 id를 쓰므로, 타입 리네임이 일어나도 이 key는
// 바뀌지 않는다(리네임은 이제 순수 DB 연산 — OntologyRepository 참조).
export function entityKey(typeId: number, name: string): string {
  return `${typeId}:${normalizeName(name)}`;
}

export function resolveExtraction(extraction: ExtractionResult, ontology: Ontology): ResolvedGraph {
  // 이름(원표기)→키, 키→정규 엔티티. 첫 등장 표기를 대표 name으로 보존.
  const byKey = new Map<string, ResolvedEntity>();
  const keyByName = new Map<string, string>();
  for (const e of extraction.entities) {
    const key = entityKey(entityTypeId(ontology, e.type), e.name);
    keyByName.set(e.name, key);
    // 최초 등장 엔티티만 대표로 등록 — 속성값도 최초 등장분을 전달(정규화·해소는 여기서 안 함).
    if (!byKey.has(key)) byKey.set(key, {
      key, type: e.type, name: e.name.trim().replace(/\s+/g, ' '),
      ...(e.properties ? { properties: e.properties } : {}),
    });
  }
  // 관계를 키로 재작성 + (subjectKey,type,objectKey) 중복 제거.
  const relSet = new Set<string>();
  const relations: ResolvedRelation[] = [];
  for (const r of extraction.relations) {
    const sk = keyByName.get(r.subject), ok = keyByName.get(r.object);
    if (!sk || !ok) continue;
    const dedup = `${sk}|${r.type}|${ok}`;
    if (relSet.has(dedup)) continue;
    relSet.add(dedup);
    relations.push({ subjectKey: sk, type: r.type, objectKey: ok });
  }
  return { entities: [...byKey.values()], relations };
}
